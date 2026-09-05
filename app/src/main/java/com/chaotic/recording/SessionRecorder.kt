package com.aitken.recording

import com.aitken.app.Tunables
import com.aitken.location.GpsFix
import com.aitken.segment.ClosedSegment
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.Locale

/**
 * Crash-safe incremental writer owning all five session files (sensor, gps,
 * segments, labels, and config) behind one interface, centralizing the
 * flush guarantee so it lives in exactly one place. Each of the four
 * incremental files carries its own independent `schema_version`
 * (architecture invariant 2, T2) since they evolve at different rates;
 * [writeConfig]'s config.json is written once, not incrementally, so it
 * carries its version as a plain field inside the JSON object instead.
 *
 * Primary writes always go straight to app-private storage — a plain
 * [File] the caller provides. The session-file format itself is *not*
 * wrapped behind an interface (T2's explicit carve-out from invariant 1: no
 * real second adapter exists for it, it's an internal format choice, not a
 * vendor dependency), so this class talks to java.io directly and is fully
 * testable on the JVM with real temp files, no Android framework needed.
 * [writeConfig]'s hand-rolled JSON (rather than `org.json`, which is an
 * Android-framework stub outside Robolectric) keeps that same guarantee for
 * the fifth file.
 *
 * `speedMps` on [writeClosedSegment]/[writeLabel] and `epochMs` everywhere
 * are caller-supplied rather than this class holding a GpsProvider or clock
 * itself (user stories 25/26): the caller (ticket 10's pipeline) already
 * queries `gpsProvider.currentFix()` and the wall-clock at the moment a
 * segment closes or a tap lands, so passing those values in keeps this
 * class a simple I/O sink with no held dependencies of its own. `epochMs`
 * (real wall-clock time, distinct from the monotonic sensor-clock `Ns`
 * timestamps used everywhere else) is recorded on segments and labels only,
 * not on every ~100Hz sensor/gps row — user story 26 asks for time-of-day
 * context per *segment*, and stamping it on every high-frequency row would
 * be redundant. If per-row wall-clock time is ever needed for the other two
 * files, a single epoch/monotonic anchor pair recorded once at session
 * start is enough to reconstruct it — not implemented here since nothing
 * currently asks for it.
 *
 * `kind` on [writeLabel] is a plain String ("POINT"/"RANGE_START"/
 * "RANGE_END") rather than TagMatcher's enum type (ticket 06), so this
 * ticket doesn't create a forward dependency on a module that doesn't exist
 * yet, and so this class stays decoupled from the tagging package in
 * general.
 *
 * [writeConfig] is the fifth file (ride-data-analysis-update.md §4):
 * previously nothing recorded which [Tunables] were active for a session,
 * so a session's own detection behavior couldn't be reconstructed from its
 * data after the fact — §1's 12-vs-132-segment swing between two sessions
 * of the *same physical road* turned out to trace entirely to calibration
 * conditions this file would have made visible immediately instead of
 * requiring a forensic replay. Written once, right after calibration
 * completes (the earliest point both the tunables and the two calibrated
 * thresholds are known), not incrementally like the other four — nothing
 * about it changes mid-session.
 */
class SessionRecorder(private val sessionDir: File, flushEveryNRows: Int = 100) {

    private val sensorFile = CsvFile(
        File(sessionDir, "sensor.csv"),
        "schema_version,timestamp_sensor_ns,accel_x_ms2,accel_y_ms2,accel_z_ms2," +
            "gyro_x_rads,gyro_y_rads,gyro_z_rads,vertical_ms2,jerk_ms3,roll_std_dev",
        flushEveryNRows
    )
    private val gpsFile = CsvFile(
        File(sessionDir, "gps.csv"),
        "schema_version,timestamp_sensor_ns,latitude,longitude,speed_mps,accuracy_m",
        flushEveryNRows
    )
    private val segmentsFile = CsvFile(
        File(sessionDir, "segments.csv"),
        "schema_version,start_ns,duration_ns,peak_m,rms_m,speed_mps,epoch_ms",
        flushEveryNRows
    )
    private val labelsFile = CsvFile(
        File(sessionDir, "labels.csv"),
        "schema_version,timestamp_sensor_ns,kind,segment_start_ns,label,tap_offset_ms,epoch_ms",
        flushEveryNRows
    )

    fun writeSensorSample(
        timestampSensorNs: Long,
        accelX: Float, accelY: Float, accelZ: Float,
        gyroX: Float?, gyroY: Float?, gyroZ: Float?,
        verticalMs2: Float, jerkMs3: Float?, rollStdDev: Float
    ) {
        sensorFile.writeRow(
            listOf(
                SCHEMA_VERSION.toString(), timestampSensorNs.toString(),
                accelX.csv(), accelY.csv(), accelZ.csv(),
                gyroX.csv(), gyroY.csv(), gyroZ.csv(),
                verticalMs2.csv(), jerkMs3.csv(), rollStdDev.csv()
            )
        )
    }

    fun writeGpsFix(fix: GpsFix) {
        gpsFile.writeRow(
            listOf(
                SCHEMA_VERSION.toString(), fix.timestampNs.toString(),
                fix.latitude.toString(), fix.longitude.toString(),
                fix.speedMps.csv(), fix.accuracyMeters.csv()
            )
        )
    }

    fun writeClosedSegment(segment: ClosedSegment, speedMps: Float?, epochMs: Long) {
        segmentsFile.writeRow(
            listOf(
                SCHEMA_VERSION.toString(),
                segment.startNs.toString(), segment.durationNs.toString(),
                segment.peakM.csv(), segment.rmsM.csv(),
                speedMps.csv(), epochMs.toString()
            )
        )
    }

    /**
     * Writes `config.json`: the [Tunables] active for this session plus the
     * two thresholds [NoiseFloorCalibrator] actually derived from them,
     * side by side — the sidecar ride-data-analysis-update.md §4 asks for.
     * Call once, as soon as calibration completes; calling it again
     * overwrites the file (harmless in practice, since nothing about a
     * session's tunables or calibrated thresholds changes after that point
     * except [Tunables.tagDebounceMs], which is deliberately re-read live
     * per tap rather than snapshotted, so a stale copy of it here is
     * expected, not a bug).
     */
    fun writeConfig(tunables: Tunables, calibratedShortStdThreshold: Float, calibratedLongStdThreshold: Float) {
        val json = buildString {
            append("{\n")
            append("  \"schemaVersion\": $SCHEMA_VERSION,\n")
            append("  \"calibrationDurationMs\": ${tunables.calibrationDurationMs},\n")
            append("  \"stdFactor\": ${tunables.stdFactor},\n")
            append("  \"floorStd\": ${tunables.floorStd},\n")
            append("  \"endQuietMs\": ${tunables.endQuietMs},\n")
            append("  \"minSegmentDurationMs\": ${tunables.minSegmentDurationMs},\n")
            append("  \"turnYawThresholdRadS\": ${tunables.turnYawThresholdRadS},\n")
            append("  \"mildSeverityDeviation\": ${tunables.mildSeverityDeviation},\n")
            append("  \"moderateSeverityDeviation\": ${tunables.moderateSeverityDeviation},\n")
            append("  \"tagDebounceMs\": ${tunables.tagDebounceMs},\n")
            append("  \"longSegmentWarningMs\": ${tunables.longSegmentWarningMs},\n")
            append("  \"calibratedShortStdThreshold\": $calibratedShortStdThreshold,\n")
            append("  \"calibratedLongStdThreshold\": $calibratedLongStdThreshold\n")
            append("}\n")
        }
        File(sessionDir, "config.json").writeText(json)
    }

    fun writeLabel(
        timestampSensorNs: Long,
        kind: String,
        segmentStartNs: Long?,
        label: String,
        tapOffsetMs: Long?,
        epochMs: Long
    ) {
        labelsFile.writeRow(
            listOf(
                SCHEMA_VERSION.toString(), timestampSensorNs.toString(), kind,
                segmentStartNs?.toString() ?: "", label,
                tapOffsetMs?.toString() ?: "", epochMs.toString()
            )
        )
    }

    /** Flushes and closes all four incrementally-written CSV files. Call when a session ends.
     *  ([writeConfig]'s config.json needs no closing — it's one synchronous write, not a stream.) */
    fun close() {
        sensorFile.close()
        gpsFile.close()
        segmentsFile.close()
        labelsFile.close()
    }

    private companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Fixed 3-decimal, locale-independent formatting for a nullable Float CSV
 * field. Explicit [Locale.US] — unlike Prototype 1's bare
 * `"%.3f".format(...)`, which silently emits a comma decimal separator
 * (corrupting a comma-delimited CSV) on a non-US-locale device.
 */
private fun Float?.csv(): String = this?.let { String.format(Locale.US, "%.3f", it) } ?: ""

/**
 * One CSV file with periodic flush — crash-safe incremental writes without
 * paying flush cost on every single row. [flushEveryNRows] mirrors
 * Prototype 1's already-audited default (100). This flush() guarantees the
 * bytes have left the application and reached the OS; it is not an fsync
 * durability guarantee against power loss, only against an app crash —
 * which is what user story 8 actually asks for.
 */
private class CsvFile(
    file: File,
    header: String,
    private val flushEveryNRows: Int
) {
    private val writer = BufferedWriter(FileWriter(file))
    private var rowsSinceFlush = 0

    init {
        writer.append(header).append("\n")
        writer.flush()
    }

    fun writeRow(fields: List<String>) {
        writer.append(fields.joinToString(",")).append("\n")
        if (++rowsSinceFlush >= flushEveryNRows) {
            writer.flush()
            rowsSinceFlush = 0
        }
    }

    fun close() {
        writer.flush()
        writer.close()
    }
}
