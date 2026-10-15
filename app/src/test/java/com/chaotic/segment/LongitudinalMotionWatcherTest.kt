package com.aitken.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LongitudinalMotionWatcherTest {

    private val ms = 1_000_000L

    @Test
    fun `flags the interval between two fixes whose speed change implies at least the limit, and only that`() {
        val w = LongitudinalMotionWatcher(maxAccelMs2 = 1.0f, maxFixGapMs = 1_500L)

        assertNull(w.onSpeed(0 * ms, 0f))                 // no previous fix
        assertNull(w.onSpeed(1_000 * ms, 0.5f))           // 0.5 m/s over 1 s = 0.5 m/s^2 < 1.0
        // 0.5 -> 6.0 over 1 s = 5.5 m/s^2 (the real fixture's launch): flagged, spanning those two fixes
        assertEquals(1_000 * ms to 2_000 * ms, w.onSpeed(2_000 * ms, 6.0f))
        assertNull(w.onSpeed(3_000 * ms, 6.2f))           // 0.2 m/s^2
        // braking counts too: 6.2 -> 2.8 over 1 s
        assertEquals(3_000 * ms to 4_000 * ms, w.onSpeed(4_000 * ms, 2.8f))
    }

    @Test
    fun `unknown speed, or fixes too far apart to judge, give the benefit of the doubt`() {
        val w = LongitudinalMotionWatcher(maxAccelMs2 = 1.0f, maxFixGapMs = 1_500L)

        w.onSpeed(0, 0f)
        assertNull(w.onSpeed(1_000 * ms, null))           // no speed in this fix
        assertNull(w.onSpeed(2_000 * ms, 9f))             // previous was unknown -> can't compare
        assertNull(w.onSpeed(5_000 * ms, 0f))             // 3 s gap > 1.5 s -> too coarse to tell
    }
}
