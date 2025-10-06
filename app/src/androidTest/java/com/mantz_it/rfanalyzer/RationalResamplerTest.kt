package com.mantz_it.rfanalyzer.dsp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mantz_it.rfanalyzer.source.SamplePacket
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(AndroidJUnit4::class)
class RationalResamplerTest {

    @Test
    fun testResamplerRoundTrip() {
        val interpolation = 11
        val decimation = 17
        val sampleRate = 48000
        val numSamples = 2000
        val frequency = 100.0 // test tone frequency

        // --- generate input signal (complex sine wave) ---
        val inPacket = SamplePacket(numSamples)
        val reIn = inPacket.re()
        val imIn = inPacket.im()
        for (i in 0 until numSamples) {
            val t = i / sampleRate.toFloat()
            reIn[i] = cos(2.0 * PI * frequency * t).toFloat()
            imIn[i] = sin(2.0 * PI * frequency * t).toFloat()
        }
        inPacket.setSize(numSamples)
        inPacket.sampleRate = sampleRate

        println("------------------------------------")
        println("Last 300 samples of inPacket:")
        for (i in inPacket.size()-300 until inPacket.size()) {
            println("Sample $i: Re = ${reIn[i]}, Im = ${imIn[i]}")
        }
        println("------------------------------------")

        // --- resampler down (11/17) ---
        val resamplerDown = RationalResampler(interpolation, decimation)
        val tmpPacket = SamplePacket(numSamples * interpolation / decimation + 100)
        var consumed = resamplerDown.resample(inPacket, tmpPacket, 0, numSamples)
        assertTrue("Did not consume full input", consumed == numSamples)

        println("Last 100 samples of tmpPacket:")
        val reTmp = tmpPacket.re()
        val imTmp = tmpPacket.im()
        for (i in 0 until minOf(100, tmpPacket.size())) {
            println("Sample $i: Re = ${reTmp[i]}, Im = ${imTmp[i]}")
        }
        println("------------------------------------")

        println("Last 100 samples of tmpPacket:")
        for (i in tmpPacket.size()-100 until tmpPacket.size()) {
            println("Sample $i: Re = ${reTmp[i]}, Im = ${imTmp[i]}")
        }
        println("------------------------------------")

        // --- resampler up (17/11) ---
        val resamplerUp = RationalResampler(decimation, interpolation)
        val outPacket = SamplePacket(numSamples + 100)
        consumed = resamplerUp.resample(tmpPacket, outPacket, 0, tmpPacket.size())
        assertTrue("Did not consume full intermediate output", consumed == tmpPacket.size())

        // --- compare input and final output ---
        val reOut = outPacket.re()
        val imOut = outPacket.im()
        val minLen = minOf(inPacket.size(), outPacket.size())

        println("Last 300 samples of outPacket:")
        for (i in outPacket.size()-300 until outPacket.size()) {
            println("Sample $i: Re = ${reOut[i]}, Im = ${imOut[i]}")
        }
        println("------------------------------------")
        println("in-size: ${inPacket.size()}, out-size: ${outPacket.size()}")

        val delay = 51 // empirically determined delay between input and output

        // callculate error between input and output. Only take the last samples when the filter delay line is not empty
        // compensate delay between input and output
        var mse = 0.0
        var count = 0
        for (i in minLen-1000 until minLen-delay) {
            val dr = reIn[i] - reOut[i+delay]
            val di = imIn[i] - imOut[i+delay]
            mse += dr * dr + di * di
            count++
        }
        mse /= count

        val rmse = sqrt(mse)
        // Allow a small error tolerance due to filter effects and boundaries
        assertTrue("Resampler round-trip RMSE too large: $rmse", rmse < 0.05)
    }

    @Test
    fun testApproximation() {
        val numIn = 2500101
        val denIn = 250000
        val maxDen = 10000
        val (num, den) = RationalResampler.limitDenominator(numIn, denIn, maxDen)
        val ratio = numIn.toDouble() / denIn
        val approx = num.toDouble() / den
        val error = kotlin.math.abs(ratio - approx)
        println("Approximation: $num/$den = $approx, error: $error")
        // Check denominator bound
        assert(den <= maxDen)
        // Check error is reasonably small
        assert(error < 1e-4) { "Error too large: $error" }
    }

    @Test
    fun testMaxApproximationError() {
        val denIn = 250000
        val maxDen = 10000
        var worstError = 0.0
        var worstNum = 0
        var worstApprox = 0.0

        for (numIn in 1_000_000..10_000_000 step 1) {
            val (num, den) = RationalResampler.limitDenominator(numIn, denIn, maxDen)
            val ratio = numIn.toDouble() / denIn
            val approx = num.toDouble() / den
            val error = kotlin.math.abs(ratio - approx)
            if (error > worstError) {
                worstError = error
                worstNum = numIn
                worstApprox = approx
            }
        }

        println("Worst error=$worstError at numIn=$worstNum, approx=$worstApprox")
        assert(worstError < 1e-4) { "Max error too large: $worstError" }
    }

}
