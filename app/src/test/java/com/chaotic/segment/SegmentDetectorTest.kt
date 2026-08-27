package com.aitken.segment

import com.aitken.dsp.RollingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * SegmentDetector's edge cases, hand-derived from T5's behavioral
 * description (the literal "ten enumerated" list from the original grilling
 * session wasn't available when this ticket was implemented — this is a
 * from-scratch enumeration against the spec, erring toward completeness).
 *
 * Most tests below share one detector shape: shortWindow=2, longWindow=2,
 * both thresholds=5f, endQuietMs=25L. Under that shape, the sequence
 * (v=0 @0ms, v=20 @10ms, v=0 @20ms, v=0 @30ms, v=0 @40ms, v=0 @50ms) is
 * hand-traced once here and reused by several tests:
 *   - @0ms:  buf=[0]              std=0            -> IDLE stays IDLE
 *   - @10ms: buf=[0,20]  mean=10  var=100  std=10   -> OPENS (10>=5)
 *   - @20ms: buf=[20,0]  mean=10  var=100  std=10   -> still signal (10>=5),
 *            lastSignalNs advances to 20ms
 *   - @30ms: buf=[0,0]   mean=0   var=0    std=0    -> quiet (0<5),
 *            quietMs=(30-20)=10 < endQuietMs(25) -> stays OPEN
 *   - @40ms: buf=[0,0]            std=0            -> quiet,
 *            quietMs=(40-20)=20 < 25 -> stays OPEN
 *   - @50ms: buf=[0,0]            std=0            -> quiet,
 *            quietMs=(50-20)=30 >= 25 -> CLOSES
 *            durationNs = lastSignalNs(20ms) - startNs(10ms) = 10ms
 *            peakM=20 (only @10ms and @20ms samples accumulated, both v
 *            values 20 and 0 -> peak stays 20)
 *            rmsM = sqrt((20^2 + 0^2) / 2) = sqrt(200) ~= 14.142
 */
class SegmentDetectorTest {

    private val ms = 1_000_000L // ns per ms, for readable timestamps

    private fun sharedShapeDetector(
        endQuietMs: Long = 25L,
        minSegmentDurationMs: Long = 5L
    ) = SegmentDetector(
        shortWindow = RollingStats(windowSamples = 2),
        longWindow = RollingStats(windowSamples = 2),
        shortStdThreshold = 5f,
        longStdThreshold = 5f,
        endQuietMs = endQuietMs,
        minSegmentDurationMs = minSegmentDurationMs
    )

    @Test
    fun `idle stays idle while quiet`() {
        val detector = sharedShapeDetector()

        var t = 0L
        repeat(10) {
            val closed = detector.push(t, 0f, turning = false)
            assertNull(closed)
            assertNull(detector.currentOpenSegment())
            t += 10 * ms
        }
    }

    @Test
    fun `idle transitions to open when signal appears and not turning`() {
        val detector = sharedShapeDetector()

        assertNull(detector.push(0L, 0f, turning = false))
        assertNull(detector.currentOpenSegment())

        assertNull(detector.push(10 * ms, 20f, turning = false))

        val open = detector.currentOpenSegment()
        assertNotNull(open)
        assertEquals(10 * ms, open!!.startNs)
        assertEquals(20f, open!!.peakM, 0.001f)
    }

    @Test
    fun `idle does not open a new segment while turning`() {
        val detector = sharedShapeDetector()

        detector.push(0L, 0f, turning = false)
        val closed = detector.push(10 * ms, 20f, turning = true)

        assertNull(closed)
        assertNull(detector.currentOpenSegment())
    }

    @Test
    fun `open segment continues extending while turning, not truncated`() {
        val detector = sharedShapeDetector()
        detector.push(0L, 0f, turning = false)
        detector.push(10 * ms, 20f, turning = false) // opens

        detector.push(20 * ms, 0f, turning = true) // still signal-true; turning ignored once open

        val open = detector.currentOpenSegment()
        assertNotNull(open)
        assertEquals(20 * ms, open!!.lastSignalNs)
    }

    @Test
    fun `open segment closes after the quiet tail elapses, with duration measured to the last signal-true sample`() {
        val detector = sharedShapeDetector(minSegmentDurationMs = 5L)
        detector.push(0L, 0f, turning = false)
        detector.push(10 * ms, 20f, turning = false)
        detector.push(20 * ms, 0f, turning = false)
        assertNull(detector.push(30 * ms, 0f, turning = false))
        assertNull(detector.push(40 * ms, 0f, turning = false))

        val closed = detector.push(50 * ms, 0f, turning = false)

        assertNotNull(closed)
        // Excludes the 20ms->50ms quiet tail: measured only to the last
        // signal-true sample at 20ms.
        assertEquals(10 * ms, closed!!.durationNs)
    }

    @Test
    fun `peak and rms accumulate only over signal-true samples, unaffected by the trailing quiet tail`() {
        val detector = sharedShapeDetector(minSegmentDurationMs = 5L)
        detector.push(0L, 0f, turning = false)
        detector.push(10 * ms, 20f, turning = false)
        detector.push(20 * ms, 0f, turning = false)
        detector.push(30 * ms, 0f, turning = false)
        detector.push(40 * ms, 0f, turning = false)

        val closed = detector.push(50 * ms, 0f, turning = false)

        assertNotNull(closed)
        assertEquals(20f, closed!!.peakM, 0.001f)
        assertEquals(14.142f, closed!!.rmsM, 0.01f)
    }

    @Test
    fun `a closed segment shorter than the minimum duration is discarded silently`() {
        // Same trace as above (actual duration = 10ms) but the floor is set
        // above that, so the close is silent.
        val detector = sharedShapeDetector(minSegmentDurationMs = 20L)
        detector.push(0L, 0f, turning = false)
        detector.push(10 * ms, 20f, turning = false)
        detector.push(20 * ms, 0f, turning = false)
        detector.push(30 * ms, 0f, turning = false)
        detector.push(40 * ms, 0f, turning = false)

        val closed = detector.push(50 * ms, 0f, turning = false)

        assertNull(closed)
        // Discarded, not stuck: state still reset back to IDLE.
        assertNull(detector.currentOpenSegment())
    }

    @Test
    fun `stopping the session while a segment is open force-closes it instead of silently dropping it`() {
        val detector = sharedShapeDetector(minSegmentDurationMs = 5L)
        detector.push(0L, 0f, turning = false)
        detector.push(10 * ms, 20f, turning = false)
        detector.push(20 * ms, 0f, turning = false)

        val closed = detector.endSession()

        assertNotNull(closed)
        assertEquals(10 * ms, closed!!.startNs)
        assertEquals(10 * ms, closed!!.durationNs)
        assertNull(detector.currentOpenSegment())
    }

    @Test
    fun `endSession returns null when no segment is open`() {
        val detector = sharedShapeDetector()
        detector.push(0L, 0f, turning = false)

        assertNull(detector.endSession())
    }

    @Test
    fun `currentOpenSegment reflects the in-progress segment while open and is null while idle`() {
        val detector = sharedShapeDetector(minSegmentDurationMs = 5L)

        assertNull(detector.currentOpenSegment())
        detector.push(0L, 0f, turning = false)
        assertNull(detector.currentOpenSegment())

        detector.push(10 * ms, 20f, turning = false)
        assertNotNull(detector.currentOpenSegment())

        detector.push(20 * ms, 0f, turning = false)
        detector.push(30 * ms, 0f, turning = false)
        detector.push(40 * ms, 0f, turning = false)
        detector.push(50 * ms, 0f, turning = false) // closes naturally

        assertNull(detector.currentOpenSegment())
    }

    @Test
    fun `short window alone detects an isolated spike the long window hasn't caught up to yet`() {
        // shortWindow=2/threshold=4 catches the spike immediately; longWindow=6
        // averages it against 5 prior quiet samples and stays under its own
        // threshold=6 at this same tick. Hand-traced:
        //   after 5x v=0: both windows read [0,0] / [0,0,0,0,0], std=0
        //   @6th push v=10: short buf=[0,10] std=5.0 (>=4 -> true)
        //                   long  buf=[0,0,0,0,0,10] mean=1.667 std~=3.727 (<6 -> false)
        val detector = SegmentDetector(
            shortWindow = RollingStats(windowSamples = 2),
            longWindow = RollingStats(windowSamples = 6),
            shortStdThreshold = 4f,
            longStdThreshold = 6f,
            endQuietMs = 500L,
            minSegmentDurationMs = 5L
        )

        var t = 0L
        repeat(5) {
            detector.push(t, 0f, turning = false)
            assertNull(detector.currentOpenSegment())
            t += 10 * ms
        }

        detector.push(t, 10f, turning = false)

        assertNotNull(detector.currentOpenSegment())
        assertEquals(t, detector.currentOpenSegment()!!.startNs)
    }

    @Test
    fun `long window alone keeps a segment open through a brief lull the short window already reads as quiet`() {
        // shortWindow=2/threshold=2, longWindow=4/threshold=1.5. Hand-traced:
        //   @0ms  v=0: buf=[0]           std=0            -> IDLE
        //   @10ms v=8: buf=[0,8] (both)  mean=4 var=16 std=4  -> OPENS (>=2 and >=1.5)
        //   @20ms v=8: short buf=[8,8]   mean=8 var=0  std=0    (<2  -> "looks quiet")
        //              long  buf=[0,8,8] mean=5.33 var=14.22 std~=3.77 (>=1.5 -> still signal)
        //              anySignal true via long alone -> segment extends, no quiet timer starts
        val detector = SegmentDetector(
            shortWindow = RollingStats(windowSamples = 2),
            longWindow = RollingStats(windowSamples = 4),
            shortStdThreshold = 2f,
            longStdThreshold = 1.5f,
            endQuietMs = 500L,
            minSegmentDurationMs = 5L
        )

        detector.push(0L, 0f, turning = false)
        detector.push(10 * ms, 8f, turning = false)
        detector.push(20 * ms, 8f, turning = false)

        val open = detector.currentOpenSegment()
        assertNotNull(open)
        assertEquals(20 * ms, open!!.lastSignalNs)
    }
}
