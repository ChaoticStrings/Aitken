package com.aitken.location

/**
 * A single GPS fix.
 *
 * Pure Kotlin, no Android imports — same discipline as the DSP modules.
 */
data class GpsFix(
    val timestampNs: Long,
    val latitude: Double,
    val longitude: Double,
    val speedMps: Float?,
    val accuracyMeters: Float?
)

/**
 * Location access seam (architecture invariant 1, T2). Business logic depends
 * only on this interface, never on FusedLocationProviderClient directly, so a
 * Play-Services-free adapter is a swap rather than a rewrite.
 */
interface GpsProvider {

    /** The most recent fix received, or null if none has arrived yet. */
    fun currentFix(): GpsFix?

    /**
     * Begin streaming fixes.
     *
     * @param onFix called once per fix.
     * @return true if updates could be requested (e.g. permission granted).
     */
    fun start(onFix: (GpsFix) -> Unit): Boolean

    /** Stop streaming and release any registrations. */
    fun stop()
}
