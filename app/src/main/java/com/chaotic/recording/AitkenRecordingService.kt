package com.aitken.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.aitken.location.AndroidGpsProvider
import com.aitken.sensor.AndroidSensorStream
import com.aitken.tagging.TagMatcher
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

// TODO [build]: manifest needs, in addition to ticket 02's ACCESS_FINE_LOCATION:
//   <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />
//   <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
//   <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
//   <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
//   <service
//       android:name="com.aitken.recording.AitkenRecordingService"
//       android:foregroundServiceType="location"
//       android:exported="false" />
// The service's android:name MUST be the fully-qualified class name, not a
// relative ".recording.AitkenRecordingService" shorthand — this repo's
// build namespace is com.chaotic.aitken (from app/build.gradle.kts) while
// this class's actual package declaration is com.aitken.recording; a
// relative name would resolve against the namespace and fail to find the
// class at runtime.

/**
 * Foreground service hosting a continuous recording session (ticket 10).
 * Declared as a `location`-type foreground service, per T4's research —
 * Android 14+ requires both the FGS type declaration and
 * ACCESS_BACKGROUND_LOCATION for continuous location access while the app
 * isn't visible. minSdk is 29 here, so the type-aware
 * `startForeground(id, notification, type)` overload (added in API 29) is
 * always available — no version branch needed.
 *
 * No network call exists anywhere on this path — [AndroidSensorStream],
 * [AndroidGpsProvider], and [SessionRecorder] are all local. The only
 * network-adjacent code in the whole app (SAF backup/config sync) lives in
 * `BackupAgent`/`ClassifierConfigLoader` (tickets 07/08), wired in
 * separately (ticket 12), never on this path — satisfies architecture
 * invariant 4 (no-network-required, scoped to Aitken's recording path).
 *
 * Turn suppression: yaw rate over [TURN_YAW_THRESHOLD_RAD_S] is treated as
 * "turning." That threshold is `[CALIBRATE]` — a placeholder, not derived
 * from any audited session, same as every other tunable in this pipeline.
 */
class AitkenRecordingService : Service() {

    private var pipeline: RecordingPipeline? = null
    private var sensorStream: AndroidSensorStream? = null
    private var gpsProvider: AndroidGpsProvider? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startSession()
        return START_STICKY
    }

    override fun onDestroy() {
        stopSession()
        super.onDestroy()
    }

    private fun startSession() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sessionDir = File(getExternalFilesDir(null) ?: filesDir, "session_$stamp")
        sessionDir.mkdirs()

        val recorder = SessionRecorder(sessionDir)
        val tagMatcher = TagMatcher()
        val newPipeline = RecordingPipeline(recorder, tagMatcher)
        pipeline = newPipeline

        val gps = AndroidGpsProvider(this)
        gpsProvider = gps
        gps.start { fix -> newPipeline.onGpsFix(fix) }

        val sensors = AndroidSensorStream(this)
        sensorStream = sensors
        sensors.start { sample ->
            val turning = abs(sample.gyroZ ?: 0f) >= TURN_YAW_THRESHOLD_RAD_S
            newPipeline.onSensorSample(sample, turning)
        }
    }

    private fun stopSession() {
        sensorStream?.stop()
        gpsProvider?.stop()
        pipeline?.endSession() // force-closes any open segment; never silently drops it
        pipeline = null
        sensorStream = null
        gpsProvider = null
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Aitken is recording")
            .setSmallIcon(android.R.drawable.ic_menu_compass) // TODO [build]: swap for a real launcher-derived icon
            .build()
    }

    private companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL_ID = "aitken_recording"
        const val TURN_YAW_THRESHOLD_RAD_S = 1.0f
    }
}
