// Plan §4.1 module map: `worker/dsp.ts` ("vertical/jerk/rollstd recompute").
// Pure, no DOM. Mirrors `GravityEstimator.kt` -> `Verticalizer.kt` (produces
// `vertical`) -> `JerkFilter.kt` (`jerk`) and `RollingStats.kt` (`rollStd`,
// the same rolling-std module `worker/detector.ts` uses, but here at
// `RecordingPipeline.kt`'s default window of 20 samples, driven by every
// sample rather than the detector's two purpose-tuned windows).
//
// Recomputing from raw `accel_x/y/z` independently verifies the on-device
// pipeline rather than trusting `sensor.csv`'s own `vertical_ms2` column —
// AC3/D8's "recomputed magnitude ... flag > 10% peak divergence".

import type { Ns, Segment } from '../core/types';

export const GRAVITY_BASELINE_MS2 = 9.81;

export interface RecomputedChannels {
  vertical: Float64Array;
  jerk: Float64Array;
  rollStd: Float64Array;
}

/** `GravityEstimator.kt`: exponential low-pass estimate of the gravity
 * vector (`alpha` = previous-estimate weight, default 0.90), then
 * `Verticalizer.kt` projects the raw sample onto that gravity direction —
 * together, "vertical acceleration invariant to phone orientation". First
 * sample seeds the estimate directly (`sampleCount == 0` branch in
 * `GravityEstimator.update`), matching Kotlin exactly rather than starting
 * from a zero vector. */
export function recomputeVertical(
  ax: Float64Array,
  ay: Float64Array,
  az: Float64Array,
  alpha = 0.9,
): Float64Array {
  const n = ax.length;
  const vertical = new Float64Array(n);
  let gx = 0;
  let gy = 0;
  let gz = 0;
  for (let i = 0; i < n; i++) {
    const x = ax[i] as number;
    const y = ay[i] as number;
    const z = az[i] as number;
    if (i === 0) {
      gx = x;
      gy = y;
      gz = z;
    } else {
      gx = alpha * gx + (1 - alpha) * x;
      gy = alpha * gy + (1 - alpha) * y;
      gz = alpha * gz + (1 - alpha) * z;
    }
    const norm = Math.sqrt(gx * gx + gy * gy + gz * gz);
    vertical[i] = norm < 1e-6 ? 0 : (x * gx + y * gy + z * gz) / norm;
  }
  return vertical;
}

/** `JerkFilter.kt`: finite-difference derivative over a `windowSamples`-wide
 * buffer (default 4, `dtSeconds` default 0.01 for 100 Hz) — `null` (`NaN`
 * here, matching how `sensor.csv`'s own empty `jerk_ms3` cells parse) until
 * the window first fills, exactly like the Kotlin `push` returning `null`
 * for the first `windowSamples` calls. */
export function recomputeJerk(vertical: Float64Array, windowSamples = 4, dtSeconds = 0.01): Float64Array {
  const n = vertical.length;
  const jerk = new Float64Array(n);
  const denom = windowSamples * dtSeconds;
  for (let i = 0; i < n; i++) {
    if (i < windowSamples) {
      jerk[i] = NaN;
    } else {
      const first = vertical[i - windowSamples] as number;
      const last = vertical[i] as number;
      jerk[i] = (last - first) / denom;
    }
  }
  return jerk;
}

/** `RollingStats.kt` at `RecordingPipeline.kt`'s default window (20
 * samples) — population std of `vertical` over the trailing window,
 * matching `roll_std_dev` in `sensor.csv`. Ring-buffer implementation, same
 * push-evict semantics as `worker/detector.ts`'s `RollingStatsJs` (kept as
 * a private duplicate here rather than exported/shared — the two modules'
 * only common dependency the plan names is the *algorithm*, not a shared
 * class; §4.2 lists no seam between them). */
export function recomputeRollingStd(vertical: Float64Array, windowSamples = 20): Float64Array {
  const n = vertical.length;
  const out = new Float64Array(n);
  const buf = new Float64Array(windowSamples);
  let head = 0;
  let count = 0;
  let sum = 0;
  let sumSq = 0;
  for (let i = 0; i < n; i++) {
    const value = vertical[i] as number;
    if (count < windowSamples) {
      buf[count] = value;
      count++;
      sum += value;
      sumSq += value * value;
    } else {
      const removed = buf[head] as number;
      buf[head] = value;
      head = (head + 1) % windowSamples;
      sum += value - removed;
      sumSq += value * value - removed * removed;
    }
    const mean = sum / count;
    out[i] = Math.sqrt(Math.max(sumSq / count - mean * mean, 0));
  }
  return out;
}

/** All three recomputed channels in one pass, for `worker/index.ts`'s
 * import pipeline (§4.4: `parse -> recompute -> lod`). */
export function recompute(ax: Float64Array, ay: Float64Array, az: Float64Array): RecomputedChannels {
  const vertical = recomputeVertical(ax, ay, az);
  return {
    vertical,
    jerk: recomputeJerk(vertical),
    rollStd: recomputeRollingStd(vertical),
  };
}

export interface Divergence {
  segId: Segment['id'];
  recordedPeak: number;
  recomputedPeak: number;
}

/** First index `i` with `tNs[i] >= v` — a plain binary search kept local to
 * this module rather than imported from `core/stats.ts` (§5.3's
 * `lowerBound`), which doesn't exist until a later ticket (rule 0.5). */
function lowerBoundNs(tNs: BigInt64Array, v: Ns): number {
  let lo = 0;
  let hi = tNs.length;
  while (lo < hi) {
    const mid = (lo + hi) >>> 1;
    if ((tNs[mid] as bigint) < v) lo = mid + 1;
    else hi = mid;
  }
  return lo;
}

/**
 * One entry per segment: the recorded `peak_m` next to
 * `max(abs(recomputedVertical - 9.81))` over the segment's own
 * `[startNs, startNs + durationNs)` window. Every segment gets an entry —
 * AC5 leaves ">10% flagged" to the caller (`core/queue.ts`'s
 * `peak-divergence` review item, a later ticket), not to this function.
 */
export function divergence(segments: readonly Segment[], tNs: BigInt64Array, recomputedVertical: Float64Array): Divergence[] {
  const n = tNs.length;
  return segments.map((seg) => {
    const startIdx = lowerBoundNs(tNs, seg.startNs);
    const endIdx = lowerBoundNs(tNs, seg.startNs + seg.durationNs);
    let recomputedPeak = 0;
    for (let i = startIdx; i < endIdx && i < n; i++) {
      const magnitude = Math.abs((recomputedVertical[i] as number) - GRAVITY_BASELINE_MS2);
      if (magnitude > recomputedPeak) recomputedPeak = magnitude;
    }
    return { segId: seg.id, recordedPeak: seg.peakM, recomputedPeak };
  });
}
