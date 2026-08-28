package com.aitken.classifier

import com.aitken.storage.SafStorageAdapter

/**
 * A classifier config, as raw content plus when it was loaded. Content's
 * internal structure is deliberately not modeled here — ticket 09
 * (ClassifierRunner) and the eventual Colab notebook define what's
 * actually inside it, and per map.md that's still fog pending real ride
 * data ("Precise confidence-threshold mechanics for autonomous tagging...
 * stays fog until then"). This loader only owns fetching, caching, and
 * staleness visibility; it never parses or interprets content.
 */
data class ClassifierConfig(val content: ByteArray, val loadedAtMs: Long) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ClassifierConfig) return false
        return content.contentEquals(other.content) && loadedAtMs == other.loadedAtMs
    }

    override fun hashCode(): Int = 31 * content.contentHashCode() + loadedAtMs.hashCode()
}

/**
 * Non-blocking access to the current classifier config (architecture
 * invariant 4's T5 correction: non-blocking classifier auto-sync that
 * falls back to last-known-good config, never gating a recording session).
 *
 * [currentConfig] always returns instantly from cache — safe to call from
 * the recording path. [checkForUpdate] polls the SAF folder
 * opportunistically and is meant to be called only from outside that path
 * (wired in ticket 12). Any failure — no grant, missing file, empty
 * content — silently leaves the cached config untouched rather than
 * throwing: a stale or never-synced config degrades gracefully instead of
 * blocking or crashing a ride (user story 22).
 */
class ClassifierConfigLoader(
    private val storage: SafStorageAdapter,
    initialConfig: ClassifierConfig? = null,
    private val configPath: String = "classifier_config.json",
    private val now: () -> Long = { System.currentTimeMillis() }
) {

    @Volatile
    private var cached: ClassifierConfig? = initialConfig

    /** Always returns instantly — the cached config, or null if none has ever loaded. */
    fun currentConfig(): ClassifierConfig? = cached

    /** @return true if a new config was found and cached this call. */
    fun checkForUpdate(): Boolean {
        if (!storage.isGranted()) return false
        val bytes = storage.read(configPath) ?: return false
        if (bytes.isEmpty()) return false
        cached = ClassifierConfig(bytes, now())
        return true
    }
}
