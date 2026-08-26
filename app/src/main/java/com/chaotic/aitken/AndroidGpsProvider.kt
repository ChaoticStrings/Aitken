package com.aitken.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

// TODO [CALIBRATE build]: this file needs
//   implementation("com.google.android.gms:play-services-location:<version>")
// added to app/build.gradle.kts before it will compile. Left unpinned
// deliberately — resolve the current version locally (Android Studio's
// dependency suggestion, or `./gradlew :app:dependencies`) rather than trust a
// guessed version here; a wrong pin breaks the build silently. The interface,
// fake, and their tests in this package don't depend on this file and are
// unaffected either way.

/**
 * Production GpsProvider backed by FusedLocationProviderClient.
 *
 * Requests high-accuracy updates at a 1s interval — the recording pipeline
 * only needs a fix coarse enough to attach a speed reading to each closed
 * segment (SessionRecorder, ticket 05), not a dense standalone GPS track.
 */
class AndroidGpsProvider(private val context: Context) : GpsProvider {

    private val client: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    @Volatile
    private var latest: GpsFix? = null

    private var callback: LocationCallback? = null

    override fun currentFix(): GpsFix? = latest

    @SuppressLint("MissingPermission")
    override fun start(onFix: (GpsFix) -> Unit): Boolean {
        if (!hasLocationPermission()) return false

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1_000L).build()

        val locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val fix = location.toGpsFix()
                latest = fix
                onFix(fix)
            }
        }

        client.requestLocationUpdates(request, locationCallback, context.mainLooper)
        callback = locationCallback
        return true
    }

    override fun stop() {
        callback?.let { client.removeLocationUpdates(it) }
        callback = null
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun Location.toGpsFix(): GpsFix = GpsFix(
        timestampNs = elapsedRealtimeNanos,
        latitude = latitude,
        longitude = longitude,
        speedMps = if (hasSpeed()) speed else null,
        accuracyMeters = if (hasAccuracy()) accuracy else null
    )
}
