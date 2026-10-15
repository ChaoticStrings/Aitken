package com.aitken.sensor

import kotlin.math.abs
import kotlin.math.max

/**
 * What the phone's accelerometer can and cannot represent — read from
 * `Sensor.getMaximumRange()` / `getVendor()` by [AndroidSensorStream].
 * Pure data, no Android imports.
 *
 * Vertical readings are anchored at resting gravity (9.81 m/s^2), so an
 * upward impact can only register [ceilingDeviationUpMs2] of deviation
 * before the axis saturates at [maxRangeMs2]; a downward swing has
 * [ceilingDeviationDownMs2].
 */
data class AccelerometerInfo(
    val name: String,
    val vendor: String,
    val maxRangeMs2: Float,
    val resolutionMs2: Float
) {
    val ceilingDeviationUpMs2: Float get() = maxRangeMs2 - GRAVITY_MS2
    val ceilingDeviationDownMs2: Float get() = maxRangeMs2 + GRAVITY_MS2

    /** True if any axis sits at (within a hair of) the sensor's saturation limit. */
    fun isClipped(x: Float, y: Float, z: Float): Boolean =
        max(abs(x), max(abs(y), abs(z))) >= maxRangeMs2 * CLIP_FRACTION

    companion object {
        const val GRAVITY_MS2 = 9.81f

        /** A real saturated reading sits at the range limit, give or take float noise. */
        const val CLIP_FRACTION = 0.999f
    }
}
