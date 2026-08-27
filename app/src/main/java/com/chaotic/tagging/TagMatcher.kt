package com.aitken.tagging

import com.aitken.segment.ClosedSegment
import com.aitken.segment.OpenSegment

/** Distinguishes a single-point tap from either end of a range annotation (user story 5). */
enum class TagKind { POINT, RANGE_START, RANGE_END }

/** The outcome of matching a manual tap against known segments. */
sealed class TagMatch {
    abstract val kind: TagKind

    data class Matched(
        override val kind: TagKind,
        val segmentStartNs: Long,
        val tapOffsetMs: Long
    ) : TagMatch()

    /** Not dropped — surfaced for Workbench reconciliation (ticket 17). */
    data class Unmatched(
        override val kind: TagKind,
        val tapTimestampNs: Long
    ) : TagMatch()
}

/**
 * Retroactively matches a manual tap to the segment that actually caused
 * it, via backward lookback — a rider taps a few seconds after actually
 * hitting a pothole, and the tag needs to land on the earlier segment, not
 * the moment of the tap itself.
 *
 * Checks the currently-open segment first — no lookback constraint applies
 * there, since a segment that's still open is almost certainly the one
 * being tagged regardless of how late the tap reads. Only when nothing is
 * open does it search recently-closed segments within [tagLookbackMs],
 * most recent first.
 *
 * `tap_offset_ms` is defined consistently for both cases as milliseconds
 * since the segment's last signal-true sample (`OpenSegment.lastSignalNs`
 * for an open match; for a closed one, `startNs + durationNs` — which by
 * construction in SegmentDetector *is* that same lastSignalNs, since
 * duration is measured to it).
 *
 * TagMatcher keeps its own small history of recently-closed segments,
 * since SegmentDetector doesn't retain a segment after emitting it. Callers
 * must feed every `ClosedSegment` SegmentDetector emits into
 * [onSegmentClosed] as it happens, or lookback has nothing to search.
 * History is trimmed lazily on each [match] call using the tap's own
 * timestamp as "now" — deliberately not bounded any more aggressively than
 * that, since real segment counts per ride are sparse relative to sensor
 * rate and don't need it.
 */
class TagMatcher(
    private val tagLookbackMs: Long = 8_000L // [CALIBRATE]
) {

    private val recentlyClosedSegments = ArrayDeque<ClosedSegment>()

    /** Feed every segment SegmentDetector closes, so lookback has history to search. */
    fun onSegmentClosed(segment: ClosedSegment) {
        recentlyClosedSegments.addLast(segment)
    }

    /**
     * Match a manual tap.
     *
     * @param kind whether this is a point tap or one end of a range
     * annotation — carried through to the result, not used in matching
     * logic itself (the "which segment" question is identical either way).
     * @param openSegment the detector's currently-open segment, or null.
     */
    fun match(tapTimestampNs: Long, kind: TagKind, openSegment: OpenSegment?): TagMatch {
        if (openSegment != null) {
            val tapOffsetMs = (tapTimestampNs - openSegment.lastSignalNs) / 1_000_000L
            return TagMatch.Matched(kind, openSegment.startNs, tapOffsetMs)
        }

        trimExpired(tapTimestampNs)

        for (segment in recentlyClosedSegments.asReversed()) {
            val lastSignalNs = segment.startNs + segment.durationNs
            val tapOffsetMs = (tapTimestampNs - lastSignalNs) / 1_000_000L
            if (tapOffsetMs in 0..tagLookbackMs) {
                return TagMatch.Matched(kind, segment.startNs, tapOffsetMs)
            }
        }

        return TagMatch.Unmatched(kind, tapTimestampNs)
    }

    private fun trimExpired(nowNs: Long) {
        while (recentlyClosedSegments.isNotEmpty()) {
            val oldest = recentlyClosedSegments.first()
            val lastSignalNs = oldest.startNs + oldest.durationNs
            val ageMs = (nowNs - lastSignalNs) / 1_000_000L
            if (ageMs > tagLookbackMs) recentlyClosedSegments.removeFirst() else break
        }
    }
}
