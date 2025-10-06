package com.mantz_it.rfanalyzer.dsp

import android.util.Log
import com.mantz_it.rfanalyzer.source.SamplePacket
import kotlin.Int
import kotlin.math.abs

/**
 * <h1>RF Analyzer - Rational Resampler</h1>
 *
 * Module:      RationalResampler.kt
 *
 * Description: This class implements a Rational Resampler combining interpolation and decimation
 *  using a polyphase filterbank built from FirFilter. Most of the code is
 * copied and inspired from the rational_resampler_ccf module from GNU Radio.
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
class RationalResampler(
    private var interpolation: Int,
    private var decimation: Int,
    taps: FloatArray? = null,
    private var fractionalBw: Float = 0.4f,
    maxTaps: Int = 0  // Tap count (per FirFilter) will be limited by maxTaps. Value '0' means no limit
) {
    private val firTaps: Array<FloatArray>
    private val delayReal: FloatArray
    private val delayImag: FloatArray
    private var delayIndex: Int = 0
    private val ctrInit = 0
    private var ctr = ctrInit

    fun getInterpolation(): Int = interpolation
    fun getDecimation(): Int = decimation

    init {
        require(interpolation > 0) { "Interpolation must be > 0" }
        require(decimation > 0) { "Decimation must be > 0" }
        if (fractionalBw <= 0 || fractionalBw >= 0.5f) {
            fractionalBw = 0.4f
        }

        // reduce by GCD
        val d = gcd(interpolation, decimation)
        interpolation /= d
        decimation /= d

        // Design taps if none provided
        val staps = taps ?: designResamplerTaps(interpolation, decimation, fractionalBw, maxTaps)
        Log.d(LOGTAG, "designResamplerTaps generated ${staps.size} taps. (interpolation=${interpolation}, decimation=${decimation}, d=$d)")

        // Round taps up to multiple of interpolation
        val newTaps = staps.toMutableList()
        var n = newTaps.size % interpolation
        if (n > 0) {
            n = interpolation - n
            repeat(n) { newTaps.add(0f) }
        }

        // Split taps into polyphase subfilters
        val nt = newTaps.size / interpolation
        firTaps = Array(interpolation) { phase ->
            FloatArray(nt) { i -> newTaps[i * interpolation + phase] }
        }
        delayReal = FloatArray(nt)
        delayImag = FloatArray(nt)
    }

    /**
     * Resample input -> output.
     * @return number of consumed input samples
     */
    fun resample(inPacket: SamplePacket, outPacket: SamplePacket, offset: Int, length: Int): Int {
        val reIn = inPacket.re()
        val imIn = inPacket.im()
        val reOut = outPacket.re()
        val imOut = outPacket.im()
        val outputCapacity = outPacket.capacity()
        var indexOut = outPacket.size()
        var consumed = 0

        var inIdx = offset

        delayReal[delayIndex] = reIn[inIdx]
        delayImag[delayIndex] = imIn[inIdx]

        // ctr might be still too high from previous packet:
        while (ctr >= interpolation) {
            ctr -= interpolation
            // consume an input sample:
            inIdx++
            if (++delayIndex >= delayReal.size) delayIndex = 0
            consumed++
            if (consumed >= length)
                break
            else {
                delayReal[delayIndex] = reIn[inIdx]
                delayImag[delayIndex] = imIn[inIdx]
            }
        }

        while (consumed < length && indexOut < outputCapacity) {
            // Apply current phase FIR
            var reSum = 0f
            var imSum = 0f
            val taps = firTaps[ctr]
            var di = delayIndex
            for (t in taps) {
                reSum += t * delayReal[di]
                imSum += t * delayImag[di]
                if (--di < 0) di = delayReal.size - 1
            }
            reOut[indexOut] = reSum
            imOut[indexOut] = imSum
            //println("out[$indexOut] = fir[$ctr].filter(${reIn[inIdx]}, ${imIn[inIdx]}) = ${reOut[indexOut]}, ${imOut[indexOut]}")
            indexOut++

            // Step phase counter
            ctr += decimation
            while (ctr >= interpolation) {
                ctr -= interpolation
                // consume an input sample:
                inIdx++
                if (++delayIndex >= delayReal.size) delayIndex = 0
                consumed++
                if (consumed >= length)
                    break
                else {
                    delayReal[delayIndex] = reIn[inIdx]
                    delayImag[delayIndex] = imIn[inIdx]
                }
            }
        }

        outPacket.setSize(indexOut)
        outPacket.sampleRate = (inPacket.sampleRate.toLong() * interpolation / decimation).toInt()
        outPacket.frequency = inPacket.frequency
        return consumed
    }

    companion object {
        private const val LOGTAG = "RationalResampler"

        fun gcd(a: Int, b: Int): Int {
            var x = abs(a)
            var y = abs(b)
            while (y != 0) {
                val t = y
                y = x % y
                x = t
            }
            return x
        }

        /**
         * Given a target ratio (outRate / inRate), find a 'nice' rational approximation
         * whose denominator does not exceed maxDen, or whose error is tolerable.
         *
         * Similar in spirit to Python’s Fraction.limit_denominator().
         *
         * @param numerator desired output rate
         * @param denominator current input rate
         * @param maxDenominator maximum allowed denominator (i.e. decimation)
         * @return Pair(interp, decim) approximating outRate/inRate
         */
        fun limitDenominator(
            numerator: Int,
            denominator: Int,
            maxDenominator: Int = 10000
        ): Pair<Int, Int> {
            val target = numerator.toDouble() / denominator.toDouble()

            // Edge / trivial: if exactly divisible
            val g0 = gcd(numerator, denominator)
            val simpleNum = numerator / g0
            val simpleDen = denominator / g0
            if (simpleDen <= maxDenominator) {
                return Pair(simpleNum, simpleDen)
            }

            // Use mediants / Farey approach
            var lowerNum = 0
            var lowerDen = 1
            var upperNum = 1
            var upperDen = 0

            while (true) {
                val mediantNum = lowerNum + upperNum
                val mediantDen = lowerDen + upperDen
                if (mediantDen > maxDenominator) {
                    break
                }
                if (mediantNum.toDouble() / mediantDen < target) {
                    lowerNum = mediantNum
                    lowerDen = mediantDen
                } else {
                    upperNum = mediantNum
                    upperDen = mediantDen
                }
            }

            // Choose best of lower vs upper
            val lowerError = abs(target - lowerNum.toDouble() / lowerDen)
            val upperError = abs(target - upperNum.toDouble() / upperDen)
            return if (lowerError < upperError) Pair(lowerNum, lowerDen) else Pair(upperNum, upperDen)
        }

        /**
         * Design filter taps for the resampler.
         * Default: low-pass with Kaiser window (beta=7.0).
         */
        @JvmStatic
        fun designResamplerTaps(interpolation: Int, decimation: Int, fractionalBw: Float, maxTaps: Int): FloatArray {
            val beta = 7.0
            val halfband = 0.5
            val rate = interpolation.toFloat() / decimation.toFloat()
            val transWidth: Float
            val midTransitionBand: Float

            if (rate >= 1.0f) {
                transWidth = (halfband - fractionalBw).toFloat()
                midTransitionBand = (halfband - transWidth / 2.0).toFloat()
            } else {
                transWidth = (rate * (halfband - fractionalBw)).toFloat()
                midTransitionBand = (rate * halfband - transWidth / 2.0).toFloat()
            }

            return FirFilter.createLowPassTaps(
                decimation = 1,
                gain = interpolation.toFloat(),
                sampleRate = interpolation.toFloat(),
                cutoffFrequency = midTransitionBand,
                transitionWidth = transWidth,
                attenuationInDecibels = 72.22087f,  // hardcoded, max. attenuation for Kaiser @ beta=7.0
                windowFunction = KaiserWindow(beta),
                maxTaps = maxTaps * interpolation   // we will have <interpolation> number of FirFilters, so specify the total amount of taps
            ) ?: FloatArray(0)
        }
    }
}
