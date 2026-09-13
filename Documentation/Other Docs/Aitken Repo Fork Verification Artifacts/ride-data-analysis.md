# Reading session_20260904_083423 and session_20260904_102725

Both unzip to the standard four-file layout (`sensor.csv`, `gps.csv`,
`segments.csv`, `labels.csv`). Analysis below replays the exact
`RollingStats`/`NoiseFloorCalibrator`/`SegmentDetector`/`TagMatcher` logic
in Python against the raw rows — not just reading what the app already
wrote — so it can check the app's own output against what the algorithm
should have produced.

## 1. The 0ms-late bug — confirmed, with a mechanism now

Every single tap in both files matches `segment_start_ns` to one
pathologically long segment, never anything else:

- **083423 (shape test)**: all 6 taps (Pothole, Bump ×2, Speedbreaker,
  RANGE_START, RANGE_END) match the same segment — one that opened at
  13.46s and didn't close until the session ended at 118.1s (**104.6s
  long**).
- **102725 (road test)**: both taps (Pothole, Bump) match a segment
  running from 61.8s to 118.3s (**56.4s long**). A second segment later in
  the same file runs 35s at essentially a dead stop (speed 0.1 m/s) —
  same failure mode, just not tapped against.

This is exactly why `tap_offset_ms` reads 0 for every row: `TagMatcher`
isn't broken — matching a tap to a segment that's still open by
construction gives 0ms offset. The segment simply never closes long enough
to exercise the closed-segment lookback path at all, so the 8s window
never actually gets tested by real data yet.

**Mechanism, isolated on the road-test data:** replaying
`NoiseFloorCalibrator` on the first 10s of `sensor.csv` (assuming default
`Tunables` — see the caveat in §4) gives `shortStdThreshold=5.540`,
`longStdThreshold=4.522`. Feeding the rest of the session through that
threshold, sample by sample, finds a genuine **9.82-second quiet gap**
(62.56s–72.38s, confirmed by scanning every sample, not a coarse check)
where neither window crosses threshold — meaning *with these thresholds*,
the segment should have closed around 62.56s and reopened around 72.38s as
two separate events, matching the "0.05–2s, cleanly separated" shape every
other segment in this file already has. It didn't, on the real device.
That gap is real in the raw numbers; the live app just used a lower
effective threshold than this replay assumes. §4 has the fix.

## 2. What actually happened at each tap (bypassing the broken segment)

Since segment-level attribution is broken, the tap timestamps themselves
plus the raw waveform are the only reliable signal. For each tap, I
searched an 8s window (matching `TagMatcher`'s own lookback) for the true
local peak (`argmax |vertical|`) independent of segment boundaries:

| Event | True peak `vertical` | GPS speed at that moment | Tap landed |
|---|---|---|---|
| Pothole (road) | 62.34 m/s² | 5.48 m/s (19.7 km/h) | 1074ms after |
| Bump (road) | 47.80 m/s² | 4.83 m/s (17.4 km/h) | 2049ms after |

Both tap delays land comfortably inside the 2–7s range from the original
feedback, and both peaks independently agree with `segments.csv`'s own
`peak_m` for the same window (62.345, 47.80) — two different methods
converging on the same number is a good sign the identification is right,
not an artifact of my search window.

The chart above shows both events, aligned on their true local peak (t=0).
Worth reading carefully: **the Pothole event stays large and multi-signed
for ~150ms on either side of the peak** (repeated swings past ±40, even
one down to -37.5), while **the Bump event is a shorter, more isolated
single spike** with calmer shoulders. That's a real, measurable
difference — but it's a *duration/sustained-energy* distinction, not the
"dip-then-rise vs. rise-only" sign pattern from the original feedback.
Both events' single largest sample is positive in this data. One example
of each isn't enough to generalize from, but it's worth knowing the
simple sign-pattern model didn't show up cleanly in the one real pair
available yet.

## 3. The shape test may not transfer to real road shapes

This is the most important methodological finding, and it works against
using 083423 to inform the DSP shape question directly. All four
point-tag events in the shape test (Pothole, Bump ×2, Speedbreaker) — hand-
shaken, not on a mount — show the *same* signature regardless of label:
~100-200ms of near-flat, small dip, one massive single-sample spike,
sharp reversal, settle. They differ in magnitude (Pothole's peak was
actually the *smallest* of the four: dev +18.28 vs. Bump's +45/+43 and
Speedbreaker's +60.73) but not in shape. That's consistent with a quick
hand flick — an impulsive, single-sample event — not with a real wheel-
suspension interaction, which (per the road test's Pothole/Bump above)
unfolds over 100–400ms with several oscillation cycles as the suspension
compresses and rebounds. A hand has no suspension to mediate the motion.

Net: the shape test is good evidence the tap/button mechanics work and
good evidence for §1's bug, but I wouldn't treat its waveforms as stand-
ins for what a real pothole vs. bump vs. speedbreaker looks like — worth
knowing before it anchors any part of ticket 26's shape discussion.

The 40s "rough stretch" range tag (RANGE_START→RANGE_END) is more useful
as-is: 31 of 41 one-second buckets read as genuinely active (mean
deviation > 1.0), 10 read as quiet gaps — a real human's shaking pattern
naturally has pauses in it. That's direct, real support for the
decaying-MDV-instead-of-binary-hysteresis idea from ticket 26 — a hard
500ms cutoff would have chopped this single intended "rough stretch" into
several, the same failure mode as §1 just in the other direction.

## 4. Why I can't fully close out the threshold question — and the fix

The replay above assumes default `Tunables` (`stdFactor=3`,
`floorStd=0.05`, 10s calibration) because **the session files don't record
which settings were actually active during capture**. If either ride
used different values (lower `stdFactor` especially), that alone would
explain why the real device's effective threshold was lower than what
this replay calculates — and I can't rule that out from the CSVs alone.

Concrete suggestion: have `SessionRecorder` write a small `config.json` (or
similar) alongside the four CSVs, capturing the `Tunables` and the
session's own calibrated thresholds (`shortStdThreshold`,
`longStdThreshold` — already computed once, just never recorded). Cheap,
and it turns "which settings produced this data" from a guess into a fact
every time, not just for this pair of files.

## 5. A concrete M-scale anchor, and a formula concern that affects it

Vision's ground truth: the deepest pothole encountered reads as **M6**.
The best candidate for that moment in the data is the Pothole tap's true
peak above: **peakM = 62.34** (using the code's own definition,
`abs(vertical)` — see below for why that number is a little inflated).
For the low end, a confirmed-quiet 9.8s stretch of real road (§1's gap)
gives a real noise-floor reference: mean `|vertical|` ≈ 9.75, but with
occasional single-sample spikes up to 22.48 even while genuinely quiet.

That noise-floor spike is worth flagging on its own: `SegmentDetector`'s
`peakM = abs(vertical)`, not `abs(vertical - 9.81)`. Two consequences —
one inflates the M6 estimate above, one is a real gap:
- Every `peakM` includes the constant ~9.81 gravity offset baked in, so
  62.34 overstates the *dynamic* part of the event by roughly that much
  (the deviation-from-baseline framing puts it closer to +52.5). Worth
  deciding which framing "M6" should anchor to before it's load-bearing
  for anything.
- More importantly: a single noisy sample that happens to swing to
  `vertical ≈ 0` (a huge dynamic event — net acceleration briefly
  cancels gravity entirely) reads as `peakM ≈ 0` under the current
  formula, i.e. the *opposite* of what actually happened. `abs(vertical -
  9.81)` doesn't have this blind spot. This is a real, findable-from-the-
  code issue, not just a framing preference, and it's upstream of any
  scale ticket 26 fixes.

## What I'd want next

- Confirm whether either capture used non-default `Tunables` — settles §4
  outright, no more guessing needed.
- A short session with `config.json` (§4) plus a few more tapped
  potholes/bumps/speedbreakers on the actual mount — one example of each
  isn't enough to trust the §2 shape distinction or rule out the simple
  sign-pattern model; it just didn't show up in this one pair.
- No real Speedbreaker or Rough-stretch tags exist yet in the *road* data
  — only in the (likely non-representative) shape test — worth a
  deliberate pass at those two specifically next time out.
