# Tickets 28–29 — sensor readout & clipping graph; detector/calibration rebase

Phase 1 (ticket 28): sensor ceiling/vendor/max-M readout, clipping drawn on the live graph.
Phase 2 (ticket 29): calibration and detection rebased — items 1a, 1b, 2, 3, 4, 5, 6.

## Audit findings (items 1a and 2)

Method: the project's standing forensic replay. An independent Python replica of the *old* pipeline
reproduced all 8 on-device segments of `workbench/test/fixtures/session_trimmed_90s` exactly, and its
gravity-projected vertical matched the recorded `vertical_ms2` to 0.0006 m/s². Findings below are from
that replica on the real data.

**1a — calibration was responding to horizontal acceleration. Yes, three ways.**
1. The threshold was a *snapshot*: the 20-sample std at the very last calibration sample (1.2154 → ×3 =
   3.646 in `config.json`), not a statistic of the window.
2. No gating of any kind. This session's 10 s window held standing, a launch (GPS speed 0.56 → 6.03 m/s in
   1 s ≈ 5.5 m/s²), cruising, and braking (6.1 → 2.8 m/s), with yaw reaching 1.12 rad/s.
3. Root cause underneath: `GravityEstimator` alpha 0.90 is a ~0.1 s time constant, so the axis `vertical`
   is projected onto chases sustained horizontal acceleration. The estimate rotated up to **36.6° within
   0.5 s inside the calibration window**. *Not changed here* — see follow-ups.
A "gravity moved" gate cannot fix it: the same estimate wobbles a median 4.2° over 0.3 s *while standing*
on this rattly mount, so vibration and launches are indistinguishable by that measure.

**2 — segment onset/offset. Not as described.** The old trigger was a rolling *std*, not amplitude: a lone
spike needed ~4.6× the threshold to fire at all (std of one spike in a 20-sample window ≈ 0.22·A), so onsets
lagged; the window kept a segment open after the waveform finished; and the 30 s window is why single
segments of 56 s / 104 s / 311 s existed. Opening/closing is now amplitude-based (below).

## What changed

| Item | Change |
|---|---|
| Phase 1 | `AccelerometerInfo` (name, vendor, `getMaximumRange()`), `MScale` (port of the Workbench `M_SCALE`), `GraphScale`. Idle and session screens show vendor/name, ceiling ±m/s², max M, and a clipped-sample count. Graph: translucent amber beyond the ceiling, brighter amber stripe at saturated samples. |
| 1a | `NoiseFloorCalibrator` excludes *dirty* samples: within 500 ms of a turn or of a GPS-speed change ≥ 1 m/s² (`LongitudinalMotionWatcher`). Samples are held 2 s before being judged, so a late GPS fix can still disqualify the seconds it describes. Clean time counts toward the duration; cap at 3× duration; floors if nothing clean. UI says "calibration paused" while excluding. |
| 1b | Two thresholds. M = mean of local-maximum peaks of `abs(vertical − 9.81)` × Detection sensitivity. D = highest sliding-window mean over the clean window × Rough patch sensitivity. Both floored. |
| 2 | `SegmentDetector` rewritten: opens at the breaching sample, closes after `endQuietMs` consistently below, duration to the last breach. |
| 3 | `RoughPatchTracker` + `Odometer`. Chain of roughness; gaps measured in metres; registers on ≥ 2 segments or length ≥ patch distance. Patch distance Tunable, log slider 1–100 m, default 2.5. Written to `patches.csv`. Rough-stretch button replaced by a live D readout. |
| 4 | Translucent blue fill between waveform and baseline while a segment is open. |
| 5 | Yellow line = sliding-window mean; dashed yellow = D threshold. Window width is a Settings slider (0.25–5 s). |
| 6 | `CalibrationPreset` + codec + `PresetStore`; start-screen dropdown; SAVE PRESET after a fresh calibration; manage/delete in Settings. A preset skips calibration after a 1 s warm-up and carries its own window width. |

## Decisions and assumptions (change any of them)

- **Y axis is symmetric-log (asinh), knee 3 m/s².** A linear axis that also shows a ±78 m/s² ceiling
  flattens road noise to a pixel.
- **Max M uses the upward ceiling**: M(ceiling − 9.81). The downward ceiling (ceiling + 9.81) is larger.
- **"D" in the UI = roughness (sliding-window mean of deviation)**, which overloads D (Duration). Recorded
  in `CONTEXT.md`.
- **Auto patches go to `patches.csv`, not `labels.csv`**, so the human-tag corpus never contains tags a
  detector rule wrote. The Workbench ignores unknown files, so it won't show them until it has a parser.
- **D is ignored for one window-width after a segment breach.** Without it, window memory turns a lone big
  pothole into a 7–18 m "patch" (see `ROUGH_PATCH_NOTES.md`).
- **`rms_m` semantics changed:** RMS over the whole segment span including in-segment lulls (it was
  breach-samples-only). Touches the open `peak_m` vs `rms_m` decision.
- **`stdFactor` / `floorStd` keys kept but re-meant** (multiplier on mean noise peak; floor in m/s², default
  0.05 → 0.5). `config.json` gets `"detectorVersion": 2`; the two legacy `calibrated*StdThreshold` keys are gone.
- Calibration now takes longer than the set duration when the rider isn't steady (this fixture: 10.0 s clean
  took 19.1 s elapsed); at best ≈ duration + 2 s.

## Verification

- **86 JVM tests pass** (`SlidingMean`, `Odometer`, `LongitudinalMotionWatcher`, `NoiseFloorCalibrator`,
  `SegmentDetector`, `RoughPatchTracker`, `CalibrationPresetCodec`, scales, `RecordingPipeline`,
  `SessionRecorder`, plus the unchanged `Severity` and `TagMatcher`). Hand-traced in test comments, per
  convention.
- Boundary mutations (`>=`→`>` on peak, quiet, and patch-distance comparisons) initially survived; boundary
  tests were added and the mutations now fail them.
- **Kotlin ↔ Python parity on the real fixture, exact:** calibration M 5.8277, D 2.5277, 330 peaks, 10 004 ms
  clean / 19 074 ms elapsed; 11 segments; 3 patches. All three tagged events (peaks 35.65, 32.92, 23.28) are
  detected. Replica: `Documentation/Other Docs/replay/detector_v2_replay.py`.
- Sensitivity sweep on the same session (segments / longest / patches, tagged events found): ×3.0 → 11 / 2.7 s
  / 3, 3 of 3; ×4.0 → 5 / 1.9 s / 1, 3 of 3; ×5.0 → 5 / 0.7 s / 1, 3 of 3.

**Not verified:** the Compose screens, `AitkenRecordingService`, `PresetStore`, and
`AndroidSensorStream.queryAccelerometerInfo` were **not compiled** — there is no Android SDK in the
environment I worked in (checked only for syntax; unresolved-Android-symbol errors expected). Nothing here was
run on a device. The pure logic was compiled with kotlinc 2.0.21 against a small JUnit stand-in, not Gradle.

## Follow-ups (not done)

1. **Workbench detector mirror is now stale.** `workbench/src/worker/detector.ts` mirrors the old std
   detector; new sessions (`detectorVersion: 2`) will diverge. Needs a v2 port and a `patches.csv` parser.
2. `GravityEstimator` alpha 0.90 (the root cause under 1a): a slower estimate, or a separate one for
   calibration, would remove horizontal-acceleration leakage from `vertical` itself. Changes every session's
   `vertical_ms2`, so it needs its own decision.
3. Stationary-vs-moving calibration still differs (a moving mount vibrates more); the gate removes
   acceleration, not that.
4. Highway-slab periodicity: parked, see `ROUGH_PATCH_NOTES.md`.
5. Ticket tracker lacks 28–29 (same standing gap as 20+).
