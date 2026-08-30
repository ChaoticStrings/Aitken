package com.aitken.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import com.aitken.app.AitkenUiState
import com.aitken.app.SettingsStore
import com.aitken.app.Tunables
import com.aitken.location.AndroidGpsProvider
import com.aitken.segment.NoiseFloorCalibrator
import com.aitken.sensor.AndroidSensorStream
import com.aitken.tagging.TagKind
import com.aitken.tagging.TagMatch
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
 * Turn suppression uses [Tunables.turnYawThresholdRadS], loaded fresh from
 * [SettingsStore] at the start of every session — a rider can change it
 * (and every other tunable) in the settings screen between rides without
 * needing a rebuild. Live sensor values and closed segments are pushed to
 * [AitkenUiState] as they happen, so the session screen's M/D graph has
 * something to draw; this service never reads [AitkenUiState] itself, only
 * writes to it — a foreground service outliving the Activity's lifecycle
 * shouldn't depend on anything the UI layer owns.
 */
class AitkenRecordingService : Service() {

    private var pipeline: RecordingPipeline? = null
    private var sensorStream: AndroidSensorStream? = null
    private var gpsProvider: AndroidGpsProvider? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        startSession()
        return START_STICKY
    }

    override fun onDestroy() {
        stopSession()
        instance = null
        super.onDestroy()
    }

    private fun startSession() {
        AitkenUiState.reset()
        val tunables = SettingsStore.load(this)

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val sessionDir = File(getExternalFilesDir(null) ?: filesDir, "session_$stamp")
        sessionDir.mkdirs()

        val recorder = SessionRecorder(sessionDir)
        val tagMatcher = TagMatcher()
        val calibrator = NoiseFloorCalibrator(
            calibrationDurationMs = tunables.calibrationDurationMs,
            stdFactor = tunables.stdFactor,
            floorStd = tunables.floorStd
        )
        val newPipeline = RecordingPipeline(
            recorder = recorder,
            tagMatcher = tagMatcher,
            onCalibrationDone = { AitkenUiState.phaseLabel.value = "RECORDING" },
            calibrator = calibrator,
            endQuietMs = tunables.endQuietMs,
            minSegmentDurationMs = tunables.minSegmentDurationMs,
            onLiveVertical = { vertical -> AitkenUiState.pushSample(vertical) },
            onSegmentClosedForUi = { segment -> AitkenUiState.pushSegment(segment) }
        )
        pipeline = newPipeline
        AitkenUiState.phaseLabel.value = "CALIBRATING"
        AitkenUiState.isRecording.value = true

        val gps = AndroidGpsProvider(this)
        gpsProvider = gps
        gps.start { fix -> newPipeline.onGpsFix(fix) }

        val sensors = AndroidSensorStream(this)
        sensorStream = sensors
        sensors.start { sample ->
            val turning = abs(sample.gyroZ ?: 0f) >= tunables.turnYawThresholdRadS
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
        AitkenUiState.isRecording.value = false
        AitkenUiState.phaseLabel.value = "IDLE"
    }

    /** Exposes the manual-tagging call for the UI to invoke — see [RecordingPipeline.tag]. */
    fun tag(kind: TagKind, label: String) {
        val result = pipeline?.tag(kind, label)
        AitkenUiState.lastTagResult.value = when (result) {
            is TagMatch.Matched -> "$label matched (${result.tapOffsetMs}ms late)"
            is TagMatch.Unmatched -> "$label — no segment found"
            null -> "$label — no sensor data yet"
        }
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

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "aitken_recording"

        /**
         * Same-process reference the UI layer calls [tag] through. Not a
         * bound service / Binder / AIDL — this never crosses processes, so
         * a plain nullable static reference is the simplest thing that
         * works, set in [onCreate] and cleared in [onDestroy]. Null means
         * no session is currently running; callers should treat that the
         * same way [tag] treats "no sensor data yet."
         */
        var instance: AitkenRecordingService? = null
            private set
    }
}
