# 13 — Auto-tagging integration

**What to build:** `ClassifierRunner` applied to closed segments during/after a
session, with the confidence indicator lit up and low-confidence auto-tags surfaced
for review instead of silently accepted.

**Blocked by:** 09, 11

**Status:** ready-for-agent

- [ ] Closed segments run through `ClassifierRunner` and get an auto-tag +
      confidence score written to the labels file
- [ ] Confidence indicator (placeholder from ticket 11) now reflects the real score
- [ ] Below-threshold auto-tags are visibly flagged for review, not silently
      written as confirmed
- [ ] Auto-tagging never blocks or delays the recording pipeline
