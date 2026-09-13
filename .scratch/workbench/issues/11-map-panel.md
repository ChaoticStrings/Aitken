# 11 — Map panel

**What to build:** A Leaflet map panel showing the GPS track coloured by
speed, segment markers sized by duration and coloured by M, a cursor marker
following waveform hover/selection, click-to-select, and a smooth `flyTo` when
the Queue steps to an item (instant under reduced motion). Sessions without
GPS show a clear notice instead of an empty map.

**Blocked by:** 04, 07

**Status:** ready-for-agent

**Plan sections:** §4.2, §6.3, §7.9, §8.7 (flyTo), §11 E-02, E-51.

**Acceptance criteria**
- [ ] `core/geo.ts` pure helpers: `nearestFixIdx(gps, tNs)` (binary search on `tNs`), `speedColour(mps)`, `trackPiecesBySpeed(gps)` → polyline pieces with colours; unit-tested with hand-built fixes.
- [ ] Leaflet is bundled (CSS + JS, marker images inlined); OSM tiles with attribution; no network on `file://` except tiles.
- [ ] Track polyline pieces coloured by 3 speed stops; segment `CircleMarker`s radius `4 + 2·log2(1 + Dsec)` clamped to [4, 14], fill by M colour; click → `selection.primary`.
- [ ] Cursor marker follows waveform hover (throttled to rAF) and snaps to the selected segment's nearest fix on selection change.
- [ ] Queue step → `map.flyTo(fix, zoom ≥ 16, {duration: 0.45})`; duration 0 under `prefers-reduced-motion` (E-51).
- [ ] `no-gps` fixture → panel shows "No GPS in this session" notice; speed features disabled elsewhere (E-02).
- [ ] Panel resize → `invalidateSize` via the layout's `onResize`.
- [ ] Demo: step the queue and watch the map fly; click a marker and see the waveform focus.

**Tests to write first:** `core/geo` unit tests; `app/store` (`selection change updates cursor fix index`); e2e `map marker click selects segment`.
