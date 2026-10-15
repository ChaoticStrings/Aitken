package com.aitken.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.aitken.recording.AitkenRecordingService
import com.aitken.segment.CalibrationPreset
import com.aitken.sensor.AccelerometerInfo
import com.aitken.sensor.AndroidSensorStream
import com.aitken.storage.SharedPreferencesSafFolderGrant
import com.aitken.storage.StorageGrantScreen
import com.aitken.tagging.TagKind

/**
 * Aitken's one and only screen (ticket 11, expanded scope — see this
 * ticket's verification note for why). Two states:
 *
 * - **Idle**: one big, unambiguous START SESSION button and a SETTINGS
 *   button. Deliberately not auto-start — confirmed directly with Vision:
 *   a single deliberate tap before setting off, not a surprise recording.
 * - **Recording**: the M/D graph (live waveform + a duration bar-strip)
 *   and four large tap targets (three point-tags, one range toggle), plus
 *   STOP SESSION and SETTINGS.
 *
 * Every touch target here is sized for riding, not sitting at a desk —
 * see the sizing notes on [BigTagButton] and the settings screen. Portrait
 * lock and dark theme are set at the Activity/manifest level, not here.
 */
@Composable
fun AitkenSessionScreen() {
    val context = LocalContext.current
    val isRecording by AitkenUiState.isRecording
    val phaseLabel by AitkenUiState.phaseLabel
    var showSettings by remember { mutableStateOf(false) }
    val storageGrant = remember { SharedPreferencesSafFolderGrant(context) }
    // Computed once per app process, not re-checked on every recomposition --
    // "one-time prompt on first launch if ungranted" (ticket 24), not a nag
    // that reappears every time Settings closes. Suppressed if a session is
    // already running in the background (AitkenRecordingService outlives the
    // Activity) -- reopening mid-ride should land on the ride, not a prompt.
    var showStorageGrant by remember { mutableStateOf(storageGrant.grantedUri() == null && !isRecording) }

    if (showStorageGrant) {
        StorageGrantScreen(grant = storageGrant, onBack = { showStorageGrant = false })
        return
    }

    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false }, onOpenStorageGrant = { showStorageGrant = true })
        return
    }

    // Settings apply on next session start, not live mid-session -- same
    // convention as Prototype 1's SettingsPanel ("Applies on next START
    // SESSION"). Loaded once per composition, not on every recomposition.
    val tunables = remember(isRecording) { SettingsStore.load(context) }
    // Idle-screen state: which saved calibration the next session starts from.
    var presets by remember(isRecording) { mutableStateOf(PresetStore.load(context)) }
    var selectedPresetId by remember(isRecording) { mutableStateOf(PresetStore.selectedId(context)) }
    // Read straight from SensorManager so the idle screen can show the sensor too.
    val idleSensorInfo = remember { AndroidSensorStream.queryAccelerometerInfo(context) }
    val sessionSensorInfo by AitkenUiState.accelInfo
    val sensorInfo = sessionSensorInfo ?: idleSensorInfo

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .systemBarsPadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AITKEN", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                if (isRecording) "● $phaseLabel" else "○ IDLE",
                color = when {
                    !isRecording -> Color(0xFF78909C)
                    phaseLabel == "CALIBRATING" -> Color(0xFFFFA726)
                    else -> Color(0xFF4FC3F7)
                },
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }

        SensorInfoLine(sensorInfo, clipCount = if (isRecording) AitkenUiState.clipCount.value else null)

        if (!isRecording) {
            IdleControls(
                modifier = Modifier.weight(1f),
                presets = presets,
                selectedPresetId = selectedPresetId,
                onSelectPreset = { id ->
                    PresetStore.select(context, id)
                    selectedPresetId = id
                },
                onStart = {
                    context.startForegroundService(Intent(context, AitkenRecordingService::class.java))
                },
                onOpenSettings = { showSettings = true }
            )
        } else {
            MdGraph(modifier = Modifier.weight(0.42f).fillMaxWidth(), tunables = tunables)

            if (phaseLabel == "CALIBRATING") {
                val calibrationProgress by AitkenUiState.calibrationProgress
                val calibrationPaused by AitkenUiState.calibrationPaused
                val usingPreset by AitkenUiState.usingPreset
                CalibratingBanner(progress = calibrationProgress, paused = calibrationPaused, presetName = usingPreset)
            } else {
                val lastTagResult by AitkenUiState.lastTagResult
                val openSegmentDurationMs by AitkenUiState.openSegmentDurationMs
                val longSegmentWarningMs = tunables.longSegmentWarningMs
                // Recommended pipeline fix #5 (ride-data-analysis-update.md):
                // flag a stuck-open segment live, in place of the normal status
                // line, rather than only discovering the 56s/104s/311s pattern
                // later from the data.
                if (openSegmentDurationMs != null && openSegmentDurationMs!! >= longSegmentWarningMs) {
                    Text(
                        "⚠ Segment open ${openSegmentDurationMs!! / 1000}s — road may not be reading smooth again",
                        color = Color(0xFFFFA726),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    // Confidence indicator removed here -- Aitken-phase (ticket 13's
                    // ClassifierRunner), not Luna. AitkenUiState.confidenceLabel stays
                    // commented out below, not deleted, for ticket 13 to pick back up.
                    SaveCalibrationRow(statusText = lastTagResult ?: "Ride safe.")
                }
            }

            TagButtons(
                modifier = Modifier.weight(0.58f).fillMaxWidth(),
                context = context,
                onOpenSettings = { showSettings = true },
                // Discarded outright, not queued -- a tap during CALIBRATING has no
                // detector to match against yet (ticket 22).
                tagsEnabled = phaseLabel != "CALIBRATING"
            )
        }
    }
}

/**
 * One compact line under the title: the accelerometer's vendor and name, its
 * saturation ceiling (`Sensor.getMaximumRange()`), and the highest M the
 * sensor can register before an upward hit clips — M-scale from the
 * Workbench prototype ([MScale]). While recording, also counts how many
 * samples have hit that ceiling.
 */
@Composable
private fun SensorInfoLine(info: AccelerometerInfo?, clipCount: Int?) {
    if (info == null) {
        Text("Accelerometer info unavailable", color = Color(0xFF546E7A), fontSize = 12.sp)
        return
    }
    val maxM = MScale.peakToM(info.ceilingDeviationUpMs2)
    val who = listOf(info.vendor, info.name).filter { it.isNotBlank() }.joinToString(" ")
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(who.ifEmpty { "Accelerometer" }, color = Color(0xFF90A4AE), fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Ceiling ±%.1f m/s²".format(info.maxRangeMs2), color = Color(0xFFCFD8DC), fontSize = 13.sp)
            Text("Max M %.1f".format(maxM), color = Color(0xFFCFD8DC), fontSize = 13.sp)
            if (clipCount != null) {
                Text(
                    "Clipped ×$clipCount",
                    color = if (clipCount > 0) Color(0xFFFFA726) else Color(0xFF546E7A),
                    fontSize = 13.sp,
                    fontWeight = if (clipCount > 0) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun IdleControls(
    modifier: Modifier = Modifier,
    presets: List<CalibrationPreset>,
    selectedPresetId: String?,
    onSelectPreset: (String?) -> Unit,
    onStart: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Button(
            onClick = onStart,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E88E5)),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth().height(200.dp)
        ) {
            Text(
                "START\nSESSION",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = Color.White
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        PresetPicker(presets = presets, selectedId = selectedPresetId, onSelect = onSelectPreset)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().height(72.dp)
        ) {
            Text("SETTINGS", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Dropdown choosing what the next session calibrates from: a fresh
 * calibration (the default — measure the first stretch of the ride), or a
 * saved preset, which skips calibration entirely. Selection persists, so a
 * rider who always uses the same mount picks it once.
 */
@Composable
private fun PresetPicker(presets: List<CalibrationPreset>, selectedId: String?, onSelect: (String?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = presets.firstOrNull { it.id == selectedId }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) {
            Text(
                "CALIBRATION: ${selected?.name ?: "Fresh"}  ▾",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Fresh calibration (measure at start)") },
                onClick = { onSelect(null); expanded = false }
            )
            presets.forEach { p ->
                DropdownMenuItem(
                    text = { Text("${p.name}  ·  M %.1f  ·  D %.1f".format(p.mThreshold, p.dThreshold)) },
                    onClick = { onSelect(p.id); expanded = false }
                )
            }
        }
    }
}

/**
 * Status line, plus — once a *fresh* calibration has finished — a SAVE
 * PRESET button that stores its M/D thresholds (and the sliding-window width
 * they were measured with) under a name the rider can edit. Hidden for a
 * session that itself started from a preset, and after saving.
 */
@Composable
private fun SaveCalibrationRow(statusText: String) {
    val calibration by AitkenUiState.calibration
    var showDialog by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val canSave = calibration != null && calibration?.presetName == null && !saved

    if (showDialog) {
        var name by remember {
            mutableStateOf("Preset " + SimpleDateFormat("d MMM HH:mm", Locale.US).format(Date()))
        }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Save calibration as preset") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    showDialog = false
                    val preset = AitkenRecordingService.instance?.saveCalibrationPreset(name)
                    if (preset != null) {
                        saved = true
                        message = "Saved preset “${preset.name}”"
                    }
                }) { Text("SAVE", fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showDialog = false }) { Text("CANCEL") } }
        )
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            message ?: statusText,
            color = Color(0xFFCFD8DC),
            fontSize = 15.sp,
            modifier = Modifier.weight(1f)
        )
        if (canSave) {
            OutlinedButton(onClick = { showDialog = true }) {
                Text("SAVE PRESET", fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * The M/D graph: a live scrolling waveform of vertical acceleration (M, as
 * a continuous signal) above a bar-strip of recent segment durations (D,
 * one bar per segment, colored by [severityOf]'s placeholder tiers).
 *
 * Layers, back to front, all on [GraphScale]'s symmetric-log axis:
 *  - **Clipped spectrum**: translucent amber beyond the sensor's saturation
 *    ceiling (`Sensor.getMaximumRange()`), with a brighter amber stripe at
 *    every sample that actually hit it.
 *  - **Reference lines**: the baseline, a few fixed deviations, the M
 *    threshold (dashed, +/-; exact now that the trigger is an amplitude)
 *    and the D threshold (dashed yellow).
 *  - **Segment fill**: translucent blue between the waveform and the
 *    baseline while a segment is open — the stretch being recorded.
 *  - **Waveform** (light blue) and the **D reading** (yellow): the moving
 *    average of deviation over the sliding window, drawn so the window
 *    width can be judged by eye against real roads.
 */
@Composable
private fun MdGraph(modifier: Modifier = Modifier, tunables: Tunables) {
    val waveform = AitkenUiState.waveform
    val recentSegments = AitkenUiState.recentSegments
    val sensorInfo = AitkenUiState.accelInfo.value
    val scale = remember(sensorInfo) { GraphScale(sensorInfo) }

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.weight(0.72f).fillMaxWidth()) {
            val width = size.width
            val height = size.height
            val baseline = 9.81f // resting gravity magnitude -- see Verticalizer
            val halfH = height / 2f
            val centerY = halfH
            fun yOf(deviation: Float) = centerY - scale.fraction(deviation) * halfH

            val amber = Color(0xFFFFA726)
            val xStep = width / (AitkenUiState.MAX_WAVEFORM_SAMPLES - 1).toFloat()

            // Snapshot once, up front. `waveform` is written from the sensor
            // HandlerThread (AitkenUiState.pushSample) while this draw block
            // runs on the render thread; reading .indices/.size/[i] as three
            // separate live calls against the mutable list let a concurrent
            // add()+removeAt(0) shrink it between reads, throwing
            // IndexOutOfBoundsException at the cap boundary. Copying once
            // here means every access below sees one consistent list.
            val samples = waveform.toList()
            fun xOf(i: Int) = width - (samples.size - 1 - i) * xStep

            // Clipped spectrum: everything beyond the sensor's ceiling.
            scale.ceilingFractionUp?.let { f ->
                val yCeil = centerY - f * halfH
                drawRect(amber.copy(alpha = 0.22f), topLeft = Offset(0f, 0f), size = Size(width, yCeil))
                drawLine(amber.copy(alpha = 0.8f), Offset(0f, yCeil), Offset(width, yCeil), strokeWidth = 1.5f)
            }
            scale.ceilingFractionDown?.let { f ->
                val yCeil = centerY + f * halfH
                drawRect(amber.copy(alpha = 0.22f), topLeft = Offset(0f, yCeil), size = Size(width, height - yCeil))
                drawLine(amber.copy(alpha = 0.8f), Offset(0f, yCeil), Offset(width, yCeil), strokeWidth = 1.5f)
            }
            // A sample that actually saturated the sensor.
            for (i in samples.indices) {
                if (samples[i].clipped) {
                    val x = xOf(i)
                    drawRect(
                        amber.copy(alpha = 0.55f),
                        topLeft = Offset(x - xStep / 2f, 0f),
                        size = Size(xStep.coerceAtLeast(2f), height)
                    )
                }
            }

            // Baseline and a few fixed deviations for scale.
            drawLine(Color(0xFF37474F), Offset(0f, centerY), Offset(width, centerY), strokeWidth = 1.5f)
            for (dev in floatArrayOf(5f, 10f, 25f)) {
                drawLine(Color(0xFF263238), Offset(0f, yOf(dev)), Offset(width, yOf(dev)), strokeWidth = 1f)
                drawLine(Color(0xFF263238), Offset(0f, yOf(-dev)), Offset(width, yOf(-dev)), strokeWidth = 1f)
            }

            // Exact thresholds: M is an amplitude trigger, D is a windowed mean.
            val calibration = AitkenUiState.calibration.value
            if (calibration != null) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                val mColor = Color(0xFF78909C)
                drawLine(mColor, Offset(0f, yOf(calibration.mThreshold)), Offset(width, yOf(calibration.mThreshold)), strokeWidth = 2f, pathEffect = dash)
                drawLine(mColor, Offset(0f, yOf(-calibration.mThreshold)), Offset(width, yOf(-calibration.mThreshold)), strokeWidth = 2f, pathEffect = dash)
                drawLine(Color(0xFFFFEB3B), Offset(0f, yOf(calibration.dThreshold)), Offset(width, yOf(calibration.dThreshold)), strokeWidth = 2f, pathEffect = dash)
            }

            if (samples.size > 1) {
                // Segment being recorded: translucent blue between waveform and baseline.
                var i = 0
                while (i < samples.size) {
                    if (!samples[i].segmentOpen) { i++; continue }
                    var end = i
                    while (end + 1 < samples.size && samples[end + 1].segmentOpen) end++
                    val fill = Path()
                    fill.moveTo(xOf(i), centerY)
                    for (k in i..end) fill.lineTo(xOf(k), yOf(samples[k].vertical - baseline))
                    fill.lineTo(xOf(end), centerY)
                    fill.close()
                    drawPath(fill, Color(0xFF4FC3F7).copy(alpha = 0.30f), style = Fill)
                    i = end + 1
                }

                val wave = Path()
                val rough = Path()
                for (k in samples.indices) {
                    val x = xOf(k)
                    val yW = yOf(samples[k].vertical - baseline)
                    val yR = yOf(samples[k].roughness)
                    if (k == 0) { wave.moveTo(x, yW); rough.moveTo(x, yR) } else { wave.lineTo(x, yW); rough.lineTo(x, yR) }
                }
                drawPath(
                    path = wave,
                    color = Color(0xFF4FC3F7),
                    style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    path = rough,
                    color = Color(0xFFFFEB3B),
                    style = Stroke(width = 3f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        Row(
            modifier = Modifier.weight(0.28f).fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            if (recentSegments.isEmpty()) {
                Text(
                    "No segments detected yet this session",
                    color = Color(0xFF546E7A),
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                val shown = recentSegments.takeLast(24)
                val maxDurationMs = shown.maxOf { it.durationNs / 1_000_000L }.coerceAtLeast(1L)
                for (segment in shown) {
                    val durationMs = segment.durationNs / 1_000_000L
                    val fraction = (durationMs.toFloat() / maxDurationMs).coerceIn(0.1f, 1f)
                    val color = when (severityOf(segment.peakM, tunables)) {
                        Severity.MILD -> Color(0xFF66BB6A)
                        Severity.MODERATE -> Color(0xFFFFA726)
                        Severity.SEVERE -> Color(0xFFEF5350)
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(fraction)
                            .background(color, RoundedCornerShape(2.dp))
                    )
                }
            }
        }
    }
}

/**
 * Shown instead of the tag-result row while calibrating — explains what is
 * happening and how much longer it will take. Progress counts *clean*
 * calibration time, so it stalls (and the banner says why) whenever the
 * vehicle is turning or accelerating/braking: those stretches are excluded
 * from the measurement rather than corrupting it. With a saved preset there
 * is nothing to measure, only sensors to settle.
 */
@Composable
private fun CalibratingBanner(modifier: Modifier = Modifier, progress: Float, paused: Boolean, presetName: String?) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            when {
                presetName != null -> "Applying preset “$presetName” — letting the sensors settle"
                paused -> "Turning or changing speed — calibration paused, hold steady"
                else -> "Hold steady on smooth road — measuring your mount's baseline vibration"
            },
            color = if (paused && presetName == null) Color(0xFFFFA726) else Color(0xFFCFD8DC),
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = Color(0xFFFFA726),
            trackColor = Color(0xFF263238)
        )
    }
}

/**
 * Takes the place of the old START/END ROUGH STRETCH button: rough patches
 * register themselves now (distance-based), so there is nothing to tap.
 * Shows the live D reading — the sliding-window roughness, the same value as
 * the yellow line on the graph — against its calibrated threshold, and lights
 * up while a rough patch is being recorded.
 */
@Composable
private fun DReadout(modifier: Modifier = Modifier) {
    val calibration by AitkenUiState.calibration
    val roughness by AitkenUiState.liveRoughness
    val patchActive by AitkenUiState.patchActive
    val patchCount by AitkenUiState.patchCount
    val threshold = calibration?.dThreshold
    val above = threshold != null && roughness >= threshold
    val fill = when {
        patchActive -> Color(0xFF8D4B00)
        above -> Color(0xFF5D4E00)
        else -> Color(0xFF1C2530)
    }
    Column(
        modifier = modifier.background(fill, RoundedCornerShape(18.dp)).padding(8.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("D", color = Color(0xFFFFEB3B), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text("%.1f".format(roughness), color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
        Text(
            if (threshold != null) "threshold %.1f".format(threshold) else "—",
            color = Color(0xFFB0BEC5),
            fontSize = 13.sp
        )
        Text(
            if (patchActive) "ROUGH PATCH" else "patches: $patchCount",
            color = if (patchActive) Color(0xFFFFEB3B) else Color(0xFF78909C),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun TagButtons(
    modifier: Modifier = Modifier,
    context: Context,
    onOpenSettings: () -> Unit,
    tagsEnabled: Boolean = true
) {
    // Prevention over cure (ride-data-analysis-update.md §3, as scoped for
    // Luna): the whole reason §1's calibration-fragility bug had a real
    // second session to compare against is that an unfortunately-timed
    // bump landed on this exact button mid-ride, ending 124056 early and
    // forcing 125553 to calibrate while already moving. A confirm step
    // can't fix calibration fragility itself, but it stops the accidental
    // tap that keeps causing it in the first place -- cheaper and more
    // direct than detecting or compensating for the split after the fact.
    var showStopConfirm by remember { mutableStateOf(false) }

    fun tap(label: String, kind: TagKind) {
        AitkenRecordingService.instance?.tag(kind, label)
    }

    if (showStopConfirm) {
        AlertDialog(
            onDismissRequest = { showStopConfirm = false },
            title = { Text("Confirm stop session?") },
            text = { Text("This ends the ride and closes out the recording. You can't resume it afterward.") },
            confirmButton = {
                TextButton(onClick = {
                    showStopConfirm = false
                    context.stopService(Intent(context, AitkenRecordingService::class.java))
                }) {
                    Text("STOP SESSION", color = Color(0xFFEF5350), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showStopConfirm = false }) {
                    Text("CANCEL")
                }
            }
        )
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTagButton("POTHOLE", Color(0xFFEF5350), Modifier.weight(1f), enabled = tagsEnabled) {
                tap("Pothole", TagKind.POINT)
            }
            BigTagButton("BUMP", Color(0xFF66BB6A), Modifier.weight(1f), enabled = tagsEnabled) {
                tap("Bump", TagKind.POINT)
            }
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigTagButton("SPEED\nBREAKER", Color(0xFFFFA726), Modifier.weight(1f), enabled = tagsEnabled) {
                tap("Speedbreaker", TagKind.POINT)
            }
            DReadout(modifier = Modifier.weight(1f).fillMaxHeight())
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Text("SETTINGS", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                // No longer stops immediately on tap -- see showStopConfirm above.
                onClick = { showStopConfirm = true },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Text(
                    "STOP\nSESSION",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * A touch target sized for riding, not sitting at a desk: fills its whole
 * row-cell (roughly a quarter of screen width, a third of remaining
 * height on most phones — comfortably over 100dp in both dimensions on
 * typical hardware), high-contrast fill color, bold oversized text. No
 * touch target in this screen is smaller than this.
 */
@Composable
private fun BigTagButton(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(18.dp),
        modifier = modifier.fillMaxHeight()
    ) {
        Text(text, fontSize = 19.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, color = Color.White)
    }
}
