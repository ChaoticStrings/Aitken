package com.aitken.recording

import com.aitken.app.Tunables
import com.aitken.location.GpsFix
import com.aitken.segment.CalibrationResult
import com.aitken.segment.ClosedSegment
import com.aitken.segment.PatchTrigger
import com.aitken.segment.RoughPatch
import com.aitken.sensor.AccelerometerInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Uses real temp files (JUnit's TemporaryFolder), not a fake — this module
 * is a plain java.io sink (T2's explicit carve-out: the session-file format
 * isn't a vendor dependency, so it isn't wrapped behind an interface), and
 * the crash-safety guarantee this ticket cares about can only be proven
 * against a real filesystem, not a mock.
 */
class SessionRecorderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun sensorFile(): File = File(tempFolder.root, "sensor.csv")
    private fun gpsFile(): File = File(tempFolder.root, "gps.csv")
    private fun segmentsFile(): File = File(tempFolder.root, "segments.csv")
    private fun labelsFile(): File = File(tempFolder.root, "labels.csv")
    private fun patchesFile(): File = File(tempFolder.root, "patches.csv")
    private fun configFile(): File = File(tempFolder.root, "config.json")

    private fun calibration(presetName: String? = null) = CalibrationResult(
        mThreshold = 5.5f, dThreshold = 2.2f, meanPeak = 1.83f, maxRoughness = 1.47f,
        peakCount = 40, cleanMs = 10_000L, elapsedMs = 12_000L, windowFilled = true, presetName = presetName
    )

    @Test
    fun `writeSensorSample writes header and row with locale-safe fixed-point formatting`() {
        val recorder = SessionRecorder(tempFolder.root)

        recorder.writeSensorSample(
            timestampSensorNs = 12345L,
            accelX = 1.23456f, accelY = -2f, accelZ = 9.81f,
            gyroX = 0.1f, gyroY = null, gyroZ = 0.3f,
            verticalMs2 = 9.8123f, jerkMs3 = null, rollStdDev = 0.05f
        )
        recorder.close()

        val lines = sensorFile().readLines()
        assertEquals(2, lines.size) // header + 1 row
        assertEquals(
            "schema_version,timestamp_sensor_ns,accel_x_ms2,accel_y_ms2,accel_z_ms2," +
                "gyro_x_rads,gyro_y_rads,gyro_z_rads,vertical_ms2,jerk_ms3,roll_std_dev",
            lines[0]
        )
        // 1.23456 -> 1.235, 9.8123 -> 9.812 (standard 3dp rounding); null cells blank.
        assertEquals(
            "1,12345,1.235,-2.000,9.810,0.100,,0.300,9.812,,0.050",
            lines[1]
        )
    }

    @Test
    fun `writeGpsFix keeps latitude and longitude at full precision, not rounded to 3 decimals`() {
        val recorder = SessionRecorder(tempFolder.root)

        recorder.writeGpsFix(
            GpsFix(
                timestampNs = 5000L,
                latitude = 18.520430,
                longitude = 73.856744,
                speedMps = 4.5f,
                accuracyMeters = null
            )
        )
        recorder.close()

        val lines = gpsFile().readLines()
        // lat/lng use full Double precision (3 decimals would be ~111m resolution,
        // far too coarse for GPS); speed still gets the fixed 3dp treatment.
        assertEquals("1,5000,18.52043,73.856744,4.500,", lines[1])
    }

    @Test
    fun `writeClosedSegment includes speed and epoch alongside M and D`() {
        val recorder = SessionRecorder(tempFolder.root)

        recorder.writeClosedSegment(
            ClosedSegment(startNs = 1000L, durationNs = 200L, peakM = 5.5f, rmsM = 3.25f),
            speedMps = 6.7f,
            epochMs = 1_800_000_000_000L
        )
        recorder.close()

        val lines = segmentsFile().readLines()
        assertEquals("1,1000,200,5.500,3.250,6.700,1800000000000", lines[1])
    }

    @Test
    fun `writeLabel writes kind, segment reference, and tap offset, with nulls as empty cells`() {
        val recorder = SessionRecorder(tempFolder.root)

        recorder.writeLabel(
            timestampSensorNs = 9000L,
            kind = "POINT",
            segmentStartNs = 8000L,
            label = "Pothole",
            tapOffsetMs = 150L,
            epochMs = 1_800_000_000_500L
        )
        recorder.writeLabel(
            timestampSensorNs = 9500L,
            kind = "POINT",
            segmentStartNs = null,
            label = "Pothole",
            tapOffsetMs = null,
            epochMs = 1_800_000_001_000L
        )
        recorder.close()

        val lines = labelsFile().readLines()
        assertEquals("1,9000,POINT,8000,Pothole,150,1800000000500", lines[1])
        assertEquals("1,9500,POINT,,Pothole,,1800000001000", lines[2])
    }

    @Test
    fun `writePatch writes a rough patch row to its own file`() {
        val recorder = SessionRecorder(tempFolder.root)
        recorder.writePatch(
            RoughPatch(
                startNs = 1_000L, endNs = 9_000L, lengthM = 3.25f, segmentCount = 2,
                peakM = 31.5f, maxD = 4.125f, trigger = PatchTrigger.CLUSTER
            ),
            speedMps = 6.5f, epochMs = 1_800_000_000_000L
        )
        recorder.close()

        val lines = patchesFile().readLines()
        assertEquals(
            "schema_version,start_ns,end_ns,length_m,segment_count,peak_m,max_d,trigger,speed_mps,epoch_ms",
            lines[0]
        )
        assertEquals("1,1000,9000,3.250,2,31.500,4.125,CLUSTER,6.500,1800000000000", lines[1])
        // the human-tag corpus is untouched
        assertEquals(1, labelsFile().readLines().size)
    }

    @Test
    fun `writeConfig records the active tunables, both calibrated thresholds, the window and the sensor, independent of the CSV flush cycle`() {
        val recorder = SessionRecorder(tempFolder.root)
        val tunables = Tunables(
            calibrationDurationMs = 10_000L, stdFactor = 3f, floorStd = 0.5f,
            endQuietMs = 500L, minSegmentDurationMs = 30L, turnYawThresholdRadS = 1.0f,
            mildSeverityDeviation = 5f, moderateSeverityDeviation = 15f,
            tagDebounceMs = 500L, longSegmentWarningMs = 20_000L,
            rollingWindowMs = 1_000L, roughSensitivity = 1.5f, roughPatchDistanceM = 2.5f
        )
        recorder.writeConfig(
            tunables, calibration(), rollingWindowMs = 1_000L,
            accelerometer = AccelerometerInfo("BMI160 Accelerometer", "BOSCH", 78.4532f, 0.0023f)
        )

        // No recorder.close() call -- unlike the CSVs, config.json is a single
        // synchronous write, not buffered, so it's on disk immediately.
        assertEquals(
            "{\n" +
                "  \"schemaVersion\": 1,\n" +
                "  \"detectorVersion\": 2,\n" +
                "  \"calibrationDurationMs\": 10000,\n" +
                "  \"stdFactor\": 3.0,\n" +
                "  \"floorStd\": 0.5,\n" +
                "  \"endQuietMs\": 500,\n" +
                "  \"minSegmentDurationMs\": 30,\n" +
                "  \"turnYawThresholdRadS\": 1.0,\n" +
                "  \"mildSeverityDeviation\": 5.0,\n" +
                "  \"moderateSeverityDeviation\": 15.0,\n" +
                "  \"tagDebounceMs\": 500,\n" +
                "  \"longSegmentWarningMs\": 20000,\n" +
                "  \"rollingWindowMs\": 1000,\n" +
                "  \"roughSensitivity\": 1.5,\n" +
                "  \"roughPatchDistanceM\": 2.5,\n" +
                "  \"calibrationSource\": \"fresh\",\n" +
                "  \"calibrationPresetName\": null,\n" +
                "  \"calibratedMThreshold\": 5.5,\n" +
                "  \"calibratedDThreshold\": 2.2,\n" +
                "  \"calibrationMeanPeak\": 1.83,\n" +
                "  \"calibrationMaxRoughness\": 1.47,\n" +
                "  \"calibrationCleanMs\": 10000,\n" +
                "  \"calibrationElapsedMs\": 12000,\n" +
                "  \"accelMaxRangeMs2\": 78.4532,\n" +
                "  \"accelVendor\": \"BOSCH\",\n" +
                "  \"accelName\": \"BMI160 Accelerometer\"\n" +
                "}\n",
            configFile().readText()
        )
    }

    @Test
    fun `writeConfig for a preset session says so, and tolerates an unknown sensor and awkward characters in the name`() {
        val recorder = SessionRecorder(tempFolder.root)
        recorder.writeConfig(Tunables(), calibration(presetName = "Rattly \"city\" mount"), rollingWindowMs = 2_000L)

        val content = configFile().readText()
        assertTrue(content.contains("\"calibrationSource\": \"preset\""))
        assertTrue(content.contains("\"calibrationPresetName\": \"Rattly \\\"city\\\" mount\""))
        assertTrue(content.contains("\"rollingWindowMs\": 2000"))
        assertTrue(content.contains("\"accelMaxRangeMs2\": null"))
        assertTrue(content.contains("\"accelVendor\": null"))
    }

    @Test
    fun `writeConfig called twice overwrites rather than appending`() {
        val recorder = SessionRecorder(tempFolder.root)

        recorder.writeConfig(Tunables(stdFactor = 1.5f), calibration(), 1_000L)
        recorder.writeConfig(Tunables(stdFactor = 3.0f), calibration(), 1_000L)

        val content = configFile().readText()
        assertTrue(content.contains("\"stdFactor\": 3.0"))
        assertTrue(!content.contains("\"stdFactor\": 1.5"))
        assertEquals(1, content.split("\"stdFactor\"").size - 1) // exactly one occurrence, not appended
    }

    @Test
    fun `a crash mid-session loses only unflushed rows, and a clean close flushes the rest`() {
        val recorder = SessionRecorder(tempFolder.root, flushEveryNRows = 3)

        repeat(5) { i ->
            recorder.writeSensorSample(
                timestampSensorNs = i.toLong(),
                accelX = 0f, accelY = 0f, accelZ = 0f,
                gyroX = null, gyroY = null, gyroZ = null,
                verticalMs2 = 0f, jerkMs3 = null, rollStdDev = 0f
            )
        }

        // No close() yet — simulated crash. Read the file independently, the
        // way a fresh process reopening it after the crash would.
        val beforeClose = sensorFile().readLines()
        assertEquals(4, beforeClose.size) // header + 3 flushed rows; rows 4-5 still buffered

        recorder.close()

        val afterClose = sensorFile().readLines()
        assertEquals(6, afterClose.size) // header + all 5 rows, once close() flushes the rest
    }
}
