package com.mantz_it.rfanalyzer.source

import android.content.Context
import android.util.Log
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.analyzer.OverloadMonitor
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * <h1>RF Analyzer - KiwiSDR Source</h1>
 *
 * Module:      KiwiSdrSource.kt
 * Description: The IQ samples of a KiwiSDR receiver's WebSocket "mod=iq" channel
 *              (SourceType.KIWISDR).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class KiwiSdrSource(
    private val host: String,
    private val port: Int = DEFAULT_PORT,
    private var password: String = "",
    private var identUser: String = "RF Analyzer ${BuildConfig.VERSION_NAME}",
    private val useTls: Boolean = false
) : IQSourceInterface {

    companion object {
        private const val TAG = "KiwiSdrSource"
        const val DEFAULT_PORT = 8073
        const val MIN_FREQUENCY = 0L // Hz
        const val MAX_FREQUENCY = 30_000_000L // Hz; refined from server 'bandwidth' MSG param if present
        const val DEFAULT_SAMPLE_RATE = 12000 // Hz; overwritten by the server's 'sample_rate' MSG param
        private const val QUEUE_SIZE = 20
        private const val GPS_HEADER_SIZE = 10
        private const val SND_HEADER_SIZE = 7 // 1B flags + 4B seq + 2B smeter
        private const val KEEPALIVE_INTERVAL_MS = 1000L
        private const val CONNECT_TIMEOUT_MS = 8000L
        private const val HANDSHAKE_TIMEOUT_MS = 15_000L

        private const val SND_FLAG_STEREO = 0x08
        private const val SND_FLAG_COMPRESSED = 0x10

        private const val GPS_EPOCH_UNIX_MS = 315_964_800_000L // 1980-01-06
        private const val GPS_WEEK_MS = 7 * 86_400_000L
        /** GPS time is ahead of UTC by the leap seconds since 1980 (18 since 2017-01-01). */
        private const val GPS_UTC_LEAP_SECONDS = 18
        /** A GPS stamp further than this from the wall clock is not trusted (a receiver without GPS). */
        private const val MAX_GPS_WALL_CLOCK_DIFFERENCE_MS = 30_000L

        /**
         * UTC time (ms) of a frame's first sample from its GPS block, or null if it has none. The
         * block holds `gpssec`, the GPS time of week, and `gpsnsec` (both little-endian uint32 at
         * +2 and +6); the week is the one that puts the stamp closest to [wallClockMs].
         */
        fun gpsTimestampMs(frame: ByteArray, gpsOffset: Int, wallClockMs: Long): Long? {
            val seconds = readUInt32LE(frame, gpsOffset + 2)
            val nanos = readUInt32LE(frame, gpsOffset + 6)
            if (seconds == 0L && nanos == 0L) return null // the first frame(s) before a GPS time is known
            if (seconds * 1000 >= GPS_WEEK_MS || nanos >= 1_000_000_000L) return null
            val timeOfWeekMs = seconds * 1000 + nanos / 1_000_000
            val gpsNowMs = wallClockMs - GPS_EPOCH_UNIX_MS + GPS_UTC_LEAP_SECONDS * 1000L
            val weekStartMs = Math.floorDiv(gpsNowMs - timeOfWeekMs + GPS_WEEK_MS / 2, GPS_WEEK_MS) * GPS_WEEK_MS
            val utcMs = GPS_EPOCH_UNIX_MS + weekStartMs + timeOfWeekMs - GPS_UTC_LEAP_SECONDS * 1000L
            return if (abs(utcMs - wallClockMs) <= MAX_GPS_WALL_CLOCK_DIFFERENCE_MS) utcMs else null
        }

        private fun readUInt32LE(bytes: ByteArray, offset: Int): Long =
            (0 until 4).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 0xFF) shl (8 * i)) }

        /**
         * The server reports the exact rate of its IQ stream, e.g. 11998.9995 Hz (its ADC clock
         * divided by an integer), but sources have integer rates. The nominal rate (a multiple of
         * 250 Hz: 12000, or 20250 on wideband receivers) keeps the resampler at a simple ratio -
         * 1:1 for 12 kHz modes, where 11998 or 11999 would need a 6000/5999 or an approximated
         * ~10000-phase filter bank. The ~100 ppm the stream is mislabeled by is the order of any
         * SDR's crystal error.
         */
        fun nominalSampleRate(reportedRate: Double): Int {
            val nominal = (reportedRate / 250.0).roundToInt() * 250
            return if (abs(reportedRate - nominal) <= nominal * 0.001) nominal else reportedRate.roundToInt()
        }
    }

    /** IQ passband in Hz relative to frequency; KiwiSDR's own default for mod=iq. */
    var lowCut: Int = -5000
    var highCut: Int = 5000

    private var callback: IQSourceInterface.Callback? = null
    private var webSocket: WebSocket? = null
    private var httpClient: OkHttpClient? = null
    private var keepaliveThread: Thread? = null

    private var queue: ArrayBlockingQueue<ByteArray>? = null
    private var returnQueue: ArrayBlockingQueue<ByteArray>? = null
    /** UTC time of the first sample of each queued or taken packet that has a GPS stamp (by identity). */
    private val gpsTimestamps = ConcurrentHashMap<ByteArray, Long>()

    /** 0 until the first SND frame told its size. */
    @Volatile override var packetSize: Int = 0
        private set
    @Volatile private var isReady: Boolean = false
    @Volatile private var stopRequested: Boolean = false
    /** The WebSocket handshake completed (onOpen) - before that, close() can only cancel. */
    @Volatile private var connected: Boolean = false

    private val converter = Signed16BitIQConverter()

    private var streamSampleRate: Int = DEFAULT_SAMPLE_RATE
    private var tunedFrequency: Long = 0L
    /** The receiver's bandwidth, reported by the server. */
    override var maxFrequency: Long = MAX_FREQUENCY
        private set
    private var freqOffsetKHz: Double = 0.0

    // The password is only sent over wss, whatever is configured: never in cleartext, even if
    // the UI's disabled password field for plain connections is bypassed.
    private val effectivePassword: String = if (useTls) password else ""

    override fun open(context: Context, callback: IQSourceInterface.Callback): Boolean {
        if (webSocket != null) {
            Log.w(TAG, "open: already connecting/connected")
            return false
        }
        this.callback = callback
        stopRequested = false
        connected = false
        isReady = false
        packetSize = 0

        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS) // long-lived streaming connection
            .build()
        httpClient = client

        // No "/kiwi/" prefix - the resource path is just "/<timestamp>/SND". The timestamp
        // only needs to be a distinct per-connection integer, not a real time value.
        val wsTimestamp = System.currentTimeMillis() / 1000L
        val scheme = if (useTls) "wss" else "ws"
        val request = Request.Builder().url("$scheme://$host:$port/$wsTimestamp/SND").build()
        // Kept from here, not only from onOpen: a Stop while connecting must be able to cancel it
        webSocket = client.newWebSocket(request, KiwiWebSocketListener())
        return true
    }

    override fun isOpen(): Boolean = isReady

    override fun close(): Boolean {
        converter.close()
        stopRequested = true
        stopKeepalive()
        // Close promptly: a lingering connection costs another user a channel slot on
        // hardware that supports only 4-8 simultaneous listeners. Still connecting: cancel.
        webSocket?.let { if (connected) it.close(1000, "closing") else it.cancel() }
        webSocket = null
        httpClient?.dispatcher?.executorService?.shutdown()
        httpClient = null
        isReady = false
        return true
    }

    override val name: String get() = "KiwiSDR ($host:$port)"

    override var sampleRate: Int
        get() = streamSampleRate
        set(value) {
            // The server fixes the IQ rate (~12 kHz, ~20 kHz on wideband receivers) and reports it
            // in the 'sample_rate' MSG param: there is nothing to request. Only a rate set before
            // the stream starts is taken; afterwards the server's rate stays, and the app writes it
            // back (AnalyzerService) instead of processing the stream at a rate it doesn't have.
            if (isReady) return
            streamSampleRate = value
            converter.sampleRate = value
        }

    override var frequency: Long
        get() = tunedFrequency
        set(value) {
            tunedFrequency = value
            converter.frequency = value
            if (isReady) {
                sendSetMod()
            }
        }

    override val minFrequency: Long get() = MIN_FREQUENCY

    override val supportedSampleRates: IntArray get() = intArrayOf(streamSampleRate)

    override val bytesPerSample: Int get() = 4 // 2x int16 (I, Q)

    // A network stream that has no ping: see IQSourceInterface.maxPacketGapMs.
    override val maxPacketGapMs: Int get() = RtltcpSource.NETWORK_MAX_PACKET_GAP_MS

    override fun getPacket(timeout: Int): ByteArray? {
        return try {
            queue?.poll(timeout.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            null
        }
    }

    override fun returnPacket(buffer: ByteArray) {
        gpsTimestamps.remove(buffer)
        returnQueue?.offer(buffer)
    }

    override fun startSampling() {
        // Sampling begins as soon as the WebSocket handshake + mod=iq setup completes;
        // nothing additional to trigger here.
    }

    override fun stopSampling() {
        // No dedicated receiver thread to stop - OkHttp delivers frames via its own
        // callback thread. Frames arriving after stopRequested is set are simply dropped
        // (see handleBinaryMessage).
    }

    override fun fillPacketIntoInterleavedBuffer(packet: ByteArray, interleavedBuffer: FloatArray): Boolean {
        return converter.fillPacketIntoInterleavedBuffer(packet, interleavedBuffer)
    }

    override fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long): Int {
        return converter.mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency).also { restampFromGps(packet, samplePacket, it) }
    }

    /** The converter stamped the samples with the time they were processed; the GPS stamp says when they were received. */
    private fun restampFromGps(packet: ByteArray, samplePacket: SamplePacket, appended: Int) {
        if (appended > 0) gpsTimestamps[packet]?.let { IQConverter.stampFromSource(samplePacket, appended, it) }
    }

    private fun reportError(message: String) {
        callback?.onIQSourceError(this, message) ?: Log.e(TAG, "reportError: no callback ($message)")
    }

    // Commands go out as binary WebSocket frames: the KiwiSDR's command parser ignores text
    // frames (it sends its own MSG/SND frames as binary ones too).
    private fun WebSocket.sendCommand(cmd: String) {
        send(cmd.encodeUtf8())
    }

    private fun sendSetMod() {
        val basebandFreqKHz = (tunedFrequency / 1000.0) - freqOffsetKHz
        val cmd = "SET mod=iq low_cut=$lowCut high_cut=$highCut freq=$basebandFreqKHz"
        webSocket?.sendCommand(cmd)
    }

    private fun startKeepalive(ws: WebSocket) {
        stopKeepalive()
        val thread = Thread {
            while (!stopRequested) {
                ws.sendCommand("SET keepalive")
                try {
                    Thread.sleep(KEEPALIVE_INTERVAL_MS)
                } catch (e: InterruptedException) {
                    break
                }
            }
        }
        thread.name = "Thread-KiwiSdr-Keepalive-" + System.currentTimeMillis()
        thread.isDaemon = true
        keepaliveThread = thread
        thread.start()
    }

    // Some servers (e.g. the *.proxy.kiwisdr.com hosts) accept the connection and then never
    // send their sample rate: give up after HANDSHAKE_TIMEOUT_MS instead of waiting forever.
    private fun watchHandshake(ws: WebSocket) {
        val thread = Thread {
            try {
                Thread.sleep(HANDSHAKE_TIMEOUT_MS)
            } catch (e: InterruptedException) {
                return@Thread
            }
            if (!isReady && !stopRequested && webSocket === ws) {
                Log.w(TAG, "watchHandshake: no IQ stream ${HANDSHAKE_TIMEOUT_MS / 1000} s after connecting - giving up")
                stopRequested = true
                stopKeepalive()
                ws.close(1000, "no IQ stream")
                webSocket = null
                reportError("KiwiSDR at $host:$port connected but sent no IQ stream within ${HANDSHAKE_TIMEOUT_MS / 1000} s")
            }
        }
        thread.name = "Thread-KiwiSdr-Handshake-" + System.currentTimeMillis()
        thread.isDaemon = true
        thread.start()
    }

    private fun stopKeepalive() {
        keepaliveThread?.interrupt()
        keepaliveThread = null
    }

    private inner class KiwiWebSocketListener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            connected = true
            if (stopRequested) { // stopped while connecting: don't take a channel slot
                Log.i(TAG, "onOpen: stopped while connecting - closing")
                webSocket.close(1000, "closing")
                return
            }
            this@KiwiSdrSource.webSocket = webSocket
            Log.i(TAG, "onOpen: connected to $host:$port")
            webSocket.sendCommand("SET auth t=kiwi p=$effectivePassword")
            watchHandshake(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            handleTextMessage(webSocket, text)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            // KiwiSDRs send MSG frames as binary frames too (their own client only looks at the
            // 3-byte tag): MSG goes to the text parser whatever the opcode, the rest is SND.
            if (bytes.size >= 3 && bytes.utf8().startsWith("MSG")) {
                handleTextMessage(webSocket, bytes.utf8())
            } else {
                handleBinaryMessage(bytes)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.e(TAG, "onFailure: " + t.message)
            if (stopRequested) { // the user stopped (a cancelled connect fails here): nothing to report
                this@KiwiSdrSource.webSocket = null
                return
            }
            val wasReady = isReady
            stopRequested = true
            stopKeepalive()
            this@KiwiSdrSource.webSocket = null
            // No automatic reconnect (as in RtltcpSource); nothing above the source retries either.
            reportError(
                if (wasReady) "Connection lost: ${t.message}"
                else "Could not connect to KiwiSDR at $host:$port: ${t.message}"
            )
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            Log.i(TAG, "onClosed: code=$code reason=$reason")
            if (!stopRequested) {
                stopRequested = true
                stopKeepalive()
                reportError("Connection closed by server: $reason")
            }
            this@KiwiSdrSource.webSocket = null
        }
    }

    private fun handleTextMessage(ws: WebSocket, text: String) {
        // Frames are "MSG" + 1 skipped byte + space-separated "key=value" pairs.
        if (!text.startsWith("MSG") || text.length <= 4) return
        val body = text.substring(4)
        for (pair in body.split(' ')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val name = if (eq >= 0) pair.substring(0, eq) else pair
            val value = if (eq >= 0) pair.substring(eq + 1) else null
            handleMsgParam(ws, name, value)
        }
    }

    private fun handleMsgParam(ws: WebSocket, name: String, value: String?) {
        when (name) {
            "too_busy" -> {
                stopRequested = true
                reportError("KiwiSDR at $host:$port has no free channel slots")
            }
            "badp" -> {
                // Sent on every connection as a routine status field - "0" means no problem.
                // Only non-zero codes (bad password, IP already connected, etc.) are errors.
                if (value != null && value != "0") {
                    stopRequested = true
                    reportError("KiwiSDR authentication failed (bad password or channel restricted, code=$value)")
                }
            }
            "down" -> {
                stopRequested = true
                reportError("KiwiSDR server at $host:$port is down")
            }
            "bandwidth" -> value?.toDoubleOrNull()?.let { maxFrequency = it.toLong() }
            "freq_offset" -> value?.toDoubleOrNull()?.let { freqOffsetKHz = it }
            "audio_rate" -> value?.toIntOrNull()?.let {
                ws.sendCommand("SET AR OK in=$it out=44100")
            }
            "sample_rate" -> value?.toDoubleOrNull()?.let { rate ->
                streamSampleRate = nominalSampleRate(rate)
                Log.i(TAG, "handleMsgParam: server sample rate $rate Hz, using $streamSampleRate Hz")
                converter.sampleRate = streamSampleRate
                // Required setup sequence once the server reports a sample rate.
                for (cmd in listOf("SET squelch=0 max=0", "SET genattn=0", "SET gen=0 mix=-1", "SET ident_user=$identUser")) {
                    ws.sendCommand(cmd)
                }
                sendSetMod()
                for (cmd in listOf("SET agc=1 hang=0 thresh=-100 slope=6 decay=1000 manGain=50", "SET compression=0", "SET keepalive")) {
                    ws.sendCommand(cmd)
                }
                startKeepalive(ws)
            }
        }
    }

    private fun handleBinaryMessage(byteString: ByteString) {
        if (stopRequested) return
        val bytes = byteString.toByteArray()
        val tag = if (bytes.size >= 3) String(bytes, 0, 3, Charsets.US_ASCII) else "?"
        if (bytes.size < 3 || tag != "SND") return
        if (bytes.size < 3 + SND_HEADER_SIZE) {
            Log.w(TAG, "handleBinaryMessage: SND frame too short (${bytes.size} bytes)")
            return
        }

        val flags = bytes[3].toInt() and 0xFF
        if (flags and SND_FLAG_STEREO == 0) {
            // Not an IQ (stereo) frame - shouldn't happen once mod=iq is active. Ignore.
            return
        }
        if (flags and SND_FLAG_COMPRESSED != 0) {
            // compression=0 is requested; ADPCM frames of a server that ignores it aren't decoded.
            Log.w(TAG, "handleBinaryMessage: received unexpected compressed SND frame, dropping")
            return
        }

        val payloadStart = 3 + SND_HEADER_SIZE
        if (bytes.size - payloadStart <= GPS_HEADER_SIZE) {
            Log.w(TAG, "handleBinaryMessage: SND payload too short for GPS header + samples")
            return
        }
        val sampleStart = payloadStart + GPS_HEADER_SIZE
        val rawSampleByteCount = bytes.size - sampleStart
        val sampleByteCount = rawSampleByteCount - (rawSampleByteCount % 4) // whole complex samples only
        if (sampleByteCount <= 0) return

        if (packetSize == 0) {
            // The first IQ frame fixes packetSize and sizes the buffer pool: the server decides
            // how many samples one SND frame holds.
            packetSize = sampleByteCount
            val newQueue = ArrayBlockingQueue<ByteArray>(QUEUE_SIZE)
            val newReturnQueue = ArrayBlockingQueue<ByteArray>(QUEUE_SIZE)
            for (i in 0 until QUEUE_SIZE - 1) {
                newReturnQueue.offer(ByteArray(packetSize))
            }
            queue = newQueue
            returnQueue = newReturnQueue
            isReady = true
            callback?.onIQSourceReady(this@KiwiSdrSource)
        }

        if (sampleByteCount != packetSize) {
            Log.w(TAG, "handleBinaryMessage: unexpected SND payload size $sampleByteCount (expected $packetSize), dropping frame")
            return
        }

        val buffer = returnQueue?.poll() ?: ByteArray(packetSize)
        val gpsTimestamp = gpsTimestampMs(bytes, payloadStart, ClockCorrection.now())
        if (gpsTimestamp != null) gpsTimestamps[buffer] = gpsTimestamp else gpsTimestamps.remove(buffer)
        // KiwiSDR's IQ samples are big-endian int16 (outside "camp" mode); Signed16BitIQConverter
        // reads little-endian, so the bytes of each value are swapped here.
        var i = 0
        while (i < sampleByteCount) {
            val srcIdx = sampleStart + i
            buffer[i] = bytes[srcIdx + 1]
            buffer[i + 1] = bytes[srcIdx]
            i += 2
        }

        if (queue?.offer(buffer) == false) {
            // The app isn't taking packets as fast as the server sends them: this frame is lost.
            GlobalPerformanceData.incrementCounter("source.droppedPackets")
            if (streamSampleRate > 0) OverloadMonitor.reportLoss("Source", (sampleByteCount / 4) * 1_000_000_000L / streamSampleRate)
            gpsTimestamps.remove(buffer)
            returnQueue?.offer(buffer)
        }
    }
}
