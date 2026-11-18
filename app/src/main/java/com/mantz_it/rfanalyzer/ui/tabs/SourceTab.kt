package com.mantz_it.rfanalyzer.ui.tabs

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.R
import com.mantz_it.libhydrasdr.HydraSdrDeviceInfo
import com.mantz_it.rfanalyzer.ui.composable.OutlinedBox
import com.mantz_it.rfanalyzer.source.AirspyGainPreset
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SegmentedButton
import com.mantz_it.libhydrasdr.HydraSdrGainCategory
import com.mantz_it.libhydrasdr.HydraSdrGainType
import com.mantz_it.libhydrasdr.HydraSdrRfPortDescriptor
import com.mantz_it.rfanalyzer.database.MapCameraPosition
import com.mantz_it.rfanalyzer.location.MapHome
import com.mantz_it.rfanalyzer.network.KiwiSdrPublicEntry
import com.mantz_it.rfanalyzer.source.AirspyHfSource
import com.mantz_it.rfanalyzer.source.AirspySource
import com.mantz_it.rfanalyzer.source.HydraSdrRfPort
import com.mantz_it.rfanalyzer.source.HydraSdrSource
import com.mantz_it.rfanalyzer.source.KiwiSdrSource
import com.mantz_it.rfanalyzer.ui.composable.FrequencyChooser
import com.mantz_it.rfanalyzer.ui.composable.KiwiSdrPickerDialog
import com.mantz_it.rfanalyzer.ui.composable.asStringWithUnit
import com.mantz_it.rfanalyzer.ui.composable.LocalShowHelp
import com.mantz_it.rfanalyzer.ui.composable.OutlinedEnumDropDown
import com.mantz_it.rfanalyzer.ui.composable.OutlinedListDropDown
import com.mantz_it.rfanalyzer.ui.composable.OutlinedLongTextField
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSlider
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSteppedSlider
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSwitch
import com.mantz_it.rfanalyzer.ui.composable.ScrollableColumnWithFadingEdge
import com.mantz_it.rfanalyzer.ui.composable.rememberOpenFilePicker

/**
 * <h1>RF Analyzer - Source Tab</h1>
 *
 * Module:      SourceTab.kt
 * Description: The main Tab of the Control Drawer. Contains all Source-related settings as well as
 *              the Start/Stop Button to start the Analyzer Service.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

enum class SourceType(val displayName: String, val defaultSupportedSampleRates: List<Long>) {
    HACKRF("HACKRF", listOf(4000000, 6000000, 8000000, 10000000, 12500000, 16000000, 20000000)),
    RTLSDR("RTLSDR", listOf(1000000, 1400000, 1800000, 2000000, 2400000, 2500000)), // RtlsdrSource.OPTIMAL_SAMPLE_RATES
    AIRSPY("AIRSPY", listOf(2400000, 2500000, 3000000, 5000000, 6000000, 10000000)),
    AIRSPYHF("AIRSPYHF", listOf(192000, 256000, 384000, 768000)),
    HYDRASDR("HYDRASDR", listOf(2500000, 5000000, 10000000)),
    KIWISDR("KiwiSDR (web)", listOf(12000, 20000)),
    FILESOURCE("File Source", listOf(0));

    /** Whether the received band has a DC spike at its centre, so a retune keeps the channel off it.
     *  A KiwiSDR's stream has none - and only a +-5 kHz passband, so an offset would push one side of
     *  the channel (LSB, the top of FT8's 3 kHz, an AM sideband) out of it. */
    val hasDcSpike: Boolean get() = this != KIWISDR
}

enum class FilesourceFileFormat(val displayName: String, val description: String, val bytesPerSample: Int) {
    HACKRF("HACKRF", "8-bit signed IQ (HackRF)", 2),
    RTLSDR("RTLSDR", "8-bit unsigned IQ (RTL-SDR)", 2),
    AIRSPY("AIRSPY", "16-bit signed IQ (AIRSPY)", 4),
    AIRSPYHF("AIRSPYHF", "32-bit float IQ (AIRSPYHF)", 8),
    HYDRASDR("HYDRASDR", "16-bit signed IQ (HYDRASDR)", 4)
}

// File extension that reflects the raw sample encoding of a recording (independent of which
// hardware produced it, since AIRSPY and HYDRASDR share the same 16-bit signed byte layout).
fun FilesourceFileFormat.fileExtension(): String = when (this) {
    FilesourceFileFormat.HACKRF -> "cs8"
    FilesourceFileFormat.RTLSDR -> "cu8"
    FilesourceFileFormat.AIRSPY, FilesourceFileFormat.HYDRASDR -> "cs16"
    FilesourceFileFormat.AIRSPYHF -> "cf32"
}

enum class RtlsdrDirectSamplingMode(val displayName: String, val intValue: Int) {
    OFF("Off", 0),
    I("I-Branch", 1),
    Q("Q-Branch", 2)
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
    val onRtlsdrDirectSamplingModeChanged: (RtlsdrDirectSamplingMode) -> Unit,
    val onAirspyAdvancedGainEnabledChanged: (newGain: Boolean) -> Unit,
    val onAirspyVgaGainChanged: (newGain: Int) -> Unit,
    val onAirspyLnaGainChanged: (newGain: Int) -> Unit,
    val onAirspyMixerGainChanged: (newGain: Int) -> Unit,
    val onAirspyLinearityGainChanged: (newGain: Int) -> Unit,
    val onAirspySensitivityGainChanged: (newGain: Int) -> Unit,
    val onAirspyGainPresetChanged: (newPreset: AirspyGainPreset) -> Unit,
    val onAirspyRfBiasEnabledChanged: (newBias: Boolean) -> Unit,
    val onAirspyConverterOffsetChanged: (newFrequency: Long) -> Unit,
    val onAirspyHfAgcEnabledChanged: (enabled: Boolean) -> Unit,
    val onAirspyHfAgcThresholdChanged: (high: Boolean) -> Unit,
    val onAirspyHfAttenuationChanged: (attenuation: Int) -> Unit,
    val onAirspyHfLnaEnabledChanged: (enabled: Boolean) -> Unit,
    val onAirspyHfConverterOffsetChanged: (newFrequency: Long) -> Unit,
    val onHydraSdrAdvancedGainEnabledChanged: (newGain: Boolean) -> Unit,
    val onHydraSdrVgaGainChanged: (newGain: Int) -> Unit,
    val onHydraSdrLnaGainChanged: (newGain: Int) -> Unit,
    val onHydraSdrMixerGainChanged: (newGain: Int) -> Unit,
    val onHydraSdrLinearityGainChanged: (newGain: Int) -> Unit,
    val onHydraSdrSensitivityGainChanged: (newGain: Int) -> Unit,
    val onHydraSdrGainPresetChanged: (newPreset: HydraSdrGainType) -> Unit,
    val onHydraSdrRfBiasEnabledChanged: (newBias: Boolean) -> Unit,
    val onHydraSdrRfPortIndexChanged: (newPortIndex: Int) -> Unit,
    val onHydraSdrConverterOffsetChanged: (newFrequency: Long) -> Unit,
    val onKiwiSdrHostChanged: (String) -> Unit,
    val onKiwiSdrPortChanged: (Int) -> Unit,
    val onKiwiSdrPasswordChanged: (String) -> Unit,
    val onKiwiSdrUseTlsChanged: (Boolean) -> Unit,
    val onKiwiSdrPublicListRequested: (forceRefresh: Boolean) -> Unit,
    val onKiwiSdrPublicReceiverSelected: (KiwiSdrPublicEntry) -> Unit,
    val onOpenFileChosen: (uri: Uri, filename: String?) -> Unit,
    val onViewRecordingsClicked: () -> Unit,
    val onFilesourceRepeatChanged: (Boolean) -> Unit,
    val onKiwiSdrMapCameraPositionChanged: (MapCameraPosition) -> Unit,
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
    rtlsdrEnableBiasT: Boolean,
    rtlsdrDirectSamplingMode: RtlsdrDirectSamplingMode,
    airspyAdvancedGainEnabled: Boolean,
    airspyVgaGain: Int,
    airspyLnaGain: Int,
    airspyMixerGain: Int,
    airspyLinearityGain: Int,
    airspySensitivityGain: Int,
    airspyGainPreset: AirspyGainPreset,
    airspyRfBiasEnabled: Boolean,
    airspyConverterOffset: Long,
    airspyHfAgcEnabled: Boolean,
    airspyHfAgcThreshold: Boolean,
    airspyHfAttenuation: Int,
    airspyHfLnaEnabled: Boolean,
    airspyHfConverterOffset: Long,
    hydraSdrAdvancedGainEnabled: Boolean,
    hydraSdrVgaGain: Int,
    hydraSdrLnaGain: Int,
    hydraSdrMixerGain: Int,
    hydraSdrLinearityGain: Int,
    hydraSdrSensitivityGain: Int,
    hydraSdrGainPreset: HydraSdrGainType,
    hydraSdrRfBiasEnabled: Boolean,
    hydraSdrRfPortIndex: Int,
    hydraSdrConverterOffset: Long,
    hydraSdrDeviceInfo: HydraSdrDeviceInfo?,
    kiwiSdrHost: String,
    kiwiSdrPort: Int,
    kiwiSdrPassword: String,
    kiwiSdrUseTls: Boolean,
    kiwiSdrPublicListEntries: List<KiwiSdrPublicEntry>,
    kiwiSdrPublicListLoading: Boolean,
    kiwiSdrPublicListError: String?,
    filesourceFilename: String,
    filesourceFileFormat: FilesourceFileFormat,
    filesourceRepeatEnabled: Boolean,
    kiwiSdrMapCameraPosition: MapCameraPosition,
    /** Where the receiver map starts before the user moved it - see [MapHome]. */
    mapHome: MapHome?,
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
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
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
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
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
                OutlinedLongTextField(
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
                    minFrequency = minimumFrequency,
                    maxFrequency = if(maximumFrequency==0L) 10000000000L else maximumFrequency,
                    enabled = true,
                    helpSubPath = "sdr-source.html#tune-frequency"
                )
                Row(modifier = Modifier.fillMaxWidth()) {
                    OutlinedSwitch(
                        label = "Hardware AGC",
                        helpText = "Automatic Gain Control",
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
                            helpSubPath = "sdr-source.html#lna-gain",
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
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
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
                        PortTextField(
                            port = rtlsdrExternalServerPort,
                            onPortChanged = sourceTabActions.onRtlsdrExternalServerPortChanged,
                            enabled = !analyzerRunning,
                            modifier = Modifier
                                .padding(start = 10.dp)
                                .weight(0.25f)
                        )
                    }
                }
                Row {
                    OutlinedLongTextField(
                        label = "Frequency Correction (ppm)",
                        value = rtlsdrFrequencyCorrection.toLong(),
                        onValueChange = { sourceTabActions.onRtlsdrFrequencyCorrectionChanged(it.toInt())},
                        modifier = Modifier.weight(1f).padding(end = 3.dp),
                        helpSubPath = "sdr-source.html#frequency-correction"
                    )
                    OutlinedLongTextField(
                        label = "Frequency Offset (Hz)",
                        value = rtlsdrConverterOffset,
                        onValueChange = sourceTabActions.onRtlsdrConverterOffsetChanged,
                        modifier = Modifier.weight(1f).padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#frequency-converter-offset"
                    )
                }
                // the rtl_tcp protocol has no bias tee command: only for the USB dongle
                if (!rtlsdrExternalServerEnabled) OutlinedSwitch(
                    label = "Antenna Port Power (Bias-T)",
                    helpText = "Enables the 4.5V bias tee voltage on the antenna port of dongles that have one (e.g. RTL-SDR Blog V3/V4)",
                    isChecked = rtlsdrEnableBiasT,
                    onCheckedChange = sourceTabActions.onRtlsdrEnableBiasTChanged,
                    helpSubPath = "sdr-source.html#antenna-port-power"
                )
                OutlinedEnumDropDown(
                    label = "Direct Sampling Mode",
                    selectedEnum = rtlsdrDirectSamplingMode,
                    enumClass = RtlsdrDirectSamplingMode::class,
                    getDisplayName = { it.displayName },
                    onSelectionChanged = sourceTabActions.onRtlsdrDirectSamplingModeChanged,
                    helpSubPath = "sdr-source.html#direct-sampling-mode",
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
                GainBox(
                    modes = listOf("Linearity", "Sensitivity", "Manual"),
                    selectedIndex = if (airspyAdvancedGainEnabled) 2 else if (airspyGainPreset == AirspyGainPreset.SENSITIVITY) 1 else 0,
                    onModeSelected = { index ->
                        if (index == 2) sourceTabActions.onAirspyAdvancedGainEnabledChanged(true)
                        else {
                            sourceTabActions.onAirspyGainPresetChanged(if (index == 1) AirspyGainPreset.SENSITIVITY else AirspyGainPreset.LINEARITY)
                            sourceTabActions.onAirspyAdvancedGainEnabledChanged(false)
                        }
                    },
                    sliders = when {
                        airspyAdvancedGainEnabled -> listOf(
                            GainSlider("VGA", airspyVgaGain, AirspySource.MIN_VGA_GAIN, AirspySource.MAX_VGA_GAIN, sourceTabActions.onAirspyVgaGainChanged),
                            GainSlider("LNA", airspyLnaGain, AirspySource.MIN_LNA_GAIN, AirspySource.MAX_LNA_GAIN, sourceTabActions.onAirspyLnaGainChanged),
                            GainSlider("Mixer", airspyMixerGain, AirspySource.MIN_MIXER_GAIN, AirspySource.MAX_MIXER_GAIN, sourceTabActions.onAirspyMixerGainChanged),
                        )
                        airspyGainPreset == AirspyGainPreset.SENSITIVITY -> listOf(
                            GainSlider("Sensitivity", airspySensitivityGain, AirspySource.MIN_SENSITIVITY_GAIN, AirspySource.MAX_SENSITIVITY_GAIN, sourceTabActions.onAirspySensitivityGainChanged)
                        )
                        else -> listOf(
                            GainSlider("Linearity", airspyLinearityGain, AirspySource.MIN_LINEARITY_GAIN, AirspySource.MAX_LINEARITY_GAIN, sourceTabActions.onAirspyLinearityGainChanged)
                        )
                    },
                    helpSubPath = "sdr-source.html#gain-mode"
                )
                OutlinedSwitch(
                    label = "Antenna Port Power",
                    helpText = "Enables 4.5V bias power on the SDR's antenna port",
                    isChecked = airspyRfBiasEnabled,
                    onCheckedChange = sourceTabActions.onAirspyRfBiasEnabledChanged,
                    helpSubPath = "sdr-source.html#antenna-port-power"
                )
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
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
                OutlinedLongTextField(
                    label = "Frequency Converter Offset (Hz)",
                    value = airspyConverterOffset,
                    onValueChange = sourceTabActions.onAirspyConverterOffsetChanged,
                    helpSubPath = "sdr-source.html#frequency-converter-offset"
                )
            }
            SourceType.AIRSPYHF -> {
                FrequencyChooser(
                    label = "Tune-Frequency",
                    unit = "Hz",
                    currentFrequency = frequency,
                    onFrequencyChanged = sourceTabActions.onFrequencyChanged,
                    minFrequency = AirspyHfSource.MIN_FREQUENCY,
                    maxFrequency = AirspyHfSource.MAX_FREQUENCY,
                    helpSubPath = "sdr-source.html#tune-frequency"
                )
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    OutlinedSwitch(
                        label = "AGC",
                        helpText = "Automatic Gain Control with high/low threshold.",
                        isChecked = airspyHfAgcEnabled,
                        onCheckedChange = sourceTabActions.onAirspyHfAgcEnabledChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 3.dp),
                        helpSubPath = "sdr-source.html#automatic-gain-control-agc"
                    )
                    OutlinedSwitch(
                        label = "LNA Preamp",
                        helpText = "Enables the +6dB LNA pre-amplifier",
                        isChecked = airspyHfLnaEnabled,
                        onCheckedChange = sourceTabActions.onAirspyHfLnaEnabledChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#lna-preamp"
                    )
                }
                if (airspyHfAgcEnabled) {
                    OutlinedSteppedSlider(
                        label = "AGC Threshold",
                        steps = listOf(0, 1),
                        selectedStepIndex = if(airspyHfAgcThreshold) 1 else 0,
                        onSelectedStepIndexChanged = { sourceTabActions.onAirspyHfAgcThresholdChanged(it == 1) },
                        formatValue = { if(it == 1) "High" else "Low" },
                        helpSubPath = "sdr-source.html#automatic-gain-control-agc"
                    )
                } else {
                    OutlinedSteppedSlider(
                        label = "Attenuation",
                        unit = "dB",
                        steps = (AirspyHfSource.MIN_ATTENUATION..AirspyHfSource.MAX_ATTENUATION).toList(),
                        selectedStepIndex = airspyHfAttenuation,
                        onSelectedStepIndexChanged = sourceTabActions.onAirspyHfAttenuationChanged,
                        formatValue = { (it * AirspyHfSource.ATTENUATION_STEP).toString() },
                        helpSubPath = "sdr-source.html#attenuation"
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
                OutlinedLongTextField(
                    label = "Frequency Converter Offset (Hz)",
                    value = airspyHfConverterOffset,
                    onValueChange = sourceTabActions.onAirspyHfConverterOffsetChanged,
                    helpSubPath = "sdr-source.html#frequency-converter-offset"
                )
            }
            SourceType.HYDRASDR -> {
                FrequencyChooser(
                    label = "Tune-Frequency",
                    unit = "Hz",
                    currentFrequency = frequency,
                    onFrequencyChanged = sourceTabActions.onFrequencyChanged,
                    minFrequency = hydraSdrDeviceInfo?.minFrequency ?: HydraSdrDeviceInfo.DEFAULT_MIN_FREQUENCY,
                    maxFrequency = hydraSdrDeviceInfo?.maxFrequency ?: HydraSdrDeviceInfo.DEFAULT_MAX_FREQUENCY,
                    helpSubPath = "sdr-source.html#tune-frequency"
                )
                // Sliders for the gains deviceInfo.gains reports (the classic LNA/Mixer/VGA +
                // Linearity/Sensitivity five until the device is open), mapped to this app's five
                // persisted gain settings.
                val hydraSdrGainSliders = mapOf(
                    HydraSdrGainType.VGA to Triple("VGA", hydraSdrVgaGain, sourceTabActions.onHydraSdrVgaGainChanged),
                    HydraSdrGainType.LNA to Triple("LNA", hydraSdrLnaGain, sourceTabActions.onHydraSdrLnaGainChanged),
                    HydraSdrGainType.MIXER to Triple("Mixer", hydraSdrMixerGain, sourceTabActions.onHydraSdrMixerGainChanged),
                    HydraSdrGainType.LINEARITY to Triple("Linearity", hydraSdrLinearityGain, sourceTabActions.onHydraSdrLinearityGainChanged),
                    HydraSdrGainType.SENSITIVITY to Triple("Sensitivity", hydraSdrSensitivityGain, sourceTabActions.onHydraSdrSensitivityGainChanged),
                )
                fun gainTypesOf(category: HydraSdrGainCategory) = (hydraSdrDeviceInfo?.gains?.map { it.type }?.filter { it.category == category }
                    ?: hydraSdrGainSliders.keys.filter { it.category == category }).filter { it in hydraSdrGainSliders }
                // Gain mode: one of the composite presets (each sets LNA, mixer and VGA together, so
                // only the selected one is applied) or Manual (the stages directly)
                val hydraSdrPresets = gainTypesOf(HydraSdrGainCategory.PRESET)
                val selectedPreset = hydraSdrPresets.firstOrNull { it == hydraSdrGainPreset } ?: hydraSdrPresets.firstOrNull()
                val activeGainTypes = if (hydraSdrAdvancedGainEnabled) gainTypesOf(HydraSdrGainCategory.MANUAL) else listOfNotNull(selectedPreset)
                GainBox(
                    modes = hydraSdrPresets.map { hydraSdrGainSliders[it]!!.first } + "Manual",
                    selectedIndex = if (hydraSdrAdvancedGainEnabled || selectedPreset == null) hydraSdrPresets.size else hydraSdrPresets.indexOf(selectedPreset),
                    onModeSelected = { index ->
                        if (index >= hydraSdrPresets.size) sourceTabActions.onHydraSdrAdvancedGainEnabledChanged(true)
                        else {
                            sourceTabActions.onHydraSdrGainPresetChanged(hydraSdrPresets[index])
                            sourceTabActions.onHydraSdrAdvancedGainEnabledChanged(false)
                        }
                    },
                    sliders = activeGainTypes.mapNotNull { gainType ->
                        val slider = hydraSdrGainSliders[gainType] ?: return@mapNotNull null // gain type this UI has no slider for yet
                        val descriptor = hydraSdrDeviceInfo?.getGainDescriptor(gainType)
                        GainSlider(slider.first, slider.second, descriptor?.minValue ?: HydraSdrSource.DEFAULT_MIN_GAIN,
                            descriptor?.maxValue ?: HydraSdrSource.DEFAULT_MAX_GAIN, slider.third)
                    },
                    helpSubPath = "sdr-source.html#gain-mode_1"
                )
                val hydraSdrRfPorts = hydraSdrDeviceInfo?.rfPorts?.takeIf { it.isNotEmpty() }
                    ?: HydraSdrRfPort.entries.map {
                        HydraSdrRfPortDescriptor(it.value, it.displayName, HydraSdrDeviceInfo.DEFAULT_MIN_FREQUENCY, HydraSdrDeviceInfo.DEFAULT_MAX_FREQUENCY, false)
                    }
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                    OutlinedListDropDown(
                        label = "RF Port",
                        items = hydraSdrRfPorts,
                        getDisplayName = { it.name },
                        selectedItem = hydraSdrRfPorts.firstOrNull { it.index == hydraSdrRfPortIndex } ?: hydraSdrRfPorts.first(),
                        onSelectionChanged = { sourceTabActions.onHydraSdrRfPortIndexChanged(it.index) },
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(end = 3.dp),
                        helpSubPath = "sdr-source.html#rf-port",
                    )
                    OutlinedSwitch(
                        label = "Antenna Port Power",
                        helpText = "Enables 4.5V bias power on the SDR's antenna port",
                        isChecked = hydraSdrRfBiasEnabled,
                        onCheckedChange = sourceTabActions.onHydraSdrRfBiasEnabledChanged,
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(start = 3.dp),
                        helpSubPath = "sdr-source.html#antenna-port-power"
                    )
                }
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
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
                OutlinedLongTextField(
                    label = "Frequency Converter Offset (Hz)",
                    value = hydraSdrConverterOffset,
                    onValueChange = sourceTabActions.onHydraSdrConverterOffsetChanged,
                    helpSubPath = "sdr-source.html#frequency-converter-offset"
                )
            }
            SourceType.KIWISDR -> {
                FrequencyChooser(
                    label = "Tune-Frequency",
                    unit = "Hz",
                    currentFrequency = frequency,
                    onFrequencyChanged = sourceTabActions.onFrequencyChanged,
                    minFrequency = KiwiSdrSource.MIN_FREQUENCY,
                    maxFrequency = KiwiSdrSource.MAX_FREQUENCY,
                    helpSubPath = "sdr-source.html#tune-frequency"
                )
                Row {
                    TextField(
                        enabled = !analyzerRunning,
                        value = kiwiSdrHost,
                        onValueChange = sourceTabActions.onKiwiSdrHostChanged,
                        label = { Text("DNS Name / IP Address") },
                        modifier = Modifier.weight(0.75f)
                    )
                    PortTextField(
                        port = kiwiSdrPort,
                        onPortChanged = sourceTabActions.onKiwiSdrPortChanged,
                        enabled = !analyzerRunning,
                        modifier = Modifier
                            .padding(start = 10.dp)
                            .weight(0.25f)
                    )
                }
                var kiwiSdrPickerDialogOpen by remember { mutableStateOf(false) }
                Button(
                    onClick = {
                        kiwiSdrPickerDialogOpen = true
                        sourceTabActions.onKiwiSdrPublicListRequested(false)
                    },
                    enabled = !analyzerRunning,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) {
                    Icon(Icons.Default.Public, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Choose from Public List")
                }
                if (kiwiSdrPickerDialogOpen) {
                    KiwiSdrPickerDialog(
                        entries = kiwiSdrPublicListEntries,
                        isLoading = kiwiSdrPublicListLoading,
                        errorMessage = kiwiSdrPublicListError,
                        onDismiss = { kiwiSdrPickerDialogOpen = false },
                        onRefresh = { sourceTabActions.onKiwiSdrPublicListRequested(true) },
                        onSelect = { entry ->
                            sourceTabActions.onKiwiSdrPublicReceiverSelected(entry)
                            kiwiSdrPickerDialogOpen = false
                        },
                        cameraPosition = kiwiSdrMapCameraPosition,
                        onCameraPositionChanged = sourceTabActions.onKiwiSdrMapCameraPositionChanged,
                        home = mapHome,
                    )
                }
                OutlinedSwitch(
                    enabled = !analyzerRunning,
                    label = "Secure Connection",
                    helpText = "Use an encrypted (wss) connection instead of plain ws://",
                    isChecked = kiwiSdrUseTls,
                    onCheckedChange = sourceTabActions.onKiwiSdrUseTlsChanged,
                    helpSubPath = "sdr-source.html#secure-connection"
                )
                TextField(
                    enabled = !analyzerRunning && kiwiSdrUseTls,
                    value = kiwiSdrPassword,
                    onValueChange = sourceTabActions.onKiwiSdrPasswordChanged,
                    label = { Text("Password (optional)") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (!kiwiSdrUseTls) {
                    Text(
                        "Password authentication only allowed for secure connections",
                        fontSize = 12.sp,
                        lineHeight = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    OutlinedSwitch(
                        label = "Automatic Sample Rate",
                        helpText = "Sample rate follows zoom level",
                        isChecked = automaticSampleRateAdjustment,
                        onCheckedChange = sourceTabActions.onAutomaticSampleRateAdjustmentChanged,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .padding(end = 3.dp),
                        helpSubPath = "sdr-source.html#sample-rate-and-gain"
                    )
                    OutlinedListDropDown(
                        label = "Sample Rate",
                        getDisplayName = { it.asStringWithUnit("Sps") },
                        onSelectionChanged = sourceTabActions.onSampleRateChanged,
                        items = supportedSampleRates,
                        selectedItem = sampleRate,
                        modifier = Modifier.weight(1f).padding(start = 3.dp),
                        enabled = !automaticSampleRateAdjustment,
                        helpSubPath = "sdr-source.html#sample-rate-and-gain"
                    )
                }
            }
            SourceType.FILESOURCE -> {
                Text("File: " + filesourceFilename.ifEmpty { "(no File selected)" }, fontSize = 12.sp, lineHeight = 13.sp)
                Row {
                    val openIqFilePicker = rememberOpenFilePicker(
                        mimeTypes = arrayOf("*/*"),
                        onFileChosen = { uri, filename -> sourceTabActions.onOpenFileChosen(uri, filename) },
                        onAbort = { }
                    )
                    Button(
                        onClick = openIqFilePicker,
                        shape = MaterialTheme.shapes.small,
                        enabled = !analyzerRunning,
                        modifier = Modifier
                            .padding(end = 3.dp)
                            .align(Alignment.CenterVertically)
                            .height(60.dp)
                    ) {
                        Icon(painter = painterResource(R.drawable.folder_open), "Open IQ File")
                        Spacer(Modifier.width(8.dp))
                        Text("Open File")
                    }
                    Button(
                        onClick = sourceTabActions.onViewRecordingsClicked,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier
                            .padding(start = 3.dp)
                            .weight(1f)
                            .height(60.dp)
                    ) {
                        Icon(imageVector = Icons.Default.Menu, contentDescription = "View Recordings")
                        Spacer(Modifier.width(8.dp))
                        Text("View Recordings")
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
                    helpSubPath = "sdr-source.html#tune-frequency_1"
                )
                FrequencyChooser(
                    label = "Sample Rate",
                    unit = "Sps",
                    digitCount = 9,
                    currentFrequency = sampleRate,
                    onFrequencyChanged = sourceTabActions.onSampleRateChanged,
                    enabled = !analyzerRunning,
                    helpSubPath = "sdr-source.html#sample-rate_1"
                )
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
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
@Preview(widthDp = 300)
fun SourceTabPreview() {
    CompositionLocalProvider(LocalShowHelp provides {}) {
        SourceTabComposable(
            sourceType = SourceType.HACKRF,
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
            rtlsdrExternalServerEnabled = false,
            rtlsdrExternalServerIP = "",
            rtlsdrExternalServerPort = 1234,
            rtlsdrFrequencyCorrection = 0,
            rtlsdrEnableBiasT = false,
            rtlsdrDirectSamplingMode = RtlsdrDirectSamplingMode.OFF,
            airspyAdvancedGainEnabled = false,
            airspyVgaGain = 2,
            airspyLnaGain = 12,
            airspyMixerGain = 0,
            airspyLinearityGain = 0,
            airspySensitivityGain = 0,
            airspyRfBiasEnabled = true,
            airspyConverterOffset = 0L,
            airspyHfAgcEnabled = false,
            airspyHfAgcThreshold = true,
            airspyHfAttenuation = 2,
            airspyHfLnaEnabled = true,
            airspyHfConverterOffset = 0L,
            hydraSdrAdvancedGainEnabled = false,
            hydraSdrVgaGain = 2,
            hydraSdrLnaGain = 12,
            hydraSdrMixerGain = 0,
            hydraSdrLinearityGain = 0,
            hydraSdrSensitivityGain = 0,
            hydraSdrGainPreset = HydraSdrGainType.LINEARITY,
            airspyGainPreset = AirspyGainPreset.LINEARITY,
            hydraSdrRfBiasEnabled = true,
            hydraSdrRfPortIndex = 0,
            hydraSdrConverterOffset = 0L,
            hydraSdrDeviceInfo = null,
            kiwiSdrHost = "",
            kiwiSdrPort = 8073,
            kiwiSdrPassword = "",
            kiwiSdrUseTls = false,
            kiwiSdrPublicListEntries = emptyList(),
            kiwiSdrPublicListLoading = false,
            kiwiSdrPublicListError = null,
            kiwiSdrMapCameraPosition = MapCameraPosition.DEFAULT,
            mapHome = null,
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
                onOpenFileChosen = { _, _ -> },
                onViewRecordingsClicked = { },
                onFilesourceRepeatChanged = {},
                onRtlsdrExternalServerEnabledChanged = { },
                onRtlsdrExternalServerIPChanged = { },
                onRtlsdrExternalServerPortChanged = { },
                onRtlsdrFrequencyCorrectionChanged = { },
                onRtlsdrEnableBiasTChanged = { },
                onRtlsdrDirectSamplingModeChanged = { },
                onAirspyRfBiasEnabledChanged = { },
                onAirspyAdvancedGainEnabledChanged = { },
                onAirspyLnaGainChanged = { },
                onAirspyVgaGainChanged = { },
                onAirspyMixerGainChanged = { },
                onAirspyLinearityGainChanged = { },
                onAirspySensitivityGainChanged = { },
                onAirspyConverterOffsetChanged = { },
                onAirspyHfAgcEnabledChanged = { },
                onAirspyHfAgcThresholdChanged = { },
                onAirspyHfAttenuationChanged = { },
                onAirspyHfLnaEnabledChanged = { },
                onAirspyHfConverterOffsetChanged = { },
                onHydraSdrAdvancedGainEnabledChanged = { },
                onHydraSdrRfBiasEnabledChanged = { },
                onHydraSdrRfPortIndexChanged = { },
                onHydraSdrLnaGainChanged = { },
                onHydraSdrVgaGainChanged = { },
                onHydraSdrMixerGainChanged = { },
                onHydraSdrLinearityGainChanged = { },
                onHydraSdrSensitivityGainChanged = { },
                onHydraSdrGainPresetChanged = {},
                onAirspyGainPresetChanged = {},
                onHydraSdrConverterOffsetChanged = { },
                onKiwiSdrHostChanged = { },
                onKiwiSdrPortChanged = { },
                onKiwiSdrPasswordChanged = { },
                onKiwiSdrUseTlsChanged = { },
                onKiwiSdrPublicListRequested = { },
                onKiwiSdrPublicReceiverSelected = { },
                onKiwiSdrMapCameraPositionChanged = { },
            ),
        )
    }
}

private class GainSlider(val label: String, val value: Int, val minValue: Int, val maxValue: Int, val onValueChanged: (Int) -> Unit)

/**
 * Gain of a tuner with composite gain presets, in one box: the gain mode (one segment per preset
 * plus Manual) and below it the slider of the selected preset or the manual stage sliders.
 */
@Composable
private fun GainBox(modes: List<String>, selectedIndex: Int, onModeSelected: (Int) -> Unit, sliders: List<GainSlider>, helpSubPath: String?) {
    OutlinedBox(label = "Gain (dB)", helpSubPath = helpSubPath, modifier = Modifier.fillMaxWidth()) {
        Column {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                modes.forEachIndexed { index, label ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        onClick = { onModeSelected(index) },
                        selected = index == selectedIndex,
                    ) { Text(label, maxLines = 1) }
                }
            }
            for (slider in sliders) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // a single preset slider needs no name: the selected segment says which it is
                    if (sliders.size > 1) Text(slider.label, fontSize = 14.sp, maxLines = 1, modifier = Modifier.padding(start = 8.dp).width(48.dp))
                    OutlinedSlider(
                        label = slider.label,
                        unit = "dB",
                        minValue = slider.minValue.toFloat(),
                        maxValue = slider.maxValue.toFloat(),
                        value = slider.value.toFloat(),
                        showOutline = false,
                        decimalPlaces = 0,
                        onValueChanged = { slider.onValueChanged(it.toInt()) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

/** A port typed into a server field: 1-65535, null for anything else (including an empty field). */
internal fun parsePort(text: String): Int? = text.toIntOrNull()?.takeIf { it in 1..65535 }

/**
 * A server's port. The field keeps its own text while it is edited, so it can be emptied and a
 * new port typed; only a valid port reaches the setting. Leaving the field with an empty or
 * invalid entry shows the stored port again, and a port set from elsewhere (a KiwiSDR picked
 * from the public list) shows up unless the field is being edited.
 */
@Composable
private fun PortTextField(port: Int, onPortChanged: (Int) -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf(port.toString()) }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(port, isFocused) { if (!isFocused) text = port.toString() }
    TextField(
        enabled = enabled,
        value = text,
        onValueChange = { newText ->
            text = newText.filter { it.isDigit() }.take(5)
            parsePort(text)?.let(onPortChanged)
        },
        isError = parsePort(text) == null,
        label = { Text("Port") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus() }),
        modifier = modifier.onFocusChanged { isFocused = it.isFocused },
    )
}
