package com.aitken.storage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * One-time folder grant (user story 23). Aitken never re-prompts once
 * [grant] already reports a URI; BackupAgent (08) and ClassifierConfigLoader
 * (07) both read whatever folder was granted here — this screen only ever
 * writes through [SafFolderGrant], never touches [SafStorageAdapter] itself.
 */
@Composable
fun StorageGrantScreen(grant: SafFolderGrant) {
    var grantedUri by remember { mutableStateOf(grant.grantedUri()) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            grant.persist(uri.toString())
            grantedUri = uri.toString()
        }
    }

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (grantedUri != null) "Backup folder granted" else "No backup folder granted yet")
        Button(onClick = { picker.launch(null) }) {
            Text(if (grantedUri != null) "Change folder" else "Grant folder")
        }
    }
}
