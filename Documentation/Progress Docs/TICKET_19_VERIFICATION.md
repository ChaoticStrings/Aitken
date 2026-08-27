# Ticket 19 — NoiseFloorCalibrator, implementation notes

## Why this exists
`SegmentDetector` (ticket 04) already took its thresholds as constructor
parameters rather than hardcoding them — so it needed zero changes. What was
missing was the piece that *produces* those numbers. Without it, the only
options were a hand-picked absolute number (guessed, doesn't transfer across
phone mounts/vehicles/road surfaces, skews the dataset) or trial-and-error
tuning per install — both are what this ticket exists to avoid.

## Design
Mirrors Prototype 1's MAIN-mode 10s calibration phase, but as its own small
module composed with `SegmentDetector` rather than folded into its
hysteresis logic — keeps `SegmentDetector` exactly as testable as it already
was, and makes the calibration step independently testable too.

The calibrator owns the actual `RollingStats` window instances and hands
them (already warmed with real history) straight to `SegmentDetector`'s
constructor — no gap between "calibration just ended" and "detection
starts fresh." Each window's threshold is derived from *that window's own*
measured baseline, not one number scaled two ways, since a short window and
a long window read genuinely different std for the same quiet road (more
averaging in the long window reads lower) — measuring them independently is
what actually makes the two-window design in ticket 04 work as intended.

## TDD sequence followed
1. `NoiseFloorCalibratorTest.kt` written first, red — five cases: `isDone`
   before any pushes, the exact push where the calibration duration is
   crossed, threshold derivation from a hand-computed `RollingStats` std,
   the floor kicking in on a perfectly flat calibration, and a full
   integration test proving the calibrated windows really do carry into a
   working `SegmentDetector`.
2. `NoiseFloorCalibrator.kt` written to make those pass, green. Every
   numeric expectation is hand-traced in comments at point of use, same
   discipline as ticket 04, since I still can't execute Kotlin here.
3. Updated `SegmentDetector.kt`'s KDoc only (no logic change) to point at
   this calibrator as the intended source of its thresholds — safe to
   swap in without re-verifying behavior.

## Deliberately deferred
Calibration doesn't account for turning. Prototype 1's calibration phase
didn't either — it just ran a blind timer at session start. If a turn
during the calibration window turns out to inflate the baseline in
practice, that's a small addition to `NoiseFloorCalibrator.push()` later,
not a redesign. Flagging now rather than guessing at whether it matters
before there's real ride data to check against.

## Tracker note
Filed as ticket 19 (appended, not renumbered) since 01–04 were already
merged when this was identified — see the tracker README for the full
reasoning.

## What compiles today
Pure Kotlin, no Android imports, no new dependency, same package as
`SegmentDetector` (`com.aitken.segment`). Should compile and run as-is
alongside the ticket 04 files already in your repo.
