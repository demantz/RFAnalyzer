package com.mantz_it.rfanalyzer.dsp

import android.util.Log
import com.mantz_it.rfanalyzer.source.SamplePacket
import kotlin.math.cos
import kotlin.math.sin

/**
 * <h1>RF Analyzer - FIR Filter</h1>
 *
 * Module:      FirFilter.kt
 * Description: This class implements a FIR filter. Most of the code is
 * copied from the firdes and firfilter module from GNU Radio.
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
class FirFilter(
	val taps: FloatArray,
	val decimation: Int,
	val gain: Float,
	val sampleRate: Float,
	val cutOffFrequency: Float,
	val transitionWidth: Float,
	val attenuation: Float
) {
    private var tapCounter = 0
    private val delaysReal: FloatArray = FloatArray(taps.size)
    private val delaysImag: FloatArray = FloatArray(taps.size)
    private var decimationCounter = 1

    /**
     * @return length of the taps array
     */
    val numberOfTaps: Int
        get() = taps.size

    /**
     * Filters the samples from the input sample packet and appends filter output to the output
     * sample packet. Stops automatically if output sample packet is full.
     * @param inPacket  input sample packet
     * @param outPacket output sample packet
     * @param offset    offset to use as start index for the input packet
     * @param length    max number of samples processed from the input packet
     * @return number of samples consumed from the input packet
     */
    fun filter(inPacket: SamplePacket, outPacket: SamplePacket, offset: Int, length: Int): Int {
        var index: Int
        var indexOut = outPacket.size()
        val outputCapacity = outPacket.capacity()
        val reIn = inPacket.re()
        val imIn = inPacket.im()
        val reOut = outPacket.re()
        val imOut = outPacket.im()

        // insert each input sample into the delay line:
        for (i in 0..<length) {
            delaysReal[tapCounter] = reIn[offset + i]
            delaysImag[tapCounter] = imIn[offset + i]

            // Calculate the filter output for every Mth element (were M = decimation)
            if (decimationCounter == 0) {
                // first check if we have enough space in the output buffers:
                if (indexOut == outputCapacity) {
                    outPacket.setSize(indexOut) // update size of output sample packet
                    outPacket.sampleRate = inPacket.sampleRate / decimation // update the sample rate of the output sample packet
                    return i // We return the number of consumed samples from the input buffers
                }

                // Calculate the results:
                reOut[indexOut] = 0f
                imOut[indexOut] = 0f
                index = tapCounter
                for (tap in taps) {
                    reOut[indexOut] += tap * delaysReal[index]
                    imOut[indexOut] += tap * delaysImag[index]
                    index--
                    if (index < 0) index = taps.size - 1
                }

                // increase indexOut:
                indexOut++
            }

            // update counters:
            decimationCounter++
            if (decimationCounter >= decimation) decimationCounter = 0
            tapCounter++
            if (tapCounter >= taps.size) tapCounter = 0
        }
        outPacket.setSize(indexOut) // update size of output sample packet
        outPacket.sampleRate = inPacket.sampleRate / decimation // update the sample rate of the output sample packet
        return length // We return the number of consumed samples from the input buffers
    }

    /**
     * Filters the real parts of the samples from the input sample packet and appends filter output to the output
     * sample packet. Stops automatically if output sample packet is full.
     * @param inPacket  input sample packet
     * @param outPacket output sample packet
     * @param offset    offset to use as start index for the input packet
     * @param length    max number of samples processed from the input packet
     * @return number of samples consumed from the input packet
     */
    fun filterReal(inPacket: SamplePacket, outPacket: SamplePacket, offset: Int, length: Int): Int {
        var index: Int
        var indexOut = outPacket.size()
        val outputCapacity = outPacket.capacity()
        val reIn = inPacket.re()
        val reOut = outPacket.re()

        // insert each input sample into the delay line:
        for (i in 0..<length) {
            delaysReal[tapCounter] = reIn[offset + i]

            // Calculate the filter output for every Mth element (were M = decimation)
            if (decimationCounter == 0) {
                // first check if we have enough space in the output buffers:
                if (indexOut == outputCapacity) {
                    outPacket.setSize(indexOut) // update size of output sample packet
                    outPacket.sampleRate = inPacket.sampleRate / decimation // update the sample rate of the output sample packet
                    return i // We return the number of consumed samples from the input buffers
                }

                // Calculate the results:
                reOut[indexOut] = 0f
                index = tapCounter
                for (tap in taps) {
                    reOut[indexOut] += tap * delaysReal[index]
                    index--
                    if (index < 0) index = taps.size - 1
                }

                // increase indexOut:
                indexOut++
            }

            // update counters:
            decimationCounter++
            if (decimationCounter >= decimation) decimationCounter = 0
            tapCounter++
            if (tapCounter >= taps.size) tapCounter = 0
        }
        outPacket.setSize(indexOut) // update size of output sample packet
        outPacket.sampleRate = inPacket.sampleRate / decimation // update the sample rate of the output sample packet
        return length // We return the number of consumed samples from the input buffers
    }

    companion object {
        private const val LOGTAG = "FirFilter"

        /**
         * FROM GNU Radio firdes::low_pass_2:
         *
         * Will calculate the tabs for the specified low pass filter and return a FirFilter instance
         *
         * @param decimation            decimation factor
         * @param gain                    filter pass band gain
         * @param sampleRate            sample rate
         * @param cutoffFrequency            cut off frequency (end of pass band)
         * @param transitionWidth        width from end of pass band to start stop band
         * @param attenuationInDecibels        attenuation of stop band
         * @return instance of FirFilter
         */
		@JvmStatic
		fun createLowPassTaps(
            decimation: Int,
            gain: Float,
            sampleRate: Float,
            cutoffFrequency: Float,
            transitionWidth: Float,
            attenuationInDecibels: Float,
            windowFunction: WindowFunction = BlackmanWindow(),
            maxTaps: Int = 0  // Tap count will be limited by maxTaps. Value '0' means no limit
        ): FloatArray?
        {
            if (sampleRate <= 0.0) {
                Log.e(LOGTAG, "createLowPassTaps: firdes check failed: sampling_freq > 0")
                return null
            }

            if (cutoffFrequency <= 0.0 || cutoffFrequency > sampleRate / 2) {
                Log.e(LOGTAG, "createLowPassTaps: firdes check failed: 0 < fa ($cutoffFrequency) <= sampling_freq ($sampleRate) / 2")
                return null
            }

            if (transitionWidth <= 0) {
                Log.e(LOGTAG, "createLowPassTaps: firdes check failed: transition_width > 0")
                return null
            }

            // Calculate number of tabs
            // Based on formula from Multirate Signal Processing for
            // Communications Systems, fredric j harris
            var ntaps = (attenuationInDecibels * sampleRate / (22.0 * transitionWidth)).toInt()
            ntaps = if (maxTaps > 0) ntaps.coerceAtMost(maxTaps) else ntaps
            if ((ntaps and 1) == 0)  // if even...
                ntaps++ // ...make odd

            // construct the truncated ideal impulse response
            // [sin(x)/x for the low pass case]
            val taps = FloatArray(ntaps)

            val M = (ntaps - 1) / 2
            val fwT0 = 2 * Math.PI.toFloat() * cutoffFrequency / sampleRate
            for (n in -M..M) {
                if (n == 0) taps[n + M] = fwT0 / Math.PI.toFloat() * windowFunction.value(n + M, ntaps)
                else {
                    // a little algebra gets this into the more familiar sin(x)/x form
                    taps[n + M] =
                        sin((n * fwT0).toDouble()).toFloat() / (n * Math.PI.toFloat()) * windowFunction.value(n + M, ntaps)
                }
            }

            // find the factor to normalize the gain, fmax.
            // For low-pass, gain @ zero freq = 1.0
            var fmax = taps[0 + M]
            for (n in 1..M) fmax += 2 * taps[n + M]

            val actualGain = gain / fmax // normalize

            for (i in 0..<ntaps) taps[i] *= actualGain

            return taps
        }

        @JvmStatic
        fun createLowPass(
            decimation: Int,
            gain: Float,
            sampleRate: Float,
            cutoffFrequency: Float,
            transitionWidth: Float,
            attenuationInDecibels: Float,
        ): FirFilter?
        {
            val taps = createLowPassTaps(decimation, gain, sampleRate, cutoffFrequency, transitionWidth, attenuationInDecibels, BlackmanWindow())
            return if(taps == null) null else FirFilter(
                taps,
                decimation,
                gain,
                sampleRate,
                cutoffFrequency,
                transitionWidth,
                attenuationInDecibels,
            )
        }
    }
}