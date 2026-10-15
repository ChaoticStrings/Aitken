package com.aitken.app

import android.content.Context
import com.aitken.segment.CalibrationPreset
import com.aitken.segment.CalibrationPresetCodec

/**
 * Persists saved calibration presets and which one (if any) the next session
 * should start with. A thin SharedPreferences shell over the pure, tested
 * [CalibrationPresetCodec] — same split as [SettingsStore] / [Tunables].
 */
object PresetStore {

    private const val PREFS = "aitken_presets"
    private const val KEY_LIST = "presets"
    private const val KEY_SELECTED = "selectedPresetId"

    fun load(context: Context): List<CalibrationPreset> {
        val text = prefs(context).getString(KEY_LIST, "") ?: ""
        return CalibrationPresetCodec.decode(text)
    }

    fun add(context: Context, preset: CalibrationPreset) {
        save(context, load(context) + preset)
    }

    fun delete(context: Context, id: String) {
        save(context, load(context).filterNot { it.id == id })
        if (selectedId(context) == id) select(context, null)
    }

    /** Id of the preset the next session will use, or null for a fresh calibration. */
    fun selectedId(context: Context): String? = prefs(context).getString(KEY_SELECTED, null)

    fun select(context: Context, id: String?) {
        prefs(context).edit().apply {
            if (id == null) remove(KEY_SELECTED) else putString(KEY_SELECTED, id)
        }.apply()
    }

    /** The selected preset, or null if none is selected or it no longer exists. */
    fun selected(context: Context): CalibrationPreset? {
        val id = selectedId(context) ?: return null
        return load(context).firstOrNull { it.id == id }
    }

    private fun save(context: Context, presets: List<CalibrationPreset>) {
        prefs(context).edit().putString(KEY_LIST, CalibrationPresetCodec.encode(presets)).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
