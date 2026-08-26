Labels: ready-for-agent
(No connected tracker in this environment — local-markdown equivalent of a triage label.)

# Luna — Aitken Recording Pipeline + Workbench

## Problem Statement

Vision wants to know which roads near them are actually dangerous to ride a scooter
on, and how that danger changes with speed — but no data exists to answer that yet.
Building that data requires a tool that can ride along, faithfully capture what a road
feature actually does to the vehicle, and let the rider label what happened without
demanding attention during the ride itself — safety comes first, and a rider will
often only be able to tag a pothole a few seconds after hitting it, once the bike is
stable again.

## Solution

Aitken is an Android app that records continuously during a ride and automatically
detects road-feature "segments" — open-ended disturbances in the vertical
acceleration signal, whether a fifty-millisecond pothole or a twenty-minute stretch
of unpaved road. The rider tags what they just rode over, even a few seconds late,
and the tag snaps backward onto the segment that actually caused it rather than the
moment the rider reacted. As tagged segments accumulate, Aitken learns to classify
and tag new segments itself, shrinking the manual burden toward zero. A companion
HTML Workbench lets Vision review, correct, and export session data; a Google Colab
notebook turns accumulated sessions into calibrated M/D-scale boundaries and
classifier parameters that flow back into Aitken, closing the loop.

## User Stories

1. As a rider, I want Aitken to record continuously once I start a session, so that
   no road feature is missed just because I didn't anticipate it.
2. As a rider, I want recording to keep working with the screen off, so the ride
   stays safe and the battery lasts the full trip.
3. As a rider, I want to tap a "pothole" button a few seconds after actually hitting
   one, so I can keep both hands on the bars until it's safe to tag it.
4. As a rider, I want that late tap to snap back to when the pothole actually
   happened, so the recorded severity and duration reflect the real event.
5. As a rider, I want to mark the start and end of a rough stretch separately from a
   single-tap pothole, so long, low-intensity features are recorded as accurately as
   short, sharp ones.
6. As a rider, I want Aitken to detect a feature's actual duration with no fixed
   cutoff, so a twenty-minute unpaved stretch is measured as faithfully as a
   fifty-millisecond pothole.
7. As a rider, I want Aitken to keep working with no internet connection, so a dead
   zone on my route never interrupts a recording.
8. As a rider, I want a phone crash mid-ride to cost me only the last unflushed
   samples, not the whole session.
9. As a rider, I want my session data automatically copied to storage I control, so
   losing or damaging my phone doesn't also cost me the data.
10. As a rider, I want to choose exactly which folder that backup goes to, so it
    lines up with whatever sync tool I'm already using.
11. As a rider, I want to know my session data is never sent anywhere else
    automatically, so I can trust the recordings stay private until I choose to
    share them.
12. As Vision reviewing data later, I want the Workbench to show the recorded signal
    and detected segments side by side, so I can visually verify the detector is
    behaving sensibly.
13. As Vision reviewing data later, I want to see the GPS track for a session on a
    map, so I can connect what happened to where it happened.
14. As Vision reviewing data later, I want to correct a wrong or missing tag
    directly in the Workbench, so mistakes don't propagate into calibration.
15. As Vision reviewing data later, I want to export a "clean" version of a session
    once reviewed, so only verified data reaches the Colab notebook.
16. As Vision running the Colab notebook, I want it to calibrate M-scale and
    D-scale boundaries against the tagged corpus, so the scales reflect real ride
    data rather than guesses.
17. As Vision running the Colab notebook, I want it to fit classifier parameters
    from confirmed tags, so Aitken can eventually tag new segments on its own.
18. As Vision running the Colab notebook, I want it to fit an M(V)/D(V) transform
    per feature class, so a future Crater app can predict how a known feature
    would feel at a different speed.
19. As a rider using a matured Aitken, I want newly detected segments auto-tagged
    with a confidence score, so I stop manually tagging routine, obvious features.
20. As a rider using a matured Aitken, I want low-confidence auto-tags flagged for
    review instead of silently accepted, so uncertain classifications don't
    quietly corrupt the corpus.
21. As a rider using a matured Aitken, I want an updated classifier config picked
    up automatically without blocking a recording session, so a sync hiccup never
    costs me a ride's data.
22. As a rider, I want the app to keep recording even if the classifier config
    hasn't updated in a while, so a stale config degrades gracefully instead of
    stopping the app from working.
23. As Vision setting up a new install, I want to grant Aitken access to a storage
    folder once, so backup and config sync both work without repeated prompts.
24. As Vision, I want every session file to carry its own schema version, so a
    future change to one file's format doesn't silently break the ones that
    didn't change.
25. As Vision, I want each detected segment to record vehicle speed alongside its
    magnitude and duration, so the M-D-V relationship central to this project is
    actually captured, not just implied.
26. As Vision, I want every segment's timestamp recorded, so time-of-day/traffic
    context is available later without extra instrumentation.
27. As Vision, I want turning to suppress the start of new segments, so lean angle
    during a corner isn't misrecorded as a road feature.
28. As Vision, I want an already-open segment to keep extending through a corner
    rather than being cut short, so a real feature encountered mid-corner isn't
    truncated.
29. As Vision, I want ending a session mid-segment to force-close that segment
    instead of discarding it, so the last few seconds of a ride aren't silently
    lost.
30. As Vision building this dataset for the long term, I want map rendering,
    location, and classifier-loading built behind our own interfaces, so swapping
    a vendor SDK later is a local change, not a rewrite.

## Implementation Decisions

**Reused verbatim from Prototype 1** (package renamed `com.crater.dsp` →
`com.aitken.dsp`): `GravityEstimator`, `Verticalizer`, `JerkFilter`, `RollingStats`.
Already pure, already unit-tested — no redesign.

**`SegmentDetector`** (new) — hysteresis-based, open-ended state machine (IDLE/OPEN),
no fixed duration ceiling. Consumes two `RollingStats` windows: a short one (~200ms)
for spike triggering, a long one (tens of seconds) for sustained-roughness tracking,
so a brief smooth lull inside a long rough stretch doesn't fragment the segment.
Peak-M and RMS-M accumulate only over signal-true samples — excluding quiet
in-segment lulls and the trailing hysteresis quiet-tail — so a lull can't drag the
reported magnitude down. Duration is measured to the last signal-true sample,
excluding the hysteresis tail, avoiding a fixed positive bias on every D value.
Turning (yaw rate over threshold) suppresses new segment starts but never truncates
an already-open segment. Ending a session force-closes any open segment. Exposes
`currentOpenSegment()` so `TagMatcher` can attach a tag to a segment still in
progress.

```
state IDLE:
  if anySignal AND NOT turning: -> OPEN, start accumulators
state OPEN:
  if anySignal: extend lastSignalNs, fold sample into peak/RMS accumulators
  else if quiet(lastSignalNs) >= endQuietMs:
    -> IDLE; emit Closed(duration = lastSignalNs - startNs, peakM, rmsM)
      unless duration < minSegmentDurationMs, in which case discard silently
```

**`SessionRecorder`** (new, deep module) — owns writes to all four session files
(sensor, gps, segments, labels) behind one interface, centralizing crash-safe
incremental flush so that guarantee lives in exactly one place. Each file carries
its own `schema_version`, versioned independently since the four evolve at
different rates.

**`TagMatcher`** (new) — matches a manual tap to a segment via backward lookback
(`tagLookbackMs`, default ~8s, `[CALIBRATE]`): checks the currently-open segment
first, then recently-closed segments within the window. Logs `tap_offset_ms`
(how late the tap arrived) as its own calibration signal. Unmatched taps are
logged, not dropped, and surfaced in the Workbench for reconciliation.

**`ClassifierRunner`** (new) — applies the current calibrated config to a closed
segment, producing an auto-tag with a confidence score. Below a confidence
threshold (`[CALIBRATE]`), the segment is flagged for review instead of
auto-accepted.

**`GpsProvider`** (new, seam) — wraps `FusedLocationProviderClient` behind our own
interface. A real second adapter exists (Play-Services-free devices), so this is
wrapped per the replaceability invariant.

**`SafStorageAdapter`** (new, seam — consolidates two originally-separate
concerns) — one adapter over a Storage Access Framework tree URI, granted once via
a one-time folder picker (`ACTION_OPEN_DOCUMENT_TREE`) and persisted. Both
`BackupAgent` (writes closed session bundles) and `ClassifierConfigLoader` (reads
updated classifier configs) sit on top of this single adapter rather than each
independently wrapping SAF. Direct-path writes to a public folder don't work under
scoped storage (Android 10+); MediaStore-into-Documents is undocumented platform
behavior and was deliberately avoided.

**`ClassifierConfigLoader`** — `currentConfig()` returns the cached local config
synchronously, never blocking; `checkForUpdate()` polls the SAF folder
opportunistically and never gates a recording session on network/sync
availability.

**`BackupAgent`** — `enqueueBackup(sessionPath)` copies a closed session bundle to
the SAF folder, best-effort, decoupled from the primary write path.

**Primary session writes always go to app-private storage first** (fast, no
permission negotiation, unaffected by SAF availability); the SAF-backed backup is
a separate, later, best-effort step — this is what makes invariants 4 and 5 both
true at once without one undermining the other.

**Architecture invariants** (ratified in T2, apply across this and all future
phases):
1. Replaceability, scoped via the two-adapters test, not applied blanket-wide: map
   rendering (Crater-future), location access, and classifier/model-config loading
   sit behind our own interfaces; Compose/AndroidX and the session-file format do
   not, since no real second adapter exists for either.
2. Per-file schema versioning.
3. Solo-maintainable: no dependency requiring a team or an on-call rotation —
   managed services (Colab, a folder-sync tool) are fine and preferred.
4. No-network-required, scoped to Aitken's recording path specifically; Workbench
   and Colab may assume connectivity.
5. Data integrity & consent: incremental crash-safe writes; automatic backup to
   Vision-controlled storage is permitted (a safety net, not a disclosure); no data
   is shared with any other party or service without an explicit, user-initiated
   action.
6. "Architecture invariant" is always written in full, never abbreviated to bare
   "invariant" (which collides with this codebase's existing DSP sense of the
   word — orientation invariance).

**Workbench modules**: `SessionLoader` (parses the four CSVs, dispatching per-file
on `schema_version`), `WaveformView` (ported from Prototype 1's existing pan/zoom
canvas, extended with segment-boundary overlays colored by D-scale bucket),
`MapView` (new, Leaflet + OSM), `TagEditor` (accept/reject/adjust auto-tags,
add/remove/move manual tags, adjust segment boundaries), `BundleExporter` (writes
the corrected bundle for Colab). `VerificationHarness` (Node.js, differential-replay
pattern reused from Prototype 1's `verify_pipeline.js`) is deferred until
`SegmentDetector` exists and produces real output to verify against.

**Front-end**: Aitken's session screen adapts Prototype 1's `DebugScreen` directly
(waveform canvas, tap buttons, session toggle — already proven on-device) rather
than a redesign, extended with a range-tag toggle distinct from the point-tap
buttons, and a confidence indicator left visibly present but unwired until
`ClassifierRunner` exists.

**Stack** (ratified in T1, full alternatives-and-why-not writeup there): Kotlin +
Jetpack Compose for Aitken; HTML/JS + a Node.js verification harness for the
Workbench; Python + Google Colab for the calibration/ML notebook.

## Testing Decisions

- A good test here verifies external behavior — a segment's recorded M/D/tag/
  confidence — not internal state transitions, mirroring the existing
  `GravityEstimatorTest`/`VerticalizerTest`/`JerkFilterTest`/`RollingStatsTest`
  pattern already in the codebase: pure input → output assertions, no mocking of
  internals.
- `SegmentDetector`, `SessionRecorder`, `TagMatcher`, and `ClassifierRunner` are
  pure-logic modules and get full unit-test coverage the same way the four reused
  DSP modules already do — accept dependencies, return results, no hidden side
  effects.
- `GpsProvider` and `SafStorageAdapter` are the two genuinely Android-coupled
  seams — the only two that get faked in tests (a fake location fix, a fake
  tree-URI file system). Everything built on top of them is tested as pure logic.
- Prior art: `app/src/test/java/com/crater/dsp/*Test.kt` (moving to
  `com.aitken.dsp`) — same JUnit4 + `org.jetbrains.kotlin:kotlin-test` setup, same
  pure-Kotlin-where-possible discipline.
- `SegmentDetector`'s ten enumerated edge cases (T5) translate directly to test
  names, e.g. `stopping the session while a segment is open force-closes it
  instead of silently dropping it`.

## Out of Scope

- Room DB, map rendering, community aggregation, civic-export formats —
  Crater-maturity features, not Luna.
- Weather/visibility context tagging — real for Crater's eventual risk model, not
  core to Aitken.
- The M(V)/D(V) transform's exact fitted form (physics-informed vs. data-driven) —
  needs real multi-speed data; a Colab-notebook question, not a Luna design one.
- Schema-migration strategy for old sessions when `schema_version` bumps —
  premature with only one version in existence.
- `VerificationHarness` build-out — waits on `SegmentDetector` existing.
- Publishing/Play Store concerns (background-location "minimum scope" review) —
  Crater/Artemis-maturity; Aitken isn't published.

## Further Notes

This spec is Luna only. Aitken-maturity (the classifier-training loop actually
running against a real corpus), Crater, and Artemis are each their own future
wayfinder effort — deliberately not designed further than the invariants and named
feasibility research already resolved here.

Full history of how each decision was reached — grilling rounds, a mid-session
correction on the network-availability assumption, rejected alternatives — lives in
the wayfinder map and tickets T1–T7, packaged alongside this spec.

Vision's background is Python/Django; Kotlin/Compose concepts should continue to be
explained inline during implementation, not assumed.
