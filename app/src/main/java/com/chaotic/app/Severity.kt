package com.aitken.app

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

/**
 * Buckets [peakM] against [tunables]' `[CALIBRATE]` thresholds.
 *
 * [peakM] is already a deviation from the resting gravity baseline —
 * `SegmentDetector.accumulate()` computes `abs(verticalM - 9.81)` directly
 * (ride-data-analysis-update.md §5) — so no further gravity subtraction
 * happens here. (Before that fix, `peakM` was a raw `abs(vertical)`
 * magnitude and this function re-subtracted gravity itself; re-subtracting
 * now would double-apply the correction and silently reintroduce the same
 * misattribution the §5 fix removed.)
 */
fun severityOf(peakM: Float, tunables: Tunables): Severity {
    return when {
        peakM < tunables.mildSeverityDeviation -> Severity.MILD
        peakM < tunables.moderateSeverityDeviation -> Severity.MODERATE
        else -> Severity.SEVERE
    }
}
