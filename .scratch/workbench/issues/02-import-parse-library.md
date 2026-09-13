# 02 — Import a bundle (zip or loose files) → parsed in a worker → Library card

**What to build:** Vision drops a `.zip` or selects a folder / multiple files
on the Library screen; the files are read, parsed off the main thread with a
progress bar, stored as an immutable Source Bundle in IndexedDB, and appear as
a Library card with counts, review state UNREVIEWED, and any import warnings.
Re-dropping the same bundle opens the existing card instead of duplicating.

**Blocked by:** 01

**Status:** ready-for-agent

**Plan sections:** §4.2 (BundleSource, WorkerBridge, Persistence seams), §4.4, §4.5, §5.1, §5.8, §6.1, §6.2, §6.5, §7.8, §11 E-01…E-17.

**Acceptance criteria**
- [ ] `ZipBundleSource` and `FilesBundleSource` both yield `Map<basename, Blob>` + SHA-256 `hash` of the pass-through files concatenated in a fixed order (`sensor, gps, segments, labels, config`); `__MACOSX/`, dotfiles, and non-session files ignored; ambiguous basenames → `ImportError('AMBIGUOUS_FILE')`.
- [ ] `parseSession` in `worker/parse.ts` dispatches per file on `schema_version` via a `Record<number, parser>`; unknown version throws `ImportError` naming file and version; headers matched by name; CRLF/BOM/trailing blank lines tolerated; empty numeric field → `NaN`; sensor rows stable-sorted by `tNs` with `reordered` count; gap list (dt > 500 ms); gyro coverage fraction.
- [ ] Tags link to segments by exact bigint equality of `segment_start_ns` ↔ `start_ns` → `SegId` `s:<start_ns>`; miss or empty → `segmentId: null` + warning `TAG_UNMATCHED`. Duplicate `start_ns` → second id suffixed `#2` + warning.
- [ ] Worker protocol (`worker/index.ts`) streams `{type:'progress', phase, fraction}` then `{type:'parsed', session}` with typed arrays **transferred**, not copied. `InlineWorkerBridge` runs the same code synchronously for tests.
- [ ] `IdbPersistence` stores `bundles` and an empty `journals` record in one transaction; `MemoryPersistence` mirrors the interface; private-mode fallback banner (E-49); quota error surfaces cleanly (E-50).
- [ ] Library renders cards (Plan §7.8) from persistence; sort by last edited; Delete with confirm; storage estimate line; drop zone + file input with `webkitdirectory` toggle.
- [ ] Same-hash re-import → toast "already imported — opened", no duplicate (E-15).
- [ ] Demo: import `session_trimmed_90s.zip` and the loose folder; both produce the same hash and one card; import each synthetic fixture and see the expected warnings on the card.

**Tests to write first (Plan §12 rows `worker/parse`, `app/store` import, `Persistence`):**
`parses fixture sensor rows to exact count`, `resolves tag to segment by exact start_ns`, `leaves tag unmatched when link empty`, `refuses unknown schema_version with file and version`, `parses headers by name in any order`, `tolerates CRLF and BOM`, `reports gyro coverage 0 for fixture`, `zip with top folder and flat zip hash equal`, `ambiguous basename is an ImportError`, `same hash imported twice yields one bundle`.
