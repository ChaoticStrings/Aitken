# 08 — BackupAgent

**What to build:** Best-effort backup of a closed session bundle to Vision-controlled
storage, decoupled from the primary (app-private) write path, so losing the phone
doesn't also cost the data.

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] `enqueueBackup(sessionPath)` copies a closed session bundle to the
      SAF-granted folder
- [ ] Backup runs after the primary write path completes, never blocking or gating
      it
- [ ] A backup failure (folder revoked, disk full, etc.) doesn't crash or block the
      app
- [ ] No data leaves the SAF-granted folder or app-private storage through any path
      other than this one — verified no other egress exists
