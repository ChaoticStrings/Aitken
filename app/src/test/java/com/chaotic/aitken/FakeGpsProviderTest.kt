package com.aitken.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises FakeGpsProvider itself, so downstream modules (SessionRecorder,
 * ticket 05) can trust the double behaves like a real fix stream would.
 */
class FakeGpsProviderTest {

    private fun fix(t: Long = 0L) = GpsFix(
        timestampNs = t,
        latitude = 18.52,
        longitude = 73.85,
        speedMps = 5f,
        accuracyMeters = 3f
    )

    @Test
    fun `currentFix is null before any fix is emitted`() {
        val provider = FakeGpsProvider()
        assertNull(provider.currentFix())
    }

    @Test
    fun `start registers a callback and returns true by default`() {
        val provider = FakeGpsProvider()

        val started = provider.start { }

        assertTrue(started)
        assertEquals(1, provider.startCallCount)
    }

    @Test
    fun `start can be scripted to fail, mirroring a real provider with no location permission`() {
        val provider = FakeGpsProvider()
        provider.startResult = false

        val started = provider.start { }

        assertFalse(started)
    }

    @Test
    fun `emitFix updates currentFix and notifies the registered callback`() {
        val provider = FakeGpsProvider()
        val received = mutableListOf<GpsFix>()
        provider.start { received.add(it) }

        val f = fix(t = 1000L)
        provider.emitFix(f)

        assertEquals(f, provider.currentFix())
        assertEquals(listOf(f), received)
    }

    @Test
    fun `stop unregisters the callback but keeps the last known fix cached`() {
        val provider = FakeGpsProvider()
        val received = mutableListOf<GpsFix>()
        provider.start { received.add(it) }
        provider.emitFix(fix(t = 1000L))

        provider.stop()
        provider.emitFix(fix(t = 2000L)) // simulated late delivery after stop

        assertEquals(1, received.size) // callback no longer invoked
        assertEquals(2000L, provider.currentFix()?.timestampNs) // cache still updates
        assertEquals(1, provider.stopCallCount)
    }
}
