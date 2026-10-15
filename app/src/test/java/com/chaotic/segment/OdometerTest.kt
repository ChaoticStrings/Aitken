package com.aitken.segment

import org.junit.Assert.assertEquals
import org.junit.Test

class OdometerTest {

    private val ms = 1_000_000L

    @Test
    fun `integrates held speed over the sensor timeline, picking up a new speed from the fix onward`() {
        val o = Odometer()
        o.onSpeed(0, 10f)

        assertEquals(0.0, o.advance(0), 1e-9)          // first sample: nothing to integrate yet
        assertEquals(1.0, o.advance(100 * ms), 1e-9)   // 10 m/s * 0.1 s
        assertEquals(2.0, o.advance(200 * ms), 1e-9)

        o.onSpeed(200 * ms, 5f)
        assertEquals(2.5, o.advance(300 * ms), 1e-9)   // 5 m/s * 0.1 s
    }

    @Test
    fun `a held speed expires after maxSpeedHoldMs even across a long gap between samples`() {
        val o = Odometer(maxSpeedHoldMs = 1_000L)
        o.onSpeed(0, 10f)

        o.advance(0)
        assertEquals(5.0, o.advance(500 * ms), 1e-9)
        // interval 500ms->1500ms: only 500ms..1000ms is still inside the hold -> 10 * 0.5 = 5 more
        assertEquals(10.0, o.advance(1_500 * ms), 1e-9)
        // fully stale now -> no more distance
        assertEquals(10.0, o.advance(2_000 * ms), 1e-9)
    }

    @Test
    fun `unknown speed contributes no distance`() {
        val o = Odometer()
        o.onSpeed(0, null)
        o.advance(0)
        assertEquals(0.0, o.advance(1_000 * ms), 1e-9)
    }
}
