package com.aitken.app

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.aitken.segment.ClosedSegment

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
    val waveform: SnapshotStateList<Float> = mutableStateListOf()
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
    /** The session's calibrated short-window std threshold, null until calibration completes. */
    val calibratedThresholdM = mutableStateOf<Float?>(null)

    /**
     * add() then removeAt(0) are two separate SnapshotStateList writes;
     * without wrapping them in one mutable snapshot, a concurrent reader
     * (e.g. MdGraph's Canvas draw block, running on a different thread than
     * this is called from -- see AndroidSensorStream's HandlerThread) can
     * observe the transient over-cap state between them. Bundling both
     * writes into one snapshot means readers only ever see "before" or
     * "after", never the momentary MAX+1 state in between.
     */
    fun pushSample(vertical: Float) {
        Snapshot.withMutableSnapshot {
            waveform.add(vertical)
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
        calibratedThresholdM.value = null
    }
}
