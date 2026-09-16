// Plan §5.7. Pure, no DOM. Kotlin-exact mirror of
// `NoiseFloorCalibrator.kt` / `SegmentDetector.kt` / `RecordingPipeline.kt`
// (com.aitken.segment / com.aitken.recording), read directly from the
// Android source rather than re-derived from the plan's prose alone (rule
// 0.2's "expected values ... come from ... the Kotlin source").
//
// Kotlin's `RollingStats` windows are *handed over* from the calibrator to
// the detector (`RecordingPipeline.kt:126-128`: `SegmentDetector(shortWindow
// = calibrator.shortWindow, ...)`) — the same window instances carry real
// history across the calibration/detection boundary, no cold-start gap.
// `calibrate` and `simulate` are kept as two separate pure functions per
// §5.7's signatures rather than one stateful object, so `simulate`
// reconstructs that same window state itself by replaying
// `vertical[0..calibEndIdx]` through fresh windows before it starts
// detecting — deterministic, and it means `calibrate`'s `calibEndIdx` is the
// only handoff `simulate` needs (not the windows themselves, which aren't a
// type this module's public interface has to expose).
//
// Float32 vs Float64 (§5.7): Kotlin's `RollingStats`/`SegmentDetector` use
// `Float`. This mirror uses plain `Float64` (JS `number`) arithmetic
// throughout. Verified against the real fixture in
// `test/unit/worker/detector.test.ts` — thresholds within 1e-3, segment
// count and start times exact — so the `Math.fround`-at-every-step fallback
// D17 allows for was not needed.

import type { Ns } from '../core/types';

/** `abs(vertical - GRAVITY_BASELINE_MS2)` is what `SegmentDetector.kt`
 * calls "deviation from resting gravity" (its own doc comment, quoting the
 * ride-data-analysis bug this fixed) — the same constant `worker/dsp.ts`
 * uses for `divergence`. Duplicated here rather than imported: `core/stats.ts`
 * (§5.3), the module that will eventually own this constant, isn't built
 * until a later ticket (rule 0.5 — no reaching across a seam that doesn't
 * exist yet). Revisit when `core/stats.ts` lands. */
export const GRAVITY_BASELINE_MS2 = 9.81;

/** `RollingStats(windowSamples = 20)` / `RollingStats(windowSamples = 3000)`
 * in `RecordingPipeline.kt` (the short/long windows the calibrator and
 * detector share) and in `NoiseFloorCalibrator.kt`'s own constructor
 * defaults. */
export const SHORT_WINDOW_SAMPLES = 20;
export const LONG_WINDOW_SAMPLES = 3000;

export interface DetectionTunables {
  calibrationDurationMs: number;
  stdFactor: number;
  floorStd: number;
  endQuietMs: number;
  minSegmentDurationMs: number;
  turnYawThresholdRadS: number;
}

export interface Calibration {
  shortStdThreshold: number;
  longStdThreshold: number;
  /** Index of the last sample consumed by calibration (inclusive). The
   * detector state machine in `simulate` starts at `calibEndIdx + 1`.
   * `calibEndIdx === vertical.length - 1` (with `n > 0`) means calibration
   * never finished — E-37, "session shorter than the calibration window" —
   * and `simulate` correctly produces zero segments since nothing is left
   * to detect over (there is no index `n`, so `calibEndIdx` never exceeds
   * `n - 1`, but the caller can detect E-37 the same way this module's own
   * tests do: comparing `calibEndIdx` to `vertical.length - 1`). */
  calibEndIdx: number;
}

/** Rolling mean and population standard deviation over a fixed window —
 * mirrors `RollingStats.kt` exactly (push-newest, evict-oldest-once-over-
 * capacity; population variance, `coerceAtLeast(0f)` clamp against negative
 * rounding error). A ring buffer (not a real deque, unlike the Kotlin
 * `ArrayDeque`) since the long window is 3000 samples and this runs once
 * per sensor sample — an `Array#shift()`-based deque would be O(n) per push
 * at that size. */
class RollingStatsJs {
  private readonly buf: Float64Array;
  private head = 0;
  private count = 0;
  private sum = 0;
  private sumSq = 0;

  constructor(private readonly windowSamples: number) {
    this.buf = new Float64Array(windowSamples);
  }

  push(value: number): { mean: number; std: number } {
    if (this.count < this.windowSamples) {
      this.buf[this.count] = value;
      this.count++;
      this.sum += value;
      this.sumSq += value * value;
    } else {
      const removed = this.buf[this.head] as number;
      this.buf[this.head] = value;
      this.head = (this.head + 1) % this.windowSamples;
      this.sum += value - removed;
      this.sumSq += value * value - removed * removed;
    }
    const n = this.count;
    const mean = this.sum / n;
    const variance = Math.max(this.sumSq / n - mean * mean, 0);
    return { mean, std: Math.sqrt(variance) };
  }
}

/** `threshold(std) = max(max(std, floorStd) * stdFactor, floorStd)` — the
 * private `threshold()` helper shared by `shortStdThreshold()`/
 * `longStdThreshold()` in `NoiseFloorCalibrator.kt`. */
function threshold(measuredStd: number, t: Pick<DetectionTunables, 'stdFactor' | 'floorStd'>): number {
  const flooredNoiseStd = Math.max(measuredStd, t.floorStd);
  return Math.max(flooredNoiseStd * t.stdFactor, t.floorStd);
}

/** `calibrationDurationMs` elapsed as of sample `i`, using the *first*
 * sample's timestamp as `startNs` — `NoiseFloorCalibrator.kt`'s `isDone`.
 * Integer-ms (bigint) division, matching Kotlin `Long` division. */
function elapsedMs(tNs: BigInt64Array, startNs: Ns, i: number): bigint {
  return ((tNs[i] as bigint) - startNs) / 1_000_000n;
}

/**
 * Replays the calibration window: pushes `vertical[0..calibrationDurationMs
 * elapsed]` through short/long `RollingStatsJs`, "push-then-check" per
 * `NoiseFloorCalibrator.push` (the sample that crosses the duration
 * boundary is itself included in the windows before `isDone` is checked).
 */
export function calibrate(vertical: Float64Array, tNs: BigInt64Array, t: DetectionTunables): Calibration {
  const n = vertical.length;
  if (n === 0) {
    return { shortStdThreshold: threshold(0, t), longStdThreshold: threshold(0, t), calibEndIdx: -1 };
  }
  const shortWindow = new RollingStatsJs(SHORT_WINDOW_SAMPLES);
  const longWindow = new RollingStatsJs(LONG_WINDOW_SAMPLES);
  const startNs = tNs[0] as bigint;
  const durationMs = BigInt(t.calibrationDurationMs);

  let lastShortStd = 0;
  let lastLongStd = 0;
  let calibEndIdx = n - 1; // E-37 default: never crossed the duration, calibration consumed the whole session

  for (let i = 0; i < n; i++) {
    lastShortStd = shortWindow.push(vertical[i] as number).std;
    lastLongStd = longWindow.push(vertical[i] as number).std;
    if (elapsedMs(tNs, startNs, i) >= durationMs) {
      calibEndIdx = i;
      break;
    }
  }

  return {
    shortStdThreshold: threshold(lastShortStd, t),
    longStdThreshold: threshold(lastLongStd, t),
    calibEndIdx,
  };
}

/** `turning = |gz| >= turnYawThresholdRadS` when `gz` is finite, else
 * `false` — covers both "no gyro column at all" (`gz === null`, the fixture
 * case: `sensor.csv` with every `gyro_*` field empty parses to `NaN`
 * throughout) and a single empty/`NaN` cell within an otherwise-present
 * column. */
function isTurning(gz: Float64Array | null, i: number, thresholdRadS: number): boolean {
  if (gz === null) return false;
  const v = gz[i] as number;
  return Number.isFinite(v) && Math.abs(v) >= thresholdRadS;
}

/**
 * Runs the `SegmentDetector.kt` state machine from `calibEndIdx + 1` to the
 * end of the array, using fresh `RollingStatsJs` windows replayed through
 * `[0, calibEndIdx]` first so they carry the same history calibration left
 * them with (see module doc comment) — `calibrate`'s own windows aren't
 * returned across the function boundary, so this reconstructs them instead
 * of receiving them, which keeps both functions pure per §5.7's signatures.
 */
export function simulate(
  vertical: Float64Array,
  tNs: BigInt64Array,
  gz: Float64Array | null,
  t: DetectionTunables,
  c: Calibration,
): Array<{ startNs: Ns; durationNs: Ns; peakM: number; rmsM: number }> {
  const n = vertical.length;
  const out: Array<{ startNs: Ns; durationNs: Ns; peakM: number; rmsM: number }> = [];
  if (n === 0 || c.calibEndIdx >= n - 1) return out; // E-37 or empty: nothing left to detect

  const shortWindow = new RollingStatsJs(SHORT_WINDOW_SAMPLES);
  const longWindow = new RollingStatsJs(LONG_WINDOW_SAMPLES);
  for (let i = 0; i <= c.calibEndIdx; i++) {
    shortWindow.push(vertical[i] as number);
    longWindow.push(vertical[i] as number);
  }

  // A boolean, not a two-state string union: TS's control-flow narrowing
  // over a `let` reassigned inside nested closures (`open`/`close`) gets
  // confused by the string-union version and statically (wrongly) narrows
  // `phase` to a single literal after the loop. A boolean sidesteps it.
  let isOpen = false;
  let startNs: Ns = 0n;
  let lastSignalNs: Ns = 0n;
  let peakM = 0;
  let sumSq = 0;
  let signalSamples = 0;
  const endQuietMs = BigInt(t.endQuietMs);
  const minSegmentDurationMs = BigInt(t.minSegmentDurationMs);

  function accumulate(v: number): void {
    const magnitude = Math.abs(v - GRAVITY_BASELINE_MS2);
    if (magnitude > peakM) peakM = magnitude;
    sumSq += magnitude * magnitude;
    signalSamples++;
  }

  function open(i: number): void {
    isOpen = true;
    startNs = tNs[i] as bigint;
    lastSignalNs = startNs;
    peakM = 0;
    sumSq = 0;
    signalSamples = 0;
    accumulate(vertical[i] as number);
  }

  function close(): void {
    const durationNs = lastSignalNs - startNs;
    const durationMs = durationNs / 1_000_000n;
    if (durationMs >= minSegmentDurationMs) {
      out.push({
        startNs,
        durationNs,
        peakM,
        rmsM: signalSamples > 0 ? Math.sqrt(sumSq / signalSamples) : 0,
      });
    }
    isOpen = false;
  }

  for (let i = c.calibEndIdx + 1; i < n; i++) {
    const shortStd = shortWindow.push(vertical[i] as number).std;
    const longStd = longWindow.push(vertical[i] as number).std;
    const anySignal = shortStd >= c.shortStdThreshold || longStd >= c.longStdThreshold;
    const turning = isTurning(gz, i, t.turnYawThresholdRadS);

    if (!isOpen) {
      if (anySignal && !turning) open(i);
    } else {
      if (anySignal) {
        lastSignalNs = tNs[i] as bigint;
        accumulate(vertical[i] as number);
      } else {
        const quietMs = ((tNs[i] as bigint) - lastSignalNs) / 1_000_000n;
        if (quietMs >= endQuietMs) close();
      }
    }
  }

  // End of data force-closes an open segment (`SegmentDetector.endSession`).
  if (isOpen) close();

  return out;
}

/**
 * `1` where `shortStd >= c.shortStdThreshold || longStd >= c.longStdThreshold`,
 * run over the *whole* array with fresh windows starting at index 0 — a
 * separate, independent pass from `simulate`'s handed-over-from-calibration
 * windows (§5.7's "running RollingStats from index 0 (after calibration
 * reset)"), so the mask covers the calibration span too, for display/
 * inspection rather than for driving the state machine.
 */
export function signalMask(vertical: Float64Array, c: Calibration): Uint8Array {
  const n = vertical.length;
  const mask = new Uint8Array(n);
  const shortWindow = new RollingStatsJs(SHORT_WINDOW_SAMPLES);
  const longWindow = new RollingStatsJs(LONG_WINDOW_SAMPLES);
  for (let i = 0; i < n; i++) {
    const shortStd = shortWindow.push(vertical[i] as number).std;
    const longStd = longWindow.push(vertical[i] as number).std;
    mask[i] = shortStd >= c.shortStdThreshold || longStd >= c.longStdThreshold ? 1 : 0;
  }
  return mask;
}
