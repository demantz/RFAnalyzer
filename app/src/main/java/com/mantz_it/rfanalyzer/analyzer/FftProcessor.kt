package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.nativedsp.NativeDsp
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.source.SamplePacket
import com.mantz_it.rfanalyzer.ui.composable.FftWaterfallSpeed
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * <h1>RF Analyzer - Analyzer Processing Loop</h1>
 *
 * Module:      FftProcessor.kt
 * Description: This Thread will fetch samples from the incoming queue (provided by the scheduler),
 * do the signal processing (fft) and then forward the result to the AnalyzerSurfacee.
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


// FFT Buffer
class FftProcessorData {
    val lock = ReentrantReadWriteLock()
    @Volatile
    var waterfallBuffer: Array<FloatArray>? = null // Circular buffer for fft samples (with history)
    @Volatile
    var waterfallBufferDirtyMap: Array<Boolean>? = null // Circular buffer which indicates for each row in waterfallBuffer if it must be recalculated
    @Volatile
    var frequencyOrSampleRateChanged = true
    @Volatile
    var writeIndex = 0 // Tracks where new FFT results go
    @Volatile
    var readIndex = 0  // Tracks where the latest FFT results are
    @Volatile
    var frequency: Long? = null
    @Volatile
    var sampleRate: Long? = null
}


class FftProcessor(
    initialFftSize: Int,
    private val inputQueue: ArrayBlockingQueue<SamplePacket>, // queue that delivers sample packets
    private val returnQueue: ArrayBlockingQueue<SamplePacket>, // queue to return unused buffers
    private val fftProcessorData: FftProcessorData,
    var waterfallSpeed: FftWaterfallSpeed,
    private val getChannelFrequencyRange: () -> Pair<Long, Long>?,
    private val onAverageSignalStrengthChanged: (Float) -> Unit,
) : Thread() {
    private var stopRequested = true // Will stop the thread when set to true
    private var nativeDsp: NativeDsp = NativeDsp()

    companion object {
        private const val LOGTAG = "FftProcessor"
    }

    /**
     * Will start the processing loop
     */
    override fun start() {
        this.stopRequested = false
        super.start()
    }

    /**
     * Will set the stopRequested flag so that the processing loop will terminate
     */
    fun stopLoop() {
        this.stopRequested = true
    }

    override fun run() {
        this.setName("Thread-FftProcessor-" + System.currentTimeMillis())
        Log.i(LOGTAG, "Processing loop started. (Thread: " + this.name + ")")
        Log.i(LOGTAG, "  using Queues: $inputQueue , $returnQueue")

        var lastFrequency: Long? = null
        var lastSampleRate: Long? = null
        val waterfallSpeedToBufferSizeMap = listOf(500, 400, 300) // slow, normal, fast
        var magPacket = SamplePacket(0)

        while (!stopRequested) {

            // fetch the next samples from the queue:
            var samples: SamplePacket?
            try {
                samples = inputQueue.poll(16, TimeUnit.MILLISECONDS)  // 16ms is roughly one frame at 60fps
                if (samples == null) {
                    //Log.d(LOGTAG, "run: Timeout while waiting on input data. skip.")
                    continue
                }
            } catch (e: InterruptedException) {
                Log.e(LOGTAG, "run: Interrupted while polling from input queue. stop.")
                this.stopLoop()
                break
            }

            if(stopRequested)
                break

            val startTime = System.nanoTime()  // start of processing

            if(magPacket.size() != samples.size()) {
                magPacket = SamplePacket(samples.size())
            }
            magPacket.frequency = samples.frequency
            magPacket.sampleRate = samples.sampleRate
            magPacket.setSize(samples.size())

            // do the signal processing:
            nativeDsp.performWindowedFftAndReturnMag(samples.re(), samples.im(), magPacket.re())

            //Log.d(LOGTAG, "After processing: ${System.currentTimeMillis()-startTime}ms")

            // return samples to the buffer pool
            returnQueue.offer(samples)

            // Update signal strength in appStateRepository:
            val samplesPerHz = magPacket.size() / samples.sampleRate.toFloat()
            val frequencyAtIndexZero = samples.frequency - samples.sampleRate/2
            val channelFrequencyRange = getChannelFrequencyRange()
            if(channelFrequencyRange != null) {
                val (channelStartFrequency, channelEndFrequency) = channelFrequencyRange
                val channelStartIndex = ((channelStartFrequency-frequencyAtIndexZero) * samplesPerHz).toInt() .coerceIn(0, magPacket.size())
                val channelEndIndex = ((channelEndFrequency-frequencyAtIndexZero) * samplesPerHz).toInt() .coerceIn(0, magPacket.size())
                if (channelEndIndex > channelStartIndex) {
                    var sum = 0f
                    val mag = magPacket.re()
                    for (i in channelStartIndex until channelEndIndex) sum += mag[i]
                    val averageSignalStrengh = sum / (channelEndIndex - channelStartIndex)
                    onAverageSignalStrengthChanged(averageSignalStrengh)
                }
            }

            // Performance Tracking
            val nsPerPacket = samples.size() * 1_000_000_000f / samples.sampleRate
            GlobalPerformanceData.updateLoad("FftProcessor", (System.nanoTime() - startTime) / nsPerPacket)

            // Put the results into fftProcessorData
            try {
                fftProcessorData.lock.writeLock().lock()
                fftProcessorData.frequency = magPacket.frequency
                fftProcessorData.sampleRate = magPacket.sampleRate.toLong()

                val frequencyChanged = magPacket.frequency != lastFrequency
                val sampleRateChanged = magPacket.sampleRate.toLong() != lastSampleRate
                fftProcessorData.frequencyOrSampleRateChanged = frequencyChanged || sampleRateChanged

                val frequencyDiff = if(lastFrequency != null) lastFrequency - magPacket.frequency else 0
                val magBuffer = magPacket.re()
                lastFrequency = magPacket.frequency
                lastSampleRate = magPacket.sampleRate.toLong()

                val waterfallBufferSize = waterfallSpeedToBufferSizeMap[waterfallSpeed.ordinal]
                if(fftProcessorData.waterfallBuffer == null || fftProcessorData.waterfallBuffer!![0].size != magBuffer!!.size) {
                    fftProcessorData.waterfallBuffer = Array(waterfallBufferSize) { FloatArray(magBuffer!!.size) { -9999f } }
                    fftProcessorData.waterfallBufferDirtyMap = Array(waterfallBufferSize) { true }
                    fftProcessorData.writeIndex = 0
                }
                // Update/Recreate the waterfallBuffer if speed changed (we preserve old samples and copy them over)
                if(fftProcessorData.waterfallBuffer!!.size != waterfallBufferSize) {
                    val oldSize = fftProcessorData.waterfallBuffer!!.size
                    fftProcessorData.waterfallBuffer = Array(waterfallBufferSize) { i ->
                        if (i < oldSize) {
                            val idx = (fftProcessorData.writeIndex + i) % oldSize
                            fftProcessorData.waterfallBuffer!![idx]
                        } else FloatArray(magBuffer!!.size) { -9999f } // Initialize new buffers
                    }
                    fftProcessorData.waterfallBufferDirtyMap = Array(waterfallBufferSize) { true }
                    fftProcessorData.writeIndex = 0
                }

                if(frequencyDiff != 0L) {
                    // shift history samples because the source frequency changed
                    val shiftOffset = (frequencyDiff*samplesPerHz).toInt()
                    val shiftLeft = shiftOffset < 0
                    if((shiftLeft && shiftOffset*-1 < magBuffer.size) || (!shiftLeft && shiftOffset < magBuffer.size)) {
                        fftProcessorData.waterfallBuffer!!.forEach {
                            if (shiftLeft) {
                                System.arraycopy(it, shiftOffset * -1, it, 0, it.size + shiftOffset ) // Shift left
                                it.fill(-9999f, it.size + shiftOffset, it.size) // Fill right side
                            } else {
                                System.arraycopy( it, 0, it, shiftOffset, it.size - shiftOffset ) // Shift right
                                it.fill(-9999f, 0, shiftOffset) // Fill left side
                            }
                        }
                    } else {
                        // clear entire history
                        fftProcessorData.waterfallBuffer!!.forEach { it.fill(-9999f) }
                    }
                    fftProcessorData.waterfallBufferDirtyMap!!.fill(true)
                } else if(sampleRateChanged) {
                    // clear entire history
                    fftProcessorData.waterfallBuffer!!.forEach { it.fill(-9999f) }
                    fftProcessorData.waterfallBufferDirtyMap!!.fill(true)
                }
                // copy newest samples into history
                System.arraycopy(magBuffer!!, 0, fftProcessorData.waterfallBuffer!![fftProcessorData.writeIndex], 0, magBuffer.size)
                fftProcessorData.waterfallBufferDirtyMap!![fftProcessorData.writeIndex] = true

                // update the read/write indices
                fftProcessorData.readIndex = fftProcessorData.writeIndex
                fftProcessorData.writeIndex = if(fftProcessorData.writeIndex==0) fftProcessorData.waterfallBuffer!!.size-1 else fftProcessorData.writeIndex-1
            } catch (e: InterruptedException) {
                Log.e(LOGTAG, "run: Interrupted while offering packet to magQueue . stop.")
                this.stopLoop()
                break
            } finally {
                fftProcessorData.lock.writeLock().unlock()
            }
            //Log.d(LOGTAG, "After draw: ${System.currentTimeMillis()-startTime}ms")
        }
        this.stopRequested = true
        Log.i(LOGTAG, "Processing loop stopped. (Thread: " + this.name + ")")
    }

}
