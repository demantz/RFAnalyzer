package com.mantz_it.rfanalyzer.ui.tabs

import android.os.SystemClock
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Devices
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.ui.RFAnalyzerTheme
import kotlinx.coroutines.delay
import com.mantz_it.rfanalyzer.ui.composable.LocalShowHelp
import com.mantz_it.rfanalyzer.ui.composable.OutlinedBox
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSlider
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSwitch
import com.mantz_it.rfanalyzer.ui.composable.ScrollableColumnWithFadingEdge
import com.mantz_it.rfanalyzer.ui.composable.asSizeInBytesToString
import com.mantz_it.rfanalyzer.ui.composable.asStringWithUnit

/**
 * <h1>RF Analyzer - Recording Tab</h1>
 *
 * Module:      RecordingTab.kt
 * Description: A Tab in the Control Drawer. Contains all settings related to recording.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


enum class StopAfterUnit(val displayName: String) {
    NEVER("Disabled"),
    MB("MB"),
    GB("GB"),
    SEC("Seconds"),
    MIN("Minutes")
}

data class RecordingTabActions(
    val onNameChanged: (String) -> Unit,
    val onOnlyRecordWhenSquelchIsSatisfiedChanged: (Boolean) -> Unit,
    val onSquelchChanged: (Float) -> Unit,
    val onStopAfterThresholdChanged: (Int) -> Unit,
    val onStopAfterUnitChanged: (StopAfterUnit) -> Unit,
    val onStartRecordingClicked: () -> Unit,
    val onStartBasebandRecordingClicked: () -> Unit,
    val onViewRecordingsClicked: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingTabComposable(
    analyzerRunning: Boolean,
    recordingRunning: Boolean,
    basebandRecordingRunning: Boolean,
    demodulationActive: Boolean,
    name: String,
    frequency: Long,
    sampleRate: Long,
    onlyRecordWhenSquelchIsSatisfied: Boolean,
    squelchEnabled: Boolean,
    minSquelch: Float,
    maxSquelch: Float,
    squelch: Float,
    stopAfterThreshold: Int,
    stopAfterUnit: StopAfterUnit,
    currentRecordingFileSize: Long,
    recordingsTotalFileSize: Long,
    recordingStartedElapsedRealtime: Long,
    recordingTabActions: RecordingTabActions
) {
    var currentTime by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    // the running recording's duration
    LaunchedEffect(Unit) {
        while (true) {
            currentTime = SystemClock.elapsedRealtime()
            delay(500L)
        }
    }

    ScrollableColumnWithFadingEdge {
        Button(
            onClick = recordingTabActions.onViewRecordingsClicked,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier
                .fillMaxWidth()
                .height(55.dp)
                .padding(vertical = 3.dp)
        ) {
            Row {
                Icon(imageVector = Icons.Default.Menu, contentDescription = "View Recordings")
                Spacer(Modifier.width(8.dp))
                Text("View Recordings")
            }
        }
        Text("Frequency: ${frequency.asStringWithUnit("Hz")}     Bandwidth: ${sampleRate.asStringWithUnit("Sps")}",
            lineHeight = 13.sp,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        Row {
            Button(
                enabled = analyzerRunning && !basebandRecordingRunning,
                shape = MaterialTheme.shapes.small,
                onClick = recordingTabActions.onStartRecordingClicked,
                modifier = Modifier.weight(1f).padding(end=3.dp).height(55.dp).align(Alignment.CenterVertically)
            ) {
                if(recordingRunning) {
                    Text("Running (${(currentTime-recordingStartedElapsedRealtime)/1000}s)", modifier = Modifier.padding(horizontal = 6.dp))
                    Icon(
                        painter = painterResource(R.drawable.stop_circle),
                        contentDescription = "Stop",
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Text("Record Raw IQ", modifier = Modifier.padding(horizontal = 6.dp))
                    Icon(
                        painter = painterResource(R.drawable.circle),
                        contentDescription = "Start",
                        modifier = Modifier.padding(vertical = 6.dp),
                        tint = Color.Red
                    )
                }
            }
            Button(
                enabled = analyzerRunning && demodulationActive && !recordingRunning,
                shape = MaterialTheme.shapes.small,
                onClick = recordingTabActions.onStartBasebandRecordingClicked,
                modifier = Modifier.weight(1f).padding(start=3.dp).height(55.dp).align(Alignment.CenterVertically)
            ) {
                if(basebandRecordingRunning) {
                    Text("Running (${(currentTime-recordingStartedElapsedRealtime)/1000}s)", modifier = Modifier.padding(horizontal = 6.dp))
                    Icon(
                        painter = painterResource(R.drawable.stop_circle),
                        contentDescription = "Stop",
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Text("Record Baseband", modifier = Modifier.padding(horizontal = 6.dp))
                    Icon(
                        painter = painterResource(R.drawable.circle),
                        contentDescription = "Start",
                        modifier = Modifier.padding(vertical = 6.dp),
                        tint = Color.Red
                    )
                }
            }
        }
        TextField(
            value = name,
            onValueChange = recordingTabActions.onNameChanged,
            label = { Text("File Name") },
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
        )
        OutlinedSwitch(
            label = "Record based on Squelch",
            helpText = "Records only while the squelch is open, including the start of each signal and a short tail.",
            isChecked = squelchEnabled && onlyRecordWhenSquelchIsSatisfied,
            onCheckedChange = recordingTabActions.onOnlyRecordWhenSquelchIsSatisfiedChanged,
            enabled = squelchEnabled,
            helpSubPath = "recording.html#channel-squelch"
        ) {
            OutlinedSlider(
                label = "Squelch (above the noise)",
                unit = "dB",
                unitInLabel = false,
                minValue = minSquelch,
                maxValue = maxSquelch,
                value = squelch,
                onValueChanged = { value -> recordingTabActions.onSquelchChanged(value) },
                showOutline = false
            )
        }
        StopAfterDropDown(
            label = if (stopAfterUnit == StopAfterUnit.NEVER) "Automatic Recording Stop" else "Stop Recording after",
            selectedUnit = stopAfterUnit,
            selectedThreshold = stopAfterThreshold,
            onUnitChanged = recordingTabActions.onStopAfterUnitChanged,
            onThresholdChanged = recordingTabActions.onStopAfterThresholdChanged,
            enabled = !(recordingRunning || basebandRecordingRunning),
            helpSubPath = "recording.html#stop-recording-after",
            modifier = Modifier.fillMaxWidth()
        )
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("Recordings occupy ${(recordingsTotalFileSize+currentRecordingFileSize).asSizeInBytesToString()} of Device Storage",
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top=16.dp))
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopAfterDropDown(
    label: String,
    selectedUnit: StopAfterUnit,
    selectedThreshold: Int,
    onUnitChanged: (StopAfterUnit) -> Unit,
    onThresholdChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    helpSubPath: String? = null
) {
    var expandedState by remember { mutableStateOf(false) }
    val expanded = if (enabled) expandedState else false
    var textFieldValue by remember { mutableStateOf(TextFieldValue(selectedThreshold.toString())) }

    // Keep text in sync with state flow when not editing
    LaunchedEffect(selectedThreshold) {
        if (textFieldValue.text.toIntOrNull() != selectedThreshold) {
            textFieldValue = textFieldValue.copy(text = selectedThreshold.toString())
        }
    }
    OutlinedBox(
        label = label,
        modifier = modifier.height(75.dp),
        helpSubPath = helpSubPath
    ) {
        Row(modifier = Modifier.align(Alignment.Center)) {
            if (selectedUnit != StopAfterUnit.NEVER) {
                TextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        if (newValue.text.isEmpty()) {
                            // cannot remove the last char (empty string is not a valid threshold)
                            // just select the current value for the user to overwrite it:
                            textFieldValue = textFieldValue.copy(
                                selection = TextRange(0, textFieldValue.text.length)
                            )
                        } else if (newValue.text.length <= 3) {
                            newValue.text.toIntOrNull()?.let {
                                textFieldValue = newValue
                                onThresholdChanged(it)
                            }
                        }
                    },
                    enabled = enabled,
                    keyboardOptions = KeyboardOptions.Default.copy(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                    modifier = Modifier.width(100.dp).height(52.dp).align(Alignment.CenterVertically)
                )
            }
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = {
                    if (enabled) expandedState = !expandedState
                }) {

                Row(Modifier.fillMaxHeight().menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)) {
                    Text(
                        text = selectedUnit.displayName,
                        textAlign = TextAlign.End,
                        fontSize = 18.sp,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(top = 8.dp, bottom = 8.dp, end = 4.dp)
                            .defaultMinSize(80.dp)
                    )
                    ExposedDropdownMenuDefaults.TrailingIcon(
                        expanded = expanded,
                        modifier = Modifier.align(Alignment.CenterVertically)
                    )
                }
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expandedState = false }) {
                    StopAfterUnit::class.java.enumConstants?.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option.displayName) },
                            onClick = {
                                expandedState = false
                                onUnitChanged(option)
                            }
                        )
                    }
                }
            }
        }
    }
}



@Composable
@Preview(name = "PIXEL_4", device = Devices.PIXEL_4)
fun RecordingTabPreview() {
    RFAnalyzerTheme {
        CompositionLocalProvider(LocalShowHelp provides {}) {
            RecordingTabComposable(
                analyzerRunning = true,
                recordingRunning = false,
                basebandRecordingRunning = false,
                demodulationActive = true,
                name = "abc",
                frequency = 97000000,
                sampleRate = 1000000,
                onlyRecordWhenSquelchIsSatisfied = true,
                squelchEnabled = true,
                minSquelch = 0f,
                maxSquelch = 40f,
                squelch = 10f,
                stopAfterThreshold = 0,
                stopAfterUnit = StopAfterUnit.SEC,
                currentRecordingFileSize = 0,
                recordingsTotalFileSize = 0,
                recordingStartedElapsedRealtime = 0,
                recordingTabActions = RecordingTabActions(
                    onNameChanged = { },
                    onOnlyRecordWhenSquelchIsSatisfiedChanged = { },
                    onSquelchChanged = { },
                    onStopAfterThresholdChanged = { },
                    onStopAfterUnitChanged = { },
                    onStartRecordingClicked = { },
                    onStartBasebandRecordingClicked = { },
                    onViewRecordingsClicked = { }
                ),
            )
        }
    }
}
