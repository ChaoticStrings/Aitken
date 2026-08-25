package com.aitken.dsp

import kotlin.math.sqrt

/**
 * Projects raw acceleration onto the gravity direction, making the result
 * invariant to phone orientation.
 *
 * Pure Kotlin. No Android imports.
 */
class Verticalizer {

    /**
     * @param accel 3-axis accelerometer reading in m/s^2.
     * @param gravity 3-axis gravity estimate in m/s^2.
     * @return signed vertical acceleration in m/s^2.
     */
    fun verticalComponent(accel: FloatArray, gravity: FloatArray): Float {
        require(accel.size == 3) { "accel must have 3 elements" }
        require(gravity.size == 3) { "gravity must have 3 elements" }

        val norm = sqrt(
            gravity[0] * gravity[0] +
                gravity[1] * gravity[1] +
                gravity[2] * gravity[2]
        )

        if (norm < 1e-6f) return 0f

        return (accel[0] * gravity[0] + accel[1] * gravity[1] + accel[2] * gravity[2]) / norm
    }
}
