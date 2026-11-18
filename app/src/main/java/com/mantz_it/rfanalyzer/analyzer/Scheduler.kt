package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.source.IQSourceInterface
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import java.io.BufferedOutputStream
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue

/**
 * <h1>RF Analyzer - Scheduler</h1>
 *
 * Module:      Scheduler.kt
 * Description: This Thread forwards the samples from the source to the Demodulator, the FFT
 *              (FftProcessor) and a running recording. Packets reach the Demodulator through
 *              blocking queues, shifted to baseband first. If the Demodulator is too slow, the
 *              scheduler drops samples so the source's buffers don't fill up.
 *
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class Scheduler(
    private val source: IQSourceInterface,
    private val putNewFftSamples: (samples: FloatArray, count: Int) -> Unit, // count: valid floats (2 per IQ sample)
    // Invoked when the scheduler loop terminates itself due to a fatal condition (source stopped
    // delivering packets), as opposed to a normal, caller-initiated stopScheduler().
    private val onUnexpectedStop: () -> Unit,
    /** The squelch's decision, made from the FFT (Squelch.kt). */
    private val squelch: SquelchGate,
) : Thread() {

    companion object {
        /** The demodulator queue holds at least this many packets, and more when they are small:
         *  a network source (rtl_tcp's 8192-sample packets, a KiwiSDR's 512) catches up after a
         *  stall in one burst, which must fit - 20 rtl_tcp packets are only 68 ms at 2.4 MSps. */
        private const val MIN_DEMOD_QUEUE_SIZE = 20
        private const val MAX_DEMOD_QUEUE_SIZE = 256
        private const val DEMOD_QUEUE_SAMPLES = 1_048_576
        /** How much of what arrived while the squelch was closed is kept for when it opens: the FFT
         *  notices a burst only some frames after it began, and a recording must keep its start. */
        private const val PREROLL_MS = 400L
        private const val LOGTAG = "Scheduler"
    }

    private val samplesPerPacket = source.packetSize / source.bytesPerSample
    private val demodQueueSize = (DEMOD_QUEUE_SAMPLES / samplesPerPacket.coerceAtLeast(1)).coerceIn(MIN_DEMOD_QUEUE_SIZE, MAX_DEMOD_QUEUE_SIZE)
    /** The pre-roll goes to the demodulator only while it has more free buffers than this - it
     *  must never fill the queue (that counts as overload). */
    private val demodFreeBuffersKept = demodQueueSize / 4
    val demodOutputQueue: ArrayBlockingQueue<SamplePacket> = ArrayBlockingQueue(demodQueueSize) // Queue that delivers samples to the Demodulator block
    val demodInputQueue: ArrayBlockingQueue<SamplePacket> = ArrayBlockingQueue(demodQueueSize)  // Queue that collects used buffers from the Demodulator block
    var channelFrequency: Long = 0 // Shift frequency to this value when passing packets to demodulator
    var isDemodulationActivated: Boolean = false // Indicates if samples should be forwarded to the demodulator queues or not.
    /** Whether the demodulator gets samples only while the squelch is open - the analog modes; a
     *  digital mode gets everything (its decoder finds the signal by itself). */
    @Volatile var demodulationFollowsSquelch: Boolean = true

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

    // What arrived while the squelch was closed and wasn't delivered yet (copies - source packets
    // go back to their pool), oldest first, at most PREROLL_MS of it. Delivered when it opens.
    private val preroll = ArrayDeque<ByteArray>()
    private var prerollBytes = 0L
    private var squelchWasOpen = true
    // The pre-roll on its way to the demodulator after the squelch opened: delivered as fast as the
    // demodulator frees buffers, the live packets queued behind it until it caught up (the
    // demodulator runs faster than real time, so that takes a fraction of a second).
    private val demodBacklog = ArrayDeque<ByteArray>()
    private var demodBacklogBytes = 0L

    init {
        // allocate the buffer packets.
        for (i in 0 until demodQueueSize) demodInputQueue.offer(
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

    private fun writeToRecording(bytes: ByteArray, count: Int) {
        try {
            bufferedOutputStream?.write(bytes, 0, count)
            recordedFileSize += count.toLong()
        } catch (e: IOException) {
            Log.e(LOGTAG, "run: Error while writing to output stream (recording): " + e.message)
            this.stopRecording()
        }
    }

    /** Fills [demodBuffer] with [packet] shifted by the channel frequency and hands it on. */
    private fun deliverToDemodulator(packet: ByteArray, demodBuffer: SamplePacket) {
        demodBuffer.setSize(0) // mark buffer as empty
        source.mixPacketIntoSamplePacket(packet, demodBuffer, channelFrequency)
        demodOutputQueue.offer(demodBuffer) // deliver packet
    }

    /** Keeps a copy of [packet]'s valid bytes while the squelch is closed, dropping the oldest
     *  beyond PREROLL_MS. */
    private fun keepForPreroll(packet: ByteArray, validByteCount: Int) {
        val maxBytes = source.sampleRate.toLong() * source.bytesPerSample * PREROLL_MS / 1000
        val copy = if (preroll.isNotEmpty() && prerollBytes + validByteCount > maxBytes && preroll.first().size == validByteCount) {
            preroll.removeFirst().also { prerollBytes -= it.size } // reuse the oldest's array
        } else ByteArray(validByteCount)
        System.arraycopy(packet, 0, copy, 0, validByteCount)
        preroll.addLast(copy)
        prerollBytes += validByteCount
        while (prerollBytes > maxBytes && preroll.size > 1) prerollBytes -= preroll.removeFirst().size
    }

    private fun clearPreroll() {
        preroll.clear()
        prerollBytes = 0
    }

    /**
     * The squelch just opened: what came in since it closed (up to PREROLL_MS) goes to the
     * recording, and its newest packets to the demodulator - as many as it has free buffers for,
     * keeping some for the live packets. In arrival order, before the packet that opened it.
     */
    private fun deliverPreroll(toRecording: Boolean, toDemodulator: Boolean) {
        if (toRecording) preroll.forEach { writeToRecording(it, it.size) }
        if (toDemodulator) preroll.forEach { addToDemodBacklog(it) }
        clearPreroll()
    }

    /** Queues [bytes] behind the backlog; beyond twice the pre-roll (a demodulator that can't keep
     *  up) the oldest is dropped and reported as lost. */
    private fun addToDemodBacklog(bytes: ByteArray) {
        demodBacklog.addLast(bytes)
        demodBacklogBytes += bytes.size
        val maxBytes = 2 * source.sampleRate.toLong() * source.bytesPerSample * PREROLL_MS / 1000
        while (demodBacklogBytes > maxBytes && demodBacklog.size > 1) {
            val dropped = demodBacklog.removeFirst()
            demodBacklogBytes -= dropped.size
            OverloadMonitor.reportLoss("Scheduler", dropped.size / source.bytesPerSample * 1_000_000_000L / source.sampleRate.coerceAtLeast(1))
        }
    }

    private fun drainDemodBacklog() {
        while (demodBacklog.isNotEmpty() && demodInputQueue.size > demodFreeBuffersKept) {
            val bytes = demodBacklog.removeFirst()
            demodBacklogBytes -= bytes.size
            deliverToDemodulator(bytes, demodInputQueue.poll() ?: break)
        }
    }

    private fun clearDemodBacklog() {
        demodBacklog.clear()
        demodBacklogBytes = 0
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

        source.startSampling()
        var gapMs = 0L // time without a packet - a network stream may stall and catch up (IQSourceInterface.maxPacketGapMs)
        while (!stopRequested) {
            // Get a new packet from the source:
            val waitStartMs = System.currentTimeMillis()
            val packet = source.getPacket(1000)
            if (packet == null && !stopRequested) {
                gapMs += System.currentTimeMillis() - waitStartMs
                if (gapMs < source.maxPacketGapMs) {
                    Log.w(LOGTAG, "run: no packet for $gapMs ms - waiting (up to ${source.maxPacketGapMs} ms)")
                    continue
                }
            }
            if (packet != null) gapMs = 0
            if (packet == null) {
                if (stopRequested) {
                    // Expected: a stop was already requested (e.g. user pressed Stop), which
                    // itself calls source.stopSampling() - packets legitimately stop arriving.
                    Log.i(LOGTAG, "run: No more packets from source (stop was already requested).")
                } else {
                    // Unexpected: nothing asked this scheduler to stop, yet the source stopped delivering packets.
                    Log.e(LOGTAG, "run: No more packets from source unexpectedly! Notifying caller and shutting down...")
                    onUnexpectedStop()
                }
                this.stopScheduler()
                break
            }
            val startTimestamp = System.nanoTime()
            val validByteCount = source.getValidByteCount(packet)
            val nsPerPacket = (validByteCount / source.bytesPerSample) * 1_000_000_000f / source.sampleRate

            val squelchOpen = squelch.open
            val recordingFollowsSquelch = bufferedOutputStream != null && onlyWhenSquelchIsSatisfied
            val demodFollowsSquelch = isDemodulationActivated && demodulationFollowsSquelch
            if (squelchOpen && !squelchWasOpen) deliverPreroll(recordingFollowsSquelch, demodFollowsSquelch)
            squelchWasOpen = squelchOpen
            if (!squelchOpen && (recordingFollowsSquelch || demodFollowsSquelch)) keepForPreroll(packet, validByteCount)
            else clearPreroll()

            ///// Recording ////////////////////////////////////////////////////////////////////////
            if (bufferedOutputStream != null) {
                if (squelchOpen || !onlyWhenSquelchIsSatisfied) writeToRecording(packet, validByteCount)
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
            if (!isDemodulationActivated) clearDemodBacklog() else drainDemodBacklog()
            if (isDemodulationActivated && (squelchOpen || !demodulationFollowsSquelch) && demodBacklog.isNotEmpty()) {
                addToDemodBacklog(packet.copyOf(validByteCount))
            } else if (isDemodulationActivated && (squelchOpen || !demodulationFollowsSquelch)) {
                // Get a buffer from the demodulator inputQueue
                val demodBuffer = demodInputQueue.poll()
                if (demodBuffer != null) {
                    deliverToDemodulator(packet, demodBuffer)
                } else {
                    Log.d(LOGTAG, "run: Flush the demod queue because demodulator is too slow!")
                    var flushed = 0L
                    var flushedNs = nsPerPacket.toLong() // this packet doesn't reach the demodulator either
                    generateSequence { demodOutputQueue.poll() }
                        .forEach {
                            if (it.sampleRate > 0) flushedNs += it.size() * 1_000_000_000L / it.sampleRate
                            demodInputQueue.offer(it); flushed++
                        }
                    GlobalPerformanceData.incrementCounter("scheduler.demodQueueDrops", flushed)
                    OverloadMonitor.reportLoss("Scheduler", flushedNs)
                }
            }

            ///// FFT //////////////////////////////////////////////////////////////////////////////
            source.fillPacketIntoInterleavedBuffer(packet, interleavedFftBuffer)
            putNewFftSamples(interleavedFftBuffer, validByteCount / source.bytesPerSample * 2)

            // Return the packet back to the source buffer pool:
            source.returnPacket(packet)

            // Performance Tracking:
            val processingTime = System.nanoTime() - startTimestamp
            val load = processingTime / nsPerPacket
            GlobalPerformanceData.updateLoad("Scheduler", load)
            OverloadMonitor.reportBusy("Scheduler", processingTime, nsPerPacket.toLong())
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
