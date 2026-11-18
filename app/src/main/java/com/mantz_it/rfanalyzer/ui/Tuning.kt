package com.mantz_it.rfanalyzer.ui

import com.mantz_it.libhydrasdr.HydraSdrDeviceInfo
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.Station
import com.mantz_it.rfanalyzer.database.StationOverlay
import com.mantz_it.rfanalyzer.database.TemporaryParameters
import com.mantz_it.rfanalyzer.database.toTemporaryParameters
import com.mantz_it.rfanalyzer.digiext.shownOrAnalog
import com.mantz_it.rfanalyzer.source.AirspyHfSource
import com.mantz_it.rfanalyzer.source.AirspySource
import com.mantz_it.rfanalyzer.source.HackrfSource
import com.mantz_it.rfanalyzer.source.KiwiSdrSource
import com.mantz_it.rfanalyzer.source.RtlsdrSource
import com.mantz_it.rfanalyzer.ui.composable.frequencyRangeString
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.tabs.SourceType
import com.mantz_it.rfanalyzer.ui.tabs.normalizeChannelWidth

/**
 * <h1>RF Analyzer - Tuning</h1>
 *
 * Module:      Tuning.kt
 * Description: Tuning to a station - a bookmark, an online list's entry or spot, the Digital tab's
 *              mode presets - from any state, one way for all of them: the viewport stays where it
 *              is when the station's signal fits into it, and only otherwise moves to it, with the
 *              span that suits the mode. The source's sample rate is raised only when the mode
 *              needs more (ADS-B), its frequency changed only when the viewport isn't received. A
 *              file can't be tuned: its recorded band is the limit and only the channel and
 *              viewport move.
 *
 *              Everything it changes is plain AppStateRepository state. AnalyzerService applies
 *              that state live while running, and reads it when it starts - so a running and a
 *              stopped analyzer take the same path here. Values are written directly rather than
 *              through MainViewModel's
 *              setViewportFrequency/setViewportSampleRate/setChannelFrequency: those are built for
 *              gestures and auto-adjust the source rate and frequency, which would undo the
 *              consistent set computed here.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

data class TuneTarget(
    val label: String,
    /** null: keep the current mode and width - a spot of a mode RF Analyzer doesn't demodulate. */
    val mode: DemodulationMode?,
    /** The demodulator's channel frequency. FT8 uses the dial frequency (signals sit above it). */
    val frequency: Long,
    val channelWidth: Int,
    /** If the current channel frequency already lies in this range, it is kept (e.g. the FM station
     *  the user last listened to). */
    val keepChannelInside: LongRange? = null,
    /** The span when the viewport has to move; null = the mode's ([viewportSpanFor]). */
    val viewportSpan: Long? = null,
    /** Parameters for this station only, while the channel stays on it (StationOverlay.kt) - unlike
     *  [params], which change the settings for good (the AX.25 variant, Stereo & RDS). */
    val overlay: TemporaryParameters? = null,
    val params: AppStateRepository.() -> Unit = {},
    /** Identifies the station for the mode sheet's "last used" ([stationKey]); null for none. */
    val key: String? = null,
)

/** A station's identity across list updates: an online list's entry by its remote id, a bookmark by its row. */
val Station.stationKey: String get() = remoteHash ?: "id:$id"

/** Tuning to [this] station: its mode (or, for a digital mode while digital features are hidden, the
 *  analog one its signal is heard with), bandwidth, the AX.25 variant for good and its other parameters as an overlay. */
fun Station.toTuneTarget(): TuneTarget {
    val mode = mode.shownOrAnalog()?.takeIf { it != DemodulationMode.OFF }
    val params = demodulationParameters
    return TuneTarget(
        label = name.ifBlank { "the station" },
        mode = mode,
        frequency = frequency,
        channelWidth = bandwidth,
        overlay = params?.toTemporaryParameters()?.takeIf { !it.isEmpty() },
        params = { params?.ax25?.let { ax25Variant.set(it.baudRate) } },
        key = stationKey,
    )
}

internal data class SourceLimits(val minFrequency: Long, val maxFrequency: Long, val sampleRates: List<Long>)

/** What is set now. */
internal data class CurrentView(
    val sampleRate: Long,
    val sourceFrequency: Long,
    val viewportFrequency: Long,
    val viewportSpan: Long,
    val channelFrequency: Long,
)

internal data class TuningPlan(
    val sampleRate: Long,
    val sourceFrequency: Long,
    val viewportFrequency: Long,
    val viewportSpan: Long,
    val channelFrequency: Long,
)

/** The frequencies a channel's signals occupy: FT8's lie above its dial frequency, an SSB channel's
 *  on one side of it. */
fun signalRange(mode: DemodulationMode, channelFrequency: Long, width: Int): LongRange = when (mode) {
    DemodulationMode.FT8, DemodulationMode.USB -> channelFrequency..channelFrequency + width
    DemodulationMode.LSB -> channelFrequency - width..channelFrequency
    else -> channelFrequency - width / 2..channelFrequency + width / 2
}

/** The span a viewport gets when a tune has to move it: wide enough to see the neighbours, narrow
 *  enough to see the signal. [currentSpan] for OFF (no channel). */
internal fun viewportSpanFor(mode: DemodulationMode, width: Int, currentSpan: Long): Long = when (mode) {
    DemodulationMode.FT8, DemodulationMode.PSK31, DemodulationMode.USB, DemodulationMode.LSB -> 10_000
    DemodulationMode.RTTY, DemodulationMode.CW -> 5_000
    DemodulationMode.AX25 -> maxOf(5_000L, 4L * width) // HF 300 Bd: 5 kHz, VHF: 50 kHz
    DemodulationMode.R09, DemodulationMode.POCSAG, DemodulationMode.NFM -> 100_000
    DemodulationMode.AM -> 50_000
    DemodulationMode.WFM, DemodulationMode.ADSB -> 2_000_000
    DemodulationMode.LORA -> 4L * width
    DemodulationMode.OFF -> currentSpan
}

/** The sample rate [mode] needs at least: its demodulator's rate for a wideband mode (ADS-B, LoRa) -
 *  with room to keep the channel off the DC spike for a live source; a recording needs only the
 *  rate itself. */
internal fun minSampleRate(mode: DemodulationMode, file: Boolean): Long = when {
    mode.basebandSamplerate < 500_000 -> 0L
    file -> mode.basebandSamplerate.toLong()
    else -> mode.basebandSamplerate * 6L / 5
}

/**
 * The channel frequency [target] demodulates: the current one if the target keeps it (an FM station
 * already tuned), else the target's own - unless only part of [target]'s keep-range is within
 * [limits] (a file covering a slice of the FM band), then the middle of that part.
 */
internal fun channelFor(target: TuneTarget, limits: SourceLimits, currentChannelFrequency: Long): Long {
    val keep = target.keepChannelInside ?: return target.frequency
    val usable = maxOf(keep.first, limits.minFrequency)..minOf(keep.last, limits.maxFrequency)
    return when {
        currentChannelFrequency in usable -> currentChannelFrequency
        target.frequency in usable || usable.isEmpty() -> target.frequency
        else -> (usable.first + usable.last) / 2
    }
}

/** Why [sourceType] can't receive [target] in [mode] (width [width]) at [channelFrequency], or null
 *  if it can. For the file source, [limits] is the band the file covers. */
internal fun capabilityError(
    target: TuneTarget, mode: DemodulationMode, width: Int, channelFrequency: Long, sourceType: SourceType, limits: SourceLimits,
): String? {
    val file = sourceType == SourceType.FILESOURCE
    val maxSampleRate = limits.sampleRates.maxOrNull() ?: 0
    val signal = signalRange(mode, channelFrequency, width)
    return when {
        signal.first < limits.minFrequency || signal.last > limits.maxFrequency ->
            if (file) "The file (${frequencyRangeString(limits.minFrequency, limits.maxFrequency)}) doesn't cover ${target.label}"
            else "${sourceType.displayName} can't receive ${target.label} (out of frequency range)"
        minSampleRate(mode, file) > maxSampleRate || width > maxSampleRate ->
            if (file) "The file's sample rate is too low for ${target.label}"
            else "${sourceType.displayName} can't receive ${target.label} (sample rate too low)"
        else -> null
    }
}

/**
 * The source/viewport/channel settings for [target] in [mode] (width [width]), given what is set
 * now. Never lowers the sample rate. The viewport stays as it is if the signal fits into it and it
 * is received; otherwise it moves to the signal with the mode's span, and the source is retuned only
 * if that viewport isn't received - then with the channel kept off the DC spike where the rate
 * leaves room for it (as MainViewModel.setChannelFrequency does with 2x the channel width), for a
 * source with one ([dcSpike]; a KiwiSDR is tuned to the viewport's centre). [keepChannelCentered]
 * centres the viewport on the channel either way. With [fixedSource] (the file source) rate and
 * frequency stay as they are and only the viewport moves, kept inside the received band.
 */
internal fun computeTuning(
    target: TuneTarget,
    mode: DemodulationMode,
    width: Int,
    limits: SourceLimits,
    current: CurrentView,
    keepChannelCentered: Boolean,
    fixedSource: Boolean = false,
    dcSpike: Boolean = true,
): TuningPlan {
    val minRate = minSampleRate(mode, fixedSource)
    val sampleRate = if (fixedSource || current.sampleRate >= minRate) current.sampleRate
        else limits.sampleRates.sorted().firstOrNull { it >= minRate } ?: current.sampleRate
    val channelFrequency = channelFor(target, limits, current.channelFrequency)
    val signal = signalRange(mode, channelFrequency, width)

    fun received(sourceFrequency: Long) = sourceFrequency - sampleRate / 2..sourceFrequency + sampleRate / 2
    fun isReceived(center: Long, span: Long, sourceFrequency: Long) =
        center - span / 2 in received(sourceFrequency) && center + span / 2 in received(sourceFrequency)

    val viewportStart = current.viewportFrequency - current.viewportSpan / 2
    val viewportEnd = current.viewportFrequency + current.viewportSpan / 2
    val fits = signal.first >= viewportStart && signal.last <= viewportEnd &&
        isReceived(current.viewportFrequency, current.viewportSpan, current.sourceFrequency)
    if (fits && !keepChannelCentered)
        return TuningPlan(sampleRate, current.sourceFrequency, current.viewportFrequency, current.viewportSpan, channelFrequency)

    val span = minOf(if (fits) current.viewportSpan else target.viewportSpan ?: viewportSpanFor(mode, width, current.viewportSpan), sampleRate)
    val viewportFrequency = if (keepChannelCentered) channelFrequency else (signal.first + signal.last) / 2

    if (fixedSource) {
        val coerced = viewportFrequency.coerceIn(current.sourceFrequency - (sampleRate - span) / 2, current.sourceFrequency + (sampleRate - span) / 2)
        return TuningPlan(sampleRate, current.sourceFrequency, coerced, span, channelFrequency)
    }
    val sourceFrequency = if (isReceived(viewportFrequency, span, current.sourceFrequency)) {
        current.sourceFrequency
    } else {
        val dcOffset = if (dcSpike) minOf(2L * width, (sampleRate - span) / 2) else 0L
        (viewportFrequency + dcOffset).coerceIn(limits.minFrequency, limits.maxFrequency)
    }
    return TuningPlan(sampleRate, sourceFrequency, viewportFrequency, span, channelFrequency)
}

/** Per source type, before a source is open (the live values only exist once one is). */
private fun staticSourceLimits(sourceType: SourceType): SourceLimits {
    val (min, max) = when (sourceType) {
        SourceType.HACKRF -> HackrfSource.MIN_FREQUENCY to HackrfSource.MAX_FREQUENCY
        SourceType.RTLSDR -> RtlsdrSource.MIN_FREQUENCY to RtlsdrSource.MAX_FREQUENCY
        SourceType.AIRSPY -> AirspySource.MIN_FREQUENCY to AirspySource.MAX_FREQUENCY
        SourceType.AIRSPYHF -> AirspyHfSource.MIN_FREQUENCY to AirspyHfSource.MAX_FREQUENCY
        SourceType.HYDRASDR -> HydraSdrDeviceInfo.DEFAULT_MIN_FREQUENCY to HydraSdrDeviceInfo.DEFAULT_MAX_FREQUENCY
        SourceType.KIWISDR -> KiwiSdrSource.MIN_FREQUENCY to KiwiSdrSource.MAX_FREQUENCY
        SourceType.FILESOURCE -> 0L to 0L // never used: a file's limits are its recorded band
    }
    return SourceLimits(min, max, sourceType.defaultSupportedSampleRates)
}

class Tuner(
    private val asr: AppStateRepository,
    private val showMessage: (String) -> Unit,
    /** Asks before something is stopped: (title, message, confirm button, action on confirm). */
    private val confirm: (String, String, String, () -> Unit) -> Unit,
    private val stopRecording: () -> Unit,
    private val stopBasebandRecording: () -> Unit,
) {
    /**
     * Tunes to [target]; [then] runs once the settings are in place (start the analyzer, show the
     * mode, remember the station). Nothing happens if the source can't receive the target. A tune
     * that moves the channel during a baseband recording, or the source during a recording, asks to
     * stop the recording first.
     */
    fun tune(target: TuneTarget, then: () -> Unit = {}) {
        val sourceType = asr.sourceType.value
        val isFile = sourceType == SourceType.FILESOURCE
        if (isFile && asr.filesourceUri.value.isEmpty()) {
            showMessage("Select a file in the Source tab first")
            return
        }
        val limits = when {
            isFile -> asr.sourceSampleRate.value.let { rate ->
                SourceLimits(asr.sourceFrequency.value - rate / 2, asr.sourceFrequency.value + rate / 2, listOf(rate))
            }
            asr.analyzerRunning.value ->
                SourceLimits(asr.sourceMinimumFrequency.value, asr.sourceMaximumFrequency.value, asr.sourceSupportedSampleRates.value)
            else -> staticSourceLimits(sourceType)
        }
        val mode = target.mode ?: asr.demodulationMode.value
        val width = if (target.mode == null) asr.channelWidth.value else mode.normalizeChannelWidth(target.channelWidth)
        val channelFrequency = channelFor(target, limits, asr.channelFrequency.value)
        capabilityError(target, mode, width, channelFrequency, sourceType, limits)?.let {
            showMessage(it)
            return
        }
        val plan = computeTuning(
            target, mode, width, limits,
            CurrentView(
                sampleRate = asr.sourceSampleRate.value,
                sourceFrequency = asr.sourceFrequency.value,
                viewportFrequency = asr.viewportFrequency.value,
                viewportSpan = asr.viewportSampleRate.value,
                channelFrequency = asr.channelFrequency.value,
            ),
            keepChannelCentered = asr.keepChannelCentered.value,
            fixedSource = isFile,
            dcSpike = sourceType.hasDcSpike,
        )
        val sourceChanges = plan.sampleRate != asr.sourceSampleRate.value || plan.sourceFrequency != asr.sourceFrequency.value
        when {
            asr.basebandRecordingRunning.value && plan.channelFrequency != asr.channelFrequency.value ->
                confirm("Stop Recording?", "The baseband recording is still running. Stop it to tune to ${target.label}?", "Yes, stop recording!") {
                    stopBasebandRecording()
                    apply(target, mode, width, plan, then)
                }
            asr.recordingRunning.value && sourceChanges ->
                confirm("Stop Recording?", "${target.label} is outside the band being recorded. Stop the recording to tune to it?", "Yes, stop recording!") {
                    stopRecording()
                    apply(target, mode, width, plan, then)
                }
            else -> apply(target, mode, width, plan, then)
        }
    }

    private fun apply(target: TuneTarget, mode: DemodulationMode, width: Int, plan: TuningPlan, then: () -> Unit) {
        asr.sourceSampleRate.set(plan.sampleRate)
        asr.sourceFrequency.set(plan.sourceFrequency)
        asr.demodulationMode.set(mode) // before channelWidth, which is stored per mode
        asr.channelWidth.set(width)
        target.params(asr)
        asr.viewportSampleRate.set(plan.viewportSpan)
        asr.viewportFrequency.set(plan.viewportFrequency)
        asr.channelFrequency.set(plan.channelFrequency)
        asr.stationOverlay.set(target.overlay?.let {
            StationOverlay(target.label, plan.channelFrequency, StationOverlay.toleranceFor(width), mode, it)
        })
        then()
    }
}
