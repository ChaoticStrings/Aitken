package com.aitken.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises FakeSafStorageAdapter itself, so downstream modules (BackupAgent,
 * ClassifierConfigLoader — tickets 07/08) can trust the double behaves like a
 * real granted folder would.
 */
class FakeSafStorageAdapterTest {

    @Test
    fun `isGranted reflects the constructor flag`() {
        assertTrue(FakeSafStorageAdapter(granted = true).isGranted())
        assertFalse(FakeSafStorageAdapter(granted = false).isGranted())
    }

    @Test
    fun `read returns null for a path that was never written`() {
        val adapter = FakeSafStorageAdapter()
        assertNull(adapter.read("sessions/session_1.csv"))
    }

    @Test
    fun `write then read round-trips the same bytes`() {
        val adapter = FakeSafStorageAdapter()
        val data = "hello".toByteArray()

        adapter.write("sessions/session_1.csv", data)

        assertEquals("hello", adapter.read("sessions/session_1.csv")?.decodeToString())
    }

    @Test
    fun `write again overwrites the previous content`() {
        val adapter = FakeSafStorageAdapter()
        adapter.write("config.json", "old".toByteArray())

        adapter.write("config.json", "new".toByteArray())

        assertEquals("new", adapter.read("config.json")?.decodeToString())
    }

    @Test
    fun `delete removes an entry so read returns null afterward`() {
        val adapter = FakeSafStorageAdapter()
        adapter.write("config.json", "data".toByteArray())

        adapter.delete("config.json")

        assertNull(adapter.read("config.json"))
    }

    @Test
    fun `list returns only direct children, not deeper descendants`() {
        val adapter = FakeSafStorageAdapter()
        adapter.write("sessions/session_1.csv", ByteArray(0))
        adapter.write("sessions/nested/deep.csv", ByteArray(0))
        adapter.write("config.json", ByteArray(0))

        val root = adapter.list()
        val sessions = adapter.list("sessions")

        assertEquals(
            setOf(StorageEntry("config.json", false), StorageEntry("sessions", true)),
            root.toSet()
        )
        assertEquals(
            setOf(
                StorageEntry("sessions/session_1.csv", false),
                StorageEntry("sessions/nested", true)
            ),
            sessions.toSet()
        )
    }
}
