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
import com.aitken.backup.BackupAgent
import com.aitken.classifier.ClassifierConfigLoader
import com.aitken.location.AndroidGpsProvider
import com.aitken.segment.NoiseFloorCalibrator
import com.aitken.sensor.AndroidSensorStream
import com.aitken.storage.AndroidSafStorageAdapter
import com.aitken.storage.SharedPreferencesSafFolderGrant
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
 * `BackupAgent`/`ClassifierConfigLoader` (tickets 07/08). Both are wired in
 * here (ticket 12) but never on the sensor/GPS/recording path itself: a
 * closed session's backup and the opportunistic config check each run on
 * their own throwaway [Thread], started only after [startSession] has
 * already begun (config check) or [RecordingPipeline.endSession] has
 * already flushed and closed the session's files (backup) — satisfies
 * architecture invariant 4 (no-network-required, scoped to Aitken's
 * recording path) and `BackupAgent`/`ClassifierConfigLoader`'s own
 * documented "never blocks or gates recording" guarantee.
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
    private var backupAgent: BackupAgent? = null
    private var sessionDir: File? = null

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
        val dir = File(getExternalFilesDir(null) ?: filesDir, "session_$stamp")
        dir.mkdirs()
        sessionDir = dir

        val storage = AndroidSafStorageAdapter(this, SharedPreferencesSafFolderGrant(this))
        backupAgent = BackupAgent(storage)
        // Opportunistic, off the recording path (ticket 12) -- best-effort;
        // nothing reads the result yet (that's ticket 09's job), this just
        // keeps the cache warm for when something does. A missing grant or
        // stale/never-synced config never gates this or any other session,
        // per ClassifierConfigLoader's own "always falls back to cache"
        // guarantee -- nothing here waits on this thread.
        Thread { ClassifierConfigLoader(storage).checkForUpdate() }.start()

        val recorder = SessionRecorder(dir)
        val tagMatcher = TagMatcher()
        val calibrator = NoiseFloorCalibrator(
            calibrationDurationMs = tunables.calibrationDurationMs,
            stdFactor = tunables.stdFactor,
            floorStd = tunables.floorStd
        )
        val newPipeline = RecordingPipeline(
            recorder = recorder,
            tagMatcher = tagMatcher,
            onCalibrationDone = { shortStdThreshold, longStdThreshold ->
                AitkenUiState.phaseLabel.value = "RECORDING"
                AitkenUiState.calibratedThresholdM.value = shortStdThreshold
                // ride-data-analysis-update.md §4: record which Tunables were
                // active and what they actually calibrated to, so a session's
                // detection behavior is reconstructable from its own files
                // afterward instead of requiring a forensic replay.
                recorder.writeConfig(tunables, shortStdThreshold, longStdThreshold)
            },
            calibrator = calibrator,
            endQuietMs = tunables.endQuietMs,
            minSegmentDurationMs = tunables.minSegmentDurationMs,
            onLiveVertical = { vertical -> AitkenUiState.pushSample(vertical) },
            onSegmentClosedForUi = { segment -> AitkenUiState.pushSegment(segment) },
            onCalibrationProgress = { fraction -> AitkenUiState.calibrationProgress.value = fraction },
            // Live-editable mid-ride (ticket 21) -- re-read on every tap rather than
            // snapshotted once like the rest of `tunables` above.
            tagDebounceMs = { SettingsStore.load(this).tagDebounceMs }
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
            // Recommended pipeline fix #5 (ride-data-analysis-update.md): surface
            // a still-open segment's live duration so the session screen can flag
            // it before it ever reaches the 56s/104s/311s territory the doc found.
            val open = newPipeline.currentOpenSegment()
            AitkenUiState.openSegmentDurationMs.value = open?.let { (it.lastSignalNs - it.startNs) / 1_000_000L }
        }
    }

    private fun stopSession() {
        sensorStream?.stop()
        gpsProvider?.stop()
        pipeline?.endSession() // force-closes any open segment, then flushes and closes the files
        pipeline = null
        sensorStream = null
        gpsProvider = null

        // Only after endSession() above has closed the files -- BackupAgent
        // reads them straight off disk. Best-effort and off the main thread
        // (ticket 12): a -1 (ungranted) or partial-copy result is accepted
        // silently here, same as BackupAgent's own documented behavior;
        // ticket 24 is what makes "ungranted" rare, not this call.
        val dir = sessionDir
        val agent = backupAgent
        if (dir != null && agent != null) {
            Thread { agent.enqueueBackup(dir) }.start()
        }
        sessionDir = null
        backupAgent = null

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
