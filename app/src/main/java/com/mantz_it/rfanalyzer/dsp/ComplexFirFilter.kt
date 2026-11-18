package com.mantz_it.rfanalyzer.dsp

import android.util.Log
import com.mantz_it.rfanalyzer_ipc.SamplePacket

/**
 * <h1>RF Analyzer - Complex FIR Filter</h1>
 *
 * Module:      ComplexFirFilter.kt
 * Description: A FIR filter with complex taps and its band-pass design, derived from GNU
 *              Radio's firdes and fir_filter (gr-filter).
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * GNU Radio: Copyright (C) 2002-2013 Free Software Foundation, Inc.
 * License: GPL version 3 or later - GNU Radio's license, which applies to code derived from it.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
class ComplexFirFilter private constructor(
    private val tapsReal: FloatArray,
    private val tapsImag: FloatArray,
    /** Decimation factor. */
    val decimation: Int,
    /** Pass band gain. */
    val gain: Float,
    val sampleRate: Float,
    /** Start of the pass band (Hz). */
    val lowCutOffFrequency: Float,
    /** End of the pass band (Hz). */
    val highCutOffFrequency: Float,
    /** Width from the end of the pass band to the start of the stop band (Hz). */
    val transitionWidth: Float,
    /** Attenuation of the stop band (dB). */
    val attenuation: Float,
) {
    private val delaysReal = FloatArray(tapsReal.size)
    private val delaysImag = FloatArray(tapsImag.size)
    private var tapCounter = 0
    private var decimationCounter = 1

    init {
        require(tapsReal.size == tapsImag.size) { "real and imag filter taps have to be of the same length!" }
    }

    val numberOfTaps: Int get() = tapsReal.size

    /**
     * Filters the samples from the input sample packet and appends filter output to the output
     * sample packet. Stops automatically if output sample packet is full.
     * @param input   input sample packet
     * @param output  output sample packet
     * @param offset  offset to use as start index for the input packet
     * @param length  max number of samples processed from the input packet
     * @return number of samples consumed from the input packet
     */
    fun filter(input: SamplePacket, output: SamplePacket, offset: Int, length: Int): Int {
        var indexOut = output.size()
        val outputCapacity = output.capacity()
        val reIn = input.re()
        val imIn = input.im()
        val reOut = output.re()
        val imOut = output.im()

        // insert each input sample into the delay line:
        for (i in 0 until length) {
            delaysReal[tapCounter] = reIn[offset + i]
            delaysImag[tapCounter] = imIn[offset + i]

            // Calculate the filter output for every Mth element (where M = decimation)
            if (decimationCounter == 0) {
                // first check if we have enough space in the output buffers:
                if (indexOut == outputCapacity) {
                    finishOutput(input, output, indexOut)
                    return i // We return the number of consumed samples from the input buffers
                }

                // Calculate the results:
                reOut[indexOut] = 0f
                imOut[indexOut] = 0f
                var index = tapCounter
                for (j in tapsReal.indices) {
                    reOut[indexOut] += tapsReal[j] * delaysReal[index] - tapsImag[j] * delaysImag[index]
                    imOut[indexOut] += tapsImag[j] * delaysReal[index] + tapsReal[j] * delaysImag[index]
                    index--
                    if (index < 0) index = tapsReal.size - 1
                }
                indexOut++
            }

            // update counters:
            decimationCounter++
            if (decimationCounter >= decimation) decimationCounter = 0
            tapCounter++
            if (tapCounter >= tapsReal.size) tapCounter = 0
        }
        finishOutput(input, output, indexOut)
        return length // We return the number of consumed samples from the input buffers
    }

    private fun finishOutput(input: SamplePacket, output: SamplePacket, size: Int) {
        output.setSize(size)
        output.sampleRate = input.sampleRate / decimation
        output.frequency = input.frequency
        output.timestamp = input.timestamp
    }

    companion object {
        private const val LOGTAG = "ComplexFirFilter"

        /**
         * FROM GNU Radio firdes::band_pass_2:
         *
         * Calculates the taps for the specified band pass filter and returns a ComplexFirFilter
         * instance (null if the parameters are invalid).
         *
         * @param decimation     decimation factor
         * @param gain           filter pass band gain
         * @param samplingFreq   sample rate (Hz)
         * @param lowCutoffFreq  cut off frequency (beginning of pass band, Hz)
         * @param highCutoffFreq cut off frequency (end of pass band, Hz)
         * @param transitionWidth width from end of pass band to start stop band (Hz)
         * @param attenuationDb  attenuation of stop band (dB)
         * @param maxTaps        maximum number of filter taps (0 = no limit)
         */
        fun createBandPass(
            decimation: Int,
            gain: Float,
            samplingFreq: Float,
            lowCutoffFreq: Float,
            highCutoffFreq: Float,
            transitionWidth: Float,
            attenuationDb: Float,
            maxTaps: Int = 0,
        ): ComplexFirFilter? {
            if (samplingFreq <= 0.0) {
                Log.e(LOGTAG, "createBandPass: firdes check failed: sampling_freq > 0")
                return null
            }
            if (lowCutoffFreq < samplingFreq * -0.5 || highCutoffFreq > samplingFreq * 0.5) {
                Log.e(LOGTAG, "createBandPass: firdes check failed: -sampling_freq / 2 < fa <= sampling_freq / 2")
                return null
            }
            if (lowCutoffFreq >= highCutoffFreq) {
                Log.e(LOGTAG, "createBandPass: firdes check failed: low_cutoff_freq >= high_cutoff_freq")
                return null
            }
            if (transitionWidth <= 0) {
                Log.e(LOGTAG, "createBandPass: firdes check failed: transition_width > 0")
                return null
            }

            // Calculate number of taps
            // Based on formula from Multirate Signal Processing for
            // Communications Systems, fredric j harris
            var ntaps = (attenuationDb * samplingFreq / (22.0 * transitionWidth)).toInt()
            if (maxTaps > 0 && ntaps > maxTaps) ntaps = maxTaps
            if (ntaps and 1 == 0) ntaps++ // make odd

            // construct the truncated ideal impulse response
            // [sin(x)/x for the low pass case]
            // Note: we calculate the real taps for a low pass and shift them
            val lowPassCutOff = (highCutoffFreq - lowCutoffFreq) / 2f
            val tapsLowPass = FloatArray(ntaps)
            val w = makeWindow(ntaps)

            val m = (ntaps - 1) / 2
            val fwT0 = 2 * Math.PI.toFloat() * lowPassCutOff / samplingFreq
            for (n in -m..m) {
                if (n == 0)
                    tapsLowPass[n + m] = fwT0 / Math.PI.toFloat() * w[n + m]
                else // a little algebra gets this into the more familiar sin(x)/x form
                    tapsLowPass[n + m] = Math.sin((n * fwT0).toDouble()).toFloat() / (n * Math.PI.toFloat()) * w[n + m]
            }

            // find the factor to normalize the gain, fmax.
            // For low-pass, gain @ zero freq = 1.0
            var fmax = tapsLowPass[m]
            for (n in 1..m) fmax += 2 * tapsLowPass[n + m]
            val actualGain = gain / fmax // normalize
            for (i in 0 until ntaps) tapsLowPass[i] *= actualGain

            // calc the band pass taps:
            val tapsReal = FloatArray(ntaps)
            val tapsImag = FloatArray(ntaps)
            val freq = Math.PI.toFloat() * (highCutoffFreq + lowCutoffFreq) / samplingFreq
            var phase = -freq * (ntaps / 2)
            for (i in 0 until ntaps) {
                tapsReal[i] = tapsLowPass[i] * Math.cos(phase.toDouble()).toFloat()
                tapsImag[i] = tapsLowPass[i] * Math.sin(phase.toDouble()).toFloat()
                phase += freq
            }

            return ComplexFirFilter(tapsReal, tapsImag, decimation, gain, samplingFreq, lowCutoffFreq, highCutoffFreq, transitionWidth, attenuationDb)
        }

        /** A Blackman window of [ntaps] samples: w(n) = 0.42 - 0.5 cos(2πn/(N-1)) + 0.08 cos(4πn/(N-1)). */
        private fun makeWindow(ntaps: Int): FloatArray = FloatArray(ntaps) { i ->
            0.42f - 0.5f * Math.cos(2 * Math.PI * i / (ntaps - 1)).toFloat() + 0.08f * Math.cos(4 * Math.PI * i / (ntaps - 1)).toFloat()
        }
    }
}
