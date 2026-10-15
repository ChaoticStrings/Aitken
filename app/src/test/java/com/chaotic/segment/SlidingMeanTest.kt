package com.aitken.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Window = 30ms, samples every 10ms, so a full window holds 3 samples
 * (the half-open span (now-30ms, now]).
 */
class SlidingMeanTest {

    private val ms = 1_000_000L

    @Test
    fun `mean covers only the samples inside the window, and the window reports full once its span has been covered`() {
        val w = SlidingMean(windowMs = 30L)

        // @0:  {3}          mean 3.0   span 0  < 30 -> not full
        assertEquals(3f, w.push(0 * ms, 3f), 1e-6f)
        assertFalse(w.isFull)
        // @10: {3,6}        mean 4.5
        assertEquals(4.5f, w.push(10 * ms, 6f), 1e-6f)
        // @20: {3,6,9}      mean 6.0   span 20 < 30 -> not full
        assertEquals(6f, w.push(20 * ms, 9f), 1e-6f)
        assertFalse(w.isFull)
        // @30: cutoff=0, the @0 sample (exactly 30ms old) has left -> {6,9,12} mean 9.0; span 30 -> full
        assertEquals(9f, w.push(30 * ms, 12f), 1e-6f)
        assertTrue(w.isFull)
        // @40: cutoff=10 -> {9,12,15} mean 12.0
        assertEquals(12f, w.push(40 * ms, 15f), 1e-6f)
    }

    @Test
    fun `reset forgets everything, including fullness`() {
        val w = SlidingMean(windowMs = 20L)
        w.push(0, 5f); w.push(10 * ms, 5f); w.push(20 * ms, 5f)
        assertTrue(w.isFull)

        w.reset()

        assertFalse(w.isFull)
        assertEquals(7f, w.push(100 * ms, 7f), 1e-6f)
    }
}
