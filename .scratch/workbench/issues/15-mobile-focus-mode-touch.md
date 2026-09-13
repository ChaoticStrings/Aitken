# 15 — Mobile focus mode, touch adapter, bottom sheet

**What to build:** Below 760 px (or coarse pointer under 1024 px) the app
swaps to `SheetLayoutHost`: compact bar, full-bleed waveform with a minimap
strip, and a draggable bottom sheet with tabs Queue · Inspect · Map · Tree and
snap points peek/half/full. `TouchPointerAdapter` delivers pan, pinch, tap,
long-press-grab, marquee, two-finger context and swipe gestures. The entire
review workflow (queue, label, confirm, boundary drag) is completable on a
Pixel-7-class phone.

**Blocked by:** 08, 09, 14

**Status:** ready-for-agent

**Plan sections:** §1.3 D4, §4.2 (`LayoutHost`, `PointerAdapter`), §5.12, §5.13, §7.3 (touch hit radii), §8.2, §8.3, §8.5, §8.7 (sheet snap), §11 E-46, E-47, E-52.

**Acceptance criteria**
- [ ] Breakpoint evaluation on resize (debounced 150 ms) swaps hosts without losing store state; layout persisted under `layouts['mobile']` (just active tab + snap point).
- [ ] `SheetLayoutHost`: snap points 72 px / 50 % / 92 %; velocity-based snapping; landscape → right drawer 40 %; tabs render the same panel components as desktop.
- [ ] Peek state shows current queue item title + label chips + ✓ ✕ ⌫ ‹ › actions; swipe on the peek header steps the queue; swipe on a queue row confirms/dismisses with an undo toast.
- [ ] `TouchPointerAdapter` emits §5.13 gestures: 1-finger drag, pinch (`scale`, `cx`), tap, long-press 350 ms (cancelled by > 8 px movement), two-finger tap; unit-tested with synthetic `PointerEvent` sequences.
- [ ] Interaction machine handles touch intents: pan, pinch-zoom about centre, tap-select, long-press-on-handle → boundary drag with snap (snap chip toggles), long-press-on-empty → marquee, two-finger tap → context sheet (Split here / Add point / Add range / Add segment).
- [ ] Hit radii doubled for touch; all tap targets ≥ 40 px; text ≥ 12 px.
- [ ] Rotation or breakpoint change mid-drag cancels the drag with no Edit (E-47); large tablets with coarse pointer keep the dock layout but use the touch adapter (E-52).
- [ ] Demo (Playwright Pixel 7): import fixture, pinch zoom, long-press-drag a boundary, clear three queue items by swipe, export via share fallback.

**Tests to write first:** `interaction/machine` (`longpress on handle begins drag (touch)`, `pinch zooms about centre`, `movement cancels longpress`), `TouchPointerAdapter` unit tests, e2e mobile flow.
