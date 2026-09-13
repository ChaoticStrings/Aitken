# 25 — Visualize tag matches and segment boundaries on the M-graph

**What to build:** Reframed from a pure background-logging research ticket
into a real, shippable debugging feature: draw what actually happened
directly on the waveform, so the "does it snap back correctly" question is
answerable by looking at the screen while riding, not by pulling logs
afterward.

- [ ] A vertical marker (red line) on the M-graph at the point a manual tap
      was matched to — using `TagMatch.Matched.segmentStartNs`, not the
      tap's own timestamp, so a correct snap-back is visibly a line
      appearing *behind* the current playhead, not at it
- [ ] A translucent colored zone spanning a rough-stretch range tag's
      matched start/end
- [ ] An unmatched tap (`TagMatch.Unmatched`) gets a distinct visual (e.g.
      a marker at the tap point itself, styled differently) so "no segment
      found" is also visible, not silent

**On Vision's hunch that this ties to the confidence score:** worth
flagging plainly, since it changes where this ticket should start looking —
reading the current source, `AitkenUiState.confidenceLabel` has no writer
anywhere in the app except its own `"—"` default. There's no code path
today where a confidence candidate being detected changes how `TagMatcher`
resolves a tap; `TagMatcher.match()`'s open-segment vs. closed-segment-
lookback branch is the only branching that exists. Possible the hunch is
carrying over from Prototype 1's debug harness, which this ticket hasn't
read — worth a quick check there before ruling it out entirely, but as far
as *this* codebase goes, the mechanism described isn't present to test.
Between this and the hysteresis hypothesis below, the visual overlay above
should make it obvious which one (if either) is actually happening, rather
than this ticket having to guess.

**A second, related question this surfaced:** Vision's report that two
rough patches within ~20s of each other may register as one, and the
reverse — a segment cutting off mid-patch. Reading `SegmentDetector`, this
is plausible: the long window (`longStdThreshold`, 3000 samples ≈ 30s at
125Hz) decays slowly by construction — old high-std samples only age out
as the window fills with new ones, which is an *implicit* decay baked into
a fixed window size, not a tunable curve. That's a strong candidate
explanation for both directions of the complaint, and it's the same
mechanism ticket 26 and ticket 27 are already looking at (Vision's decay-
curve / "frustration" framing). Recommend this ticket's overlay explicitly
show segment open/close boundaries too, not just tag markers, so both
questions get answered by the same feature.

**Prompted by:** field feedback (d), second half, and Vision's follow-up
clarifications (a), (b), (c).

**Blocked by:** None to start. Recommend after ticket 21 (debounce), so
double-taps don't confuse what's being watched on the overlay.

**Status:** ready-for-agent

- [ ] Manual QA: ride with the overlay active, deliberately tap late (2–7s
      after a felt bump) on a genuinely separate, well-spaced series of
      bumps, and confirm by eye whether markers land on the correct past
      peak or all cluster at zero
- [ ] Manual QA: ride two genuinely distinct rough patches ~15–25s apart
      and observe whether the segment boundary overlay shows one merged
      zone or two — direct evidence for the merge question above
- [ ] Findings (confirmed mechanism, not just "seems to work now") feed
      ticket 26 as its starting evidence
