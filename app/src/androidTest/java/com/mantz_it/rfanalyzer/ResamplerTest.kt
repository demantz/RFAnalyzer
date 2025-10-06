package com.mantz_it.rfanalyzer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mantz_it.rfanalyzer.analyzer.Decimator
import com.mantz_it.rfanalyzer.analyzer.Resampler
import com.mantz_it.rfanalyzer.source.SamplePacket
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class ResamplerTest {

    private val LOGTAG = "ResamplerTest"

    @Test
    fun testResamplerMatchesDecimator() {
        val inputRate = 48000        // input sample rate
        val outputRate = 12000       // output sample rate (integer decimation case)
        val freq = 100.0             // sine frequency in Hz
        val durationSec = 1.0        // test duration
        val packetSize = 1024        // packet size in samples

        val numSamples = (inputRate * durationSec).toInt()
        val re = FloatArray(numSamples)
        val im = FloatArray(numSamples)

        // Generate IQ sine wave: cos for I, sin for Q
        for (n in 0 until numSamples) {
            val t = n.toDouble() / inputRate
            re[n] = cos(2.0 * Math.PI * freq * t).toFloat()
            im[n] = sin(2.0 * Math.PI * freq * t).toFloat()
        }

        // Split into packets
        val inputPackets = mutableListOf<SamplePacket>()
        var pos = 0
        while (pos < numSamples) {
            val n = minOf(packetSize, numSamples - pos)
            val reSlice = re.copyOfRange(pos, pos + n)
            val imSlice = im.copyOfRange(pos, pos + n)
            val packet = SamplePacket(reSlice, imSlice, 0, inputRate, n)
            inputPackets.add(packet)
            pos += n
        }

        // Queues
        val inQueue1 = ArrayBlockingQueue<SamplePacket>(inputPackets.size)
        val retQueue1 = ArrayBlockingQueue<SamplePacket>(inputPackets.size)
        val inQueue2 = ArrayBlockingQueue<SamplePacket>(inputPackets.size)
        val retQueue2 = ArrayBlockingQueue<SamplePacket>(inputPackets.size)

        inQueue1.addAll(inputPackets.map { it }) // shallow reuse is fine here
        inQueue2.addAll(inputPackets.map { it })

        // Start Decimator
        val decimator = Decimator(outputRate, packetSize, inQueue1, retQueue1)
        decimator.start()

        // Start RationalResampler
        val resampler = Resampler(outputRate, packetSize,  inQueue2, retQueue2)
        resampler.start()

        // Collect outputs
        val outRe1 = mutableListOf<Float>()
        val outIm1 = mutableListOf<Float>()
        val outRe2 = mutableListOf<Float>()
        val outIm2 = mutableListOf<Float>()

        val timeoutMs = 5000
        while (outRe1.size < numSamples * outputRate / inputRate) {
            val p = decimator.getDecimatedPacket(timeoutMs) ?: break
            repeat(p.size()) {
                outRe1.add(p.re(it))
                outIm1.add(p.im(it))
            }
            decimator.returnDecimatedPacket(p)
        }
        while (outRe2.size < numSamples * outputRate / inputRate) {
            val p = resampler.getResampledPacket(timeoutMs) ?: break
            repeat(p.size()) {
                outRe2.add(p.re(it))
                outIm2.add(p.im(it))
            }
        }

        // Stop
        decimator.stopDecimator()
        resampler.stopResampler()

        // Compare sizes
        Log.i(LOGTAG, "Decimator produced ${outRe1.size} samples, Resampler produced ${outRe2.size} samples")
        val nCompare = minOf(outRe1.size, outRe2.size)
        assertTrue("No samples produced", nCompare > 0)

        // Compare error
        val delay = 11 // empirically determined delay in samples
        var mse = 0.0
        var count = 0
        for (i in 0 until nCompare - delay) {
            val dr = outRe1[i] - outRe2[i+delay]
            val di = outIm1[i] - outIm2[i+delay]
            Log.d(LOGTAG, "${outRe1[i]} - ${outRe2[i]}    |    ${outIm1[i]} - ${outIm2[i]}")
            mse += (dr * dr + di * di)
            count++
        }
        mse /= count.toDouble()

        Log.i(LOGTAG, "Mean squared error between Decimator and Resampler = $mse")
        // We expect some small difference, but not huge
        assertTrue("MSE too high: $mse", mse < 0.003)
    }
}
