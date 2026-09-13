# 14 — Dockable layout: dockview host, presets, persistence

**What to build:** Replace the fixed grid with `DockviewLayoutHost`: every
panel draggable by its tab to any edge, into any tab group, or floating;
resizable with minimums; two presets (Review, Inspect); "Reset layout";
arrangement persisted per device and restored on load, falling back to the
Review preset if incompatible.

**Blocked by:** 04, 07, 11

**Status:** ready-for-agent

**Plan sections:** §4.2 (`LayoutHost`), §5.12, §6.5 (`layouts`), §8.1, §8.3, §8.7 (dock motion), §11 E-45, E-46.

**Acceptance criteria**
- [ ] `LayoutHost` interface implemented by `DockviewLayoutHost`; panels registered by `PanelId` with `render(el) → dispose`; `show/hide/focus`; `serialize/restore`; `applyPreset`; `onResize` drives canvas and map `invalidateSize`.
- [ ] Presets `review` and `inspect` match §8.1 exactly (integration test asserts visible panel set and relative positions via `serialize()`).
- [ ] Layout persisted to `layouts['desktop']` debounced 500 ms; restore on load; `restore` returns false for garbage / missing panel ids / dockview version mismatch → preset applied silently (E-45).
- [ ] Minimum panel size 220×120; floating panels allowed; closing a panel hides it (re-openable from a top-bar "Panels" menu with checkmarks).
- [ ] Dock/undock motion per §8.7 via dockview's CSS hooks; 0 ms under reduced motion.
- [ ] Top bar "Layout" menu: Review · Inspect · Reset.
- [ ] Demo: drag Inspector to the left, float the Map, reload → identical; Reset → Review preset.

**Tests to write first:** `layout/LayoutHost` row of Plan §12 (`serialize/restore round-trip`, `restore of garbage returns false`, `preset shows expected panels`); e2e `layout survives reload`.
