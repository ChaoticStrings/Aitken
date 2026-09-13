# Post-Ticket-20 field feedback — wayfinder addendum (rev 2)

Revised after Vision's read-through: the M/D/V model explainer, and
per-ticket clarifications on 21–25, plus a new ticket 27.

## Ground-truth check (unchanged from rev 1)

- Ticket 12 is filed and unblocked but **not actually merged** — its
  checklist reads `[x]` but nothing outside `BackupAgent.kt` /
  `ClassifierConfigLoader.kt` calls either module. `TICKET_20_VERIFICATION.md`
  independently confirms "09 and 12 are still outstanding."
- Ticket 11 shipped, folded into ticket 20's commit.
- `.scratch/luna/README.md`'s frontier summary is stale — corrected version
  in rev 1, still holds.
- The attached crash logcat predates the "Fix Ticket 20 bugs" commit; the
  current source already has that fix. Not live.

## The M/D/V model (new — this is the real spine of ticket 26 now)

M (magnitude/impulse), D (duration/distance a feature is "in effect" for),
and V (vehicle speed) aren't independent: for a fixed physical feature,
higher V means shorter D, which reads as sharper, higher M. Aitken's job is
recording and *reactively classifying* real M/D/V relationships from ride
data; Crater's job, later, is *predictive* extrapolation (known M/D at one
V → estimated M/D at a rider's current V, e.g. for a live map warning).
Crater is explicitly out of scope for this map (its own future wayfinder
effort per `map.md`) — noted for context, nothing here builds toward it
directly.

**Velocity is confirmed to need to feed the DSP**, not stay a logged-only
field. This resolves what was previously an open question in ticket 26 and
folds feedback (i) in directly.

## Decisions so far (added this round)

- The "confidence score causes the 0s snap-back bug" hunch doesn't match
  what the current code does — `confidenceLabel` has no writer anywhere.
  Not dismissed, just redirected: ticket 25 now leads with a visual
  marker overlay so the real mechanism is *seen*, not guessed twice.
- Ticket 23's scope split in two: removing the dead confidence UI stays
  23 (trivial, unblocked); the MDV meter that replaces it is now its own
  ticket, 27, typed as a **prototype** — a cheap artifact to ride with and
  tune, not a finished feature, explicitly feeding ticket 26's real
  decision rather than waiting on it.
- Ticket 25 reframed from background-logging research into a real,
  shippable marker-overlay feature — better evidence, ships to riders too.

## Ready-for-agent now (unblocked, small, scope confirmed)

| # | Title | Blocked by | Notes |
|---|---|---|---|
| 21 | Debounce manual tag taps | — | cooldown is now a live-editable Settings slider (200–1200ms), not a fixed constant |
| 22 | Explain calibration + show threshold band | — | draw the calibrated std-threshold as an approximate dashed band; amplitude-vs-impulse question flagged as input to 26 |
| 23 | Remove the dead confidence UI | — | trimmed scope; comment out, don't delete |
| 24 | Wire a folder-grant flow into the app | — | simplified per Vision — plain export to a chosen folder is enough; flagged whether that still means SAF (assumed yes, matches ratified T5) |

**Amend ticket 12**: add `24` to `Blocked by` — its own E2E criterion needs
a grant flow to exist first.

## Prototype + research (feed ticket 26, don't wait on it)

| # | Title | Type | Blocked by |
|---|---|---|---|
| 25 | Visualize tag matches + segment boundaries on the M-graph | prototype/research, HITL QA | soft: after 21 |
| 27 | Live MDV meter with adjustable threshold + decay sliders | prototype, HITL | soft: after 22 |

## The decision (holds up ticket 09)

**26 — Decide the real M/D/V model and what a segment carries for it.**
Now much better specified than rev 1 (the model itself is confirmed;
what's open is how it gets computed, whether segment openness becomes a
continuous decaying score instead of binary hysteresis, and whether gyro-
based speedbreaker profiling is in scope yet). Feeds from 25 and 27's
findings — hold this conversation once both have something to show, not
before.

**Amend ticket 09**: add `26` to `Blocked by`, alongside existing `04, 07`.

## Not yet specified (stays fog)

- The Colab calibration/classifier-fitting notebook — still waiting on
  real session data (25) and the finalized M/D/V formula (26).
- GPS-spatial binning of MDV (per-road-stretch accumulation across many
  riders, closer to what the eventual Crater map needs) — ticket 27
  deliberately builds the session-relative version first; spatial binning
  is closer to Workbench ticket 16's territory than this live view.
- Anything in Workbench (14–18) ticket 26's segment-shape decision might
  touch — unknown blast radius until 26 closes.

## Out of scope (Crater, named explicitly this round)

- Predictive M/D/V extrapolation across riders/speeds, time-of-day
  weighting, live map risk-zone warnings — all Crater-phase, its own
  future wayfinder effort. Understood, not built toward here.

## Recommended order

1. **21, 22, 23, 24** — any order, independently valuable, none touch
   detection/classification logic.
2. **25** (after 21) and **27** (after 22) — can run in parallel with each
   other; both feed 26 with real evidence instead of guesses.
3. **12** — once 24 lands.
4. **26** — once 25 and 27 have something to show. The one most likely to
   produce another surprise ticket if skipped.
5. **09, then 13** — only after 26 closes.
