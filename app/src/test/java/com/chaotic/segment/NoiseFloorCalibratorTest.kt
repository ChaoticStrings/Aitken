package com.aitken.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Shared test shape: samples every 10ms; verticals are written as
 * `9.81 + dev` so each sample's deviation from gravity reads straight off
 * the trace. rollingWindowMs=20 (two samples when full), peakFactor=2,
 * roughFactor=1.5, floor=0.1, settle=10ms, commit lag=10ms (a sample is
 * judged when the *next* one arrives). Durations vary per test.
 *
 * A "peak" is a local maximum of the deviation: strictly above its
 * predecessor, at least its successor.
 */
class NoiseFloorCalibratorTest {

    private val ms = 1_000_000L
    private val g = 9.81f

    private fun calibrator(durationMs: Long = 1_000L, commitLagMs: Long = 10L) = NoiseFloorCalibrator(
        calibrationDurationMs = durationMs,
        rollingWindowMs = 20L,
        peakFactor = 2f,
        roughFactor = 1.5f,
        floor = 0.1f,
        settleMs = 10L,
        commitLagMs = commitLagMs,
        maxElapsedFactor = 3
    )

    private fun NoiseFloorCalibrator.feed(t: Long, dev: Float, turning: Boolean = false) =
        push(t * ms, g + dev, turning)

    @Test
    fun `M is the mean of the peaks and D is the highest sliding-window mean, each scaled by its factor`() {
        val c = calibrator()
        // devs @0..70ms: 1,3,2,5,1,4,0,0 -- the last sample only exists to let the 0@60 be judged
        listOf(1f, 3f, 2f, 5f, 1f, 4f, 0f, 0f).forEachIndexed { i, dev -> c.feed(i * 10L, dev) }

        val r = c.result()
        // peaks: 3 (1<3>=2), 5 (2<5>=1), 4 (1<4>=0) -> mean 4.0 -> M = 4.0 * 2 = 8.0
        assertEquals(3, r.peakCount)
        assertEquals(4f, r.meanPeak, 1e-4f)
        assertEquals(8f, r.mThreshold, 1e-4f)
        // 20ms window means once full (@20ms on): {3,2}=2.5 {2,5}=3.5 {5,1}=3.0 {1,4}=2.5 {4,0}=2.0 -> max 3.5 -> D = 5.25
        assertTrue(r.windowFilled)
        assertEquals(3.5f, r.maxRoughness, 1e-4f)
        assertEquals(5.25f, r.dThreshold, 1e-4f)
        // samples @0..60 judged and clean: 60ms of clean time
        assertEquals(60L, r.cleanMs)
    }

    @Test
    fun `a plateau counts as one peak, at its first sample`() {
        val c = calibrator()
        // devs 1,4,4,0,0: the first 4 (1<4>=4) is a peak; the second (4 is not < 4) is not
        listOf(1f, 4f, 4f, 0f, 0f).forEachIndexed { i, dev -> c.feed(i * 10L, dev) }
        val r = c.result()
        assertEquals(1, r.peakCount)
        assertEquals(4f, r.meanPeak, 1e-4f)
    }

    @Test
    fun `a perfectly flat window derives no peaks and floors both thresholds`() {
        val c = calibrator()
        repeat(8) { i -> c.feed(i * 10L, 0f) }

        val r = c.result()
        assertEquals(0, r.peakCount)
        assertEquals(0.1f, r.mThreshold, 1e-6f)
        assertEquals(0.1f, r.dThreshold, 1e-6f)
    }

    @Test
    fun `samples around a turn are excluded, so a turn's spikes never reach the thresholds`() {
        val c = calibrator()
        // t:dev  0:1  10:3  20:2(turning)  30:5  40:1  50:4  60:0  70:0  80:0
        // turning @20 -> dirty [10,30]; so @10, @20, @30 are dropped (including the 3 and 5 peaks)
        val devs = listOf(1f, 3f, 2f, 5f, 1f, 4f, 0f, 0f, 0f)
        devs.forEachIndexed { i, dev -> c.feed(i * 10L, dev, turning = (i == 2)) }

        val r = c.result()
        // clean runs: [@0] alone, then @40,50,60,70 (@80 still pending). Peak only 4 (1<4>=0 @50).
        assertEquals(1, r.peakCount)
        assertEquals(8f, r.mThreshold, 1e-4f)          // 4.0 * 2, not the ungated (3+5+4)/3*2
        // second run, 20ms windows once full: @60 {4,0}=2.0 (first full), @70 {0,0}=0 -> max 2.0 -> D = 3.0
        assertEquals(3f, r.dThreshold, 1e-4f)
        // clean time: @40->@70 = 30ms (the lone @0 sample is not adjacent to anything clean)
        assertEquals(30L, r.cleanMs)
    }

    @Test
    fun `isPaused tracks whether the latest sample is excluded`() {
        val c = calibrator()
        c.feed(0, 1f)
        assertFalse(c.isPaused)
        c.feed(10, 1f, turning = true)   // dirty [0,20]
        assertTrue(c.isPaused)
        c.feed(20, 1f)                   // still inside [0,20] (inclusive)
        assertTrue(c.isPaused)
        c.feed(30, 1f)                   // 30 > 20 -> clean again
        assertFalse(c.isPaused)
    }

    @Test
    fun `a late GPS-derived motion interval still disqualifies the samples it describes`() {
        // lag 40ms: a sample is judged only once a sample 40ms newer has arrived
        val c = calibrator(commitLagMs = 40L)
        // t:dev 0:1 10:3 20:2 30:9 40:1 50:4 -- then the GPS fix reports hard accel over 30..40
        listOf(1f, 3f, 2f, 9f, 1f, 4f).forEachIndexed { i, dev -> c.feed(i * 10L, dev) }
        c.markMotion(30 * ms, 40 * ms)                       // + settle 10 -> dirty [20, 50]
        // t:dev 60:0 70:4 80:1 90:0 100:0 110:0 120:0 130:0 140:0
        listOf(0f, 4f, 1f, 0f, 0f, 0f, 0f, 0f, 0f).forEachIndexed { i, dev -> c.feed(60L + i * 10L, dev) }

        val r = c.result()
        // judged (<=100ms): @0,@10 clean (but no peak: @20 is dirty), @20..@50 dirty, @60..@100 clean.
        // Only peak: 4 @70 (0<4>=1) -> mean 4.0 -> M = 8.0. Without the late mark: 3, 9, 4, 4 -> 5.0 -> 10.0.
        assertEquals(1, r.peakCount)
        assertEquals(8f, r.mThreshold, 1e-4f)
    }

    @Test
    fun `clean time, not wall time, completes calibration`() {
        val c = calibrator(durationMs = 50L)
        val results = (0..6).map { i -> c.feed(i * 10L, 0f) }
        // judged through @(t-10): clean ms = t-10 -> reaches 50 at push@60
        assertEquals(listOf(false, false, false, false, false, false, true), results)
        assertEquals(1f, c.progressFraction, 1e-6f)
        assertTrue(c.isDone)
    }

    @Test
    fun `progress follows clean time`() {
        val c = calibrator(durationMs = 100L)
        c.feed(0, 0f); c.feed(10, 0f); c.feed(20, 0f); c.feed(30, 0f)
        // judged @0..@20 -> 20ms clean of 100
        assertEquals(0.2f, c.progressFraction, 1e-5f)
    }

    @Test
    fun `a rider who never settles still finishes after three times the duration, on the floors`() {
        val c = calibrator(durationMs = 50L)
        val results = (0..15).map { i -> c.feed(i * 10L, 5f, turning = true) }
        // cap = 3 * 50 = 150ms elapsed -> push@150 is the first to report done
        assertEquals(false, results[14])
        assertEquals(true, results[15])

        val r = c.result()
        assertEquals(0L, r.cleanMs)
        assertFalse(r.windowFilled)
        assertEquals(0.1f, r.mThreshold, 1e-6f)
        assertEquals(0.1f, r.dThreshold, 1e-6f)
    }
}
