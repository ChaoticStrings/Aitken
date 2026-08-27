# Luna — ticket set

Local-markdown tracker (no connector available in this environment). Drop this
`.scratch/luna/` folder into the real repo; `issues/` is numbered in dependency
order, blockers first, with one exception noted below.

**Progress**: 01 (DSP port), 02 (GpsProvider), 03 (SafStorageAdapter), 04
(SegmentDetector), and 19 (NoiseFloorCalibrator) implemented and merged.
Remaining 13 tickets are `ready-for-agent`.

**19 — NoiseFloorCalibrator** was added after the fact, once calibration intent
was clarified in conversation — it wasn't part of the original 18. It logically
sits between 01 and 04/10 (SegmentDetector consumes its output; ticket 10's
recording pipeline runs it at session start) despite the higher number; kept
appended rather than renumbered so it doesn't disturb tickets already merged.

Frontier now: 05 (SessionRecorder) and 06 (TagMatcher), both blocked only by 04
which is done.

**Deliberately not ticketed**, per SPEC.md's own Out of Scope section and map.md's
"Not yet specified" list:
- `VerificationHarness` — explicitly deferred until SegmentDetector (04) exists and
  produces real output to verify against.
- The Colab calibration/classifier-fitting notebook — never designed to pseudocode
  level (T1 only ratified the stack choice), and needs Luna's first real sessions to
  be specifiable at all.

Both are natural candidates for their own wayfinder map once real ride data exists.

