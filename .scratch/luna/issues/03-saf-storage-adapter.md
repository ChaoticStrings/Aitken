# 03 — SafStorageAdapter + one-time folder grant

**What to build:** A single adapter over a Storage Access Framework tree URI, granted
once through a folder picker and persisted, so `BackupAgent` and
`ClassifierConfigLoader` share one seam instead of each independently wrapping SAF.

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

- [x] `ACTION_OPEN_DOCUMENT_TREE` folder picker flow implemented and grants a
      persisted URI permission
- [x] Picker only needs to run once per install; the grant survives an app restart
- [ ] `SafStorageAdapter` interface exposes read/write/list operations against the
      granted folder
- [x] Fake tree-URI filesystem available for tests
- [x] Rider can grant the folder from a settings/onboarding screen (user story 23)
