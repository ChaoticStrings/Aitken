package com.aitken.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * patchDistanceM = 2.5, dThreshold = 2.0. Time is irrelevant to the logic
 * (it is only recorded), so each sample is `(t, odometerM, ...)` with t in
 * ms and odometer in metres, hand-driven to exercise one rule at a time.
 */
class RoughPatchTrackerTest {

    private val ms = 1_000_000L

    private fun tracker() = RoughPatchTracker(patchDistanceM = 2.5f, dThreshold = 2.0f)

    private fun RoughPatchTracker.s(
        t: Long, odo: Double, dev: Float = 0f, breach: Boolean = false,
        seg: Long? = null, roughness: Float? = null
    ): List<PatchEvent> = onSample(t * ms, odo, dev, breach, seg?.let { it * ms }, roughness)

    @Test
    fun `a lone short pothole is just a segment, not a rough patch`() {
        val tr = tracker()
        val all = mutableListOf<PatchEvent>()
        // breach samples at odo 0.0..1.0 (one segment, 1 m long)
        all += tr.s(0, 0.0, 9f, breach = true, seg = 0)
        all += tr.s(10, 0.5, 9f, breach = true, seg = 0)
        all += tr.s(20, 1.0, 9f, breach = true, seg = 0)
        // segment closes; quiet while travelling on: gap = odo - 1.0, expires past 2.5 m -> odo > 3.5
        all += tr.s(30, 1.2, seg = null)
        all += tr.s(40, 3.6, seg = null)
        assertTrue(all.isEmpty())
        assertNull(tr.endSession())
    }

    @Test
    fun `two potholes within the patch distance register as a CLUSTER the moment the second one starts`() {
        val tr = tracker()
        tr.s(0, 0.0, 9f, breach = true, seg = 0)             // pothole A
        tr.s(10, 0.1, 0f, seg = null)                        // A closes
        val atSecond = tr.s(200, 2.0, 7f, breach = true, seg = 200)   // gap 2.0 - 0.0 = 2.0 <= 2.5: pothole B

        assertEquals(listOf<PatchEvent>(PatchEvent.Opened(startNs = 0)), atSecond)
        assertTrue(tr.isPatchActive)

        // vehicle leaves: expires once gap > 2.5 from the last rough sample (odo 2.0) -> odo > 4.5
        assertTrue(tr.s(300, 4.4).isEmpty())
        val closed = tr.s(310, 4.6)
        val patch = (closed.single() as PatchEvent.Closed).patch
        assertEquals(PatchTrigger.CLUSTER, patch.trigger)
        assertEquals(2, patch.segmentCount)
        assertEquals(0L, patch.startNs)
        assertEquals(200 * ms, patch.endNs)
        assertEquals(2.0f, patch.lengthM, 1e-5f)
        assertEquals(9f, patch.peakM, 1e-5f)
    }

    @Test
    fun `a gap of exactly the patch distance still counts as within range`() {
        val tr = tracker()
        tr.s(0, 0.0, 9f, breach = true, seg = 0)
        tr.s(10, 0.0)
        assertTrue(tr.s(100, 2.5).isEmpty())             // gap 2.5 is not > 2.5: chain still alive
        val events = tr.s(110, 2.5, 8f, breach = true, seg = 110)
        assertEquals(listOf<PatchEvent>(PatchEvent.Opened(0)), events)
    }

    @Test
    fun `two potholes farther apart than the patch distance stay two separate segments`() {
        val tr = tracker()
        val all = mutableListOf<PatchEvent>()
        all += tr.s(0, 0.0, 9f, breach = true, seg = 0)
        all += tr.s(10, 0.1)                                  // closes
        all += tr.s(100, 2.7)                                 // gap 2.7 > 2.5: first chain expires, unregistered
        all += tr.s(110, 3.0, 8f, breach = true, seg = 110)   // fresh chain
        all += tr.s(120, 3.1)
        all += tr.s(200, 6.0)
        assertTrue(all.isEmpty())
    }

    @Test
    fun `a single segment that spans the patch distance registers as LENGTH`() {
        val tr = tracker()
        assertTrue(tr.s(0, 0.0, 6f, breach = true, seg = 0).isEmpty())
        assertTrue(tr.s(10, 1.0, 6f, breach = true, seg = 0).isEmpty())
        assertTrue(tr.s(20, 2.0, 6f, breach = true, seg = 0).isEmpty())
        // length reaches exactly 2.5 -> registers
        assertEquals(listOf<PatchEvent>(PatchEvent.Opened(0)), tr.s(30, 2.5, 6f, breach = true, seg = 0))

        val closing = tr.endSession()!!.patch
        assertEquals(PatchTrigger.LENGTH, closing.trigger)
        assertEquals(1, closing.segmentCount)
        assertEquals(2.5f, closing.lengthM, 1e-5f)
    }

    @Test
    fun `sustained roughness over the D threshold registers a ROUGHNESS patch with no segment at all`() {
        val tr = tracker()
        // gravel: windowed roughness 2.5 >= 2.0, individual samples never open a segment
        assertTrue(tr.s(0, 0.0, 1f, roughness = 2.5f).isEmpty())
        assertTrue(tr.s(10, 1.0, 1f, roughness = 2.5f).isEmpty())
        assertEquals(listOf<PatchEvent>(PatchEvent.Opened(0)), tr.s(20, 2.5, 1f, roughness = 2.5f))
        tr.s(30, 4.0, 1f, roughness = 2.5f)

        val patch = tr.endSession()!!.patch
        assertEquals(PatchTrigger.ROUGHNESS, patch.trigger)
        assertEquals(0, patch.segmentCount)
        assertEquals(4.0f, patch.lengthM, 1e-5f)
        assertEquals(2.5f, patch.maxD, 1e-5f)
    }

    @Test
    fun `roughness right after a segment is the window's memory of it, not new roughness - until the window width has passed`() {
        // memory = 1000ms: D stays above threshold after a big pothole, but must not extend the chain
        val tr = RoughPatchTracker(patchDistanceM = 2.5f, dThreshold = 2.0f, roughnessMemoryMs = 1_000L)
        tr.s(0, 0.0, 30f, breach = true, seg = 0)
        tr.s(10, 0.1, seg = null)                                   // segment closed
        // smeared D = 3.0 >= 2.0, but within 1000ms of the breach: not rough, so no patch forms
        assertTrue(tr.s(500, 4.0, 1f, roughness = 3.0f).isEmpty())
        assertTrue(tr.s(1000, 8.0, 1f, roughness = 3.0f).isEmpty())
        // 1001ms after the breach the memory has passed: genuinely rough stretch begins here
        assertTrue(tr.s(1001, 8.1, 1f, roughness = 3.0f).isEmpty()) // chain starts, length 0
        assertTrue(tr.s(1050, 9.4, 1f, roughness = 3.0f).isEmpty()) // length 1.3 m < 2.5
        assertEquals(listOf<PatchEvent>(PatchEvent.Opened(1001 * ms)), tr.s(1100, 10.7, 1f, roughness = 3.0f))
    }

    @Test
    fun `roughness below the threshold, or not yet available, is not rough`() {
        val tr = tracker()
        val all = mutableListOf<PatchEvent>()
        all += tr.s(0, 0.0, 1f, roughness = 1.9f)
        all += tr.s(10, 5.0, 20f, roughness = null)      // big deviation but no segment breach and no window yet
        all += tr.s(20, 10.0, 1f, roughness = 1.9f)
        assertTrue(all.isEmpty())
        assertNull(tr.endSession())
    }

    @Test
    fun `a segment's own quiet tail keeps its chain alive but never lengthens it`() {
        val tr = tracker()
        tr.s(0, 0.0, 9f, breach = true, seg = 0)
        // 0.5 s of quiet tail at speed: odometer runs on to 10 m while the segment is still "open"
        assertTrue(tr.s(100, 5.0, seg = 0).isEmpty())
        assertTrue(tr.s(200, 10.0, seg = 0).isEmpty())
        // segment closes: chain expires at once (gap measured from the last BREACH at odo 0), unregistered
        assertTrue(tr.s(210, 10.1, seg = null).isEmpty())
        assertNull(tr.endSession())
    }

    @Test
    fun `two segments stationary at the same spot still cluster`() {
        val tr = tracker()
        tr.s(0, 0.0, 9f, breach = true, seg = 0)
        tr.s(10, 0.0)
        val events = tr.s(2000, 0.0, 9f, breach = true, seg = 2000)
        assertEquals(listOf<PatchEvent>(PatchEvent.Opened(0)), events)
    }

    @Test
    fun `endSession closes an in-progress registered patch, and returns null for an unregistered chain`() {
        val tr = tracker()
        tr.s(0, 0.0, 9f, breach = true, seg = 0)
        assertNull(tr.endSession())                 // one short segment: not a patch

        val tr2 = tracker()
        tr2.s(0, 0.0, 9f, breach = true, seg = 0)
        tr2.s(10, 1.0, 9f, breach = true, seg = 10)
        val closed = tr2.endSession()
        assertEquals(PatchTrigger.CLUSTER, closed!!.patch.trigger)
        assertNull(tr2.endSession())                // idempotent
    }
}
