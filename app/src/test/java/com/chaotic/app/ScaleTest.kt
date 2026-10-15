package com.aitken.app

import com.aitken.sensor.AccelerometerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleTest {

    @Test
    fun `M scale matches the Workbench prototype - M0 at the floor, M6 at the anchor, capped at M10`() {
        assertEquals(0f, MScale.peakToM(5f), 1e-6f)
        assertEquals(0f, MScale.peakToM(10f), 1e-6f)
        assertEquals(6f, MScale.peakToM(60f), 1e-4f)
        assertEquals(10f, MScale.peakToM(1000f), 1e-6f)
        assertEquals(0f, MScale.peakToM(Float.NaN), 1e-6f)
    }

    @Test
    fun `max M for a sensor is the M of its upward ceiling deviation`() {
        // 8g sensor (78.4532): up ceiling 78.4532 - 9.81 = 68.6432 -> 6*ln(6.86432)/ln(6) = 6.4507
        val eightG = AccelerometerInfo("BMI160", "BOSCH", 78.4532f, 0.0023f)
        assertEquals(68.6432f, eightG.ceilingDeviationUpMs2, 1e-3f)
        assertEquals(88.2632f, eightG.ceilingDeviationDownMs2, 1e-3f)
        assertEquals(6.4507f, MScale.peakToM(eightG.ceilingDeviationUpMs2), 1e-3f)
        // 4g sensor (39.2266): 29.4166 -> 3.6131
        val fourG = AccelerometerInfo("X", "Y", 39.2266f, 0.01f)
        assertEquals(3.6131f, MScale.peakToM(fourG.ceilingDeviationUpMs2), 1e-3f)
    }

    @Test
    fun `clipping is any axis at the saturation limit`() {
        val s = AccelerometerInfo("n", "v", 78.4532f, 0.01f)
        assertTrue(s.isClipped(0f, 0f, 78.4532f))
        assertTrue(s.isClipped(-78.45f, 1f, 1f))      // within 0.1% of the limit, negative side
        assertFalse(s.isClipped(10f, 20f, 77f))
    }

    @Test
    fun `graph axis is zero at baseline, symmetric, and puts the larger sensor ceiling at 85 percent of half-height`() {
        val scale = GraphScale(AccelerometerInfo("n", "v", 78.4532f, 0.01f))
        assertEquals(0f, scale.fraction(0f), 1e-6f)
        assertEquals(-scale.fraction(5f), scale.fraction(-5f), 1e-6f)
        // down ceiling (88.2632) is the larger one -> exactly 0.85
        assertEquals(0.85f, scale.ceilingFractionDown!!, 1e-4f)
        // up ceiling (68.6432): asinh(22.88/1)/... = 0.7976 -- sits lower, so the amber band above it is wider
        assertEquals(0.7976f, scale.ceilingFractionUp!!, 1e-3f)
        // ordinary road noise stays visible: 5 m/s^2 is about 27% of half-height, not a pixel
        assertEquals(0.2678f, scale.fraction(5f), 1e-3f)
        // anything beyond the axis clamps to the plot edge
        assertEquals(1f, scale.fraction(1_000_000f), 1e-6f)
    }

    @Test
    fun `with no sensor info there are no ceilings`() {
        val scale = GraphScale(null)
        assertNull(scale.ceilingFractionUp)
        assertNull(scale.ceilingFractionDown)
    }

    @Test
    fun `patch-distance slider is logarithmic from 1 m to 100 m`() {
        assertEquals(1f, LogScale.sliderToMetres(0f), 1e-4f)
        assertEquals(10f, LogScale.sliderToMetres(0.5f), 1e-3f)
        assertEquals(100f, LogScale.sliderToMetres(1f), 1e-2f)
        assertEquals(0.19897f, LogScale.metresToSlider(2.5f), 1e-4f)   // the 2.5 m default
        assertEquals(0f, LogScale.metresToSlider(0.2f), 1e-6f)          // clamped
    }

    @Test
    fun `snap gives 0_1 m steps under 10 m and whole metres above`() {
        assertEquals(2.5f, LogScale.snap(2.4573f), 1e-6f)
        assertEquals(17f, LogScale.snap(17.3f), 1e-6f)
        assertEquals(100f, LogScale.snap(99.6f), 1e-6f)
        assertEquals(1f, LogScale.snap(0.5f), 1e-6f)
    }
}
