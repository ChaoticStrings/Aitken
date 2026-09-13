# 13 — Export Clean Bundle, self-verify, re-import with review state

**What to build:** Export from the top bar: refuse with a list if blocking
queue items are open, warn if advisory ones are; build the zip in the worker
with byte-identical pass-through of raw files plus Workbench-written
`segments.csv`, `labels.csv`, `review.json`; re-read and verify before
offering the download; set EXPORTED. Importing a Clean Bundle restores the
Journal and review state when the source hash matches.

**Blocked by:** 05, 07, 09

**Status:** ready-for-agent

**Plan sections:** §0.8, §5.10, §6.4, §7.6, §11 E-16, E-41…E-44.

**Acceptance criteria**
- [ ] `segmentsToCsv` / `labelsToCsv` write schema v1 headers exactly; bigint ns written exactly; magnitudes 3 dp; `speed_mps` empty when null; unmatched tags write empty `segment_start_ns` and `tap_offset_ms`; sorted by time. Round-trip test: parse(write(state)) deep-equals state.
- [ ] `buildReviewSidecar` matches §6.4 including full journal, counts derived from the journal, per-segment provenance/confirmed map (confirmed = a `ReviewResolve confirmed` exists for any item targeting that segment).
- [ ] `buildCleanBundle` copies pass-through files as the original `Blob`s (E-41); folder name sanitised (E-42); `review.json` keeps the original name.
- [ ] `verifyCleanBundle` re-reads the zip, SHA-256-compares each pass-through entry to the source, parses `segments.csv`/`labels.csv` and deep-equals state; a test corrupts one byte and asserts failure with the entry named (E-43).
- [ ] Export flow per §7.6: blocking → refuse dialog with "Go to first"; advisory → "Export anyway?"; verify fail → no download, dialog with first 3 mismatches; success → download, `ReviewStateSet('EXPORTED')`, toast. Mobile fallback to `navigator.share` / long-press link (E-44).
- [ ] Import: `review.json` present + hash match → journal restored, cursor at end, review state from journal; hash mismatch → import without journal + `REVIEW_SIDECAR_STALE` warning (E-16). Clean Bundle cards show a "clean" badge in the Library.
- [ ] Demo: review the fixture to REVIEWED, export, re-import the exported zip into a fresh profile → same state, EXPORTED badge.

**Tests to write first:** `worker/export` row of Plan §12; `app/store` (`export refused while blocking open`, `reimport restores journal`, `stale sidecar warns`); e2e `export downloads verified zip`.
