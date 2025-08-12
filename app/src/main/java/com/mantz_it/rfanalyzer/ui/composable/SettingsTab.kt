package com.mantz_it.rfanalyzer.ui.composable

import android.net.Uri
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.LogcatLogger
import androidx.compose.ui.res.painterResource
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.ui.ColorTheme
import com.mantz_it.rfanalyzer.ui.RFAnalyzerTheme

/**
 * <h1>RF Analyzer - Settings Tab</h1>
 *
 * Module:      SettingsTab.kt
 * Description: A Tab in the Control Drawer. Contains general app settings.
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


enum class ScreenOrientation(val displayName: String) {
    AUTO("AUTO"),
    PORTRAIT("Portrait"),
    LANDSCAPE("Landscape"),
    REVERSE_PORTRAIT("Reverse Portrait"),
    REVERSE_LANDSCAPE("Reverse Landscape")
}

enum class FontSize(val displayName: String) {
    SMALL("Small"),
    NORMAL("Normal"),
    LARGE("Large")
}

enum class ControlDrawerSide(val displayName: String) {
    RIGHT("Right"),
    LEFT("Left"),
}

data class SettingsTabActions(
    val onScreenOrientationChanged: (ScreenOrientation) -> Unit,
    val onFontSizeChanged: (FontSize) -> Unit,
    val onColorThemeChanged: (ColorTheme) -> Unit,
    val onLongPressHelpEnabledChanged: (Boolean) -> Unit,
    val onControlDrawerSideChanged: (ControlDrawerSide) -> Unit,
    val onReverseTuningWheelChanged: (Boolean) -> Unit,
    val onRtlsdrAllowOutOfBoundFrequencyChanged: (Boolean) -> Unit,
    val onShowDebugInformationChanged: (Boolean) -> Unit,
    val onLoggingEnabledChanged: (Boolean) -> Unit,
    val onShowLogClicked: () -> Unit,
    val onSaveLogToFileClicked: (Uri) -> Unit,
    val onShareLogClicked: () -> Unit,
    val onDeleteLogClicked: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsTabComposable(
    screenOrientation: ScreenOrientation,
    fontSize: FontSize,
    colorTheme: ColorTheme,
    longPressHelpEnabled: Boolean,
    reverseTuningWheel: Boolean,
    controlDrawerSide: ControlDrawerSide,
    rtlsdrAllowOutOfBoundFrequency: Boolean,
    showDebugInformation: Boolean,
    loggingEnabled: Boolean,
    settingsTabActions: SettingsTabActions
) {
    val destinationFileChooser = letUserChooseDestinationFile(
        suggestedFileName = LogcatLogger.logfileName,
        mimeType = "text/plain",
        onAbort = { },
        onDestinationChosen = { destUri -> settingsTabActions.onSaveLogToFileClicked(destUri) })
    ScrollableColumnWithFadingEdge {
        OutlinedEnumDropDown(
            label = "Screen Orientation",
            selectedEnum = screenOrientation,
            enumClass = ScreenOrientation::class,
            getDisplayName = { it.displayName },
            onSelectionChanged = settingsTabActions.onScreenOrientationChanged,
            helpSubPath = "settings.html#screen-orientation"
        )
        OutlinedEnumDropDown(
            label = "App Color Theme",
            selectedEnum = colorTheme,
            enumClass = ColorTheme::class,
            getDisplayName = { it.displayName },
            onSelectionChanged = settingsTabActions.onColorThemeChanged,
            helpSubPath = "settings.html#app-color-theme"
        )
        OutlinedSteppedSlider(
            label = "Font Size (FFT Plot)",
            steps = FontSize.entries,
            selectedStepIndex = fontSize.ordinal,
            onSelectedStepIndexChanged = { settingsTabActions.onFontSizeChanged(FontSize.entries[it.toInt()]) },
            formatValue = { value -> value.displayName },
            helpSubPath = "settings.html#font-size-fft-plot"
        )
        OutlinedSwitch(
            label = "Context Help System",
            helpText = "If enabled, long pressing on the label of a UI element navigates to the user manual",
            isChecked = longPressHelpEnabled,
            onCheckedChange = settingsTabActions.onLongPressHelpEnabledChanged,
            helpSubPath = "settings.html#context-help-system"
        )
        OutlinedSwitch(
            label = "Reverse Tuning Wheel",
            helpText = "If enabled, the tuning wheel direction is reversed. This might feel more natural for some users.",
            isChecked = reverseTuningWheel,
            onCheckedChange = settingsTabActions.onReverseTuningWheelChanged,
            helpSubPath = "settings.html#reverse-tuning-wheel"
        )
        OutlinedEnumDropDown(
            label = "Control Drawer Alignment (Landscape)",
            selectedEnum = controlDrawerSide,
            enumClass = ControlDrawerSide::class,
            getDisplayName = { it.displayName },
            onSelectionChanged = settingsTabActions.onControlDrawerSideChanged,
            helpSubPath = "settings.html#control-drawer-alignment-landscape"
        )
        OutlinedSwitch(
            label = "Allow Out-of-Bound Frequency (RTL-SDR)",
            helpText = "Allow all frequency values for RTL-SDR, if if not originally supported by the Tuner (advanced)",
            isChecked = rtlsdrAllowOutOfBoundFrequency,
            onCheckedChange = settingsTabActions.onRtlsdrAllowOutOfBoundFrequencyChanged,
            helpSubPath = "settings.html#allow-out-of-bound-frequency-rtl-sdr"
        )
        OutlinedSwitch(
            label = "Show Debug Information",
            helpText = "Displays additional information in the FFT text area",
            isChecked = showDebugInformation,
            onCheckedChange = settingsTabActions.onShowDebugInformationChanged,
            helpSubPath = "settings.html#show-debug-information"
        )
        OutlinedSwitch(
            label = "Logging",
            helpText = "Logs the applications' commandline output (Logcat) to a file",
            isChecked = loggingEnabled,
            onCheckedChange = settingsTabActions.onLoggingEnabledChanged,
            helpSubPath = "settings.html#logging"
        ) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = settingsTabActions.onShowLogClicked,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(end = 3.dp)
                ) {
                    Text("View Log", modifier = Modifier.padding(horizontal = 6.dp))
                    Icon(
                        painter = painterResource(R.drawable.text_snippet),
                        contentDescription = "View log file"
                    )
                }
                Button(
                    onClick = destinationFileChooser,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(horizontal = 3.dp).weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.file_save),
                        contentDescription = "Save log file"
                    )
                }
                Button(
                    onClick = settingsTabActions.onShareLogClicked,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(horizontal = 3.dp).weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.Share,
                        contentDescription = "Share log file"
                    )
                }
                Button(
                    onClick = settingsTabActions.onDeleteLogClicked,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.weight(1f).padding(start = 3.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete log file"
                    )
                }
            }
        }
    }
}

@Composable
@Preview
fun SettingsTabPreview() {
    CompositionLocalProvider(LocalShowHelp provides {}) {
        RFAnalyzerTheme {
            SettingsTabComposable(
                screenOrientation = ScreenOrientation.AUTO,
                fontSize = FontSize.NORMAL,
                colorTheme = ColorTheme.RFANALYZER_DARK,
                longPressHelpEnabled = true,
                reverseTuningWheel = false,
                controlDrawerSide = ControlDrawerSide.RIGHT,
                rtlsdrAllowOutOfBoundFrequency = false,
                loggingEnabled = true,
                showDebugInformation = true,
                settingsTabActions = SettingsTabActions(
                    onScreenOrientationChanged = { },
                    onFontSizeChanged = { },
                    onColorThemeChanged = { },
                    onLongPressHelpEnabledChanged = { },
                    onReverseTuningWheelChanged = { },
                    onControlDrawerSideChanged = { },
                    onRtlsdrAllowOutOfBoundFrequencyChanged = { },
                    onShowDebugInformationChanged = { },
                    onLoggingEnabledChanged = { },
                    onShowLogClicked = { },
                    onSaveLogToFileClicked = { },
                    onShareLogClicked = { },
                    onDeleteLogClicked = { },
                ),
            )
        }
    }
}