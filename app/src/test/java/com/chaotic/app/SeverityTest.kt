package com.aitken.app

import org.junit.Assert.assertEquals
import org.junit.Test

class SeverityTest {

    private val tunables = Tunables() // defaults: mild<5, moderate<15 (deviation from ~9.81 baseline)

    @Test
    fun `a peak close to the gravity baseline is mild`() {
        // |12 - 9.81| = 2.19 < 5
        assertEquals(Severity.MILD, severityOf(peakM = 12f, tunables = tunables))
    }

    @Test
    fun `a moderate deviation from baseline`() {
        // |20 - 9.81| = 10.19; 5 <= 10.19 < 15
        assertEquals(Severity.MODERATE, severityOf(peakM = 20f, tunables = tunables))
    }

    @Test
    fun `a large deviation from baseline is severe`() {
        // |30 - 9.81| = 20.19 >= 15
        assertEquals(Severity.SEVERE, severityOf(peakM = 30f, tunables = tunables))
    }

    @Test
    fun `a near-weightless moment (low peakM) also registers as a real deviation`() {
        // peakM is always >= 0 by construction (SegmentDetector tracks abs(vertical)).
        // |0 - 9.81| = 9.81; 5 <= 9.81 < 15 -- MODERATE, not MILD, even though peakM itself is small.
        assertEquals(Severity.MODERATE, severityOf(peakM = 0f, tunables = tunables))
    }

    @Test
    fun `thresholds are read from the injected Tunables, not hardcoded`() {
        val custom = Tunables(mildSeverityDeviation = 1f, moderateSeverityDeviation = 2f)
        // |12 - 9.81| = 2.19 -- SEVERE under these custom (tight) thresholds,
        // even though the same value was MILD under defaults above.
        assertEquals(Severity.SEVERE, severityOf(peakM = 12f, tunables = custom))
    }
}
