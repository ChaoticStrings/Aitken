package com.aitken.segment

/** What first qualified a chain of roughness as a rough patch. */
enum class PatchTrigger {
    /** One segment (plus any roughness around it) that itself spanned >= the patch distance. */
    LENGTH,

    /** Two or more distinct segments within the patch distance of each other. */
    CLUSTER,

    /** Sliding-window roughness above the D threshold for >= the patch distance, no segment needed. */
    ROUGHNESS
}

/** A finished, registered rough patch. Distances are along the odometer. */
data class RoughPatch(
    val startNs: Long,
    val endNs: Long,
    val lengthM: Float,
    val segmentCount: Int,
    val peakM: Float,
    val maxD: Float,
    val trigger: PatchTrigger
)

sealed class PatchEvent {
    /** A chain has just qualified as a rough patch (still in progress). */
    data class Opened(val startNs: Long) : PatchEvent()

    /** A registered rough patch has ended. */
    data class Closed(val patch: RoughPatch) : PatchEvent()
}

/**
 * Distance-based rough-patch detection: replaces tapping START/END on a
 * "Rough stretch" button, and replaces any time-based notion of one.
 *
 * Works on a **chain of roughness**. A sample is *rough* if it is a
 * threshold breach of an open segment, or the sliding-window roughness (D)
 * is at/above [dThreshold]. Rough samples extend the current chain; a chain
 * survives gaps of up to [patchDistanceM] of *travelled distance* (not
 * time — the same road is the same road at any speed) and ends once the
 * vehicle has gone further than that without anything rough. While the
 * chain's own segment is still open (its quiet tail included) the chain is
 * held alive, but does not grow — otherwise a lone pothole would be
 * stretched by `endQuietMs x speed` and misread as a long patch.
 *
 * A chain is registered as a rough patch as soon as either holds:
 *  - it contains two or more distinct segments ([PatchTrigger.CLUSTER] —
 *    "two or more potholes within the set range"), or
 *  - it spans at least [patchDistanceM] ([PatchTrigger.LENGTH] if it holds
 *    one segment, [PatchTrigger.ROUGHNESS] if it holds none — gravel and
 *    washboard, rough but never tall enough to open a segment).
 *
 * A single short pothole is neither, and stays just a segment (see
 * [roughnessMemoryMs] for why that needs a guard).
 *
 * Known open problem, deliberately NOT solved here (see
 * `Documentation/Core Docs/ROUGH_PATCH_NOTES.md`): periodic features such
 * as concrete-slab joints can keep a chain, or the sliding window, alive
 * for kilometres. Pure Kotlin.
 */
class RoughPatchTracker(
    private val patchDistanceM: Float,
    private val dThreshold: Float,
    /**
     * Roughness (D) stops counting as rough for this long after the last
     * segment breach, and while a segment is open. The sliding window
     * *remembers* a big impact for its whole width, so at city speeds one
     * lone pothole would otherwise keep D above threshold for metres after
     * it and register as a patch all by itself — replay of the real 90 s
     * fixture turned single potholes into 7-18 m "patches" that way. Pass the
     * sliding-window width; 0 disables the guard. What remains for D to
     * catch is roughness with no segment-sized event behind it: gravel,
     * washboard.
     */
    private val roughnessMemoryMs: Long = 0L
) {

    private var active = false
    private var registered = false
    private var trigger = PatchTrigger.LENGTH
    private var startNs = 0L
    private var startM = 0.0
    private var endNs = 0L
    private var endM = 0.0
    private var segmentCount = 0
    private var lastSegmentStartNs: Long? = null
    private var peakM = 0f
    private var maxD = 0f
    private var lastBreachNs: Long? = null

    /** True while a *registered* patch is in progress. */
    val isPatchActive: Boolean get() = active && registered

    /**
     * @param odometerM cumulative distance (see [Odometer]).
     * @param deviation `abs(vertical - gravity)` for this sample.
     * @param breach true if this sample is a threshold breach inside an open segment.
     * @param openSegmentStartNs start of the currently open segment, null if none.
     * @param roughness sliding-window mean of [deviation], or null until the window has filled.
     */
    fun onSample(
        timestampNs: Long,
        odometerM: Double,
        deviation: Float,
        breach: Boolean,
        openSegmentStartNs: Long?,
        roughness: Float?
    ): List<PatchEvent> {
        val events = ArrayList<PatchEvent>(0)

        if (breach) lastBreachNs = timestampNs
        val lastBreach = lastBreachNs
        val inSegmentMemory = roughnessMemoryMs > 0L && (
            openSegmentStartNs != null ||
                (lastBreach != null && (timestampNs - lastBreach) / 1_000_000L <= roughnessMemoryMs)
            )
        val dActive = roughness != null && roughness >= dThreshold && !inSegmentMemory
        val rough = breach || dActive

        // A chain is kept alive past the distance limit only by its own open segment.
        val heldByOwnSegment = openSegmentStartNs != null && openSegmentStartNs == lastSegmentStartNs
        if (active && !heldByOwnSegment && odometerM - endM > patchDistanceM) {
            finalizeChain()?.let { events.add(it) }
        }

        if (rough) {
            if (!active) {
                active = true
                registered = false
                startNs = timestampNs
                startM = odometerM
                segmentCount = 0
                lastSegmentStartNs = null
                peakM = 0f
                maxD = 0f
            }
            endNs = timestampNs
            endM = odometerM
            if (deviation > peakM) peakM = deviation
            if (roughness != null && roughness > maxD) maxD = roughness
            if (breach && openSegmentStartNs != null && openSegmentStartNs != lastSegmentStartNs) {
                segmentCount++
                lastSegmentStartNs = openSegmentStartNs
            }

            if (!registered) {
                val length = endM - startM
                val qualified = when {
                    segmentCount >= 2 -> PatchTrigger.CLUSTER
                    length >= patchDistanceM -> if (segmentCount == 0) PatchTrigger.ROUGHNESS else PatchTrigger.LENGTH
                    else -> null
                }
                if (qualified != null) {
                    registered = true
                    trigger = qualified
                    events.add(PatchEvent.Opened(startNs))
                }
            }
        }
        return events
    }

    /** Ends the session: closes any chain still in progress (if it had qualified). */
    fun endSession(): PatchEvent.Closed? = finalizeChain()

    private fun finalizeChain(): PatchEvent.Closed? {
        if (!active) return null
        active = false
        if (!registered) return null
        registered = false
        return PatchEvent.Closed(
            RoughPatch(
                startNs = startNs,
                endNs = endNs,
                lengthM = (endM - startM).toFloat(),
                segmentCount = segmentCount,
                peakM = peakM,
                maxD = maxD,
                trigger = trigger
            )
        )
    }
}
