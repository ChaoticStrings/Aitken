# 04 — Waveform, minimap, LOD pyramid, viewport (read-only)

**What to build:** Opening a session from the Library shows the Workspace
with a DPR-correct canvas waveform of the selected channel, recorded
segments as M-coloured bands, tags in a lane, a minimap of the whole session
with the viewport window, mouse pan/zoom/fit, channel switcher, and hover
readout. No editing yet. Fixed CSS-grid layout for now (dockview arrives in
14); panels are already separate Preact components with stable `PanelId`s.

**Blocked by:** 02

**Status:** ready-for-agent

**Plan sections:** §4.1, §4.2 (ChannelProvider, PointerAdapter seams), §4.5, §5.9, §5.13, §6.3, §7.3, §8.4 (`F`, `+`, `-`, `[`, `]`, `Esc`), §8.6, §11 E-06, E-14.

**Acceptance criteria**
- [ ] `buildLod` builds min/max levels at strides 2,4,8,… in the worker for the current channel; `pickLevel` chooses the level for the current samples-per-pixel; the draw loop never iterates more than `2 × width` points per trace (assert via an instrumented counter in a unit test on `draw.ts`'s data-selection helper).
- [ ] `RecordedChannels` provider exposes `vertical, jerk, rollStd, ax, ay, az, gx, gy, gz`; `RecomputedChannels` exposes `vertical*, jerk*, rollStd*` from ticket 03; channels with no data (empty gyro) are listed greyed with a reason (E-06).
- [ ] Viewport in the store: `startSec/endSec`, clamped to session, min span 50 ms; zoom anchored at cursor; keyboard `F` fit, `+`/`-` zoom, `[`/`]` step selection through segments in time (selection is read-only highlight here).
- [ ] `MousePointerAdapter` emits Plan §5.13 gestures; `interaction/machine.ts` handles `pan` and `wheel-zoom` intents only in this ticket (others come later) and is unit-tested with scripted sequences.
- [ ] Hit testing (`hittest.ts`) implements the §7.3 priority order and is unit-tested with a synthetic layout; hover shows a DOM tooltip with time, value, and hit target.
- [ ] Selection ring is a DOM overlay positioned from the viewport mapping, not drawn on canvas.
- [ ] Minimap draws the full-session envelope from the coarsest LOD level and the viewport window; dragging the window pans; clicking jumps.
- [ ] Canvas resizes via `ResizeObserver`; redraws coalesced into one rAF; DPR handled (e2e screenshot at DPR 2 has no blur — checked by hand and noted).
- [ ] Demo: open fixture, pan/zoom smoothly, switch channels, hover readout, minimap sync.

**Tests to write first:** `worker/lod` row, `interaction/machine` (`drag on empty pans`, `wheel zooms about cursor`), `hittest priority order`, `viewport clamps and min span`.
