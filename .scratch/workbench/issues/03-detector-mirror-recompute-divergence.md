# 03 — Kotlin-exact detector mirror, DSP recompute, peak divergence

**What to build:** In the worker: a calibration + segment-detector
implementation that reproduces `NoiseFloorCalibrator.kt` /
`SegmentDetector.kt` / `RecordingPipeline.kt` exactly; a recompute of
vertical / jerk / rolling-std from raw accelerometer; a `signalMask`; and a
per-segment divergence list (recorded `peak_m` vs recomputed
`abs(vertical − 9.81)` peak). Surfaced minimally in the UI as a "Detector
check" line on the Library card ("simulated 27 / recorded 27 · 0 divergent")
so the slice is demoable before the Report panel exists.

**Blocked by:** 02

**Status:** done

**Plan sections:** §1.4 D17, §5.3, §5.7, §6.1, §11 E-05, E-06, E-37, E-40.

**Acceptance criteria**
- [x] `calibrate` reproduces the fixture's calibrated thresholds (diffs of 3.9e-5 / 4.5e-5 against config.json, well inside 1e-3 — see verification note for why the full-session step and the trimmed-fixture step collapse into one check here), frozen as `expected/calibration.json`.
- [x] `simulate` over the trimmed fixture yields exactly the recorded segment count (8/8) and identical `start_ns` values, frozen as `expected/simulated.json`. Float64 reproduced this exactly; `Math.fround` fallback wasn't needed.
- [x] Semantics locked by hand-traced synthetic tests (`test/unit/worker/detector.test.ts`): push-then-check boundary, RollingStats handoff, threshold formula, anySignal OR rule, turning suppression vs extension, integer-ms quietMs, duration discard, end-of-data force-close, magnitude-from-9.81 — all covered; see verification note item 4 for the one behaviour (quiet-lull exclusion) covered by code inspection instead of an isolated test, and why.
- [x] `recomputeVertical/Jerk/RollingStd` mirror `GravityEstimator`/`Verticalizer`/`JerkFilter`/`RollingStats`; recomputed vertical within 0.000585 of recorded on the fixture (bar was 1e-2).
- [x] `divergence(segments, tNs, signal)` returns `{segId, recordedPeak, recomputedPeak}` for every segment; injected-20%-error test asserts only that segment exceeds 10%.
- [x] Runs are tagged with `runId`; `RealWorkerBridge` drops a result whose `runId` isn't the latest requested (E-40) — mechanism unit-testable but not yet exercised by a genuine concurrent race; see verification note item 5.
- [x] Sessions shorter than the calibration window produce zero segments and `calibrationIncomplete: true` (E-37) — covered on both the real `no-gyro` fixture and an integration test through the Library card.
- [x] Demo: Library card shows the detector-check line for the fixture (`simulated 8 / recorded 8 · 0 divergent`, verified via Playwright against the real built app) and for `no-gyro` (calibration-incomplete notice, turning forced false throughout via `gz: null`).

**Tests to write first:** the `worker/detector` and `worker/dsp` rows of Plan §12, verbatim names.
