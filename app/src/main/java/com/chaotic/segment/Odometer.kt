package com.aitken.segment

/**
 * Integrates distance travelled along the *sensor* timeline, so
 * distance-based logic (rough patches) shares one clock with everything
 * else in the pipeline instead of reconciling GPS time against sensor time.
 *
 * GPS speed arrives at roughly 1 Hz; between fixes the last known speed is
 * held flat (zero-order hold). The caller stamps each fix with the sensor
 * timestamp current *at the moment it arrived* — the same "reuse the last
 * sensor timestamp, never an independently-read clock" rule
 * [com.aitken.recording.RecordingPipeline] already applies to manual taps.
 *
 * A held speed older than [maxSpeedHoldMs] is treated as unknown and
 * contributes zero distance, rather than extrapolating a stale reading
 * through a long GPS outage (tunnel, lost fix). Pure Kotlin.
 */
class Odometer(private val maxSpeedHoldMs: Long = 10_000L) {

    private var speedMps: Float? = null
    private var speedAtNs: Long = 0L
    private var lastTimestampNs: Long? = null

    /** Cumulative distance in metres since construction. */
    var distanceM: Double = 0.0
        private set

    /** Record a GPS speed fix; [speedMps] null means "no speed in this fix". */
    fun onSpeed(atNs: Long, speedMps: Float?) {
        this.speedMps = speedMps
        this.speedAtNs = atNs
    }

    /** Advance to [timestampNs] (call once per sensor sample) and return total distance. */
    fun advance(timestampNs: Long): Double {
        val last = lastTimestampNs
        lastTimestampNs = timestampNs
        val speed = speedMps
        if (last != null && speed != null && timestampNs > last) {
            // The held speed only counts up to speedAt + maxSpeedHoldMs, even
            // across a long gap between sensor samples.
            val validUntilNs = speedAtNs + maxSpeedHoldMs * 1_000_000L
            val endNs = minOf(timestampNs, validUntilNs)
            if (endNs > last) distanceM += speed * ((endNs - last) / 1e9)
        }
        return distanceM
    }
}
