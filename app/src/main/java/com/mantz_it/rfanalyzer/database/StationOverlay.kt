package com.mantz_it.rfanalyzer.database

import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer_ipc.LoraIqMode
import com.mantz_it.rfanalyzer_ipc.RttyPolarity

/**
 * <h1>RF Analyzer - Station Overlay</h1>
 *
 * Module:      StationOverlay.kt
 * Description: The demodulation parameters a station sets while the channel stays on it - laid
 *              over the user's own settings, which it never changes.
 *
 * Tuning to a bookmark (or starting a quick-start preset) sets frequency, mode, bandwidth and the
 * AX.25 variant for good: they are visible and what the user asked for. Its finer parameters -
 * squelch, RTTY/POCSAG/PSK31/R09 rates, LoRa spreading factors and chirp direction - hold only
 * while the channel is on the station (AppStateRepository.stationOverlay), so a demodulator tuned
 * on to another signal does not keep the bookmark's fixed values. The overlay ends when the channel
 * leaves the station, the mode or the source changes, another station is tuned, or the app restarts
 * (it is not persisted). Changing one of its parameters by hand makes that value the user's setting
 * and takes it out of the overlay.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The parameters of a [StationOverlay]; null = the user's setting applies. */
data class TemporaryParameters(
    val squelchEnabled: Boolean? = null,
    val squelchMarginDb: Float? = null,
    val rttyBaudRate: Float? = null,
    val rttyFrequencyShift: Float? = null,
    val rttyPolarity: RttyPolarity? = null,
    val pocsagBaudRate: Int? = null,
    val psk31BaudRate: Float? = null,
    val r09BaudRate: Int? = null,
    val loraSpreadingFactors: Int? = null,
    val loraIqMode: LoraIqMode? = null,
) {
    fun isEmpty(): Boolean = this == TemporaryParameters()
}

/** A station's [params], in force while the channel is within [toleranceHz] of [frequencyHz] in [mode]. */
data class StationOverlay(
    /** For the Demodulation tab: "Settings from 'DWD Pinneberg' until you tune away". */
    val stationName: String,
    val frequencyHz: Long,
    val toleranceHz: Long,
    val mode: DemodulationMode,
    val params: TemporaryParameters,
) {
    companion object {
        /** The channel may move this much within a narrow station (fine-tuning) before the overlay ends. */
        const val MIN_TOLERANCE_HZ = 500L

        fun toleranceFor(bandwidthHz: Int): Long = maxOf(bandwidthHz / 2L, MIN_TOLERANCE_HZ)
    }
}

/** A bookmark's stored parameters as an overlay: all but the AX.25 variant, which is applied for good. */
fun DemodulationParameters.toTemporaryParameters(): TemporaryParameters = TemporaryParameters(
    squelchEnabled = squelch?.enabled,
    squelchMarginDb = squelch?.takeIf { it.enabled }?.marginDb,
    rttyBaudRate = rtty?.baudRate,
    rttyFrequencyShift = rtty?.frequencyShift,
    rttyPolarity = rtty?.polarity,
    pocsagBaudRate = pocsag?.baudRate,
    psk31BaudRate = psk31?.baudRate,
    r09BaudRate = r09?.baudRate,
    loraSpreadingFactors = lora?.spreadingFactors,
    loraIqMode = lora?.iqMode,
)
