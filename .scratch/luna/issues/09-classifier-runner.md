# 09 — ClassifierRunner

**What to build:** Applies the current calibrated config to a closed segment,
producing an auto-tag with a confidence score, and flags low-confidence segments for
review instead of silently accepting them.

**Blocked by:** 04, 07

**Status:** ready-for-agent

- [ ] Given a closed segment and the current config, produces an auto-tag +
      confidence score
- [ ] Segments below the confidence threshold (`[CALIBRATE]`) are flagged for
      review, not auto-accepted
- [ ] Pure-logic module: accepts a segment + config, returns a result, no hidden
      side effects
- [ ] Full unit-test coverage following the same pure input → output pattern as the
      DSP modules
