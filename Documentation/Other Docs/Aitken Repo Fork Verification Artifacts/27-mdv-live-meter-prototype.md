# 27 — Prototype: live MDV meter with adjustable threshold sliders

**Type:** prototype (HITL) — the goal isn't a finished feature, it's a cheap,
concrete artifact Vision can actually ride with, to make ticket 26's
decision (the real M/D/V formula, decay behavior, what feeds ClassifierRunner)
from evidence instead of from a whiteboard. Its formula is disposable;
expect it to be replaced or heavily rewritten once 26 closes.

**What to build:** Replaces the slot ticket 23 emptied out — a graph that
fills with the session's current MDV value, colored across
Vision-adjustable green/amber/red zones (sliders in Settings, not a fixed
0–100 scale), so thresholds can be tuned by eye against real roads before
ticket 09 has to pick presets. These same three colors are the intended
future map-coloring scheme for Crater, per Vision's brief — worth keeping
that naming (not "mild/moderate/severe") consistent from here on.

**MDV as understood from Vision's explainer** (confirm this reading before
building): M (magnitude/impulse), D (duration/distance the feature is
"in effect" for), and V (vehicle speed) are related — for a fixed physical
road feature, higher V means less time in contact (shorter D), which reads
as sharper, higher M. The three aren't independent; a segment isn't fully
described by any one of them alone.

**What this prototype does NOT need to get right (that's ticket 26):**
- The real combining formula for M, D, and V into one score
- Whether it's session-relative (a live number that rises and falls as you
  ride) or spatially binned to GPS location (a per-road-stretch value that
  accumulates across many riders over time, which is closer to what the
  eventual Crater map needs, and closer to Workbench ticket 16's territory
  than this live in-session view) — **recommend building the session-
  relative version here**, since it's buildable today and still gives
  Vision the tuning feedback loop; flag GPS-spatial binning as fog for
  Workbench/Crater
- The exact decay shape — see below, make it a knob, not a fixed answer

**Blocked by:** None to start (all inputs — vertical accel magnitude, GPS
speed, segment open/close events — already reach `RecordingPipeline`).
Loosely sequenced after ticket 22, since both touch the same graph area.

**Status:** ready-for-agent (prototype — expect rework after 26)

- [ ] A placeholder MDV formula, clearly marked `[CALIBRATE]` /
      throwaway in code comments: something like current magnitude scaled
      by a speed factor, decaying toward zero when no segment is open —
      good enough to react visibly on a real ride, not claimed as correct
- [ ] **Decay is itself a slider** (Vision's "frustration doesn't just
      vanish, it fades" framing) — a fourth adjustable value alongside the
      three threshold sliders, so the decay *rate* is also something to
      tune empirically here rather than guessed once and locked in
- [ ] Three threshold sliders in Settings (green/amber/red boundaries),
      persisted like the rest of `Tunables`, live-editable mid-session for
      the same reason as ticket 21's cooldown — none of this touches the
      recorded data, only how it's displayed and bucketed live
- [ ] The fill graph replaces the row ticket 23 emptied, updates live as
      MDV rises and decays
- [ ] Nothing here is written to the session/label files as if it were a
      real classification — this is a display-and-tuning tool, not
      `ClassifierRunner`'s output; keep that boundary explicit in code so
      ticket 09 doesn't accidentally inherit a placeholder formula
