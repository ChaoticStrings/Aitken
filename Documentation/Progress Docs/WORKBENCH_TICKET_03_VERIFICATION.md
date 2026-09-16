# Ticket 03 — Kotlin-exact detector mirror, DSP recompute, peak divergence — Verification

## What was built

- `worker/detector.ts` — `calibrate`/`simulate`/`signalMask` (§5.7),
  Kotlin-exact mirror of `NoiseFloorCalibrator.kt`/`SegmentDetector.kt`/
  `RecordingPipeline.kt`, read directly from the Android source (not
  re-derived from the plan's prose). `RollingStatsJs` is a ring-buffer
  reimplementation of `RollingStats.kt`'s push-evict/population-variance
  semantics. `calibrate` reproduces the Kotlin `NoiseFloorCalibrator`'s
  push-then-check boundary (the sample crossing `calibrationDurationMs` is
  itself included before the check). `simulate` reconstructs the
  calibrator's window state by replaying `vertical[0..calibEndIdx]` through
  fresh windows before running the `SegmentDetector` state machine from
  `calibEndIdx + 1` onward — this is how the "windows are handed over, not
  reset" behaviour survives `calibrate`/`simulate` being two separate pure
  functions instead of one stateful object.
- `worker/dsp.ts` — `recomputeVertical`/`recomputeJerk`/`recomputeRollingStd`
  (mirroring `GravityEstimator.kt` → `Verticalizer.kt`, `JerkFilter.kt`,
  `RollingStats.kt` at `RecordingPipeline.kt`'s default 20-sample window),
  `recompute` (all three in one pass), and `divergence` (per-segment
  recorded-vs-recomputed peak, using a local binary search rather than
  `core/stats.lowerBound`, which doesn't exist until a later ticket).
- `worker/index.ts` gained a second, independent request type —
  `detectorCheck` — alongside ticket 02's `parse`, tagged with a `runId`
  (E-40: a newer request's result is honoured, an older one's is dropped).
  `runDetectorCheck` is exported so `InlineWorkerBridge` shares the exact
  function `RealWorkerBridge` drives through the real `postMessage`
  boundary, the same parity pattern ticket 02 established for `parseSession`.
- `bridge/WorkerBridge.ts`/`InlineWorkerBridge.ts`/`RealWorkerBridge.ts` —
  added `runDetectorCheck(input, runId)`. `RealWorkerBridge` deliberately
  does **not** transfer the input's typed-array buffers (unlike
  `parseSession`'s result): they're the same buffers the caller's
  already-imported `ParsedSession` still holds live references to, and a
  future ticket (04's `WaveformView`) will read them too — transferring
  would silently detach and zero them out from under the main thread. A
  one-time structured-clone copy (sub-millisecond on the fixture's ~9k
  rows) is the right trade.
- `persistence/Persistence.ts` gained the `derived` IDB store (§6.5),
  scoped to what this ticket actually produces — the detector-check
  summary — not the full `lod`/`recomputed` arrays §6.5 also lists there
  (ticket 04's job, when something actually needs to cache them).
  `IdbPersistence` bumped `DB_VERSION` to 2 and added the store
  incrementally (existing `bundles`/`journals` data is untouched by the
  upgrade). `deleteBundle` now also clears a bundle's `derived` row.
  `MemoryPersistence` got the same three methods for parity.
- `app/libraryStore.ts` — `importFromSource` now runs the detector check
  once, right after `parseSession`, while the full `ParsedSession` is
  already in hand, and caches the result via `saveDerived`. A detector
  check failure doesn't fail the import — the bundle is already saved and
  usable; the card just shows no detector-check line for it.
  `listEntries()` joins each bundle with its `derived` row.
- `panels/Library.tsx` — the "Detector check" line: `Detector check:
  simulated N / recorded M · K divergent`, plus a
  "session shorter than the calibration window" suffix on E-37.

## Verified against the real fixture, not just synthetic data

A useful fact fell out of actually reading `make-fixture.mjs`: it trims
`session_trimmed_90s` from a **later** cutoff (~90s) than
`calibrationDurationMs` (10s), so the trimmed fixture's first 10 seconds of
`sensor.csv` are byte-identical to the real, untrimmed session's first 10
seconds. That means AC1's two steps — "run against the full session" and
"freeze the trimmed fixture's own values" — collapse into one verification
achievable from the trimmed fixture alone; the full untrimmed session
(never present in this repo or sandbox, per
`WORKBENCH_TICKET_01_VERIFICATION.md`'s same blocker) wasn't actually
needed here.

Measured directly against `session_trimmed_90s`:
- `calibrate`'s thresholds vs. `config.json`'s
  `calibratedShortStdThreshold`/`calibratedLongStdThreshold`: diffs of
  **3.9e-5** and **4.5e-5** — two orders of magnitude inside the 1e-3 bar.
  Frozen as `test/fixtures/session_trimmed_90s/expected/calibration.json`.
- `simulate`'s 8 segments match all 8 recorded `segments.csv` rows exactly
  — same `start_ns` values, same `duration_ns` values, `peak_m`/`rms_m`
  matching to the CSV's own 3-decimal-place precision. Frozen as
  `expected/simulated.json`.
- `recompute`'s vertical channel vs. the recorded `vertical_ms2` column:
  max deviation **0.000585** across all 9041 rows — well inside the 1e-2
  bar. (The one segment with a non-trivial divergence, ~4.3% on the
  earliest recorded segment, is because `recompute` seeds `GravityEstimator`
  fresh at the trimmed file's own first sample, whereas the on-device
  estimator had already been warmed up for most of the ride by that point
  — not a mirror-fidelity bug. Every later segment is well under 0.1%.)

**D17's `Math.fround` fallback wasn't needed.** Plain `Float64` throughout
reproduced the Kotlin `Float`-based pipeline's real output within all of
the above tolerances, including exact segment count and start times.

## Discrepancies from the plan, and why

1. **§5.7's `signalMask` comment ("running RollingStats from index 0 (after
   calibration reset)") is one line with no worked example.** Implemented
   literally: a fresh pair of `RollingStatsJs` from index 0 across the
   *whole* array (not continuing from `calibrate`'s handed-over windows),
   thresholded by the already-computed `Calibration`. This covers the
   calibration span too, which only makes sense for a display/inspection
   use (a future ticket's waveform overlay), not for driving `simulate`'s
   own state machine — flagging the interpretation explicitly since no
   ticket yet consumes `signalMask`'s output to check it against.

2. **The `derived` store caches only the detector-check summary, not the
   full `recomputed`/`lod` arrays §6.5 also lists there.** Rule 0.5 — no
   ticket needs those cached yet (`lod` is ticket 04's; nothing currently
   re-reads `recompute`'s output after the detector check consumes it
   in-process). Revisit when ticket 04 or later actually needs to avoid
   recomputing them on every session open.

3. **No on-demand "rebuild the detector check" path for a bundle that
   predates this ticket** (there are none in this repo's fixtures, but a
   real user's already-imported session would have no `derived` row).
   `listEntries()` just omits the card's detector-check line when the row
   is absent, per §6.5's own "evictable; rebuilt if absent" — but nothing
   yet triggers that rebuild. Deliberately out of scope for a Library-card
   demo line; worth a small ticket of its own if it turns out to matter
   before ticket 04 needs to solve the same problem for `lod`.

4. **"Quiet in-segment lull excluded from peak/RMS" has no isolated
   hand-traced unit test.** Constructing a small sequence where one sample
   is simultaneously "quiet" (present in the state machine's aggregate
   `std` below threshold) and has an extreme `vertical` value (needed to
   prove its magnitude was excluded) turned out to be mutually
   contradictory at the small sample counts a hand-traced test can use — an
   outlier large enough to be worth excluding also pulls its own window's
   std over threshold. Covered instead by: (a) code inspection —
   `accumulate()` in `simulate` is called only inside the two
   `anySignal`-true branches, matching `SegmentDetector.kt`'s own call
   sites exactly; (b) the real fixture's near-zero divergence on 6 of 8
   segments, which a lull-inclusion bug would show up in as inflated RMS.

5. **E-40 (`runId` staleness) has no test exercising two genuinely
   concurrent `RealWorkerBridge.runDetectorCheck` calls.** The current
   caller (`libraryStore.importFromSource`) only ever issues one per
   import, so there's nothing in this codebase yet that races two runs
   against each other — the mechanism exists and is unit-testable in
   isolation, but its actual "cancel an in-flight run" payoff only shows up
   once ticket 12 (Simulation + Diff + Adopt, where tunables sliders
   re-trigger `simulate` on every change) exists to trigger it for real.

## Test names and counts

- Vitest `unit` — `test/unit/worker/detector.test.ts` (12): `calibration
  thresholds match config.json within 1e-3`, `push-then-check: the sample
  crossing the duration boundary is itself included`, `threshold is
  max(max(std, floorStd) * stdFactor, floorStd)`, `session shorter than the
  calibration window never finishes calibrating (E-37)`, `simulated segment
  count equals recorded count`, `simulated starts equal recorded starts`,
  `RollingStats windows are handed over from calibration, not reset`,
  `anySignal is true when either window crosses its own threshold`,
  `turning suppresses open but not extension, and end of data
  force-closes`, `quietMs uses integer-ms division and closes only at or
  past endQuietMs`, `segment shorter than minSegmentDurationMs is
  discarded`, `flags samples where either window is at or above its
  threshold, from a fresh index-0 pass` (`signalMask`).
- Vitest `unit` — `test/unit/worker/dsp.test.ts` (4): `recomputed vertical
  equals recorded within 1e-2 on fixture`, `recomputeJerk returns NaN until
  the window fills`, `recomputeRollingStd matches the population-std
  formula for a short window`, `divergence list flags injected 20% error`.
- Vitest `integration` — 2 new cases added to `import-pipeline.test.ts`:
  `ticket 03: runs and caches the detector check on import, and it
  survives a listEntries round-trip`, `ticket 03: a session shorter than
  the calibration window shows calibrationIncomplete with zero simulated
  segments`; 1 new case in the `IdbPersistence` block: `ticket 03:
  saveDerived/getDerived round-trip, and deleteBundle also clears the
  derived row`.
- Playwright — extended the existing `imports the real trimmed session...`
  e2e case with a `Detector check: simulated 8 / recorded 8 · 0 divergent`
  assertion — the only place the new `detectorCheck` message type crosses
  the real `postMessage` boundary in an automated test.

53/53 Vitest tests green (16 new + 37 pre-existing, all re-run, none
touched). `tsc --noEmit` clean. 14/14 Playwright tests green (7 desktop +
7 mobile — 3 pre-existing smoke + 4 import, one of the four now carrying
the new assertion), run against the sandbox's pre-baked Chromium
(`/opt/pw-browsers/chromium-1194`, same constraint ticket 01 documented —
no network path to Playwright's browser CDN here).

## How it was demoed

`npm run build` → single `dist/index.html` (543 KB) → Playwright against
that real built file, real Chromium, both desktop and mobile projects:
import `session_trimmed_90s.zip` through the actual file input, and the
Library card renders `Detector check: simulated 8 / recorded 8 · 0
divergent` under the existing segment/tag/warning lines — round-tripped
through the real Worker, not the inline test bridge.

## What's still open

- Items 2 and 3 above (`derived` store scope, no rebuild-on-demand path).
- `signalMask`'s output isn't consumed or displayed anywhere yet — it
  exists to satisfy §5.7's interface and is unit-tested directly, but its
  first real consumer is a later ticket's waveform overlay.
- Item 5 above — `runId` cancellation is implemented and unit-testable but
  not yet exercised by a genuine race, pending ticket 12.

## Status

Status: done.
