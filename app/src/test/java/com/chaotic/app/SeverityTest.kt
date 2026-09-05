package com.aitken.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SeverityTest {

    private val tunables = Tunables() // defaults: mild<5, moderate<15 (deviation from ~9.81 baseline)

    @Test
    fun `a peak close to the gravity baseline is mild`() {
        // peakM is already a gravity-relative deviation (SegmentDetector.accumulate()
        // computes abs(vertical - 9.81) directly, per ride-data-analysis-update.md §5) --
        // severityOf buckets it as-is, no further subtraction.
        assertEquals(Severity.MILD, severityOf(peakM = 2f, tunables = tunables))
    }

    @Test
    fun `a moderate deviation from baseline`() {
        // 5 <= 10 < 15
        assertEquals(Severity.MODERATE, severityOf(peakM = 10f, tunables = tunables))
    }

    @Test
    fun `a large deviation from baseline is severe`() {
        // 20 >= 15
        assertEquals(Severity.SEVERE, severityOf(peakM = 20f, tunables = tunables))
    }

    @Test
    fun `zero deviation is mild`() {
        // A peakM of exactly 0 means the segment's worst sample sat exactly
        // at the gravity baseline -- genuinely no deviation, so MILD is
        // correct. (Pre-fix, peakM was a raw abs(vertical) magnitude and 0f
        // here actually meant "vertical == 0", i.e. free-fall -- a real
        // 9.81 deviation the old re-subtraction happened to catch, but only
        // as a side effect of the same bug §5 removes. That scenario no
        // longer arises this way: SegmentDetector now reports free-fall
        // directly as peakM ~= 9.81.)
        assertEquals(Severity.MILD, severityOf(peakM = 0f, tunables = tunables))
    }

    @Test
    fun `thresholds are read from the injected Tunables, not hardcoded`() {
        val custom = Tunables(mildSeverityDeviation = 1f, moderateSeverityDeviation = 2f)
        // 12 -- SEVERE under these custom (tight) thresholds.
        assertEquals(Severity.SEVERE, severityOf(peakM = 12f, tunables = custom))
    }
}
