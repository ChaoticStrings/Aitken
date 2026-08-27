# 12 — Backup + config sync wired into app lifecycle

**What to build:** Closed sessions are actually backed up automatically after a
ride, and the classifier config actually refreshes in the background — both wired
into the real app lifecycle rather than existing only as standalone modules.

**Blocked by:** 07, 08, 10

**Status:** ready-for-agent

- [ ] Session close triggers `BackupAgent.enqueueBackup` automatically
- [ ] App periodically/opportunistically calls
      `ClassifierConfigLoader.checkForUpdate()` outside the recording path
- [ ] Riding with a stale or never-synced config still completes a normal session
- [ ] End-to-end test: a closed session appears in the SAF folder without any user
      action beyond ending the ride
