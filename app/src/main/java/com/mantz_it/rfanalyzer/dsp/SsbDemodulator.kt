package com.mantz_it.rfanalyzer.dsp

import android.util.Log
import com.mantz_it.rfanalyzer_ipc.SamplePacket

/**
 * <h1>RF Analyzer - SSB Demodulator</h1>
 *
 * Module:      SsbDemodulator.kt
 * Description: SSB demodulator with a fixed baseband sample rate (never derived from an incoming
 *              packet). [upperBand] (USB vs. LSB) is fixed for the lifetime of the instance - LSB
 *              and USB each get their own instance and their own band pass filter field.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class SsbDemodulator(private val sampleRate: Int, packetSize: Int, private val upperBand: Boolean) {

    companion object {
        private const val LOGTAG = "SsbDemodulator"
        private const val BAND_PASS_ATTENUATION = 40f
        private const val BAND_PASS_MAX_TAPS = 2000
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
            2, // Decimate by 2; => AUDIO_RATE
            1f,
            sampleRate.toFloat(),
            if (upperBand) 200f else -channelWidth.toFloat(),
            if (upperBand) channelWidth.toFloat() else -200f,
            sampleRate * 0.01f,
            BAND_PASS_ATTENUATION,
            BAND_PASS_MAX_TAPS
        )
        val filter = bandPassFilter ?: return
        Log.d(LOGTAG, ("rebuildFilter: created new band pass filter with " + filter.numberOfTaps
                    + " taps. Decimation=" + filter.decimation + " Low-Cut-Off=" + filter.lowCutOffFrequency
                    + " High-Cut-Off=" + filter.highCutOffFrequency + " transition=" + filter.transitionWidth))
    }

    /**
     * Will SSB demodulate the samples in input.
     * Demodulated samples are stored in the real array of output. Note: All samples in output
     * will always be overwritten!
     *
     * @param input        incoming (modulated) samples
     * @param output    outgoing (demodulated) samples
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
