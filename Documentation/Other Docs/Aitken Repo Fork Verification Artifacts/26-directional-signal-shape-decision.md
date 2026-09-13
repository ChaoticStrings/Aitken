# 26 — Decide the real M/D/V model and what a segment carries for it

**Type:** grilling (HITL) — a decision to make *with* Vision, informed by
ticket 25's overlay findings and ticket 27's prototype meter, not resolved
by silent agent choice.

**The model, as explained (restated to confirm the reading is right
before it's built on):**
- **M** — magnitude/impulse of a road feature's effect on the vehicle
- **D** — duration/distance the feature is "in effect" for
- **V** — vehicle speed
- These three aren't independent: for a fixed physical feature, higher V
  means less time in contact (shorter D), which reads as sharper, higher M.
  A segment isn't fully described by any one of them alone.
- Aitken's job (this app, this phase) is to **record and reactively
  classify** real M/D/V relationships from ride data. Crater's job (later,
  out of scope for this map — its own future wayfinder effort per
  `map.md`) is to **extrapolate predictively**: given a known M/D at one
  V for a location, estimate M/D at a rider's current, different V, and
  warn before a "minor jostle" becomes a "loss of control" event. Noted
  here for context, not built here.

**What this resolves, concretely, for tickets already on the books:**

- `SegmentDetector.peakM` is currently `abs(vertical)` — **unsigned**, and
  doesn't yet incorporate speed at all. Confirmed with Vision: **velocity
  must feed the DSP**, not stay a logged-only field as it is today. Open:
  does `SegmentDetector` itself take a speed input, or does something
  downstream combine `ClosedSegment` + the speed already written alongside
  it? Either is defensible — decide deliberately, it's a scoped-
  replaceability call.
- **Segment openness itself may need to change shape.** Today, a segment
  stays open/closed based on a binary std-threshold-plus-fixed-hysteresis
  rule (`endQuietMs` = 500ms flat cutoff, plus a 30s rolling window that
  decays implicitly and slowly by construction). Vision's "frustration
  decays after smooth road, builds on rough road" framing — and ticket 25's
  hypothesis that this same slow implicit decay may be *why* two separate
  rough patches ~20s apart can read as one, or a segment can cut off mid-
  patch — points toward replacing the fixed-window/fixed-cutoff hysteresis
  with an explicit, tunable decay curve driving a continuous MDV score,
  rather than a binary open/closed state. Ticket 27's prototype exists to
  make this tangible before deciding it for real here.
- Nothing currently consumes gyroscope data for classification (only yaw
  rate, to suppress false starts during turns) — whether a speedbreaker
  profile is in scope for the ticket right after this one, or stays fog
  until real ride data exists to shape it against, same caveat ticket 19's
  noise-floor calibration already had.
- The M/D graph's "D" bar (current `AitkenSessionScreen`) is per-segment
  *duration*, colored by an unsigned severity tier — a different thing
  from the continuous MDV meter ticket 27 is prototyping. Recommend
  deciding here whether the duration-bar strip still earns a place once
  27's meter exists, or gets folded into it.
- Ticket 22 surfaced an amplitude-vs-std-vs-impulse question in how
  calibration's threshold gets visualized — same underlying question as
  above (what exactly is M measuring, and does V change what "above
  threshold" means at all).

**Blocks:** Ticket 09 — add "26" to its `Blocked by` line, alongside its
existing `04, 07`. 09's own scope ("applies config to a closed segment")
isn't specifiable until "segment" (and its relationship to M/D/V) is.

**Feeds from:** Ticket 25's overlay findings, ticket 27's prototype meter
and its tuned decay/threshold values.

**Status:** ready-for-agent (grilling) — hold this conversation after 25
and 27 have something to show, not before.

**Open questions to work through, breadth-first:**
- [ ] Confirm the M/D/V relationship as restated above is the right
      reading before any of it gets built into `SegmentDetector`
- [ ] Does speed modulate the detection *threshold* itself (a bump at
      5 km/h and 40 km/h reads very differently in raw accel terms), the
      *reported M value*, or both?
- [ ] Replace segment open/close's binary hysteresis with a continuous
      decaying MDV score — yes/no, and if yes, is the decay curve shape
      itself something `Tunables` exposes (matching ticket 27's slider) or
      something the eventual Colab notebook fits from real data?
- [ ] Gyroscope-based speedbreaker profiling — ticket now, or fog until
      more ride data exists?
- [ ] Does the duration-bar strip survive alongside ticket 27's MDV meter,
      get replaced by it, or serve a genuinely different purpose worth
      keeping both?
- [ ] Whatever segment shape comes out of this — breaking change to
      `ClosedSegment`'s existing consumers (`TagMatcher`, the session file
      schema, the not-yet-built Workbench tickets 14–18)? Surface that
      cost now, not mid-09
