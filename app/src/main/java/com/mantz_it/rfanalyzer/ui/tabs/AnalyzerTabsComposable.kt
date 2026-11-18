package com.mantz_it.rfanalyzer.ui.tabs

import com.mantz_it.rfanalyzer.digiext.decodes
import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import com.mantz_it.rfanalyzer.digiext.DigitalLink
import com.mantz_it.rfanalyzer.digiext.notice
import com.mantz_it.rfanalyzer.digiext.usesDigitalExtension
import com.mantz_it.rfanalyzer.digiext.rds.LocalRdsPtyUs
import com.mantz_it.rfanalyzer.digiext.rds.RdsPtyTable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SsidChart
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.digiext.digitalMode
import com.mantz_it.rfanalyzer.ui.MainViewModel
import com.mantz_it.rfanalyzer.digiext.ui.DigitalTabActions
import com.mantz_it.rfanalyzer.digiext.ui.DigitalTabComposable

/**
 * <h1>RF Analyzer - Analyzer Tabs</h1>
 *
 * Module:      AnalyzerTabsComposable.kt
 * Description: A wrapper for all Tabs in the Control Drawer.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


enum class AnalyzerTabs(val displayName: String, val icon: ImageVector) {
    SOURCE("Source", Icons.Filled.SettingsInputAntenna),
    DEMODULATION("Demodulation", Icons.Filled.GraphicEq),
    // The Digital tab (with its Overview/Raw/Map subtabs, DigitalTab.kt's PacketsView)
    PACKETS("Digital", Icons.Filled.Memory),
    RECORDING("Recording", Icons.Filled.FiberManualRecord),
    DISPLAY("Display", Icons.Filled.SsidChart),
    SETTINGS("Settings", Icons.Filled.Settings),
    ABOUT("About", Icons.Filled.Info)
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun AnalyzerTabsComposable(
    mainViewModel: MainViewModel,
    appStateRepository: AppStateRepository,
    sourceTabActions: SourceTabActions,
    displayTabActions: DisplayTabActions,
    demodulationTabActions: DemodulationTabActions,
    recordingTabActions: RecordingTabActions,
    settingsTabActions: SettingsTabActions,
    aboutTabActions: AboutTabActions,
    digitalTabActions: DigitalTabActions,
    selectedTab: AnalyzerTabs
) {
    val appVersion by appStateRepository.appVersion.stateFlow.collectAsState()
    val digitalStatus by mainViewModel.digitalStatus.collectAsState()
    val digitalNotice = digitalStatus.notice().takeIf { DigitalFeatures.shown }
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
    val rtlsdrDirectSamplingMode by appStateRepository.rtlsdrDirectSamplingMode.stateFlow.collectAsState()
    val rtlsdrEnableBiasT by appStateRepository.rtlsdrEnableBiasT.stateFlow.collectAsState()
    val airspyAdvancedGainEnabled by appStateRepository.airspyAdvancedGainEnabled.stateFlow.collectAsState()
    val airspyVgaGain by appStateRepository.airspyVgaGain.stateFlow.collectAsState()
    val airspyLnaGain by appStateRepository.airspyLnaGain.stateFlow.collectAsState()
    val airspyMixerGain by appStateRepository.airspyMixerGain.stateFlow.collectAsState()
    val airspyLinearityGain by appStateRepository.airspyLinearityGain.stateFlow.collectAsState()
    val airspySensitivityGain by appStateRepository.airspySensitivityGain.stateFlow.collectAsState()
    val airspyGainPreset by appStateRepository.airspyGainPreset.stateFlow.collectAsState()
    val airspyRfBiasEnabled by appStateRepository.airspyRfBiasEnabled.stateFlow.collectAsState()
    val airspyConverterOffset by appStateRepository.airspyConverterOffset.stateFlow.collectAsState()
    val airspyHfAgcEnabled by appStateRepository.airspyHfAgcEnabled.stateFlow.collectAsState()
    val airspyHfAgcThreshold by appStateRepository.airspyHfAgcThreshold.stateFlow.collectAsState()
    val airspyHfAttenuation by appStateRepository.airspyHfAttenuation.stateFlow.collectAsState()
    val airspyHfLnaEnabled by appStateRepository.airspyHfLnaEnabled.stateFlow.collectAsState()
    val airspyHfConverterOffset by appStateRepository.airspyHfConverterOffset.stateFlow.collectAsState()
    val hydraSdrAdvancedGainEnabled by appStateRepository.hydraSdrAdvancedGainEnabled.stateFlow.collectAsState()
    val hydraSdrVgaGain by appStateRepository.hydraSdrVgaGain.stateFlow.collectAsState()
    val hydraSdrLnaGain by appStateRepository.hydraSdrLnaGain.stateFlow.collectAsState()
    val hydraSdrMixerGain by appStateRepository.hydraSdrMixerGain.stateFlow.collectAsState()
    val hydraSdrLinearityGain by appStateRepository.hydraSdrLinearityGain.stateFlow.collectAsState()
    val hydraSdrSensitivityGain by appStateRepository.hydraSdrSensitivityGain.stateFlow.collectAsState()
    val hydraSdrGainPreset by appStateRepository.hydraSdrGainPreset.stateFlow.collectAsState()
    val hydraSdrRfBiasEnabled by appStateRepository.hydraSdrRfBiasEnabled.stateFlow.collectAsState()
    val hydraSdrRfPortIndex by appStateRepository.hydraSdrRfPortIndex.stateFlow.collectAsState()
    val hydraSdrConverterOffset by appStateRepository.hydraSdrConverterOffset.stateFlow.collectAsState()
    val hydraSdrDeviceInfo by appStateRepository.hydraSdrDeviceInfo.stateFlow.collectAsState()
    val kiwiSdrHost by appStateRepository.kiwiSdrHost.stateFlow.collectAsState()
    val kiwiSdrPort by appStateRepository.kiwiSdrPort.stateFlow.collectAsState()
    val kiwiSdrPassword by appStateRepository.kiwiSdrPassword.stateFlow.collectAsState()
    val kiwiSdrUseTls by appStateRepository.kiwiSdrUseTls.stateFlow.collectAsState()
    val kiwiSdrPublicListUiState by mainViewModel.kiwiSdrPublicListUiState.collectAsState()
    val filesourceFilename by appStateRepository.filesourceFilename.stateFlow.collectAsState()
    val filesourceFileFormat by appStateRepository.filesourceFileFormat.stateFlow.collectAsState()
    val filesourceRepeatEnabled by appStateRepository.filesourceRepeatEnabled.stateFlow.collectAsState()
    val fftSize by appStateRepository.fftSize.stateFlow.collectAsState()
    val fftAverageLength by appStateRepository.fftAverageLength.stateFlow.collectAsState()
    val fftPeakHold by appStateRepository.fftPeakHold.stateFlow.collectAsState()
    val maxFrameRate by appStateRepository.maxFrameRate.stateFlow.collectAsState()
    val waterfallColorMap by appStateRepository.waterfallColorMap.stateFlow.collectAsState()
    val waterfallFps by appStateRepository.waterfallFps.stateFlow.collectAsState()
    val fftDrawingType by appStateRepository.fftDrawingType.stateFlow.collectAsState()
    val fftRelativeFrequency by appStateRepository.fftRelativeFrequency.stateFlow.collectAsState()
    val fftWaterfallRatio by appStateRepository.fftWaterfallRatio.stateFlow.collectAsState()
    val fftPaddingRatio by appStateRepository.fftPaddingRatio.stateFlow.collectAsState()
    val manualFftSizeEnabled by appStateRepository.manualFftSizeEnabled.stateFlow.collectAsState()
    val demodulationMode by appStateRepository.demodulationMode.stateFlow.collectAsState()
    val demodulationWfmStereoAndRdsEnabled by appStateRepository.demodulationWfmStereoAndRdsEnabled.stateFlow.collectAsState()
    val demodulationWfmRdsPtyTable by appStateRepository.demodulationWfmRdsPtyTable.stateFlow.collectAsState()
    val userLocation by mainViewModel.userLocation.collectAsState()
    val rdsPtyUs = demodulationWfmRdsPtyTable.isRbds(userLocation.countryCode)
    val demodulationEnabled by appStateRepository.demodulationEnabled.stateFlow.collectAsState()
    val channelFrequency by appStateRepository.channelFrequency.stateFlow.collectAsState()
    val sessionStartMs by mainViewModel.currentSessionStartMs.collectAsState()
    val channelWidth by appStateRepository.channelWidth.stateFlow.collectAsState()
    val tuneStepHz by appStateRepository.tuneStepHz.stateFlow.collectAsState()
    val pocsagBaudRate by appStateRepository.effectivePocsagBaudRate.stateFlow.collectAsState()
    val meshCoreCustomChannels by appStateRepository.meshCoreCustomChannels.stateFlow.collectAsState()
    val ax25Variant by appStateRepository.ax25Variant.stateFlow.collectAsState()
    val rttyBaudRate by appStateRepository.effectiveRttyBaudRate.stateFlow.collectAsState()
    val rttyFrequencyShift by appStateRepository.effectiveRttyFrequencyShift.stateFlow.collectAsState()
    val rttyPolarity by appStateRepository.effectiveRttyPolarity.stateFlow.collectAsState()
    val psk31BaudRate by appStateRepository.effectivePsk31BaudRate.stateFlow.collectAsState()
    val r09BaudRate by appStateRepository.effectiveR09BaudRate.stateFlow.collectAsState()
    val loraSpreadingFactors by appStateRepository.effectiveLoraSpreadingFactors.stateFlow.collectAsState()
    val loraIqMode by appStateRepository.effectiveLoraIqMode.stateFlow.collectAsState()
    val stationOverlay by appStateRepository.stationOverlay.stateFlow.collectAsState()
    val ft8TimingMode by appStateRepository.ft8TimingMode.stateFlow.collectAsState()
    val ft8ManualOffsetMs by appStateRepository.ft8TimeOffsetMs.stateFlow.collectAsState()
    val clockSyncState by mainViewModel.clockSyncState.collectAsState()
    val squelchEnabled by appStateRepository.effectiveSquelchEnabled.stateFlow.collectAsState()
    val squelch by appStateRepository.effectiveSquelchMargin.stateFlow.collectAsState()
    val audioVolumeLevel by appStateRepository.effectiveAudioVolumeLevel.stateFlow.collectAsState()
    val audioMuted by appStateRepository.audioMuted.stateFlow.collectAsState()
    val keepChannelCentered by appStateRepository.keepChannelCentered.stateFlow.collectAsState()
    val stationFavorites by mainViewModel.stationFavorites.collectAsState()
    val bandFavorites by mainViewModel.bandFavorites.collectAsState()
    val recordingRunning by appStateRepository.recordingRunning.stateFlow.collectAsState()
    val basebandRecordingRunning by appStateRepository.basebandRecordingRunning.stateFlow.collectAsState()
    val recordingName by appStateRepository.recordingName.stateFlow.collectAsState()
    val recordOnlyWhenSquelchIsSatisfied by appStateRepository.recordOnlyWhenSquelchIsSatisfied.stateFlow.collectAsState()
    val recordingStopAfterThreshold by appStateRepository.recordingStopAfterThreshold.stateFlow.collectAsState()
    val recordingstopAfterUnit by appStateRepository.recordingstopAfterUnit.stateFlow.collectAsState()
    val recordingCurrentFileSize by appStateRepository.recordingCurrentFileSize.stateFlow.collectAsState()
    val recordingStartedElapsedRealtime by appStateRepository.recordingStartedElapsedRealtime.stateFlow.collectAsState()
    val screenOrientation by appStateRepository.screenOrientation.stateFlow.collectAsState()
    val fontSize by appStateRepository.fontSize.stateFlow.collectAsState()
    val colorTheme by appStateRepository.colorTheme.stateFlow.collectAsState()
    val longPressHelpEnabled by appStateRepository.longPressHelpEnabled.stateFlow.collectAsState()
    val reverseTuningWheel by appStateRepository.reverseTuningWheel.stateFlow.collectAsState()
    val startAnalyzerOnUsbAttach by appStateRepository.startAnalyzerOnUsbAttach.stateFlow.collectAsState()
    val controlDrawerSide by appStateRepository.controlDrawerSide.stateFlow.collectAsState()
    val enableLowPerformanceMode by appStateRepository.enableLowPerformanceMode.stateFlow.collectAsState()
    val lowPerformanceModeFilterQuality by appStateRepository.lowPerformanceModeFilterQuality.stateFlow.collectAsState()
    val showDebugInformation by appStateRepository.showDebugInformation.stateFlow.collectAsState()
    val loggingEnabled by appStateRepository.loggingEnabled.stateFlow.collectAsState()
    val storedVerticalScaleMin by appStateRepository.viewportVerticalScaleMin.stateFlow.collectAsState()
    val storedVerticalScaleMax by appStateRepository.viewportVerticalScaleMax.stateFlow.collectAsState()
    // Sanitized because the vertical scale and squelch sliders throw on a non-finite/inverted range
    val (viewportVerticalScaleMin, viewportVerticalScaleMax) =
        AppStateRepository.sanitizeVerticalScale(storedVerticalScaleMin, storedVerticalScaleMax)
    val viewportZoom by appStateRepository.viewportZoom.stateFlow.collectAsState()
    val analyzerRunning by appStateRepository.analyzerRunning.stateFlow.collectAsState()
    val analyzerStartPending by appStateRepository.analyzerStartPending.stateFlow.collectAsState()
    val appUsageTimeInSeconds by appStateRepository.appUsageTimeInSeconds.stateFlow.collectAsState()
    val trialSecondsLeft by appStateRepository.trialSecondsLeft.stateFlow.collectAsState()
    val license by appStateRepository.license.stateFlow.collectAsState()
    val digitalTrialSecondsLeft by appStateRepository.digitalTrialSecondsLeft.stateFlow.collectAsState()
    val billingState by mainViewModel.billingState.collectAsState()

    val totalRecordingSizeInBytes by mainViewModel.totalRecordingSizeInBytes.collectAsState()

    val mapTrails by mainViewModel.mapTrails.collectAsState()

    // The Digital tab's shared filter, Raw subtab paging and Map subtab state.
    val digitalTabFilter by mainViewModel.digitalTabFilter.collectAsState()
    val filteredPackets = mainViewModel.filteredPacketsPaging.collectAsLazyPagingItems()
    val mapMarkerState by mainViewModel.mapMarkerState.collectAsState()
    val mapHome by mainViewModel.mapHome.collectAsState()
    val kiwiSdrMapCameraPosition by mainViewModel.kiwiSdrMapCameraPosition.collectAsState()
    val showMapTrails by mainViewModel.showMapTrails.collectAsState()
    val showAllModesOnMap by mainViewModel.showAllModesOnMap.collectAsState()
    val pendingMapFocus by mainViewModel.mapFocusRequest.collectAsState()
    val pendingPacketDelete = mainViewModel.pendingDelete
    val pendingPacketShare = mainViewModel.pendingShare
    // From the collected states, so the mode button follows a mode change while the tab is shown.
    val currentPacketMode = demodulationMode.digitalMode(demodulationWfmStereoAndRdsEnabled)

    // rememberSaveable alone survives rotation and process death, not this `when` switching to
    // another tab and back (the branch's subtree is disposed). SaveableStateProvider keeps the
    // Digital tab's saveable state (sub-view, sort order, expanded row, ...) across tab switches,
    // as NavHost does per back-stack entry. Only the Digital tab uses it.
    val saveableStateHolder = rememberSaveableStateHolder()

    Box(modifier = Modifier.padding(5.dp).fillMaxSize()) {
        when (selectedTab) {
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
                    rtlsdrEnableBiasT = rtlsdrEnableBiasT,
                    rtlsdrDirectSamplingMode = rtlsdrDirectSamplingMode,
                    airspyAdvancedGainEnabled = airspyAdvancedGainEnabled,
                    airspyVgaGain = airspyVgaGain,
                    airspyLnaGain = airspyLnaGain,
                    airspyMixerGain = airspyMixerGain,
                    airspyLinearityGain = airspyLinearityGain,
                    airspySensitivityGain = airspySensitivityGain,
                    airspyGainPreset = airspyGainPreset,
                    airspyRfBiasEnabled = airspyRfBiasEnabled,
                    airspyConverterOffset = airspyConverterOffset,
                    airspyHfAgcEnabled = airspyHfAgcEnabled,
                    airspyHfAgcThreshold = airspyHfAgcThreshold,
                    airspyHfAttenuation = airspyHfAttenuation,
                    airspyHfLnaEnabled = airspyHfLnaEnabled,
                    airspyHfConverterOffset = airspyHfConverterOffset,
                    hydraSdrAdvancedGainEnabled = hydraSdrAdvancedGainEnabled,
                    hydraSdrVgaGain = hydraSdrVgaGain,
                    hydraSdrLnaGain = hydraSdrLnaGain,
                    hydraSdrMixerGain = hydraSdrMixerGain,
                    hydraSdrLinearityGain = hydraSdrLinearityGain,
                    hydraSdrSensitivityGain = hydraSdrSensitivityGain,
                    hydraSdrGainPreset = hydraSdrGainPreset,
                    hydraSdrRfBiasEnabled = hydraSdrRfBiasEnabled,
                    hydraSdrRfPortIndex = hydraSdrRfPortIndex,
                    hydraSdrConverterOffset = hydraSdrConverterOffset,
                    hydraSdrDeviceInfo = hydraSdrDeviceInfo,
                    kiwiSdrHost = kiwiSdrHost,
                    kiwiSdrPort = kiwiSdrPort,
                    kiwiSdrPassword = kiwiSdrPassword,
                    kiwiSdrUseTls = kiwiSdrUseTls,
                    kiwiSdrPublicListEntries = kiwiSdrPublicListUiState.entries,
                    kiwiSdrPublicListLoading = kiwiSdrPublicListUiState.isLoading,
                    kiwiSdrPublicListError = kiwiSdrPublicListUiState.errorMessage,
                    filesourceFilename = filesourceFilename,
                    filesourceFileFormat = filesourceFileFormat,
                    filesourceRepeatEnabled = filesourceRepeatEnabled,
                    kiwiSdrMapCameraPosition = kiwiSdrMapCameraPosition,
                    mapHome = mapHome,
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
                    waterfallFps = waterfallFps,
                    relativeFrequency = fftRelativeFrequency,
                    fftWaterfallRatio = fftWaterfallRatio,
                    fftPaddingRatio = fftPaddingRatio,
                    manualFftSizeEnabled = manualFftSizeEnabled,
                    displayTabActions = displayTabActions
                )
            AnalyzerTabs.DEMODULATION
                -> DemodulationTabComposable(
                    demodulationEnabled = demodulationEnabled,
                    demodulationMode = demodulationMode,
                    digitalNotice = digitalNotice.takeIf { usesDigitalExtension(demodulationMode, demodulationWfmStereoAndRdsEnabled) },
                    wfmStereoAndRdsEnabled = demodulationWfmStereoAndRdsEnabled,
                    wfmRdsPtyTable = demodulationWfmRdsPtyTable,
                    wfmRdsAutoIsRbds = RdsPtyTable.AUTO.isRbds(userLocation.countryCode),
                    channelFrequency = channelFrequency,
                    channelWidth = channelWidth,
                    tuneStepHz = tuneStepHz,
                    pocsagBaudRate = pocsagBaudRate,
                    meshCoreCustomChannels = meshCoreCustomChannels,
                    ax25Variant = ax25Variant,
                    rttyBaudRate = rttyBaudRate,
                    rttyFrequencyShift = rttyFrequencyShift,
                    rttyPolarity = rttyPolarity,
                    psk31BaudRate = psk31BaudRate,
                    r09BaudRate = r09BaudRate,
                    loraSpreadingFactors = loraSpreadingFactors,
                    loraIqMode = loraIqMode,
                    stationOverlay = stationOverlay,
                    ft8TimingMode = ft8TimingMode,
                    ft8ManualOffsetMs = ft8ManualOffsetMs,
                    clockSyncState = clockSyncState,
                    squelchEnabled = squelchEnabled,
                    minSquelch = 0f,
                    maxSquelch = AppStateRepository.MAX_SQUELCH_MARGIN,
                    squelch = squelch,
                    viewportZoom = viewportZoom,
                    audioVolumeLevel = audioVolumeLevel,
                    audioMuted = audioMuted,
                    stationFavorites = stationFavorites,
                    bandFavorites = bandFavorites,
                    demodulationTabActions = demodulationTabActions
                )
            AnalyzerTabs.PACKETS
                -> saveableStateHolder.SaveableStateProvider(AnalyzerTabs.PACKETS.name) {
                    // RDS programme types are stored as codes and named by the region setting.
                    CompositionLocalProvider(LocalRdsPtyUs provides rdsPtyUs) {
                        DigitalTabComposable(
                            filter = digitalTabFilter,
                            pagingItems = filteredPackets,
                            // Not "listening" while nothing can be decoded.
                            liveMode = currentPacketMode.takeIf { analyzerRunning && digitalStatus.availability.decodes && digitalStatus.link != DigitalLink.FAILED },
                            channelFrequency = channelFrequency,
                            digitalNotice = digitalNotice,
                            digitalTrialSecondsLeft = digitalStatus.trialSecondsLeft,
                            sessionStartMs = sessionStartMs,
                            sourceClock = mainViewModel.sourceClock,
                            pendingDelete = pendingPacketDelete,
                            pendingShare = pendingPacketShare,
                            digitalTabActions = digitalTabActions,
                            overviewSections = mainViewModel.overviewSections,
                            markerState = mapMarkerState,
                            mapHome = mapHome,
                            showMapTrails = showMapTrails,
                            showAllModesOnMap = showAllModesOnMap,
                            pendingFocus = pendingMapFocus,
                            onFocusHandled = mainViewModel::consumeMapFocusRequest,
                            mapTrails = mapTrails,
                            mapLayers = mainViewModel.mapLayers,
                            mapCameraMemory = mainViewModel.digitalMapCameras,
                        )
                    }
                }
            AnalyzerTabs.RECORDING
                -> RecordingTabComposable(
                    analyzerRunning = analyzerRunning,
                    recordingRunning = recordingRunning,
                    basebandRecordingRunning = basebandRecordingRunning,
                    demodulationActive = demodulationEnabled,
                    name = recordingName,
                    frequency = sourceFrequency,
                    sampleRate = sourceSampleRate,
                    onlyRecordWhenSquelchIsSatisfied = recordOnlyWhenSquelchIsSatisfied,
                    squelchEnabled = squelchEnabled,
                    squelch = squelch,
                    minSquelch = 0f,
                    maxSquelch = AppStateRepository.MAX_SQUELCH_MARGIN,
                    stopAfterThreshold = recordingStopAfterThreshold,
                    stopAfterUnit = recordingstopAfterUnit,
                    currentRecordingFileSize = recordingCurrentFileSize,
                    recordingsTotalFileSize = totalRecordingSizeInBytes,
                    recordingStartedElapsedRealtime = recordingStartedElapsedRealtime,
                    recordingTabActions = recordingTabActions
                )
            AnalyzerTabs.SETTINGS
                -> SettingsTabComposable(
                    screenOrientation = screenOrientation,
                    fontSize = fontSize,
                    keepChannelCentered = keepChannelCentered,
                    colorTheme = colorTheme,
                    longPressHelpEnabled = longPressHelpEnabled,
                    reverseTuningWheel = reverseTuningWheel,
                    startAnalyzerOnUsbAttach = startAnalyzerOnUsbAttach,
                    controlDrawerSide = controlDrawerSide,
                    enableLowPerformanceMode = enableLowPerformanceMode,
                    lowPerformanceModeFilterQuality = lowPerformanceModeFilterQuality,
                    showDebugInformation = showDebugInformation,
                    loggingEnabled = loggingEnabled,
                    location = userLocation,
                    settingsTabActions = settingsTabActions
                )
            AnalyzerTabs.ABOUT
                -> AboutTabComposable(
                    appUsageTime = appUsageTimeInSeconds,
                    trialSecondsLeft = trialSecondsLeft,
                    license = license,
                    digitalTrialSecondsLeft = digitalTrialSecondsLeft,
                    billing = billingState,
                    appVersion = appVersion,
                    appBuildType = appBuildType,
                    aboutTabActions = aboutTabActions
                )
        }
    }
}