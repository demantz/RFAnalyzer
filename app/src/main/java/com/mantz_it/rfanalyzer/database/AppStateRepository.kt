package com.mantz_it.rfanalyzer.database

import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mantz_it.rfanalyzer.analyzer.FftProcessorData
import com.mantz_it.rfanalyzer.decoder.DecodedEvent
import com.mantz_it.rfanalyzer.source.HackrfSource
import com.mantz_it.rfanalyzer.source.HydraSdrRfPort
import com.mantz_it.rfanalyzer.ui.composable.DecoderConversionMode
import com.mantz_it.rfanalyzer.ui.composable.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.FftColorMap
import com.mantz_it.rfanalyzer.ui.composable.FftDrawingType
import com.mantz_it.rfanalyzer.ui.composable.FilesourceFileFormat
import com.mantz_it.rfanalyzer.ui.composable.FontSize
import com.mantz_it.rfanalyzer.ui.composable.ScreenOrientation
import com.mantz_it.rfanalyzer.ui.composable.SourceType
import com.mantz_it.rfanalyzer.ui.composable.StopAfterUnit
import com.mantz_it.rfanalyzer.ui.ColorTheme
import com.mantz_it.rfanalyzer.ui.composable.ControlDrawerSide
import com.mantz_it.rfanalyzer.ui.composable.RtlsdrDirectSamplingMode
import com.mantz_it.rfanalyzer.ui.screens.StationsPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.EnumMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <h1>RF Analyzer - State Repository</h1>
 *
 * Module:      AppStateRepository.kt
 * Description: The global repository holding app state and settings. All states/settings are
 * represented as State<T> which can be observed by the UI or other components. Internally all
 * States are either a Setting (is automatically persisted using the DataStore API), a MutableState
 * (not persisted) or a DerivedState (read-only, non-persisted state which is directly derived from other
 * states)
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


// helper function to collect app state variables cleanly
fun <T> CoroutineScope.collectAppState(
    state: AppStateRepository.State<T>,
    block: (T) -> Unit
) = launch {
    state.stateFlow.collect(block)
}

@Singleton
class AppStateRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // Constants
    companion object {
        const val VERTICAL_SCALE_LOWER_BOUNDARY = -100f // Smallest dB value the vertical scale can start
        const val VERTICAL_SCALE_UPPER_BOUNDARY = 10f // Highest dB value the vertical scale can start
        const val DEFAULT_VERTICAL_SCALE_MIN = -60f
        const val DEFAULT_VERTICAL_SCALE_MAX = -10f
        const val TRIAL_VERSTION_USAGE_TIME = 60*60  // 1 hour of usage time
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // General App State
    val welcomeScreenFinished = Setting("welcomeScreenFinished", false, scope, dataStore)
    val notificationPermissionAskedAtLeastOnce = Setting("notificationPermissionAskedAtLeastOnce", false, scope, dataStore)
    val dontAskForNotificationPermission = Setting("dontAskForNotificationPermission", false, scope, dataStore)
    val appVersion = MutableState("-")
    val appBuildType = MutableState("-")

    // A workaround for the RTL-SDR Blog V4: We track connected USB devices to allow lower frequencies (<24MHz) automatically
    val rtlsdrBlogV4connected = MutableState(false)

    // Source Tab
    val sourceType = Setting("sourceType", SourceType.HACKRF, scope, dataStore)
    val sourceName = MutableState("")
    val sourceMinimumFrequency = MutableState(0L)
    val sourceMaximumFrequency = MutableState(20000000000L)
    val sourceSupportedSampleRates = MutableState(listOf(0L))
    val sourceFrequencies = SourceType.entries.associateWith { type -> Setting("sourceFrequency_${type.name}", 97000000L, scope, dataStore) }
    val sourceFrequency = DerivedEnumState(sourceType, sourceFrequencies)
    val sourceSampleRates = SourceType.entries.associateWith { type -> Setting("sourceSampleRate_${type.name}", type.defaultSupportedSampleRates.first(), scope, dataStore) }
    val sourceSampleRate = DerivedEnumState(sourceType, sourceSampleRates)
    val sourceAutomaticSampleRateAdjustment = Setting("sourceAutomaticSampleRateAdjustment", true, scope, dataStore)
    val sourceSignalStartFrequency = DerivedState(sourceFrequency, sourceSampleRate) { sourceFrequency.value - sourceSampleRate.value/2 }
    val sourceSignalEndFrequency = DerivedState(sourceFrequency, sourceSampleRate) { sourceFrequency.value + sourceSampleRate.value/2 }
    val sourceMinimumPossibleSignalFrequency = DerivedState(sourceAutomaticSampleRateAdjustment, sourceMinimumFrequency, sourceSupportedSampleRates) {
        val sampleRate = if (sourceAutomaticSampleRateAdjustment.value) sourceSupportedSampleRates.value.lastOrNull() ?: sourceSampleRate.value else sourceSampleRate.value
        sourceMinimumFrequency.value - sampleRate/2
    }
    val sourceMaximumPossibleSignalFrequency = DerivedState(sourceAutomaticSampleRateAdjustment, sourceMaximumFrequency, sourceSupportedSampleRates) {
        val sampleRate = if (sourceAutomaticSampleRateAdjustment.value) sourceSupportedSampleRates.value.lastOrNull() ?: sourceSampleRate.value else sourceSampleRate.value
        sourceMaximumFrequency.value + sampleRate/2
    }
    val hackrfVgaGainSteps = (0..HackrfSource.MAX_VGA_RX_GAIN step HackrfSource.VGA_RX_GAIN_STEP_SIZE).toList()
    val hackrfVgaGainIndex = Setting("hackrfVgaRxGainIndex", 10, scope, dataStore)
    val hackrfLnaGainSteps = (0..HackrfSource.MAX_LNA_GAIN step HackrfSource.LNA_GAIN_STEP_SIZE).toList()
    val hackrfLnaGainIndex = Setting("hackrfLnaRxGainIndex", 1, scope, dataStore)
    val hackrfAmplifierEnabled = Setting("hackrfAmplifierEnabled", false, scope, dataStore)
    val hackrfAntennaPowerEnabled = Setting("hackrfAntennaPowerEnabled", false, scope, dataStore)
    val hackrfConverterOffset = Setting("hackrfConverterOffset", 0L, scope, dataStore)
    val rtlsdrGainSteps = Setting("rtlsdrGainSteps", listOf(0, 0), scope, dataStore)
    val rtlsdrGainIndex = Setting("rtlsdrGainIndex", 0, scope, dataStore)
    val rtlsdrIFGainSteps = Setting("rtlsdrIFGainSteps", listOf(0, 0), scope, dataStore)
    val rtlsdrIFGainIndex = Setting("rtlsdrIFGainIndex", 0, scope, dataStore)
    val rtlsdrAgcEnabled = Setting("rtlsdrAgcEnabled", false, scope, dataStore)
    val rtlsdrManualGainEnabled = Setting("rtlsdrManualGainEnabled", false, scope, dataStore)
    val rtlsdrConverterOffset = Setting("rtlsdrConvertOffset", 0L, scope, dataStore)
    val rtlsdrExternalServerEnabled = Setting("rtlsdrExternalServerEnabled", false, scope, dataStore)
    val rtlsdrExternalServerIP = Setting("rtlsdrExternalServerIP", "", scope, dataStore)
    val rtlsdrExternalServerPort = Setting("rtlsdrExternalServerPort", 1234, scope, dataStore)
    val rtlsdrFrequencyCorrection = Setting("rtlsdrFrequencyCorrection", 0, scope, dataStore)
    val rtlsdrAllowOutOfBoundFrequency = Setting("rtlsdrAllowOutOfBoundFrequency", false, scope, dataStore)
    val rtlsdrEnableBiasT = Setting("rtlsdrEnableBiasT", false, scope, dataStore)
    val rtlsdrDirectSamplingMode = Setting("rtlsdrDirectSamplingMode", RtlsdrDirectSamplingMode.OFF, scope, dataStore)
    val rtlsdrFrequencyRestrictionsDisabled = DerivedState(rtlsdrBlogV4connected, rtlsdrAllowOutOfBoundFrequency, rtlsdrDirectSamplingMode) {
        rtlsdrAllowOutOfBoundFrequency.value || rtlsdrBlogV4connected.value || rtlsdrDirectSamplingMode.value != RtlsdrDirectSamplingMode.OFF
    }
    val airspyAdvancedGainEnabled = Setting("airspyAdvancedGainEnabled", false, scope, dataStore)
    val airspyVgaGain = Setting("airspyVgaGain", 10, scope, dataStore)
    val airspyLnaGain = Setting("airspyLnaGain", 10, scope, dataStore)
    val airspyMixerGain = Setting("airspyMixerGain", 10, scope, dataStore)
    val airspyLinearityGain = Setting("airspyLinearityGain", 10, scope, dataStore)
    val airspySensitivityGain = Setting("airspySensitivityGain", 10, scope, dataStore)
    val airspyRfBiasEnabled = Setting("airspyRfBiasEnabled", false, scope, dataStore)
    val airspyConverterOffset = Setting("airspyConvertOffset", 0L, scope, dataStore)
    val airspyHfAgcEnabled = Setting("airspyHfAgcEnabled", true, scope, dataStore)
    val airspyHfAgcThreshold = Setting("airspyHfAgcThreshold", false, scope, dataStore)
    val airspyHfAttenuation = Setting("airspyHfAttenuation", 0, scope, dataStore)
    val airspyHfLnaEnabled = Setting("airspyHfLnaEnabled", false, scope, dataStore)
    val airspyHfConverterOffset = Setting("airspyHfConvertOffset", 0L, scope, dataStore)
    val hydraSdrAdvancedGainEnabled = Setting("hydraSdrAdvancedGainEnabled", false, scope, dataStore)
    val hydraSdrVgaGain = Setting("hydraSdrVgaGain", 10, scope, dataStore)
    val hydraSdrLnaGain = Setting("hydraSdrLnaGain", 10, scope, dataStore)
    val hydraSdrMixerGain = Setting("hydraSdrMixerGain", 10, scope, dataStore)
    val hydraSdrLinearityGain = Setting("hydraSdrLinearityGain", 10, scope, dataStore)
    val hydraSdrSensitivityGain = Setting("hydraSdrSensitivityGain", 10, scope, dataStore)
    val hydraSdrRfBiasEnabled = Setting("hydraSdrRfBiasEnabled", false, scope, dataStore)
    val hydraSdrRfPort = Setting("hydraSdrRfPort", HydraSdrRfPort.RX0, scope, dataStore)
    val hydraSdrConverterOffset = Setting("hydraSdrConvertOffset", 0L, scope, dataStore)
    val filesourceUri = MutableState("")
    val filesourceFilename = MutableState("")
    val filesourceFileFormat = MutableState(FilesourceFileFormat.HACKRF)
    val filesourceRepeatEnabled = Setting("filesourceRepeatEnabled", false, scope, dataStore)

    // View Tab
    val fftSize = Setting("fftSize", 16384, scope, dataStore)
    val fftAverageLength = Setting("fftAverageLength", 0, scope, dataStore)
    val fftPeakHold = Setting("fftPeakHold", false, scope, dataStore)
    val maxFrameRate = Setting("maxFrameRate", 30, scope, dataStore)
    val waterfallColorMap = Setting("waterfallColorMap", FftColorMap.GQRX, scope, dataStore)
    val waterfallFps = Setting("waterfallFps", 30, scope, dataStore)
    val fftDrawingType = Setting("fftDrawingType", FftDrawingType.LINE, scope, dataStore)
    val fftRelativeFrequency = Setting("fftRelativeFrequency", false, scope, dataStore)
    val fftWaterfallRatio = Setting("fftWaterfallRatio", 0.4f, scope, dataStore)

    // Demodulation Tab
    val demodulationMode = Setting("demodulationMode", DemodulationMode.OFF, scope, dataStore)
    val demodulationEnabled = DerivedState(demodulationMode) { demodulationMode.value != DemodulationMode.OFF }
    val channelFrequency = Setting("channelFrequency", 97000000L, scope, dataStore)
    val channelWidths = DemodulationMode.entries.associateWith { mode -> Setting("channelWidth_${mode.name}", mode.defaultChannelWidth, scope, dataStore) }
    val channelWidth = DerivedEnumState(demodulationMode, channelWidths)
    val channelStartFrequency = DerivedState(channelFrequency, channelWidth, demodulationMode) {
        when (demodulationMode.value) {
            DemodulationMode.LSB -> channelFrequency.value - channelWidth.value
            DemodulationMode.USB -> channelFrequency.value
            else -> channelFrequency.value - channelWidth.value/2
        }
    }
    val channelEndFrequency = DerivedState(channelFrequency, channelWidth, demodulationMode) {
        when (demodulationMode.value) {
            DemodulationMode.USB -> channelFrequency.value + channelWidth.value
            DemodulationMode.LSB -> channelFrequency.value
            else -> channelFrequency.value + channelWidth.value/2
        }
    }
    val squelchEnabled = Setting("squelchEnabled", false, scope, dataStore)
    val squelch = Setting("squelch", -50f, scope, dataStore)
    val keepChannelCentered = Setting("keepChannelCentered", false, scope, dataStore)
    val audioVolumeLevel = Setting("audioVolumeLevel", 0.33f, scope, dataStore)
    val audioMuted = Setting("audioMuted", false, scope, dataStore)
    val effectiveAudioVolumeLevel = DerivedState(audioVolumeLevel, audioMuted) { if(audioMuted.value) 0f else audioVolumeLevel.value }

    // Recording Tab
    val recordingRunning = MutableState(false)
    val recordingName = Setting("recordingName", "My Recording", scope, dataStore)
    val recordOnlyWhenSquelchIsSatisfied = Setting("recordOnlyWhenSquelchIsSatisfied", false, scope, dataStore)
    val recordingStopAfterThreshold = Setting("recordingStopAfterThreshold", 10, scope, dataStore)
    val recordingstopAfterUnit = Setting("recordingStopAfterUnit", StopAfterUnit.NEVER, scope, dataStore)
    val recordingCurrentFileSize = MutableState(0L)
    val recordingStartedTimestamp = MutableState(0L)

    // Decoder Tab (rtl_433 based decoding)
    val decoderEnabled = Setting("decoderEnabled", false, scope, dataStore)
    val decoderTargetSampleRate = Setting("decoderTargetSampleRate", 250000, scope, dataStore)
    val decoderChannelized = Setting("decoderChannelized", false, scope, dataStore)
    val decoderConversionMode = Setting("decoderConversionMode", DecoderConversionMode.NATIVE, scope, dataStore)
    val decoderAutoLevel = Setting("decoderAutoLevel", true, scope, dataStore)
    val decoderMinSnr = Setting("decoderMinSnr", 9f, scope, dataStore)
    val decoderReportMeta = Setting("decoderReportMeta", true, scope, dataStore)
    val decoderLogToFile = Setting("decoderLogToFile", true, scope, dataStore)
    // User-defined rtl_433 "flex" (-X) decoder specs; see decoder/FlexDecoder.kt
    val decoderFlexDecoders = Setting("decoderFlexDecoders", emptyList<String>(), scope, dataStore)
    val decoderRunning = MutableState(false)
    val decoderEventCount = MutableState(0)
    val decoderLastModel = MutableState("")
    val decoderLastEventTimestamp = MutableState(0L)
    val decoderLogFilePath = MutableState("")
    val decodedEvents = MutableState<List<DecodedEvent>>(emptyList())

    // Settings Tab
    val screenOrientation = Setting("screenOrientation", ScreenOrientation.AUTO, scope, dataStore)
    val fontSize = Setting("fontSize", FontSize.NORMAL, scope, dataStore)
    val showDebugInformation = Setting("showDebugInformation", false, scope, dataStore)
    val loggingEnabled = Setting("loggingEnabled", false, scope, dataStore)
    val colorTheme = Setting("colorTheme", ColorTheme.RFANALYZER_DARK, scope, dataStore)
    val controlDrawerSide = Setting("controlDrawerSide", ControlDrawerSide.RIGHT, scope, dataStore)
    val longPressHelpEnabled = Setting("longPressHelpEnabled", true, scope, dataStore)
    val reverseTuningWheel = Setting("reverseTuningWheel", false, scope, dataStore)
    val enableLowPerformanceMode = Setting("enableLowPerformanceMode", false, scope, dataStore)
    val lowPerformanceModeFilterQuality = Setting("lowPerformanceModeFilterQuality", 0.42f, scope, dataStore)

    // Recordings Screen
    val displayOnlyFavoriteRecordings = Setting("displayOnlyFavoriteRecordings", false, scope, dataStore)

    // Station & Band Bookmarks
    val bookmarkManagerScreenPage = MutableState(StationsPage.BOOKMARKLISTS)
    val displayDefaultBandImportDialog = Setting("showDefaultBandImportDialog", true, scope, dataStore)
    val displayBookmarkTutorial = Setting("showBookmarkTutorial", true, scope, dataStore)
    val displayedLegacyImportDialog = Setting("displayedLegacyImportDialog", false, scope, dataStore)
    val onlineStationDownloadState = EnumMap(OnlineStationProvider.entries.associateWith { MutableState<DownloadState>(DownloadState.Idle) })
    val bookmarkListFilterSearchString = Setting("bookmarkListFilterSearchString", "", scope, dataStore)
    val stationFilterForList = StationFilterSettings("list", scope, dataStore)
    val bandFilterForList = BandFilterSettings("list", scope, dataStore)
    val displayStationsInFft = Setting("displayStationsInFft", true, scope, dataStore)
    val useCustomFftStationFilter = Setting("synchronizeStationFilters", true, scope, dataStore)
    val stationFilterForFft = StationFilterSettings("fft", scope, dataStore)
    val displayBandsInFft = Setting("displayStationsInFft", true, scope, dataStore)
    val useCustomFftBandFilter = Setting("synchronizeBandFilters", true, scope, dataStore)
    val bandFilterForFft = BandFilterSettings("fft", scope, dataStore)

    // Analyzer Surface
    val viewportFrequency =  Setting("viewportFrequency", 97000000L, scope, dataStore)
    val viewportSampleRate = Setting("viewportSampleRate", 2000000L, scope, dataStore)
    val viewportVerticalScaleMin = Setting("viewportVerticalScaleMin", -60f, scope, dataStore)
    val viewportVerticalScaleMax = Setting("viewportVerticalScaleMax", 0f, scope, dataStore)
    val viewportStartFrequency = DerivedState(viewportFrequency, viewportSampleRate) { viewportFrequency.value - viewportSampleRate.value/2 }
    val viewportEndFrequency = DerivedState(viewportFrequency, viewportSampleRate) { viewportFrequency.value + viewportSampleRate.value/2 }
    val viewportZoom = DerivedState(sourceSampleRate, viewportSampleRate) {
        val tmp = (1f - (viewportSampleRate.value.toFloat()/sourceSampleRate.value)).coerceIn(0f, 1f)
        if (tmp.isNaN()) 0f else tmp
    }

    // Analyzer State
    val analyzerRunning = MutableState(false)
    val analyzerStartPending = MutableState(false) // Indicates if the analyzer is currently getting started
    val averageSignalStrength = MutableState(-999f)
    val squelchSatisfied = DerivedState(averageSignalStrength, squelch, squelchEnabled) {
        if (squelchEnabled.value)
            averageSignalStrength.value > squelch.value
        else
            true  // always satisfied when squelch is disabled
    }

    // Billing State
    val appUsageTimeInSeconds = Setting("appUsageTimeInSeconds", 0, scope, dataStore)
    val isFullVersion = Setting("isFullVersion", false, scope, dataStore)
    val isPurchasePending = Setting("isPurchasePending", false, scope, dataStore)
    val isAppUsageTimeUsedUp = DerivedState(appUsageTimeInSeconds) { appUsageTimeInSeconds.value > TRIAL_VERSTION_USAGE_TIME }

    // Donation Dialog:
    val timestampOfLastDonationDialog = Setting("timestampOfLastDonationDialog", 0, scope, dataStore)
    val donationDialogCounter = Setting("donationDialogCounter", 0, scope, dataStore)


    // FFT Data
    val fftProcessorData = FftProcessorData()


    // Service Events
    private val _analyzerEvents = MutableSharedFlow<AnalyzerEvent?>()
    val analyzerEvents: SharedFlow<AnalyzerEvent?> = _analyzerEvents
    sealed class AnalyzerEvent {
        data class RecordingFinished(val finalSize: Long, val recordingFile: File): AnalyzerEvent()
        data class SourceFailure(val message: String): AnalyzerEvent()
    }
    fun emitAnalyzerEvent(event: AnalyzerEvent){ scope.launch { _analyzerEvents.emit(event) } }

    init {
        scope.launch {
            sourceType.awaitInitialized()
            sourceSupportedSampleRates.set(sourceType.value.defaultSupportedSampleRates)
        }
    }

    // --- Inner Helper Classes ----

    inner class StationFilterSettings(
        prefix: String,
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
    ) {
        private val bookmarkLists = Setting("$prefix.stationFilterBookmarkLists", emptyList<Long>(), scope, dataStore)
        private val search = Setting("$prefix.stationFilterSearch", "", scope, dataStore)
        private val minFrequency = Setting("$prefix.stationFilterMinFrequency", 0L, scope, dataStore)
        private val maxFrequency = Setting("$prefix.stationFilterMaxFrequency", 0L, scope, dataStore)
        private val mode = Setting("$prefix.stationFilterMode", emptyList<String>(), scope, dataStore)
        private val onlyFavorites = Setting("$prefix.stationFilterOnlyFavorites", false, scope, dataStore)
        private val onlyOnAirNow = Setting("$prefix.stationFilterOnlyOnAirNow", false, scope, dataStore)
        private val sources = Setting("$prefix.stationFilterSources", emptyList<String>(), scope, dataStore)
        val state = DerivedState(
            bookmarkLists,
            search,
            minFrequency,
            maxFrequency,
            mode,
            onlyFavorites,
            onlyOnAirNow,
            sources,
        ) {
            StationFilter(
                bookmarkLists = bookmarkLists.value.toSet(),
                search = search.value,
                minFrequency = minFrequency.value,
                maxFrequency = maxFrequency.value,
                mode = mode.value.mapNotNull { DemodulationMode.entries.firstOrNull { m -> m.name == it } }.toSet(),
                onlyFavorites = onlyFavorites.value,
                onlyOnAirNow = onlyOnAirNow.value,
                sources = sources.value.mapNotNull { SourceProvider.entries.firstOrNull { s -> s.name == it } }.toSet()
            )
        }
        fun set(filter: StationFilter) {
            bookmarkLists.set(filter.bookmarkLists.toList())
            search.set(filter.search)
            minFrequency.set(filter.minFrequency)
            maxFrequency.set(filter.maxFrequency)
            mode.set(filter.mode.map { it.name })
            onlyFavorites.set(filter.onlyFavorites)
            onlyOnAirNow.set(filter.onlyOnAirNow)
            sources.set(filter.sources.map { it.name })
        }
    }
    inner class BandFilterSettings(
        prefix: String,
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
    ) {
        private val onlyFavorites = Setting("$prefix.bandFilterOnlyFavorites", false, scope, dataStore)
        private val minFrequency = Setting("$prefix.bandFilterMinFrequency", 0L, scope, dataStore)
        private val maxFrequency = Setting("$prefix.bandFilterMaxFrequency", 0L, scope, dataStore)
        private val search = Setting("$prefix.bandFilterSearch", "", scope, dataStore)
        private val bookmarkLists = Setting("$prefix.bandFilterBookmarkLists", emptyList<Long>(), scope, dataStore)
        val state = DerivedState(
            onlyFavorites,
            minFrequency,
            maxFrequency,
            search,
            bookmarkLists
        ) {
            BandFilter(
                onlyFavorites = onlyFavorites.value,
                minFrequency = minFrequency.value,
                maxFrequency = maxFrequency.value,
                search = search.value,
                bookmarkLists = bookmarkLists.value.toSet()
            )
        }
        fun set(filter: BandFilter) {
            onlyFavorites.set(filter.onlyFavorites)
            minFrequency.set(filter.minFrequency)
            maxFrequency.set(filter.maxFrequency)
            search.set(filter.search)
            bookmarkLists.set(filter.bookmarkLists.toList())
        }
    }

    open class State<T>(initialValue: T) {
        protected val flow = MutableStateFlow(initialValue)
        val stateFlow: StateFlow<T>
            get() { return flow.asStateFlow() }
        open val value: T
            get() { return flow.value }

    }

    open class MutableState<T>(initialValue: T): State<T>(initialValue) {
        protected val listeners = mutableListOf<(T) -> Unit>()
        fun addOnChangedListener(listener: (T) -> Unit) { listeners.add(listener) }
        fun removeOnChangedListener(listener: (T) -> Unit) { listeners.remove(listener) }

        open fun set(value: T) {
            if (flow.value != value) {
                flow.value = value
                listeners.forEach { it(value) }
            }
        }
    }

    // Wrapper class for persisted States (i.e. Settings)
    inner class Setting<T>(
        private val keyName: String,
        default: T,
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
        private val debugLogSet: Boolean = false
    ) : MutableState<T>(default) {
        val initializationFinished = CompletableDeferred<Unit>()
        suspend fun awaitInitialized() {
            initializationFinished.await()
        }
        init {
            val key: Preferences.Key<T>? = when (default) {
                is Boolean -> booleanPreferencesKey(keyName)
                is Int -> intPreferencesKey(keyName)
                is Long -> longPreferencesKey(keyName)
                is Float -> floatPreferencesKey(keyName)
                is String -> stringPreferencesKey(keyName)
                is Enum<*> -> null
                is List<*> -> null
                else -> throw IllegalArgumentException("Unsupported setting type (setting: ${keyName}")
            } as Preferences.Key<T>?

            scope.launch {
                // Load initial value
                val saved = dataStore.data
                    .map { prefs -> when(default) {
                        is Enum<*> -> {  // up until 2.1 this was stored as int (ordinal) but now we store the enum name (using a different key)
                            val enumClass = default!!::class.java
                            // If we find the string type key take it, otherwise try using the legacy int key:
                            val stringKey = stringPreferencesKey(keyName + "Enum")  // name new key with 'Enum' suffix
                            val legacyIntKey = intPreferencesKey(keyName)
                            val prefsString = prefs[stringKey]
                            val prefsInt = prefs[legacyIntKey]
                            val enumValue = when {
                                prefsString != null -> enumClass.enumConstants.firstOrNull { it.name == prefsString }
                                prefsInt != null -> enumClass.enumConstants.getOrNull(prefsInt)
                                else -> default
                            } ?: default

                            // If we loaded from legacy int, immediately migrate to new key
                            if (prefsString == null && prefsInt != null) {
                                scope.launch {
                                    dataStore.edit { editPrefs ->
                                        editPrefs[stringKey] = enumValue.name
                                        editPrefs.remove(legacyIntKey)  // cleanup
                                    }
                                }
                            }

                            enumValue
                        }
                        is List<*> -> {
                            // Store lists as comma separated list (string)
                            val stringKey = stringPreferencesKey(keyName)
                            val stringValue = prefs[stringKey]
                            if (stringValue == null)
                                default as T
                            else if (stringValue.length > 2 && stringValue[1] == ':') {
                                // NEW FORMAT
                                val payload = stringValue.substring(2)
                                when (stringValue[0]) {
                                    'I' -> payload.split(",").mapNotNull { it.toIntOrNull() }
                                    'L' -> payload.split(",").mapNotNull { it.toLongOrNull() }
                                    'S' -> payload.split("(?<!\\\\),".toRegex())
                                        .map { it.replace("\\,", ",") }
                                    else -> throw IllegalArgumentException("Unsupported list type in setting (setting: ${keyName}, listtype: ${stringValue[0]}")
                                } as T
                            }
                            else {
                                // LEGACY FORMAT (List<Int>) (RF Analyzer 2.1.1 and below)
                                // Or just an empty list ("")
                                stringValue.split(",").mapNotNull { it.toIntOrNull() } as T
                            }
                        }
                        else -> {
                            if (key == null) throw IllegalArgumentException("Unsupported setting type (setting: ${keyName})")
                            else prefs[key] ?: default
                        }
                    }}
                    .firstOrNull()

                if (saved != null) flow.value = saved
                listeners.toList().forEach { it(value) } // Iterate over a copy to avoid ConcurrentModificationException

                initializationFinished.complete(Unit) // mark setting as loaded completely and safe to access

                // Persist changes (debounced)
                flow
                    .drop(1)
                    .debounce(500)
                    .distinctUntilChanged()
                    .collectLatest { newValue ->
                        dataStore.edit { prefs ->
                            when (newValue) {
                                is Enum<*> -> prefs[stringPreferencesKey(keyName + "Enum")] = newValue.name
                                is List<*> -> {
                                    prefs[stringPreferencesKey(keyName)] =
                                        if (newValue.isEmpty()) ""
                                        else when (newValue.first()) {
                                            is Int -> "I:" + newValue.joinToString(",")
                                            is Long -> "L:" + newValue.joinToString(",")
                                            is String -> "S:" + newValue.joinToString(",") { (it as String).replace(",", "\\,") }
                                            else -> throw IllegalArgumentException("Unsupported list element type (setting: ${keyName}): ${newValue.first()?.javaClass?.name}")
                                        }
                                }
                                else -> key?.let { prefs[it] = newValue }
                            }
                        }
                    }
            }
        }

        // Debugging:
        override fun set(value: T) {
            if(debugLogSet) {
                Log.d("AppStateRepository [SET]", "set '${keyName}' = $value  (was ${flow.value})")
                Throwable().stackTrace.forEachIndexed { index, element ->
                    if (element.className.startsWith("com.mantz_it."))
                        Log.d("AppStateRepository [SET]", "     #$index ${element.className}.${element.methodName} (${element.fileName}:${element.lineNumber})")
                }
            }
            super.set(value)
        }
    }

    // A subclass of State which represents a derived State from a set of States
    // Can be used like a normal state to always have access to the currently selected enum state
    class DerivedEnumState<E : Enum<E>, T>(
        private val enumSelector: MutableState<E>,
        private val states: Map<E, MutableState<T>>,
    ) : MutableState<T>(states[enumSelector.value]!!.value) {
        init {
            fun update() {
                val newValue = states[enumSelector.value]?.value
                if (newValue != null && newValue != flow.value)
                    flow.value = newValue
            }
            enumSelector.addOnChangedListener { update() }
            states.forEach { (_, state) -> state.addOnChangedListener { update() } }
            // There could be a race condition during initialization when Settings are loaded from the Datastore.
            // If the OnChanged listener was not attached in time, we missed the updated value. So let's get it again:
            update()
        }

        override fun set(value: T) {
            val selectedEnum = enumSelector.value
            states[selectedEnum]?.set(value)
            listeners.forEach { it(value) }
        }

        override val value: T
            get() { return states[enumSelector.value]!!.value }
    }

    class DerivedState<T>(
        vararg dependencies: MutableState<*>,
        private val compute: () -> T
    ) : State<T>(compute()) {

        init {
            fun update() {
                val newValue = compute()
                if (flow.value != newValue)
                    flow.value = newValue
            }
            dependencies.forEach {
                it.addOnChangedListener { update() }
            }
            // There could be a race condition during initialization when Settings are loaded from the Datastore.
            // If the OnChanged listener was not attached in time, we missed the updated value. So let's get it again:
            update()
        }

        // Direct access to underlying dependencies!
        override val value: T
            get() = compute()
    }


}