package com.mantz_it.rfanalyzer.dsp

import android.util.Log
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import kotlin.math.atan2

/**
 * <h1>RF Analyzer - FM Demodulator</h1>
 *
 * Module:      FmDemodulator.kt
 * Description: FM demodulator with a fixed baseband sample rate (never derived from an incoming
 *              packet). Used for both NFM and WFM-mono, distinguished only by [maxDeviationRatio] -
 *              the two modes get separate instances since they run at different sample rates. Owns
 *              its own channel-selectivity pre-demod low-pass filter, discriminator carry-over
 *              state, and an optional fixed-cutoff post-demod audio low-pass filter.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class FmDemodulator(
    private val sampleRate: Int,
    packetSize: Int,
    private val maxDeviationRatio: Float, // 0.75 for NFM, 0.85 for WFM mono
    postDemodAudioCutoffHz: Float? = null // null = skip; e.g. 3000f for NFM voice band, 15000f for WFM mono
) {
    companion object {
        private const val LOGTAG = "FmDemodulator"
        private const val CHANNEL_FILTER_ATTENUATION = 60f
        private const val CHANNEL_FILTER_MAX_TAPS = 2000
        private const val AUDIO_FILTER_ATTENUATION = 60f
        private const val AUDIO_FILTER_MAX_TAPS = 2000
    }

    var channelWidth: Int = -1
        set(value) {
            if (value == field) return // setter is invoked once per packet by Demodulator.run(); only rebuild on genuine changes
            field = value
            rebuildFilter()
        }

    private var channelFilter: FirFilter? = null
    private var quadratureGain: Float = 0f
    private val channelFiltered = SamplePacket(packetSize)
    private val discriminatorOutput = SamplePacket(packetSize)
    private var carryOverSamplesRe: Float = 0f
    private var carryOverSamplesIm: Float = 0f

    // Fixed cutoff, independent of channelWidth - built once at construction, not part of rebuildFilter().
    private val audioFilter: FirFilter? = postDemodAudioCutoffHz?.let { cutoff ->
        FirFilter.createLowPass(1, 1f, sampleRate.toFloat(), cutoff, cutoff * 0.2f, AUDIO_FILTER_ATTENUATION, AUDIO_FILTER_MAX_TAPS)
    }

    private fun rebuildFilter() {
        channelFilter = FirFilter.createLowPass(
            1,
            1f,
            sampleRate.toFloat(),
            channelWidth.toFloat(),
            channelWidth * 0.2f,
            CHANNEL_FILTER_ATTENUATION,
            CHANNEL_FILTER_MAX_TAPS
        )
        val filter = channelFilter ?: return
        val maxDeviation = channelWidth * maxDeviationRatio
        quadratureGain = sampleRate / (2 * Math.PI * maxDeviation).toFloat()
        Log.d(LOGTAG, ("rebuildFilter: created new channel filter with " + filter.numberOfTaps
                    + " taps. Cut-Off=" + filter.cutOffFrequency + " transition=" + filter.transitionWidth))
    }

    /**
     * Will FM demodulate the samples in input, then (if a post-demod audio filter is configured)
     * low-pass filter the demodulated audio.
     * Demodulated samples are stored in the real array of output. Note: All samples in output
     * will always be overwritten!
     *
     * @param input        incoming (modulated) samples
     * @param output    outgoing (demodulated) samples
     */
    fun demodulate(input: SamplePacket, output: SamplePacket) {
        val filter = channelFilter ?: return

        channelFiltered.setSize(0)
        if (filter.filter(input, channelFiltered, 0, input.size()) < input.size()) {
            Log.e(LOGTAG, "demodulate: could not filter all samples from input packet.")
        }

        val inputSize = channelFiltered.size()
        if (inputSize == 0) return

        val reIn = channelFiltered.re()
        val imIn = channelFiltered.im()
        val reOut = discriminatorOutput.re()
        val imOut = discriminatorOutput.im()

        // Quadrature demodulation:
        reOut[0] = reIn[0] * carryOverSamplesRe + imIn[0] * carryOverSamplesIm
        imOut[0] = imIn[0] * carryOverSamplesRe - reIn[0] * carryOverSamplesIm
        reOut[0] = quadratureGain * atan2(imOut[0], reOut[0])
        for (i in 1..<inputSize) {
            reOut[i] = reIn[i] * reIn[i - 1] + imIn[i] * imIn[i - 1]
            imOut[i] = imIn[i] * reIn[i - 1] - reIn[i] * imIn[i - 1]
            reOut[i] = quadratureGain * atan2(imOut[i], reOut[i])
        }
        carryOverSamplesRe = reIn[inputSize - 1]
        carryOverSamplesIm = imIn[inputSize - 1]
        discriminatorOutput.setSize(inputSize)
        discriminatorOutput.sampleRate = sampleRate
        discriminatorOutput.timestamp = channelFiltered.timestamp

        val postFilter = audioFilter
        if (postFilter != null) {
            output.setSize(0)
            if (postFilter.filterReal(discriminatorOutput, output, 0, discriminatorOutput.size()) < discriminatorOutput.size()) {
                Log.e(LOGTAG, "demodulate: could not filter all samples through post-demod audio filter.")
            }
        } else {
            val outRe = output.re()
            for (i in 0..<inputSize) outRe[i] = reOut[i]
            output.setSize(inputSize)
            output.sampleRate = sampleRate
            output.timestamp = discriminatorOutput.timestamp
        }
    }
}
