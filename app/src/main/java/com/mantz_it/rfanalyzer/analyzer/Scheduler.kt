package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.source.IQSourceInterface
import com.mantz_it.rfanalyzer.source.SamplePacket
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue

/**
 * <h1>RF Analyzer - Scheduler</h1>
 *
 * Module:      Scheduler.kt
 * Description: This Thread is responsible for forwarding the samples from the input hardware
 * to the Demodulator and to the Processing Loop and at the correct speed and format.
 * Sample packets are passed to other blocks by using blocking queues. The samples passed
 * to the Demodulator will be shifted to base band first.
 * If the Demodulator or the Processing Loop are to slow, the scheduler will automatically
 * drop incoming samples to keep the buffer of the source from beeing filled up.
 *
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
class Scheduler(private val source: IQSourceInterface, private val putNewFftSamples: (FloatArray) -> Unit) : Thread() {

    companion object {
        // Define the size of the fft output and input Queues. By setting this value to 2 we basically end up
        // with double buffering. Maybe the two queues are overkill, but it works pretty well like this and
        // it handles the synchronization between the scheduler thread and the processing loop for us.
        // Note that setting the size to 1 will not work well and any number higher than 2 will cause
        // higher delays when switching frequencies.
        private const val FFT_QUEUE_SIZE = 2
        private const val DEMOD_QUEUE_SIZE = 20
        private const val SQUELCH_DEBOUNCE_COUNT = 50  // number of loop iterations to wait before squelch goes from true to false
        private const val LOGTAG = "Scheduler"
    }

    val demodOutputQueue: ArrayBlockingQueue<SamplePacket> = ArrayBlockingQueue(DEMOD_QUEUE_SIZE) // Queue that delivers samples to the Demodulator block
    val demodInputQueue: ArrayBlockingQueue<SamplePacket> = ArrayBlockingQueue(DEMOD_QUEUE_SIZE)  // Queue that collects used buffers from the Demodulator block
    var channelFrequency: Long = 0 // Shift frequency to this value when passing packets to demodulator
    var isDemodulationActivated: Boolean = false // Indicates if samples should be forwarded to the demodulator queues or not.
    var squelchSatisfied: Boolean = false // indicates whether the current signal is strong enough to cross the squelch threshold
    // Optional tap for the decoder. Receives the same interleaved IQ buffer that is used for the
    // FFT. The callee must copy it because the buffer is reused on the next iteration.
    @Volatile
    var decoderSampleSink: ((FloatArray) -> Unit)? = null

    private val interleavedFftBuffer = FloatArray(source.packetSize / source.bytesPerSample * 2)

    private var stopRequested = true

    // Recording
    private var stopRecording = false
    private var bufferedOutputStream: BufferedOutputStream? = null          // Used for recording
    private var recordedFileSize: Long = 0                                  // Number of bytes written to the recording file
    private var recordedStartTimestamp: Long = 0                            // Timestamp of when recording was started
    private var maxRecordingTime: Long? = null                              // Maximum time to record (in milliseconds). null -> never stop
    private var maxRecordingFileSize: Long? = null                          // Maximum file size for the recording (in bytes). null -> never stop
    private var onlyWhenSquelchIsSatisfied: Boolean = false                 // only write samples to file when squelch is satisfied
    private var onRecordingStopped: ((finalSize: Long) -> Unit)? = null     // callback when recording stops (with final file size in bytes)
    private var onFileSizeUpdate: ((currentFileSize: Long) -> Unit)? = null // periodical callback during recording to report file size (in bytes) to ui
    private var squelchDebounceCounter: Int = 0                             // helper counter to debounce squelch changes

    init {
        val samplesPerPacket = source.packetSize / source.bytesPerSample
        // allocate the buffer packets.
        for (i in 0 until DEMOD_QUEUE_SIZE) demodInputQueue.offer(
            SamplePacket(samplesPerPacket)
        )
    }

    fun stopScheduler() {
        this.stopRequested = true
        source.stopSampling()
    }

    override fun start() {
        this.stopRequested = false
        super.start()
    }

    /**
     * Will stop writing samples to the bufferedOutputStream and close it.
     */
    fun stopRecording() {
        this.stopRecording = true
        Log.i(LOGTAG, "stopRecording")
    }

    fun startRecording(bufferedOutputStream: BufferedOutputStream,
                       onlyWhenSquelchIsSatisfied: Boolean,                     // only write samples to file when squelch is satisfied
                       maxRecordingTime: Long? = null,                          // Maximum time to record (in milliseconds). null -> never stop
                       maxRecordingFileSize: Long? = null,                      // Maximum file size for the recording (in bytes). null -> never stop
                       onRecordingStopped: (finalSize: Long) -> Unit,           // callback when recording stops (with final file size in bytes)
                       onFileSizeUpdate: (currentFileSize: Long) -> Unit) {     // periodical callback during recording to report file size (in bytes) to ui
        stopRecording = false
        recordedFileSize = 0
        recordedStartTimestamp = System.currentTimeMillis()
        this.bufferedOutputStream = bufferedOutputStream
        this.onlyWhenSquelchIsSatisfied = onlyWhenSquelchIsSatisfied
        this.maxRecordingTime = maxRecordingTime
        this.maxRecordingFileSize = maxRecordingFileSize
        this.onRecordingStopped = onRecordingStopped
        this.onFileSizeUpdate = onFileSizeUpdate
        Log.i(LOGTAG, "startRecording: Recording started.")
    }

    override fun run() {
        this.name = "Thread-Scheduler-" + System.currentTimeMillis()
        Log.i(LOGTAG, "Scheduler started. (Thread: " + this.name + ")")
        var counter: Long = 0

        val nsPerPacket = (source.packetSize / source.bytesPerSample) * 1_000_000_000f / source.sampleRate

        source.startSampling()
        while (!stopRequested) {
            // Get a new packet from the source:
            val packet = source.getPacket(1000)
            if (packet == null) {
                Log.e(LOGTAG, "run: No more packets from source. Shutting down...")
                this.stopScheduler()
                break
            }
            val startTimestamp = System.nanoTime()

            // Squelch debounce: When squelchSatisfied goes from true to false, wait SQUELCH_DEBOUNCE_COUNT loop iterations before actually stop demodulation/recording
            if (squelchSatisfied)
                squelchDebounceCounter = 0
            else if (squelchDebounceCounter < SQUELCH_DEBOUNCE_COUNT)
                squelchDebounceCounter++

            ///// Recording ////////////////////////////////////////////////////////////////////////
            if (bufferedOutputStream != null) {
                if(squelchSatisfied || !onlyWhenSquelchIsSatisfied || squelchDebounceCounter < SQUELCH_DEBOUNCE_COUNT) {
                    try {
                        bufferedOutputStream!!.write(packet)
                        recordedFileSize += packet.size.toLong()
                    } catch (e: IOException) {
                        Log.e(LOGTAG, "run: Error while writing to output stream (recording): " + e.message)
                        this.stopRecording()
                    }
                }
                // report file size every 100 packets:
                if(counter % 100 == 0L) onFileSizeUpdate?.let { it(recordedFileSize) }
                // check if recording should stop:
                maxRecordingTime?.let { if(it <= (System.currentTimeMillis()-recordedStartTimestamp)) {
                        Log.i(LOGTAG, "run: Max Recording Time reached!")
                        stopRecording()
                    }
                }
                maxRecordingFileSize?.let { if(it <= recordedFileSize) {
                        Log.i(LOGTAG, "run: Max Recording File Size reached!")
                        stopRecording()
                    }
                }
                if (stopRecording) {
                    try {
                        bufferedOutputStream!!.close()
                    } catch (e: IOException) {
                        Log.e(LOGTAG, "run: Error while closing output stream (recording): " + e.message)
                    }
                    bufferedOutputStream = null
                    Log.i(LOGTAG, "run: Recording stopped.")
                    onRecordingStopped?.let { it(recordedFileSize) }
                }
                counter++
            }

            ///// Demodulation /////////////////////////////////////////////////////////////////////
            if (isDemodulationActivated && (squelchSatisfied || squelchDebounceCounter < SQUELCH_DEBOUNCE_COUNT)) {
                // Get a buffer from the demodulator inputQueue
                val demodBuffer = demodInputQueue.poll()
                if (demodBuffer != null) {
                    demodBuffer.setSize(0) // mark buffer as empty
                    // fill the packet into the buffer and shift its spectrum by mixFrequency:
                    source.mixPacketIntoSamplePacket(packet, demodBuffer, channelFrequency)
                    demodOutputQueue.offer(demodBuffer) // deliver packet
                } else {
                    Log.d(LOGTAG, "run: Flush the demod queue because demodulator is too slow!")
                    generateSequence { demodOutputQueue.poll() }
                        .forEach { demodInputQueue.offer(it) }
                }
            }

            ///// FFT //////////////////////////////////////////////////////////////////////////////
            source.fillPacketIntoInterleavedBuffer(packet, interleavedFftBuffer)
            putNewFftSamples(interleavedFftBuffer)

            ///// Decoder //////////////////////////////////////////////////////////////////////////
            // Best-effort tap: the sink copies the buffer and may drop it if the decoder is busy.
            decoderSampleSink?.invoke(interleavedFftBuffer)

            // Return the packet back to the source buffer pool:
            source.returnPacket(packet)

            // Performance Tracking:
            val processingTime = System.nanoTime() - startTimestamp
            val load = processingTime / nsPerPacket
            GlobalPerformanceData.updateLoad("Scheduler", load)
        }
        this.stopRequested = true
        if (bufferedOutputStream != null) {
            try {
                bufferedOutputStream!!.close()
            } catch (e: IOException) {
                Log.e(LOGTAG, "run: Error while closing output stream (cleanup)(recording): " + e.message)
            }
            bufferedOutputStream = null
            Log.i(LOGTAG, "run: Recording stopped (Scheduler shutting down).")
            onRecordingStopped?.let { it(recordedFileSize) }
        }
        Log.i(LOGTAG, "Scheduler stopped. (Thread: " + this.name + ")")
    }
}
