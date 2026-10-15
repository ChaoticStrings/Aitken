package com.aitken.app

import com.aitken.sensor.AccelerometerInfo
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.sign

/**
 * Vertical axis of the live graph: signed deviation from resting gravity
 * (m/s^2) -> fraction of the plot's half-height, -1..1.
 *
 * The axis is **symmetric-logarithmic** (`asinh`): near-linear within
 * [KNEE_MS2] of baseline, logarithmic beyond. A linear axis that also shows
 * a typical +/-78 m/s^2 sensor ceiling would squash ordinary road noise
 * (~1-3 m/s^2) to a pixel or two, and the yellow D line and thresholds
 * would be unreadable; this keeps both visible, and matches the
 * logarithmic M scale it is read against.
 *
 * The sensor's larger ceiling sits at [CEILING_FRACTION] of the half-height
 * so the band beyond it — the clipped spectrum — has room to be drawn. With
 * no [AccelerometerInfo] there are no ceilings and no band.
 */
class GraphScale(info: AccelerometerInfo?) {

    private val up: Float? = info?.ceilingDeviationUpMs2
    private val down: Float? = info?.ceilingDeviationDownMs2
    private val reference: Float =
        if (up != null && down != null) maxOf(compress(up), compress(down)) else compress(FALLBACK_EXTENT_MS2)

    /** Signed deviation -> fraction of half-height in -1..1 (positive is up). */
    fun fraction(deviationMs2: Float): Float =
        (sign(deviationMs2) * compress(deviationMs2) / reference * CEILING_FRACTION).coerceIn(-1f, 1f)

    /** Fraction at which the upward clip ceiling sits, or null if the sensor is unknown. */
    val ceilingFractionUp: Float? get() = up?.let { fraction(it) }

    /** Fraction at which the downward clip ceiling sits (as a positive magnitude), or null. */
    val ceilingFractionDown: Float? get() = down?.let { fraction(it) }

    private fun compress(x: Float): Float = asinh(abs(x) / KNEE_MS2)

    companion object {
        const val KNEE_MS2 = 3f
        const val CEILING_FRACTION = 0.85f
        const val FALLBACK_EXTENT_MS2 = 40f
    }
}
