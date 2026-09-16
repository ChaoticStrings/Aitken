import { describe, expect, it } from 'vitest';
import { readFileSync } from 'node:fs';
import { calibrate, simulate, signalMask, type DetectionTunables } from '../../../src/worker/detector';
import { loadFixtureFiles, fixturesRoot } from '../../helpers/fixtures';

// ---- helpers ---------------------------------------------------------------

function parseSensorForDetector(files: Map<string, string>) {
  const text = files.get('sensor.csv') as string;
  const lines = text.split(/\r\n|\n/).filter((l) => l.length > 0);
  const columns = (lines[0] as string).split(',');
  const idx = new Map<string, number>();
  columns.forEach((c, i) => idx.set(c, i));
  const iT = idx.get('timestamp_sensor_ns') as number;
  const iGz = idx.get('gyro_z_rads') as number;
  const iV = idx.get('vertical_ms2') as number;
  const rows = lines.slice(1).map((l) => l.split(','));
  const n = rows.length;
  const tNs = new BigInt64Array(n);
  const vertical = new Float64Array(n);
  const gz = new Float64Array(n);
  let gzPresent = false;
  for (let i = 0; i < n; i++) {
    const row = rows[i] as string[];
    tNs[i] = BigInt(row[iT] as string);
    vertical[i] = Number(row[iV]);
    const g = row[iGz] as string;
    gz[i] = g === '' ? NaN : Number(g);
    if (g !== '') gzPresent = true;
  }
  return { tNs, vertical, gz: gzPresent ? gz : null };
}

function tunablesFromConfig(configJson: string): DetectionTunables {
  const c = JSON.parse(configJson);
  return {
    calibrationDurationMs: c.calibrationDurationMs,
    stdFactor: c.stdFactor,
    floorStd: c.floorStd,
    endQuietMs: c.endQuietMs,
    minSegmentDurationMs: c.minSegmentDurationMs,
    turnYawThresholdRadS: c.turnYawThresholdRadS,
  };
}

const ns = (ms: number): bigint => BigInt(ms) * 1_000_000n;

// ---- calibrate ---------------------------------------------------------------

describe('calibrate', () => {
  it('calibration thresholds match config.json within 1e-3', () => {
    // The trimmed fixture's first `calibrationDurationMs` (10s) of sensor
    // rows are byte-identical to the real, untrimmed session's first 10s
    // (make-fixture.mjs only filters rows by a *later* cutoff, ~90s) — so
    // config.json's on-device-computed `calibratedShortStdThreshold` /
    // `calibratedLongStdThreshold`, though recorded against the full
    // session, are reproducible from the trimmed fixture alone. AC1's "run
    // against the full session first" step and "freeze the trimmed
    // fixture's own values" step collapse into the same verification here.
    const files = loadFixtureFiles('session_trimmed_90s');
    const { tNs, vertical } = parseSensorForDetector(files);
    const t = tunablesFromConfig(files.get('config.json') as string);
    const c = calibrate(vertical, tNs, t);
    expect(Math.abs(c.shortStdThreshold - 3.64617)).toBeLessThan(1e-3);
    expect(Math.abs(c.longStdThreshold - 5.0351295)).toBeLessThan(1e-3);
    // Frozen literal, independently written by scripts/compute-fixture-expected
    // (see expected/calibration.json) — matches this run to floating-point
    // equality, not just the 1e-3 the config-derived AC asks for.
    const expected = JSON.parse(readFileSync(`${fixturesRoot}/session_trimmed_90s/expected/calibration.json`, 'utf-8'));
    expect(c.shortStdThreshold).toBeCloseTo(expected.shortStdThreshold, 9);
    expect(c.longStdThreshold).toBeCloseTo(expected.longStdThreshold, 9);
    expect(c.calibEndIdx).toBe(expected.calibEndIdx);
  });

  it('push-then-check: the sample crossing the duration boundary is itself included', () => {
    // Hand-traced: elapsedMs at each index (calibrationDurationMs = 10000):
    // i0=0, i1=4000, i2=8000, i3=10000 (>=10000 -> done HERE, inclusive).
    const tNs = new BigInt64Array([ns(0), ns(4000), ns(8000), ns(10000), ns(12000)]);
    const vertical = new Float64Array([9.81, 9.81, 9.81, 9.81, 9.81]);
    const t: DetectionTunables = {
      calibrationDurationMs: 10000,
      stdFactor: 3,
      floorStd: 0.05,
      endQuietMs: 500,
      minSegmentDurationMs: 30,
      turnYawThresholdRadS: 1,
    };
    const c = calibrate(vertical, tNs, t);
    expect(c.calibEndIdx).toBe(3); // not 2 (the sample AT the boundary counts) or 4
  });

  it('threshold is max(max(std, floorStd) * stdFactor, floorStd)', () => {
    // Constant signal (std = 0 throughout) crossing the calibration window:
    // threshold = max(max(0, 0.05) * 3, 0.05) = max(0.15, 0.05) = 0.15.
    const tNs = new BigInt64Array([ns(0), ns(3000), ns(6000), ns(9000), ns(12000)]);
    const vertical = new Float64Array([9.81, 9.81, 9.81, 9.81, 9.81]);
    const t: DetectionTunables = {
      calibrationDurationMs: 10000,
      stdFactor: 3,
      floorStd: 0.05,
      endQuietMs: 500,
      minSegmentDurationMs: 30,
      turnYawThresholdRadS: 1,
    };
    const c = calibrate(vertical, tNs, t);
    expect(c.shortStdThreshold).toBeCloseTo(0.15, 9);
    expect(c.longStdThreshold).toBeCloseTo(0.15, 9);
  });

  it('session shorter than the calibration window never finishes calibrating (E-37)', () => {
    const files = loadFixtureFiles('synthetic/no-gyro'); // spans ~2s; calibrationDurationMs is 10000
    const { tNs, vertical } = parseSensorForDetector(files);
    const t = tunablesFromConfig(files.get('config.json') as string);
    const c = calibrate(vertical, tNs, t);
    expect(c.calibEndIdx).toBe(vertical.length - 1);
  });
});

// ---- simulate ------------------------------------------------------------

describe('simulate', () => {
  it('simulated segment count equals recorded count', () => {
    const files = loadFixtureFiles('session_trimmed_90s');
    const { tNs, vertical, gz } = parseSensorForDetector(files);
    const t = tunablesFromConfig(files.get('config.json') as string);
    const c = calibrate(vertical, tNs, t);
    const segs = simulate(vertical, tNs, gz, t, c);
    // segments.csv has 8 recorded rows (asserted independently in
    // worker/parse.test.ts's "parses fixture sensor rows to exact count").
    expect(segs).toHaveLength(8);
  });

  it('simulated starts equal recorded starts', () => {
    const files = loadFixtureFiles('session_trimmed_90s');
    const { tNs, vertical, gz } = parseSensorForDetector(files);
    const t = tunablesFromConfig(files.get('config.json') as string);
    const c = calibrate(vertical, tNs, t);
    const segs = simulate(vertical, tNs, gz, t, c);
    // Literal start_ns values copied from test/fixtures/session_trimmed_90s/segments.csv.
    const recordedStarts = [
      95748349469971n,
      95751084334357n,
      95760183974402n,
      95765076891109n,
      95766369736929n,
      95768507910420n,
      95782709438180n,
      95811480719287n,
    ];
    expect(segs.map((s) => s.startNs)).toEqual(recordedStarts);
  });

  it('RollingStats windows are handed over from calibration, not reset (Kotlin RecordingPipeline.kt:126-128)', () => {
    // Two 4-sample arrays, identical except index 2 during calibration: one
    // has a loud outlier there (12.0), the other stays at baseline (9.81).
    // Both cross calibrationDurationMs at index 2 (calibEndIdx = 2), so
    // index 3 is the only detection-phase sample in either case, and it has
    // the *same* value (9.81) in both variants. If the detector's windows
    // were reset at the calibration boundary instead of carrying the
    // calibrator's history forward, index 3 would behave identically in
    // both variants. It doesn't: std at index 3, given the values pushed so
    // far (hand-traced via the population-variance formula,
    // sum(x^2)/n - mean^2):
    //   quiet history  [9.81,9.81,9.81,9.81] -> std = 0
    //   loud history   [9.81,9.81,12.0,9.81] -> std ≈ 0.948298
    const tNs = new BigInt64Array([ns(0), ns(4000), ns(8000), ns(12000)]);
    const t: DetectionTunables = {
      calibrationDurationMs: 8000, // crosses at index 2 (elapsed 8000 >= 8000)
      stdFactor: 1,
      floorStd: 0,
      endQuietMs: 500,
      minSegmentDurationMs: 0,
      turnYawThresholdRadS: 1,
    };

    const quiet = new Float64Array([9.81, 9.81, 9.81, 9.81]);
    const cQuiet = calibrate(quiet, tNs, t);
    expect(cQuiet.calibEndIdx).toBe(2);
    // Threshold 0.9 sits strictly between the two variants' index-3 std.
    const segsQuiet = simulate(quiet, tNs, null, t, { ...cQuiet, shortStdThreshold: 0.9, longStdThreshold: 0.9 });
    expect(segsQuiet).toHaveLength(0);

    const loud = new Float64Array([9.81, 9.81, 12.0, 9.81]);
    const cLoud = calibrate(loud, tNs, t);
    expect(cLoud.calibEndIdx).toBe(2);
    const segsLoud = simulate(loud, tNs, null, t, { ...cLoud, shortStdThreshold: 0.9, longStdThreshold: 0.9 });
    expect(segsLoud).toHaveLength(1);
    expect(segsLoud[0]?.startNs).toBe(tNs[3]);
  });

  it('anySignal is true when either window crosses its own threshold', () => {
    // shortStdThreshold set unreachably high; longStdThreshold set to the
    // same 1.0 used in other hand-traced cases here (std at n=3 ≈ 1.032376,
    // see the duration-discard case below) — signal must still fire via the
    // long window alone.
    const tNs = new BigInt64Array([ns(0), ns(10), ns(20), ns(30)]);
    const vertical = new Float64Array([9.81, 9.81, 12.0, 9.81]);
    const t: DetectionTunables = {
      calibrationDurationMs: 0, // crosses immediately; calibEndIdx = 0
      stdFactor: 1,
      floorStd: 0,
      endQuietMs: 5,
      minSegmentDurationMs: 0,
      turnYawThresholdRadS: 1,
    };
    const c = { shortStdThreshold: 100, longStdThreshold: 1.0, calibEndIdx: 0 };
    const segs = simulate(vertical, tNs, null, t, c);
    expect(segs.length).toBeGreaterThan(0); // opened at index 2 via the long window despite the short threshold never being reachable
  });

  it('turning suppresses open but not extension, and end of data force-closes', () => {
    // shortStdThreshold = longStdThreshold = 0 makes anySignal true for
    // every sample (std is never negative), isolating the state machine
    // from RollingStats noise so turning/quiet/close logic can be traced by
    // hand alone.
    const tNs = new BigInt64Array([ns(0), ns(10), ns(20), ns(30), ns(40)]);
    const vertical = new Float64Array([10.0, 10.5, 11.0, 9.5, 9.0]);
    const gz = new Float64Array([5.0, 5.0, 0.0, 5.0, 0.0]); // turning: T,T,F,T,F
    const t: DetectionTunables = {
      calibrationDurationMs: 0,
      stdFactor: 1,
      floorStd: 0,
      endQuietMs: 500,
      minSegmentDurationMs: 0,
      turnYawThresholdRadS: 1.0,
    };
    const c = { shortStdThreshold: 0, longStdThreshold: 0, calibEndIdx: -1 };
    const segs = simulate(vertical, tNs, gz, t, c);
    // i0, i1: turning true, IDLE -> stays IDLE (open suppressed).
    // i2: turning false, anySignal true, IDLE -> opens at tNs[2].
    // i3: turning true again, but phase is OPEN -> turning is not consulted
    //     in the OPEN branch (SegmentDetector.kt push()), so it extends.
    // i4: turning false, extends again.
    // End of data (n=5) force-closes: duration = tNs[4] - tNs[2] = 20ms.
    expect(segs).toHaveLength(1);
    const seg = segs[0];
    expect(seg?.startNs).toBe(tNs[2]);
    expect(seg?.durationNs).toBe(tNs[4] as bigint - (tNs[2] as bigint));
    // magnitude = abs(vertical - 9.81): |11.0-9.81|=1.19, |9.5-9.81|=0.31, |9.0-9.81|=0.81
    expect(seg?.peakM).toBeCloseTo(1.19, 9);
    expect(seg?.rmsM).toBeCloseTo(0.8501568482736975, 9);
  });

  it('quietMs uses integer-ms division and closes only at or past endQuietMs', () => {
    // vertical = [9.81,9.81,12.0,9.81,12.0], threshold=1.0: hand-traced std
    // (population-variance formula, as in the case above) is signal at
    // index 2 (1.032376), quiet at index 3 (0.948298), signal again at
    // index 4 (1.072877) — so index 3 is the one "quiet" sample between two
    // signal samples, and only its *timing* changes between the two variants
    // below.
    const vertical = new Float64Array([9.81, 9.81, 12.0, 9.81, 12.0]);
    const t: DetectionTunables = {
      calibrationDurationMs: 0,
      stdFactor: 1,
      floorStd: 0,
      endQuietMs: 1,
      minSegmentDurationMs: 0,
      turnYawThresholdRadS: 1,
    };
    const c = { shortStdThreshold: 1.0, longStdThreshold: 1.0, calibEndIdx: -1 };

    // Variant A: index 3 sits 999_999ns (0.999999ms) after the opening
    // sample (index 2) -> quietMs = 999_999n / 1_000_000n = 0 (bigint
    // truncation), which never reaches endQuietMs=1. The segment must NOT
    // close at index 3, so index 4's signal *extends* the same segment:
    // exactly one segment, still starting at index 2.
    const tNsNoClose = new BigInt64Array([0n, 10_000_000n, 20_000_000n, 20_999_999n, 21_000_000n]);
    const segsNoClose = simulate(vertical, tNsNoClose, null, t, c);
    expect(segsNoClose).toHaveLength(1);
    expect(segsNoClose[0]?.startNs).toBe(tNsNoClose[2]);
    expect(segsNoClose[0]?.durationNs).toBe((tNsNoClose[4] as bigint) - (tNsNoClose[2] as bigint));

    // Variant B: index 3 sits exactly 1_000_000ns (1.0ms) after index 2 ->
    // quietMs = 1_000_000n / 1_000_000n = 1, which meets endQuietMs=1. The
    // segment closes AT index 3, so index 4's signal opens a *second*,
    // separate segment.
    const tNsCloses = new BigInt64Array([0n, 10_000_000n, 20_000_000n, 21_000_000n, 22_000_000n]);
    const segsCloses = simulate(vertical, tNsCloses, null, t, c);
    expect(segsCloses).toHaveLength(2);
    expect(segsCloses[0]?.startNs).toBe(tNsCloses[2]);
    expect(segsCloses[0]?.durationNs).toBe(0n); // closed without ever extending past the opening sample
    expect(segsCloses[1]?.startNs).toBe(tNsCloses[4]);
  });

  it('segment shorter than minSegmentDurationMs is discarded', () => {
    // Single-outlier decay, hand-traced via the population-variance formula
    // (sum(x^2)/n - mean^2), n-1 copies of 9.81 plus one 12.0:
    //   n=3 (...,9.81,9.81,12.0):        std ≈ 1.032376  (>=1.0 -> signal)
    //   n=4 (...,+9.81):                 std ≈ 0.948298  (<1.0 -> quiet)
    //   n=5..8 (+9.81 each):              std decays further, all < 1.0
    // Only index 2 (the 12.0 sample) is ever signal-true, so the segment
    // that opens there has zero duration (lastSignalNs never advances past
    // startNs) and endQuietMs=50 is reached exactly at index 7 (quietMs =
    // (tNs[7]-tNs[2])/1e6 = 50ms).
    const tNs = new BigInt64Array(Array.from({ length: 8 }, (_, i) => ns(i * 10)));
    const vertical = new Float64Array([9.81, 9.81, 12.0, 9.81, 9.81, 9.81, 9.81, 9.81]);
    const t: DetectionTunables = {
      calibrationDurationMs: 0,
      stdFactor: 1,
      floorStd: 0,
      endQuietMs: 50,
      minSegmentDurationMs: 10, // any positive value: a 0-duration segment must always be discarded
      turnYawThresholdRadS: 1,
    };
    const c = { shortStdThreshold: 1.0, longStdThreshold: 1.0, calibEndIdx: -1 };
    const segs = simulate(vertical, tNs, null, t, c);
    expect(segs).toHaveLength(0);
  });
});

// ---- signalMask ------------------------------------------------------------

describe('signalMask', () => {
  it('flags samples where either window is at or above its threshold, from a fresh index-0 pass', () => {
    // Same std trace as the duration-discard case above (n=1..4 of the
    // [9.81,9.81,12.0,9.81] prefix): 0, 0, 1.032376 (>=1 -> flagged), 0.948298 (<1).
    const vertical = new Float64Array([9.81, 9.81, 12.0, 9.81]);
    const c = { shortStdThreshold: 1.0, longStdThreshold: 1.0, calibEndIdx: -1 };
    const mask = signalMask(vertical, c);
    expect(Array.from(mask)).toEqual([0, 0, 1, 0]);
  });
});
