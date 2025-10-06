package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.dsp.RationalResampler
import com.mantz_it.rfanalyzer.source.SamplePacket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * <h1>RF Analyzer - Resampler</h1>
 *
 * Module:      Resampler.kt
 * Description: Generalized resampler block that downsamples/upsamples the incoming
 *              signal to the desired output sample rate using a RationalResampler.
 *              Runs in its own thread like the old Decimator.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class Resampler(
    var outputSampleRate: Int,
    private val packetSize: Int,
    private val inputQueue: ArrayBlockingQueue<SamplePacket>,
    private val inputReturnQueue: ArrayBlockingQueue<SamplePacket>
) : Thread() {

    private var stopRequested = true
    private var resampler: RationalResampler? = null
    private var inputRate: Int = 0
    private var lastOutputRate: Int = 0

    private val outputQueue = ArrayBlockingQueue<SamplePacket>(OUTPUT_QUEUE_SIZE)
    private val outputReturnQueue = ArrayBlockingQueue<SamplePacket>(OUTPUT_QUEUE_SIZE)

    init {
        repeat(OUTPUT_QUEUE_SIZE) {
            outputReturnQueue.offer(SamplePacket(packetSize))
        }
    }

    fun getResampledPacket(timeout: Int): SamplePacket? {
        return try {
            outputQueue.poll(timeout.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Log.e(LOGTAG, "getPacket: Interrupted while waiting on queue")
            null
        }
    }

    fun returnResampledPacket(packet: SamplePacket) {
        outputReturnQueue.offer(packet)
    }

    @Synchronized
    override fun start() {
        stopRequested = false
        super.start()
    }

    fun stopResampler() {
        stopRequested = true
    }

    override fun run() {
        this.name = "Thread-Resampler-${System.currentTimeMillis()}"
        Log.i(LOGTAG, "Resampler started. (Thread: $name)")

        while (!stopRequested) {
            val inputSamples: SamplePacket = try {
                inputQueue.poll(1000, TimeUnit.MILLISECONDS) ?: continue
            } catch (e: InterruptedException) {
                Log.e(LOGTAG, "run: Interrupted while waiting on input queue! stop.")
                stopRequested = true
                break
            }

            // Grab output buffer
            val outputSamples: SamplePacket = try {
                val packet = outputReturnQueue.poll(1000, TimeUnit.MILLISECONDS)
                if (packet == null) {
                    Log.d(LOGTAG, "run: No packets from outputReturnQueue. Skipping input packet.")
                    inputReturnQueue.offer(inputSamples)
                    continue
                } else packet
            } catch (e: InterruptedException) {
                Log.e(LOGTAG, "run: Interrupted while waiting on output return queue! stop.")
                stopRequested = true
                break
            }
            outputSamples.setSize(0) // mark as empty

            val inRate = inputSamples.sampleRate
            if (resampler == null || inRate != inputRate || outputSampleRate != lastOutputRate) {
                Log.d(LOGTAG, "run: (Re)creating resampler: new rates: inRate=$inRate, outRate=$outputSampleRate")
                // Limit the maximum interpolation factor to 10000 to keep memory usage of filter bank in bounds:
                val (interpolation, decimation) = RationalResampler.limitDenominator(outputSampleRate, inRate, 10000)
                val error = kotlin.math.abs(outputSampleRate.toDouble() / inRate - interpolation.toDouble() / decimation)
                Log.d(LOGTAG, "run: (Re)creating resampler: interpolation=$interpolation, decimation=$decimation (error=$error or ${(outputSampleRate*error).toInt()} Sps)")
                resampler = RationalResampler(interpolation, decimation, maxTaps = 500) // limiting tap count to max. 500 per FirFilter (should only kick in for large difference in sample rates, e.g. 20Msps -> 96000kSps)
                inputRate = inRate
                lastOutputRate = outputSampleRate
            }

            val startTimestamp = System.nanoTime()
            val consumed = resampler!!.resample(inputSamples, outputSamples, 0, inputSamples.size())
            if (consumed < inputSamples.size()) {
                Log.w(LOGTAG, "run: Resampler consumed only $consumed of ${inputSamples.size()} samples")
            }

            outputSamples.sampleRate = outputSampleRate  // set the desired output sample rate instead of the actual sample rate, to not confuse later stages

            // TODO: Currently only downsampling can guarantee that the samples fit in the out packet.
            // TODO: To support upsampling, the input packet should not be returned when not all samples were consumed!

            // performance tracking
            val nsPerPacket = inputSamples.size() * 1_000_000_000f / inputSamples.sampleRate
            GlobalPerformanceData.updateLoad("Resampler",(System.nanoTime() - startTimestamp) / nsPerPacket)

            inputReturnQueue.offer(inputSamples)
            outputQueue.offer(outputSamples)
        }

        stopRequested = true
        Log.i(LOGTAG, "Resampler stopped. (Thread: $name)")
    }

    companion object {
        private const val LOGTAG = "Resampler"
        private const val OUTPUT_QUEUE_SIZE = 2 // double buffer
    }
}
