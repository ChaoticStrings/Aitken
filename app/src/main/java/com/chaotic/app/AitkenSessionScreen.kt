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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aitken.recording.AitkenRecordingService
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

        if (!isRecording) {
            IdleControls(
                modifier = Modifier.weight(1f),
                onStart = {
                    context.startForegroundService(Intent(context, AitkenRecordingService::class.java))
                },
                onOpenSettings = { showSettings = true }
            )
        } else {
            MdGraph(modifier = Modifier.weight(0.42f).fillMaxWidth(), tunables = tunables)

            if (phaseLabel == "CALIBRATING") {
                val calibrationProgress by AitkenUiState.calibrationProgress
                CalibratingBanner(progress = calibrationProgress)
            } else {
                val lastTagResult by AitkenUiState.lastTagResult
                // Confidence indicator removed here -- Aitken-phase (ticket 13's
                // ClassifierRunner), not Luna. AitkenUiState.confidenceLabel stays
                // commented out below, not deleted, for ticket 13 to pick back up.
                Text(
                    lastTagResult ?: "Ride safe.",
                    color = Color(0xFFCFD8DC),
                    fontSize = 15.sp,
                    modifier = Modifier.fillMaxWidth()
                )
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

@Composable
private fun IdleControls(modifier: Modifier = Modifier, onStart: () -> Unit, onOpenSettings: () -> Unit) {
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
        Spacer(modifier = Modifier.height(28.dp))
        OutlinedButton(
            onClick = onOpenSettings,
            modifier = Modifier.fillMaxWidth().height(72.dp)
        ) {
            Text("SETTINGS", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * The M/D graph: a live scrolling waveform of vertical acceleration (M, as
 * a continuous signal) above a bar-strip of recent segment durations (D,
 * one bar per segment, colored by [severityOf]'s placeholder tiers). These
 * are deliberately two separate simple visuals rather than one combined
 * one — [AitkenUiState.waveform] stores raw values only, no timestamps, so
 * precisely aligning a segment's exact start/end against waveform pixels
 * isn't attempted here; that precision is the Workbench's job (ticket 15),
 * not this live glance-while-riding view. Once calibration has run, a
 * dashed band overlays the waveform at the calibrated threshold — an
 * approximation, since the real trigger is a rolling std, not a single-
 * sample amplitude (ticket 22).
 */
@Composable
private fun MdGraph(modifier: Modifier = Modifier, tunables: Tunables) {
    val waveform = AitkenUiState.waveform
    val recentSegments = AitkenUiState.recentSegments

    Column(modifier = modifier) {
        Canvas(modifier = Modifier.weight(0.72f).fillMaxWidth()) {
            val width = size.width
            val height = size.height
            val baseline = 9.81f // resting gravity magnitude -- see Verticalizer
            val centerY = height / 2f
            // 30 m/s^2 of deviation from baseline fills 42% of the half-height.
            // Not calibrated -- a reasonable placeholder range for what a
            // real bump should look like without clipping off-canvas.
            val scaleY = (height * 0.42f) / 30f

            drawLine(Color(0xFF37474F), Offset(0f, centerY), Offset(width, centerY), strokeWidth = 1.5f)
            for (i in 1..2) {
                val yTop = centerY - i * (height * 0.2f)
                val yBot = centerY + i * (height * 0.2f)
                drawLine(Color(0xFF263238), Offset(0f, yTop), Offset(width, yTop), strokeWidth = 1f)
                drawLine(Color(0xFF263238), Offset(0f, yBot), Offset(width, yBot), strokeWidth = 1f)
            }

            // Approximate reference only -- the real threshold is a rolling std
            // over a window, not a single-sample amplitude, so this dashed band
            // is "roughly where detection kicks in," not a precise boundary. See
            // ticket 22's note on why an exact line can't be drawn honestly.
            val threshold = AitkenUiState.calibratedThresholdM.value
            if (threshold != null) {
                val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f), 0f)
                val bandColor = Color(0xFF546E7A)
                val yTopBand = (centerY - threshold * scaleY).coerceIn(0f, height)
                val yBotBand = (centerY + threshold * scaleY).coerceIn(0f, height)
                drawLine(bandColor, Offset(0f, yTopBand), Offset(width, yTopBand), strokeWidth = 2f, pathEffect = dash)
                drawLine(bandColor, Offset(0f, yBotBand), Offset(width, yBotBand), strokeWidth = 2f, pathEffect = dash)
            }

            // Snapshot once, up front. `waveform` is written from the sensor
            // HandlerThread (AitkenUiState.pushSample) while this draw block
            // runs on the render thread; reading .indices/.size/[i] as three
            // separate live calls against the mutable list let a concurrent
            // add()+removeAt(0) shrink it between reads, throwing
            // IndexOutOfBoundsException at the cap boundary. Copying once
            // here means every access below sees one consistent list.
            val samples = waveform.toList()
            if (samples.size > 1) {
                val path = Path()
                val xStep = width / (AitkenUiState.MAX_WAVEFORM_SAMPLES - 1).toFloat()
                for (i in samples.indices) {
                    val x = width - (samples.size - 1 - i) * xStep
                    val y = (centerY - (samples[i] - baseline) * scaleY).coerceIn(0f, height)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = Color(0xFF4FC3F7),
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
 * Shown instead of the tag-result/confidence row while CALIBRATING (ticket
 * 22) -- explains what's happening and how much longer it'll take, since
 * the phase label alone ("● CALIBRATING") doesn't say either.
 */
@Composable
private fun CalibratingBanner(modifier: Modifier = Modifier, progress: Float) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            "Hold steady on smooth road — measuring your mount's baseline vibration",
            color = Color(0xFFCFD8DC),
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

@Composable
private fun TagButtons(
    modifier: Modifier = Modifier,
    context: Context,
    onOpenSettings: () -> Unit,
    tagsEnabled: Boolean = true
) {
    var rangeOpen by remember { mutableStateOf(false) }

    fun tap(label: String, kind: TagKind) {
        AitkenRecordingService.instance?.tag(kind, label)
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
            BigTagButton(
                text = if (rangeOpen) "END ROUGH\nSTRETCH" else "START ROUGH\nSTRETCH",
                color = if (rangeOpen) Color(0xFFEF5350) else Color(0xFF8D6E63),
                modifier = Modifier.weight(1f),
                enabled = tagsEnabled
            ) {
                tap("Rough stretch", if (rangeOpen) TagKind.RANGE_END else TagKind.RANGE_START)
                rangeOpen = !rangeOpen
            }
        }
        Row(modifier = Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.weight(1f).fillMaxHeight()
            ) {
                Text("SETTINGS", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Button(
                onClick = { context.stopService(Intent(context, AitkenRecordingService::class.java)) },
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
