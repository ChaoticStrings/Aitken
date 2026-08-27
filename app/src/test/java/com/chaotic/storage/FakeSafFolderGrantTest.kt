package com.aitken.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FakeSafFolderGrantTest {

    @Test
    fun `grantedUri is null before anything is persisted`() {
        val grant = FakeSafFolderGrant()
        assertNull(grant.grantedUri())
    }

    @Test
    fun `persist stores the uri so grantedUri returns it`() {
        val grant = FakeSafFolderGrant()

        grant.persist("content://tree/mock-uri")

        assertEquals("content://tree/mock-uri", grant.grantedUri())
    }

    @Test
    fun `persist overwrites a previous grant`() {
        val grant = FakeSafFolderGrant()
        grant.persist("content://tree/old-uri")

        grant.persist("content://tree/new-uri")

        assertEquals("content://tree/new-uri", grant.grantedUri())
    }
}
