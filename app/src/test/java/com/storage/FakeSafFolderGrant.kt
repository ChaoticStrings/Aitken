package com.aitken.storage

/** Test double for SafFolderGrant — an in-memory var, no SharedPreferences. */
class FakeSafFolderGrant : SafFolderGrant {

    private var uri: String? = null

    override fun grantedUri(): String? = uri

    override fun persist(treeUriString: String) {
        uri = treeUriString
    }
}
