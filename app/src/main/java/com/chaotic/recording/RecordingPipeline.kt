package com.aitken.recording

import com.aitken.dsp.GravityEstimator
import com.aitken.dsp.JerkFilter
import com.aitken.dsp.RollingStats
import com.aitken.dsp.Verticalizer
import com.aitken.location.GpsFix
import com.aitken.segment.ClosedSegment
import com.aitken.segment.NoiseFloorCalibrator
import com.aitken.segment.SegmentDetector
import com.aitken.sensor.SensorStream
import com.aitken.tagging.TagKind
import com.aitken.tagging.TagMatch
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
    /** Called once, with the session's calibrated short-window std threshold (ticket 22). */
    private val onCalibrationDone: (Float) -> Unit = {},
    private val gravity: GravityEstimator = GravityEstimator(),
    private val verticalizer: Verticalizer = Verticalizer(),
    private val jerkFilter: JerkFilter = JerkFilter(),
    private val rollingStats: RollingStats = RollingStats(),
    private val calibrator: NoiseFloorCalibrator = NoiseFloorCalibrator(),
    private val endQuietMs: Long = 500L,
    private val minSegmentDurationMs: Long = 30L,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Called with every computed vertical value — feeds the live waveform (ticket 11's M/D graph). */
    private val onLiveVertical: (Float) -> Unit = {},
    /** Called with every closed segment, in addition to TagMatcher — feeds the M/D graph's segment markers. */
    private val onSegmentClosedForUi: (ClosedSegment) -> Unit = {},
    /** Called on every sample while CALIBRATING, with progress toward completion (ticket 22). */
    private val onCalibrationProgress: (Float) -> Unit = {},
    /**
     * Minimum time between two [tag] calls of the same (kind, label) before
     * the second is ignored outright (ticket 21). Read fresh on every call,
     * not captured once at construction, so a rider adjusting this in
     * Settings takes effect on their very next tap — never applies to the
     * data pipeline itself, only to whether a tap reaches it at all.
     */
    private val tagDebounceMs: () -> Long = { 500L }
) {

    private enum class Phase { CALIBRATING, DETECTING }

    private var phase = Phase.CALIBRATING
    private var detector: SegmentDetector? = null
    private var latestSpeedMps: Float? = null

    /**
     * The most recently seen sensor sample's own timestamp — used as "now"
     * for manual taps ([tag]), deliberately never an independently-read
     * system clock. `SensorEvent.timestamp` and `System.nanoTime()`/
     * `SystemClock` don't reliably share an epoch on real devices
     * (Prototype 1's own audited finding, `DEFAULT_CALIBRATION.md`: label
     * rows must carry the sensor clock, not `System.nanoTime()`, which was
     * observed to differ by hours). Reusing the last real sensor timestamp
     * sidesteps the whole question, by construction, regardless of which
     * clock the sensor actually uses.
     */
    private var lastSensorTimestampNs: Long? = null

    /** Last tap timestamp per (kind, label) — "the same button" — for debouncing. */
    private val lastTagTimestampNs = mutableMapOf<Pair<TagKind, String>, Long>()

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
        lastSensorTimestampNs = sample.timestampNs

        val accel = sample.accelArray
        val g = gravity.update(accel)
        val vertical = verticalizer.verticalComponent(accel, g)
        val jerk = jerkFilter.push(vertical)
        val stats = rollingStats.push(vertical)
        onLiveVertical(vertical)

        recorder.writeSensorSample(
            timestampSensorNs = sample.timestampNs,
            accelX = sample.accelX, accelY = sample.accelY, accelZ = sample.accelZ,
            gyroX = sample.gyroX, gyroY = sample.gyroY, gyroZ = sample.gyroZ,
            verticalMs2 = vertical, jerkMs3 = jerk, rollStdDev = stats.std
        )

        when (phase) {
            Phase.CALIBRATING -> {
                val done = calibrator.push(sample.timestampNs, vertical)
                onCalibrationProgress(calibrator.progressFraction)
                if (done) {
                    val shortThreshold = calibrator.shortStdThreshold()
                    detector = SegmentDetector(
                        shortWindow = calibrator.shortWindow,
                        longWindow = calibrator.longWindow,
                        shortStdThreshold = shortThreshold,
                        longStdThreshold = calibrator.longStdThreshold(),
                        endQuietMs = endQuietMs,
                        minSegmentDurationMs = minSegmentDurationMs
                    )
                    phase = Phase.DETECTING
                    onCalibrationDone(shortThreshold)
                }
            }
            Phase.DETECTING -> {
                val d = detector ?: return // shouldn't happen; defensive, not a silent crash
                val closed = d.push(sample.timestampNs, vertical, turning)
                if (closed != null) {
                    recorder.writeClosedSegment(closed, latestSpeedMps, now())
                    tagMatcher.onSegmentClosed(closed)
                    onSegmentClosedForUi(closed)
                }
            }
        }
    }

    /** The currently-open segment, if any — for the manual tagging UI (ticket 11). */
    fun currentOpenSegment() = detector?.currentOpenSegment()

    /**
     * Match a manual tap (point or range boundary) against current segment
     * state and persist it to the labels file — the actual logic behind
     * ticket 11's tap buttons. Uses [lastSensorTimestampNs] as the tap's
     * timestamp, never an independently-read clock (see that field's doc).
     *
     * @return the match result, or null if no sensor sample has arrived yet
     * (nothing to tag against — e.g. tapped during calibration's very first
     * instant, before any sample has been processed), or if this exact
     * (kind, label) button was tapped less than [tagDebounceMs] ago — a
     * debounced tap is dropped outright, same as an unmatched one is *not*:
     * it never reaches [TagMatcher] and nothing is written to disk.
     */
    fun tag(kind: TagKind, label: String): TagMatch? {
        val tapTimestampNs = lastSensorTimestampNs ?: return null

        val key = kind to label
        val lastTapNs = lastTagTimestampNs[key]
        if (lastTapNs != null && (tapTimestampNs - lastTapNs) / 1_000_000L < tagDebounceMs()) {
            return null
        }
        lastTagTimestampNs[key] = tapTimestampNs

        val match = tagMatcher.match(tapTimestampNs, kind, currentOpenSegment())
        val segmentStartNs = (match as? TagMatch.Matched)?.segmentStartNs
        val tapOffsetMs = (match as? TagMatch.Matched)?.tapOffsetMs
        recorder.writeLabel(
            timestampSensorNs = tapTimestampNs,
            kind = kind.name,
            segmentStartNs = segmentStartNs,
            label = label,
            tapOffsetMs = tapOffsetMs,
            epochMs = now()
        )
        return match
    }

    /** Ends the session: force-closes any open segment, then flushes and closes all four files. */
    fun endSession() {
        detector?.endSession()?.let { closed ->
            recorder.writeClosedSegment(closed, latestSpeedMps, now())
            tagMatcher.onSegmentClosed(closed)
            onSegmentClosedForUi(closed)
        }
        recorder.close()
    }
}
