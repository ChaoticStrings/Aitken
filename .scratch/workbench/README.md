# Workbench v2 — ticket set

Local-markdown tracker. Source of truth for *what* and *how*:
`Documentation/Core Docs/WORKBENCH_PLAN.md` (read §0 first). Each ticket file
here is a tracer-bullet vertical slice, numbered in dependency order (blockers
first), sized for one fresh context window.

**Frontier now:** 03, 04, or 05 — all unblocked and independent now that 02
is done (see `Documentation/Progress Docs/WORKBENCH_TICKET_02_VERIFICATION.md`).
03 (detector/recompute) is the highest-risk of the three and the one most of
the later tickets ultimately sit behind — worth doing before 04/05 rather
than purely by number order, but nothing blocks starting with 04 or 05
first if that's preferred.

**Working a ticket**
1. Read the ticket, then the plan sections it points at (`Plan §…`).
2. Red → green per acceptance criterion at the seams in Plan §12 only.
3. `npm run build`, open `workbench/dist/index.html`, demo the slice by hand.
4. Write `Documentation/Progress Docs/WORKBENCH_TICKET_NN_VERIFICATION.md`.
5. Tick the criteria here, set `Status: done`, commit, stop.

**Not ticketed, deliberately** (Plan §13): cross-device sync, learned review
rules, cross-session analytics, Android changes, Roughness modal.
