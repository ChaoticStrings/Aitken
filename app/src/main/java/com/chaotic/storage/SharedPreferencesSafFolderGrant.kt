package com.aitken.storage

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Production SafFolderGrant. Persists the granted tree URI in
 * SharedPreferences and takes the persistable read/write URI permission at
 * grant time, so the grant survives a reboot, not just an app restart.
 */
class SharedPreferencesSafFolderGrant(private val context: Context) : SafFolderGrant {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun grantedUri(): String? = prefs.getString(KEY_URI, null)

    override fun persist(treeUriString: String) {
        val uri = Uri.parse(treeUriString)
        context.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString(KEY_URI, treeUriString).apply()
    }

    private companion object {
        const val PREFS_NAME = "aitken_storage"
        const val KEY_URI = "granted_tree_uri"
    }
}
