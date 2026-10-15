package com.aitken.segment

/**
 * Turns GPS speed fixes into "the vehicle was accelerating or braking
 * hard" intervals, for [NoiseFloorCalibrator] to exclude from calibration.
 *
 * Why GPS speed rather than the accelerometer: the gravity estimate the
 * vertical signal is projected onto ([com.aitken.dsp.GravityEstimator],
 * alpha 0.90 ~ 0.1 s time constant) chases sustained horizontal
 * acceleration, so a launch or a hard stop leaks into `vertical`. Replay of
 * the real 90 s fixture session showed that same estimate wobbling several
 * degrees *while standing* (mount rattle), so "did gravity move" cannot
 * tell vibration from a launch — whereas a speed change of a metre per
 * second between consecutive fixes is vibration-proof by construction.
 *
 * Returns the sensor-timeline interval between the two fixes whose speed
 * difference implies |a| >= [maxAccelMs2]. Unknown speeds, or fixes more
 * than [maxFixGapMs] apart (too coarse to tell), produce no interval —
 * benefit of the doubt, matching how a stationary phone at session start
 * (no speed yet) must still be allowed to calibrate. Pure Kotlin.
 */
class LongitudinalMotionWatcher(
    private val maxAccelMs2: Float = 1.0f,
    private val maxFixGapMs: Long = 1_500L
) {

    private var prevSpeedMps: Float? = null
    private var prevAtNs: Long = 0L

    /** @return (fromNs, toNs) of a too-dynamic interval, or null. */
    fun onSpeed(atNs: Long, speedMps: Float?): Pair<Long, Long>? {
        val prev = prevSpeedMps
        val prevAt = prevAtNs
        prevSpeedMps = speedMps
        prevAtNs = atNs

        if (speedMps == null || prev == null) return null
        val dtMs = (atNs - prevAt) / 1_000_000L
        if (dtMs <= 0L || dtMs > maxFixGapMs) return null
        val accel = Math.abs(speedMps - prev) / (dtMs / 1000f)
        return if (accel >= maxAccelMs2) prevAt to atNs else null
    }
}
