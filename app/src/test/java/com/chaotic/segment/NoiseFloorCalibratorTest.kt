package com.aitken.segment

import com.aitken.dsp.RollingStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseFloorCalibratorTest {

    private val ms = 1_000_000L

    @Test
    fun `isDone is false before any samples are pushed`() {
        val calibrator = NoiseFloorCalibrator()
        assertFalse(calibrator.isDone)
    }

    @Test
    fun `push returns false while still calibrating and true once the duration elapses`() {
        val calibrator = NoiseFloorCalibrator(calibrationDurationMs = 1000L)

        var t = 0L
        repeat(9) { // pushes at t = 0, 100, ..., 800ms
            assertFalse(calibrator.push(t, 0f))
            t += 100 * ms
        }
        // t is now 900ms; elapsed since start (0ms) = 900 < 1000
        assertFalse(calibrator.push(t, 0f))
        t += 100 * ms

        // t is now 1000ms; elapsed = 1000 >= 1000 -> duration crossed
        assertTrue(calibrator.push(t, 0f))
        assertTrue(calibrator.isDone)
    }

    @Test
    fun `progressFraction rises from 0 toward 1 as calibration elapses`() {
        val calibrator = NoiseFloorCalibrator(calibrationDurationMs = 1000L)
        assertEquals(0f, calibrator.progressFraction, 0.001f)

        calibrator.push(0L, 0f)
        assertEquals(0f, calibrator.progressFraction, 0.001f) // no time elapsed on the very first sample

        calibrator.push(500 * ms, 0f)
        assertEquals(0.5f, calibrator.progressFraction, 0.001f)

        calibrator.push(1000 * ms, 0f)
        assertEquals(1f, calibrator.progressFraction, 0.001f)
    }

    @Test
    fun `progressFraction is clamped to 1 even if a push arrives well past the duration`() {
        val calibrator = NoiseFloorCalibrator(calibrationDurationMs = 1000L)
        calibrator.push(0L, 0f)
        calibrator.push(5000 * ms, 0f)
        assertEquals(1f, calibrator.progressFraction, 0.001f)
    }

    @Test
    fun `thresholds are derived from the measured noise floor times stdFactor`() {
        // window size 2, values [0, 1]: mean=0.5, var=(0+1)/2-0.25=0.25, std=0.5
        // flooredNoiseStd = max(0.5, 0.05) = 0.5
        // threshold = max(0.5 * 3, 0.05) = 1.5
        val calibrator = NoiseFloorCalibrator(
            shortWindow = RollingStats(windowSamples = 2),
            longWindow = RollingStats(windowSamples = 2),
            stdFactor = 3f,
            floorStd = 0.05f
        )

        calibrator.push(0L, 0f)
        calibrator.push(1000 * ms, 1f)

        assertEquals(1.5f, calibrator.shortStdThreshold(), 0.001f)
        assertEquals(1.5f, calibrator.longStdThreshold(), 0.001f)
    }

    @Test
    fun `floor applies when the measured noise is unrealistically flat`() {
        // window size 2, values [0, 0]: std=0 exactly.
        // flooredNoiseStd = max(0, 0.05) = 0.05
        // threshold = max(0.05 * 3, 0.05) = 0.15
        // Without this floor, a perfectly flat calibration would derive a
        // threshold of 0 and every subsequent sample would read as signal.
        val calibrator = NoiseFloorCalibrator(
            shortWindow = RollingStats(windowSamples = 2),
            longWindow = RollingStats(windowSamples = 2),
            stdFactor = 3f,
            floorStd = 0.05f
        )

        calibrator.push(0L, 0f)
        calibrator.push(10 * ms, 0f)

        assertEquals(0.15f, calibrator.shortStdThreshold(), 0.001f)
        assertEquals(0.15f, calibrator.longStdThreshold(), 0.001f)
    }

    @Test
    fun `calibrated windows carry their history straight into a SegmentDetector built from them`() {
        val calibrator = NoiseFloorCalibrator(
            shortWindow = RollingStats(windowSamples = 2),
            longWindow = RollingStats(windowSamples = 2),
            stdFactor = 3f,
            floorStd = 0.05f
        )
        calibrator.push(0L, 0f)
        calibrator.push(10 * ms, 0f) // quiet calibration -> threshold floors to 0.15

        val detector = SegmentDetector(
            shortWindow = calibrator.shortWindow,
            longWindow = calibrator.longWindow,
            shortStdThreshold = calibrator.shortStdThreshold(),
            longStdThreshold = calibrator.longStdThreshold(),
            endQuietMs = 25L,
            minSegmentDurationMs = 5L
        )

        // buf carries over as [0,0] -> push 0 keeps it quiet, stays idle
        detector.push(20 * ms, 0f, turning = false)
        // buf=[0,5], mean=2.5, var=6.25, std=2.5 >= 0.15 -> a real jolt still
        // triggers detection using the just-calibrated threshold
        detector.push(30 * ms, 5f, turning = false)

        assertNotNull(detector.currentOpenSegment())
    }
}
