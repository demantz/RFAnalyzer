package com.mantz_it.rfanalyzer.ui.tabs

import androidx.compose.animation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.RangeSlider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.analyzer.FftProcessor.Companion.MAX_FFT_SIZE
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.VERTICAL_SCALE_LOWER_BOUNDARY
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.VERTICAL_SCALE_UPPER_BOUNDARY
import kotlin.math.roundToInt
import com.mantz_it.rfanalyzer.ui.composable.LocalShowHelp
import com.mantz_it.rfanalyzer.ui.composable.OutlinedBox
import com.mantz_it.rfanalyzer.ui.composable.OutlinedEnumDropDown
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSlider
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSteppedSlider
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSwitch
import com.mantz_it.rfanalyzer.ui.composable.ScrollableColumnWithFadingEdge

/**
 * <h1>RF Analyzer - Display Tab</h1>
 *
 * Module:      DisplayTab.kt
 * Description: A Tab in the Control Drawer. Contains all settings related to the drawing of the FFT
 *              and Waterfall
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


enum class FftColorMap(val displayName: String) {
    GQRX("GQRX"),
    JET("JET"),
    TURBO("TURBO"),
}

enum class FftDrawingType(val displayName: String) {
    BAR("BAR"),
    LINE("LINE")
}

data class DisplayTabActions(
    val onVerticalScaleChanged: (Float, Float) -> Unit,
    val onAutoscaleClicked: () -> Unit,
    val onResetScalingClicked: () -> Unit,
    val onFftSizeChanged: (Int) -> Unit,
    val onAverageLengthChanged: (Int) -> Unit,
    val onPeakHoldEnabledChanged: (Boolean) -> Unit,
    val onMaxFrameRateChanged: (Int) -> Unit,
    val onColorMapChanged: (FftColorMap) -> Unit,
    val onDrawingTypeChanged: (FftDrawingType) -> Unit,
    val onWaterfallFpsChanged: (Int) -> Unit,
    val onRelativeFrequencyEnabledChanged: (Boolean) -> Unit,
    val onFftWaterfallRatioChanged: (Float) -> Unit,
    val onFftPaddingRatioChanged: (Float) -> Unit,
    val onManualFftSizeEnabledChanged: (Boolean) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplayTabComposable(
    viewportVerticalScaleMin: Float,
    viewportVerticalScaleMax: Float,
    fftSize: Int,
    averageLength: Int,
    peakHold: Boolean,
    maxFrameRate: Int,
    colorMap: FftColorMap,
    drawingType: FftDrawingType,
    waterfallFps: Int,
    relativeFrequency: Boolean,
    fftWaterfallRatio: Float,
    fftPaddingRatio: Float,
    manualFftSizeEnabled: Boolean,
    displayTabActions: DisplayTabActions,
) {
    val fftSizeSteps = generateSequence(1) { it * 2 }
        .dropWhile { it < 1024 }
        .takeWhile { it <= MAX_FFT_SIZE }
        .toList()
    ScrollableColumnWithFadingEdge {
        OutlinedBox(
            label = "Scale of Vertical Axis (in dBFS)",
            helpSubPath = "fft.html#scale-of-vertical-axis"
        ) {
            Column {
                Row {
                    Text(
                        text = "${viewportVerticalScaleMin.toInt()}",
                        fontSize = 20.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .width(5.dp + 15.dp*3)
                            .padding(horizontal = 4.dp)
                    )
                    // Consumes the slider's drag so an ancestor doesn't drag along (the side drawer in
                    // landscape); the RangeSlider is the only control that needs it.
                    fun Modifier.stopParentDrag(): Modifier = pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown() // don’t require unconsumed; let the slider still work
                            do {
                                val event = awaitPointerEvent()
                                // Mark everything as consumed so ancestors won't treat it as a drag
                                event.changes.forEach { it.consume() }
                            } while (event.changes.any { it.pressed })
                        }
                    }
                    RangeSlider(
                        value = viewportVerticalScaleMin..viewportVerticalScaleMax,
                        onValueChange = { displayTabActions.onVerticalScaleChanged(it.start, it.endInclusive) },
                        valueRange = VERTICAL_SCALE_LOWER_BOUNDARY..VERTICAL_SCALE_UPPER_BOUNDARY,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 10.dp)
                            .stopParentDrag()  // else the side drawer (landscape) also takes the drag
                    )
                    Text(
                        text = "${viewportVerticalScaleMax.toInt()}",
                        fontSize = 20.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .width(5.dp + 15.dp*3)
                            .padding(horizontal = 4.dp)
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = displayTabActions.onAutoscaleClicked,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(start = 10.dp, end = 3.dp).weight(1f)
                    ) {
                        Icon(painter = painterResource(R.drawable.settings_overscan), contentDescription = "Vertical Autoscale")
                        Text("Autoscale", modifier = Modifier.padding(horizontal = 10.dp))
                    }
                    Button(
                        onClick = displayTabActions.onResetScalingClicked,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(start = 3.dp, end = 10.dp).weight(1f)
                    ) {
                        Text("Reset Scale", modifier = Modifier.padding(horizontal = 10.dp))
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedSlider(
                label = "Averaging",
                unit = "frames",
                minValue = 0f,
                maxValue = 30f,
                value = averageLength.toFloat(),
                decimalPlaces = 0,
                onValueChanged = { value -> displayTabActions.onAverageLengthChanged(value.roundToInt()) },
                modifier = Modifier.weight(1f).padding(end = 3.dp),
                helpSubPath = "fft.html#averaging"
            )
            OutlinedSlider(
                label = "Max Frame Rate",
                unit = "fps",
                minValue = 1f,
                maxValue = 60f,
                value = maxFrameRate.toFloat(),
                onValueChanged = { value -> displayTabActions.onMaxFrameRateChanged(value.roundToInt()) },
                modifier = Modifier.weight(1f).padding(start = 3.dp),
                helpSubPath = "fft.html#max-frame-rate"
            )
        }
        OutlinedSwitch(
            label = "Manual FFT Size",
            helpText = "Manually control FFT size and padding instead of choosing them automatically based on the sample rate",
            isChecked = manualFftSizeEnabled,
            onCheckedChange = displayTabActions.onManualFftSizeEnabledChanged,
            helpSubPath = "fft.html#manual-fft-size"
        )
        AnimatedVisibility(
            visible = manualFftSizeEnabled,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            Column {
                OutlinedSteppedSlider(
                    label = "FFT Size",
                    unit = "",
                    steps = fftSizeSteps,
                    selectedStepIndex = if(fftSizeSteps.indexOf(fftSize) >= 0) fftSizeSteps.indexOf(fftSize) else fftSizeSteps.size/2,
                    onSelectedStepIndexChanged = { idx -> displayTabActions.onFftSizeChanged(fftSizeSteps[idx]) },
                    helpSubPath = "fft.html#fft-size"
                )
                OutlinedSlider(
                    label = "FFT Padding Ratio",
                    unit = "%",
                    minValue = 0f,
                    maxValue = 75f,
                    value = fftPaddingRatio * 100f,
                    onValueChanged = { displayTabActions.onFftPaddingRatioChanged(it / 100f) },
                    decimalPlaces = 0,
                    helpSubPath = "fft.html#fft-padding-ratio"
                )
            }
        }
        OutlinedSlider(
            label = "Waterfall Speed",
            unit = "fps",
            minValue = 1f,
            maxValue = 400f,
            value = waterfallFps.toFloat(),
            onValueChanged = { displayTabActions.onWaterfallFpsChanged(it.toInt()) },
            decimalPlaces = 0,
            helpSubPath = "fft.html#waterfall-speed"
        )
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            OutlinedSwitch(
                label = "Peak Hold",
                helpText = "Show dot indicators for the highest signal strength",
                isChecked = peakHold,
                onCheckedChange = displayTabActions.onPeakHoldEnabledChanged,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 3.dp),
                helpSubPath = "fft.html#peak-hold"
            )
            OutlinedSwitch(
                label = "Relative Frequency",
                helpText = "Display frequencies relative to the center frequency",
                isChecked = relativeFrequency,
                onCheckedChange = displayTabActions.onRelativeFrequencyEnabledChanged,
                modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 3.dp),
                helpSubPath = "fft.html#relative-frequency"
            )
        }
        OutlinedSlider(
            label = "Spectrum/Waterfall Ratio",
            unit = "",
            minValue = 0.1f,
            maxValue = 0.9f,
            value = fftWaterfallRatio,
            onValueChanged = displayTabActions.onFftWaterfallRatioChanged,
            decimalPlaces = 2,
            unitInLabel = false,
            helpSubPath = "fft.html#spectrumwaterfall-ratio"
        )
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedEnumDropDown(
                label = "Waterfall Color Map",
                selectedEnum = colorMap,
                enumClass = FftColorMap::class,
                getDisplayName = { it.displayName },
                onSelectionChanged = displayTabActions.onColorMapChanged,
                modifier = Modifier.weight(1f).padding(end = 3.dp),
                helpSubPath = "fft.html#waterfall-color-map"
            )
            OutlinedEnumDropDown(
                label = "FFT Drawing Type",
                selectedEnum = drawingType,
                enumClass = FftDrawingType::class,
                getDisplayName = { it.displayName },
                onSelectionChanged = displayTabActions.onDrawingTypeChanged,
                modifier = Modifier.weight(1f).padding(start = 3.dp),
                helpSubPath = "fft.html#fft-drawing-type"
            )
        }
    }
}


@Composable
@Preview
fun DisplayTabPreview() {
    CompositionLocalProvider(LocalShowHelp provides {}) {
        DisplayTabComposable(
            viewportVerticalScaleMin = -60f,
            viewportVerticalScaleMax = -40f,
            fftSize = 4096,
            averageLength = 0,
            peakHold = true,
            maxFrameRate = 30,
            colorMap = FftColorMap.GQRX,
            drawingType = FftDrawingType.LINE,
            waterfallFps = 23,
            relativeFrequency = false,
            fftWaterfallRatio = 0.5f,
            fftPaddingRatio = 0f,
            manualFftSizeEnabled = true,
            DisplayTabActions(
                onVerticalScaleChanged = { _, _ -> },
                onAutoscaleClicked = {},
                onResetScalingClicked = {},
                onFftSizeChanged = {},
                onAverageLengthChanged = {},
                onPeakHoldEnabledChanged = {},
                onMaxFrameRateChanged = {},
                onColorMapChanged = {},
                onDrawingTypeChanged = {},
                onWaterfallFpsChanged = {},
                onRelativeFrequencyEnabledChanged = {},
                onFftWaterfallRatioChanged = { },
                onFftPaddingRatioChanged = {},
                onManualFftSizeEnabledChanged = {},
            ),
        )
    }
}
