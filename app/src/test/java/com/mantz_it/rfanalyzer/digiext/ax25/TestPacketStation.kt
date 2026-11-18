package com.mantz_it.rfanalyzer.digiext.ax25

import com.mantz_it.rfanalyzer.digiext.db.liveObject
import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import com.mantz_it.rfanalyzer_ipc.Ax25Variant
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Packet
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Phy
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - Packet Station Tests</h1>
 *
 * Module:      TestPacketStation.kt
 * Description: [applyPacketFrame]: what a BBS, a node and a connecting user look like as
 *              [PacketStation]s.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestPacketStation {

    private fun address(callsign: String, ssid: Int, isLast: Boolean, c: Boolean): ByteArray {
        val padded = callsign.padEnd(6)
        return ByteArray(7) { if (it < 6) (padded[it].code shl 1).toByte() else ((ssid shl 1) or (if (isLast) 1 else 0) or (if (c) 0x80 else 0)).toByte() }
    }

    private fun frame(t: Long, dest: String, destSsid: Int, src: String, srcSsid: Int, control: Int, pid: Int?, info: String = ""): Ax25Frame {
        val raw = address(dest, destSsid, false, true) + address(src, srcSsid, true, false) + byteArrayOf(control.toByte()) +
            (pid?.let { byteArrayOf(it.toByte()) } ?: ByteArray(0)) + info.toByteArray()
        val packet = Ax25Packet(timestampMs = t, frequencyHz = 14_103_300, raw = Bytes(raw), phy = Ax25Phy(Ax25Variant.HF_300))
        return Ax25Spec.interpret(packet, packet.frequencyHz).packet
    }

    @Test
    fun aBbsANodeAndAConnection() = runBlocking {
        val store = memoryObjectStore(PacketStationFamily)
        applyPacketFrame(store, frame(1_000, "MAIL", 0, "VA2PGN", 0, 0x03, 0xF0, "Mail For:VA2PGN"))
        applyPacketFrame(store, frame(2_000, "FBB", 0, "VA2PGN", 0, 0x03, 0xF0, "49642  B   1260 EQUAK         HP2DFA 261025 EARTHQUAKE ALERT"))
        applyPacketFrame(store, frame(3_000, "ID", 0, "N0HI", 7, 0x03, 0xF0, "NODE: SOLBPQ:N0HI-7, Parker, CO Area BPQ Packet Node "))
        applyPacketFrame(store, frame(4_000, "KB9KC", 7, "KE0GB", 0, 0x3F, null))
        // an APRS frame is not a packet station's
        applyPacketFrame(store, frame(5_000, "APRS", 0, "DF4IAE", 0, 0x03, 0xF0, "!4903.50N/07201.75W-"))

        val bbs = store.liveObject("PACKET:VA2PGN")
        assertEquals("BBS", bbs.state.role)
        assertEquals(listOf("VA2PGN"), bbs.state.mailFor)
        assertEquals(1, bbs.state.bulletins)
        assertEquals("EARTHQUAKE ALERT", bbs.state.lastBulletin?.title)
        assertEquals(2, bbs.packetCount)

        assertEquals("NODE", store.liveObject("PACKET:N0HI-7").state.role)

        val user = store.liveObject("PACKET:KE0GB")
        assertEquals(listOf("KB9KC-7"), user.state.peers)
        assertEquals("SABM P", user.state.lastFrame)
        // the called node is listed, not heard
        val called = store.liveObject("PACKET:KB9KC-7")
        assertEquals(0, called.packetCount)
        assertEquals(listOf("KE0GB"), called.state.peers)
        assertEquals(4, store.live.value.size)
    }
}
