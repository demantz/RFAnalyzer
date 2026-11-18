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
import com.mantz_it.libhydrasdr.HydraSdrDeviceInfo
import com.mantz_it.libhydrasdr.HydraSdrGainType
import com.mantz_it.rfanalyzer.billing.licenseOfCache
import com.mantz_it.rfanalyzer.source.AirspyGainPreset
import com.mantz_it.rfanalyzer.analyzer.FftProcessorData
import com.mantz_it.rfanalyzer.source.HackrfSource
import com.mantz_it.rfanalyzer.source.KiwiSdrSource
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.tabs.FftColorMap
import com.mantz_it.rfanalyzer.ui.tabs.FftDrawingType
import com.mantz_it.rfanalyzer.ui.tabs.FilesourceFileFormat
import com.mantz_it.rfanalyzer.ui.tabs.FontSize
import com.mantz_it.rfanalyzer.ui.tabs.ScreenOrientation
import com.mantz_it.rfanalyzer.ui.tabs.SourceType
import com.mantz_it.rfanalyzer.ui.tabs.StopAfterUnit
import com.mantz_it.rfanalyzer.ui.ColorTheme
import com.mantz_it.rfanalyzer.ui.tabs.ControlDrawerSide
import com.mantz_it.rfanalyzer.ui.tabs.RtlsdrDirectSamplingMode
import com.mantz_it.rfanalyzer.ui.screens.StationsPage
import com.mantz_it.rfanalyzer.digiext.db.defaultRetention
import com.mantz_it.rfanalyzer.digiext.rds.RdsPtyTable
import com.mantz_it.rfanalyzer.location.PositionMode
import com.mantz_it.rfanalyzer_ipc.DigitalDemodParams
import com.mantz_it.rfanalyzer_ipc.Ft8TimingMode
import com.mantz_it.rfanalyzer_ipc.RttyPolarity
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
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
import com.mantz_it.rfanalyzer.digiext.db.DigitalFilter
import com.mantz_it.rfanalyzer.source.RtlsdrSource
import com.mantz_it.rfanalyzer.source.AirspySource
import com.mantz_it.rfanalyzer.source.AirspyHfSource
import com.mantz_it.rfanalyzer_ipc.LoraSpreadingFactors
import com.mantz_it.rfanalyzer_ipc.LoraIqMode
import kotlin.math.abs

/**
 * <h1>RF Analyzer - State Repository</h1>
 *
 * Module:      AppStateRepository.kt
 * Description: The global repository holding app state and settings. All states/settings are
 *              represented as State<T> which can be observed by the UI or other components.
 *              Internally all States are either a Setting (is automatically persisted using the
 *              DataStore API), a MutableState (not persisted) or a DerivedState (read-only,
 *              non-persisted state which is directly derived from other states)
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
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
    private val dataStore: DataStore<Preferences>,
    /** The settings in PrivateSettings.KEYS: left out of cloud backups. Tests may pass one store for both. */
    private val privateDataStore: DataStore<Preferences> = dataStore,
) {
    // Constants
    companion object {
        // All levels in dBFS per FFT bin (a full-scale tone reads 0 dB).
        const val VERTICAL_SCALE_LOWER_BOUNDARY = -150f // Smallest dB value the vertical scale can start
        const val VERTICAL_SCALE_UPPER_BOUNDARY = 10f // Highest dB value the vertical scale can start
        const val DEFAULT_VERTICAL_SCALE_MIN = -110f
        const val DEFAULT_VERTICAL_SCALE_MAX = -20f
        /** The squelch opens this many dB above the noise floor (see Squelch.kt). */
        const val DEFAULT_SQUELCH_MARGIN = 10f
        const val MAX_SQUELCH_MARGIN = 40f
        const val TRIAL_VERSTION_USAGE_TIME = 60*60  // 1 hour of usage time
        /** Digital modes without the full version: this much decoding (see digitalTrialUsedSeconds). */
        const val DIGITAL_TRIAL_SECONDS = 2*60*60
        const val CURRENT_WHATS_NEW_VERSION = 1  // bump whenever the What's New screen content is replaced
        const val LOW_PERFORMANCE_MAX_FRAME_RATE = 30

        /**
         * Converts a level persisted by 2.2.x and earlier into dBFS: those versions showed
         * 10*log10(|X|/N) - half the dB of the power, without the window's coherent gain (0.42).
         * Exact for an FFT without padding; with padding the old values were a few dB lower still.
         */
        fun legacyDbToDbfs(legacyDb: Float): Float = 2f * legacyDb + 7.5f

        /**
         * Returns the vertical scale (minDB, maxDB) made usable: finite, inside the boundaries and
         * min < max. A non-finite or inverted scale makes every slider whose range is derived from
         * it (vertical scale, squelch) throw when it is shown - and once persisted, on every start.
         */
        fun sanitizeVerticalScale(minDB: Float, maxDB: Float): Pair<Float, Float> {
            if (!minDB.isFinite() || !maxDB.isFinite())
                return Pair(DEFAULT_VERTICAL_SCALE_MIN, DEFAULT_VERTICAL_SCALE_MAX)
            val min = minDB.coerceIn(VERTICAL_SCALE_LOWER_BOUNDARY, VERTICAL_SCALE_UPPER_BOUNDARY - 1f)
            val max = maxDB.coerceIn(min + 1f, VERTICAL_SCALE_UPPER_BOUNDARY)
            return Pair(min, max)
        }

        /**
         * Where a demodulation channel outside the source signal ([signalStart]..[signalEnd]) goes:
         * the centre of the viewport once the analyzer surface has pulled it into the signal (the
         * signal's centre if it lies completely outside). Null when the channel is inside already.
         */
        fun channelFrequencyInsideSignal(channel: Long, signalStart: Long, signalEnd: Long, viewportFrequency: Long, viewportSampleRate: Long): Long? {
            if (channel in signalStart..signalEnd) return null
            val half = minOf(viewportSampleRate, signalEnd - signalStart) / 2
            if (viewportFrequency + half < signalStart || viewportFrequency - half > signalEnd) return (signalStart + signalEnd) / 2
            return viewportFrequency.coerceIn(signalStart + half, signalEnd - half)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // General App State
    val welcomeScreenFinished = Setting("welcomeScreenFinished", false, scope, dataStore)
    val lastSeenWhatsNewVersion = Setting("lastSeenWhatsNewVersion", 0, scope, dataStore)
    val notificationPermissionAskedAtLeastOnce = Setting("notificationPermissionAskedAtLeastOnce", false, scope, dataStore)
    val dontAskForNotificationPermission = Setting("dontAskForNotificationPermission", false, scope, dataStore)
    val appVersion = MutableState("-")
    val appBuildType = MutableState("-")

    // Source Tab
    val sourceType = Setting("sourceType", SourceType.HACKRF, scope, dataStore)
    val sourceName = MutableState("")
    val sourceMinimumFrequency = MutableState(0L)
    val sourceMaximumFrequency = MutableState(20000000000L)
    val sourceSupportedSampleRates = MutableState(listOf(0L))
    // A KiwiSDR receives 0-30 MHz, so it starts in the 40 m band instead of on FM broadcast.
    val sourceFrequencies = SourceType.entries.associateWith { type ->
        Setting("sourceFrequency_${type.name}", if (type == SourceType.KIWISDR) 7_100_000L else 97_000_000L, scope, dataStore)
    }
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
    val rtlsdrEnableBiasT = Setting("rtlsdrEnableBiasT", false, scope, dataStore)
    val rtlsdrDirectSamplingMode = Setting("rtlsdrDirectSamplingMode", RtlsdrDirectSamplingMode.OFF, scope, dataStore)
    val airspyAdvancedGainEnabled = Setting("airspyAdvancedGainEnabled", false, scope, dataStore)
    val airspyVgaGain = Setting("airspyVgaGain", 10, scope, dataStore)
    val airspyLnaGain = Setting("airspyLnaGain", 10, scope, dataStore)
    val airspyMixerGain = Setting("airspyMixerGain", 10, scope, dataStore)
    val airspyLinearityGain = Setting("airspyLinearityGain", 14, scope, dataStore)    // 14: see hydraSdrLinearityGain
    val airspySensitivityGain = Setting("airspySensitivityGain", 14, scope, dataStore)
    val airspyGainPreset = Setting("airspyGainPreset", AirspyGainPreset.LINEARITY, scope, dataStore)
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
    // 14 rather than the device default 10: at 10 weak signals sit at the ADC noise floor (on a DAB
    // mux: Linearity 10 ~10 dB SNR, 14 ~20 dB, overload from ~18 in a strong FM area)
    val hydraSdrLinearityGain = Setting("hydraSdrLinearityGain", 14, scope, dataStore)
    val hydraSdrSensitivityGain = Setting("hydraSdrSensitivityGain", 14, scope, dataStore)
    val hydraSdrGainPreset = Setting("hydraSdrGainPreset", HydraSdrGainType.LINEARITY, scope, dataStore)
    val hydraSdrRfBiasEnabled = Setting("hydraSdrRfBiasEnabled", false, scope, dataStore)
    // A new key: 2.2.1 stored an enum ("hydraSdrRfPortEnum"), and the bare "hydraSdrRfPort" is the
    // enum path's legacy int key (Setting), which can hold an unrelated ordinal. The port resets to
    // the first one reported, once, on the update.
    val hydraSdrRfPortIndex = Setting("hydraSdrRfPortIndex", 0, scope, dataStore)
    val hydraSdrConverterOffset = Setting("hydraSdrConvertOffset", 0L, scope, dataStore)
    // The open device's capabilities (gain ranges, RF ports, frequency range), set by AnalyzerService
    // like sourceSupportedSampleRates; null until a HydraSdrSource is open.
    val hydraSdrDeviceInfo = MutableState<HydraSdrDeviceInfo?>(null)
    val kiwiSdrHost = Setting("kiwiSdrHost", "", scope, dataStore)
    val kiwiSdrPort = Setting("kiwiSdrPort", KiwiSdrSource.DEFAULT_PORT, scope, dataStore)
    val kiwiSdrPassword = Setting("kiwiSdrPassword", "", scope, privateDataStore)
    val kiwiSdrUseTls = Setting("kiwiSdrUseTls", false, scope, dataStore)
    val kiwiSdrPublicListCacheJson = Setting("kiwiSdrPublicListCacheJson", "", scope, dataStore)
    val kiwiSdrPublicListCacheTimestamp = Setting("kiwiSdrPublicListCacheTimestamp", 0L, scope, dataStore)
    val filesourceUri = MutableState("")
    val filesourceFilename = MutableState("")
    val filesourceFileFormat = Setting("filesourceFileFormat", FilesourceFileFormat.HACKRF, scope, dataStore)
    val filesourceTimestamp = MutableState(0L)
    val filesourceRepeatEnabled = Setting("filesourceRepeatEnabled", false, scope, dataStore)

    // Display Tab
    val fftSize = Setting("fftSize", 16384, scope, dataStore)
    val fftAverageLength = Setting("fftAverageLength", 0, scope, dataStore)
    val fftPeakHold = Setting("fftPeakHold", false, scope, dataStore)
    val maxFrameRate = Setting("maxFrameRate", 60, scope, dataStore)
    val waterfallColorMap = Setting("waterfallColorMap", FftColorMap.GQRX, scope, dataStore)
    val waterfallFps = Setting("waterfallFps", 30, scope, dataStore)
    val fftDrawingType = Setting("fftDrawingType", FftDrawingType.LINE, scope, dataStore)
    val fftRelativeFrequency = Setting("fftRelativeFrequency", false, scope, dataStore)
    val fftWaterfallRatio = Setting("fftWaterfallRatio", 0.3f, scope, dataStore)
    val fftPaddingRatio = Setting("fftPaddingRatio", 0f, scope, dataStore)
    val manualFftSizeEnabled = Setting("manualFftSizeEnabled", false, scope, dataStore)

    // Demodulation Tab
    val demodulationMode = Setting("demodulationMode", DemodulationMode.OFF, scope, dataStore)
    val demodulationWfmStereoAndRdsEnabled = Setting("demodulationWfmStereoAndRdsEnabled", false, scope, dataStore)
    val demodulationWfmRdsPtyTable = Setting("demodulationWfmRdsPtyTable", RdsPtyTable.AUTO, scope, dataStore)
    val demodulationEnabled = DerivedState(demodulationMode) { demodulationMode.value != DemodulationMode.OFF }
    val channelFrequency = Setting("channelFrequency", 97000000L, scope, dataStore)
    val channelWidths = DemodulationMode.entries.associateWith { mode -> Setting("channelWidth_${mode.name}", mode.defaultChannelWidth, scope, dataStore) }
    val channelWidth = DerivedEnumState(demodulationMode, channelWidths)
    val tuneStepHzs = DemodulationMode.entries.associateWith { mode -> Setting("tuneStepHz_${mode.name}", mode.tuneStepDistance, scope, dataStore) }
    val tuneStepHz = DerivedEnumState(demodulationMode, tuneStepHzs)
    // 0 = Auto (detected by the demodulator); interpreted in DemodulationTab's POCSAG branch and
    // AnalyzerService's DigitalDemodParams.Pocsag mapping.
    val pocsagBaudRate = Setting("pocsagBaudRate", 0, scope, dataStore)
    // AX.25 variant by baud rate (300 = HF_300, 1200 = VHF_1200/Bell202, 9600 = G3RUH_9600).
    // Switching it snaps the channel width (MainViewModel.onAx25VariantChanged): the variants need
    // very different widths but share the one channelWidth_AX25 setting.
    val ax25Variant = Setting("ax25Variant", 1200, scope, dataStore)
    // RTTY baud rate (45.45/50/75/100) and mark/space frequency shift (85/170/450/850 Hz), both
    // DigitalDemodParams.Rtty.AUTO (0) = detected by the demodulator, the default. A fixed
    // frequencyShift needs a channelWidth snap, in both directions - see MainViewModel.kt's
    // onRttyFrequencyShiftChanged/onChannelWidthChanged - since the channel width indicator is how
    // the user visually confirms the shift setting matches the signal.
    val rttyBaudRate = Setting("rttyBaudRate", DigitalDemodParams.Rtty.AUTO, scope, dataStore)
    val rttyFrequencyShift = Setting("rttyFrequencyShift", DigitalDemodParams.Rtty.AUTO, scope, dataStore)
    // Which tone is mark (see RttyPolarity); AUTO decodes both and keeps the one that fits.
    val rttyPolarity = Setting("rttyPolarity", RttyPolarity.AUTO, scope, dataStore)
    // PSK31/63/125 symbol rate (31.25/62.5/125), DigitalDemodParams.Psk31.AUTO (0) = detected per
    // transmission, the default. Its capture width is the PSK31 channel width.
    val psk31BaudRate = Setting("psk31BaudRate", DigitalDemodParams.Psk31.AUTO, scope, dataStore)
    // R09 FFSK bit rate (2400/1200), DigitalDemodParams.R09.AUTO (0) = both decoded, the default.
    val r09BaudRate = Setting("r09BaudRate", DigitalDemodParams.R09.AUTO, scope, dataStore)
    // LoRa spreading factors (a LoraSpreadingFactors set; AUTO = the receiver's SF7-12) and chirp
    // direction (AUTO = both). A fixed choice needs less CPU and keeps out what isn't wanted.
    val loraSpreadingFactors = Setting("loraSpreadingFactors", LoraSpreadingFactors.AUTO, scope, dataStore)
    val loraIqMode = Setting("loraIqMode", LoraIqMode.AUTO, scope, dataStore)
    // Where FT8's period timing comes from (see Ft8TimingMode); Automatic needs nothing from the user.
    val ft8TimingMode = Setting("ft8TimingMode", Ft8TimingMode.AUTOMATIC, scope, dataStore)
    // Manual timing's offset (ms, +/-), added to the sample timestamps. Both reach the extension
    // through AnalyzerService.applyDemodulationSettings(), like pocsagBaudRate.
    val ft8TimeOffsetMs = Setting("ft8TimeOffsetMs", 0, scope, dataStore)
    // User-added MeshCore hashtag channel names (without the leading '#') whose group chat is
    // decrypted, on top of the built-in ones. App-side only (MeshCore decryption runs in the app):
    // AnalyzerService feeds it to MeshCoreChannelRegistry.updateCustomChannels(), not to the extension.
    val meshCoreCustomChannels = Setting("meshCoreCustomChannels", emptyList<String>(), scope, dataStore)
    val channelStartFrequency = DerivedState(channelFrequency, channelWidth, demodulationMode) {
        when (demodulationMode.value) {
            DemodulationMode.LSB -> channelFrequency.value - channelWidth.value
            DemodulationMode.USB, DemodulationMode.FT8 -> channelFrequency.value
            else -> channelFrequency.value - channelWidth.value/2
        }
    }
    val channelEndFrequency = DerivedState(channelFrequency, channelWidth, demodulationMode) {
        when (demodulationMode.value) {
            DemodulationMode.USB, DemodulationMode.FT8 -> channelFrequency.value + channelWidth.value
            DemodulationMode.LSB -> channelFrequency.value
            else -> channelFrequency.value + channelWidth.value/2
        }
    }
    val squelchEnableds = DemodulationMode.entries.associateWith { mode -> Setting("squelchEnabled_${mode.name}", false, scope, dataStore) }
    val squelchEnabled = DerivedEnumState(demodulationMode, squelchEnableds)
    // dB above the noise floor. 2.2.1's absolute, global level ("squelch") is not carried over: it
    // depended on the SDR, its gain and the FFT size.
    val squelchMargins = DemodulationMode.entries.associateWith { mode -> Setting("squelchMarginDb_${mode.name}", DEFAULT_SQUELCH_MARGIN, scope, dataStore) }
    val squelchMargin = DerivedEnumState(demodulationMode, squelchMargins)
    val keepChannelCentered = Setting("keepChannelCentered", false, scope, dataStore)

    // A tuned station's parameters, laid over the settings below while the channel stays on it (see
    // StationOverlay.kt). The demodulator, the Demodulation tab and the FFT read the effective*
    // values; a hand edit writes the setting and releases that value (releaseOverlayParameter).
    val stationOverlay = MutableState<StationOverlay?>(null)

    private fun <T> overlayValue(field: (TemporaryParameters) -> T?): T? =
        stationOverlay.value?.takeIf { it.mode == demodulationMode.value }?.let { field(it.params) }

    private fun <T> overlaid(setting: MutableState<T>, field: (TemporaryParameters) -> T?) =
        DerivedState(setting, stationOverlay, demodulationMode) { overlayValue(field) ?: setting.value }

    /** The squelch exists for the analog modes only: a digital mode's decoder finds the signal by
     *  itself, so a bookmark's squelch on a digital station is ignored. */
    val effectiveSquelchEnabled = DerivedState(squelchEnabled, stationOverlay, demodulationMode) {
        !demodulationMode.value.isDigitalExtension && (overlayValue { it.squelchEnabled } ?: squelchEnabled.value)
    }
    val effectiveSquelchMargin = overlaid(squelchMargin) { it.squelchMarginDb }
    val effectiveRttyBaudRate = overlaid(rttyBaudRate) { it.rttyBaudRate }
    val effectiveRttyFrequencyShift = overlaid(rttyFrequencyShift) { it.rttyFrequencyShift }
    val effectiveRttyPolarity = overlaid(rttyPolarity) { it.rttyPolarity }
    val effectivePocsagBaudRate = overlaid(pocsagBaudRate) { it.pocsagBaudRate }
    val effectivePsk31BaudRate = overlaid(psk31BaudRate) { it.psk31BaudRate }
    val effectiveR09BaudRate = overlaid(r09BaudRate) { it.r09BaudRate }
    val effectiveLoraSpreadingFactors = overlaid(loraSpreadingFactors) { it.loraSpreadingFactors }
    val effectiveLoraIqMode = overlaid(loraIqMode) { it.loraIqMode }

    /** A hand edit of an overlaid parameter: it is the user's setting now, no longer the station's. */
    fun releaseOverlayParameter(release: TemporaryParameters.() -> TemporaryParameters) {
        val overlay = stationOverlay.value ?: return
        val params = overlay.params.release()
        stationOverlay.set(if (params.isEmpty()) null else overlay.copy(params = params))
    }
    val audioVolumeLevel = Setting("audioVolumeLevel", 0.33f, scope, dataStore)
    val audioMuted = Setting("audioMuted", false, scope, dataStore)
    val effectiveAudioVolumeLevel = DerivedState(audioVolumeLevel, audioMuted) { if(audioMuted.value) 0f else audioVolumeLevel.value }

    // Recording Tab
    val recordingRunning = MutableState(false)
    val basebandRecordingRunning = MutableState(false)
    val anyRecordingRunning = DerivedState(recordingRunning, basebandRecordingRunning) { recordingRunning.value || basebandRecordingRunning.value }
    val recordingName = Setting("recordingName", "My Recording", scope, dataStore)
    val recordOnlyWhenSquelchIsSatisfied = Setting("recordOnlyWhenSquelchIsSatisfied", false, scope, dataStore)
    val recordingStopAfterThreshold = Setting("recordingStopAfterThreshold", 10, scope, dataStore)
    val recordingstopAfterUnit = Setting("recordingStopAfterUnit", StopAfterUnit.NEVER, scope, dataStore)
    val recordingCurrentFileSize = MutableState(0L)
    val recordingStartedTimestamp = MutableState(0L)
    /** SystemClock.elapsedRealtime() at the start of the running recording, for its running time:
     *  [recordingStartedTimestamp] is its date, which a replayed file sets to the file's own time. */
    val recordingStartedElapsedRealtime = MutableState(0L)

    // Settings Tab
    val screenOrientation = Setting("screenOrientation", ScreenOrientation.AUTO, scope, dataStore)
    val fontSize = Setting("fontSize", FontSize.NORMAL, scope, dataStore)
    val showDebugInformation = Setting("showDebugInformation", false, scope, dataStore)
    val loggingEnabled = Setting("loggingEnabled", false, scope, dataStore)
    val colorTheme = Setting("colorTheme", ColorTheme.RFANALYZER_DARK, scope, dataStore)
    val controlDrawerSide = Setting("controlDrawerSide", ControlDrawerSide.RIGHT, scope, dataStore)
    val longPressHelpEnabled = Setting("longPressHelpEnabled", true, scope, dataStore)
    val reverseTuningWheel = Setting("reverseTuningWheel", false, scope, dataStore)
    // Plugging in a USB SDR while the app is in front (or opens because of it) starts the analyzer
    val startAnalyzerOnUsbAttach = Setting("startAnalyzerOnUsbAttach", false, scope, dataStore)
    val enableLowPerformanceMode = Setting("enableLowPerformanceMode", false, scope, dataStore)
    // The rate the FFT/waterfall is drawn at: drawing costs about twice the CPU at 60 fps as at 30, so
    // low-performance mode caps it.
    val effectiveMaxFrameRate = DerivedState(maxFrameRate, enableLowPerformanceMode) {
        if (enableLowPerformanceMode.value) minOf(maxFrameRate.value, LOW_PERFORMANCE_MAX_FRAME_RATE) else maxFrameRate.value
    }
    val lowPerformanceModeFilterQuality = Setting("lowPerformanceModeFilterQuality", 0.42f, scope, dataStore)

    // Location (see LocationRepository): "" = guessed, "R1".."R3" = an ITU region alone, else a country code
    // Private: left out of cloud backups (PrivateSettings)
    val locationArea = Setting("locationArea", "", scope, privateDataStore)
    val locationPositionMode = Setting("locationPositionMode", PositionMode.NONE, scope, privateDataStore)
    val locationLocator = Setting("locationLocator", "", scope, privateDataStore)
    /** The device's last fix while PositionMode.DEVICE is chosen: "lat,lon,timeMs" or "". */
    val locationDeviceFix = Setting("locationDeviceFix", "", scope, privateDataStore)

    // Recordings Screen
    val displayOnlyFavoriteRecordings = Setting("displayOnlyFavoriteRecordings", false, scope, dataStore)

    // Station & Band Bookmarks
    val bookmarkManagerScreenPage = MutableState(StationsPage.BOOKMARKLISTS)
    val displayDefaultImportDialog = Setting("showDefaultBandImportDialog", true, scope, dataStore)
    /** "version|area" of the digital station list's rows (StationRepository.applyDigitalStationList). */
    val digitalStationListApplied = Setting("digitalStationListApplied", "", scope, dataStore)
    val displayBookmarkTutorial = Setting("showBookmarkTutorial", true, scope, dataStore)
    val displayedLegacyImportDialog = Setting("displayedLegacyImportDialog", false, scope, dataStore)
    val onlineStationDownloadState = EnumMap(OnlineStationProvider.entries.associateWith { MutableState<DownloadState>(DownloadState.Idle) })
    val bookmarkListFilterSearchString = Setting("bookmarkListFilterSearchString", "", scope, dataStore)
    val stationFilterForList = StationFilterSettings("list", scope, dataStore)
    val bandFilterForList = BandFilterSettings("list", scope, dataStore)
    val displayStationsInFft = Setting("displayStationsInFft", true, scope, dataStore)
    val useCustomFftStationFilter = Setting("synchronizeStationFilters", true, scope, dataStore)
    val stationFilterForFft = StationFilterSettings("fft", scope, dataStore)
    val displayBandsInFft = Setting("displayBandsInFft", true, scope, dataStore)
    val useCustomFftBandFilter = Setting("synchronizeBandFilters", true, scope, dataStore)
    val bandFilterForFft = BandFilterSettings("fft", scope, dataStore)

    // Digital tab
    /** Per-mode cap on retained raw packets (0 = unlimited), defaulting to [defaultRetention].
     *  Per mode because the modes' packet rates differ by three orders of magnitude. */
    val digitalPacketRetentionByMode: Map<DigitalMode, Setting<Int>> =
        DigitalMode.entries.associateWith { mode ->
            Setting("digitalPacketRetention_${mode.name}", mode.defaultRetention, scope, dataStore)
        }
    val digitalTabFilter = DigitalFilterSettings("digitalTab", scope, dataStore)
    // The Overview's selected section (by label) for a mode with several - LoRa's sub-protocols.
    val digitalOverviewSections = DigitalMode.entries.associateWith { mode -> Setting("digitalOverviewSection_${mode.name}", "", scope, dataStore) }
    /** The station each mode was last started with (Station.stationKey) - the mode sheet's Start. */
    val modeSheetLastStations = DigitalMode.entries.associateWith { mode -> Setting("modeSheetLastStation_${mode.name}", "", scope, dataStore) }

    // Map camera of the KiwiSDR public receiver picker
    val mapCameraPositionForKiwiSdrDialog = MapCameraPositionSettings("kiwiSdrDialog", scope, privateDataStore)  // private: where the user looked
    val showMapTrails = Setting("showMapTrails", true, scope, dataStore)
    // Whether the Digital tab's Map plots the tracked objects of every mode, or only the shown one's.
    val showAllModesOnMap = Setting("showAllModesOnMap", false, scope, dataStore)

    // Analyzer Surface
    val viewportFrequency =  Setting("viewportFrequency", 97000000L, scope, dataStore)
    val viewportSampleRate = Setting("viewportSampleRate", 2000000L, scope, dataStore)

    /**
     * The channel frequency is one global setting while the source frequency is kept per source
     * type, and a band moves only the viewport: after a switch to another SDR or a band far away
     * the channel can lie outside the received signal, where its mode silently receives nothing.
     * Moves it to where the view will be (see [channelFrequencyInsideSignal]); returns the new
     * frequency, null if it was inside.
     */
    fun moveChannelIntoSourceSignal(): Long? {
        val frequency = channelFrequencyInsideSignal(channelFrequency.value, sourceSignalStartFrequency.value,
            sourceSignalEndFrequency.value, viewportFrequency.value, viewportSampleRate.value) ?: return null
        channelFrequency.set(frequency)
        return frequency
    }
    val viewportVerticalScaleMin = Setting("viewportVerticalScaleMinDbfs", DEFAULT_VERTICAL_SCALE_MIN, scope, dataStore, legacyKey = "viewportVerticalScaleMin" to ::legacyDbToDbfs)
    val viewportVerticalScaleMax = Setting("viewportVerticalScaleMaxDbfs", DEFAULT_VERTICAL_SCALE_MAX, scope, dataStore, legacyKey = "viewportVerticalScaleMax" to ::legacyDbToDbfs)
    // The vertical scale last used with each source type (levels in dBFS differ between sources:
    // ADC bit depth, gain, antenna), swapped in when the source type changes (see init). NaN: not
    // used with that source yet, the current scale stays.
    private val verticalScalesBySource = SourceType.entries.associateWith { type ->
        Pair(Setting("viewportVerticalScaleMinDbfs_${type.name}", Float.NaN, scope, dataStore),
             Setting("viewportVerticalScaleMaxDbfs_${type.name}", Float.NaN, scope, dataStore))
    }
    // The viewport last used with each source type, swapped in the same way: each type has its own
    // frequency, and a KiwiSDR's 12 kHz band would otherwise leave the next source zoomed in that
    // far. 0: not used with that source yet - the view shows the new source's whole band.
    private val viewportsBySource = SourceType.entries.associateWith { type ->
        Pair(Setting("viewportFrequency_${type.name}", 0L, scope, dataStore),
             Setting("viewportSampleRate_${type.name}", 0L, scope, dataStore))
    }
    val viewportStartFrequency = DerivedState(viewportFrequency, viewportSampleRate) { viewportFrequency.value - viewportSampleRate.value/2 }
    val viewportEndFrequency = DerivedState(viewportFrequency, viewportSampleRate) { viewportFrequency.value + viewportSampleRate.value/2 }
    val viewportZoom = DerivedState(sourceSampleRate, viewportSampleRate) {
        val tmp = (1f - (viewportSampleRate.value.toFloat()/sourceSampleRate.value)).coerceIn(0f, 1f)
        if (tmp.isNaN()) 0f else tmp
    }

    // Analyzer State
    val analyzerRunning = MutableState(false)
    val analyzerStartPending = MutableState(false) // Indicates if the analyzer is currently getting started
    /** The channel's level per FFT frame (per-bin dB, see Squelch.kt). */
    val averageSignalStrength = MutableState(-999f)
    /** The squelch's decision (always true while it is off) and where it opens now (noise floor +
     *  margin, per-bin dB - the FFT's squelch line); set from the FFT thread. */
    val squelchSatisfied = MutableState(true)
    val squelchThresholdDb = MutableState(DEFAULT_VERTICAL_SCALE_MIN)

    // Billing State
    val appUsageTimeInSeconds = Setting("appUsageTimeInSeconds", 0, scope, dataStore)
    // The license as Google Play last reported it - only the BillingRepository writes these (and
    // the debug receiver). isFullVersion keeps the key 2.2.1 wrote, so everyone who bought the full
    // version has it from the first frame after the update. Never rename either key.
    val isFullVersion = Setting("isFullVersion", false, scope, dataStore)
    val isBaseVersion = Setting("isBaseVersion", false, scope, dataStore)
    val license = DerivedState(isFullVersion, isBaseVersion) { licenseOfCache(isFullVersion.value, isBaseVersion.value) }
    /** Base or Full Version: no trial limits. The extensions need the Full Version (digitalFeaturesUnlocked). */
    val isLicensed = DerivedState(isFullVersion, isBaseVersion) { isFullVersion.value || isBaseVersion.value }
    // The running analyzer's seconds not yet added to appUsageTimeInSeconds and
    // digitalTrialUsedSeconds: AnalyzerService adds them every 30 s (each write rewrites the
    // preferences file). Everything derived counts them, so the times left count down by the second.
    val unsavedUsageSeconds = MutableState(0)
    val unsavedDigitalTrialSeconds = MutableState(0)
    val trialSecondsLeft = DerivedState(appUsageTimeInSeconds, unsavedUsageSeconds) {
        (TRIAL_VERSTION_USAGE_TIME - appUsageTimeInSeconds.value - unsavedUsageSeconds.value).coerceAtLeast(0)
    }
    val isAppUsageTimeUsedUp = DerivedState(appUsageTimeInSeconds, unsavedUsageSeconds) {
        appUsageTimeInSeconds.value + unsavedUsageSeconds.value > TRIAL_VERSTION_USAGE_TIME
    }

    // Digital modes are a feature of the full version (also Base Version + upgrade). Without it
    // they have their own trial - the Base Version too -, counted only while a digital mode is
    // actually decoding (browsing stored data costs nothing) and independent of the trial above -
    // so a user whose trial ended before the extension came out can still try them.
    // digitalFeaturesUnlocked is the one gate.
    // Debug builds have the same gate (their mock billing's purchases complete at once; the debug
    // receiver can set isFullVersion, isBaseVersion and digitalTrialUsedSeconds).
    val digitalTrialUsedSeconds = Setting("digitalTrialUsedSeconds", 0, scope, dataStore)
    val digitalTrialSecondsLeft = DerivedState(digitalTrialUsedSeconds, unsavedDigitalTrialSeconds) {
        (DIGITAL_TRIAL_SECONDS - digitalTrialUsedSeconds.value - unsavedDigitalTrialSeconds.value).coerceAtLeast(0)
    }
    val digitalFeaturesUnlocked = DerivedState(isFullVersion, digitalTrialUsedSeconds, unsavedDigitalTrialSeconds) {
        isFullVersion.value || digitalTrialUsedSeconds.value + unsavedDigitalTrialSeconds.value < DIGITAL_TRIAL_SECONDS
    }
    /** Shown while it counts: no full version. */
    val digitalTrialRunning = DerivedState(isFullVersion) { !isFullVersion.value }

    // Donation Dialog:
    val timestampOfLastDonationDialog = Setting("timestampOfLastDonationDialog", 0, scope, dataStore)
    val donationDialogCounter = Setting("donationDialogCounter", 0, scope, dataStore)


    // FFT Data
    val fftProcessorData = FftProcessorData()

    /** A source type's frequency range before its device is open; null for the file source, whose
     *  range is its recorded band (set when it opens). */
    private fun staticFrequencyRange(type: SourceType): Pair<Long, Long>? = when (type) {
        SourceType.HACKRF -> HackrfSource.MIN_FREQUENCY to HackrfSource.MAX_FREQUENCY
        SourceType.RTLSDR -> RtlsdrSource.MIN_FREQUENCY to RtlsdrSource.MAX_FREQUENCY
        SourceType.AIRSPY -> AirspySource.MIN_FREQUENCY to AirspySource.MAX_FREQUENCY
        SourceType.AIRSPYHF -> AirspyHfSource.MIN_FREQUENCY to AirspyHfSource.MAX_FREQUENCY
        // placeholder until the live device's real range is read (HydraSdrSource.deviceInfo)
        SourceType.HYDRASDR -> HydraSdrDeviceInfo.DEFAULT_MIN_FREQUENCY to HydraSdrDeviceInfo.DEFAULT_MAX_FREQUENCY
        SourceType.KIWISDR -> KiwiSdrSource.MIN_FREQUENCY to KiwiSdrSource.MAX_FREQUENCY
        SourceType.FILESOURCE -> null
    }


    // Service Events
    private val _analyzerEvents = MutableSharedFlow<AnalyzerEvent?>()
    val analyzerEvents: SharedFlow<AnalyzerEvent?> = _analyzerEvents
    sealed class AnalyzerEvent {
        /** A recording finished and is stored (see AnalyzerService.storeFinishedRecording). */
        data class RecordingFinished(val recording: Recording): AnalyzerEvent()
        data class SourceFailure(val message: String): AnalyzerEvent()
        data class IQSourceInformation(val message: String): AnalyzerEvent()
    }
    fun emitAnalyzerEvent(event: AnalyzerEvent){ scope.launch { _analyzerEvents.emit(event) } }

    init {
        // The station overlay ends when the channel leaves the station or the mode changes - and with
        // the source: another receiver's squelch threshold means something else. Synchronous
        // listeners, so it ends with the very change (StationOverlay.kt).
        fun endOverlayIfLeft() {
            val overlay = stationOverlay.value ?: return
            if (abs(channelFrequency.value - overlay.frequencyHz) > overlay.toleranceHz || demodulationMode.value != overlay.mode)
                stationOverlay.set(null)
        }
        channelFrequency.addOnChangedListener { endOverlayIfLeft() }
        demodulationMode.addOnChangedListener { endOverlayIfLeft() }
        sourceType.addOnChangedListener { stationOverlay.set(null) }
        scope.launch {
            // A source type's frequency range and sample rates until its device is open and reports its
            // own (AnalyzerService.startScheduler) - applied on every change of the type (Source tab, a
            // plugged-in SDR, a played recording), so the range is never the previous source's.
            sourceType.awaitInitialized()
            sourceType.stateFlow.collect { type ->
                sourceSupportedSampleRates.set(type.defaultSupportedSampleRates)
                staticFrequencyRange(type)?.let { (min, max) ->
                    sourceMinimumFrequency.set(min)
                    sourceMaximumFrequency.set(max)
                }
            }
        }
        scope.launch {
            // Repairs a vertical scale persisted by 2.2.1 and earlier, which could store NaN (a pinch
            // with both fingers at the same y). New writes are sanitized, so this can go once few
            // users update from 2.2.1.
            viewportVerticalScaleMin.awaitInitialized()
            viewportVerticalScaleMax.awaitInitialized()
            val (min, max) = sanitizeVerticalScale(viewportVerticalScaleMin.value, viewportVerticalScaleMax.value)
            if (min != viewportVerticalScaleMin.value || max != viewportVerticalScaleMax.value) {
                Log.w("AppStateRepository", "Repairing invalid vertical scale (${viewportVerticalScaleMin.value}, ${viewportVerticalScaleMax.value}) -> ($min, $max)")
                viewportVerticalScaleMin.set(min)
                viewportVerticalScaleMax.set(max)
            }
        }
        scope.launch {
            sourceType.awaitInitialized()
            viewportVerticalScaleMin.awaitInitialized()
            viewportVerticalScaleMax.awaitInitialized()
            verticalScalesBySource.values.forEach { (min, max) -> min.awaitInitialized(); max.awaitInitialized() }
            viewportFrequency.awaitInitialized()
            viewportSampleRate.awaitInitialized()
            viewportsBySource.values.forEach { (frequency, sampleRate) -> frequency.awaitInitialized(); sampleRate.awaitInitialized() }
            sourceFrequencies.values.forEach { it.awaitInitialized() }
            sourceSampleRates.values.forEach { it.awaitInitialized() }
            var currentSourceType = sourceType.value
            sourceType.addOnChangedListener { newSourceType ->
                if (newSourceType == currentSourceType) return@addOnChangedListener
                verticalScalesBySource.getValue(currentSourceType).let { (min, max) ->
                    min.set(viewportVerticalScaleMin.value)
                    max.set(viewportVerticalScaleMax.value)
                }
                verticalScalesBySource.getValue(newSourceType).let { (min, max) ->
                    if (min.value.isFinite() && max.value.isFinite()) {
                        val (newMin, newMax) = sanitizeVerticalScale(min.value, max.value)
                        viewportVerticalScaleMin.set(newMin)
                        viewportVerticalScaleMax.set(newMax)
                    }
                }
                viewportsBySource.getValue(currentSourceType).let { (frequency, sampleRate) ->
                    frequency.set(viewportFrequency.value)
                    sampleRate.set(viewportSampleRate.value)
                }
                viewportsBySource.getValue(newSourceType).let { (frequency, sampleRate) ->
                    val newSourceRate = sourceSampleRates.getValue(newSourceType).value
                    if (sampleRate.value > 0) {
                        viewportFrequency.set(frequency.value)
                        viewportSampleRate.set(sampleRate.value)
                    } else {
                        viewportFrequency.set(sourceFrequencies.getValue(newSourceType).value)
                        // a file source without a file has no band yet: 0 until a file sets it
                        // (AnalyzerSurface then shows the whole band)
                        viewportSampleRate.set(newSourceRate)
                    }
                }
                currentSourceType = newSourceType
            }
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

    inner class DigitalFilterSettings(
        prefix: String,
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
    ) {
        // Empty string = no mode selected (DigitalFilter.mode == null).
        private val modeFilter = Setting("$prefix.digitalPacketFilterMode", "", scope, dataStore)
        private val lastMs = Setting("$prefix.digitalPacketFilterLastMs", 0L, scope, dataStore)
        private val minTime = Setting("$prefix.digitalPacketFilterMinTime", 0L, scope, dataStore)
        private val maxTime = Setting("$prefix.digitalPacketFilterMaxTime", 0L, scope, dataStore)
        private val minFrequency = Setting("$prefix.digitalPacketFilterMinFrequency", 0L, scope, dataStore)
        private val maxFrequency = Setting("$prefix.digitalPacketFilterMaxFrequency", 0L, scope, dataStore)
        private val search = Setting("$prefix.digitalPacketFilterSearch", "", scope, dataStore)
        private val newestFirst = Setting("$prefix.digitalPacketFilterNewestFirst", true, scope, dataStore)
        val state = DerivedState(
            modeFilter,
            lastMs,
            minTime,
            maxTime,
            minFrequency,
            maxFrequency,
            search,
            newestFirst,
        ) {
            DigitalFilter(
                mode = DigitalMode.entries.firstOrNull { it.name == modeFilter.value },
                lastMs = lastMs.value,
                minTime = minTime.value,
                maxTime = maxTime.value,
                minFrequency = minFrequency.value,
                maxFrequency = maxFrequency.value,
                search = search.value,
                newestFirst = newestFirst.value,
            )
        }
        fun set(filter: DigitalFilter) {
            modeFilter.set(filter.mode?.name ?: "")
            lastMs.set(filter.lastMs)
            minTime.set(filter.minTime)
            maxTime.set(filter.maxTime)
            minFrequency.set(filter.minFrequency)
            maxFrequency.set(filter.maxFrequency)
            search.set(filter.search)
            newestFirst.set(filter.newestFirst)
        }
    }

    inner class MapCameraPositionSettings(
        prefix: String,
        scope: CoroutineScope,
        dataStore: DataStore<Preferences>,
    ) {
        // Stored as Float (Setting<T> has no Double branch); ample precision for a remembered map view.
        private val latitude = Setting("$prefix.mapCameraLatitude", MapCameraPosition.DEFAULT.latitude.toFloat(), scope, dataStore)
        private val longitude = Setting("$prefix.mapCameraLongitude", MapCameraPosition.DEFAULT.longitude.toFloat(), scope, dataStore)
        private val zoom = Setting("$prefix.mapCameraZoom", MapCameraPosition.DEFAULT.zoom.toFloat(), scope, dataStore)
        val state = DerivedState(latitude, longitude, zoom) {
            MapCameraPosition(latitude.value.toDouble(), longitude.value.toDouble(), zoom.value.toDouble())
        }
        fun set(position: MapCameraPosition) {
            latitude.set(position.latitude.toFloat())
            longitude.set(position.longitude.toFloat())
            zoom.set(position.zoom.toFloat())
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
        private val debugLogSet: Boolean = false,
        // Primitive settings only: (old key, conversion) for a value that was persisted under
        // another key in another unit. Read while keyName holds no value yet.
        private val legacyKey: Pair<String, (T) -> T>? = null,
    ) : MutableState<T>(default) {
        val initializationFinished = CompletableDeferred<Unit>()
        suspend fun awaitInitialized() {
            initializationFinished.await()
        }
        @Suppress("UNCHECKED_CAST")
        private fun primitiveKey(name: String, default: T): Preferences.Key<T>? = when (default) {
            is Boolean -> booleanPreferencesKey(name)
            is Int -> intPreferencesKey(name)
            is Long -> longPreferencesKey(name)
            is Float -> floatPreferencesKey(name)
            is String -> stringPreferencesKey(name)
            is Enum<*> -> null
            is List<*> -> null
            else -> throw IllegalArgumentException("Unsupported setting type (setting: ${name}")
        } as Preferences.Key<T>?

        init {
            val key: Preferences.Key<T>? = primitiveKey(keyName, default)

            scope.launch {
                // Load initial value
                val saved = dataStore.data
                    .map { prefs -> when(default) {
                        is Enum<*> -> {  // the name under keyName + "Enum"; until 2.1 the ordinal as int under keyName
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
                                // "<I|L|S>:<items>"
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
                                // Untyped List<Int> (RF Analyzer 2.1.1 and below) or an empty list ("")
                                stringValue.split(",").mapNotNull { it.toIntOrNull() } as T
                            }
                        }
                        else -> {
                            if (key == null) throw IllegalArgumentException("Unsupported setting type (setting: ${keyName})")
                            else prefs[key] ?: legacyKey?.let { (legacyName, convert) ->
                                primitiveKey(legacyName, default)?.let { prefs[it] }?.let(convert)
                            } ?: default
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

    // The state of the setting an enum selects (e.g. the current demodulation mode's channel width);
    // set() writes that setting.
    class DerivedEnumState<E : Enum<E>, T>(
        private val enumSelector: MutableState<E>,
        private val states: Map<E, MutableState<T>>,
    ) : MutableState<T>(states[enumSelector.value]!!.value) {
        init {
            // Listeners hear every change of the value, also one from the selected state (a Setting
            // loading from the DataStore) or a switch of the enum, so a DerivedState on top
            // (effectiveSquelchEnabled) follows both.
            fun update() {
                val newValue = states[enumSelector.value]?.value
                if (newValue != null && newValue != flow.value) {
                    flow.value = newValue
                    listeners.forEach { it(newValue) }
                }
            }
            enumSelector.addOnChangedListener { update() }
            states.forEach { (_, state) -> state.addOnChangedListener { update() } }
            // A Setting may have loaded from the DataStore before the listeners were attached: read again.
            update()
        }

        override fun set(value: T) {
            states[enumSelector.value]?.set(value) // its listener updates this state
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
            // A Setting may have loaded from the DataStore before the listeners were attached: read again.
            update()
        }

        // Computed on every read: current even before a listener has fired.
        override val value: T
            get() = compute()
    }

    // The current mode's demodulation parameters for a new bookmark (MainViewModel.showEditStationBookmarkSheet,
    // BookmarkManagerViewModel.setEditedStationToNewWithDefaults). Device-level ones (FT8's time offset)
    // are not a property of the station. Only what is fixed (not Auto) is stored: a bookmark that
    // repeats Auto would turn the station's demodulator into a fixed one the day Auto learns more.
    fun captureCurrentDemodulationParameters(): DemodulationParameters? {
        val mode = demodulationMode.value
        val squelchParams = if (effectiveSquelchEnabled.value)
            DemodulationParameters.Squelch(enabled = true, marginDb = effectiveSquelchMargin.value)
        else null
        val rttyAuto = DigitalDemodParams.Rtty.AUTO
        val rttyParams = DemodulationParameters.Rtty(baudRate = effectiveRttyBaudRate.value, frequencyShift = effectiveRttyFrequencyShift.value,
            polarity = effectiveRttyPolarity.value)
            .takeIf { mode == DemodulationMode.RTTY && (it.baudRate != rttyAuto || it.frequencyShift != rttyAuto || it.polarity != RttyPolarity.AUTO) }
        val pocsagParams = DemodulationParameters.Pocsag(baudRate = effectivePocsagBaudRate.value)
            .takeIf { mode == DemodulationMode.POCSAG && it.baudRate != 0 }
        val ax25Params = if (mode == DemodulationMode.AX25) DemodulationParameters.Ax25(baudRate = ax25Variant.value) else null // no Auto
        val psk31Params = DemodulationParameters.Psk31(baudRate = effectivePsk31BaudRate.value)
            .takeIf { mode == DemodulationMode.PSK31 && it.baudRate != DigitalDemodParams.Psk31.AUTO }
        val r09Params = DemodulationParameters.R09(baudRate = effectiveR09BaudRate.value)
            .takeIf { mode == DemodulationMode.R09 && it.baudRate != DigitalDemodParams.R09.AUTO }
        val loraParams = DemodulationParameters.Lora(spreadingFactors = effectiveLoraSpreadingFactors.value, iqMode = effectiveLoraIqMode.value)
            .takeIf { mode == DemodulationMode.LORA && (it.spreadingFactors != LoraSpreadingFactors.AUTO || it.iqMode != LoraIqMode.AUTO) }
        return DemodulationParameters(squelch = squelchParams, rtty = rttyParams, pocsag = pocsagParams, ax25 = ax25Params,
            psk31 = psk31Params, r09 = r09Params, lora = loraParams)
            .takeIf { listOf(squelchParams, rttyParams, pocsagParams, ax25Params, psk31Params, r09Params, loraParams).any { it != null } }
    }


}