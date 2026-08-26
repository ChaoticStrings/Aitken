package com.aitken.storage

/** A single entry (file or directory) within the granted folder tree. */
data class StorageEntry(
    val path: String,
    val isDirectory: Boolean
)

/**
 * Seam over a granted Storage Access Framework folder tree (architecture
 * invariant 1, T2). BackupAgent (ticket 08) and ClassifierConfigLoader
 * (ticket 07) both sit on top of this single adapter rather than each
 * independently wrapping SAF.
 */
interface SafStorageAdapter {

    /** True once a folder has been granted and the grant is still usable. */
    fun isGranted(): Boolean

    /** Entries directly under [path] within the granted folder ("" = root). */
    fun list(path: String = ""): List<StorageEntry>

    /** Write [bytes] to [path], creating any missing parent directories. */
    fun write(path: String, bytes: ByteArray)

    /** Read the bytes at [path], or null if it doesn't exist. */
    fun read(path: String): ByteArray?

    /** Delete the entry at [path], if it exists. */
    fun delete(path: String)
}
