package com.aitken.segment

import kotlin.math.abs
import kotlin.math.sqrt

/** A closed, ready-to-tag road-feature segment. */
data class ClosedSegment(
    val startNs: Long,
    val durationNs: Long,
    val peakM: Float,
    val rmsM: Float
)

/** An in-progress segment, exposed so TagMatcher (ticket 06) can attach a tag before it closes. */
data class OpenSegment(
    val startNs: Long,
    val lastSignalNs: Long,
    val peakM: Float,
    val rmsM: Float
)

/**
 * Amplitude-triggered, open-ended segment detector — the novel core the
 * whole Luna redesign depends on. No fixed duration ceiling.
 *
 * **Opens at the very sample whose deviation from resting gravity,
 * `abs(vertical - gravityBaselineMs2)`, reaches [mThreshold]**, and keeps
 * recording until the waveform has stayed *below* that threshold for
 * [endQuietMs] straight ("consistently falls below the threshold and the
 * event has passed" — a real impact rings through zero crossings, so a
 * single sub-threshold sample must not end it). The segment's duration runs
 * from the opening sample to the *last* sample at or above the threshold,
 * excluding the quiet tail that confirmed the end.
 *
 * Replaces the earlier two-window rolling-std trigger. That trigger fired
 * only once a 20-sample window's std had built up (a lone spike needed
 * roughly 4.6x the threshold to fire at all, so onsets lagged and were
 * blurred), stayed open for the length of its window after the waveform
 * finished, and its 30-second window was why single "segments" of 56 s,
 * 104 s and 311 s existed. Sustained roughness is now the
 * [RoughPatchTracker]'s job, not this class's.
 *
 * [peakM] is the largest deviation seen; [rmsM] is the RMS of deviation
 * over every sample from the opening sample to the last threshold breach —
 * in-segment lulls count (they are part of the event), the trailing quiet
 * tail does not.
 *
 * [mThreshold] comes from [NoiseFloorCalibrator]; a guessed absolute number
 * doesn't transfer across phone mounts, vehicles, or road surfaces.
 */
class SegmentDetector(
    private val mThreshold: Float,
    private val endQuietMs: Long = 500L,
    private val minSegmentDurationMs: Long = 30L,
    private val gravityBaselineMs2: Float = 9.81f
) {

    private enum class Phase { IDLE, OPEN }

    private var phase = Phase.IDLE
    private var startNs = 0L
    private var lastSignalNs = 0L
    private var peakM = 0f
    private var sumSq = 0.0
    private var samples = 0
    private var sumSqAtSignal = 0.0
    private var samplesAtSignal = 0

    /** True if the most recent [push] was a threshold breach inside an open segment. */
    var breachedOnLastPush: Boolean = false
        private set

    /** True while a segment is open (including its quiet tail, before it has closed). */
    val isOpen: Boolean get() = phase == Phase.OPEN

    /**
     * Push one vertical-acceleration sample.
     *
     * @param turning true if yaw rate is currently over threshold; suppresses
     * a new segment start but never truncates an already-open segment.
     * @return the segment that just closed, or null if none did.
     */
    fun push(timestampNs: Long, verticalM: Float, turning: Boolean): ClosedSegment? {
        val deviation = abs(verticalM - gravityBaselineMs2)
        val breach = deviation >= mThreshold
        breachedOnLastPush = false

        return when (phase) {
            Phase.IDLE -> {
                if (breach && !turning) {
                    phase = Phase.OPEN
                    startNs = timestampNs
                    lastSignalNs = timestampNs
                    peakM = deviation
                    sumSq = 0.0
                    samples = 0
                    accumulate(deviation)
                    markSignal()
                    breachedOnLastPush = true
                }
                null
            }
            Phase.OPEN -> {
                accumulate(deviation)
                if (breach) {
                    lastSignalNs = timestampNs
                    if (deviation > peakM) peakM = deviation
                    markSignal()
                    breachedOnLastPush = true
                    null
                } else {
                    val quietMs = (timestampNs - lastSignalNs) / 1_000_000L
                    if (quietMs >= endQuietMs) close() else null
                }
            }
        }
    }

    /** Force-closes an open segment — call when a recording session ends. */
    fun endSession(): ClosedSegment? = if (phase == Phase.OPEN) close() else null

    /** The in-progress segment, or null while IDLE. */
    fun currentOpenSegment(): OpenSegment? {
        if (phase != Phase.OPEN) return null
        return OpenSegment(startNs, lastSignalNs, peakM, rms())
    }

    private fun accumulate(deviation: Float) {
        sumSq += deviation.toDouble() * deviation
        samples++
    }

    private fun markSignal() {
        sumSqAtSignal = sumSq
        samplesAtSignal = samples
    }

    private fun rms(): Float =
        if (samplesAtSignal > 0) sqrt(sumSqAtSignal / samplesAtSignal).toFloat() else 0f

    private fun close(): ClosedSegment? {
        val durationNs = lastSignalNs - startNs
        val durationMs = durationNs / 1_000_000L
        val result = if (durationMs >= minSegmentDurationMs) {
            ClosedSegment(startNs, durationNs, peakM, rms())
        } else {
            null
        }
        phase = Phase.IDLE
        return result
    }
}
