package com.aitken.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every tunable from [Tunables], as a large slider with a descriptive
 * label and a big current-value readout — meant to be usable one-handed,
 * on a shaking phone mount, at a stop before or during a ride. Slider
 * tracks get extra vertical padding beyond Material3's default so a
 * mis-tap from vibration doesn't register on the wrong control, and every
 * value is quantized to a coarse step count rather than continuous drag —
 * precision isn't the point here, "roughly right, definitely not a
 * mis-tap" is.
 *
 * Changes only take effect on the *next* START SESSION, matching
 * Prototype 1's own settings convention — never mid-ride.
 */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val current = remember { SettingsStore.load(context) }

    var calibrationSec by remember { mutableFloatStateOf(current.calibrationDurationMs / 1000f) }
    var stdFactor by remember { mutableFloatStateOf(current.stdFactor) }
    var floorStd by remember { mutableFloatStateOf(current.floorStd) }
    var endQuietMs by remember { mutableFloatStateOf(current.endQuietMs.toFloat()) }
    var minSegmentMs by remember { mutableFloatStateOf(current.minSegmentDurationMs.toFloat()) }
    var turnThreshold by remember { mutableFloatStateOf(current.turnYawThresholdRadS) }
    var mildDeviation by remember { mutableFloatStateOf(current.mildSeverityDeviation) }
    var moderateDeviation by remember { mutableFloatStateOf(current.moderateSeverityDeviation) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1116))
            .systemBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text("SETTINGS", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(
            "Applies on next START SESSION — never changes a ride already in progress.",
            color = Color(0xFF90A4AE),
            fontSize = 14.sp
        )

        BigSlider(
            label = "Calibration duration",
            help = "How long Aitken measures quiet-road baseline noise before it starts " +
                "detecting bumps. Longer = more reliable baseline, shorter = riding sooner.",
            value = calibrationSec,
            onValueChange = { calibrationSec = it },
            valueRange = 5f..30f,
            steps = 24, // 1-second increments
            valueLabel = "${calibrationSec.toInt()}s"
        )

        BigSlider(
            label = "Detection sensitivity",
            help = "Multiplier on the measured baseline noise. Lower = catches smaller " +
                "bumps but more false triggers; higher = only strong jolts count.",
            value = stdFactor,
            onValueChange = { stdFactor = it },
            valueRange = 1.5f..6f,
            steps = 17, // 0.25 increments
            valueLabel = "×%.2f".format(stdFactor)
        )

        BigSlider(
            label = "Noise floor",
            help = "Absolute minimum threshold, regardless of sensitivity above — " +
                "prevents a freakishly smooth calibration from making every sample trigger.",
            value = floorStd,
            onValueChange = { floorStd = it },
            valueRange = 0.01f..0.5f,
            steps = 48,
            valueLabel = "%.2f".format(floorStd)
        )

        BigSlider(
            label = "Quiet time to close a segment",
            help = "How long the road must read smooth again before a detected bump/" +
                "stretch is considered finished. Shorter = tighter segments, longer = " +
                "less likely to split one bumpy stretch into several.",
            value = endQuietMs,
            onValueChange = { endQuietMs = it },
            valueRange = 100f..2000f,
            steps = 18, // 100ms increments
            valueLabel = "${endQuietMs.toInt()}ms"
        )

        BigSlider(
            label = "Shortest segment kept",
            help = "Detected segments shorter than this are discarded silently — " +
                "filters out single-sample sensor noise spikes.",
            value = minSegmentMs,
            onValueChange = { minSegmentMs = it },
            valueRange = 10f..200f,
            steps = 18,
            valueLabel = "${minSegmentMs.toInt()}ms"
        )

        BigSlider(
            label = "Turn sensitivity",
            help = "Yaw rate above this is treated as \"turning\" and suppresses new " +
                "bump detection starting mid-corner (an already-detected bump keeps " +
                "recording through the turn regardless).",
            value = turnThreshold,
            onValueChange = { turnThreshold = it },
            valueRange = 0.3f..3f,
            steps = 26,
            valueLabel = "%.2f rad/s".format(turnThreshold)
        )

        BigSlider(
            label = "Graph color: mild cutoff",
            help = "On the session screen's graph, a segment below this deviation from " +
                "resting gravity shows green.",
            value = mildDeviation,
            onValueChange = { mildDeviation = it },
            valueRange = 1f..20f,
            steps = 18,
            valueLabel = "%.1f m/s²".format(mildDeviation)
        )

        BigSlider(
            label = "Graph color: severe cutoff",
            help = "At or above this deviation, a segment shows red instead of amber. " +
                "Should be set higher than the mild cutoff above, or the amber tier " +
                "effectively disappears — not enforced automatically, so double check " +
                "both values before saving.",
            value = moderateDeviation,
            onValueChange = { moderateDeviation = it },
            valueRange = 1f..40f,
            steps = 38,
            valueLabel = "%.1f m/s²".format(moderateDeviation)
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    SettingsStore.save(
                        context,
                        Tunables(
                            calibrationDurationMs = (calibrationSec * 1000).toLong(),
                            stdFactor = stdFactor,
                            floorStd = floorStd,
                            endQuietMs = endQuietMs.toLong(),
                            minSegmentDurationMs = minSegmentMs.toLong(),
                            turnYawThresholdRadS = turnThreshold,
                            mildSeverityDeviation = mildDeviation,
                            moderateSeverityDeviation = moderateDeviation
                        )
                    )
                    onBack()
                },
                modifier = Modifier.weight(1f).height(64.dp)
            ) {
                Text("SAVE", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.weight(1f).height(64.dp)
            ) {
                Text("BACK", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BigSlider(
    label: String,
    help: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String
) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(valueLabel, color = Color(0xFF4FC3F7), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
        Text(help, color = Color(0xFF90A4AE), fontSize = 13.sp)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFF4FC3F7),
                activeTrackColor = Color(0xFF4FC3F7),
                inactiveTrackColor = Color(0xFF263238)
            ),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        )
    }
}
