package com.aitken.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Amplitude-triggered detector. Shared shape: mThreshold=5, endQuietMs=25,
 * minSegmentDurationMs=5, samples every 10ms. Verticals are `9.81 + dev` (or
 * `9.81 - dev` for a downward swing), so each sample's deviation from
 * gravity reads straight off the trace.
 *
 * The basic trace, reused below (dev per sample):
 *   @0  0 -> IDLE
 *   @10 6 -> 6 >= 5: OPENS at exactly this sample (start=10ms)
 *   @20 2 -> below; quiet since the last breach @10 = 10ms < 25 -> stays open
 *   @30 7 -> breach again: lastSignal=30, peak=7
 *   @40 0 -> quiet 10ms
 *   @50 0 -> quiet 20ms
 *   @60 0 -> quiet 30ms >= 25 -> CLOSES; duration = 30 - 10 = 20ms
 *   rms over the span @10..@30 (the @20 lull included, the tail excluded):
 *     sqrt((36 + 4 + 49) / 3) = sqrt(29.667) ~= 5.447
 */
class SegmentDetectorTest {

    private val ms = 1_000_000L
    private val g = 9.81f

    private fun detector(mThreshold: Float = 5f) = SegmentDetector(
        mThreshold = mThreshold, endQuietMs = 25L, minSegmentDurationMs = 5L
    )

    private fun SegmentDetector.up(t: Long, dev: Float, turning: Boolean = false) =
        push(t * ms, g + dev, turning)

    @Test
    fun `opens at the very sample that breaches the threshold and closes only after the waveform stays below it`() {
        val d = detector()

        assertNull(d.up(0, 0f)); assertFalse(d.isOpen)
        assertNull(d.up(10, 6f))
        assertTrue(d.isOpen)
        assertEquals(10 * ms, d.currentOpenSegment()!!.startNs)   // no onset lag
        assertNull(d.up(20, 2f))
        assertNull(d.up(30, 7f))
        assertNull(d.up(40, 0f))
        assertNull(d.up(50, 0f))
        val closed = d.up(60, 0f)

        assertNotNull(closed)
        assertEquals(10 * ms, closed!!.startNs)
        assertEquals(20 * ms, closed.durationNs)       // to the LAST breach, excluding the quiet tail
        assertEquals(7f, closed.peakM, 1e-4f)
        assertEquals(5.447f, closed.rmsM, 1e-3f)
        assertFalse(d.isOpen)
    }

    @Test
    fun `closes at exactly endQuietMs of quiet, not a sample later`() {
        val d = detector()           // endQuietMs = 25
        d.up(10, 6f)
        d.up(20, 6f)                 // lastSignal = 20
        assertNull(d.up(44, 0f))     // 24ms quiet < 25 -> still open
        assertNotNull(d.up(45, 0f))  // 25ms quiet >= 25 -> closes
    }

    @Test
    fun `a sub-threshold sample inside an event does not end it`() {
        val d = detector()
        d.up(10, 6f)
        d.up(20, 0f)   // zero crossing, 10ms quiet < 25
        d.up(30, 6f)   // event continues
        assertTrue(d.isOpen)
        assertEquals(30 * ms, d.currentOpenSegment()!!.lastSignalNs)
    }

    @Test
    fun `breachedOnLastPush is true only for threshold breaches inside a segment`() {
        val d = detector()
        d.up(0, 0f);  assertFalse(d.breachedOnLastPush)
        d.up(10, 6f); assertTrue(d.breachedOnLastPush)
        d.up(20, 2f); assertFalse(d.breachedOnLastPush)
        d.up(30, 7f); assertTrue(d.breachedOnLastPush)
    }

    @Test
    fun `a downward swing past the threshold opens a segment just like an upward one`() {
        val d = detector()
        assertNull(d.push(10 * ms, g - 6f, false))
        assertTrue(d.isOpen)
        assertEquals(6f, d.currentOpenSegment()!!.peakM, 1e-4f)
    }

    @Test
    fun `just under the threshold never opens`() {
        val d = detector()
        listOf(0L, 10L, 20L, 30L).forEach { t -> d.up(t, 4.9f) }
        assertFalse(d.isOpen)
        assertNull(d.endSession())
    }

    @Test
    fun `turning suppresses a new start but never truncates an open segment`() {
        val d = detector()
        d.up(10, 6f, turning = true)
        assertFalse(d.isOpen)                       // suppressed

        d.up(20, 6f)                                // opens normally
        d.up(30, 8f, turning = true)                // breach while turning: still extends
        assertTrue(d.isOpen)
        assertEquals(30 * ms, d.currentOpenSegment()!!.lastSignalNs)
        assertEquals(8f, d.currentOpenSegment()!!.peakM, 1e-4f)
    }

    @Test
    fun `a segment shorter than the minimum duration is discarded silently`() {
        val d = detector()
        d.up(10, 6f)           // single breach: duration 0 < 5ms
        d.up(20, 0f)
        d.up(30, 0f)
        assertNull(d.up(40, 0f))   // quiet 30 >= 25 -> closes, but discarded
        assertFalse(d.isOpen)
    }

    @Test
    fun `endSession force-closes an open segment and excludes the quiet tail from duration and rms`() {
        val d = detector()
        d.up(10, 6f)
        d.up(20, 6f)
        d.up(30, 0f)           // quiet tail, accumulated but excluded at close

        val closed = d.endSession()
        assertNotNull(closed)
        assertEquals(10 * ms, closed!!.startNs)
        assertEquals(10 * ms, closed.durationNs)
        assertEquals(6f, closed.peakM, 1e-4f)
        assertEquals(6f, closed.rmsM, 1e-4f)          // sqrt((36+36)/2)
        assertNull(d.endSession())                     // idempotent
    }

    @Test
    fun `after closing, a later breach opens a fresh segment`() {
        val d = detector()
        d.up(10, 6f); d.up(20, 0f); d.up(30, 0f); d.up(40, 0f)   // closes @40? quiet 30>=25
        assertFalse(d.isOpen)
        d.up(100, 9f)
        assertTrue(d.isOpen)
        assertEquals(100 * ms, d.currentOpenSegment()!!.startNs)
        assertEquals(9f, d.currentOpenSegment()!!.peakM, 1e-4f)
    }
}
