# 11 — Manual tagging UI

**What to build:** Point-tap and range-tag buttons, adapted from Prototype 1's
`DebugScreen`, so a rider can tag what they just rode over — even a few seconds
late — and have it snap back to the segment that caused it.

**Blocked by:** 06, 10

**Status:** ready-for-agent

- [ ] Point-tap buttons (e.g. "pothole") available during an active session
- [ ] Range-tag toggle, distinct from point taps, marks the start/end of a rough
      stretch
- [ ] Both wired through `TagMatcher`; a late tap visibly lands on the correct
      (earlier) segment
- [ ] Confidence indicator present in the UI but unwired (placeholder for ticket
      13)
