package com.aitken.segment

/**
 * A saved calibration: the two thresholds a fresh calibration derived, plus
 * the sliding-window width the D threshold was measured with (D is only
 * meaningful for that exact window, so it travels with the preset). Picking
 * one at session start skips the calibration phase.
 */
data class CalibrationPreset(
    val id: String,
    val name: String,
    val mThreshold: Float,
    val dThreshold: Float,
    val rollingWindowMs: Long,
    val createdEpochMs: Long
)

/**
 * One preset per line, tab-separated: `id, name, m, d, windowMs, created`.
 * Hand-rolled (not org.json, an Android-framework stub outside Robolectric)
 * so it stays testable on the plain JVM, same reasoning as
 * `SessionRecorder.writeConfig`. Tabs/newlines in names are flattened to
 * spaces on encode; a malformed line is skipped on decode rather than
 * discarding the whole list.
 */
object CalibrationPresetCodec {

    fun encode(presets: List<CalibrationPreset>): String =
        presets.joinToString("\n") { p ->
            listOf(
                p.id.clean(), p.name.clean(),
                p.mThreshold.toString(), p.dThreshold.toString(),
                p.rollingWindowMs.toString(), p.createdEpochMs.toString()
            ).joinToString("\t")
        }

    fun decode(text: String): List<CalibrationPreset> =
        text.lines().mapNotNull { line ->
            val f = line.split("\t")
            if (f.size != 6) return@mapNotNull null
            val m = f[2].toFloatOrNull() ?: return@mapNotNull null
            val d = f[3].toFloatOrNull() ?: return@mapNotNull null
            val w = f[4].toLongOrNull() ?: return@mapNotNull null
            val c = f[5].toLongOrNull() ?: return@mapNotNull null
            if (f[0].isEmpty()) return@mapNotNull null
            CalibrationPreset(f[0], f[1], m, d, w, c)
        }

    private fun String.clean(): String = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
}
