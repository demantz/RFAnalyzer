package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.dsp.AmDemodulator
import com.mantz_it.rfanalyzer.dsp.CwDemodulator
import com.mantz_it.rfanalyzer.dsp.FmDemodulator
import com.mantz_it.rfanalyzer.dsp.SsbDemodulator
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import java.io.BufferedOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue

/**
 * <h1>RF Analyzer - Demodulator</h1>
 *
 * Module:      Demodulator.kt
 * Description: This class implements demodulation of the analog radio modes (AM, FM, SSB, CW) and
 *              hands the digital modes' samples to the digital extension. It runs as a separate
 *              thread. It reads baseband samples from a queue, resamples them to the mode's rate,
 *              demodulates them and forwards the audio to an AudioSink thread.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class Demodulator(
    inputQueue: ArrayBlockingQueue<SamplePacket>,   // Queue that delivers received baseband signals
    inputReturnQueue: ArrayBlockingQueue<SamplePacket>,  // Queue to return used buffers from the inputQueue
    val packetSize: Int,                                 // Size of the packets in the input queue
    val pushDigitalExtensionInputSamples: (SamplePacket) -> Boolean,
    val pollDigitalExtensionOutputSamples: (SamplePacket) -> Boolean,
    lowPowerformanceMode: Boolean = false           // Flag to enable low performance mode
) : Thread() {

    companion object {
        private const val LOGTAG = "Demodulator"
        const val AUDIO_RATE = 48000
    }

    private var stopRequested = true

    var filterQuality: Float = 1f
        set(value) {
            val coercedVal = value.coerceIn(0.01f, 1f)
            field = coercedVal
            resampler.maxTaps = (filterQuality*500).toInt()
        }

    var channelWidth: Int = 0
        set(value) { field = value.coerceIn(demodulationMode.minChannelWidth, demodulationMode.maxChannelWidth) }

    // Internal sample buffers: packetSize, the most a resampled packet holds.
    private val quadratureSamples = SamplePacket(packetSize)
    // Demodulated into when the audio sink has no free buffer: the packet is still demodulated
    // (filter state stays continuous, a digital mode's extension gets every sample), only its
    // audio is not played.
    private val droppedAudio = SamplePacket(packetSize)

    // DEMODULATION - one lazily-initialized instance per mode, each owning its own fixed-sample-rate
    // filter state. Lazy construction and all property mutation below happen exclusively inside
    // run(), on this thread, so LazyThreadSafetyMode.NONE is safe.
    private val amDemodulator by lazy(LazyThreadSafetyMode.NONE) { AmDemodulator(DemodulationMode.AM.basebandSamplerate, packetSize) }
    private val nfmDemodulator by lazy(LazyThreadSafetyMode.NONE) { FmDemodulator(DemodulationMode.NFM.basebandSamplerate, packetSize, maxDeviationRatio = 0.75f, postDemodAudioCutoffHz = 3000f) }
    private val wfmDemodulator by lazy(LazyThreadSafetyMode.NONE) { FmDemodulator(DemodulationMode.WFM.basebandSamplerate, packetSize, maxDeviationRatio = 0.85f, postDemodAudioCutoffHz = 15000f) }
    private val lsbDemodulator by lazy(LazyThreadSafetyMode.NONE) { SsbDemodulator(DemodulationMode.LSB.basebandSamplerate, packetSize, upperBand = false) }
    private val usbDemodulator by lazy(LazyThreadSafetyMode.NONE) { SsbDemodulator(DemodulationMode.USB.basebandSamplerate, packetSize, upperBand = true) }
    private val cwDemodulator by lazy(LazyThreadSafetyMode.NONE) { CwDemodulator(DemodulationMode.CW.basebandSamplerate, packetSize) }

    var demodulationMode: DemodulationMode = DemodulationMode.OFF
        /**
         * Sets a new demodulation mode, also while the demodulator is running: sets the
         * resampler's output rate, the mode's default channel width and stereo or mono audio.
         */
        set(value) {
            resampler.outputSampleRate = value.basebandSamplerate
            field = value
            this.channelWidth = value.defaultChannelWidth
            audioSink.setStereo(value == DemodulationMode.WFM && wfmStereoEnabled)
        }

    // If true, WFM uses the stereo+RDS demodulator in the digital extension; if false, the local mono wfmDemodulator is used instead.
    var wfmStereoEnabled: Boolean = false
        set(value) {
            field = value
            audioSink.setStereo(value && demodulationMode == DemodulationMode.WFM)
        }

    // RESAMPLING (input sample rate --> the mode's baseband rate)
    private val resampler = Resampler(demodulationMode.basebandSamplerate, packetSize, inputQueue, inputReturnQueue, (filterQuality*500).toInt())

    // AUDIO OUTPUT
    private var audioSink: AudioSink = AudioSink(packetSize, AUDIO_RATE, lowPowerformanceMode) // the demodulated audio's rate --> AUDIO_RATE, and the audio output
    var audioVolumeLevel = 1f        // Audio Volume (0 is mute and 1 is full volume)

    // BASEBAND RECORDING
    // Records the samples exactly as they come out of the resampler, i.e. before channel
    // filtering (interleaved little-endian float32 I/Q, no header).
    private var bufferedOutputStream: BufferedOutputStream? = null
    private var recordedFileSize: Long = 0
    private var recordedStartTimestamp: Long = 0
    private var maxRecordingTime: Long? = null
    private var maxRecordingFileSize: Long? = null
    private var onRecordingStopped: ((finalSize: Long) -> Unit)? = null
    private var onFileSizeUpdate: ((currentFileSize: Long) -> Unit)? = null
    private var onFirstSampleRecorded: ((timestamp: Long) -> Unit)? = null
    private var stopRecordingRequested = false
    private var recordingCounter: Long = 0
    private val recordingByteBuffer = ByteBuffer.allocate(4 * 1000).order(ByteOrder.LITTLE_ENDIAN)

    /** [timestamp]: UTC time (ms) of re[0]/im[0]. */
    @Throws(IOException::class)
    private fun writeSamplesToRecording(re: FloatArray, im: FloatArray, n: Int, timestamp: Long) {
        if (recordedFileSize == 0L && n > 0) onFirstSampleRecorded?.let { it(timestamp) }
        for (idx in 0..<n) {
            recordingByteBuffer.putFloat(re[idx])
            recordingByteBuffer.putFloat(im[idx])
            if (!recordingByteBuffer.hasRemaining()) {
                recordingByteBuffer.flip()
                bufferedOutputStream!!.write(recordingByteBuffer.array(), 0, recordingByteBuffer.limit())
                recordingByteBuffer.clear()
            }
        }
        recordedFileSize += n.toLong() * 8
    }

    /**
     * Starts the thread. This thread will start 2 more threads for resampling and audio output.
     * These threads are managed by the Demodulator and terminated, when the Demodulator thread
     * terminates.
     */
    @Synchronized
    override fun start() {
        stopRequested = false
        super.start()
    }

    /**
     * Stops the thread
     */
    fun stopDemodulator() {
        stopRequested = true
        // Wakes the loop immediately out of its bounded resampler/audioSink polls instead of
        // waiting out their timeouts - both already catch InterruptedException and return null.
        interrupt()
    }

    /**
     * Will stop writing baseband samples to the bufferedOutputStream and close it.
     */
    fun stopBasebandRecording() {
        this.stopRecordingRequested = true
        Log.i(LOGTAG, "stopBasebandRecording")
    }

    fun startBasebandRecording(bufferedOutputStream: BufferedOutputStream,
                                maxRecordingTime: Long? = null,                          // Maximum time to record (in milliseconds). null -> never stop
                                maxRecordingFileSize: Long? = null,                      // Maximum file size for the recording (in bytes). null -> never stop
                                onRecordingStopped: (finalSize: Long) -> Unit,           // callback when recording stops (with final file size in bytes)
                                onFileSizeUpdate: (currentFileSize: Long) -> Unit,       // periodical callback during recording to report file size (in bytes) to ui
                                onFirstSampleRecorded: (timestamp: Long) -> Unit) {      // callback with the UTC time (ms) of the first recorded sample
        stopRecordingRequested = false
        recordedFileSize = 0
        recordedStartTimestamp = System.currentTimeMillis()
        recordingByteBuffer.clear()
        this.bufferedOutputStream = bufferedOutputStream
        this.maxRecordingTime = maxRecordingTime
        this.maxRecordingFileSize = maxRecordingFileSize
        this.onRecordingStopped = onRecordingStopped
        this.onFileSizeUpdate = onFileSizeUpdate
        this.onFirstSampleRecorded = onFirstSampleRecorded
        Log.i(LOGTAG, "startBasebandRecording: Baseband recording started.")
    }

    override fun run() {
        var inputSamples: SamplePacket?
        var audioBuffer: SamplePacket?

        this.name = "Thread-Demodulator-" + System.currentTimeMillis()
        Log.i(LOGTAG, "Demodulator started. (Thread: " + this.name + ")")

        // Start the audio sink thread:
        audioSink.start()

        // Start the resampler thread:
        resampler.start()

        while (!stopRequested) {
            // Get a resampled packet from the resampler:
            inputSamples = resampler.getResampledPacket(1000)
            val startTimestamp = System.nanoTime()

            // Verify the input sample packet is not null:
            if (inputSamples == null) {
                continue
            }

            ///// Baseband Recording //////////////////////////////////////////////////////////
            if (bufferedOutputStream != null) {
                try {
                    val re = inputSamples.re()
                    val im = inputSamples.im()
                    val n = inputSamples.size()

                    // While the squelch gates the samples (analog modes), the Scheduler hands on
                    // only what passes it - including the burst's start from before it opened.
                    writeSamplesToRecording(re, im, n, inputSamples.timestamp)
                } catch (e: IOException) {
                    Log.e(LOGTAG, "run: Error while writing to output stream (baseband recording): " + e.message)
                    stopBasebandRecording()
                }
                // report file size every 100 packets:
                if (recordingCounter % 100 == 0L) onFileSizeUpdate?.let { it(recordedFileSize) }
                // check if recording should stop:
                maxRecordingTime?.let { if (it <= (System.currentTimeMillis() - recordedStartTimestamp)) {
                        Log.i(LOGTAG, "run: Max Baseband Recording Time reached!")
                        stopBasebandRecording()
                    }
                }
                maxRecordingFileSize?.let { if (it <= recordedFileSize) {
                        Log.i(LOGTAG, "run: Max Baseband Recording File Size reached!")
                        stopBasebandRecording()
                    }
                }
                if (stopRecordingRequested) {
                    try {
                        recordingByteBuffer.flip()
                        if (recordingByteBuffer.limit() > 0) bufferedOutputStream!!.write(recordingByteBuffer.array(), 0, recordingByteBuffer.limit())
                        recordingByteBuffer.clear()
                        bufferedOutputStream!!.close()
                    } catch (e: IOException) {
                        Log.e(LOGTAG, "run: Error while closing output stream (baseband recording): " + e.message)
                    }
                    bufferedOutputStream = null
                    Log.i(LOGTAG, "run: Baseband recording stopped.")
                    onRecordingStopped?.let { it(recordedFileSize) }
                }
                recordingCounter++
            }

            // Channel filtering is each mode's demodulator's job: just copy the resampled I/Q
            // into quadratureSamples.
            val inRe = inputSamples.re()
            val inIm = inputSamples.im()
            val outRe = quadratureSamples.re()
            val outIm = quadratureSamples.im()
            for(i in 0..<inputSamples.size()) {
                outRe[i] = inRe[i]
                outIm[i] = inIm[i]
            }
            quadratureSamples.setSize(inputSamples.size())
            quadratureSamples.frequency = inputSamples.frequency
            quadratureSamples.sampleRate = inputSamples.sampleRate
            quadratureSamples.timestamp = inputSamples.timestamp

            // Drop packets still tagged with a previous mode's sample rate: the Resampler
            // reconfigures itself asynchronously on its own thread when demodulationMode changes,
            // so a packet dequeued right after a mode switch can briefly carry a stale rate. Each
            // demod class below is built for one fixed rate, so feeding it a wrong-rate packet
            // would silently mis-filter.
            if (quadratureSamples.sampleRate != demodulationMode.basebandSamplerate) {
                Log.d(LOGTAG, "run: dropping packet tagged with stale sampleRate=${quadratureSamples.sampleRate}, expected ${demodulationMode.basebandSamplerate} for mode $demodulationMode")
                resampler.returnResampledPacket(inputSamples)
                continue
            }

            // return input samples to the resampler:
            resampler.returnResampledPacket(inputSamples)

            val time1 = System.nanoTime() - startTimestamp
            val nsPerPacket = inputSamples.size() * 1_000_000_000f / inputSamples.sampleRate

            // Get a buffer from the audio sink without waiting: the chain is clocked by the source,
            // not by audio playback. With none free (a network source's burst, or the source's
            // clock running a little ahead of the audio clock) this packet's audio is dropped -
            // waiting here would back up the whole chain until the Scheduler flushes its queue.
            val playable = audioSink.getPacketBuffer(0)
            if (playable == null) GlobalPerformanceData.incrementCounter("demodulator.audioDropped")
            audioBuffer = playable ?: droppedAudio

            val startTimestamp2 = System.nanoTime()

            audioBuffer.setSize(0) // mark buffer as empty

            // demodulate (sample rate is demodulationMode.basebandSamplerate)
            when (demodulationMode) {
                DemodulationMode.OFF -> { }
                DemodulationMode.AM  -> {
                    amDemodulator.channelWidth = channelWidth
                    amDemodulator.demodulate(quadratureSamples, audioBuffer)
                }
                DemodulationMode.NFM -> {
                    nfmDemodulator.channelWidth = channelWidth
                    nfmDemodulator.demodulate(quadratureSamples, audioBuffer)
                }
                DemodulationMode.WFM -> {
                    if (wfmStereoEnabled) {
                        pushDigitalExtensionInputSamples(quadratureSamples)
                        val pollSuccess = pollDigitalExtensionOutputSamples(audioBuffer)
                        if (!pollSuccess) GlobalPerformanceData.incrementCounter("demodulator.ipcPollMiss")
                    } else {
                        wfmDemodulator.channelWidth = channelWidth
                        wfmDemodulator.demodulate(quadratureSamples, audioBuffer)
                    }
                }
                DemodulationMode.LSB -> {
                    lsbDemodulator.channelWidth = channelWidth
                    lsbDemodulator.demodulate(quadratureSamples, audioBuffer)
                }
                DemodulationMode.USB -> {
                    usbDemodulator.channelWidth = channelWidth
                    usbDemodulator.demodulate(quadratureSamples, audioBuffer)
                }
                DemodulationMode.CW  -> {
                    cwDemodulator.channelWidth = channelWidth
                    cwDemodulator.demodulate(quadratureSamples, audioBuffer)
                }
                DemodulationMode.RTTY, DemodulationMode.PSK31, DemodulationMode.R09, DemodulationMode.FT8, DemodulationMode.AX25, DemodulationMode.ADSB, DemodulationMode.POCSAG, DemodulationMode.LORA -> {
                    pushDigitalExtensionInputSamples(quadratureSamples)
                    val pollSuccess = pollDigitalExtensionOutputSamples(audioBuffer)
                    if (!pollSuccess) GlobalPerformanceData.incrementCounter("demodulator.ipcPollMiss")
                }
            }

            // apply audio volume:
            val audioBufferRe = audioBuffer.re()
            val audioBufferIm = audioBuffer.im()
            for (i in 0..<audioBuffer.size()) {
                audioBufferRe[i] = audioBufferRe[i] * audioVolumeLevel
                audioBufferIm[i] = audioBufferIm[i] * audioVolumeLevel
            }

            // Performance Tracking
            val processingTime = System.nanoTime() - startTimestamp2 + time1
            GlobalPerformanceData.updateLoad("Demodulator", processingTime / nsPerPacket)
            OverloadMonitor.reportBusy("Demodulator", processingTime, nsPerPacket.toLong())

            // play audio (the AudioSink resamples it to AUDIO_RATE)
            if (playable != null) audioSink.enqueuePacket(playable)
        }

        // Stop the audio sink thread:
        audioSink.stopSink()

        // Stop the resampler thread:
        resampler.stopResampler()

        this.stopRequested = true
        if (bufferedOutputStream != null) {
            try {
                recordingByteBuffer.flip()
                if (recordingByteBuffer.limit() > 0) bufferedOutputStream!!.write(recordingByteBuffer.array(), 0, recordingByteBuffer.limit())
                recordingByteBuffer.clear()
                bufferedOutputStream!!.close()
            } catch (e: IOException) {
                Log.e(LOGTAG, "run: Error while closing output stream (cleanup)(baseband recording): " + e.message)
            }
            bufferedOutputStream = null
            Log.i(LOGTAG, "run: Baseband recording stopped (Demodulator shutting down).")
            onRecordingStopped?.let { it(recordedFileSize) }
        }
        Log.i(LOGTAG, "Demodulator stopped. (Thread: " + this.name + ")")
    }

}
