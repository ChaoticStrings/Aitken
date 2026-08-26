package com.aitken.storage

/**
 * Persists the one-time SAF folder grant across app restarts (user story 23).
 * The real implementation backs onto SharedPreferences and takes the
 * persistable URI permission at grant time. BackupAgent and
 * ClassifierConfigLoader never touch this directly — they only see
 * [SafStorageAdapter].
 */
interface SafFolderGrant {

    /** The currently granted folder's tree URI, or null if none has been granted. */
    fun grantedUri(): String?

    /** Persist a newly granted tree URI, called once the picker returns a result. */
    fun persist(treeUriString: String)
}
