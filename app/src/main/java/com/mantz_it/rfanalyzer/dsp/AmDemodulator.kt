package com.mantz_it.rfanalyzer.dsp

import android.util.Log
import com.mantz_it.rfanalyzer_ipc.SamplePacket

/**
 * <h1>RF Analyzer - AM Demodulator</h1>
 *
 * Module:      AmDemodulator.kt
 * Description: AM demodulator. Owns its own channel low-pass filter (before demodulation), an audio
 *              low-pass filter and AGC state. Envelope detection turns each sideband tone into an
 *              audio tone at the same offset, so AM's audio bandwidth follows channelWidth: the
 *              audio filter is rebuilt with the channel filter - unlike FM's, whose cutoff is fixed
 *              (FM's channel width is set by the deviation, not by the audio).
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class AmDemodulator(private val sampleRate: Int, packetSize: Int) {

    companion object {
        private const val LOGTAG = "AmDemodulator"
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
    private var audioFilter: FirFilter? = null
    private val channelFiltered = SamplePacket(packetSize)
    private val demodulated = SamplePacket(packetSize)
    private var lastMax = 0f // used for gain control

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
        Log.d(LOGTAG, ("rebuildFilter: created new channel filter with " + filter.numberOfTaps
                    + " taps. Cut-Off=" + filter.cutOffFrequency + " transition=" + filter.transitionWidth))

        // Audio bandwidth for AM scales with channelWidth (see class doc) - rebuilt here, unlike
        // FM's fixed-cutoff audio filter.
        val audioCutoff = channelWidth * 0.5f
        audioFilter = FirFilter.createLowPass(
            1,
            1f,
            sampleRate.toFloat(),
            audioCutoff,
            audioCutoff * 0.2f,
            AUDIO_FILTER_ATTENUATION,
            AUDIO_FILTER_MAX_TAPS
        )
    }

    /**
     * Will AM demodulate the samples in input.
     * Demodulated samples are stored in the real array of output. Note: All samples in output
     * will always be overwritten!
     *
     * @param input        incoming (modulated) samples
     * @param output    outgoing (demodulated) samples
     */
    fun demodulate(input: SamplePacket, output: SamplePacket) {
        val filter = channelFilter ?: return
        val postFilter = audioFilter ?: return

        channelFiltered.setSize(0)
        if (filter.filter(input, channelFiltered, 0, input.size()) < input.size()) {
            Log.e(LOGTAG, "demodulate: could not filter all samples from input packet.")
        }

        val reIn = channelFiltered.re()
        val imIn = channelFiltered.im()
        val reOut = demodulated.re()
        var avg = 0f
        lastMax *= 0.95f // simplest AGC

        val size = channelFiltered.size()
        if (size == 0) return

        // Complex to squared magnitude (power)
        for (i in 0..<size) {
            reOut[i] = (reIn[i] * reIn[i] + imIn[i] * imIn[i])
            avg += reOut[i]
            if (reOut[i] > lastMax) lastMax = reOut[i]
        }
        avg /= size

        // normalize values:
        val gain = 0.75f / lastMax
        for (i in 0..<size) reOut[i] = (reOut[i] - avg) * gain

        demodulated.setSize(size)
        demodulated.sampleRate = sampleRate
        demodulated.timestamp = channelFiltered.timestamp

        output.setSize(0)
        if (postFilter.filterReal(demodulated, output, 0, demodulated.size()) < demodulated.size()) {
            Log.e(LOGTAG, "demodulate: could not filter all samples through post-demod audio filter.")
        }
    }
}
