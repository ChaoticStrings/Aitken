package com.aitken.recording

import com.aitken.dsp.GravityEstimator
import com.aitken.dsp.JerkFilter
import com.aitken.dsp.RollingStats
import com.aitken.dsp.Verticalizer
import com.aitken.location.GpsFix
import com.aitken.segment.CalibrationPreset
import com.aitken.segment.CalibrationResult
import com.aitken.segment.ClosedSegment
import com.aitken.segment.LongitudinalMotionWatcher
import com.aitken.segment.NoiseFloorCalibrator
import com.aitken.segment.Odometer
import com.aitken.segment.PatchEvent
import com.aitken.segment.RoughPatch
import com.aitken.segment.RoughPatchTracker
import com.aitken.segment.SegmentDetector
import com.aitken.segment.SlidingMean
import com.aitken.sensor.AccelerometerInfo
import com.aitken.sensor.SensorStream
import com.aitken.tagging.TagKind
import com.aitken.tagging.TagMatch
import com.aitken.tagging.TagMatcher
import kotlin.math.abs

/**
 * Wires sensor samples through DSP -> SegmentDetector / RoughPatchTracker ->
 * SessionRecorder, and feeds every closed segment to TagMatcher so lookback
 * has history to search. Pure orchestration logic, no Android dependency —
 * the sensor/GPS sources and the recorder's destination are all injected
 * (or, for the DSP/segment/calibration pieces, defaulted to fresh
 * instances), so this class is fully testable with a scripted sample
 * sequence. [AitkenRecordingService] is the thin Android-framework shell
 * that constructs this with real dependencies.
 *
 * Phases:
 *  - CALIBRATING (no [preset]): [NoiseFloorCalibrator] measures the M and D
 *    thresholds, ignoring turns and hard acceleration/braking (GPS-speed
 *    intervals are forwarded to it from [onGpsFix]).
 *  - WARMING_UP (with a [preset]): no measurement; the saved thresholds are
 *    used, after just long enough for the gravity estimate and the sliding
 *    window to settle.
 *  - DETECTING: [SegmentDetector] (M threshold) and [RoughPatchTracker] (D
 *    threshold + distance) run on every sample.
 *
 * `turning` is supplied per sample by the caller — real yaw-rate
 * thresholding from the gyro is a detail of [AitkenRecordingService].
 */
class RecordingPipeline(
    private val recorder: SessionRecorder,
    private val tagMatcher: TagMatcher,
    /** Called once, with the thresholds the session will detect with (fresh or from a preset). */
    private val onCalibrationDone: (CalibrationResult) -> Unit = {},
    private val gravity: GravityEstimator = GravityEstimator(),
    private val verticalizer: Verticalizer = Verticalizer(),
    private val jerkFilter: JerkFilter = JerkFilter(),
    private val rollingStats: RollingStats = RollingStats(),
    private val calibrator: NoiseFloorCalibrator = NoiseFloorCalibrator(),
    /** If set, calibration is skipped and these thresholds are used (see [rollingWindowMs]). */
    private val preset: CalibrationPreset? = null,
    /** Sliding-window width for the D reading. With a [preset] this must be the preset's own. */
    private val rollingWindowMs: Long = 1_000L,
    private val roughPatchDistanceM: Float = 2.5f,
    private val endQuietMs: Long = 500L,
    private val minSegmentDurationMs: Long = 30L,
    /** Used only to flag clipped samples on the live graph; never alters recorded data. */
    private val accelerometer: AccelerometerInfo? = null,
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Called with every computed vertical value. */
    private val onLiveVertical: (Float) -> Unit = {},
    /** Called with everything the live graph draws, once per sample. */
    private val onLiveSample: (LiveSample) -> Unit = {},
    /** Called with every closed segment, in addition to TagMatcher — feeds the M/D graph's segment markers. */
    private val onSegmentClosedForUi: (ClosedSegment) -> Unit = {},
    /** Called on every sample while CALIBRATING/WARMING_UP, with progress toward completion. */
    private val onCalibrationProgress: (Float) -> Unit = {},
    /** Called while CALIBRATING with whether the latest sample is being excluded (turning/accelerating). */
    private val onCalibrationPaused: (Boolean) -> Unit = {},
    /** A rough patch has just qualified (still in progress). */
    private val onPatchOpened: (Long) -> Unit = {},
    /** A registered rough patch has ended; already written to patches.csv. */
    private val onPatchClosed: (RoughPatch) -> Unit = {},
    /**
     * Minimum time between two [tag] calls of the same (kind, label) before
     * the second is ignored outright (ticket 21). Read fresh on every call.
     */
    private val tagDebounceMs: () -> Long = { 500L }
) {

    private enum class Phase { CALIBRATING, WARMING_UP, DETECTING }

    private var phase = if (preset != null) Phase.WARMING_UP else Phase.CALIBRATING
    private var detector: SegmentDetector? = null
    private var tracker: RoughPatchTracker? = null
    private var latestSpeedMps: Float? = null

    private val sliding = SlidingMean(rollingWindowMs)
    private val odometer = Odometer()
    private val motionWatcher = LongitudinalMotionWatcher()
    private var firstSensorTimestampNs: Long? = null

    /**
     * The most recently seen sensor sample's own timestamp — used as "now"
     * for manual taps ([tag]) and to stamp GPS fixes onto the sensor
     * timeline, deliberately never an independently-read system clock.
     * `SensorEvent.timestamp` and `System.nanoTime()`/`SystemClock` don't
     * reliably share an epoch on real devices (Prototype 1's audited
     * finding, `DEFAULT_CALIBRATION.md`).
     */
    private var lastSensorTimestampNs: Long? = null

    /** Last tap timestamp per (kind, label) — "the same button" — for debouncing. */
    private val lastTagTimestampNs = mutableMapOf<Pair<TagKind, String>, Long>()

    /** Caller feeds every GPS fix here as it arrives. */
    fun onGpsFix(fix: GpsFix) {
        latestSpeedMps = fix.speedMps
        recorder.writeGpsFix(fix)

        val at = lastSensorTimestampNs ?: return
        odometer.onSpeed(at, fix.speedMps)
        val dynamic = motionWatcher.onSpeed(at, fix.speedMps)
        if (dynamic != null && phase == Phase.CALIBRATING) {
            calibrator.markMotion(dynamic.first, dynamic.second)
        }
    }

    /**
     * Push one raw accelerometer/gyro sample.
     *
     * @param turning true if yaw rate is currently over threshold —
     * suppresses new segment starts and excludes the sample from calibration.
     */
    fun onSensorSample(sample: SensorStream.SensorSample, turning: Boolean) {
        val ts = sample.timestampNs
        lastSensorTimestampNs = ts
        if (firstSensorTimestampNs == null) firstSensorTimestampNs = ts

        val accel = sample.accelArray
        val g = gravity.update(accel)
        val vertical = verticalizer.verticalComponent(accel, g)
        val jerk = jerkFilter.push(vertical)
        val stats = rollingStats.push(vertical)
        val deviation = abs(vertical - GRAVITY_BASELINE_MS2)
        val roughness = sliding.push(ts, deviation)
        val odometerM = odometer.advance(ts)
        onLiveVertical(vertical)

        recorder.writeSensorSample(
            timestampSensorNs = ts,
            accelX = sample.accelX, accelY = sample.accelY, accelZ = sample.accelZ,
            gyroX = sample.gyroX, gyroY = sample.gyroY, gyroZ = sample.gyroZ,
            verticalMs2 = vertical, jerkMs3 = jerk, rollStdDev = stats.std
        )

        when (phase) {
            Phase.CALIBRATING -> {
                val done = calibrator.push(ts, vertical, turning)
                onCalibrationProgress(calibrator.progressFraction)
                onCalibrationPaused(calibrator.isPaused)
                if (done) startDetecting(calibrator.result())
            }
            Phase.WARMING_UP -> {
                val warmupMs = maxOf(rollingWindowMs, MIN_WARMUP_MS)
                val elapsedMs = (ts - (firstSensorTimestampNs ?: ts)) / 1_000_000L
                onCalibrationProgress((elapsedMs.toFloat() / warmupMs).coerceIn(0f, 1f))
                val p = preset
                if (p != null && elapsedMs >= warmupMs) {
                    startDetecting(
                        CalibrationResult(
                            mThreshold = p.mThreshold, dThreshold = p.dThreshold,
                            meanPeak = 0f, maxRoughness = 0f, peakCount = 0,
                            cleanMs = 0L, elapsedMs = elapsedMs, windowFilled = true,
                            presetName = p.name
                        )
                    )
                }
            }
            Phase.DETECTING -> {
                val d = detector ?: return // shouldn't happen; defensive, not a silent crash
                val tr = tracker ?: return
                val closed = d.push(ts, vertical, turning)
                val events = tr.onSample(
                    timestampNs = ts,
                    odometerM = odometerM,
                    deviation = deviation,
                    breach = d.breachedOnLastPush,
                    openSegmentStartNs = d.currentOpenSegment()?.startNs,
                    roughness = if (sliding.isFull) roughness else null
                )
                events.forEach { handle(it) }
                if (closed != null) emitClosed(closed)
            }
        }

        onLiveSample(
            LiveSample(
                vertical = vertical,
                roughness = roughness,
                segmentOpen = detector?.isOpen == true,
                clipped = accelerometer?.isClipped(sample.accelX, sample.accelY, sample.accelZ) == true
            )
        )
    }

    /** The currently-open segment, if any — for the manual tagging UI (ticket 11). */
    fun currentOpenSegment() = detector?.currentOpenSegment()

    /**
     * Match a manual tap (point or range boundary) against current segment
     * state and persist it to the labels file. Uses [lastSensorTimestampNs]
     * as the tap's timestamp, never an independently-read clock.
     *
     * @return the match result, or null if no sensor sample has arrived yet,
     * or if this exact (kind, label) button was tapped less than
     * [tagDebounceMs] ago — a debounced tap is dropped outright: it never
     * reaches [TagMatcher] and nothing is written to disk.
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

    /** Ends the session: force-closes any open segment and patch, then flushes and closes all files. */
    fun endSession() {
        detector?.endSession()?.let { emitClosed(it) }
        tracker?.endSession()?.let { handle(it) }
        recorder.close()
    }

    private fun startDetecting(result: CalibrationResult) {
        detector = SegmentDetector(
            mThreshold = result.mThreshold,
            endQuietMs = endQuietMs,
            minSegmentDurationMs = minSegmentDurationMs
        )
        tracker = RoughPatchTracker(
            patchDistanceM = roughPatchDistanceM,
            dThreshold = result.dThreshold,
            roughnessMemoryMs = rollingWindowMs
        )
        phase = Phase.DETECTING
        onCalibrationDone(result)
    }

    private fun emitClosed(closed: ClosedSegment) {
        recorder.writeClosedSegment(closed, latestSpeedMps, now())
        tagMatcher.onSegmentClosed(closed)
        onSegmentClosedForUi(closed)
    }

    private fun handle(event: PatchEvent) {
        when (event) {
            is PatchEvent.Opened -> onPatchOpened(event.startNs)
            is PatchEvent.Closed -> {
                recorder.writePatch(event.patch, latestSpeedMps, now())
                onPatchClosed(event.patch)
            }
        }
    }

    private companion object {
        const val GRAVITY_BASELINE_MS2 = 9.81f

        /** Enough for GravityEstimator's 50-sample warm-up at 100 Hz and then some. */
        const val MIN_WARMUP_MS = 1_000L
    }
}
