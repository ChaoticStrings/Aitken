package com.aitken.segment

import com.aitken.dsp.RollingStats
import kotlin.math.max

/**
 * Derives SegmentDetector's short/long std thresholds from a quiet-road
 * calibration window, instead of a hardcoded absolute number a rider would
 * otherwise have to guess by trial and error — a threshold tuned for one
 * phone mount, vehicle, and road surface doesn't transfer to another's, and
 * a mismatched guess quietly skews which bumps count as signal across the
 * whole dataset.
 *
 * Owns the exact same two [RollingStats] windows [SegmentDetector] will go
 * on to use ([shortWindow]/[longWindow]), so they carry real recent history
 * straight from calibration into detection — no cold-start gap at the
 * boundary. Intended flow (wired into the recording pipeline in ticket 10):
 *
 * ```
 * val calibrator = NoiseFloorCalibrator()
 * // during calibration: repeatedly call calibrator.push(...) until it
 * // returns true (or calibrator.isDone reads true)
 * val detector = SegmentDetector(
 *     shortWindow = calibrator.shortWindow,
 *     longWindow = calibrator.longWindow,
 *     shortStdThreshold = calibrator.shortStdThreshold(),
 *     longStdThreshold = calibrator.longStdThreshold()
 * )
 * ```
 *
 * Each window's threshold is derived from *that window's own* measured
 * baseline std, not one flat number scaled two different ways — short and
 * long windows have genuinely different natural noise levels even on the
 * same quiet road (more averaging in the long window generally reads
 * lower), so calibrating them independently is what makes the two-window
 * design in [SegmentDetector] actually work as intended.
 *
 * [calibrationDurationMs], [stdFactor], and [floorStd] are `[CALIBRATE]` —
 * mirroring Prototype 1's already-audited MAIN-mode defaults (10s
 * calibration, 3x noise floor, 0.05 absolute floor) rather than re-derived
 * from scratch, but still expected to move once Aitken has its own real
 * ride data. The floor exists so a freakishly flat calibration window (e.g.
 * a stationary test on a table) can't derive a threshold of ~0, which would
 * make every subsequent sample read as signal.
 *
 * Does not account for turning during calibration — if that turns out to
 * matter in practice (a turn during the calibration window inflating the
 * baseline), it's a small addition here, not a redesign.
 */
class NoiseFloorCalibrator(
    val shortWindow: RollingStats = RollingStats(windowSamples = 20),
    val longWindow: RollingStats = RollingStats(windowSamples = 3000),
    private val calibrationDurationMs: Long = 10_000L,
    private val stdFactor: Float = 3f,
    private val floorStd: Float = 0.05f
) {

    private var startNs: Long? = null
    private var lastTimestampNs: Long = 0L
    private var lastShortStd = 0f
    private var lastLongStd = 0f

    /** True once at least [calibrationDurationMs] of samples have been pushed. */
    val isDone: Boolean
        get() {
            val start = startNs ?: return false
            return (lastTimestampNs - start) / 1_000_000L >= calibrationDurationMs
        }

    /** Fraction of [calibrationDurationMs] elapsed so far, clamped to 0f..1f — for a progress UI. */
    val progressFraction: Float
        get() {
            val start = startNs ?: return 0f
            val elapsedMs = (lastTimestampNs - start) / 1_000_000L
            return (elapsedMs.toFloat() / calibrationDurationMs).coerceIn(0f, 1f)
        }

    /**
     * Push one vertical-acceleration sample during the calibration window.
     *
     * @return true if this push crossed the calibration duration — the
     * caller should stop calibrating and read the thresholds below.
     */
    fun push(timestampNs: Long, verticalM: Float): Boolean {
        if (startNs == null) startNs = timestampNs
        lastTimestampNs = timestampNs
        lastShortStd = shortWindow.push(verticalM).std
        lastLongStd = longWindow.push(verticalM).std
        return isDone
    }

    /** The threshold SegmentDetector's short window should trigger above. */
    fun shortStdThreshold(): Float = threshold(lastShortStd)

    /** The threshold SegmentDetector's long window should trigger above. */
    fun longStdThreshold(): Float = threshold(lastLongStd)

    private fun threshold(measuredStd: Float): Float {
        val flooredNoiseStd = max(measuredStd, floorStd)
        return max(flooredNoiseStd * stdFactor, floorStd)
    }
}
