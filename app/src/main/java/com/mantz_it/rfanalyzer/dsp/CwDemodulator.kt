package com.mantz_it.rfanalyzer.dsp

import android.util.Log
import com.mantz_it.rfanalyzer_ipc.SamplePacket

/**
 * <h1>RF Analyzer - CW Demodulator</h1>
 *
 * Module:      CwDemodulator.kt
 * Description: CW "demodulator" with a fixed baseband sample rate (never derived from an incoming
 *              packet). Not a true demodulation but a beat frequency oscillator: the Scheduler
 *              mixes the carrier down to +CW_OFFSET_FREQUENCY Hz instead of 0 Hz (AnalyzerService
 *              sets its channel frequency), so the real part of a narrow band pass around
 *              CW_OFFSET_FREQUENCY is the audible tone. Owns its own band pass filter and AGC
 *              state.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class CwDemodulator(private val sampleRate: Int, packetSize: Int) {

    companion object {
        private const val LOGTAG = "CwDemodulator"
        private const val BAND_PASS_ATTENUATION = 40f
        private const val BAND_PASS_MAX_TAPS = 2000

        // Offset for the CW signal. 750Hz will be the audio tone the user hears if the signal is exactly at the channel frequency
        const val CW_OFFSET_FREQUENCY = 750
    }

    var channelWidth: Int = -1
        set(value) {
            if (value == field) return // setter is invoked once per packet by Demodulator.run(); only rebuild on genuine changes
            field = value
            rebuildFilter()
        }

    private var bandPassFilter: ComplexFirFilter? = null
    private var lastMax = 0f // used for gain control

    private fun rebuildFilter() {
        bandPassFilter = ComplexFirFilter.createBandPass(
            1,
            1f,
            sampleRate.toFloat(),
            CW_OFFSET_FREQUENCY - channelWidth / 2.0f,
            CW_OFFSET_FREQUENCY + channelWidth / 2.0f,
            channelWidth * 0.2f,
            BAND_PASS_ATTENUATION,
            BAND_PASS_MAX_TAPS
        )
        val filter = bandPassFilter ?: return
        Log.d(LOGTAG, ("rebuildFilter: created new band pass filter with " + filter.numberOfTaps
                    + " taps. Decimation=" + filter.decimation + " Low-Cut-Off=" + filter.lowCutOffFrequency
                    + " High-Cut-Off=" + filter.highCutOffFrequency + " transition=" + filter.transitionWidth))
    }

    /**
     * Will CW demodulate the samples in input. Expects the signal to be at CW_OFFSET_FREQUENCY Hz.
     * Demodulated samples are stored in the real array of output. Note: All samples in output
     * will always be overwritten!
     *
     * @param input        incoming (modulated) samples
     * @param output       outgoing (demodulated) samples
     */
    fun demodulate(input: SamplePacket, output: SamplePacket) {
        val filter = bandPassFilter ?: return
        val reOut = output.re()

        output.setSize(0) // mark buffer as empty
        if (filter.filter(input, output, 0, input.size()) < input.size()) {
            Log.e(LOGTAG, "demodulate: could not filter all samples from input packet.")
        }

        // gain control: searching for max:
        lastMax *= 0.95f // simplest AGC
        for (i in 0..<output.size()) {
            if (reOut[i] > lastMax) lastMax = reOut[i]
        }
        // normalize values:
        val gain = 0.75f / lastMax
        for (i in 0..<output.size()) reOut[i] *= gain
    }
}
