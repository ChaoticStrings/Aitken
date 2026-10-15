package com.aitken.segment

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationPresetCodecTest {

    @Test
    fun `presets round-trip through encode and decode`() {
        val a = CalibrationPreset("1", "Smooth highway", 4.5f, 1.8f, 1000L, 1_800_000_000_000L)
        val b = CalibrationPreset("2", "City, rattly mount", 6.25f, 2.5f, 2000L, 1_800_000_500_000L)
        assertEquals(listOf(a, b), CalibrationPresetCodec.decode(CalibrationPresetCodec.encode(listOf(a, b))))
    }

    @Test
    fun `tabs and newlines in a name are flattened, not allowed to corrupt the record`() {
        val p = CalibrationPreset("1", "bad\tname\nhere", 1f, 1f, 1000L, 5L)
        val decoded = CalibrationPresetCodec.decode(CalibrationPresetCodec.encode(listOf(p)))
        assertEquals(1, decoded.size)
        assertEquals("bad name here", decoded[0].name)
    }

    @Test
    fun `a malformed line is skipped without discarding the rest`() {
        val good = CalibrationPreset("1", "ok", 2f, 1f, 1000L, 5L)
        val text = CalibrationPresetCodec.encode(listOf(good)) + "\ngarbage line\n2\tx\tnotanumber\t1\t1000\t5"
        assertEquals(listOf(good), CalibrationPresetCodec.decode(text))
    }

    @Test
    fun `empty storage decodes to no presets`() {
        assertTrue(CalibrationPresetCodec.decode("").isEmpty())
    }
}
