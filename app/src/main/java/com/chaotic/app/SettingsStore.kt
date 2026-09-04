package com.aitken.app

import android.content.Context

/** Persists [Tunables] across app restarts. Mirrors Prototype 1's `SettingsStore` pattern exactly. */
object SettingsStore {

    private const val PREFS = "aitken_settings"

    fun load(context: Context): Tunables {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val d = Tunables()
        return Tunables(
            calibrationDurationMs = p.getLong("calibrationDurationMs", d.calibrationDurationMs),
            stdFactor = p.getFloat("stdFactor", d.stdFactor),
            floorStd = p.getFloat("floorStd", d.floorStd),
            endQuietMs = p.getLong("endQuietMs", d.endQuietMs),
            minSegmentDurationMs = p.getLong("minSegmentDurationMs", d.minSegmentDurationMs),
            turnYawThresholdRadS = p.getFloat("turnYawThresholdRadS", d.turnYawThresholdRadS),
            mildSeverityDeviation = p.getFloat("mildSeverityDeviation", d.mildSeverityDeviation),
            moderateSeverityDeviation = p.getFloat("moderateSeverityDeviation", d.moderateSeverityDeviation),
            tagDebounceMs = p.getLong("tagDebounceMs", d.tagDebounceMs)
        )
    }

    fun save(context: Context, t: Tunables) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("calibrationDurationMs", t.calibrationDurationMs)
            .putFloat("stdFactor", t.stdFactor)
            .putFloat("floorStd", t.floorStd)
            .putLong("endQuietMs", t.endQuietMs)
            .putLong("minSegmentDurationMs", t.minSegmentDurationMs)
            .putFloat("turnYawThresholdRadS", t.turnYawThresholdRadS)
            .putFloat("mildSeverityDeviation", t.mildSeverityDeviation)
            .putFloat("moderateSeverityDeviation", t.moderateSeverityDeviation)
            .putLong("tagDebounceMs", t.tagDebounceMs)
            .apply()
    }
}
