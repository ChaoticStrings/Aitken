# 12 — Simulation, Diff, Adopt

**What to build:** The Tunables panel: six sliders seeded from `config.json`
(or defaults with a warning), a debounced Run posting to the worker, a dashed
overlay of simulated segments on the waveform, a Diff list (Added / Removed /
Moved / Unchanged) that focuses on click and selects with checkboxes, and
Adopt all / Adopt selected / Adopt additions only — each one `AdoptSimulation`
Edit with tags re-matched and the fallout shown in a confirm dialog first.

**Blocked by:** 03, 10

**Status:** ready-for-agent

**Plan sections:** §2 (Simulation), §5.2 (`AdoptSimulation`), §5.4, §5.7, §7.5, §11 E-04, E-05, E-37, E-38, E-40.

**Acceptance criteria**
- [ ] `core/diff.ts` pairs by ≥ 50 % overlap of the shorter (greedy by overlap desc), classifies `moved` when either boundary differs by more than one sample interval, else `unchanged`; unpaired → `added` / `removed`; unit tests with hand-built cases including a 1-sample move and a 49 % overlap.
- [ ] `buildAdoptEdit(diff, selection, mode, state, signal)` produces one `AdoptSimulation` with fresh stats for `move`/`add`, and `rematch` entries for every tag whose segment is removed or moved, computed with `suggestMatch` against the post-adopt segment list; returns `fallout: TagId[]` of tags left unmatched.
- [ ] Tunables panel: sliders with config-difference dots, Reset to config/defaults, Run (debounced 400 ms on release + button), spinner while the worker runs, stale-run results dropped (E-40).
- [ ] Waveform draws simulated segments as dashed bands in `--sim`; Diff list virtualised (E-38) with counts, coloured groups; clicking focuses viewport; checkboxes feed `selection`.
- [ ] Adopt dialog shows counts (red if added > 3× current) and the fallout list; confirm applies the single Edit (origin `adopt`); undo restores everything in one step.
- [ ] `no-segments` fixture: Report suggests Simulation; Adopt all populates segments and re-matches tags (E-04).
- [ ] Session shorter than calibration → zero results and a notice (E-37).
- [ ] Demo: raise `stdFactor`, see fewer segments, adopt additions only, undo.

**Tests to write first:** `core/diff` row of Plan §12; `app/store` (`adopt simulation rematches tags`, `adopt is one undo step`, `stale run ignored`).
