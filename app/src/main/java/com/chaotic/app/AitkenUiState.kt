package com.aitken.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.aitken.recording.LiveSample
import com.aitken.segment.CalibrationResult
import com.aitken.segment.ClosedSegment
import com.aitken.sensor.AccelerometerInfo

/**
 * Shared, observable live-recording state, bridging [AitkenRecordingService]
 * (which owns the actual recording pipeline and runs its sensor callback on
 * a background thread) to the Compose UI (which reads it on the main
 * thread). A plain top-level object, not a ViewModel — a foreground
 * *Service*, not the Activity, owns the recording lifecycle; the Activity
 * is only ever a view onto whatever the service is doing, and the service
 * needs to keep running even if the Activity isn't currently visible.
 *
 * Kept deliberately small: a capped recent-sample window for the waveform,
 * a capped recent-segment list for the M/D graph, and a handful of scalar
 * status fields. No new dependency (kotlinx.coroutines/Flow) was pulled in
 * for this — Compose's own `State` objects are safe to write from a
 * background thread and trigger recomposition on read, which is all this
 * needs.
 */
object AitkenUiState {

    const val MAX_WAVEFORM_SAMPLES = 300
    const val MAX_RECENT_SEGMENTS = 40

    val phaseLabel = mutableStateOf("IDLE")
    val isRecording = mutableStateOf(false)
    val waveform: SnapshotStateList<LiveSample> = mutableStateListOf()
    val recentSegments: SnapshotStateList<ClosedSegment> = mutableStateListOf()
    val lastTagResult = mutableStateOf<String?>(null)
    /**
     * Removed from the UI (ticket 23) -- was a static "—" placeholder no
     * code ever wrote to. Kept here, commented out, for ticket 13 (Auto-
     * tagging integration) to wire to a real ClassifierRunner score:
     *
     * val confidenceLabel = mutableStateOf("—")
     */
    /** 0f..1f progress through the CALIBRATING phase — see RecordingPipeline.onCalibrationProgress. */
    val calibrationProgress = mutableStateOf(0f)
    /** What the session is detecting with (M and D thresholds), null until calibration/warm-up completes. */
    val calibration = mutableStateOf<CalibrationResult?>(null)
    /** True while calibration is currently ignoring samples (turning / accelerating / braking). */
    val calibrationPaused = mutableStateOf(false)
    /** Name of the preset this session started from, null for a fresh calibration. */
    val usingPreset = mutableStateOf<String?>(null)
    /** The phone's accelerometer: vendor, name, saturation limit. Null if unknown. */
    val accelInfo = mutableStateOf<AccelerometerInfo?>(null)
    /** Samples this session that hit the sensor's saturation limit. */
    val clipCount = mutableStateOf(0)
    /** Live D reading (sliding-window roughness), rounded to 0.1 and updated at ~20 Hz so the readout doesn't recompose every sample. */
    val liveRoughness = mutableStateOf(0f)
    /** A registered rough patch is in progress right now. */
    val patchActive = mutableStateOf(false)
    /** Rough patches registered so far this session. */
    val patchCount = mutableStateOf(0)
    /**
     * How long (ms) the currently-open segment has been open, null while
     * IDLE/CALIBRATING or between segments. Feeds the long-segment warning
     * banner (recommended pipeline fix #5, ride-data-analysis-update.md) —
     * updated on every sensor sample by [AitkenRecordingService] from
     * [com.aitken.recording.RecordingPipeline.currentOpenSegment], the same
     * accessor the tap-matching path already uses.
     */
    val openSegmentDurationMs = mutableStateOf<Long?>(null)

    /**
     * add() then removeAt(0) are two separate SnapshotStateList writes;
     * without wrapping them in one mutable snapshot, a concurrent reader
     * (e.g. MdGraph's Canvas draw block, running on a different thread than
     * this is called from -- see AndroidSensorStream's HandlerThread) can
     * observe the transient over-cap state between them. Bundling both
     * writes into one snapshot means readers only ever see "before" or
     * "after", never the momentary MAX+1 state in between.
     */
    fun pushSample(sample: LiveSample) {
        Snapshot.withMutableSnapshot {
            waveform.add(sample)
            while (waveform.size > MAX_WAVEFORM_SAMPLES) waveform.removeAt(0)
        }
    }

    fun pushSegment(segment: ClosedSegment) {
        Snapshot.withMutableSnapshot {
            recentSegments.add(segment)
            while (recentSegments.size > MAX_RECENT_SEGMENTS) recentSegments.removeAt(0)
        }
    }

    fun reset() {
        waveform.clear()
        recentSegments.clear()
        phaseLabel.value = "IDLE"
        isRecording.value = false
        lastTagResult.value = null
        calibrationProgress.value = 0f
        calibration.value = null
        calibrationPaused.value = false
        usingPreset.value = null
        clipCount.value = 0
        liveRoughness.value = 0f
        patchActive.value = false
        patchCount.value = 0
        openSegmentDurationMs.value = null
    }
}
