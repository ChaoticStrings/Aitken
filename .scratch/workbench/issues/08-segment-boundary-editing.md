# 08 — Segment boundary editing: drag, snap, tighten, live stats

**What to build:** A selected segment shows handles on the waveform; dragging
one moves the boundary with snap-to-threshold-crossing (Alt disables), commits
one `SegmentMove` on release with peak/RMS recomputed as `abs(v − 9.81)` over
signal-true samples; `T` tightens the selected segment(s) to where the signal
actually exceeds the calibrated threshold; the Inspector's Segment variant
shows editable start/end inputs, live vs recorded magnitude, Severity bucket,
and a divergence note.

**Blocked by:** 04, 05

**Status:** ready-for-agent

**Plan sections:** §5.2 (`SegmentMove`), §5.3, §5.6 (`tightenBounds`, `snapBoundary`), §5.7 (`signalMask`), §6.3, §7.3 (handles, snap), §7.4 (Segment variant), §8.4 (`T`, `Alt`), §8.7 (invalid-input shake), §11 E-18…E-20.

**Acceptance criteria**
- [ ] `segmentStats` computes peak/RMS of `abs(vertical − 9.81)` over `[start, start+dur)`, optionally restricted by `signalMask`; hand-traced tests including a lull sample excluded by the mask and an empty range.
- [ ] `signalMask` from ticket 03's calibration is computed once per session in the worker and cached (`derived` store); `ThresholdView` assembled in the store.
- [ ] `snapBoundary` finds the nearest rising (start) / falling (end) mask edge within ±`settings.snap.windowMs` (150 ms default); returns the candidate unchanged if none; tests on hand-built masks.
- [ ] `tightenBounds` shrinks to first/last mask-true sample inside the segment; null when none → inline message, no Edit (E-20).
- [ ] Interaction machine: `down` on a handle of a selected segment → `beginDragBoundary`; `move` → `dragBoundary` with snapped `ns` (unless `alt`); `up` → `commitBoundary` producing exactly one `SegmentMove` with stats. Start may never pass end: clamp to ≥ 1 sample (E-18); clamp to session bounds (E-19). Drags that end where they started produce no Edit.
- [ ] A faint snap tick and a live duration label render during drag (DOM overlay, not canvas).
- [ ] Inspector Segment variant: `mm:ss.mmm` inputs commit on blur/Enter as one `SegmentMove`; invalid → shake + inline reason; live peak/RMS with greyed recorded values when different; Severity bucket from config; divergence note; buttons Tighten · Delete · Confirm (Split/Merge disabled until ticket 10).
- [ ] `T` applies tighten to the primary selection (multi-select support arrives in 10 but the code path must accept an array).
- [ ] Demo: drag a boundary with and without Alt, watch it snap; tighten a segment with a long quiet tail; undo both.

**Tests to write first:** `core/stats`, `core/tighten` rows of Plan §12; `interaction/machine` (`down on handle begins boundary drag`, `alt disables snap`, `zero-length drag emits no edit`, `start clamped before end`).
