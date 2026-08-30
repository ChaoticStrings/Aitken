package com.aitken.app

import kotlin.math.abs

/**
 * Placeholder relative severity bucketing for the M/D graph's segment
 * coloring — NOT a calibrated M-scale. Prototype 1's
 * `docs/DEFAULT_CALIBRATION.md` builds a real logarithmic M0–M10 scale
 * anchored to a noise floor and a real maximum observed event from an
 * actual ride; that's the template to follow once Aitken has its own
 * accel-based ride data to anchor against (Prototype 1's anchors are
 * jerk-based and don't transfer directly — different signal). Until then,
 * this coarse 3-tier stand-in is what the graph shows.
 */
enum class Severity { MILD, MODERATE, SEVERE }

private const val GRAVITY_BASELINE_MS2 = 9.81f

/**
 * How far [peakM] (always >= 0 — `SegmentDetector` tracks peak
 * `abs(vertical)`) deviates from the resting gravity baseline in either
 * direction, bucketed against [tunables]' `[CALIBRATE]` thresholds.
 */
fun severityOf(peakM: Float, tunables: Tunables): Severity {
    val deviation = abs(peakM - GRAVITY_BASELINE_MS2)
    return when {
        deviation < tunables.mildSeverityDeviation -> Severity.MILD
        deviation < tunables.moderateSeverityDeviation -> Severity.MODERATE
        else -> Severity.SEVERE
    }
}
