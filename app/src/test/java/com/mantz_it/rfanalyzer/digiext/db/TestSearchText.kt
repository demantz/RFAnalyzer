package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Phy
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Packet
import com.mantz_it.rfanalyzer_ipc.Ax25Variant
import com.mantz_it.rfanalyzer.digiext.ax25.Ax25Spec
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreEncryptedEnvelope
import com.mantz_it.rfanalyzer.digiext.lora.MeshCorePacket
import com.mantz_it.rfanalyzer.digiext.lora.MeshCorePayloadType
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreRouteType
import com.mantz_it.rfanalyzer.digiext.lora.LoraPayloadMessage
import com.mantz_it.rfanalyzer.digiext.lora.loraSearchTerms
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Search Text Tests</h1>
 *
 * Module:      TestSearchText.kt
 * Description: Pins what a stored row's search text holds: what the UI shows and the identities, no
 *              bookkeeping or raw bytes.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestSearchText {

    @Test
    fun textAndShortNumbersAreCollectedAlongWithTheObjectKey() {
        assertEquals(
            "3C66B1 AirbornePositionPayload 16400 BARO 7700 1013.6 -17 2097151",
            genericSearchText(
                "3C66B1",
                """{"type":"AirbornePositionPayload","altitudeFt":16400,"altSource":"BARO","squawk":"7700","qnhHpa":1013.6,"report":-17,"capcode":2097151,"note":null}""",
            ),
        )
    }

    /** Ids, timestamps, Hz frequencies, computed floats, decimal identities, flags and raw bytes. */
    @Test
    fun bookkeepingBooleansAndRawBytesAreLeftOut() {
        assertEquals(
            "K MeshCorePacket 2 name",
            genericSearchText(
                "K",
                """{"id":72495241095765,"receiveTimestamp":1791889174469,"receiveFrequency":173139900,"type":"MeshCorePacket","ok":true,"headingDeg":181.89338584574628,"devAddr":687306151,"size":2,"codes":[16,12,85,-76],"path":[[-111,-77],[-40,91]],"empty":[],"s":"name"}""",
            ),
        )
    }

    /** RDS alternative frequencies are shown as they are stored - a list of numbers, but not bytes. */
    @Test
    fun listsOfFractionalNumbersAreKept() {
        assertEquals("K 87.7 88.5", genericSearchText("K", """{"af":[87.7,88.5]}"""))
    }

    /** SQLite's `LIKE` stops at a NUL, which would hide everything after a padded POCSAG message. */
    @Test
    fun nulCharactersAreRemoved() {
        assertEquals("K R#Tfu Set of ISS", genericSearchText("K", """{"a":"R#Tfu\u0000\u0000","b":"Set of ISS"}"""))
    }

    @Test
    fun listsOfTextAreKept() {
        assertEquals("K WIDE1-1 WIDE2-1", genericSearchText("K", """{"path":["WIDE1-1","WIDE2-1"]}"""))
    }

    @Test
    fun extraTermsAreAppended() {
        assertEquals("K a 91b3 d85b", genericSearchText("K", """{"x":"a"}""", extraTerms = listOf("91b3", "", "d85b")))
    }

    /** The routing hashes of a MeshCore packet are searchable as hex although its bytes are not. */
    @Test
    fun meshCoreRoutingHashesAreSearchableAsHex() {
        val packet: LoraPayloadMessage = MeshCorePacket(
            routeType = MeshCoreRouteType.entries.first(),
            payloadType = MeshCorePayloadType.entries.first(),
            payloadVersion = 0,
            transportCodes = Bytes(byteArrayOf(16, 12, 85, -76)),
            pathHashSize = 2,
            path = listOf(Bytes(byteArrayOf(-111, -77)), Bytes(byteArrayOf(-40, 91))),
            info = MeshCoreEncryptedEnvelope(
                destHash = null, srcHash = null, channelHash = Bytes(byteArrayOf(0x11)),
                mac = Bytes(byteArrayOf(1, 2)), ciphertext = Bytes(byteArrayOf(99, 98, 97)),
            ),
        )
        assertEquals(listOf("91B3", "D85B", "11"), loraSearchTerms(packet))

        val text = genericSearchText("", storageJson.encodeToString(packet), extraTerms = loraSearchTerms(packet))
        assertTrue(text, text.endsWith("91B3 D85B 11"))
        assertFalse(text, text.contains("99"))
        assertFalse(text, text.contains("-111"))
    }

    /** An AX.25 row finds its APRS text and its addresses as written ("CALL-SSID"). */
    @Test
    fun ax25FramesAreSearchableByTheirTextAndAddresses() {
        fun address(call: String, ssid: Int, last: Boolean) =
            call.padEnd(6).map { (it.code shl 1).toByte() }.toByteArray() + byteArrayOf(((ssid shl 1) or (if (last) 1 else 0)).toByte())
        val raw = Bytes(
            address("APRS", 0, false) + address("DF4IAE", 9, false) + address("WIDE1", 1, true) +
                byteArrayOf(0x03, 0xF0.toByte()) + ">Out and About.".toByteArray(Charsets.ISO_8859_1)
        )
        val row = Ax25Spec.toRow(Ax25Spec.interpret(Ax25Packet(raw = raw, phy = Ax25Phy(Ax25Variant.VHF_1200)), 144_800_000L).packet)

        assertTrue(row.searchText, row.searchText.contains("Out and About."))
        assertTrue(row.searchText, row.searchText.contains("DF4IAE-9"))
        assertTrue(row.searchText, row.searchText.contains("WIDE1-1"))
    }
}
