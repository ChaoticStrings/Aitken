# 04 — SegmentDetector

**What to build:** The open-ended, hysteresis-based segment detection engine — the
novel core the whole redesign depends on — turning raw DSP output into `Closed`
segment events with no fixed duration ceiling.

**Blocked by:** 01

**Status:** ready-for-agent

- [x] IDLE/OPEN state machine implemented per T5's pseudocode, driven by a short
      (~200ms) trigger window and a long (tens of seconds) sustain window
- [x] Peak-M and RMS-M accumulate only over signal-true samples, excluding
      in-segment quiet lulls and the trailing hysteresis quiet-tail
- [x] Duration measured to the last signal-true sample, excluding the hysteresis tail
- [x] Turning (yaw rate over threshold) suppresses new segment starts but never
      truncates an already-open segment
- [x] Ending a session force-closes any open segment instead of discarding it
- [x] `currentOpenSegment()` exposed for `TagMatcher`
- [x] All ten edge cases enumerated in T5 covered by named unit tests (e.g.
      "stopping the session while a segment is open force-closes it instead of
      silently dropping it")
- [x] Segments shorter than `minSegmentDurationMs` are discarded silently, not
      emitted
