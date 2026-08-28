# 07 — ClassifierConfigLoader

**What to build:** Non-blocking access to the current classifier config, with
opportunistic background sync over the SAF folder, so a stale or unreachable config
never stops a recording session.

**Blocked by:** 03

**Status:** ready-for-agent

- [x] `currentConfig()` returns the cached local config synchronously, never
      blocking
- [x] `checkForUpdate()` polls the SAF folder opportunistically, off the recording
      path
- [x] A recording session proceeds normally with no config update available (stale
      config degrades gracefully, user story 22)
- [x] Fake `SafStorageAdapter` used to test both the update-found and
      no-update-available paths
