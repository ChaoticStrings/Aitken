package com.aitken.storage

/**
 * Test double for SafStorageAdapter. Paths are flat string keys ("/" as the
 * separator, no leading slash); [list] infers directories from any key that
 * has further path segments beneath the requested prefix — no real
 * filesystem or Android dependency involved.
 */
class FakeSafStorageAdapter(private var granted: Boolean = true) : SafStorageAdapter {

    private val files = mutableMapOf<String, ByteArray>()

    fun setGranted(value: Boolean) {
        granted = value
    }

    override fun isGranted(): Boolean = granted

    override fun list(path: String): List<StorageEntry> {
        val prefix = if (path.isEmpty()) "" else "$path/"
        val direct = linkedMapOf<String, Boolean>() // name -> isDirectory

        for (fullPath in files.keys) {
            if (!fullPath.startsWith(prefix)) continue
            val remainder = fullPath.removePrefix(prefix)
            if (remainder.isEmpty()) continue

            val slash = remainder.indexOf('/')
            if (slash == -1) {
                direct[remainder] = false
            } else {
                direct.putIfAbsent(remainder.substring(0, slash), true)
            }
        }

        return direct.map { (name, isDir) -> StorageEntry("$prefix$name", isDir) }
    }

    override fun write(path: String, bytes: ByteArray) {
        files[path] = bytes
    }

    override fun read(path: String): ByteArray? = files[path]

    override fun delete(path: String) {
        files.remove(path)
    }
}
