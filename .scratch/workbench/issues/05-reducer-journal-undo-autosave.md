# 05 — Reducer, Journal, undo/redo, autosave, reopen

**What to build:** The event-sourced core. A pure `reduce(state, edit)` with
`replay`, the store's `apply/undo/redo`, debounced autosave of the journal to
persistence, and restore on reopen. Demoable via the first real edit: the
Inspector shows a selected tag's label as chips; clicking a chip applies
`TagRelabel`; undo/redo work; reload keeps it; the Library card shows
"last edited".

**Blocked by:** 02

**Status:** ready-for-agent

**Plan sections:** §2, §4.3, §5.1, §5.2 (all reducer rules), §5.11, §6.5 (`journals`), §7.4 (Tag section only), §11 E-25, E-28, E-29, E-30.

**Acceptance criteria**
- [ ] `core/reducer.ts` implements every `Edit` kind in §5.2 (except `Batch`, ticket 09) as a total, pure function returning structurally-shared new state; segments stay sorted by `startNs`; unknown kind is a no-op.
- [ ] Every reducer rule in §5.2 has a named unit test with hand-built input/expected states (delete unlinks tags; move re-sorts and flags edited; split repoints by matched moment; merge requires adjacency; adopt order remove→move→add→rematch; resolve null deletes key; edits downgrade REVIEWED/EXPORTED to IN_REVIEW).
- [ ] `replay(initial, entries, upTo)` equals sequential `reduce`; store `state` is a computed signal of `replay(initial, journal, cursor)` with memoisation of the last prefix (performance test: 1,000 entries replay < 50 ms in Node).
- [ ] `apply` truncates the redo tail, appends `{seq, ts, origin, edit}`, advances cursor; `undo`/`redo` move cursor; both disabled at bounds.
- [ ] Autosave writes `journals[hash] = {entries, cursor}` debounced 300 ms and immediately on `visibilitychange → hidden`; reopen restores entries + cursor (redo available if cursor < length) (E-28, E-29).
- [ ] Initial `SessionState` is derived from the parsed bundle (segments/tags/`resolutions: {}`/`UNREVIEWED`) and cached per hash.
- [ ] Inspector (Tag variant, minimal) with label chips from `Settings.labels`; custom label input trims and case-insensitively matches known labels (E-30); top bar undo/redo buttons + `Ctrl/Cmd+Z`, `Ctrl/Cmd+Shift+Z`.
- [ ] Demo: relabel a tag, undo, redo, reload — state identical; Library card shows last edited.

**Tests to write first:** the `core/reducer` row of Plan §12 in full; `app/store` (`apply after undo truncates redo`, `autosave writes journal within 300ms`, `import then reopen restores state`).
