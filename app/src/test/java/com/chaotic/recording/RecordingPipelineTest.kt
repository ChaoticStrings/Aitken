package com.aitken.recording

import com.aitken.dsp.RollingStats
import com.aitken.location.GpsFix
import com.aitken.segment.NoiseFloorCalibrator
import com.aitken.sensor.SensorStream
import com.aitken.tagging.TagKind
import com.aitken.tagging.TagMatch
import com.aitken.tagging.TagMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Every sample here is deliberately pure-Z-axis (accelX=accelY=0). With
 * GravityEstimator/Verticalizer, a pure-Z accel and a pure-Z gravity
 * estimate are always colinear, so `verticalComponent` reduces to exactly
 * `accelZ`, regardless of GravityEstimator's internal EMA state (true
 * whether gravity starts at [0,0,0] on the very first sample, or has
 * already converged — the dot-product/norm math cancels out identically
 * either way as long as every vector stays purely along Z). This lets every
 * scenario here reuse the exact same hand-traced RollingStats numbers
 * already proven in SegmentDetectorTest/NoiseFloorCalibratorTest, just
 * re-expressed as accelZ inputs run through the real DSP chain instead of
 * a bare "vertical" float.
 */
class RecordingPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val ms = 1_000_000L

    private fun sample(t: Long, accelZ: Float) = SensorStream.SensorSample(
        timestampNs = t, accelX = 0f, accelY = 0f, accelZ = accelZ,
        gyroX = null, gyroY = null, gyroZ = null
    )

    private fun newPipeline(
        tagMatcher: TagMatcher = TagMatcher(),
        onCalibrationDone: () -> Unit = {},
        now: () -> Long = { 0L }
    ): RecordingPipeline {
        val recorder = SessionRecorder(tempFolder.root, flushEveryNRows = 1)
        val calibrator = NoiseFloorCalibrator(
            shortWindow = RollingStats(windowSamples = 2),
            longWindow = RollingStats(windowSamples = 2),
            calibrationDurationMs = 15L,
            stdFactor = 3f,
            floorStd = 0.05f
        )
        return RecordingPipeline(
            recorder = recorder,
            tagMatcher = tagMatcher,
            onCalibrationDone = onCalibrationDone,
            calibrator = calibrator,
            endQuietMs = 15L,
            minSegmentDurationMs = 5L,
            now = now
        )
    }

    @Test
    fun `calibration phase writes sensor rows but produces no segments`() {
        var calibrationDoneCalls = 0
        val pipeline = newPipeline(onCalibrationDone = { calibrationDoneCalls++ })

        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // crosses 15ms -> done

        assertEquals(1, calibrationDoneCalls)
        val sensorLines = File(tempFolder.root, "sensor.csv").readLines()
        assertEquals(4, sensorLines.size) // header + 3 rows
        val segmentLines = File(tempFolder.root, "segments.csv").readLines()
        assertEquals(1, segmentLines.size) // header only, no segments yet
    }

    @Test
    fun `a genuine spike opens and later closes a segment, written with speed, and reaches TagMatcher`() {
        val tagMatcher = TagMatcher(tagLookbackMs = 1000L)
        val pipeline = newPipeline(tagMatcher = tagMatcher, now = { 9999L })
        pipeline.onGpsFix(
            GpsFix(timestampNs = 0L, latitude = 0.0, longitude = 0.0, speedMps = 6f, accuracyMeters = null)
        )

        // Calibration: quiet, floors to threshold 0.15 -- same trace as
        // NoiseFloorCalibratorTest's flat-calibration case.
        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // calibration done

        // Detecting: windows carry over as [0,0].
        pipeline.onSensorSample(sample(30 * ms, 5f), turning = false) // buf=[0,5] std=2.5 -> opens
        pipeline.onSensorSample(sample(40 * ms, 0f), turning = false) // buf=[5,0] std=2.5 -> extends
        pipeline.onSensorSample(sample(50 * ms, 0f), turning = false) // buf=[0,0] std=0 -> quiet, 10ms<15ms
        pipeline.onSensorSample(sample(60 * ms, 0f), turning = false) // 20ms>=15ms -> closes

        val segmentLines = File(tempFolder.root, "segments.csv").readLines()
        assertEquals(2, segmentLines.size) // header + 1 closed segment
        // start=30ms, duration=10ms (30ms->40ms, excludes the quiet tail),
        // peak=5, rms=sqrt((5^2+0^2)/2)=3.536, speed=6 (from the GPS fix), epoch=9999
        assertEquals("1,30000000,10000000,5.000,3.536,6.000,9999", segmentLines[1])

        // Reached TagMatcher: a tap shortly after matches via lookback.
        val tap = tagMatcher.match(tapTimestampNs = 45 * ms, kind = TagKind.POINT, openSegment = null)
        assertTrue(tap is TagMatch.Matched)
        assertEquals(30 * ms, (tap as TagMatch.Matched).segmentStartNs)
    }

    @Test
    fun `stopping mid-open-segment force-closes it end-to-end`() {
        val pipeline = newPipeline(now = { 5555L })

        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // calibration done
        pipeline.onSensorSample(sample(30 * ms, 5f), turning = false) // opens
        pipeline.onSensorSample(sample(40 * ms, 0f), turning = false) // extends, last signal at 40ms

        pipeline.endSession() // force-close -- no quiet tail was ever waited out

        val segmentLines = File(tempFolder.root, "segments.csv").readLines()
        assertEquals(2, segmentLines.size)
        // No GPS fix was ever pushed in this test -> speed cell is blank.
        assertEquals("1,30000000,10000000,5.000,3.536,,5555", segmentLines[1])
    }

    @Test
    fun `tag returns null and writes nothing when no sensor sample has arrived yet`() {
        val pipeline = newPipeline()

        val result = pipeline.tag(TagKind.POINT, "Pothole")

        assertNull(result)
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(1, labelLines.size) // header only
    }

    @Test
    fun `tag matches the currently-open segment and writes it to labels csv`() {
        val pipeline = newPipeline(now = { 9999L })
        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // calibration done
        pipeline.onSensorSample(sample(30 * ms, 5f), turning = false) // opens

        val result = pipeline.tag(TagKind.POINT, "Pothole")

        assertTrue(result is TagMatch.Matched)
        assertEquals(30 * ms, (result as TagMatch.Matched).segmentStartNs)
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        // tap at lastSensorTimestampNs=30ms, same as the segment's own lastSignalNs at this point -> offset 0
        assertEquals("1,30000000,POINT,30000000,Pothole,0,9999", labelLines[1])
    }

    @Test
    fun `an unmatched tap is still logged, with blank segment reference and offset`() {
        val pipeline = newPipeline(now = { 9999L })
        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // calibration done, nothing ever opened

        val result = pipeline.tag(TagKind.POINT, "Pothole")

        assertTrue(result is TagMatch.Unmatched)
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals("1,20000000,POINT,,Pothole,,9999", labelLines[1])
    }

    @Test
    fun `range-tag start and end are recorded with distinct kind values`() {
        val pipeline = newPipeline(now = { 9999L })
        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(30 * ms, 5f), turning = false) // opens

        pipeline.tag(TagKind.RANGE_START, "Rough stretch")
        pipeline.tag(TagKind.RANGE_END, "Rough stretch")

        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(3, labelLines.size) // header + 2 rows
        assertTrue(labelLines[1].contains(",RANGE_START,"))
        assertTrue(labelLines[2].contains(",RANGE_END,"))
    }
}
