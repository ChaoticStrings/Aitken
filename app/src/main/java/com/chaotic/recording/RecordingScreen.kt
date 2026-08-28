package com.aitken.recording

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Minimal session start/stop toggle — ticket 10's slice of Prototype 1's
 * `DebugScreen`. The waveform canvas and tap buttons are ticket 11's
 * concern; this just starts/stops [AitkenRecordingService] via Intent. The
 * service owns the actual recording pipeline.
 */
@Composable
fun RecordingScreen() {
    val context = LocalContext.current
    var recording by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(if (recording) "Recording..." else "Not recording")
        Button(onClick = {
            val intent = Intent(context, AitkenRecordingService::class.java)
            if (recording) {
                context.stopService(intent)
            } else {
                context.startForegroundService(intent)
            }
            recording = !recording
        }) {
            Text(if (recording) "STOP SESSION" else "START SESSION")
        }
    }
}
