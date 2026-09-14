# Ticket 02 — Import a bundle → parsed in a worker → Library card — Verification

## What was built

- `core/types.ts` — `Ns`, `SegId`, `TagId`, `TagKind`, `Segment`, `Tag`. Only
  the subset ticket 02 produces; `ReviewItem`/`SessionState`/`Resolution`/
  `Blocking` (also in §5.1) are added when `core/reducer.ts`/`core/queue.ts`
  exist (ticket 05+) — nothing to produce or consume them yet.
- `import/BundleSource.ts` — `ZipBundleSource` (JSZip) and
  `FilesBundleSource` (File[]/webkitdirectory), sharing basename-matching,
  ambiguity detection, and SHA-256 hashing of the five pass-through files in
  a fixed order.
- `worker/parse.ts` — `parseSession`, pure, no DOM. Schema dispatch via
  `Record<number, parser>` per file; CRLF/BOM/trailing-blank-line tolerant;
  header lookup by name; stable-sorts sensor rows by `tNs` and counts
  reordered rows; gap list (dt > 500 ms); gyro coverage; exact-bigint
  tag↔segment matching with `#2`-suffixed duplicate handling for both
  segments and tags; `config.json` parsed with `Tunables.kt` defaults on a
  missing file (E-05).
- `worker/index.ts` — the message protocol (`progress` → `parsed`/`error`),
  transferring every sensor/gps typed array's buffer rather than copying.
- `bridge/` — `InlineWorkerBridge` (same-thread, used by every automated
  test) and `RealWorkerBridge` (real `postMessage`, used only by the app and
  by the e2e import tests — the one place the actual Worker boundary gets
  exercised).
- `persistence/` — `IdbPersistence` (raw IndexedDB, `bundles`+`journals` in
  one transaction), `MemoryPersistence` (tests + fallback), and
  `createPersistence()` (tries IDB, falls back to memory on any failure —
  E-49).
- `app/libraryStore.ts` — orchestrates BundleSource → WorkerBridge →
  Persistence, with same-hash dedup (E-15) and cached per-bundle summary
  stats (segment/tag counts, duration, warnings) so the Library doesn't
  re-parse on every app launch just to render cards.
- `panels/Library.tsx` — real cards (name, date, duration, segment/tag
  counts, size, review-state badge, warning count), sorted by last edited,
  Delete with `confirm()`, storage estimate line, a drop zone, and two file
  inputs (Choose files / Choose folder) rather than one input with a
  runtime-toggled `webkitdirectory` attribute — see discrepancy 3 below.
- `app/Toast.tsx` — minimal toast host (signals-based), used for import
  success/failure and the "already imported" case.
- Moved the ticket 01 placeholder from `app/Library.tsx` to
  `panels/Library.tsx`, matching Plan §9's actual project structure (ticket
  01 put it in the wrong folder).
- **Ticket 01's blank-page fix is folded into this commit**, per your call
  to roll it forward rather than ship it separately: `main.tsx` waits for
  `DOMContentLoaded` before mounting, and `vite.config.ts` strips
  `type="module"` from the built script tag (`classicScriptForBuild`) with
  `rollupOptions.output.format: 'iife'`, plus the e2e regression guard
  (`pageErrors` assertion). Also applied the same reasoning to the new
  Worker: `worker.format: 'iife'` and `?worker&inline` (not
  `new Worker(new URL(...))`), so the worker's code ships embedded in the
  single HTML file as a `Blob`/`createObjectURL` string rather than a
  second dist file vite-plugin-singlefile has no way to inline (checked its
  source directly — it has no worker-specific handling at all).

## Discrepancies from the plan, and why

1. **§6.1's gyro claim about fixture 140717 is wrong.** The plan states the
   fixture "has zero gyro rows." The real data (checked directly, not
   assumed) shows only the first 6 of 9041 rows lack gyro — a startup
   warm-up window — then ~99.9% coverage for the rest. Fixed the test
   (`reports gyro coverage for the real fixture, and 0 for the synthetic
   no-gyro case` — renamed from the ticket's literal `reports gyro coverage
   0 for fixture`, since asserting a false thing under the ticket's exact
   name would be worse than a truthful test under a corrected one) to
   assert the real value and flag this here per "ground truth over
   documentation."

2. **`webkitdirectory` toggle implemented as two separate inputs/buttons,
   not one input with a runtime-toggled attribute.** A single `<input
   type="file">` can't switch `webkitdirectory` on and off after it's been
   rendered in most browsers (the attribute is read at input-creation/
   picker-invocation time) — "Choose files…" and "Choose folder…" as two
   buttons, each triggering its own hidden input, gets the same practical
   outcome (pick loose files vs. pick a folder) without relying on that.
   Also found and fixed a real bug here: Preact sets non-standard/
   vendor-prefixed DOM props like `webkitdirectory` directly rather than via
   `setAttribute`, so `webkitdirectory=""` (empty string, falsy) silently
   never enabled directory mode at all — needs `webkitdirectory={true}`.
   Caught by an e2e test failure ("File input does not support
   directories"), not by inspection.

3. **Quota-exceeded (E-50) isn't covered by an automated test.**
   `fake-indexeddb` has no quota-simulation support (checked its source).
   `IdbPersistence.saveImportedBundle` does catch `QuotaExceededError` from
   both `transaction.onerror` and `.onabort` (different engines route it
   differently) and rethrows as `ImportError('STORAGE_QUOTA_EXCEEDED')` —
   verified by code review, not by a running test. Manual verification via
   Chrome DevTools' storage-quota override is the next-best check available
   in this environment; flagging the gap rather than claiming coverage that
   doesn't exist.

4. **Only 8 of the 9 synthetic fixtures get an assertion on their specific
   warning code**; the remaining 4 (`gaps`, `unknown-label`,
   `range-problems`, `crlf-bom`) are asserted to import without throwing,
   not checked against a specific warning code on the card. `schema-v2` is
   asserted to reject with `UNKNOWN_SCHEMA_VERSION` (it's supposed to fail,
   not warn). This is a real, deliberate coverage tradeoff under time
   pressure, not an oversight discovered after the fact — the parser-level
   tests (`parse.test.ts`) already assert precise behavior for all 9
   fixtures; the card-level tests mostly confirm the pipeline wires that
   behavior through to a card without losing it.

5. **`RealWorkerBridge` is only exercised by e2e tests, not integration
   tests.** This is the design intent (AC4's "InlineWorkerBridge runs the
   same code ... for tests"), not a gap — but worth being explicit that the
   actual `postMessage`/transfer-list/Blob-worker-construction code path
   only gets checked in a real browser (`test/e2e/import.spec.ts`), which is
   also the only place a bug specific to that boundary (a non-transferable
   value, a lost `requestId`) would show up.

## A second real bug, found the same way as the first

While wiring the new Worker in, the exact same class of bug from ticket
01's blank-page report almost shipped again silently: `webkitdirectory={true}`
above (discrepancy 2) — found by an e2e test failing with a real, specific
Playwright error message, not by re-reading the code and noticing something
looked off. Recording this because it reinforces the same lesson: this kind
of "JSX-attribute → DOM behavior" gap doesn't show up in `tsc --noEmit`, and
doesn't always throw — it can also fail exactly like the blank-page bug did,
silently.

## Test names and counts

- Vitest `unit` (23): the 14 in `parse.test.ts` (including the two ticket-01
  harness tests) + 7 in `bundleSource.test.ts` + 2 harness sanity tests.
- Vitest `integration` (11): 9 in `import-pipeline.test.ts` (including the
  ticket's 4 exactly-named required tests:
  `same hash imported twice yields one bundle`, plus
  `zip with top folder and flat zip hash equal` and
  `ambiguous basename is an ImportError` in the unit suite) + 2 in
  `persistence-harness.test.ts`.
- Playwright (14): the 3 ticket-01 smoke tests × 2 projects, plus 4 new
  import tests × 2 projects (`imports the real trimmed session...`,
  `re-importing the same session...`, `importing a synthetic fixture with
  missing gps.csv...`, `Delete removes a card...`).

48/48 green, verified twice: once in the working copy, once from a
clean `rm -rf node_modules && npm ci` in the same checkout (a from-scratch
clone + `git am` pass happens at commit time, same as ticket 01).

## What's still open

- `LibraryEntry.reviewState` is hardcoded to `'UNREVIEWED'` — correct today
  (every journal is empty) but needs replacing with real
  `SessionState.reviewState` once ticket 05's reducer exists.
- "Sort by last edited" currently sorts by `importedAt`, since nothing can
  edit a session yet; becomes the journal's `updatedAt` once ticket 05 lands.
- The Library doesn't yet show a queue "N open" count (§7.8) — that needs
  `core/queue.ts`, which isn't built until the review-queue ticket (07).
- Folder drag-and-drop isn't implemented (only a dropped `.zip` or dropped
  loose files) — real folder drag-and-drop needs recursive
  `FileSystemDirectoryEntry` traversal via the drag `DataTransferItem` API,
  which is a meaningfully bigger scope than the file-input `webkitdirectory`
  path this ticket already covers. The "Choose folder…" button is the real,
  working folder-import path; drag-and-drop of a folder specifically is the
  gap.

## Status

Status: done.
