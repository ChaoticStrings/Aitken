package com.aitken.classifier

import com.aitken.storage.FakeSafStorageAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassifierConfigLoaderTest {

    @Test
    fun `currentConfig is null before anything has ever loaded`() {
        val loader = ClassifierConfigLoader(FakeSafStorageAdapter())
        assertNull(loader.currentConfig())
    }

    @Test
    fun `currentConfig returns the seeded initial config synchronously`() {
        val seed = ClassifierConfig("seed".toByteArray(), loadedAtMs = 1000L)
        // Folder isn't even granted -- proves currentConfig() doesn't need storage at all.
        val loader = ClassifierConfigLoader(FakeSafStorageAdapter(granted = false), initialConfig = seed)

        assertEquals(seed, loader.currentConfig())
    }

    @Test
    fun `checkForUpdate returns false and leaves the cache untouched when the folder isn't granted`() {
        val seed = ClassifierConfig("seed".toByteArray(), loadedAtMs = 1000L)
        val loader = ClassifierConfigLoader(FakeSafStorageAdapter(granted = false), initialConfig = seed)

        val updated = loader.checkForUpdate()

        assertFalse(updated)
        assertEquals(seed, loader.currentConfig())
    }

    @Test
    fun `checkForUpdate returns false and leaves the cache untouched when the file doesn't exist`() {
        val seed = ClassifierConfig("seed".toByteArray(), loadedAtMs = 1000L)
        val loader = ClassifierConfigLoader(FakeSafStorageAdapter(), initialConfig = seed)

        val updated = loader.checkForUpdate()

        assertFalse(updated)
        assertEquals(seed, loader.currentConfig())
    }

    @Test
    fun `checkForUpdate returns false and leaves the cache untouched when the file is empty`() {
        val seed = ClassifierConfig("seed".toByteArray(), loadedAtMs = 1000L)
        val storage = FakeSafStorageAdapter()
        storage.write("classifier_config.json", ByteArray(0))
        val loader = ClassifierConfigLoader(storage, initialConfig = seed)

        val updated = loader.checkForUpdate()

        assertFalse(updated)
        assertEquals(seed, loader.currentConfig())
    }

    @Test
    fun `checkForUpdate loads and caches a new config, timestamped with the injected clock`() {
        val storage = FakeSafStorageAdapter()
        storage.write("classifier_config.json", "new config".toByteArray())
        val loader = ClassifierConfigLoader(storage, now = { 5000L })

        val updated = loader.checkForUpdate()

        assertTrue(updated)
        val current = loader.currentConfig()
        assertEquals("new config", current?.content?.decodeToString())
        assertEquals(5000L, current?.loadedAtMs)
    }
}
