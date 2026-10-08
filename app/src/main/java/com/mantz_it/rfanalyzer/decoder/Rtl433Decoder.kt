package com.mantz_it.rfanalyzer.decoder

import android.util.Log
import com.mantz_it.librtl433.Rtl433Native
import com.mantz_it.rfanalyzer.database.AppStateRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * <h1>RF Analyzer - rtl_433 Decoder</h1>
 *
 * Owns the native rtl_433 decoder and a small worker thread. The Scheduler
 * hands over the interleaved IQ buffer it already builds for the FFT; this
 * class copies it into a bounded pool, converts/decodes off the SDR threads and
 * publishes decoded events to [AppStateRepository] (and optionally to a JSONL
 * log file).
 *
 * Decoding is best-effort: if the worker cannot keep up, whole packets are
 * dropped rather than stalling the SDR pipeline.
 *
 * Copyright (C) 2026 RF Analyzer contributors
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class Rtl433Decoder(
    private val appStateRepository: AppStateRepository,
    private val filesDir: File
) {
    companion object {
        private const val TAG = "Rtl433Decoder"
        private const val QUEUE_CAPACITY = 4
        private const val MAX_RECENT_EVENTS = 200
        private const val DECODED_DIRECTORY = "decoded"
    }

    private var native: Rtl433Native? = null
    private var worker: Thread? = null
    private var bufferSize = 0

    private val freeBuffers = ArrayBlockingQueue<FloatArray>(QUEUE_CAPACITY)
    private val pendingBuffers = ArrayBlockingQueue<FloatArray>(QUEUE_CAPACITY)

    // All native calls are executed on the worker thread. Callers enqueue
    // commands from other threads so configuration never races with feeding.
    private val pendingCommands = ConcurrentLinkedQueue<() -> Unit>()

    @Volatile
    private var running = false

    private var logWriter: BufferedWriter? = null
    private var logFile: File? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val timestampFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    private val recentEvents = ArrayDeque<DecodedEvent>()

    /**
     * Creates the native decoder and starts the worker thread.
     *
     * @param samplesPerPacket number of complex samples per IQ buffer handed to
     *        [onSamples] (the FFT buffer holds twice as many floats).
     */
    fun start(samplesPerPacket: Int): Boolean {
        if (running) return true
        if (samplesPerPacket <= 0) return false

        val decoder = try {
            Rtl433Native()
        } catch (t: Throwable) {
            Log.e(TAG, "start: native decoder unavailable: ${t.message}")
            return false
        }
        if (!decoder.create()) {
            Log.e(TAG, "start: failed to create native decoder")
            return false
        }

        native = decoder
        decoder.onDecoded = ::onDecodedJson
        bufferSize = samplesPerPacket * 2
        freeBuffers.clear()
        pendingBuffers.clear()
        pendingCommands.clear()
        repeat(QUEUE_CAPACITY) { freeBuffers.offer(FloatArray(bufferSize)) }

        if (appStateRepository.decoderLogToFile.value)
            openLogFile()

        running = true
        worker = Thread({ workerLoop() }, "Thread-Rtl433Decoder-${System.currentTimeMillis()}").apply { start() }
        appStateRepository.decoderRunning.set(true)
        applyOptions()
        applyFlexDecoders()
        Log.i(TAG, "start: decoder running (rtl_433 ${decoder.version()})")
        return true
    }

    /** Stops the worker, flushes and releases the native decoder. */
    fun stop() {
        if (!running && worker == null && native == null) return
        running = false
        worker?.interrupt()
        try {
            worker?.join(2000)
        } catch (e: InterruptedException) {
            Log.w(TAG, "stop: interrupted while joining worker")
        }
        val stillAlive = worker?.isAlive == true
        worker = null
        if (stillAlive) {
            // Never destroy the native decoder while the worker may still use
            // it; leaking is preferable to a use-after-free.
            Log.e(TAG, "stop: worker did not stop in time; not destroying native decoder")
            native = null
        } else {
            native?.destroy()
            native = null
        }
        pendingCommands.clear()
        closeLogFile()
        appStateRepository.decoderRunning.set(false)
        Log.i(TAG, "stop: decoder stopped")
    }

    /**
     * Applies the IQ stream parameters. [targetSampleRate] is the rate the
     * decoder should run at; the bridge decimates by an integer factor.
     */
    fun configure(sampleRate: Int, centerFrequency: Long, targetSampleRate: Int) {
        if (!running || sampleRate <= 0) return
        val decimation = (sampleRate / targetSampleRate.coerceAtLeast(1)).coerceAtLeast(1)
        pendingCommands.add {
            native?.configure(sampleRate, centerFrequency, decimation)
            Log.i(TAG, "configure: rate=$sampleRate center=$centerFrequency decimation=$decimation")
        }
    }

    /** Updates the frequency reported in decoded events (SDR retune). */
    fun setCenterFrequency(centerFrequency: Long) {
        if (!running) return
        pendingCommands.add { native?.setCenterFrequency(centerFrequency) }
    }

    /**
     * Enables/disables channel filtering. [offsetHz] is the channel offset from
     * the SDR center; [bandwidthHz] the channel width (0 disables).
     */
    fun setChannel(offsetHz: Int, bandwidthHz: Int) {
        if (!running) return
        pendingCommands.add { native?.setChannel(offsetHz, bandwidthHz) }
    }

    /** Applies units, sensitivity and metadata options from the current settings. */
    fun applyOptions() {
        if (!running) return
        val conversion = appStateRepository.decoderConversionMode.value.bridgeValue
        val autoLevel = appStateRepository.decoderAutoLevel.value
        val reportMeta = appStateRepository.decoderReportMeta.value
        val minSnr = appStateRepository.decoderMinSnr.value
        pendingCommands.add { native?.setOptions(conversion, autoLevel, reportMeta, minSnr) }
    }

    /**
     * Registers the user-defined flex decoders (for unrecognised protocols) with
     * the native decoder. Flex devices cannot be unregistered individually, so
     * changing the list requires recreating the decoder (see AnalyzerService).
     */
    private fun applyFlexDecoders() {
        if (!running) return
        val specs = appStateRepository.decoderFlexDecoders.value
        if (specs.isEmpty()) return
        pendingCommands.add {
            specs.forEachIndexed { index, spec ->
                val ok = native?.addFlex(spec) ?: false
                if (!ok)
                    Log.w(TAG, "applyFlexDecoders: flex decoder[$index] rejected: '$spec'")
                else
                    Log.i(TAG, "applyFlexDecoders: registered flex decoder[$index]: '$spec'")
            }
        }
    }

    /** Opens or closes the JSONL log file while decoding is running. */
    fun setLogToFile(enabled: Boolean) {
        if (!running) return
        pendingCommands.add {
            if (enabled) {
                if (logWriter == null) openLogFile()
            } else if (logWriter != null || logFile != null) {
                closeLogFile()
                appStateRepository.decoderLogFilePath.set("")
            }
        }
    }

    /**
     * Called from the Scheduler thread with the shared interleaved IQ buffer.
     * The buffer is copied because the Scheduler reuses it immediately.
     */
    fun onSamples(samples: FloatArray) {
        if (!running) return
        if (samples.size != bufferSize) return
        val buffer = freeBuffers.poll() ?: return // decoder is behind: drop the packet
        System.arraycopy(samples, 0, buffer, 0, bufferSize)
        if (!pendingBuffers.offer(buffer)) {
            freeBuffers.offer(buffer)
        }
    }

    private fun workerLoop() {
        val decoder = native ?: return
        while (running) {
            drainCommands()
            val buffer = try {
                pendingBuffers.poll(200, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                null
            } ?: continue
            try {
                decoder.feed(buffer)
            } catch (t: Throwable) {
                Log.e(TAG, "workerLoop: feed failed: ${t.message}")
            }
            freeBuffers.offer(buffer)
        }
        drainCommands()
        try {
            decoder.flush()
        } catch (t: Throwable) {
            Log.e(TAG, "workerLoop: flush failed: ${t.message}")
        }
    }

    private fun drainCommands() {
        while (true) {
            val command = pendingCommands.poll() ?: return
            try {
                command()
            } catch (t: Throwable) {
                Log.e(TAG, "drainCommands: command failed: ${t.message}")
            }
        }
    }

    private fun onDecodedJson(jsonLine: String) {
        val event = parseEvent(jsonLine)
        recentEvents.addLast(event)
        while (recentEvents.size > MAX_RECENT_EVENTS) recentEvents.removeFirst()

        appStateRepository.decodedEvents.set(recentEvents.toList())
        appStateRepository.decoderEventCount.set(appStateRepository.decoderEventCount.value + 1)
        appStateRepository.decoderLastModel.set(event.model)
        appStateRepository.decoderLastEventTimestamp.set(event.timestampMs)

        writeLogLine(jsonLine)
    }

    private fun parseEvent(jsonLine: String): DecodedEvent {
        val fields = mutableListOf<Pair<String, String>>()
        var model = ""
        try {
            val obj = json.parseToJsonElement(jsonLine) as? JsonObject
            obj?.forEach { (key, value) ->
                val text = (value as? JsonPrimitive)?.content ?: value.toString()
                fields.add(key to text)
                if (key == "model") model = text
            }
        } catch (t: Throwable) {
            Log.w(TAG, "parseEvent: could not parse JSON: ${t.message}")
        }
        return DecodedEvent(
            timestampMs = System.currentTimeMillis(),
            model = model.ifEmpty { "Unknown" },
            fields = fields,
            json = jsonLine
        )
    }

    private fun openLogFile() {
        try {
            val dir = File(filesDir, DECODED_DIRECTORY)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "decoded_${timestampFormat.format(Date())}.jsonl")
            logFile = file
            logWriter = BufferedWriter(FileWriter(file, true))
            appStateRepository.decoderLogFilePath.set(file.absolutePath)
            Log.i(TAG, "openLogFile: writing to ${file.absolutePath}")
        } catch (t: Throwable) {
            Log.e(TAG, "openLogFile: failed: ${t.message}")
            logFile = null
            logWriter = null
            appStateRepository.decoderLogFilePath.set("")
        }
    }

    private fun writeLogLine(jsonLine: String) {
        val writer = logWriter ?: return
        try {
            writer.write(jsonLine)
            writer.newLine()
            writer.flush()
        } catch (t: Throwable) {
            Log.e(TAG, "writeLogLine: failed: ${t.message}")
        }
    }

    private fun closeLogFile() {
        try {
            logWriter?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "closeLogFile: ${t.message}")
        }
        logWriter = null
        logFile = null
    }
}
