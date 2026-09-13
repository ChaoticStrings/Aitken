# Workbench v2 — ticket set

Local-markdown tracker. Source of truth for *what* and *how*:
`Documentation/Core Docs/WORKBENCH_PLAN.md` (read §0 first). Each ticket file
here is a tracer-bullet vertical slice, numbered in dependency order (blockers
first), sized for one fresh context window.

**Frontier now:** 02 (ticket 01 done — see
`Documentation/Progress Docs/WORKBENCH_TICKET_01_VERIFICATION.md`).
After 02 lands: 03, 04, 05 are all unblocked and independent.

**Working a ticket**
1. Read the ticket, then the plan sections it points at (`Plan §…`).
2. Red → green per acceptance criterion at the seams in Plan §12 only.
3. `npm run build`, open `workbench/dist/index.html`, demo the slice by hand.
4. Write `Documentation/Progress Docs/WORKBENCH_TICKET_NN_VERIFICATION.md`.
5. Tick the criteria here, set `Status: done`, commit, stop.

**Not ticketed, deliberately** (Plan §13): cross-device sync, learned review
rules, cross-session analytics, Android changes, Roughness modal.
