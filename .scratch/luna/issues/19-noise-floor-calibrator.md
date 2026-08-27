# 19 — NoiseFloorCalibrator

**What to build:** A quiet-road calibration step that derives
`SegmentDetector`'s short/long std thresholds from measured baseline
roughness, instead of a hardcoded number a rider would have to guess by
trial and error — a mismatched guess quietly skews which bumps count as
signal across the whole dataset. Mirrors Prototype 1's MAIN-mode 10s
noise-floor phase, but as its own composable module rather than folded into
the detector's hysteresis logic.

**Numbering note:** this ticket was identified after 01–04 were already
implemented, once calibration intent was clarified — it wasn't part of the
original 18-ticket set. It logically sits between 01 (DSP port, provides
`RollingStats`) and 04/10 (`SegmentDetector` consumes its output,
ticket 10's recording pipeline runs it at session start) despite the higher
number.

**Blocked by:** 01

**Status:** ready-for-agent — implemented this session, pending your build
confirmation

- [x] Owns the same two `RollingStats` windows `SegmentDetector` will go on
      to use, so real recent history carries from calibration straight into
      detection with no cold-start gap
- [x] Each window's threshold is derived from *that window's own* measured
      baseline std (not one flat number scaled two ways) — short and long
      windows have genuinely different natural noise levels even on the same
      quiet road
- [x] `calibrationDurationMs`, `stdFactor`, and `floorStd` are `[CALIBRATE]`,
      mirroring Prototype 1's already-audited MAIN-mode defaults (10s, 3x,
      0.05 floor)
- [x] A floor prevents a freakishly flat calibration window from deriving a
      ~0 threshold, which would make every subsequent sample read as signal
- [x] `push()` reports when the calibration duration has elapsed, so the
      caller knows when to stop calibrating and start detecting
- [ ] **Open, deliberately deferred:** turning during calibration isn't
      accounted for. If a turn during the calibration window turns out to
      inflate the baseline in practice, that's a small addition here, not a
      redesign — flagging now rather than guessing at whether it matters
      before there's real data to check against.
