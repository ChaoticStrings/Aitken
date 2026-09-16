# Workbench v2 — ticket set

Local-markdown tracker. Source of truth for *what* and *how*:
`Documentation/Core Docs/WORKBENCH_PLAN.md` (read §0 first). Each ticket file
here is a tracer-bullet vertical slice, numbered in dependency order (blockers
first), sized for one fresh context window.

**Frontier now:** 04 or 05 — both unblocked now that 02 and 03 are done
(see `Documentation/Progress Docs/WORKBENCH_TICKET_03_VERIFICATION.md`).
Neither blocks the other; 12 (Simulation + Diff + Adopt) is the first
ticket that will actually exercise 03's `runId`-cancellation path under a
genuine concurrent race, so it's worth keeping in mind but isn't next.

**Working a ticket**
1. Read the ticket, then the plan sections it points at (`Plan §…`).
2. Red → green per acceptance criterion at the seams in Plan §12 only.
3. `npm run build`, open `workbench/dist/index.html`, demo the slice by hand.
4. Write `Documentation/Progress Docs/WORKBENCH_TICKET_NN_VERIFICATION.md`.
5. Tick the criteria here, set `Status: done`, commit, stop.

**Not ticketed, deliberately** (Plan §13): cross-device sync, learned review
rules, cross-session analytics, Android changes, Roughness modal.
