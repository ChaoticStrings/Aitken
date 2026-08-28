package com.aitken.backup

import com.aitken.storage.SafStorageAdapter
import java.io.File

/**
 * Best-effort backup of a closed session bundle to Vision-controlled
 * storage (user stories 9–11), decoupled from SessionRecorder's primary
 * app-private write path.
 *
 * This class is just the copy logic — [enqueueBackup] runs synchronously
 * when called. The "never blocks or gates recording" guarantee comes from
 * *when* it's called (only after a session has fully closed and flushed),
 * which is a lifecycle concern wired in ticket 12, not something this class
 * enforces itself.
 *
 * A failure never throws — swallowed per-file via `runCatching` and
 * reported through the return value, so one bad file (folder revoked mid-
 * copy, disk full, a file deleted out from under it) can't crash the app
 * or stop the rest of the bundle from backing up.
 *
 * On "no other egress exists" (ticket 08's last acceptance criterion):
 * this class's only write path is the injected [SafStorageAdapter] — no
 * network client, no other storage API, nothing else in this file talks to
 * anything external. That's verifiable by reading this file. Whether
 * *nothing else in the whole app* egresses data is a repo-wide property,
 * not something a unit test for this one class can prove — worth a
 * `/code-review` pass before this ships, not a claim this ticket makes on
 * its own.
 */
class BackupAgent(private val storage: SafStorageAdapter) {

    /**
     * Copies every file directly inside [sessionDir] into a same-named
     * subfolder of the SAF-granted folder.
     *
     * @return the number of files successfully copied; -1 if the folder
     * isn't granted or [sessionDir] doesn't exist, so callers/tests can
     * tell "nothing to do" apart from "partial failure."
     */
    fun enqueueBackup(sessionDir: File): Int {
        if (!storage.isGranted()) return -1
        val files = sessionDir.listFiles()?.filter { it.isFile } ?: return -1

        var copied = 0
        for (file in files) {
            val ok = runCatching {
                storage.write("${sessionDir.name}/${file.name}", file.readBytes())
            }.isSuccess
            if (ok) copied++
        }
        return copied
    }
}
