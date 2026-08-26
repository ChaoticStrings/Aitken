package com.aitken.storage

// TODO [build]: needs androidx.documentfile:documentfile:1.1.0 added to
//   app/build.gradle.kts before this file compiles. Chosen from a live check
//   of Google's current release notes (developer.android.com/jetpack/androidx/
//   releases/documentfile), not guessed — same discipline as the play-services
//   pin in AndroidGpsProvider.kt, just with a source checked this time so it
//   shouldn't need a round-trip. SafStorageAdapter, SafFolderGrant, their
//   fakes, and all fake-backed tests in this package are unaffected either
//   way.

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * Production SafStorageAdapter backed by DocumentFile over a granted SAF tree
 * URI. The root DocumentFile is re-resolved on every call rather than cached,
 * since the OS can revoke a grant at any time (e.g. removable storage pulled
 * out) — an adapter that trusted a cached handle could silently write into
 * nothing.
 */
class AndroidSafStorageAdapter(
    private val context: Context,
    private val grant: SafFolderGrant
) : SafStorageAdapter {

    private fun root(): DocumentFile? {
        val uriString = grant.grantedUri() ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uriString))
    }

    override fun isGranted(): Boolean = root()?.exists() == true

    override fun list(path: String): List<StorageEntry> {
        val dir = resolveDirectory(path) ?: return emptyList()
        return dir.listFiles().mapNotNull { entry ->
            val name = entry.name ?: return@mapNotNull null
            StorageEntry(
                path = if (path.isEmpty()) name else "$path/$name",
                isDirectory = entry.isDirectory
            )
        }
    }

    override fun write(path: String, bytes: ByteArray) {
        val parent = ensureDirectory(parentOf(path)) ?: return
        val fileName = nameOf(path)
        val target = parent.findFile(fileName)
            ?: parent.createFile("application/octet-stream", fileName)
            ?: return
        context.contentResolver.openOutputStream(target.uri, "w")?.use { it.write(bytes) }
    }

    override fun read(path: String): ByteArray? {
        val parent = resolveDirectory(parentOf(path)) ?: return null
        val file = parent.findFile(nameOf(path)) ?: return null
        return context.contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
    }

    override fun delete(path: String) {
        val parent = resolveDirectory(parentOf(path)) ?: return
        parent.findFile(nameOf(path))?.delete()
    }

    private fun parentOf(path: String) = path.substringBeforeLast('/', "")
    private fun nameOf(path: String) = path.substringAfterLast('/')

    private fun resolveDirectory(path: String): DocumentFile? {
        var current = root() ?: return null
        if (path.isEmpty()) return current
        for (segment in path.split('/')) {
            current = current.findFile(segment)?.takeIf { it.isDirectory } ?: return null
        }
        return current
    }

    private fun ensureDirectory(path: String): DocumentFile? {
        var current = root() ?: return null
        if (path.isEmpty()) return current
        for (segment in path.split('/')) {
            current = current.findFile(segment)?.takeIf { it.isDirectory }
                ?: current.createDirectory(segment)
                ?: return null
        }
        return current
    }
}
