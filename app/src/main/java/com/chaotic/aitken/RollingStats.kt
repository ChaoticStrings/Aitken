package com.aitken.dsp

import kotlin.math.sqrt

/**
 * Rolling mean and population standard deviation over a fixed window.
 *
 * Pure Kotlin. No Android imports.
 *
 * @param windowSamples number of samples in the rolling window.
 */
class RollingStats(
    private val windowSamples: Int = 20
) {

    private val buffer = ArrayDeque<Float>()
    private var sum = 0f
    private var sumSq = 0f

    data class Stats(
        val mean: Float,
        val std: Float
    )

    fun push(value: Float): Stats {
        buffer.addLast(value)
        sum += value
        sumSq += value * value

        if (buffer.size > windowSamples) {
            val removed = buffer.removeFirst()
            sum -= removed
            sumSq -= removed * removed
        }

        val n = buffer.size
        val mean = sum / n
        val variance = (sumSq / n - mean * mean).coerceAtLeast(0f)
        return Stats(mean, sqrt(variance))
    }

    fun reset() {
        buffer.clear()
        sum = 0f
        sumSq = 0f
    }
}
