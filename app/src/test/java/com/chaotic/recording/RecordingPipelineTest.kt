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
        onCalibrationDone: (Float, Float) -> Unit = { _, _ -> },
        onCalibrationProgress: (Float) -> Unit = {},
        tagDebounceMs: () -> Long = { 500L },
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
            onCalibrationProgress = onCalibrationProgress,
            tagDebounceMs = tagDebounceMs,
            calibrator = calibrator,
            endQuietMs = 15L,
            minSegmentDurationMs = 5L,
            now = now
        )
    }

    @Test
    fun `calibration phase writes sensor rows but produces no segments`() {
        var calibrationDoneCalls = 0
        var reportedShortThreshold = -1f
        var reportedLongThreshold = -1f
        val pipeline = newPipeline(onCalibrationDone = { shortThreshold, longThreshold ->
            calibrationDoneCalls++
            reportedShortThreshold = shortThreshold
            reportedLongThreshold = longThreshold
        })

        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // crosses 15ms -> done

        assertEquals(1, calibrationDoneCalls)
        // Flat 0f calibration -> std=0, floors to 0.05, threshold = 0.05*3 = 0.15,
        // same trace as NoiseFloorCalibratorTest's "floor applies" case, and
        // identical for both windows here since short/long are both size-2
        // over the same flat data.
        assertEquals(0.15f, reportedShortThreshold, 0.001f)
        assertEquals(0.15f, reportedLongThreshold, 0.001f)
        val sensorLines = File(tempFolder.root, "sensor.csv").readLines()
        assertEquals(4, sensorLines.size) // header + 3 rows
        val segmentLines = File(tempFolder.root, "segments.csv").readLines()
        assertEquals(1, segmentLines.size) // header only, no segments yet
    }

    @Test
    fun `calibration progress climbs toward 1 and stops being reported once detecting starts`() {
        val progressReadings = mutableListOf<Float>()
        val pipeline = newPipeline(onCalibrationProgress = { progressReadings.add(it) })

        pipeline.onSensorSample(sample(0L, 0f), turning = false) // elapsed 0ms of 15ms
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false) // elapsed 10ms of 15ms
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // crosses 15ms -> done
        pipeline.onSensorSample(sample(30 * ms, 0f), turning = false) // now detecting, not calibrating

        assertEquals(listOf(0f, 10f / 15f, 1f), progressReadings)
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
        // start=30ms, duration=10ms (30ms->40ms, excludes the quiet tail).
        // peak/rms are gravity-relative deviations (§5 fix), not raw magnitude:
        // samples are v=5 (@30ms) and v=0 (@40ms) -> devs |5-9.81|=4.81,
        // |0-9.81|=9.81 -> peak=9.81, rms=sqrt((4.81^2+9.81^2)/2)~=7.726.
        // speed=6 (from the GPS fix), epoch=9999
        assertEquals("1,30000000,10000000,9.810,7.726,6.000,9999", segmentLines[1])

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
        // Same v=5/v=0 trace as above -> peak=9.81, rms=7.726 (§5 fix).
        // No GPS fix was ever pushed in this test -> speed cell is blank.
        assertEquals("1,30000000,10000000,9.810,7.726,,5555", segmentLines[1])
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
    fun `a second tap of the same kind and label within the debounce window is ignored`() {
        val pipeline = newPipeline(now = { 9999L }, tagDebounceMs = { 500L })
        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // calibration done

        val first = pipeline.tag(TagKind.POINT, "Pothole")
        pipeline.onSensorSample(sample(220 * ms, 0f), turning = false) // 200ms later, < 500ms window
        val second = pipeline.tag(TagKind.POINT, "Pothole")

        assertTrue(first is TagMatch.Unmatched)
        assertNull(second)
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(2, labelLines.size) // header + only the first tap
    }

    @Test
    fun `a second tap of the same kind and label outside the debounce window still records`() {
        val pipeline = newPipeline(now = { 9999L }, tagDebounceMs = { 500L })
        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // calibration done

        val first = pipeline.tag(TagKind.POINT, "Pothole")
        pipeline.onSensorSample(sample(600 * ms, 0f), turning = false) // 580ms later, > 500ms window
        val second = pipeline.tag(TagKind.POINT, "Pothole")

        assertTrue(first is TagMatch.Unmatched)
        assertTrue(second is TagMatch.Unmatched)
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(3, labelLines.size) // header + both taps
    }

    @Test
    fun `taps for different labels are never debounced against each other, even with the same kind`() {
        val pipeline = newPipeline(now = { 9999L }, tagDebounceMs = { 500L })
        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // calibration done

        val pothole = pipeline.tag(TagKind.POINT, "Pothole")
        val bump = pipeline.tag(TagKind.POINT, "Bump") // same instant, different label

        assertTrue(pothole is TagMatch.Unmatched)
        assertTrue(bump is TagMatch.Unmatched)
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(3, labelLines.size) // header + both, neither debounced against the other
    }

    @Test
    fun `range-tag start and end are debounced independently even though they share a label`() {
        val pipeline = newPipeline(now = { 9999L }, tagDebounceMs = { 500L })
        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // calibration done

        val start = pipeline.tag(TagKind.RANGE_START, "Rough stretch")
        val end = pipeline.tag(TagKind.RANGE_END, "Rough stretch") // same instant, same label

        assertTrue(start is TagMatch.Unmatched)
        assertTrue(end is TagMatch.Unmatched)
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(3, labelLines.size)
    }

    @Test
    fun `a live-changed debounce window applies to the very next tap`() {
        var debounceMs = 1000L
        val pipeline = newPipeline(now = { 9999L }, tagDebounceMs = { debounceMs })
        pipeline.onSensorSample(sample(0L, 0f), turning = false)
        pipeline.onSensorSample(sample(10 * ms, 0f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, 0f), turning = false) // calibration done

        pipeline.tag(TagKind.POINT, "Pothole") // tap at 20ms, under the original 1000ms window
        pipeline.onSensorSample(sample(220 * ms, 0f), turning = false) // 200ms later

        debounceMs = 100L // shrink the window before the second tap
        val second = pipeline.tag(TagKind.POINT, "Pothole") // 200ms since first tap, now > 100ms window

        assertTrue(second is TagMatch.Unmatched) // no longer debounced under the new, shorter window
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(3, labelLines.size)
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
