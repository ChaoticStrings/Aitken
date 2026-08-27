package com.aitken.tagging

import com.aitken.segment.ClosedSegment
import com.aitken.segment.OpenSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TagMatcherTest {

    private val ms = 1_000_000L

    @Test
    fun `a tap while a segment is open matches that segment, regardless of lookback window`() {
        val matcher = TagMatcher(tagLookbackMs = 500L)
        val open = OpenSegment(startNs = 1000 * ms, lastSignalNs = 1200 * ms, peakM = 5f, rmsM = 3f)

        // Tap arrives well past the lookback window, but the segment is
        // still open, so lookback doesn't even come into play.
        val result = matcher.match(tapTimestampNs = 3000 * ms, kind = TagKind.POINT, openSegment = open)

        assertEquals(
            TagMatch.Matched(TagKind.POINT, segmentStartNs = 1000 * ms, tapOffsetMs = 1800L),
            result
        )
    }

    @Test
    fun `a tap shortly after a segment closes matches it via lookback`() {
        val matcher = TagMatcher(tagLookbackMs = 500L)
        val segment = ClosedSegment(startNs = 1000 * ms, durationNs = 200 * ms, peakM = 5f, rmsM = 3f)
        matcher.onSegmentClosed(segment) // lastSignalNs = 1000+200 = 1200ms

        val result = matcher.match(tapTimestampNs = 1350 * ms, kind = TagKind.POINT, openSegment = null)

        assertEquals(
            TagMatch.Matched(TagKind.POINT, segmentStartNs = 1000 * ms, tapOffsetMs = 150L),
            result
        )
    }

    @Test
    fun `a tap arriving after the lookback window has elapsed is unmatched, not dropped`() {
        val matcher = TagMatcher(tagLookbackMs = 500L)
        val segment = ClosedSegment(startNs = 1000 * ms, durationNs = 200 * ms, peakM = 5f, rmsM = 3f)
        matcher.onSegmentClosed(segment) // lastSignalNs = 1200ms

        val result = matcher.match(tapTimestampNs = 1800 * ms, kind = TagKind.POINT, openSegment = null)

        assertEquals(TagMatch.Unmatched(TagKind.POINT, tapTimestampNs = 1800 * ms), result)
    }

    @Test
    fun `among multiple closed segments in the window, the most recent one wins`() {
        val matcher = TagMatcher(tagLookbackMs = 1000L)
        val segmentA = ClosedSegment(startNs = 1000 * ms, durationNs = 100 * ms, peakM = 1f, rmsM = 1f)
        val segmentB = ClosedSegment(startNs = 1300 * ms, durationNs = 100 * ms, peakM = 1f, rmsM = 1f)
        matcher.onSegmentClosed(segmentA) // lastSignalNs = 1100ms
        matcher.onSegmentClosed(segmentB) // lastSignalNs = 1400ms

        // 50ms after B, 350ms after A -- both technically in the 1000ms
        // window, but B is the intended match.
        val result = matcher.match(tapTimestampNs = 1450 * ms, kind = TagKind.POINT, openSegment = null)

        assertEquals(
            TagMatch.Matched(TagKind.POINT, segmentStartNs = 1300 * ms, tapOffsetMs = 50L),
            result
        )
    }

    @Test
    fun `an expired segment is trimmed from history and doesn't block matching a valid more-recent one`() {
        val matcher = TagMatcher(tagLookbackMs = 200L)
        val segmentA = ClosedSegment(startNs = 1000 * ms, durationNs = 100 * ms, peakM = 1f, rmsM = 1f)
        val segmentB = ClosedSegment(startNs = 2000 * ms, durationNs = 100 * ms, peakM = 1f, rmsM = 1f)
        matcher.onSegmentClosed(segmentA) // lastSignalNs = 1100ms -- will be long expired
        matcher.onSegmentClosed(segmentB) // lastSignalNs = 2100ms -- in-window

        val result = matcher.match(tapTimestampNs = 2150 * ms, kind = TagKind.POINT, openSegment = null)

        assertEquals(
            TagMatch.Matched(TagKind.POINT, segmentStartNs = 2000 * ms, tapOffsetMs = 50L),
            result
        )
    }

    @Test
    fun `range-tag start and end events are tracked with their own kind, independent of point taps`() {
        val matcher = TagMatcher()
        val open = OpenSegment(startNs = 1000 * ms, lastSignalNs = 1100 * ms, peakM = 5f, rmsM = 3f)

        val start = matcher.match(tapTimestampNs = 1150 * ms, kind = TagKind.RANGE_START, openSegment = open)
        val end = matcher.match(tapTimestampNs = 1200 * ms, kind = TagKind.RANGE_END, openSegment = open)

        assertTrue(start is TagMatch.Matched && start.kind == TagKind.RANGE_START)
        assertTrue(end is TagMatch.Matched && end.kind == TagKind.RANGE_END)
    }
}
