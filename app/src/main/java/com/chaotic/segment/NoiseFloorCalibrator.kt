package com.aitken.segment

import kotlin.math.abs
import kotlin.math.max

/**
 * What a calibration run measured and the two thresholds derived from it.
 * Both thresholds are in m/s^2 of *deviation from resting gravity*
 * (`abs(vertical - 9.81)`), the same unit [SegmentDetector]'s peak M and the
 * live D reading use.
 *
 * @property mThreshold amplitude that opens a segment (see [SegmentDetector]).
 * @property dThreshold sliding-window roughness above which a rough patch is
 * registered (see [RoughPatchTracker]).
 * @property cleanMs how much *usable* calibration time was accumulated —
 * stretches spent turning or accelerating/braking are excluded.
 * @property windowFilled false means the sliding window never completed
 * during clean data, so [dThreshold] is just the floor.
 * @property presetName non-null when this result came from a saved preset
 * rather than a fresh measurement.
 */
data class CalibrationResult(
    val mThreshold: Float,
    val dThreshold: Float,
    val meanPeak: Float,
    val maxRoughness: Float,
    val peakCount: Int,
    val cleanMs: Long,
    val elapsedMs: Long,
    val windowFilled: Boolean,
    val presetName: String? = null
)

/**
 * Derives the two detection thresholds from a quiet-road calibration
 * window:
 *
 *  - **M threshold** = (mean of the *peaks* of `abs(vertical - gravity)` seen
 *    in the window) x [peakFactor]. A peak is a local maximum — a sample
 *    strictly above its predecessor and at least as high as its successor.
 *    "How high does this mount's ordinary noise typically reach?", scaled up
 *    by the rider's sensitivity setting.
 *  - **D threshold** = (the highest [rollingWindowMs]-wide moving average of
 *    `abs(vertical - gravity)` seen in the window) x [roughFactor]. "How
 *    rough does this mount's ordinary noise ever get, sustained?" — the
 *    highest hysteresis, then a margin so an ordinary long ride doesn't trip
 *    it from sheer extreme-value luck.
 *
 * Both are floored at [floor] so a freakishly flat window (phone on a
 * table) can't derive a threshold of ~0.
 *
 * **Horizontal motion does not feed calibration.** Previous behaviour took
 * a snapshot of the 20-sample std at the very last calibration sample, with
 * no awareness of turning or acceleration; the replayed real session's
 * calibration window spanned standing, a 5 m/s^2 launch, cruising and hard
 * braking, and the gravity estimate swung up to 36.6 degrees in 0.5 s inside
 * it. Now a sample is *dirty* — excluded from every statistic — when it is
 * within [settleMs] of (a) a turn ([push]'s `turning`) or (b) a too-dynamic
 * GPS-speed interval reported through [markMotion]. Because GPS speed
 * reports an acceleration only after it has happened, samples are held in a
 * buffer for [commitLagMs] before being judged, so a late [markMotion] can
 * still disqualify the seconds it describes. Clean time is what counts
 * toward [calibrationDurationMs]; if the rider never settles, calibration
 * still ends after [maxElapsedFactor] x the duration with whatever clean
 * data exists (floors if none).
 *
 * Pure Kotlin. `[CALIBRATE]`: every default here awaits real ride data.
 */
class NoiseFloorCalibrator(
    private val calibrationDurationMs: Long = 10_000L,
    private val rollingWindowMs: Long = 1_000L,
    private val peakFactor: Float = 3f,
    private val roughFactor: Float = 1.5f,
    private val floor: Float = 0.5f,
    private val settleMs: Long = 500L,
    private val commitLagMs: Long = 2_000L,
    private val maxElapsedFactor: Int = 3,
    private val gravityBaselineMs2: Float = 9.81f
) {

    private class Pending(val timestampNs: Long, val deviation: Float)

    private val pending = ArrayDeque<Pending>()
    private val dirtyIntervals = ArrayList<LongArray>()
    private val sliding = SlidingMean(rollingWindowMs)

    private var firstNs: Long? = null
    private var latestNs: Long = 0L
    private var done = false
    private var pausedNow = false

    // Committed (final) statistics.
    private var prevPrevDev: Float? = null
    private var prevDev: Float? = null
    private var peakSum = 0.0
    private var peakCount = 0
    private var maxRoughness = 0f
    private var windowFilled = false
    private var prevCleanNs: Long? = null
    private var cleanNs = 0L

    val isDone: Boolean get() = done

    /** True while the most recent sample is excluded (turning / accelerating) — for a "hold steady" UI hint. */
    val isPaused: Boolean get() = pausedNow && !done

    /** Clean calibration time accumulated so far as a fraction of the target, 0f..1f. */
    val progressFraction: Float
        get() = if (done) 1f else (cleanNs / 1_000_000L).toFloat().div(calibrationDurationMs).coerceIn(0f, 1f)

    /**
     * Mark [fromNs]..[toNs] (sensor timeline) as too dynamic — see
     * [LongitudinalMotionWatcher]. May describe the past: samples are still
     * buffered, not yet judged, for [commitLagMs].
     */
    fun markMotion(fromNs: Long, toNs: Long) {
        val settleNs = settleMs * 1_000_000L
        addInterval(fromNs - settleNs, toNs + settleNs)
    }

    /**
     * Push one vertical-acceleration sample.
     *
     * @param turning true if yaw rate is currently over threshold.
     * @return true once calibration is complete — read [result].
     */
    fun push(timestampNs: Long, verticalM: Float, turning: Boolean): Boolean {
        if (done) return true
        if (firstNs == null) firstNs = timestampNs
        latestNs = timestampNs

        if (turning) {
            val settleNs = settleMs * 1_000_000L
            addInterval(timestampNs - settleNs, timestampNs + settleNs)
        }
        pausedNow = isDirty(timestampNs)
        pending.addLast(Pending(timestampNs, abs(verticalM - gravityBaselineMs2)))

        val lagNs = commitLagMs * 1_000_000L
        while (pending.isNotEmpty() && latestNs - pending.first().timestampNs >= lagNs) {
            commit(pending.removeFirst())
        }

        val elapsedMs = (latestNs - (firstNs ?: latestNs)) / 1_000_000L
        if (cleanNs / 1_000_000L >= calibrationDurationMs ||
            elapsedMs >= calibrationDurationMs * maxElapsedFactor
        ) {
            done = true
        }
        return done
    }

    fun result(): CalibrationResult {
        val meanPeak = if (peakCount > 0) (peakSum / peakCount).toFloat() else 0f
        val filledRoughness = if (windowFilled) maxRoughness else 0f
        return CalibrationResult(
            mThreshold = max(meanPeak * peakFactor, floor),
            dThreshold = max(filledRoughness * roughFactor, floor),
            meanPeak = meanPeak,
            maxRoughness = filledRoughness,
            peakCount = peakCount,
            cleanMs = cleanNs / 1_000_000L,
            elapsedMs = (latestNs - (firstNs ?: latestNs)) / 1_000_000L,
            windowFilled = windowFilled
        )
    }

    private fun commit(sample: Pending) {
        // Intervals wholly before this sample can never matter again.
        dirtyIntervals.removeAll { it[1] < sample.timestampNs }

        if (isDirty(sample.timestampNs)) {
            // A gap: stop treating neighbours as adjacent.
            prevPrevDev = null
            prevDev = null
            prevCleanNs = null
            sliding.reset()
            return
        }

        val previousClean = prevCleanNs
        if (previousClean != null) cleanNs += sample.timestampNs - previousClean
        prevCleanNs = sample.timestampNs

        val dev = sample.deviation
        val a = prevPrevDev
        val b = prevDev
        if (a != null && b != null && b > a && b >= dev) {
            peakSum += b
            peakCount++
        }
        prevPrevDev = b
        prevDev = dev

        val mean = sliding.push(sample.timestampNs, dev)
        if (sliding.isFull) {
            windowFilled = true
            if (mean > maxRoughness) maxRoughness = mean
        }
    }

    private fun isDirty(timestampNs: Long): Boolean =
        dirtyIntervals.any { timestampNs >= it[0] && timestampNs <= it[1] }

    private fun addInterval(fromNs: Long, toNs: Long) {
        val last = dirtyIntervals.lastOrNull()
        if (last != null && fromNs <= last[1] && toNs >= last[0]) {
            last[0] = minOf(last[0], fromNs)
            last[1] = maxOf(last[1], toNs)
        } else {
            dirtyIntervals.add(longArrayOf(fromNs, toNs))
        }
    }
}
