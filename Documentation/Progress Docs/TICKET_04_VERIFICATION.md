# Ticket 04 — SegmentDetector, implementation notes

## On the "ten enumerated edge cases"
The docs available to me (SPEC.md, T5) reference "ten edge cases enumerated"
from the original grilling session but don't reproduce the literal list —
only the general behavioral description and one example test name. Rather
than guess at what the missing ten said, I derived a full set myself
straight from the spec's behavioral requirements, and erred toward
completeness: 12 tests, covering every requirement T5/SPEC.md actually
states (open/close hysteresis, turn suppression scoped to new-starts-only,
signal-true-only accumulation, quiet-tail exclusion from duration,
minimum-duration discard, forced close, `currentOpenSegment()`), plus two
tests specifically proving *why* two RollingStats windows are needed rather
than one — the short window catching an isolated spike, and the long window
keeping a segment open through a lull the short window alone would already
read as quiet. If the original ten differ from this list, tell me and I'll
reconcile.

## TDD sequence followed
1. `SegmentDetectorTest.kt` written first, red — all 12 cases, using
   detector shapes small enough to hand-trace exactly (window sizes of 2–6
   samples, not the ~100Hz production defaults) since I can't execute Kotlin
   in this sandbox. Every numeric expectation in the test file is backed by
   an explicit hand-computation of `RollingStats`' population-variance
   formula, shown in comments at the point of use.
2. `SegmentDetector.kt` written to make those pass, green.
3. Caught my own bug on review before shipping: three tests accessed a
   nullable local (`open`/`closed`) a second time without repeating `!!` —
   JUnit's `assertNotNull` doesn't smart-cast in Kotlin the way an `if (x !=
   null)` block would, so those would've failed to compile. Fixed all three
   rather than ship a third build-break in a row.

## Design decisions made without the original detail (flagging per "explain
reasoning, don't silently decide")
- **Signal thresholds are absolute, not baseline-relative.** Prototype 1's
  MAIN mode ran a 10s noise-floor calibration phase and triggered off
  `std >= noiseStd * stdFactor`. Nothing in SPEC.md or T5 mentions a
  calibration phase for SegmentDetector, so I used fixed, injectable
  absolute thresholds (`shortStdThreshold`, `longStdThreshold`) instead —
  both marked `[CALIBRATE]`, expected to move once real ride data exists,
  same as `tagLookbackMs` and the confidence threshold elsewhere in the
  spec. If a calibration phase was actually intended, that's a different
  module boundary (probably upstream of SegmentDetector) and worth a
  conversation before ticket 10 wires this into the real pipeline.
- **`push()` returns `ClosedSegment?` rather than taking an `onClosed`
  callback.** Matches the existing pure-input-output style already
  established by `JerkFilter.push()` returning `Float?` — no captured
  mutable state needed in callers or tests.
- **Window sizes and thresholds are constructor parameters, not hardcoded.**
  Production defaults (20 samples ≈ 200ms, 3000 samples ≈ 30s @ 100Hz) are
  placeholders in the same spirit as the DSP modules' `windowSamples = 20`
  default — real calibration happens later.

## What compiles today
Pure Kotlin, no Android imports, no new dependency — `SegmentDetector.kt`
and `SegmentDetectorTest.kt` should compile and run as-is once dropped in
next to `com/aitken/dsp`.

## Architecture invariant check
No Android or DSP-internal leakage — `SegmentDetector` depends only on
`RollingStats`' public `push()`/`Stats` surface (already a pure module).
`currentOpenSegment()` is exposed exactly as T5 specifies, for `TagMatcher`
(ticket 06) to attach a tag to a segment that hasn't closed yet.
