package com.aitken.app

import kotlin.math.ln

/**
 * The M (magnitude) scale, 0..10, logarithmic in peak deviation from
 * resting gravity — a direct port of the Workbench prototype's `M_SCALE` /
 * `peakToMScale` (Prototype/workbench.html), the source of truth for these
 * numbers. `[CALIBRATE]`: change them there and here together.
 */
object MScale {
    /** Peak at/under this reads as M0 (near gravity/noise). */
    const val FLOOR_MS2 = 10f

    /** Peak that reads as [ANCHOR_M]. */
    const val ANCHOR_PEAK_MS2 = 60f
    const val ANCHOR_M = 6f
    const val MAX_M = 10f

    fun peakToM(peakMs2: Float): Float {
        if (!peakMs2.isFinite() || peakMs2 <= FLOOR_MS2) return 0f
        val raw = ANCHOR_M * ln(peakMs2 / FLOOR_MS2) / ln(ANCHOR_PEAK_MS2 / FLOOR_MS2)
        return raw.coerceIn(0f, MAX_M)
    }
}
