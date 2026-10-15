package com.aitken.recording

import com.aitken.location.GpsFix
import com.aitken.segment.CalibrationPreset
import com.aitken.segment.CalibrationResult
import com.aitken.segment.NoiseFloorCalibrator
import com.aitken.segment.RoughPatch
import com.aitken.sensor.AccelerometerInfo
import com.aitken.sensor.SensorStream
import com.aitken.tagging.TagKind
import com.aitken.tagging.TagMatch
import com.aitken.tagging.TagMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * `accelZ`, regardless of GravityEstimator's internal EMA state. A sample's
 * deviation from resting gravity is therefore just `accelZ - 9.81`, which
 * the helpers below write as `Q + dev` (Q = quiet = exactly gravity).
 *
 * Shared pipeline shape: calibration needs 10ms of clean time and judges a
 * sample 10ms after it arrives, so it completes on the *third* sample
 * (@0, @10, @20) -- detection starts at @30. A flat Q calibration has no
 * peaks and no roughness, so both thresholds sit on the 0.5 floor. The
 * sliding window is the default 1000ms, which a sub-second test never
 * fills, so D never fires unless a test says otherwise.
 */
class RecordingPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val ms = 1_000_000L
    private val q = 9.81f

    private fun sample(t: Long, accelZ: Float) = SensorStream.SensorSample(
        timestampNs = t, accelX = 0f, accelY = 0f, accelZ = accelZ,
        gyroX = null, gyroY = null, gyroZ = null
    )

    private fun testCalibrator(durationMs: Long = 10L, commitLagMs: Long = 10L) = NoiseFloorCalibrator(
        calibrationDurationMs = durationMs,
        rollingWindowMs = 20L,
        peakFactor = 2f,
        roughFactor = 1.5f,
        floor = 0.5f,
        settleMs = 10L,
        commitLagMs = commitLagMs
    )

    private fun newPipeline(
        tagMatcher: TagMatcher = TagMatcher(),
        calibrator: NoiseFloorCalibrator = testCalibrator(),
        preset: CalibrationPreset? = null,
        rollingWindowMs: Long = 1_000L,
        accelerometer: AccelerometerInfo? = null,
        onCalibrationDone: (CalibrationResult) -> Unit = {},
        onCalibrationProgress: (Float) -> Unit = {},
        onLiveSample: (LiveSample) -> Unit = {},
        onPatchOpened: (Long) -> Unit = {},
        onPatchClosed: (RoughPatch) -> Unit = {},
        tagDebounceMs: () -> Long = { 500L },
        now: () -> Long = { 0L }
    ): RecordingPipeline {
        val recorder = SessionRecorder(tempFolder.root, flushEveryNRows = 1)
        return RecordingPipeline(
            recorder = recorder,
            tagMatcher = tagMatcher,
            onCalibrationDone = onCalibrationDone,
            onCalibrationProgress = onCalibrationProgress,
            onLiveSample = onLiveSample,
            onPatchOpened = onPatchOpened,
            onPatchClosed = onPatchClosed,
            tagDebounceMs = tagDebounceMs,
            calibrator = calibrator,
            preset = preset,
            rollingWindowMs = rollingWindowMs,
            roughPatchDistanceM = 2.5f,
            endQuietMs = 15L,
            minSegmentDurationMs = 5L,
            accelerometer = accelerometer,
            now = now
        )
    }

    private fun RecordingPipeline.calibrate() {
        onSensorSample(sample(0L, q), turning = false)
        onSensorSample(sample(10 * ms, q), turning = false)
        onSensorSample(sample(20 * ms, q), turning = false) // calibration done
    }

    @Test
    fun `calibration phase writes sensor rows but produces no segments, then reports the derived thresholds`() {
        var done: CalibrationResult? = null
        var calls = 0
        val pipeline = newPipeline(onCalibrationDone = { done = it; calls++ })

        pipeline.onSensorSample(sample(0L, q), turning = false)
        pipeline.onSensorSample(sample(10 * ms, q), turning = false)
        assertEquals(0, calls)
        pipeline.onSensorSample(sample(20 * ms, q), turning = false) // 10ms of clean time judged -> done

        assertEquals(1, calls)
        // Flat calibration: no peaks, no roughness -> both thresholds sit on the 0.5 floor.
        assertEquals(0.5f, done!!.mThreshold, 1e-6f)
        assertEquals(0.5f, done!!.dThreshold, 1e-6f)
        assertEquals(null, done!!.presetName)
        val sensorLines = File(tempFolder.root, "sensor.csv").readLines()
        assertEquals(4, sensorLines.size) // header + 3 rows
        assertEquals(1, File(tempFolder.root, "segments.csv").readLines().size) // header only
    }

    @Test
    fun `calibration progress follows clean time and stops being reported once detecting starts`() {
        val progress = mutableListOf<Float>()
        val pipeline = newPipeline(onCalibrationProgress = { progress.add(it) })

        pipeline.onSensorSample(sample(0L, q), turning = false)       // nothing judged yet
        pipeline.onSensorSample(sample(10 * ms, q), turning = false)  // judged @0: 0ms clean
        pipeline.onSensorSample(sample(20 * ms, q), turning = false)  // judged @10: 10ms clean -> done
        pipeline.onSensorSample(sample(30 * ms, q), turning = false)  // now detecting

        assertEquals(listOf(0f, 0f, 1f), progress)
    }

    @Test
    fun `hard acceleration reported by GPS during calibration is excluded from the thresholds`() {
        // lag 40ms, settle 10ms: see NoiseFloorCalibratorTest's late-motion trace
        val calibrator = testCalibrator(durationMs = 1_000L, commitLagMs = 40L)
        val pipeline = newPipeline(calibrator = calibrator)
        fun fix(speed: Float) =
            GpsFix(timestampNs = 0L, latitude = 0.0, longitude = 0.0, speedMps = speed, accuracyMeters = null)

        // devs @0..50: 1,3,2,9,1,4 ; the GPS speed jumps 0 -> 5 between the fixes after @0 and @30
        pipeline.onSensorSample(sample(0 * ms, q + 1f), turning = false)
        pipeline.onGpsFix(fix(0f))
        pipeline.onSensorSample(sample(10 * ms, q + 3f), turning = false)
        pipeline.onSensorSample(sample(20 * ms, q + 2f), turning = false)
        pipeline.onSensorSample(sample(30 * ms, q + 9f), turning = false)
        pipeline.onGpsFix(fix(5f))                                   // -> dynamic interval 0..30ms, +/-10ms settle
        listOf(1f, 4f, 0f, 4f, 1f, 0f, 0f, 0f, 0f, 0f).forEachIndexed { i, dev ->
            pipeline.onSensorSample(sample((40L + i * 10L) * ms, q + dev), turning = false)
        }

        // samples @0..@40 are dirty; the only peak left to be judged is the 4 @70 (0<4>=1)
        val r = calibrator.result()
        assertEquals(1, r.peakCount)
        assertEquals(8f, r.mThreshold, 1e-4f)   // 4.0 * peakFactor 2 -- ungated would have been (3+9+4+4)/4*2 = 10
    }

    @Test
    fun `a genuine spike opens at the breaching sample, closes after the quiet tail, is written with speed, and reaches TagMatcher`() {
        val tagMatcher = TagMatcher(tagLookbackMs = 1000L)
        val pipeline = newPipeline(tagMatcher = tagMatcher, now = { 9999L })
        pipeline.onGpsFix(
            GpsFix(timestampNs = 0L, latitude = 0.0, longitude = 0.0, speedMps = 6f, accuracyMeters = null)
        )
        pipeline.calibrate()

        pipeline.onSensorSample(sample(30 * ms, q + 6f), turning = false) // dev 6 >= 0.5 -> opens right here
        pipeline.onSensorSample(sample(40 * ms, q + 4f), turning = false) // breach, last signal @40
        pipeline.onSensorSample(sample(50 * ms, q), turning = false)      // quiet 10ms < 15
        pipeline.onSensorSample(sample(60 * ms, q), turning = false)      // quiet 20ms >= 15 -> closes

        val segmentLines = File(tempFolder.root, "segments.csv").readLines()
        assertEquals(2, segmentLines.size) // header + 1 closed segment
        // start=30ms, duration=10ms (to the last breach, excluding the quiet tail);
        // peak=6.000; rms over the span @30..@40 = sqrt((36+16)/2) = 5.099; speed 6 from the fix; epoch 9999
        assertEquals("1,30000000,10000000,6.000,5.099,6.000,9999", segmentLines[1])

        val tap = tagMatcher.match(tapTimestampNs = 65 * ms, kind = TagKind.POINT, openSegment = null)
        assertTrue(tap is TagMatch.Matched)
        assertEquals(30 * ms, (tap as TagMatch.Matched).segmentStartNs)
    }

    @Test
    fun `stopping mid-open-segment force-closes it end-to-end`() {
        val pipeline = newPipeline(now = { 5555L })
        pipeline.calibrate()
        pipeline.onSensorSample(sample(30 * ms, q + 6f), turning = false) // opens
        pipeline.onSensorSample(sample(40 * ms, q + 4f), turning = false) // extends, last signal @40

        pipeline.endSession() // force-close -- no quiet tail was ever waited out

        val segmentLines = File(tempFolder.root, "segments.csv").readLines()
        assertEquals(2, segmentLines.size)
        // No GPS fix was ever pushed in this test -> speed cell is blank.
        assertEquals("1,30000000,10000000,6.000,5.099,,5555", segmentLines[1])
    }

    @Test
    fun `two potholes within the patch distance are written as one CLUSTER rough patch, live-notified when the second starts`() {
        val opened = mutableListOf<Long>()
        val closed = mutableListOf<RoughPatch>()
        val pipeline = newPipeline(
            now = { 9999L },
            onPatchOpened = { opened.add(it) },
            onPatchClosed = { closed.add(it) }
        )
        pipeline.calibrate()
        // 50 m/s from @20ms on -> the odometer gains 0.5 m per 10ms sample: odo(t) = 0.05 * (t - 20) m
        pipeline.onGpsFix(GpsFix(0L, 0.0, 0.0, speedMps = 50f, accuracyMeters = null))

        fun at(t: Long, dev: Float) = pipeline.onSensorSample(sample(t * ms, q + dev), turning = false)
        at(30, 6f); at(40, 4f)               // pothole A: breaches @30,@40 (odo 0.5, 1.0)
        at(50, 0f); at(60, 0f); at(70, 0f)   // A closes @60; the gap so far < 2.5 m
        assertTrue(opened.isEmpty())
        at(80, 6f)                           // pothole B at odo 3.0: 2.0 m after A's last breach -> registers now
        assertEquals(listOf(30 * ms), opened)
        at(90, 4f)                           // B's last breach, odo 3.5
        for (t in 100L..150L step 10L) at(t, 0f)   // quiet on; the chain expires past odo 6.0 (@150 = 6.5)

        assertEquals(1, closed.size)
        val lines = File(tempFolder.root, "patches.csv").readLines()
        assertEquals(2, lines.size)
        // start @30ms, end @90ms (last breach), length 3.5 - 0.5 = 3.0 m, 2 segments, peak 6, max D 0 (window never full),
        // trigger CLUSTER, speed 50, epoch 9999
        assertEquals("1,30000000,90000000,3.000,2,6.000,0.000,CLUSTER,50.000,9999", lines[1])
    }

    @Test
    fun `two potholes farther apart than the patch distance leave patches csv empty`() {
        val pipeline = newPipeline()
        pipeline.calibrate()
        pipeline.onGpsFix(GpsFix(0L, 0.0, 0.0, speedMps = 50f, accuracyMeters = null))

        fun at(t: Long, dev: Float) = pipeline.onSensorSample(sample(t * ms, q + dev), turning = false)
        at(30, 6f); at(40, 4f)               // A: last breach at odo 1.0
        for (t in 50L..100L step 10L) at(t, 0f)
        at(130, 6f); at(140, 4f)             // B at odo 5.5: 4.5 m later > 2.5
        for (t in 150L..300L step 10L) at(t, 0f)
        pipeline.endSession()

        assertEquals(1, File(tempFolder.root, "patches.csv").readLines().size) // header only
        assertEquals(3, File(tempFolder.root, "segments.csv").readLines().size) // but both potholes are still segments
    }

    @Test
    fun `a saved preset skips calibration, warms up, then detects with the preset's thresholds`() {
        val preset = CalibrationPreset("1", "Highway", mThreshold = 3f, dThreshold = 1f, rollingWindowMs = 20L, createdEpochMs = 5L)
        val progress = mutableListOf<Float>()
        var done: CalibrationResult? = null
        val pipeline = newPipeline(
            preset = preset, rollingWindowMs = 20L,
            onCalibrationProgress = { progress.add(it) }, onCalibrationDone = { done = it }
        )

        pipeline.onSensorSample(sample(0L, q), turning = false)
        pipeline.onSensorSample(sample(500 * ms, q), turning = false)
        assertEquals(null, done)
        pipeline.onSensorSample(sample(1_000 * ms, q), turning = false) // warm-up is max(window, 1000ms) -> now

        assertEquals(listOf(0f, 0.5f, 1f), progress)
        assertEquals("Highway", done!!.presetName)
        assertEquals(3f, done!!.mThreshold, 1e-6f)
        assertEquals(1f, done!!.dThreshold, 1e-6f)

        // dev 2.9 < 3 stays closed; dev 3.5 >= 3 opens
        pipeline.onSensorSample(sample(1_010 * ms, q + 2.9f), turning = false)
        assertNull(pipeline.currentOpenSegment())
        pipeline.onSensorSample(sample(1_020 * ms, q + 3.5f), turning = false)
        assertEquals(1_020 * ms, pipeline.currentOpenSegment()!!.startNs)
    }

    @Test
    fun `live samples flag clipping at the sensor ceiling and report whether a segment is open`() {
        val live = mutableListOf<LiveSample>()
        val sensor = AccelerometerInfo("n", "v", maxRangeMs2 = 20f, resolutionMs2 = 0.01f)
        val pipeline = newPipeline(accelerometer = sensor, onLiveSample = { live.add(it) })
        pipeline.calibrate()

        pipeline.onSensorSample(sample(30 * ms, 10f), turning = false)   // within range, dev 0.19 < 0.5: no segment
        pipeline.onSensorSample(sample(40 * ms, 20f), turning = false)   // saturated, dev 10.19: opens

        assertFalse(live[3].clipped); assertFalse(live[3].segmentOpen)
        assertTrue(live[4].clipped);  assertTrue(live[4].segmentOpen)
        assertEquals(20f, live[4].vertical, 1e-4f)
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
        pipeline.calibrate()
        pipeline.onSensorSample(sample(30 * ms, q + 6f), turning = false) // opens

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
        pipeline.calibrate() // nothing ever opened

        val result = pipeline.tag(TagKind.POINT, "Pothole")

        assertTrue(result is TagMatch.Unmatched)
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals("1,20000000,POINT,,Pothole,,9999", labelLines[1])
    }

    @Test
    fun `a second tap of the same kind and label within the debounce window is ignored`() {
        val pipeline = newPipeline(now = { 9999L }, tagDebounceMs = { 500L })
        pipeline.calibrate()

        val first = pipeline.tag(TagKind.POINT, "Pothole")
        pipeline.onSensorSample(sample(220 * ms, q), turning = false) // 200ms later, < 500ms window
        val second = pipeline.tag(TagKind.POINT, "Pothole")

        assertTrue(first is TagMatch.Unmatched)
        assertNull(second)
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(2, labelLines.size) // header + only the first tap
    }

    @Test
    fun `a second tap of the same kind and label outside the debounce window still records`() {
        val pipeline = newPipeline(now = { 9999L }, tagDebounceMs = { 500L })
        pipeline.calibrate()

        val first = pipeline.tag(TagKind.POINT, "Pothole")
        pipeline.onSensorSample(sample(600 * ms, q), turning = false) // 580ms later, > 500ms window
        val second = pipeline.tag(TagKind.POINT, "Pothole")

        assertTrue(first is TagMatch.Unmatched)
        assertTrue(second is TagMatch.Unmatched)
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(3, labelLines.size) // header + both taps
    }

    @Test
    fun `taps for different labels are never debounced against each other, even with the same kind`() {
        val pipeline = newPipeline(now = { 9999L }, tagDebounceMs = { 500L })
        pipeline.calibrate()

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
        pipeline.calibrate()

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
        pipeline.calibrate()

        pipeline.tag(TagKind.POINT, "Pothole") // tap at 20ms, under the original 1000ms window
        pipeline.onSensorSample(sample(220 * ms, q), turning = false) // 200ms later

        debounceMs = 100L // shrink the window before the second tap
        val second = pipeline.tag(TagKind.POINT, "Pothole") // 200ms since first tap, now > 100ms window

        assertTrue(second is TagMatch.Unmatched) // no longer debounced under the new, shorter window
        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(3, labelLines.size)
    }

    @Test
    fun `range-tag start and end are recorded with distinct kind values`() {
        val pipeline = newPipeline(now = { 9999L })
        pipeline.calibrate()
        pipeline.onSensorSample(sample(30 * ms, q + 6f), turning = false) // opens

        pipeline.tag(TagKind.RANGE_START, "Rough stretch")
        pipeline.tag(TagKind.RANGE_END, "Rough stretch")

        val labelLines = File(tempFolder.root, "labels.csv").readLines()
        assertEquals(3, labelLines.size) // header + 2 rows
        assertTrue(labelLines[1].contains(",RANGE_START,"))
        assertTrue(labelLines[2].contains(",RANGE_END,"))
    }
}
