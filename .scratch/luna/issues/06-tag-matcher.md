# 06 — TagMatcher

**What to build:** Retroactive matching of a manual tap to the segment that actually
caused it, via backward lookback, so a rider can tag a pothole a few seconds late and
have it land on the right segment.

**Blocked by:** 04

**Status:** ready-for-agent

- [ ] Tap checked first against the currently-open segment, then against
      recently-closed segments within `tagLookbackMs` (default ~8s, marked
      `[CALIBRATE]`)
- [ ] `tap_offset_ms` (how late the tap arrived) logged as its own calibration
      signal
- [ ] Unmatched taps (outside the lookback window) are logged, not dropped, for
      Workbench reconciliation
- [ ] Range-tag start/end events matched independently from point taps
