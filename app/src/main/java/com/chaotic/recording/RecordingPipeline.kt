package com.aitken.recording

import com.aitken.dsp.GravityEstimator
import com.aitken.dsp.JerkFilter
import com.aitken.dsp.RollingStats
import com.aitken.dsp.Verticalizer
import com.aitken.location.GpsFix
import com.aitken.segment.NoiseFloorCalibrator
import com.aitken.segment.SegmentDetector
import com.aitken.sensor.SensorStream
import com.aitken.tagging.TagMatcher

/**
 * Wires sensor samples through DSP -> SegmentDetector -> SessionRecorder,
 * and feeds every closed segment to TagMatcher so lookback has history to
 * search. Pure orchestration logic, no Android dependency — the sensor/GPS
 * sources and the recorder's destination are all injected (or, for the
 * DSP/segment/calibration pieces, defaulted to fresh instances), so this
 * class is fully testable with a scripted sample sequence, the same way
 * SessionRecorder's own tests work. [AitkenRecordingService] is the thin
 * Android-framework shell that constructs this with real dependencies and
 * feeds it real sensor/GPS callbacks.
 *
 * Two phases, mirroring Prototype 1's MAIN mode: CALIBRATING (for
 * [NoiseFloorCalibrator]'s duration), then DETECTING. `turning` is supplied
 * per sample by the caller — real yaw-rate thresholding from the gyro is a
 * detail of [AitkenRecordingService], since neither the calibrator nor the
 * detector needs to know how it's computed, only the resulting boolean.
 */
class RecordingPipeline(
    private val recorder: SessionRecorder,
    private val tagMatcher: TagMatcher,
    private val onCalibrationDone: () -> Unit = {},
    private val gravity: GravityEstimator = GravityEstimator(),
    private val verticalizer: Verticalizer = Verticalizer(),
    private val jerkFilter: JerkFilter = JerkFilter(),
    private val rollingStats: RollingStats = RollingStats(),
    private val calibrator: NoiseFloorCalibrator = NoiseFloorCalibrator(),
    private val endQuietMs: Long = 500L,
    private val minSegmentDurationMs: Long = 30L,
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    private enum class Phase { CALIBRATING, DETECTING }

    private var phase = Phase.CALIBRATING
    private var detector: SegmentDetector? = null
    private var latestSpeedMps: Float? = null

    /** Caller feeds every GPS fix here as it arrives. */
    fun onGpsFix(fix: GpsFix) {
        latestSpeedMps = fix.speedMps
        recorder.writeGpsFix(fix)
    }

    /**
     * Push one raw accelerometer/gyro sample.
     *
     * @param turning true if yaw rate is currently over threshold —
     * suppresses new segment starts, per [SegmentDetector].
     */
    fun onSensorSample(sample: SensorStream.SensorSample, turning: Boolean) {
        val accel = sample.accelArray
        val g = gravity.update(accel)
        val vertical = verticalizer.verticalComponent(accel, g)
        val jerk = jerkFilter.push(vertical)
        val stats = rollingStats.push(vertical)

        recorder.writeSensorSample(
            timestampSensorNs = sample.timestampNs,
            accelX = sample.accelX, accelY = sample.accelY, accelZ = sample.accelZ,
            gyroX = sample.gyroX, gyroY = sample.gyroY, gyroZ = sample.gyroZ,
            verticalMs2 = vertical, jerkMs3 = jerk, rollStdDev = stats.std
        )

        when (phase) {
            Phase.CALIBRATING -> {
                val done = calibrator.push(sample.timestampNs, vertical)
                if (done) {
                    detector = SegmentDetector(
                        shortWindow = calibrator.shortWindow,
                        longWindow = calibrator.longWindow,
                        shortStdThreshold = calibrator.shortStdThreshold(),
                        longStdThreshold = calibrator.longStdThreshold(),
                        endQuietMs = endQuietMs,
                        minSegmentDurationMs = minSegmentDurationMs
                    )
                    phase = Phase.DETECTING
                    onCalibrationDone()
                }
            }
            Phase.DETECTING -> {
                val d = detector ?: return // shouldn't happen; defensive, not a silent crash
                val closed = d.push(sample.timestampNs, vertical, turning)
                if (closed != null) {
                    recorder.writeClosedSegment(closed, latestSpeedMps, now())
                    tagMatcher.onSegmentClosed(closed)
                }
            }
        }
    }

    /** The currently-open segment, if any — for the manual tagging UI (ticket 11). */
    fun currentOpenSegment() = detector?.currentOpenSegment()

    /** Ends the session: force-closes any open segment, then flushes and closes all four files. */
    fun endSession() {
        detector?.endSession()?.let { closed ->
            recorder.writeClosedSegment(closed, latestSpeedMps, now())
            tagMatcher.onSegmentClosed(closed)
        }
        recorder.close()
    }
}
