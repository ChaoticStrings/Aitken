package com.aitken.backup

import com.aitken.storage.FakeSafStorageAdapter
import com.aitken.storage.SafStorageAdapter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class BackupAgentTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `enqueueBackup returns -1 and doesn't throw when the folder isn't granted`() {
        val agent = BackupAgent(FakeSafStorageAdapter(granted = false))
        val sessionDir = tempFolder.newFolder("session_1")
        File(sessionDir, "sensor.csv").writeText("data")

        val result = agent.enqueueBackup(sessionDir)

        assertEquals(-1, result)
    }

    @Test
    fun `enqueueBackup returns -1 when sessionDir doesn't exist`() {
        val agent = BackupAgent(FakeSafStorageAdapter())
        val missingDir = File(tempFolder.root, "does_not_exist")

        val result = agent.enqueueBackup(missingDir)

        assertEquals(-1, result)
    }

    @Test
    fun `enqueueBackup copies every file in sessionDir into a same-named subfolder`() {
        val storage = FakeSafStorageAdapter()
        val agent = BackupAgent(storage)
        val sessionDir = tempFolder.newFolder("session_20260827")
        File(sessionDir, "sensor.csv").writeText("sensor-data")
        File(sessionDir, "gps.csv").writeText("gps-data")

        val result = agent.enqueueBackup(sessionDir)

        assertEquals(2, result)
        assertEquals("sensor-data", storage.read("session_20260827/sensor.csv")?.decodeToString())
        assertEquals("gps-data", storage.read("session_20260827/gps.csv")?.decodeToString())
    }

    @Test
    fun `a failure copying one file doesn't stop the rest from being backed up`() {
        val real = FakeSafStorageAdapter()
        val sessionDir = tempFolder.newFolder("session_2")
        File(sessionDir, "sensor.csv").writeText("sensor-data")
        File(sessionDir, "gps.csv").writeText("gps-data")
        val throwing = ThrowingOnPathStorageAdapter(real, failOnPath = "session_2/sensor.csv")
        val agent = BackupAgent(throwing)

        val result = agent.enqueueBackup(sessionDir)

        assertEquals(1, result) // only gps.csv made it
        assertEquals("gps-data", real.read("session_2/gps.csv")?.decodeToString())
    }
}

/** Purpose-built double, scoped to this test file only, to simulate a per-file write failure. */
private class ThrowingOnPathStorageAdapter(
    private val delegate: SafStorageAdapter,
    private val failOnPath: String
) : SafStorageAdapter by delegate {
    override fun write(path: String, bytes: ByteArray) {
        if (path == failOnPath) throw IOException("simulated failure")
        delegate.write(path, bytes)
    }
}
