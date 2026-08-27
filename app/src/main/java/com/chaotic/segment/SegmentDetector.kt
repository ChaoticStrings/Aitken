package com.aitken.segment

import com.aitken.dsp.RollingStats
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
 * Open-ended, hysteresis-based segment detector (T5) — the novel core the
 * whole Luna redesign depends on. No fixed duration ceiling.
 *
 * Consumes a short (spike-triggering, ~200ms) and a long (sustained-
 * roughness, tens of seconds) [RollingStats] window; either reading above
 * its own threshold counts as signal. This is what lets a brief smooth lull
 * inside a long rough stretch keep the segment open even when the short
 * window alone would momentarily read "quiet" — the long window still
 * remembers the recent roughness.
 *
 * Peak-M and RMS-M accumulate only over signal-true samples — quiet
 * in-segment lulls and the trailing hysteresis quiet-tail never drag the
 * reported magnitude down. Duration is measured to the last signal-true
 * sample, excluding that same quiet tail, on both a natural close and a
 * forced one (`endSession`).
 *
 * [shortStdThreshold], [longStdThreshold], [endQuietMs], and
 * [minSegmentDurationMs] are `[CALIBRATE]` — placeholders pending real ride
 * data, not derived from any audited session. The default window sizes
 * assume a ~100Hz sample rate (20 samples ~= 200ms, 3000 samples ~= 30s).
 *
 * [shortStdThreshold] and [longStdThreshold] in particular should come from
 * [NoiseFloorCalibrator], not a hand-picked number — a guessed absolute
 * threshold doesn't transfer across phone mounts, vehicles, or road
 * surfaces, and would quietly skew which bumps count as signal across the
 * dataset.
 */
class SegmentDetector(
    private val shortWindow: RollingStats = RollingStats(windowSamples = 20),
    private val longWindow: RollingStats = RollingStats(windowSamples = 3000),
    private val shortStdThreshold: Float = 2f,
    private val longStdThreshold: Float = 1f,
    private val endQuietMs: Long = 500L,
    private val minSegmentDurationMs: Long = 30L
) {

    private enum class Phase { IDLE, OPEN }

    private var phase = Phase.IDLE
    private var startNs = 0L
    private var lastSignalNs = 0L
    private var peakM = 0f
    private var sumSq = 0f
    private var signalSamples = 0

    /**
     * Push one vertical-acceleration sample.
     *
     * @param turning true if yaw rate is currently over threshold; suppresses
     * a new segment start but never truncates an already-open segment.
     * @return the segment that just closed, or null if none did.
     */
    fun push(timestampNs: Long, verticalM: Float, turning: Boolean): ClosedSegment? {
        val shortStd = shortWindow.push(verticalM).std
        val longStd = longWindow.push(verticalM).std
        val anySignal = shortStd >= shortStdThreshold || longStd >= longStdThreshold

        return when (phase) {
            Phase.IDLE -> {
                if (anySignal && !turning) open(timestampNs, verticalM)
                null
            }
            Phase.OPEN -> {
                if (anySignal) {
                    lastSignalNs = timestampNs
                    accumulate(verticalM)
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

    private fun open(timestampNs: Long, verticalM: Float) {
        phase = Phase.OPEN
        startNs = timestampNs
        lastSignalNs = timestampNs
        peakM = 0f
        sumSq = 0f
        signalSamples = 0
        accumulate(verticalM)
    }

    private fun accumulate(verticalM: Float) {
        val magnitude = abs(verticalM)
        if (magnitude > peakM) peakM = magnitude
        sumSq += magnitude * magnitude
        signalSamples++
    }

    private fun rms(): Float = if (signalSamples > 0) sqrt(sumSq / signalSamples) else 0f

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
