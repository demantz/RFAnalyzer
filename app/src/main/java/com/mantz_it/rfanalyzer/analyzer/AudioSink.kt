package com.mantz_it.rfanalyzer.analyzer

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.mantz_it.rfanalyzer.dsp.RationalResampler
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * <h1>RF Analyzer - Audio Sink</h1>
 *
 * Module:      AudioSink.kt
 * Description: This class implements the interface to the systems audio API.
 *              It will run in a separate thread and buffer incoming sample packets
 *              in a blocking queue. Input packets are demodulated (real, or L/R-as-re/im
 *              for stereo) signals at whatever rate the producer tagged them with -
 *              a RationalResampler converts any incoming rate to the audio rate.
 *
 *              A network source delivers its samples in bursts - rtl_tcp sends 256 KiB at a
 *              time (half a second at 250 kSps), a KiwiSDR's frames come with the internet's
 *              jitter - and the chain turns a burst into many small audio packets within a few
 *              milliseconds. So the queue holds many packets when they are small (a USB SDR's
 *              large packets need few), and the AudioTrack keeps a cushion of audio that lasts
 *              until the next burst: it starts at the system's minimum buffer, and every
 *              underrun refills it with silence and doubles it (up to MAX_CUSHION_MS). A USB
 *              SDR, which delivers evenly, keeps the low latency.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class AudioSink(
    private val packetSize: Int,
    private val sampleRate: Int,
    private val lowPerformanceMode: Boolean
) : Thread() {
    companion object {
        private const val LOGTAG = "AudioSink"
        /** Samples the queue's packets hold together: few large packets or many small ones. */
        private const val QUEUE_SAMPLES = 262_144
        private const val MIN_QUEUE_SIZE = 2
        private const val MAX_QUEUE_SIZE = 64
        /** The most audio kept ahead of playback - the latency a very bursty source gets. */
        private const val MAX_CUSHION_MS = 1000
        /** Underruns while the chain warms up after the start (JIT, first allocations) don't count. */
        private const val WARM_UP_MS = 2000L
    }

    private var audioTrack: AudioTrack? = null
    @Volatile
    private var stopRequested = true
    private var stereo = false
    private var currentlySetToStereo = false
    private val inputQueue: ArrayBlockingQueue<SamplePacket>
    private val outputQueue: ArrayBlockingQueue<SamplePacket>
    // The audio kept ahead of playback after an underrun, in frames. Starts as the track's buffer;
    // once grown, the buffer is twice the cushion, so a burst's audio fits on top.
    private val initialCushionFrames: Int
    private var cushionFrames: Int
    private var underrunCount = 0     // the track's underruns already answered
    private var framesWritten = 0L    // to the current track
    private var playingSinceMs = 0L

    // Converts whatever rate an incoming packet is tagged with to `sampleRate`. Rebuilt (see
    // ensureResampler()) whenever the incoming rate changes - e.g. switching between digital
    // modes whose audio taps sit at different native rates. Null when the incoming rate already
    // equals `sampleRate` (no conversion needed).
    private var resampler: RationalResampler? = null
    private var resamplerInputRate = -1

    init {
        val audioFormat = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val minBufferSize = AudioTrack.getMinBufferSize(sampleRate, audioFormat, AudioFormat.ENCODING_PCM_FLOAT)
        var queueSize = (QUEUE_SAMPLES / packetSize.coerceAtLeast(1)).coerceIn(MIN_QUEUE_SIZE, MAX_QUEUE_SIZE)
        val minBufferFrames = minBufferSize / 4 // mono float
        initialCushionFrames = if (lowPerformanceMode) {
            queueSize = maxOf(queueSize, 10)
            minBufferFrames * 10
        } else {
            minBufferFrames
        }
        cushionFrames = initialCushionFrames

        inputQueue = ArrayBlockingQueue(queueSize)
        outputQueue = ArrayBlockingQueue(queueSize)
        repeat(queueSize) { outputQueue.offer(SamplePacket(packetSize)) }

        createAudioTrack()
    }

    fun setStereo(stereo: Boolean) {
        this.stereo = stereo
    }

    private fun createAudioTrack() {
        Log.d(LOGTAG, "createAudioTrack: Creating new AudioTrack (stereo=$stereo)...")
        val channelMask = if (stereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(sampleRate)
            .setChannelMask(channelMask)
            .build()

        // Room for the largest cushion plus a burst; the buffer in use is set from the cushion.
        val channels = if (stereo) 2 else 1
        val capacityFrames = maxOf(2 * sampleRate * MAX_CUSHION_MS / 1000, 2 * cushionFrames)
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(audioAttributes)
            .setAudioFormat(format)
            .setBufferSizeInBytes(capacityFrames * channels * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
            .apply { setBufferSizeInFrames(bufferFramesFor(cushionFrames)) }
        underrunCount = 0
        framesWritten = 0
        currentlySetToStereo = stereo
    }

    private fun bufferFramesFor(cushion: Int) = if (cushion > initialCushionFrames) 2 * cushion else cushion

    /**
     * The track ran dry: the audio arrives more unevenly than the cushion evens out. The cushion
     * doubles and is refilled with silence at once - one gap instead of one at every burst. It
     * only grows: the sink is new with every start of the analyzer.
     */
    private fun onUnderrun() {
        val track = audioTrack ?: return
        val count = track.underrunCount
        if (count <= underrunCount) return
        underrunCount = count
        if (System.currentTimeMillis() - playingSinceMs < WARM_UP_MS) return
        val maxCushionFrames = sampleRate * MAX_CUSHION_MS / 1000
        if (cushionFrames < maxCushionFrames) {
            cushionFrames = minOf(2 * cushionFrames, maxCushionFrames)
            track.setBufferSizeInFrames(bufferFramesFor(cushionFrames))
            Log.i(LOGTAG, "onUnderrun: underrun #$count - audio cushion now ${cushionFrames * 1000L / sampleRate} ms")
        }
        val played = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL // an unsigned 32-bit count
        val queued = (framesWritten - played) and 0xFFFFFFFFL
        val channels = if (currentlySetToStereo) 2 else 1
        var silence = (cushionFrames - queued) * channels
        val zeros = FloatArray(minOf(silence, 4096L).toInt().coerceAtLeast(0))
        while (silence > 0 && !stopRequested) {
            val written = track.write(zeros, 0, minOf(silence, zeros.size.toLong()).toInt(), AudioTrack.WRITE_BLOCKING)
            if (written <= 0) break
            silence -= written
            framesWritten += written / channels
        }
    }

    /**
     * Starts the thread
     */
    @Synchronized
    override fun start() {
        stopRequested = false
        super.start()
    }

    /**
     * Stops the thread
     */
    fun stopSink() {
        stopRequested = true
    }

    /**
     * @return size of the packets that are offered by getPacketBuffer()
     */
    fun getPacketSize(): Int = packetSize

    /**
     * The AudioSink allocates the buffers for audio playback. Use this method to request
     * a free buffer. This method will block up to [timeout] ms if no buffer is available
     * (0: return at once).
     *
     * @param timeout max time this method will block
     * @return free buffer or null if no buffer available
     */
    fun getPacketBuffer(timeout: Int): SamplePacket? {
        return try {
            if (timeout <= 0) outputQueue.poll() else outputQueue.poll(timeout.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Log.e(LOGTAG, "getPacketBuffer: Interrupted. return null...")
            null
        }
    }

    /**
     * Enqueues a packet buffer for being played on the audio track.
     *
     * @param packet the packet buffer from getPacketBuffer() filled with samples
     * @return true if success, false if error
     */
    fun enqueuePacket(packet: SamplePacket?): Boolean {
        if (packet == null) {
            Log.e(LOGTAG, "enqueuePacket: Packet is null.")
            return false
        }
        if (!inputQueue.offer(packet)) {
            Log.e(LOGTAG, "enqueuePacket: Queue is full.")
            return false
        }
        return true
    }

    /**
     * Rebuilds `resampler` for `inputRate` if it differs from what's currently configured.
     * `inputRate <= 0` is treated as "nothing to resample" (e.g. a packet whose producer never
     * tagged a rate) rather than attempting to build a resampler, which would otherwise blow up
     * `RationalResampler`'s "decimation must be > 0" precondition.
     */
    private fun ensureResampler(inputRate: Int) {
        if (inputRate <= 0 || inputRate == sampleRate) {
            if (resampler != null) {
                resampler?.close()
                resampler = null
                resamplerInputRate = inputRate
            }
            return
        }
        if (resampler != null && resamplerInputRate == inputRate) return
        resampler?.close()
        val (interpolation, decimation) = RationalResampler.limitDenominator(sampleRate, inputRate)
        resampler = RationalResampler(interpolation, decimation)
        resamplerInputRate = inputRate
    }

    override fun run() {
        // resample()'s output, written to the AudioTrack after every call - so packetSize is
        // enough also for an upsampled packet.
        val resampleScratch = SamplePacket(packetSize)
        val interleavedStereoOutputPacket = SamplePacket(packetSize * 2)

        this.name = "Thread-AudioSink-" + System.currentTimeMillis()
        Log.i(LOGTAG, "AudioSink started. (Thread: ${this.name})")

        if (lowPerformanceMode) {
            Log.i(LOGTAG, "AudioSink is in low performance mode. Wait for queue to fill up.")
            while (inputQueue.size < inputQueue.remainingCapacity() && !stopRequested) {
                try {
                    sleep(100)
                } catch (ignored: InterruptedException) {
                }
            }
        }

        // start audio playback:
        audioTrack!!.play()
        playingSinceMs = System.currentTimeMillis()

        // Continuously write the data from the queue to the audio track:
        while (!stopRequested) {
            if (currentlySetToStereo != stereo) {
                audioTrack!!.stop()
                audioTrack!!.release()
                createAudioTrack()
                audioTrack!!.play()
            }

            val packet: SamplePacket
            try {
                val polled = inputQueue.poll(1000, TimeUnit.MILLISECONDS)
                if (polled == null) {
                    if (lowPerformanceMode) {
                        Log.i(LOGTAG, "[low performance mode] AudioSink queue is empty. Wait for queue to fill up.")
                        while (inputQueue.size < inputQueue.remainingCapacity() && !stopRequested) {
                            try {
                                sleep(100)
                            } catch (ignored: InterruptedException) {
                            }
                        }
                    }
                    continue
                }
                packet = polled
            } catch (e: InterruptedException) {
                Log.e(LOGTAG, "run: Interrupted while polling from queue. stop")
                stopRequested = true
                break
            }

            // Skip empty packets (a digital mode without audio polls back an unfilled buffer):
            // their sampleRate may never have been set.
            if (packet.size() > 0) {
                ensureResampler(packet.sampleRate)

                val activeResampler = resampler
                if (activeResampler == null) {
                    writeChunk(packet, interleavedStereoOutputPacket)
                } else {
                    var offset = 0
                    while (offset < packet.size()) {
                        resampleScratch.setSize(0)
                        val consumed = activeResampler.resample(packet, resampleScratch, offset, packet.size() - offset)
                        offset += consumed
                        writeChunk(resampleScratch, interleavedStereoOutputPacket)
                        if (consumed == 0) break // safety: avoid spinning if no output could be produced
                    }
                }
            }

            // Return the buffer to the output queue
            outputQueue.offer(packet)
            onUnderrun()
        }

        resampler?.close()
        resampler = null

        // stop audio playback:
        audioTrack!!.stop()
        audioTrack!!.release()
        this.stopRequested = true
        Log.i(LOGTAG, "AudioSink stopped. (Thread: ${this.name})")
    }

    /**
     * Writes one chunk of audio (mono, or stereo with left=re()/right=im()) to the AudioTrack,
     * interleaving L/R first if in stereo mode.
     */
    private fun writeChunk(chunk: SamplePacket, interleaveScratch: SamplePacket) {
        if (currentlySetToStereo) {
            val outputRe = interleaveScratch.re()
            val leftChannel = chunk.re()
            val rightChannel = chunk.im()
            for (i in 0 until chunk.size()) {
                outputRe[i * 2] = leftChannel[i]
                outputRe[i * 2 + 1] = rightChannel[i]
            }
            interleaveScratch.setSize(chunk.size() * 2)
            writeToAudioTrack(interleaveScratch)
        } else {
            writeToAudioTrack(chunk)
        }
    }

    // Blocking: the thread sleeps in the AudioTrack until there is room. A non-blocking write
    // retried in a loop would keep a core busy whenever the track is full - most of the time, since
    // the chain delivers audio as fast as the track plays it. A blocking write returns early when
    // the track is stopped, paused or flushed.
    private fun writeToAudioTrack(packet: SamplePacket) {
        var samplesWritten = 0
        while (samplesWritten < packet.size() && !stopRequested) {
            val written = audioTrack!!.write(packet.re(), samplesWritten, packet.size() - samplesWritten, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.e(LOGTAG, "writeToAudioTrack: AudioTrack.write failed ($written)")
                return
            }
            if (written == 0) return // stopped or paused under us
            samplesWritten += written
            framesWritten += written / (if (currentlySetToStereo) 2 else 1)
        }
    }
}
