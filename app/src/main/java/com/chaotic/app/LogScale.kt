package com.aitken.app

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** Maps a 0..1 slider position onto a logarithmic 1..100 m rough-patch distance and back. */
object LogScale {
    const val MIN_M = 1f
    const val MAX_M = 100f

    fun sliderToMetres(position: Float): Float =
        MIN_M * (MAX_M / MIN_M).pow(position.coerceIn(0f, 1f))

    fun metresToSlider(metres: Float): Float =
        ln(metres.coerceIn(MIN_M, MAX_M) / MIN_M) / ln(MAX_M / MIN_M)

    /** Rounds to a rider-friendly value: 0.1 m steps below 10 m, whole metres from there. */
    fun snap(metres: Float): Float {
        val clamped = metres.coerceIn(MIN_M, MAX_M)
        return if (clamped < 10f) (clamped * 10f).roundToInt() / 10f else clamped.roundToInt().toFloat()
    }
}
