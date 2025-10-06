package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.BillingRepositoryInterface
import com.mantz_it.rfanalyzer.ui.MainViewModel

/**
 * <h1>RF Analyzer - Analyzer Tabs</h1>
 *
 * Module:      AnalyzerTabsComposable.kt
 * Description: A wrapper for all Tabs in the Control Drawer.
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


enum class AnalyzerTabs(val displayName: String) {
    SOURCE("Source"),
    DEMODULATION("Demodulation"),
    RECORDING("Recording"),
    DISPLAY("Display"),
    SETTINGS("Settings"),
    ABOUT("About")
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AnalyzerTabsComposable(
    mainViewModel: MainViewModel,
    appStateRepository: AppStateRepository,
    billingRepository: BillingRepositoryInterface,
    sourceTabActions: SourceTabActions,
    displayTabActions: DisplayTabActions,
    demodulationTabActions: DemodulationTabActions,
    recordingTabActions: RecordingTabActions,
    settingsTabActions: SettingsTabActions,
    aboutTabActions: AboutTabActions,
) {
    var state by rememberSaveable { mutableStateOf(AnalyzerTabs.SOURCE) }

    val appVersion by appStateRepository.appVersion.stateFlow.collectAsState()
    val appBuildType by appStateRepository.appBuildType.stateFlow.collectAsState()
    val sourceType by appStateRepository.sourceType.stateFlow.collectAsState()
    val sourceName by appStateRepository.sourceName.stateFlow.collectAsState()
    val sourceMinimumFrequency by appStateRepository.sourceMinimumFrequency.stateFlow.collectAsState()
    val sourceMaximumFrequency by appStateRepository.sourceMaximumFrequency.stateFlow.collectAsState()
    val sourceSupportedSampleRates by appStateRepository.sourceSupportedSampleRates.stateFlow.collectAsState()
    val sourceFrequency by appStateRepository.sourceFrequency.stateFlow.collectAsState()
    val sourceSampleRate by appStateRepository.sourceSampleRate.stateFlow.collectAsState()
    val sourceAutomaticSampleRateAdjustment by appStateRepository.sourceAutomaticSampleRateAdjustment.stateFlow.collectAsState()
    val hackrfVgaGainIndex by appStateRepository.hackrfVgaGainIndex.stateFlow.collectAsState()
    val hackrfLnaGainIndex by appStateRepository.hackrfLnaGainIndex.stateFlow.collectAsState()
    val hackrfAmplifierEnabled by appStateRepository.hackrfAmplifierEnabled.stateFlow.collectAsState()
    val hackrfAntennaPowerEnabled by appStateRepository.hackrfAntennaPowerEnabled.stateFlow.collectAsState()
    val hackrfConverterOffset by appStateRepository.hackrfConverterOffset.stateFlow.collectAsState()
    val rtlsdrGainSteps by appStateRepository.rtlsdrGainSteps.stateFlow.collectAsState()
    val rtlsdrGainIndex by appStateRepository.rtlsdrGainIndex.stateFlow.collectAsState()
    val rtlsdrIFGainSteps by appStateRepository.rtlsdrIFGainSteps.stateFlow.collectAsState()
    val rtlsdrIFGainIndex by appStateRepository.rtlsdrIFGainIndex.stateFlow.collectAsState()
    val rtlsdrAgcEnabled by appStateRepository.rtlsdrAgcEnabled.stateFlow.collectAsState()
    val rtlsdrManualGainEnabled by appStateRepository.rtlsdrManualGainEnabled.stateFlow.collectAsState()
    val rtlsdrConverterOffset by appStateRepository.rtlsdrConverterOffset.stateFlow.collectAsState()
    val rtlsdrExternalServerEnabled by appStateRepository.rtlsdrExternalServerEnabled.stateFlow.collectAsState()
    val rtlsdrExternalServerIP by appStateRepository.rtlsdrExternalServerIP.stateFlow.collectAsState()
    val rtlsdrExternalServerPort by appStateRepository.rtlsdrExternalServerPort.stateFlow.collectAsState()
    val rtlsdrFrequencyCorrection by appStateRepository.rtlsdrFrequencyCorrection.stateFlow.collectAsState()
    val rtlsdrAllowOutOfBoundFrequency by appStateRepository.rtlsdrAllowOutOfBoundFrequency.stateFlow.collectAsState()
    val rtlsdrEnableBiasT by appStateRepository.rtlsdrEnableBiasT.stateFlow.collectAsState()
    val airspyAdvancedGainEnabled by appStateRepository.airspyAdvancedGainEnabled.stateFlow.collectAsState()
    val airspyVgaGain by appStateRepository.airspyVgaGain.stateFlow.collectAsState()
    val airspyLnaGain by appStateRepository.airspyLnaGain.stateFlow.collectAsState()
    val airspyMixerGain by appStateRepository.airspyMixerGain.stateFlow.collectAsState()
    val airspyLinearityGain by appStateRepository.airspyLinearityGain.stateFlow.collectAsState()
    val airspySensitivityGain by appStateRepository.airspySensitivityGain.stateFlow.collectAsState()
    val airspyRfBiasEnabled by appStateRepository.airspyRfBiasEnabled.stateFlow.collectAsState()
    val airspyConverterOffset by appStateRepository.airspyConverterOffset.stateFlow.collectAsState()
    val hydraSdrAdvancedGainEnabled by appStateRepository.hydraSdrAdvancedGainEnabled.stateFlow.collectAsState()
    val hydraSdrVgaGain by appStateRepository.hydraSdrVgaGain.stateFlow.collectAsState()
    val hydraSdrLnaGain by appStateRepository.hydraSdrLnaGain.stateFlow.collectAsState()
    val hydraSdrMixerGain by appStateRepository.hydraSdrMixerGain.stateFlow.collectAsState()
    val hydraSdrLinearityGain by appStateRepository.hydraSdrLinearityGain.stateFlow.collectAsState()
    val hydraSdrSensitivityGain by appStateRepository.hydraSdrSensitivityGain.stateFlow.collectAsState()
    val hydraSdrRfBiasEnabled by appStateRepository.hydraSdrRfBiasEnabled.stateFlow.collectAsState()
    val hydraSdrRfPort by appStateRepository.hydraSdrRfPort.stateFlow.collectAsState()
    val hydraSdrConverterOffset by appStateRepository.hydraSdrConverterOffset.stateFlow.collectAsState()
    val filesourceFilename by appStateRepository.filesourceFilename.stateFlow.collectAsState()
    val filesourceFileFormat by appStateRepository.filesourceFileFormat.stateFlow.collectAsState()
    val filesourceRepeatEnabled by appStateRepository.filesourceRepeatEnabled.stateFlow.collectAsState()
    val fftSize by appStateRepository.fftSize.stateFlow.collectAsState()
    val fftAverageLength by appStateRepository.fftAverageLength.stateFlow.collectAsState()
    val fftPeakHold by appStateRepository.fftPeakHold.stateFlow.collectAsState()
    val maxFrameRate by appStateRepository.maxFrameRate.stateFlow.collectAsState()
    val waterfallColorMap by appStateRepository.waterfallColorMap.stateFlow.collectAsState()
    val waterfallSpeed by appStateRepository.waterfallSpeed.stateFlow.collectAsState()
    val fftDrawingType by appStateRepository.fftDrawingType.stateFlow.collectAsState()
    val fftRelativeFrequency by appStateRepository.fftRelativeFrequency.stateFlow.collectAsState()
    val fftWaterfallRatio by appStateRepository.fftWaterfallRatio.stateFlow.collectAsState()
    val demodulationMode by appStateRepository.demodulationMode.stateFlow.collectAsState()
    val demodulationEnabled by appStateRepository.demodulationEnabled.stateFlow.collectAsState()
    val channelFrequency by appStateRepository.channelFrequency.stateFlow.collectAsState()
    val channelWidth by appStateRepository.channelWidth.stateFlow.collectAsState()
    val squelchEnabled by appStateRepository.squelchEnabled.stateFlow.collectAsState()
    val squelch by appStateRepository.squelch.stateFlow.collectAsState()
    val audioVolumeLevel by appStateRepository.effectiveAudioVolumeLevel.stateFlow.collectAsState()
    val audioMuted by appStateRepository.audioMuted.stateFlow.collectAsState()
    val keepChannelCentered by appStateRepository.keepChannelCentered.stateFlow.collectAsState()
    val recordingRunning by appStateRepository.recordingRunning.stateFlow.collectAsState()
    val recordingName by appStateRepository.recordingName.stateFlow.collectAsState()
    val recordOnlyWhenSquelchIsSatisfied by appStateRepository.recordOnlyWhenSquelchIsSatisfied.stateFlow.collectAsState()
    val recordingStopAfterThreshold by appStateRepository.recordingStopAfterThreshold.stateFlow.collectAsState()
    val recordingstopAfterUnit by appStateRepository.recordingstopAfterUnit.stateFlow.collectAsState()
    val recordingCurrentFileSize by appStateRepository.recordingCurrentFileSize.stateFlow.collectAsState()
    val recordingStartedTimestamp by appStateRepository.recordingStartedTimestamp.stateFlow.collectAsState()
    val screenOrientation by appStateRepository.screenOrientation.stateFlow.collectAsState()
    val fontSize by appStateRepository.fontSize.stateFlow.collectAsState()
    val colorTheme by appStateRepository.colorTheme.stateFlow.collectAsState()
    val longPressHelpEnabled by appStateRepository.longPressHelpEnabled.stateFlow.collectAsState()
    val reverseTuningWheel by appStateRepository.reverseTuningWheel.stateFlow.collectAsState()
    val controlDrawerSide by appStateRepository.controlDrawerSide.stateFlow.collectAsState()
    val showDebugInformation by appStateRepository.showDebugInformation.stateFlow.collectAsState()
    val loggingEnabled by appStateRepository.loggingEnabled.stateFlow.collectAsState()
    val viewportVerticalScaleMin by appStateRepository.viewportVerticalScaleMin.stateFlow.collectAsState()
    val viewportVerticalScaleMax by appStateRepository.viewportVerticalScaleMax.stateFlow.collectAsState()
    val viewportZoom by appStateRepository.viewportZoom.stateFlow.collectAsState()
    val analyzerRunning by appStateRepository.analyzerRunning.stateFlow.collectAsState()
    val analyzerStartPending by appStateRepository.analyzerStartPending.stateFlow.collectAsState()
    val appUsageTimeInSeconds by appStateRepository.appUsageTimeInSeconds.stateFlow.collectAsState()
    val isAppUsageTimeUsedUp by appStateRepository.isAppUsageTimeUsedUp.stateFlow.collectAsState()
    val isFullVersion by appStateRepository.isFullVersion.stateFlow.collectAsState()
    val isPurchasePending by appStateRepository.isPurchasePending.stateFlow.collectAsState()

    val remainingTrialDays by billingRepository.remainingTrialPeriodDays.collectAsState()

    val totalRecordingSizeInBytes by mainViewModel.totalRecordingSizeInBytes.collectAsState()

    Column {
        PrimaryScrollableTabRow(selectedTabIndex = state.ordinal) {
            AnalyzerTabs.entries.forEach { tab ->
                Tab(
                    selected = state == tab,
                    onClick = { state = tab },
                    text = { Text(tab.displayName) })
            }
        }
        Box(modifier = Modifier.padding(5.dp)) {
            when (state) {
                AnalyzerTabs.SOURCE
                    -> SourceTabComposable(
                        sourceType = sourceType,
                        sourceName = sourceName,
                        analyzerRunning = analyzerRunning,
                        analyzerStartPending = analyzerStartPending,
                        frequency = sourceFrequency,
                        sampleRate = sourceSampleRate,
                        automaticSampleRateAdjustment = sourceAutomaticSampleRateAdjustment,
                        minimumFrequency = sourceMinimumFrequency,
                        maximumFrequency = sourceMaximumFrequency,
                        supportedSampleRates = sourceSupportedSampleRates,
                        hackrfVgaGainSteps = appStateRepository.hackrfVgaGainSteps,
                        hackrfVgaGainIndex = hackrfVgaGainIndex,
                        hackrfLnaGainSteps = appStateRepository.hackrfLnaGainSteps,
                        hackrfLnaGainIndex = hackrfLnaGainIndex,
                        hackrfAmplifierEnabled = hackrfAmplifierEnabled,
                        hackrfAntennaPowerEnabled = hackrfAntennaPowerEnabled,
                        hackrfConverterOffset = hackrfConverterOffset,
                        rtlsdrGainSteps = rtlsdrGainSteps,
                        rtlsdrGainIndex = rtlsdrGainIndex,
                        rtlsdrIFGainSteps = rtlsdrIFGainSteps,
                        rtlsdrIFGainIndex = rtlsdrIFGainIndex,
                        rtlsdrAgcEnabled = rtlsdrAgcEnabled,
                        rtlsdrManualGainEnabled = rtlsdrManualGainEnabled,
                        rtlsdrExternalServerEnabled = rtlsdrExternalServerEnabled,
                        rtlsdrExternalServerIP = rtlsdrExternalServerIP,
                        rtlsdrExternalServerPort = rtlsdrExternalServerPort,
                        rtlsdrFrequencyCorrection = rtlsdrFrequencyCorrection,
                        rtlsdrConverterOffset = rtlsdrConverterOffset,
                        rtlsdrAllowOutOfBoundFrequency = rtlsdrAllowOutOfBoundFrequency,
                        rtlsdrEnableBiasT = rtlsdrEnableBiasT,
                        airspyAdvancedGainEnabled = airspyAdvancedGainEnabled,
                        airspyVgaGain = airspyVgaGain,
                        airspyLnaGain = airspyLnaGain,
                        airspyMixerGain = airspyMixerGain,
                        airspyLinearityGain = airspyLinearityGain,
                        airspySensitivityGain = airspySensitivityGain,
                        airspyRfBiasEnabled = airspyRfBiasEnabled,
                        airspyConverterOffset = airspyConverterOffset,
                        hydraSdrAdvancedGainEnabled = hydraSdrAdvancedGainEnabled,
                        hydraSdrVgaGain = hydraSdrVgaGain,
                        hydraSdrLnaGain = hydraSdrLnaGain,
                        hydraSdrMixerGain = hydraSdrMixerGain,
                        hydraSdrLinearityGain = hydraSdrLinearityGain,
                        hydraSdrSensitivityGain = hydraSdrSensitivityGain,
                        hydraSdrRfBiasEnabled = hydraSdrRfBiasEnabled,
                        hydraSdrRfPort = hydraSdrRfPort,
                        hydraSdrConverterOffset = hydraSdrConverterOffset,
                        filesourceFilename = filesourceFilename,
                        filesourceFileFormat = filesourceFileFormat,
                        filesourceRepeatEnabled = filesourceRepeatEnabled,
                        sourceTabActions = sourceTabActions
                    )
                AnalyzerTabs.DISPLAY
                    -> DisplayTabComposable(
                        viewportVerticalScaleMin = viewportVerticalScaleMin,
                        viewportVerticalScaleMax = viewportVerticalScaleMax,
                        fftSize = fftSize,
                        averageLength = fftAverageLength,
                        peakHold = fftPeakHold,
                        maxFrameRate = maxFrameRate,
                        colorMap = waterfallColorMap,
                        drawingType = fftDrawingType,
                        waterfallSpeed = waterfallSpeed,
                        relativeFrequency = fftRelativeFrequency,
                        fftWaterfallRatio = fftWaterfallRatio,
                        displayTabActions = displayTabActions
                    )
                AnalyzerTabs.DEMODULATION
                    -> DemodulationTabComposable(
                        demodulationEnabled = demodulationEnabled,
                        demodulationMode = demodulationMode,
                        channelFrequency = channelFrequency,
                        channelWidth = channelWidth,
                        squelchEnabled = squelchEnabled,
                        minSquelch = viewportVerticalScaleMin,
                        maxSquelch = viewportVerticalScaleMax,
                        squelch = squelch,
                        keepChannelCentered = keepChannelCentered,
                        viewportZoom = viewportZoom,
                        audioVolumeLevel = audioVolumeLevel,
                        audioMuted = audioMuted,
                        demodulationTabActions = demodulationTabActions
                    )
                AnalyzerTabs.RECORDING
                    -> RecordingTabComposable(
                        analyzerRunning = analyzerRunning,
                        recordingRunning = recordingRunning,
                        name = recordingName,
                        frequency = sourceFrequency,
                        sampleRate = sourceSampleRate,
                        onlyRecordWhenSquelchIsSatisfied = recordOnlyWhenSquelchIsSatisfied,
                        squelchEnabled = squelchEnabled,
                        squelch = squelch,
                        minSquelch = viewportVerticalScaleMin,
                        maxSquelch = viewportVerticalScaleMax,
                        stopAfterThreshold = recordingStopAfterThreshold,
                        stopAfterUnit = recordingstopAfterUnit,
                        currentRecordingFileSize = recordingCurrentFileSize,
                        recordingsTotalFileSize = totalRecordingSizeInBytes,
                        recordingStartedTimestamp = recordingStartedTimestamp,
                        recordingTabActions = recordingTabActions
                    )
                AnalyzerTabs.SETTINGS
                    -> SettingsTabComposable(
                        screenOrientation = screenOrientation,
                        fontSize = fontSize,
                        colorTheme = colorTheme,
                        longPressHelpEnabled = longPressHelpEnabled,
                        reverseTuningWheel = reverseTuningWheel,
                        controlDrawerSide = controlDrawerSide,
                        rtlsdrAllowOutOfBoundFrequency = rtlsdrAllowOutOfBoundFrequency,
                        showDebugInformation = showDebugInformation,
                        loggingEnabled = loggingEnabled,
                        settingsTabActions = settingsTabActions
                    )
                AnalyzerTabs.ABOUT
                    -> AboutTabComposable(
                        appUsageTime = appUsageTimeInSeconds,
                        isAppUsageTimeUsedUp = isAppUsageTimeUsedUp,
                        remainingTrialDays = remainingTrialDays,
                        isFullVersion = isFullVersion,
                        isPurchasePending = isPurchasePending,
                        appVersion = appVersion,
                        appBuildType = appBuildType,
                        aboutTabActions = aboutTabActions
                    )
            }
        }
    }
}