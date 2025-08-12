package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * <h1>RF Analyzer - Demodulation Tab</h1>
 *
 * Module:      DemodulationTab.kt
 * Description: A Tab in the Control Drawer. Contains all settings related to
 * signal demodulation.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */


enum class DemodulationMode(val displayName: String, val minChannelWidth: Int, val maxChannelWidth: Int, val defaultChannelWidth: Int, val tuneStepDistance: Int) {
    OFF("OFF", 0, 50000, 0, 0),  //dummy channel width values; equals largest channel width (=WFM)
    AM("AM", 3000, 15000, 8000, 1000),
    NFM("FM (narrow)", 3000, 15000, 10000, 1000),
    WFM("FM (wide)", 30000, 125000, 65000, 100000),
    LSB("LSB", 1500, 5000, 2800, 100),
    USB("USB", 1500, 5000, 2800, 100),
    CW("CW", 150, 800, 300, 50),
    //DIGITAL("Digital Mode", 0, 50000, 0)  //dummy channel width values; equals largest channel width (=WFM)
}

data class DemodulationTabActions(
    val onDemodulationModeChanged: (DemodulationMode) -> Unit,
    val onChannelFrequencyChanged: (Long) -> Unit,
    val onTunerWheelDelta: (Float) -> Unit,
    val onChannelWidthChanged: (Int) -> Unit,
    val onSquelchEnabledChanged: (Boolean) -> Unit,
    val onSquelchChanged: (Float) -> Unit,
    val onKeepChannelCenteredChanged: (Boolean) -> Unit,
    val onZoomChanged: (Float) -> Unit,
    val onAudioMuteClicked: () -> Unit,
    val onAudioVolumeLevelChanged: (Float) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemodulationTabComposable(
    demodulationEnabled: Boolean,
    demodulationMode: DemodulationMode,
    channelFrequency: Long,
    channelWidth: Int,
    squelchEnabled: Boolean,
    minSquelch: Float,
    maxSquelch: Float,
    squelch: Float,
    keepChannelCentered: Boolean,
    viewportZoom: Float,
    audioVolumeLevel: Float,
    audioMuted: Boolean,
    demodulationTabActions: DemodulationTabActions
) {
    var showVolumeSlider by remember { mutableStateOf(false) }
    var volumeSliderTouched by remember { mutableStateOf(false) }

    ScrollableColumnWithFadingEdge {
        Row {
            OutlinedBox(label = "Audio Volume",
                helpSubPath = "demodulation.html#audio-controls",
                modifier = Modifier.padding(end = 6.dp)
            ) {
                Row(modifier = Modifier.align(Alignment.Center)) {
                    IconButton(onClick = demodulationTabActions.onAudioMuteClicked) {
                        Icon(
                            imageVector = if (audioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                            tint = MaterialTheme.colorScheme.primary,
                            contentDescription = "Mute toggle"
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clickable {
                                showVolumeSlider = !showVolumeSlider
                                volumeSliderTouched = false
                            }
                            .padding(6.dp)
                            .align(Alignment.CenterVertically)
                    ) {
                        Text(
                            text = "${(audioVolumeLevel * 100).toInt()}%",
                            fontSize = 20.sp,
                            textAlign = TextAlign.End,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier.width(50.dp)
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Show volume slider",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    AnimatedVisibility(
                        visible = showVolumeSlider,
                        enter = fadeIn() + expandHorizontally(),
                        exit = fadeOut() + shrinkHorizontally()
                    ) {
                        Slider(
                            value = audioVolumeLevel,
                            onValueChange = {
                                volumeSliderTouched = true  // user is interacting, prevent auto-dismiss
                                demodulationTabActions.onAudioVolumeLevelChanged(it)
                            },
                            onValueChangeFinished = { showVolumeSlider = false },
                            valueRange = 0f..1f,
                            modifier = Modifier.padding(end = 10.dp).fillMaxWidth()
                        )
                    }
                    // hide volume slider after 3 seconds of not being used:
                    LaunchedEffect(showVolumeSlider) {
                        if (showVolumeSlider) {
                            delay(3000)
                            if (!volumeSliderTouched) {
                                showVolumeSlider = false
                            }
                        }
                    }

                }
            }
            AnimatedVisibility(
                visible = !showVolumeSlider,
                enter = fadeIn() + expandHorizontally(),
                exit = fadeOut() + shrinkHorizontally(),
                modifier = Modifier.weight(1f)
            ) {
                OutlinedEnumDropDown(
                    label = "Demodulation Mode",
                    selectedEnum = demodulationMode,
                    enumClass = DemodulationMode::class,
                    getDisplayName = { it.displayName },
                    onSelectionChanged = demodulationTabActions.onDemodulationModeChanged,
                    helpSubPath = "demodulation.html#demodulation-mode",
                    modifier = Modifier.height(DEFAULT_MIN_BOX_HEIGHT)
                )
            }
        }
        if (demodulationEnabled) {
            Row {
                FrequencyChooser(
                    label = "Channel Frequency",
                    unit = "Hz",
                    currentFrequency = channelFrequency,
                    onFrequencyChanged = demodulationTabActions.onChannelFrequencyChanged,
                    helpSubPath = "demodulation.html#channel-frequency",
                    modifier = Modifier.weight(1f)
                )
            }
            TunerWheel(
                onRotationEvent = demodulationTabActions.onTunerWheelDelta,
                sensitivity = if (demodulationMode.tuneStepDistance > 1000) 0.1f else 0.4f
            )
            OutlinedBox(label = "Zoom", helpSubPath = "demodulation.html#zoom-and-bandwidth") {
                Slider(
                    value = reverseSaturationFunction(y=viewportZoom, a=3.5f, k=0.15f),
                    onValueChange = { demodulationTabActions.onZoomChanged(saturationFunction(x=it, a=3.5f, k=0.15f)) },
                    valueRange = when(demodulationMode) {
                        DemodulationMode.OFF -> 0f..0.995f
                        DemodulationMode.AM, DemodulationMode.NFM -> 0f..0.7f
                        DemodulationMode.WFM -> 0f..0.45f
                        DemodulationMode.LSB, DemodulationMode.USB -> 0f..0.95f
                        DemodulationMode.CW -> 0f..0.997f
                    },
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }
            OutlinedSwitch(
                label = "Squelch",
                helpText = "Enable Squelch to set a threshold for the signal level and cut out noise. Signals below the squelch threshold are muted.",
                isChecked = squelchEnabled,
                onCheckedChange = demodulationTabActions.onSquelchEnabledChanged,
                helpSubPath = "demodulation.html#squelch-control",
            ) {
                OutlinedSlider(
                    label = "Squelch",
                    unit = "dB",
                    unitInLabel = false,
                    minValue = minSquelch,
                    maxValue = maxSquelch,
                    value = squelch,
                    onValueChanged = { value -> demodulationTabActions.onSquelchChanged(value) },
                    showOutline = false
                )
            }
            OutlinedSlider(
                label = "Channel Bandwidth",
                unit = "kHz",
                minValue = demodulationMode.minChannelWidth.toFloat()/1000,
                maxValue = demodulationMode.maxChannelWidth.toFloat()/1000,
                value = channelWidth.toFloat()/1000,
                decimalPlaces = 1,
                onValueChanged = { value -> demodulationTabActions.onChannelWidthChanged((value*1000).roundToInt()) },
                helpSubPath = "demodulation.html#zoom-and-bandwidth",
            )
            OutlinedSwitch(
                label = "Keep Channel Centered",
                helpText = "Always center the viewport around the channel selector",
                isChecked = keepChannelCentered,
                helpSubPath = "demodulation.html#keep-channel-centered",
                onCheckedChange = demodulationTabActions.onKeepChannelCenteredChanged
            )
        }
    }
}

@Composable
fun TunerWheel(
    modifier: Modifier = Modifier,
    onRotationEvent: (Float) -> Unit,
    height: Dp = 70.dp,
    sensitivity: Float = 0.5f,   // how aggressive/sensitive does the wheel react to input
    enableHapticFeedback: Boolean = true
) {
    val borderColor = MaterialTheme.colorScheme.secondary
    val animatedRotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }
    var wheelWidthPx by remember { mutableStateOf(1f) } // avoid divide-by-zero

    val haptic = LocalHapticFeedback.current

    // Throttled rotation updates
    var lastUpdateTime by remember { mutableStateOf(0L) }
    var lastUpdateValue by remember { mutableStateOf(0f) }
    LaunchedEffect(animatedRotation.value) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime > 25) {
            val delta = (animatedRotation.value - lastUpdateValue) * -1f
            val deltaWithSensitivity = delta * sensitivity
            if (abs(deltaWithSensitivity) >= 1f) {
                if (enableHapticFeedback)
                    haptic.performHapticFeedback(hapticFeedbackType = HapticFeedbackType.TextHandleMove)
                onRotationEvent(deltaWithSensitivity)
                lastUpdateTime = currentTime
                lastUpdateValue = animatedRotation.value
            }
        }
    }

    Box(modifier = modifier
            .height(height)
            .onSizeChanged { size ->
                wheelWidthPx = size.width.toFloat()
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    // stop fling when user touches the wheel
                    onPress = {
                        scope.launch {
                            animatedRotation.snapTo(animatedRotation.value)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                        //velocityTracker.resetTracking()
                    },
                    onDrag = { change, dragAmount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch {
                            animatedRotation.snapTo(animatedRotation.value + dragAmount.x * -0.2f)
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity().x
                        if (abs(velocity) > 1000f) {
                            scope.launch {
                                animatedRotation.animateTo(
                                    animatedRotation.value + velocity * -0.1f,
                                    animationSpec = tween(800, easing = LinearEasing)
                                )
                            }
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = size / 2f
            val wheelWidth = size.width * 0.9f

            // Draw outer frame
            drawRoundRect(
                color = Color.Black,
                cornerRadius = CornerRadius(20f, 20f),
                size = size
            )

            // Draw border
            drawRoundRect(
                color = borderColor,
                cornerRadius = CornerRadius(20f, 20f),
                size = size,
                style = Stroke(width = 4f)
            )

            // Draw wheel
            val resolution = 50
            val points = List(resolution) { i ->
                val angle = (i * Math.PI / resolution).toFloat()
                Pair(
                    center.width - cos(angle) * wheelWidth / 2,  // x coordinate
                    cos(angle).pow(2) * size.height * 0.05f      // y offset (wheel gets smaller at the edges)
                )
            }
            val path = Path().apply {
                moveTo(points.first().first, points.first().second)
                points.forEach { lineTo(it.first, center.height * 0.2f + it.second) }
                points.reversed().forEach { lineTo(it.first, center.height * 1.8f - it.second) }
                close()
            }
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, Color.LightGray, Color.Black),
                    startX = 0f,
                    endX = size.width
                )
            )

            // Draw ticks
            val numberOfTicks = 15
            repeat(numberOfTicks) { i ->
                val angle = (i * 2 * Math.PI / numberOfTicks).toFloat() + animatedRotation.value * 0.01f
                val angleNorm = (angle % Math.PI).toFloat().let { if (it < 0) it + Math.PI.toFloat() else it }
                val x = cos(angleNorm) * center.width * 0.9f         // x coordinate
                val y = cos(angleNorm).pow(2) * size.height * 0.05f  // y offset (ticks get smaller at the edges)
                val tickWidth = 1f + (1f-cos(angleNorm).pow(2))*5f   // ticks get thinner at the edges

                val lineStart = Offset(center.width + x, center.height * 0.2f + y)
                val lineEnd = Offset(center.width + x, center.height * 1.8f - y)

                drawLine(color = Color.LightGray, start = lineStart, end = lineEnd, strokeWidth = tickWidth)
                drawLine(color = Color.DarkGray, start = lineStart + Offset(tickWidth, 0f), end = lineEnd + Offset(tickWidth, 0f), strokeWidth = tickWidth*2)
            }
        }
    }
}

@Composable
@Preview
fun DemodulationTabPreview() {
    CompositionLocalProvider(LocalShowHelp provides {}) {
        DemodulationTabComposable(
            demodulationEnabled = true,
            demodulationMode = DemodulationMode.USB,
            channelFrequency = 14000000,
            channelWidth = 50000,
            squelchEnabled = true,
            minSquelch = -50f,
            maxSquelch = -10f,
            squelch = -20f,
            keepChannelCentered = false,
            viewportZoom = 0.9f,
            audioVolumeLevel = 0.72f,
            audioMuted = false,
            demodulationTabActions = DemodulationTabActions(
                onDemodulationModeChanged = {},
                onChannelFrequencyChanged = {},
                onTunerWheelDelta = {},
                onChannelWidthChanged = {},
                onSquelchEnabledChanged = { },
                onSquelchChanged = { },
                onKeepChannelCenteredChanged = { },
                onZoomChanged = { },
                onAudioMuteClicked = { },
                onAudioVolumeLevelChanged = { },
            ),
        )
    }
}