package com.mantz_it.rfanalyzer.source.testutil

import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * <h1>RF Analyzer - Fake KiwiSDR Server</h1>
 *
 * Module:      FakeKiwiSdrServer.kt
 * Description: Minimal hand-rolled HYBI13 WebSocket *server* used to test KiwiSdrSource without a
 *              real network dependency. Implements just enough of RFC 6455 (HTTP upgrade handshake,
 *              unmasking client frames, sending unmasked server frames) to interoperate with any
 *              spec-compliant client, including OkHttp - deliberately not built on OkHttp itself
 *              (its mockwebserver doesn't serve WebSocket connections anyway).
 *
 * Simulates the KiwiSDR application-level protocol on top of that: records every SET command frame
 * the client sends, and exposes helpers to script MSG param frames and SND binary IQ frames in the
 * layout KiwiSdrSource parses.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class FakeKiwiSdrServer {

    companion object {
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val OPCODE_TEXT = 0x1
        private const val OPCODE_BINARY = 0x2
        private const val OPCODE_CLOSE = 0x8
        private const val OPCODE_PING = 0x9
        private const val OPCODE_PONG = 0xA

        const val SND_FLAG_STEREO = 0x08
        const val SND_FLAG_COMPRESSED = 0x10
        const val SND_FLAG_LITTLE_ENDIAN = 0x80
    }

    private val serverSocket = ServerSocket(0)
    val port: Int get() = serverSocket.localPort

    @Volatile private var clientSocket: Socket? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var input: InputStream? = null

    val receivedCommands = CopyOnWriteArrayList<String>()
    @Volatile var requestPath: String? = null
        private set

    private val connectedLatch = CountDownLatch(1)
    val clientCloseObserved = CountDownLatch(1)

    /** Called with every command the client sends. */
    @Volatile var onTextReceived: ((String) -> Unit)? = null

    fun start() {
        val thread = Thread {
            try {
                val socket = serverSocket.accept()
                clientSocket = socket
                socket.tcpNoDelay = true
                input = socket.getInputStream()
                output = socket.getOutputStream()
                if (!performHandshake()) return@Thread
                connectedLatch.countDown()
                readLoop()
            } catch (e: IOException) {
                // Socket closed while accepting/handshaking - expected for some negative-path tests.
            }
        }
        thread.isDaemon = true
        thread.name = "FakeKiwiSdrServer-accept"
        thread.start()
    }

    fun awaitConnected(timeoutMs: Long = 5000): Boolean = connectedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    fun awaitCommand(prefix: String, timeoutMs: Long = 5000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (receivedCommands.any { it.startsWith(prefix) }) return true
            Thread.sleep(20)
        }
        return receivedCommands.any { it.startsWith(prefix) }
    }

    private fun performHandshake(): Boolean {
        val inp = input ?: return false
        val out = output ?: return false
        val reader = BufferedReader(InputStreamReader(inp, StandardCharsets.US_ASCII))
        val requestLine = reader.readLine() ?: return false
        val parts = requestLine.split(' ')
        if (parts.size >= 2) requestPath = parts[1]

        var key: String? = null
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0 && line.substring(0, idx).trim().equals("Sec-WebSocket-Key", ignoreCase = true)) {
                key = line.substring(idx + 1).trim()
            }
        }
        if (key == null) return false

        val accept = Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-1").digest((key + WS_GUID).toByteArray(StandardCharsets.US_ASCII))
        )
        val response = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        out.write(response.toByteArray(StandardCharsets.US_ASCII))
        out.flush()
        return true
    }

    private fun readLoop() {
        val inp = input ?: return
        try {
            while (true) {
                val first = inp.read()
                if (first == -1) break
                val opcode = first and 0x0F
                val second = inp.read()
                if (second == -1) break
                val masked = (second and 0x80) != 0
                var payloadLen = (second and 0x7F).toLong()
                if (payloadLen == 126L) {
                    val b0 = inp.read(); val b1 = inp.read()
                    if (b0 == -1 || b1 == -1) break
                    payloadLen = ((b0 and 0xFF) shl 8 or (b1 and 0xFF)).toLong()
                } else if (payloadLen == 127L) {
                    var len = 0L
                    for (i in 0 until 8) {
                        val b = inp.read()
                        if (b == -1) return
                        len = (len shl 8) or (b.toLong() and 0xFF)
                    }
                    payloadLen = len
                }
                val maskKey = ByteArray(4)
                if (masked) {
                    var read = 0
                    while (read < 4) {
                        val r = inp.read(maskKey, read, 4 - read)
                        if (r == -1) return
                        read += r
                    }
                }
                val payload = ByteArray(payloadLen.toInt())
                var read = 0
                while (read < payload.size) {
                    val r = inp.read(payload, read, payload.size - read)
                    if (r == -1) return
                    read += r
                }
                if (masked) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor maskKey[i % 4].toInt()).toByte()
                    }
                }
                when (opcode) {
                    // KiwiSDR clients send SET commands as binary frames (see
                    // KiwiSdrSource.sendCommand), although the payload is plain ASCII: both
                    // opcodes are commands here.
                    OPCODE_TEXT, OPCODE_BINARY -> {
                        val text = String(payload, StandardCharsets.UTF_8)
                        receivedCommands.add(text)
                        onTextReceived?.invoke(text)
                    }
                    OPCODE_CLOSE -> {
                        sendFrame(OPCODE_CLOSE, ByteArray(0))
                        clientCloseObserved.countDown()
                        return
                    }
                    OPCODE_PING -> sendFrame(OPCODE_PONG, payload)
                    else -> { /* pong from client not expected - ignore */ }
                }
            }
        } catch (e: IOException) {
            // Connection dropped - expected for negative-path tests.
        } finally {
            clientCloseObserved.countDown()
        }
    }

    private fun sendFrame(opcode: Int, payload: ByteArray) {
        val out = output ?: return
        synchronized(this) {
            val header = ArrayList<Byte>(10)
            header.add((0x80 or opcode).toByte()) // FIN + opcode, never masked (server->client)
            val len = payload.size
            when {
                len <= 125 -> header.add(len.toByte())
                len <= 0xFFFF -> {
                    header.add(126.toByte())
                    header.add(((len shr 8) and 0xFF).toByte())
                    header.add((len and 0xFF).toByte())
                }
                else -> {
                    header.add(127.toByte())
                    for (shift in intArrayOf(56, 48, 40, 32, 24, 16, 8, 0)) {
                        header.add(((len.toLong() shr shift) and 0xFF).toByte())
                    }
                }
            }
            try {
                out.write(header.toByteArray())
                out.write(payload)
                out.flush()
            } catch (e: IOException) {
                // client already gone - fine for negative-path tests
            }
        }
    }

    fun sendText(message: String) = sendFrame(OPCODE_TEXT, message.toByteArray(StandardCharsets.UTF_8))

    fun sendBinary(bytes: ByteArray) = sendFrame(OPCODE_BINARY, bytes)

    /**
     * Builds and sends one SND binary frame carrying an IQ (stereo) payload: 7-byte SND
     * header + 10-byte GPS block + interleaved big-endian int16 I/Q samples, the real KiwiSDR
     * wire format.
     */
    fun sendSndIqFrame(seq: Int, samples: List<Pair<Int, Int>>, extraFlags: Int = 0, gpsSeconds: Long = 0, gpsNanos: Long = 0) {
        sendBinary(buildSndIqFrame(seq, samples, extraFlags, gpsSeconds, gpsNanos))
    }

    /** [gpsSeconds]/[gpsNanos]: the GPS time of week of the first sample (0/0: no GPS time). */
    fun buildSndIqFrame(seq: Int, samples: List<Pair<Int, Int>>, extraFlags: Int = 0, gpsSeconds: Long = 0, gpsNanos: Long = 0): ByteArray {
        val tag = "SND".toByteArray(StandardCharsets.US_ASCII)
        val header = ByteArray(7)
        header[0] = (SND_FLAG_STEREO or extraFlags).toByte()
        header[1] = (seq and 0xFF).toByte()
        header[2] = ((seq shr 8) and 0xFF).toByte()
        header[3] = ((seq shr 16) and 0xFF).toByte()
        header[4] = ((seq shr 24) and 0xFF).toByte()
        header[5] = 0 // smeter (BE uint16) - arbitrary for tests
        header[6] = 100
        // GPS block: last_gps_solution, dummy, gpssec and gpsnsec (little-endian uint32)
        val gps = ByteArray(10)
        gps[0] = 1
        for (i in 0 until 4) {
            gps[2 + i] = (gpsSeconds shr (8 * i)).toByte()
            gps[6 + i] = (gpsNanos shr (8 * i)).toByte()
        }
        val sampleBytes = ByteArray(samples.size * 4)
        var idx = 0
        for ((i, q) in samples) {
            sampleBytes[idx] = ((i shr 8) and 0xFF).toByte()
            sampleBytes[idx + 1] = (i and 0xFF).toByte()
            sampleBytes[idx + 2] = ((q shr 8) and 0xFF).toByte()
            sampleBytes[idx + 3] = (q and 0xFF).toByte()
            idx += 4
        }
        return tag + header + gps + sampleBytes
    }

    /** Sends a truncated/malformed frame tagged as SND (too short to contain a full header). */
    fun sendMalformedSndFrame() {
        sendBinary("SND".toByteArray(StandardCharsets.US_ASCII) + byteArrayOf(0x08, 0, 0))
    }

    /** Drops the TCP connection without a WebSocket close handshake, simulating a server crash/network loss. */
    fun disconnectAbruptly() {
        try {
            clientSocket?.close()
        } catch (e: IOException) {
            // ignore
        }
    }

    fun stop() {
        disconnectAbruptly()
        try {
            serverSocket.close()
        } catch (e: IOException) {
            // ignore
        }
    }
}
