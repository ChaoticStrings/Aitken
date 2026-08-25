package com.aitken.dsp

/**
 * Finite-difference jerk filter over a small sample window.
 *
 * Pure Kotlin. No Android imports.
 *
 * @param windowSamples number of samples in the derivative window.
 * @param dtSeconds fixed sample period, default 0.01 s for 100 Hz.
 */
class JerkFilter(
    private val windowSamples: Int = 4,
    private val dtSeconds: Float = 0.01f
) {

    private val buffer = ArrayDeque<Float>()

    /**
     * Push a new vertical acceleration value.
     *
     * @return signed jerk in m/s^3, or null until the window is filled.
     */
    fun push(vertical: Float): Float? {
        buffer.addLast(vertical)

        if (buffer.size < windowSamples + 1) {
            return null
        }

        while (buffer.size > windowSamples + 1) {
            buffer.removeFirst()
        }

        val first = buffer.first()
        val last = buffer.last()
        return (last - first) / (windowSamples * dtSeconds)
    }

    fun reset() {
        buffer.clear()
    }
}
