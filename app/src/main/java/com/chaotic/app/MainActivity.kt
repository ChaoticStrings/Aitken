package com.aitken.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.LaunchedEffect

/**
 * Aitken's only Activity — hosts [AitkenSessionScreen] and handles the
 * runtime permission requests nothing else in the app was asking for
 * (previously: `AndroidGpsProvider.start()` would just silently return
 * false with no permission ever granted, since nothing ever prompted).
 *
 * Portrait lock is set in the manifest
 * (`android:screenOrientation="portrait"`), not here. Dark theme is forced
 * via `darkColorScheme()` regardless of system theme setting — a bright
 * screen at night actively hurts a rider's night vision, so this isn't
 * optional/aesthetic the way it might be in a typical app.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                // Background location can only be requested after foreground
                // location is already granted (Android's staged permission
                // model) -- declared first so the launcher below can
                // reference it; Kotlin doesn't allow forward-referencing a
                // local val from an earlier lambda in the same scope.
                val backgroundLocationLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) {
                    // No-op either way: if denied, recording still works
                    // while the app is in the foreground, it just won't
                    // reliably continue with the screen off. Nothing more
                    // to do here than let the rider find that out and grant
                    // it later via system settings if they want screen-off
                    // recording.
                }

                val corePermissions = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { results ->
                    if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ) {
                        backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }

                LaunchedEffect(Unit) {
                    val toRequest = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        toRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    corePermissions.launch(toRequest.toTypedArray())
                }

                AitkenSessionScreen()
            }
        }
    }
}
