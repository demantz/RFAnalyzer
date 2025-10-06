package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.source.AirspySource
import com.mantz_it.rfanalyzer.source.HydraSdrRfPort
import com.mantz_it.rfanalyzer.source.HydraSdrSource

/**
 * <h1>RF Analyzer - Source Tab</h1>
 *
 * Module:      SourceTab.kt
 * Description: The main Tab of the Control Drawer. Contains all Source-related
 * settings as well as the Start/Stop Button to start the Analyzer Service.
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


enum class SourceType(val displayName: String, val defaultSupportedSampleRates: List<Long>) {
    HACKRF("HACKRF", listOf(4000000, 6000000, 8000000, 10000000, 12500000, 16000000, 20000000)),
    RTLSDR("RTLSDR", listOf(1000000, 2000000, 2500000)),
    AIRSPY("AIRSPY", listOf(2400000, 2500000, 3000000, 5000000, 6000000, 10000000)),
    HYDRASDR("HYDRASDR", listOf(2500000, 5000000, 10000000)),
    FILESOURCE("File Source", listOf(0));
}

enum class FilesourceFileFormat(val displayName: String, val description: String, val bytesPerSample: Int) {
    HACKRF("HACKRF", "8-bit signed IQ (HackRF)", 2),
    RTLSDR("RTLSDR", "8-bit unsigned IQ (RTL-SDR)", 2),
    AIRSPY("AIRSPY", "16-bit signed IQ (AIRSPY)", 4),
    HYDRASDR("HYDRASDR", "16-bit signed IQ (HYDRASDR)", 4)
}

data class SourceTabActions(
    val onStartStopClicked: () -> Unit,
    val onSourceTypeChanged: (newType: SourceType) -> Unit,
    val onFrequencyChanged: (newFrequency: Long) -> Unit,
    val onSampleRateChanged: (newSampleRate: Long) -> Unit,
    val onAutomaticSampleRateAdjustmentChanged: (Boolean) -> Unit,
    val onHackrfVgaGainIndexChanged: (newIndex: Int) -> Unit,
    val onHackrfLnaGainIndexChanged: (newIndex: Int) -> Unit,
    val onHackrfAmplifierEnabledChanged: (Boolean) -> Unit,
    val onHackrfAntennaPowerEnabledChanged: (Boolean) -> Unit,
    val onHackrfConverterOffsetChanged: (newFrequency: Long) -> Unit,
    val onRtlsdrGainIndexChanged: (newIndex: Int) -> Unit,
    val onRtlsdrIFGainIndexChanged: (newIndex: Int) -> Unit,
    val onRtlsdrAgcEnabledChanged: (Boolean) -> Unit,
    val onRtlsdrManualGainEnabledChanged: (Boolean) -> Unit,
    val onRtlsdrExternalServerEnabledChanged: (Boolean) -> Unit,
    val onRtlsdrExternalServerIPChanged: (String) -> Unit,
    val onRtlsdrExternalServerPortChanged: (Int) -> Unit,
    val onFilesourceFileFormatChanged: (fileFormat: FilesourceFileFormat) -> Unit,
    val onRtlsdrFrequencyCorrectionChanged: (Int) -> Unit,
    val onRtlsdrConverterOffsetChanged: (newFrequency: Long) -> Unit,
    val onRtlsdrEnableBiasTChanged: (Boolean) -> Unit,
    val onAirspyAdvancedGainEnabledChanged: (newGain: Boolean) -> Unit,
    val onAirspyVgaGainChanged: (newGain: Int) -> Unit,
    val onAirspyLnaGainChanged: (newGain: Int) -> Unit,
    val onAirspyMixerGainChanged: (newGain: Int) -> Unit,
    val onAirspyLinearityGainChanged: (newGain: Int) -> Unit,
    val onAirspySensitivityGainChanged: (newGain: Int) -> Unit,
    val onAirspyRfBiasEnabledChanged: (newBias: Boolean) -> Unit,
    val onAirspyConverterOffsetChanged: (newFrequency: Long) -> Unit,
    val onHydraSdrAdvancedGainEnabledChanged: (newGain: Boolean) -> Unit,
    val onHydraSdrVgaGainChanged: (newGain: Int) -> Unit,
    val onHydraSdrLnaGainChanged: (newGain: Int) -> Unit,
    val onHydraSdrMixerGainChanged: (newGain: Int) -> Unit,
    val onHydraSdrLinearityGainChanged: (newGain: Int) -> Unit,
    val onHydraSdrSensitivityGainChanged: (newGain: Int) -> Unit,
    val onHydraSdrRfBiasEnabledChanged: (newBias: Boolean) -> Unit,
    val onHydraSdrRfPortChanged: (newPort: HydraSdrRfPort) -> Unit,
    val onHydraSdrConverterOffsetChanged: (newFrequency: Long) -> Unit,
    val onOpenFileClicked: () -> Unit,
    val onViewRecordingsClicked: () -> Unit,
    val onFilesourceRepeatChanged: (Boolean) -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceTabComposable(
    sourceType: SourceType,
    sourceName: String,
    analyzerRunning: Boolean,
    analyzerStartPending: Boolean,
    frequency: Long,
    sampleRate: Long,
    automaticSampleRateAdjustment: Boolean,
    minimumFrequency: Long,
    maximumFrequency: Long,
    supportedSampleRates: List<Long>,
    hackrfVgaGainSteps: List<Int>,
    hackrfVgaGainIndex: Int,
    hackrfLnaGainSteps: List<Int>,
    hackrfLnaGainIndex: Int,
    hackrfAmplifierEnabled: Boolean,
    hackrfAntennaPowerEnabled: Boolean,
    hackrfConverterOffset: Long,
    rtlsdrGainSteps: List<Int>,
    rtlsdrGainIndex: Int,
    rtlsdrIFGainSteps: List<Int>,
    rtlsdrIFGainIndex: Int,
    rtlsdrAgcEnabled: Boolean,
    rtlsdrManualGainEnabled: Boolean,
    rtlsdrExternalServerEnabled: Boolean,
    rtlsdrExternalServerIP: String,
    rtlsdrExternalServerPort: Int,
    rtlsdrFrequencyCorrection: Int,
    rtlsdrConverterOffset: Long,
    rtlsdrAllowOutOfBoundFrequency: Boolean,
    rtlsdrEnableBiasT: Boolean,
    airspyAdvancedGainEnabled: Boolean,
    airspyVgaGain: Int,
    airspyLnaGain: Int,
    airspyMixerGain: Int,
    airspyLinearityGain: Int,
    airspySensitivityGain: Int,
    airspyRfBiasEnabled: Boolean,
    airspyConverterOffset: Long,
    hydraSdrAdvancedGainEnabled: Boolean,
    hydraSdrVgaGain: Int,
    hydraSdrLnaGain: Int,
    hydraSdrMixerGain: Int,
    hydraSdrLinearityGain: Int,
    hydraSdrSensitivityGain: Int,
    hydraSdrRfBiasEnabled: Boolean,
    hydraSdrRfPort: HydraSdrRfPort,
    hydraSdrConverterOffset: Long,
    filesourceFilename: String,
    filesourceFileFormat: FilesourceFileFormat,
    filesourceRepeatEnabled: Boolean,
    sourceTabActions: SourceTabActions
) {
    ScrollableColumnWithFadingEdge {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedEnumDropDown(
                label = "Signal Source",
                selectedEnum = sourceType,
                enumClass = SourceType::class,
                getDisplayName = { it.displayName },
                onSelectionChanged = sourceTabActions.onSourceTypeChanged,
                enabled = !analyzerRunning,
                modifier = Modifier.weight(0.618f),
                helpSubPath = "sdr-source.html#selecting-a-source"
            )
            Button(
                onClick = sourceTabActions.onStartStopClicked,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .padding(start = 8.dp, end = 2.dp, top = 11.dp)
                    .height(60.dp)
                    .align(Alignment.CenterVertically)
                    .weight(0.382f)
            ) {
                if(analyzerRunning) {
                    Icon(
                        painter = painterResource(R.drawable.stop_circle),
                        contentDescription = "Stop",
                        modifier = Modifier.size(40.dp)
                    )
                } else if (analyzerStartPending) {
                    CircularProgressIndicator(color = Color.White)
                } else {
                    Icon(
                        painter = painterResource(R.drawable.play_circle),
                        contentDescription = "Start",
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
        when (sourceType) {
            SourceType.HACKRF -> {
                FrequencyChooser(
                    label = "Tune-Frequency",
                    unit = "Hz",
                    currentFrequency = frequency,
                    onFrequencyChanged = sourceTabActions.onFrequencyChanged,
                    minFrequency = minimumFrequency,
                    maxFrequency = if(maximumFrequency==0L) 10000000000L else maximumFrequency,
                    enabled = true,
                    helpSubPath = "sdr-source.html#tune-frequency"
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedSteppedSlider(
                        label = "VGA Gain",
                        unit = "dB",
                        steps = hackrfVgaGainSteps,
                        selectedStepIndex = hackrfVgaGainIndex,
                        onSelectedStepIndexChanged = sourceTabActions.onHackrfVgaGainIndexChanged,
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 3.dp),
                        helpSubPath = "sdr-source.html#vga-gain"
                    )
                    OutlinedSteppedSlider(
                        label = "LNA Gain",
                        unit = "dB",
                        steps = hackrfLnaGainSteps,
                        selectedStepIndex = hackrfLnaGainIndex,
                        onSelectedStepIndexChanged = sourceTabActions.onHackrfLnaGainIndexChanged,
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#lna-gain"
                    )
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedSwitch(
                        label = "Amplifier",
                        helpText = "Enables the HackRF's internal Amplifier",
                        isChecked = hackrfAmplifierEnabled,
                        onCheckedChange = sourceTabActions.onHackrfAmplifierEnabledChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 3.dp),
                        helpSubPath = "sdr-source.html#amplifier"
                    )
                    OutlinedSwitch(
                        label = "Antenna Port Power",
                        helpText = "Enables 3.3V power on the HackRF's antenna port",
                        isChecked = hackrfAntennaPowerEnabled,
                        onCheckedChange = sourceTabActions.onHackrfAntennaPowerEnabledChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#antenna-port-power"
                    )
                }
                Row {
                    OutlinedSwitch(
                        label = "Automatic Sample Rate",
                        helpText = "Sample rate follows zoom level",
                        isChecked = automaticSampleRateAdjustment,
                        onCheckedChange = sourceTabActions.onAutomaticSampleRateAdjustmentChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 3.dp),
                        helpSubPath = "sdr-source.html#sample-rate"
                    )
                    OutlinedListDropDown(
                        label = "Sample Rate",
                        getDisplayName = { it.asStringWithUnit("Sps") },
                        onSelectionChanged = sourceTabActions.onSampleRateChanged,
                        items = SourceType.HACKRF.defaultSupportedSampleRates,
                        selectedItem = sampleRate,
                        modifier = Modifier.weight(1f).padding(start = 3.dp),
                        enabled = !automaticSampleRateAdjustment,
                        helpSubPath = "sdr-source.html#sample-rate"
                    )
                }
                OutlinedIntegerTextField(
                    label = "Frequency Converter Offset (Hz)",
                    value = hackrfConverterOffset,
                    onValueChange = sourceTabActions.onHackrfConverterOffsetChanged,
                    helpSubPath = "sdr-source.html#frequency-converter-offset"
                )
            }
            SourceType.RTLSDR -> {
                FrequencyChooser(
                    label = "Tune-Frequency",
                    unit = "Hz",
                    currentFrequency = frequency,
                    onFrequencyChanged = sourceTabActions.onFrequencyChanged,
                    minFrequency = if (rtlsdrAllowOutOfBoundFrequency) 0 else minimumFrequency,
                    maxFrequency = if(rtlsdrAllowOutOfBoundFrequency || maximumFrequency==0L) 10000000000L else maximumFrequency,
                    enabled = true,
                    helpSubPath = "sdr-source.html#tune-frequency"
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedSwitch(
                        label = "Hardware AGC",
                        helpText = "Automatic Gain Control",  // add to manual: only recommended for very low power signals
                        isChecked = rtlsdrAgcEnabled,
                        onCheckedChange = sourceTabActions.onRtlsdrAgcEnabledChanged,
                        helpSubPath = "sdr-source.html#hardware-agc",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 3.dp)
                    )
                    OutlinedSwitch(
                        label = "Manual Gain",
                        helpText = "Set gain levels manually",
                        isChecked = rtlsdrManualGainEnabled,
                        onCheckedChange = sourceTabActions.onRtlsdrManualGainEnabledChanged,
                        helpSubPath = "sdr-source.html#manual-gain",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 3.dp)
                    )
                }
                if(rtlsdrManualGainEnabled) {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedSteppedSlider(
                            label = "Gain",
                            unit = "dB",
                            steps = rtlsdrGainSteps,
                            selectedStepIndex = rtlsdrGainIndex,
                            formatValue = { (it/10.0).toString() },  // step values are in 1/10 dB steps
                            helpSubPath = "sdr-source.html#gain-controls",
                            onSelectedStepIndexChanged = sourceTabActions.onRtlsdrGainIndexChanged,
                            modifier = Modifier
                                .weight(1f)
                                .padding(end = if (rtlsdrIFGainSteps.size > 1) 3.dp else 0.dp)
                        )
                        if(rtlsdrIFGainSteps.size > 1) {
                            OutlinedSteppedSlider(
                                label = "IF Gain",
                                unit = "dB",
                                steps = rtlsdrIFGainSteps,
                                selectedStepIndex = rtlsdrIFGainIndex,
                                formatValue = { (it/10.0).toString() },
                                onSelectedStepIndexChanged = sourceTabActions.onRtlsdrIFGainIndexChanged,
                                helpSubPath = "sdr-source.html#if-gain",
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 3.dp)
                            )
                        }
                    }
                }
                Row {
                    OutlinedSwitch(
                        label = "Automatic Sample Rate",
                        helpText = "Sample rate follows zoom level",
                        isChecked = automaticSampleRateAdjustment,
                        onCheckedChange = sourceTabActions.onAutomaticSampleRateAdjustmentChanged,
                        helpSubPath = "sdr-source.html#sample-rate",
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 3.dp)
                    )
                    OutlinedListDropDown(
                        label = "Sample Rate",
                        getDisplayName = { it.asStringWithUnit("Sps") },
                        onSelectionChanged = sourceTabActions.onSampleRateChanged,
                        items = SourceType.RTLSDR.defaultSupportedSampleRates,
                        selectedItem = sampleRate,
                        helpSubPath = "sdr-source.html#sample-rate",
                        modifier = Modifier.weight(1f).padding(start = 3.dp),
                        enabled = !automaticSampleRateAdjustment
                    )
                }
                OutlinedSwitch(
                    enabled = !analyzerRunning,
                    label = "External Server (rtl_tcp)",
                    helpText = "Connect to external RTL-SDR server instead of USB device",
                    isChecked = rtlsdrExternalServerEnabled,
                    onCheckedChange = sourceTabActions.onRtlsdrExternalServerEnabledChanged,
                    helpSubPath = "sdr-source.html#external-rtl-sdr-server"
                ) {
                    Row {
                        TextField(
                            enabled = !analyzerRunning,
                            value = rtlsdrExternalServerIP,
                            onValueChange = sourceTabActions.onRtlsdrExternalServerIPChanged,
                            label = { Text("DNS Name / IP Address") },
                            modifier = Modifier.weight(0.75f)
                        )
                        TextField(
                            enabled = !analyzerRunning,
                            value = rtlsdrExternalServerPort.toString(),
                            onValueChange = { newValue ->
                                val value = newValue.toIntOrNull()
                                if(value != null)
                                    sourceTabActions.onRtlsdrExternalServerPortChanged(
                                        value.coerceIn(0, 65535) // Max Port Range
                                    ) },
                            label = { Text("Port") },
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .weight(0.25f)
                        )
                    }
                }
                Row {
                    OutlinedIntegerTextField(
                        label = "Frequency Correction (ppm)",
                        value = rtlsdrFrequencyCorrection.toLong(),
                        onValueChange = { sourceTabActions.onRtlsdrFrequencyCorrectionChanged(it.toInt())},
                        modifier = Modifier.weight(1f).padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#frequency-correction"
                    )
                    OutlinedIntegerTextField(
                        label = "Frequency Offset (Hz)",
                        value = rtlsdrConverterOffset,
                        onValueChange = sourceTabActions.onRtlsdrConverterOffsetChanged,
                        modifier = Modifier.weight(1f).padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#frequency-converter-offset"
                    )
                }
                OutlinedSwitch(
                    label = "Antenna Port Power (only Blog v4)",
                    helpText = "Enables the 4.5V bias tee voltage on the antenna port of the RTL-SDR Blog v4",
                    isChecked = rtlsdrEnableBiasT,
                    onCheckedChange = sourceTabActions.onRtlsdrEnableBiasTChanged,
                    enabled = !analyzerRunning,
                    helpSubPath = "sdr-source.html#antenna-port-power"
                )
            }
            SourceType.AIRSPY -> {
                FrequencyChooser(
                    label = "Tune-Frequency",
                    unit = "Hz",
                    currentFrequency = frequency,
                    onFrequencyChanged = sourceTabActions.onFrequencyChanged,
                    minFrequency = AirspySource.MIN_FREQUENCY,
                    maxFrequency = AirspySource.MAX_FREQUENCY,
                    helpSubPath = "sdr-source.html#tune-frequency"
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedSwitch(
                        label = "Advanced Gain Control",
                        helpText = "Set VGA, LNA and Mixer Gain directly",
                        isChecked = airspyAdvancedGainEnabled,
                        onCheckedChange = sourceTabActions.onAirspyAdvancedGainEnabledChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#advanced-gain-control"
                    )
                    OutlinedSwitch(
                        label = "Antenna Port Power",
                        helpText = "Enables 4.5V bias power on the SDR's antenna port",
                        isChecked = airspyRfBiasEnabled,
                        onCheckedChange = sourceTabActions.onAirspyRfBiasEnabledChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#antenna-port-power"
                    )
                }
                if (airspyAdvancedGainEnabled) {
                    OutlinedSlider(
                        label = "VGA Gain",
                        unit = "dB",
                        value = airspyVgaGain.toFloat(),
                        decimalPlaces = 0,
                        onValueChanged = { sourceTabActions.onAirspyVgaGainChanged(it.toInt()) },
                        minValue = AirspySource.MIN_VGA_GAIN.toFloat(),
                        maxValue = AirspySource.MAX_VGA_GAIN.toFloat(),
                        helpSubPath = "sdr-source.html#vga-gain"
                    )
                    OutlinedSlider(
                        label = "LNA Gain",
                        unit = "dB",
                        value = airspyLnaGain.toFloat(),
                        decimalPlaces = 0,
                        onValueChanged = { sourceTabActions.onAirspyLnaGainChanged(it.toInt()) },
                        minValue = AirspySource.MIN_LNA_GAIN.toFloat(),
                        maxValue = AirspySource.MAX_LNA_GAIN.toFloat(),
                        helpSubPath = "sdr-source.html#lna-gain"
                    )
                    OutlinedSlider(
                        label = "Mixer Gain",
                        unit = "dB",
                        value = airspyMixerGain.toFloat(),
                        decimalPlaces = 0,
                        onValueChanged = { sourceTabActions.onAirspyMixerGainChanged(it.toInt()) },
                        minValue = AirspySource.MIN_MIXER_GAIN.toFloat(),
                        maxValue = AirspySource.MAX_MIXER_GAIN.toFloat(),
                        helpSubPath = "sdr-source.html#mixer-gain"
                    )
                } else {
                    OutlinedSlider(
                        label = "Linearity Gain",
                        unit = "dB",
                        value = airspyLinearityGain.toFloat(),
                        decimalPlaces = 0,
                        onValueChanged = { sourceTabActions.onAirspyLinearityGainChanged(it.toInt()) },
                        minValue = AirspySource.MIN_LINEARITY_GAIN.toFloat(),
                        maxValue = AirspySource.MAX_LINEARITY_GAIN.toFloat(),
                        helpSubPath = "sdr-source.html#advanced-gain-control"
                    )
                    OutlinedSlider(
                        label = "Sensitivity Gain",
                        unit = "dB",
                        value = airspySensitivityGain.toFloat(),
                        decimalPlaces = 0,
                        onValueChanged = { sourceTabActions.onAirspySensitivityGainChanged(it.toInt()) },
                        minValue = AirspySource.MIN_SENSITIVITY_GAIN.toFloat(),
                        maxValue = AirspySource.MAX_SENSITIVITY_GAIN.toFloat(),
                        helpSubPath = "sdr-source.html#advanced-gain-control"
                    )
                }
                Row {
                    OutlinedSwitch(
                        label = "Automatic Sample Rate",
                        helpText = "Sample rate follows zoom level",
                        isChecked = automaticSampleRateAdjustment,
                        onCheckedChange = sourceTabActions.onAutomaticSampleRateAdjustmentChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 3.dp),
                        helpSubPath = "sdr-source.html#sample-rate"
                    )
                    OutlinedListDropDown(
                        label = "Sample Rate",
                        getDisplayName = { it.asStringWithUnit("Sps") },
                        onSelectionChanged = sourceTabActions.onSampleRateChanged,
                        items = supportedSampleRates,
                        selectedItem = sampleRate,
                        modifier = Modifier.weight(1f).padding(start = 3.dp),
                        enabled = !automaticSampleRateAdjustment,
                        helpSubPath = "sdr-source.html#sample-rate"
                    )
                }
                OutlinedIntegerTextField(
                    label = "Frequency Converter Offset (Hz)",
                    value = airspyConverterOffset,
                    onValueChange = sourceTabActions.onAirspyConverterOffsetChanged,
                    helpSubPath = "sdr-source.html#frequency-converter-offset"
                )
            }
            SourceType.HYDRASDR -> {
                FrequencyChooser(
                    label = "Tune-Frequency",
                    unit = "Hz",
                    currentFrequency = frequency,
                    onFrequencyChanged = sourceTabActions.onFrequencyChanged,
                    minFrequency = HydraSdrSource.MIN_FREQUENCY,
                    maxFrequency = HydraSdrSource.MAX_FREQUENCY,
                    helpSubPath = "sdr-source.html#tune-frequency"
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedSwitch(
                        label = "Advanced Gain Control",
                        helpText = "Set VGA, LNA and Mixer Gain directly",
                        isChecked = hydraSdrAdvancedGainEnabled,
                        onCheckedChange = sourceTabActions.onHydraSdrAdvancedGainEnabledChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#advanced-gain-control_1"
                    )
                    OutlinedSwitch(
                        label = "Antenna Port Power",
                        helpText = "Enables 4.5V bias power on the SDR's antenna port",
                        isChecked = hydraSdrRfBiasEnabled,
                        onCheckedChange = sourceTabActions.onHydraSdrRfBiasEnabledChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#antenna-port-power"
                    )
                }
                if (hydraSdrAdvancedGainEnabled) {
                    OutlinedSlider(
                        label = "VGA Gain",
                        unit = "dB",
                        value = hydraSdrVgaGain.toFloat(),
                        decimalPlaces = 0,
                        onValueChanged = { sourceTabActions.onHydraSdrVgaGainChanged(it.toInt()) },
                        minValue = HydraSdrSource.MIN_VGA_GAIN.toFloat(),
                        maxValue = HydraSdrSource.MAX_VGA_GAIN.toFloat(),
                        helpSubPath = "sdr-source.html#vga-gain"
                    )
                    OutlinedSlider(
                        label = "LNA Gain",
                        unit = "dB",
                        value = hydraSdrLnaGain.toFloat(),
                        decimalPlaces = 0,
                        onValueChanged = { sourceTabActions.onHydraSdrLnaGainChanged(it.toInt()) },
                        minValue = HydraSdrSource.MIN_LNA_GAIN.toFloat(),
                        maxValue = HydraSdrSource.MAX_LNA_GAIN.toFloat(),
                        helpSubPath = "sdr-source.html#lna-gain"
                    )
                    OutlinedSlider(
                        label = "Mixer Gain",
                        unit = "dB",
                        value = hydraSdrMixerGain.toFloat(),
                        decimalPlaces = 0,
                        onValueChanged = { sourceTabActions.onHydraSdrMixerGainChanged(it.toInt()) },
                        minValue = HydraSdrSource.MIN_MIXER_GAIN.toFloat(),
                        maxValue = HydraSdrSource.MAX_MIXER_GAIN.toFloat(),
                        helpSubPath = "sdr-source.html#mixer-gain"
                    )
                } else {
                    OutlinedSlider(
                        label = "Linearity Gain",
                        unit = "dB",
                        value = hydraSdrLinearityGain.toFloat(),
                        decimalPlaces = 0,
                        onValueChanged = { sourceTabActions.onHydraSdrLinearityGainChanged(it.toInt()) },
                        minValue = HydraSdrSource.MIN_LINEARITY_GAIN.toFloat(),
                        maxValue = HydraSdrSource.MAX_LINEARITY_GAIN.toFloat(),
                        helpSubPath = "sdr-source.html#advanced-gain-control_1"
                    )
                    OutlinedSlider(
                        label = "Sensitivity Gain",
                        unit = "dB",
                        value = hydraSdrSensitivityGain.toFloat(),
                        decimalPlaces = 0,
                        onValueChanged = { sourceTabActions.onHydraSdrSensitivityGainChanged(it.toInt()) },
                        minValue = HydraSdrSource.MIN_SENSITIVITY_GAIN.toFloat(),
                        maxValue = HydraSdrSource.MAX_SENSITIVITY_GAIN.toFloat(),
                        helpSubPath = "sdr-source.html#advanced-gain-control_1"
                    )
                }
                OutlinedEnumDropDown(
                    label = "RF Port",
                    selectedEnum = hydraSdrRfPort,
                    enumClass = HydraSdrRfPort::class,
                    getDisplayName = { it.displayName },
                    onSelectionChanged = sourceTabActions.onHydraSdrRfPortChanged,
                    helpSubPath = "sdr-source.html#rf-port",
                )
                Row {
                    OutlinedSwitch(
                        label = "Automatic Sample Rate",
                        helpText = "Sample rate follows zoom level",
                        isChecked = automaticSampleRateAdjustment,
                        onCheckedChange = sourceTabActions.onAutomaticSampleRateAdjustmentChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 3.dp),
                        helpSubPath = "sdr-source.html#sample-rate"
                    )
                    OutlinedListDropDown(
                        label = "Sample Rate",
                        getDisplayName = { it.asStringWithUnit("Sps") },
                        onSelectionChanged = sourceTabActions.onSampleRateChanged,
                        items = supportedSampleRates,
                        selectedItem = sampleRate,
                        modifier = Modifier.weight(1f).padding(start = 3.dp),
                        enabled = !automaticSampleRateAdjustment,
                        helpSubPath = "sdr-source.html#sample-rate"
                    )
                }
                OutlinedIntegerTextField(
                    label = "Frequency Converter Offset (Hz)",
                    value = hydraSdrConverterOffset,
                    onValueChange = sourceTabActions.onHydraSdrConverterOffsetChanged,
                    helpSubPath = "sdr-source.html#frequency-converter-offset"
                )
            }
            SourceType.FILESOURCE -> {
                Text("File: " + filesourceFilename.ifEmpty { "(no File selected)" }, fontSize = 12.sp, lineHeight = 13.sp)
                Row {
                    Button(
                        onClick = sourceTabActions.onOpenFileClicked,
                        shape = MaterialTheme.shapes.small,
                        enabled = !analyzerRunning,
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .align(Alignment.CenterVertically)
                    ) {
                        Text("Open File", modifier = Modifier.padding(horizontal = 10.dp))
                        Icon(painter = painterResource(R.drawable.folder_open), "Open IQ File")
                    }
                    Button(
                        onClick = sourceTabActions.onViewRecordingsClicked,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .padding(horizontal = 6.dp)
                            .weight(1f)
                    ) {
                        Row {
                            Text("View Recordings", modifier = Modifier.padding(horizontal = 16.dp))
                            Icon(imageVector = Icons.Default.Menu, contentDescription = "View Recordings")
                        }
                    }
                }
                FrequencyChooser(
                    label = "Tune-Frequency",
                    unit = "Hz",
                    currentFrequency = frequency,
                    onFrequencyChanged = sourceTabActions.onFrequencyChanged,
                    minFrequency = 0,
                    maxFrequency = 10000000000L,
                    enabled = !analyzerRunning,
                    helpSubPath = "sdr-source.html#tune-frequency_2"
                )
                FrequencyChooser(
                    label = "Sample Rate",
                    unit = "Sps",
                    digitCount = 9,
                    currentFrequency = sampleRate,
                    onFrequencyChanged = sourceTabActions.onSampleRateChanged,
                    enabled = !analyzerRunning,
                    helpSubPath = "sdr-source.html#sample-rate_2"
                )
                Row {
                    OutlinedEnumDropDown(
                        label = "File Format",
                        selectedEnum = filesourceFileFormat,
                        enumClass = FilesourceFileFormat::class,
                        getDisplayName = { it.displayName },
                        onSelectionChanged = sourceTabActions.onFilesourceFileFormatChanged,
                        enabled = !analyzerRunning,
                        helpSubPath = "sdr-source.html#file-format",
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 3.dp)
                    )
                    OutlinedSwitch(
                        label = "Repeat",
                        helpText = "Automatically rewind file after reaching the end",
                        isChecked = filesourceRepeatEnabled,
                        onCheckedChange = sourceTabActions.onFilesourceRepeatChanged,
                        helpSubPath = "sdr-source.html#repeat",
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
@Preview
fun SourceTabPreview() {
    CompositionLocalProvider(LocalShowHelp provides {}) {
        SourceTabComposable(
            sourceType = SourceType.HYDRASDR,
            sourceName = "Test-Source",
            analyzerRunning = true,
            analyzerStartPending = false,
            frequency = 30000000L,
            sampleRate = 1000000,
            automaticSampleRateAdjustment = true,
            minimumFrequency = 100000,
            maximumFrequency = 1000000000,
            supportedSampleRates = listOf(1000000),
            hackrfVgaGainSteps = listOf(0, 1, 2, 4, 8, 12, 26, 100),
            hackrfVgaGainIndex = 2,
            hackrfLnaGainSteps = listOf(0, 1, 2, 4, 8, 12, 26, 100),
            hackrfLnaGainIndex = 5,
            rtlsdrGainSteps = listOf(0, 1, 2, 4, 8, 12, 26, 100),
            rtlsdrGainIndex = 3,
            rtlsdrIFGainSteps = listOf(0),
            rtlsdrIFGainIndex = 0,
            hackrfAmplifierEnabled = false,
            hackrfAntennaPowerEnabled = false,
            hackrfConverterOffset = 0L,
            rtlsdrAgcEnabled = false,
            rtlsdrManualGainEnabled = true,
            rtlsdrConverterOffset = 0L,
            filesourceFilename = "20250101_test_HACKRF_100MHz_2Msps.iq",
            filesourceFileFormat = FilesourceFileFormat.HACKRF,
            filesourceRepeatEnabled = true,
            rtlsdrExternalServerEnabled = true,
            rtlsdrExternalServerIP = "",
            rtlsdrExternalServerPort = 1234,
            rtlsdrFrequencyCorrection = 0,
            rtlsdrAllowOutOfBoundFrequency = false,
            rtlsdrEnableBiasT = false,
            airspyAdvancedGainEnabled = false,
            airspyVgaGain = 2,
            airspyLnaGain = 12,
            airspyMixerGain = 0,
            airspyLinearityGain = 0,
            airspySensitivityGain = 0,
            airspyRfBiasEnabled = true,
            airspyConverterOffset = 0L,
            hydraSdrAdvancedGainEnabled = false,
            hydraSdrVgaGain = 2,
            hydraSdrLnaGain = 12,
            hydraSdrMixerGain = 0,
            hydraSdrLinearityGain = 0,
            hydraSdrSensitivityGain = 0,
            hydraSdrRfBiasEnabled = true,
            hydraSdrRfPort = HydraSdrRfPort.RX0,
            hydraSdrConverterOffset = 0L,
            sourceTabActions = SourceTabActions(
                onSourceTypeChanged = { },
                onStartStopClicked = { },
                onFrequencyChanged = { },
                onSampleRateChanged = { },
                onAutomaticSampleRateAdjustmentChanged = { },
                onHackrfVgaGainIndexChanged = {},
                onHackrfLnaGainIndexChanged = {},
                onRtlsdrGainIndexChanged = {},
                onRtlsdrIFGainIndexChanged = {},
                onHackrfAmplifierEnabledChanged = {},
                onHackrfAntennaPowerEnabledChanged = {},
                onHackrfConverterOffsetChanged = {},
                onRtlsdrAgcEnabledChanged = {},
                onRtlsdrManualGainEnabledChanged = {},
                onFilesourceFileFormatChanged = {},
                onRtlsdrConverterOffsetChanged = {},
                onOpenFileClicked = { },
                onViewRecordingsClicked = { },
                onFilesourceRepeatChanged = {},
                onRtlsdrExternalServerEnabledChanged = { },
                onRtlsdrExternalServerIPChanged = { },
                onRtlsdrExternalServerPortChanged = { },
                onRtlsdrFrequencyCorrectionChanged = { },
                onRtlsdrEnableBiasTChanged = { },
                onAirspyRfBiasEnabledChanged = { },
                onAirspyAdvancedGainEnabledChanged = { },
                onAirspyLnaGainChanged = { },
                onAirspyVgaGainChanged = { },
                onAirspyMixerGainChanged = { },
                onAirspyLinearityGainChanged = { },
                onAirspySensitivityGainChanged = { },
                onAirspyConverterOffsetChanged = { },
                onHydraSdrAdvancedGainEnabledChanged = { },
                onHydraSdrRfBiasEnabledChanged = { },
                onHydraSdrRfPortChanged = { },
                onHydraSdrLnaGainChanged = { },
                onHydraSdrVgaGainChanged = { },
                onHydraSdrMixerGainChanged = { },
                onHydraSdrLinearityGainChanged = { },
                onHydraSdrSensitivityGainChanged = { },
                onHydraSdrConverterOffsetChanged = { },
            ),
        )
    }
}