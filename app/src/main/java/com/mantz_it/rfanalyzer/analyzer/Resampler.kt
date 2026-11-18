package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.dsp.RationalResampler
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * <h1>RF Analyzer - Resampler</h1>
 *
 * Module:      Resampler.kt
 * Description: Generalized resampler block that downsamples/upsamples the incoming
 *              signal to the desired output sample rate using a RationalResampler.
 *              Runs in its own thread.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class Resampler(
    var outputSampleRate: Int,
    private val packetSize: Int,
    private val inputQueue: ArrayBlockingQueue<SamplePacket>,
    private val inputReturnQueue: ArrayBlockingQueue<SamplePacket>,
    var maxTaps: Int = 500
) : Thread() {

    private var stopRequested = true
    private var resampler: RationalResampler? = null
    private var inputRate: Int = 0
    private var lastOutputRate: Int = 0
    private var lastMaxTaps = maxTaps

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

            val inRate = inputSamples.sampleRate
            if (inRate == 0) {
                Log.d(LOGTAG, "run: inputSamples.sampleRate == 0. skipping..")
                inputReturnQueue.offer(inputSamples)
                continue
            }

            // Check if reconfiguration of Resampler is necessary
            if (resampler == null || inRate != inputRate || outputSampleRate != lastOutputRate || maxTaps != lastMaxTaps) {
                Log.d(LOGTAG, "run: (Re)creating resampler: new rates: inRate=$inRate, outRate=$outputSampleRate")
                // Limit the maximum interpolation factor to 10000 to keep memory usage of filter bank in bounds:
                val (interpolation, decimation) = RationalResampler.limitDenominator(outputSampleRate, inRate, 10000)
                val error = kotlin.math.abs(outputSampleRate.toDouble() / inRate - interpolation.toDouble() / decimation)
                Log.d(LOGTAG, "run: (Re)creating resampler: interpolation=$interpolation, decimation=$decimation (error=$error or ${(outputSampleRate*error).toInt()} Sps)")
                resampler?.close() // release the outgoing instance's native resources before replacing it
                resampler = RationalResampler(interpolation, decimation, maxTaps = maxTaps) // maxTaps per polyphase subfilter: only limits large rate ratios (e.g. 20 MSps -> 96 kSps)
                inputRate = inRate
                lastOutputRate = outputSampleRate
                lastMaxTaps = maxTaps
            }

            var inputBufferIndex = 0 // start processing from the beginning of the input packet
            var totalProcessingTimeNs = 0L
            while (inputBufferIndex < inputSamples.size()) {
                // Grab output buffer
                val outputSamples: SamplePacket = try {
                    val packet = outputReturnQueue.poll(1000, TimeUnit.MILLISECONDS)
                    if (packet == null) {
                        Log.d(LOGTAG, "run: No packets from outputReturnQueue. Skipping input packet.")
                        if (inputSamples.sampleRate > 0)
                            OverloadMonitor.reportLoss("Resampler", (inputSamples.size() - inputBufferIndex) * 1_000_000_000L / inputSamples.sampleRate)
                        break // the input packet is returned below (once)
                    } else packet
                } catch (e: InterruptedException) {
                    Log.e(LOGTAG, "run: Interrupted while waiting on output return queue! stop.")
                    stopRequested = true
                    break
                }
                outputSamples.setSize(0) // mark as empty
                val remainingSamples = inputSamples.size() - inputBufferIndex
                val startTimestamp = System.nanoTime()
                val consumed = resampler!!.resample(inputSamples, outputSamples, inputBufferIndex, remainingSamples)
                totalProcessingTimeNs += System.nanoTime() - startTimestamp
                outputSamples.sampleRate = outputSampleRate  // set the desired output sample rate instead of the actual sample rate, to not confuse later stages
                outputQueue.offer(outputSamples)
                inputBufferIndex += consumed
            }

            // performance tracking
            val nsPerPacket = inputSamples.size() * 1_000_000_000f / inputSamples.sampleRate
            GlobalPerformanceData.updateLoad("Resampler", totalProcessingTimeNs / nsPerPacket)
            OverloadMonitor.reportBusy("Resampler", totalProcessingTimeNs, nsPerPacket.toLong())

            inputReturnQueue.offer(inputSamples)
        }

        stopRequested = true
        resampler?.close()
        Log.i(LOGTAG, "Resampler stopped. (Thread: $name)")
    }

    companion object {
        private const val LOGTAG = "Resampler"
        private const val OUTPUT_QUEUE_SIZE = 2 // double buffer
    }
}
