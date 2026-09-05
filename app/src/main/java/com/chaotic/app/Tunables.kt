package com.aitken.app

/**
 * All rider-adjustable session parameters, persisted via [SettingsStore]
 * and surfaced as sliders in the settings screen. Every field here is
 * `[CALIBRATE]` — a starting point, not a derived constant.
 *
 * Prototype 1's `docs/DEFAULT_CALIBRATION.md` is the template for how
 * these get properly audited once real Aitken ride data exists: measure a
 * noise floor and real event strengths from a real session, bound each
 * threshold between them, cite the evidence. That audit can't be done yet
 * because Aitken's `SegmentDetector` triggers off vertical-acceleration
 * *std* (a `RollingStats` window), not jerk-threshold like Prototype 1's
 * old model — a different signal, so Prototype 1's actual numbers don't
 * transfer, only its methodology does.
 *
 * One real number worth carrying forward: that same audit measured the
 * device's *actual* sensor rate at 125.45 Hz, not the nominal 100 Hz this
 * codebase's window-size comments assume. Doesn't change any default here
 * (window sizes are sample-counted, not time-counted, so they self-adapt),
 * but worth knowing when reasoning about "how many ms is N samples."
 */
data class Tunables(
    /** How long the quiet-road calibration phase runs before detection starts. */
    val calibrationDurationMs: Long = 10_000L,
    /** Multiplier on measured baseline std to derive the detection threshold. */
    val stdFactor: Float = 3f,
    /** Absolute floor under the measured/derived std, so a freakishly flat calibration can't zero out. */
    val floorStd: Float = 0.05f,
    /** How long a segment must read quiet before it's considered closed. */
    val endQuietMs: Long = 500L,
    /** Segments shorter than this are discarded silently, not emitted. */
    val minSegmentDurationMs: Long = 30L,
    /** Yaw rate above this is treated as "turning," suppressing new segment starts. */
    val turnYawThresholdRadS: Float = 1.0f,
    /** Below this, a segment's peak deviation from the gravity baseline reads as MILD on the graph. */
    val mildSeverityDeviation: Float = 5f,
    /** Below this (and at/above mild), a segment reads as MODERATE; at/above it, SEVERE. */
    val moderateSeverityDeviation: Float = 15f,
    /**
     * Minimum time between two taps of the same tag button before the
     * second is ignored, so a shaking mount can't double-fire one button.
     * Unlike every other field here, this one is read fresh on every tap
     * rather than once per session — see `AitkenRecordingService`.
     */
    val tagDebounceMs: Long = 500L,
    /**
     * A currently-open segment reads as "suspiciously long" on the live
     * session screen once it's been open this long (recommended pipeline
     * fix #5, ride-data-analysis-update.md) — the live-screen counterpart
     * to that doc's forensic finding that a single road feature spanning
     * 5+ minutes (124056's 311s segment) should never happen and nothing
     * called attention to it while riding. 20s is well above any
     * legitimate single bump/pothole/speedbreaker (all under 5s in the
     * doc's clean anchor set, §2) and above a deliberately-tagged "Rough
     * stretch" range too, while sitting well below the pathological 56s/
     * 104s/311s segments that motivated this fix — so it flags the merge
     * failure early without false-triggering on ordinary riding.
     */
    val longSegmentWarningMs: Long = 20_000L
)
