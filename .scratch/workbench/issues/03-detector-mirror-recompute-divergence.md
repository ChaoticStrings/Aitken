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

**Status:** ready-for-agent

**Plan sections:** §1.4 D17, §5.3, §5.7, §6.1, §11 E-05, E-06, E-37, E-40.

**Acceptance criteria**
- [ ] `calibrate` reproduces the fixture's calibrated thresholds: run `make-fixture` to record `expected/calibration.json` from the **full** session first (values must match `config.json`'s `calibratedShortStdThreshold` 3.64617 / `calibratedLongStdThreshold` 5.0351295 within 1e-3), then freeze the trimmed fixture's own values as literals.
- [ ] `simulate` over the trimmed fixture with its `config.json` tunables yields exactly the recorded segment count and identical `start_ns` values (`expected/simulated.json`). If Float64 does not reproduce, switch accumulation to `Math.fround` and retest before recording a discrepancy.
- [ ] Semantics locked by hand-traced synthetic tests: push-then-check calibration boundary; RollingStats handed over (not reset); `threshold = max(max(std,floor)·factor, floor)`; `anySignal` OR rule; turning suppresses open but not extension; integer-ms `quietMs`; `< minSegmentDurationMs` discarded; end of data force-closes; magnitude is `abs(v − 9.81)` over signal-true samples only.
- [ ] `recomputeVertical/Jerk/RollingStd` mirror `GravityEstimator`/`Verticalizer`/`JerkFilter`/`RollingStats` (read the Kotlin; note alpha and window constants in code comments); recomputed vertical within 1e-2 of recorded on the fixture.
- [ ] `divergence(segments, signal)` returns `{segId, recordedPeak, recomputedPeak}` for every segment; test injects a 20 % error into one recorded peak and asserts only that one exceeds 10 %.
- [ ] Runs are tagged with `runId`; a newer request cancels/ignores an older result (E-40).
- [ ] Sessions shorter than the calibration window produce zero segments and a notice (E-37).
- [ ] Demo: Library card shows the detector-check line for the fixture and for `no-gyro` (turning always false).

**Tests to write first:** the `worker/detector` and `worker/dsp` rows of Plan §12, verbatim names.
