package com.aitken.segment

/**
 * Time-windowed moving average — the "sliding window" behind the D
 * (roughness / hysteresis) reading. Unlike [com.aitken.dsp.RollingStats]
 * (sample-counted), the window here is a span of *time* ([windowMs]), so
 * its width means the same thing whatever the real sensor rate turns out
 * to be (Prototype 1's audit measured 125.45 Hz on-device, not the nominal
 * 100 Hz) — the rider tunes it in milliseconds, not samples.
 *
 * The window is the half-open span `(now - windowMs, now]`: a sample
 * exactly [windowMs] old has just left it. Pure Kotlin, no Android imports.
 */
class SlidingMean(private val windowMs: Long) {

    private val timestamps = ArrayDeque<Long>()
    private val values = ArrayDeque<Float>()
    private var sum = 0.0
    private var firstTimestampNs: Long? = null
    private var latestTimestampNs: Long = 0L

    /**
     * True once the samples pushed since the last [reset] span at least
     * [windowMs] — i.e. the window has been fully covered at least once.
     * Callers that compare the mean against a threshold should ignore it
     * until this is true: a half-filled window is a startup artefact, not a
     * measurement.
     */
    val isFull: Boolean
        get() {
            val first = firstTimestampNs ?: return false
            return (latestTimestampNs - first) / 1_000_000L >= windowMs
        }

    /** Pushes one value and returns the mean over the current window. */
    fun push(timestampNs: Long, value: Float): Float {
        if (firstTimestampNs == null) firstTimestampNs = timestampNs
        latestTimestampNs = timestampNs
        timestamps.addLast(timestampNs)
        values.addLast(value)
        sum += value

        val cutoffNs = timestampNs - windowMs * 1_000_000L
        while (timestamps.isNotEmpty() && timestamps.first() <= cutoffNs) {
            timestamps.removeFirst()
            sum -= values.removeFirst()
        }
        return (sum / values.size).toFloat()
    }

    fun reset() {
        timestamps.clear()
        values.clear()
        sum = 0.0
        firstTimestampNs = null
        latestTimestampNs = 0L
    }
}
