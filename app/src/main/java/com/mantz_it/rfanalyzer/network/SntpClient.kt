package com.mantz_it.rfanalyzer.network

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * <h1>RF Analyzer - SNTP Client</h1>
 *
 * Module:      SntpClient.kt
 * Description: Measures this device's wall-clock error against NTP servers (SNTP, RFC 4330, UDP
 *              port 123). One query gives offset = ((T2 - T1) + (T3 - T4)) / 2, with T1/T4 the
 *              local send/receive times and T2/T3 the server's receive/transmit times. An
 *              asymmetric network path biases that by up to half the round trip, so of several
 *              queries the one with the shortest round trip is used.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object SntpClient {
    private const val TAG = "SntpClient"
    /** Android's own NTP server first, then Google Public NTP (the NTP Pool's rules don't allow
     *  pool.ntp.org as a product's default). */
    val DEFAULT_SERVERS = listOf("time.android.com", "time.google.com")
    private const val PORT = 123
    private const val QUERIES_PER_SERVER = 4
    private const val TIMEOUT_MS = 2000
    private const val PACKET_SIZE = 48
    private const val OFFSET_ORIGINATE = 24
    private const val OFFSET_RECEIVE = 32
    private const val OFFSET_TRANSMIT = 40
    /** Seconds from the NTP epoch (1900) to the Unix epoch (1970). */
    private const val NTP_TO_UNIX_SECONDS = 2_208_988_800L

    /** [offsetMs]: UTC minus the local wall clock. */
    class Measurement(val offsetMs: Long, val roundTripMs: Long, val server: String)

    /** Queries the first reachable of [servers]; throws an [IOException] if none answers. */
    suspend fun measure(servers: List<String> = DEFAULT_SERVERS): Measurement = withContext(Dispatchers.IO) {
        var lastError: IOException? = null
        for (server in servers) {
            var best: Measurement? = null
            try {
                val address = InetAddress.getByName(server)
                DatagramSocket().use { socket ->
                    socket.soTimeout = TIMEOUT_MS
                    repeat(QUERIES_PER_SERVER) {
                        try {
                            val m = query(socket, address, server)
                            if (best == null || m.roundTripMs < best!!.roundTripMs) best = m
                        } catch (e: IOException) {
                            lastError = e
                        }
                    }
                }
            } catch (e: IOException) {
                lastError = e
            }
            best?.let {
                Log.d(TAG, "measure: ${it.server}: offset ${it.offsetMs} ms, round trip ${it.roundTripMs} ms")
                return@withContext it
            }
            Log.w(TAG, "measure: no answer from $server: ${lastError?.message}")
        }
        throw IOException("No time server reachable (${lastError?.message})", lastError)
    }

    private fun query(socket: DatagramSocket, address: InetAddress, server: String): Measurement {
        val request = ByteArray(PACKET_SIZE)
        request[0] = 0x23 // leap indicator 0, version 4, mode 3 (client)
        val t1 = System.currentTimeMillis()
        val t1Nanos = System.nanoTime()
        writeTimestamp(request, OFFSET_TRANSMIT, t1)
        socket.send(DatagramPacket(request, PACKET_SIZE, address, PORT))
        val response = ByteArray(PACKET_SIZE)
        val packet = DatagramPacket(response, PACKET_SIZE)
        // the server echoes our transmit time as its originate time: skips a late answer to an earlier query
        do {
            socket.receive(packet)
        } while (!response.copyOfRange(OFFSET_ORIGINATE, OFFSET_ORIGINATE + 8)
                .contentEquals(request.copyOfRange(OFFSET_TRANSMIT, OFFSET_TRANSMIT + 8)))
        // T4 from the monotonic clock, so a wall-clock step during the query can't distort it
        val t4 = t1 + (System.nanoTime() - t1Nanos) / 1_000_000
        return evaluate(response, t1, t4, server)
    }

    /** Offset and round trip from a server [response] to a request sent at [t1] and answered at [t4] (local ms). */
    internal fun evaluate(response: ByteArray, t1: Long, t4: Long, server: String): Measurement {
        val leap = (response[0].toInt() shr 6) and 0x3
        val mode = response[0].toInt() and 0x7
        val stratum = response[1].toInt() and 0xFF
        if (mode != 4) throw IOException("$server: not a server response (mode $mode)")
        // stratum 0 is a "kiss-o'-death" (e.g. rate limiting), leap 3 an unsynchronised server
        if (stratum == 0 || stratum > 15 || leap == 3) throw IOException("$server: not synchronised (stratum $stratum, leap $leap)")
        val t2 = readTimestamp(response, OFFSET_RECEIVE)
        val t3 = readTimestamp(response, OFFSET_TRANSMIT)
        return Measurement(offsetMs = ((t2 - t1) + (t3 - t4)) / 2, roundTripMs = (t4 - t1) - (t3 - t2), server = server)
    }

    /** NTP timestamp (32-bit seconds since 1900, 32-bit fraction) at [offset] as Unix ms. */
    internal fun readTimestamp(buffer: ByteArray, offset: Int): Long {
        var seconds = readUInt32(buffer, offset)
        val fraction = readUInt32(buffer, offset + 4)
        // era 1 starts 2036-02-07: seconds that small mean it already began
        if (seconds and 0x8000_0000L == 0L) seconds += 0x1_0000_0000L
        return (seconds - NTP_TO_UNIX_SECONDS) * 1000 + (fraction * 1000 + 0x8000_0000L) / 0x1_0000_0000L
    }

    internal fun writeTimestamp(buffer: ByteArray, offset: Int, unixMs: Long) {
        val seconds = Math.floorDiv(unixMs, 1000L) + NTP_TO_UNIX_SECONDS
        val fraction = Math.floorMod(unixMs, 1000L) * 0x1_0000_0000L / 1000
        writeUInt32(buffer, offset, seconds)
        writeUInt32(buffer, offset + 4, fraction)
    }

    private fun readUInt32(buffer: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, i -> (value shl 8) or (buffer[offset + i].toLong() and 0xFF) }

    private fun writeUInt32(buffer: ByteArray, offset: Int, value: Long) {
        for (i in 0 until 4) buffer[offset + i] = (value shr (24 - 8 * i)).toByte()
    }
}
