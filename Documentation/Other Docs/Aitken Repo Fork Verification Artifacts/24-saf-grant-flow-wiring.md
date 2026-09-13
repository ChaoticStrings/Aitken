# 24 — Wire a folder-grant flow into the app

**What to build:** `StorageGrantScreen.kt` and `AndroidSafStorageAdapter`
(ticket 03) already implement a folder picker and write path. Neither is
ever reached — grepping the whole `app/src/main` tree, `StorageGrantScreen`
is referenced nowhere outside its own file. Nothing in `MainActivity` or
`AitkenSessionScreen` ever navigates to it, so `storage.isGranted()` can
never become true on a fresh install, and `BackupAgent.enqueueBackup()`
silently returns `-1` (its own documented "not granted" signal) forever.

**Prompted by:** field feedback (g). Confirmed with Vision: keep this
simple for now — exporting session files to a chosen folder is enough,
no fancy re-grant safety net or account handling needed yet.

**One thing worth confirming before building, not assuming:** "exporting
to specified, unrestricted directories of my choosing" could mean either
(a) keep the SAF folder-picker `StorageGrantScreen` already built, just
finally make it reachable — the unrestricted-storage architecture invariant
`map.md`/T5 already ratified — or (b) actually mean a plain filesystem path
picker outside SAF, which would reopen that ratified decision. Building
this ticket as (a) unless told otherwise, since it's the smaller change and
matches standing architecture; flagging rather than guessing since it's a
real fork in what gets built.

**Blocked by:** None (03 is merged).

**This ticket blocks ticket 12's real completion** — ticket 12's own
acceptance criterion (a session backs up with no user action beyond ending
the ride) can't be verified true without a grant flow existing first.
Recommend adding "24" to ticket 12's `Blocked by` line.

**Status:** ready-for-agent

- [ ] A grant entry point exists and is actually reachable — Settings
      screen, plus a one-time prompt on first launch if ungranted
- [ ] If a session ends with no folder granted, the rider gets some
      visible signal instead of a silent `-1`
- [ ] `BackupAgent`'s existing one-subfolder-per-session behavior
      (`"${sessionDir.name}/..."`) already avoids dumping every session
      into one flat folder — feedback (g) asked for exactly this; it looks
      like ticket 08 already built it, just never reachable end-to-end
      to confirm until this ticket wires the grant in
