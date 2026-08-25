package com.aitken.dsp

import kotlin.math.sqrt

/**
 * Exponential low-pass estimate of the gravity vector.
 *
 * Pure Kotlin. No Android imports.
 *
 * @param alpha previous-estimate weight. 0.90 means gravity tracks slowly;
 * the new sample contributes only 10%.
 * @param warmupSamples number of samples before isWarmed becomes true.
 */
class GravityEstimator(
    private val alpha: Float = 0.90f,
    private val warmupSamples: Int = 50
) {

    private var gravity = FloatArray(3) { 0f }
    private var sampleCount = 0

    val isWarmed: Boolean
        get() = sampleCount >= warmupSamples

    /** Reset to an uninitialized state. */
    fun reset() {
        gravity = FloatArray(3) { 0f }
        sampleCount = 0
    }

    /**
     * Update the estimate with a new 3-axis accelerometer reading.
     *
     * @param accel 3-element array in m/s^2.
     * @return a copy of the current gravity estimate.
     */
    fun update(accel: FloatArray): FloatArray {
        require(accel.size == 3) { "accel must have 3 elements" }

        if (sampleCount == 0) {
            gravity[0] = accel[0]
            gravity[1] = accel[1]
            gravity[2] = accel[2]
        } else {
            gravity[0] = alpha * gravity[0] + (1f - alpha) * accel[0]
            gravity[1] = alpha * gravity[1] + (1f - alpha) * accel[1]
            gravity[2] = alpha * gravity[2] + (1f - alpha) * accel[2]
        }

        sampleCount++
        return gravity.clone()
    }

    fun magnitude(): Float {
        return sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
    }
}
