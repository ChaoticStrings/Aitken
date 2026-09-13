# 10 — Split, merge, create segment, multi-select, Smart Filter, bulk actions

**What to build:** `S` splits the selected segment at the hover cursor, `M`
merges it with the next adjacent one, `N` arms drawing a new segment;
shift-click / shift-drag marquee builds a multi-selection; the Tree panel
gets a Smart Filter input with the §7.7 grammar and "Select matching"; the
Inspector's multi-selection variant offers bulk Relabel · Auto-match ·
Tighten all · Confirm all · Delete, each producing exactly one undo step via
the `Batch` Edit (already added in ticket 09).

**Blocked by:** 09

**Status:** ready-for-agent

**Plan sections:** §5.2 (`SegmentSplit/Merge/Create`, `Batch`), §5.6 (`planSplit`, `planMerge`), §7.4 (Multi variant), §7.7, §8.4 (`S`, `M`, `N`, `/`, `Shift`), §11 E-21…E-24, E-26, E-27, E-39.

**Acceptance criteria**
- [ ] `planSplit` returns null outside `(start, end)`; otherwise two segments with fresh stats and provenance `split`, tags re-pointed per §5.2 rule (E-21, E-22). `planMerge` requires adjacency in sorted order (E-23, E-24); merged segment spans both, provenance `merged`, stats recomputed, tags of both re-pointed to the merged id.
- [ ] `S`/`M` keys and Inspector buttons enabled only when the plan is non-null (tooltip explains why otherwise).
- [ ] `N` arms segment drawing: drag on the plot creates a `SegmentCreate` with stats, provenance `created`; drags under 20 ms are ignored. Overlaps with existing segments are allowed and counted in the report (E-39).
- [ ] Multi-selection: shift-click toggles, shift-drag marquee selects all segments and tags whose time intersects the range; `Esc` clears; waveform and Tree highlight the set; count badge.
- [ ] `compileFilter` implements the §7.7 grammar with a table-driven acceptance/rejection test; parse errors report token offset; Tree input `/` focuses, matches highlight live, "Select matching" replaces the selection.
- [ ] Bulk actions build one `Batch`: Relabel (E-27 rule), Auto-match unmatched (`TagRematch` per suggestion, skipping none), Tighten all (skip nulls, report count), Confirm all (`ReviewResolve` for all open items whose target is selected), Delete (confirm dialog listing counts).
- [ ] Hotkeys `1–9` with a multi-selection follow E-27.
- [ ] Demo: filter `M >= 20 AND untagged`, select matching, press `1`, undo once → all gone; split a long segment, merge it back, undo.

**Tests to write first:** `core/splitmerge`, `core/filter` rows of Plan §12; `interaction/machine` (`shift-drag marquees`, `armed segment drag creates`); `app/store` (`bulk relabel is one undo step`).
