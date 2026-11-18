package com.mantz_it.rfanalyzer.digiext.lora.aprs

import com.mantz_it.rfanalyzer.digiext.aprs.MicEInfo
import com.mantz_it.rfanalyzer.digiext.aprs.PositionReport
import com.mantz_it.rfanalyzer.digiext.aprs.ThirdPartyInfo
import com.mantz_it.rfanalyzer.digiext.db.liveObject
import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import com.mantz_it.rfanalyzer.digiext.lora.LoraAprsPacket
import com.mantz_it.rfanalyzer.digiext.lora.LoraPayloadDecoder
import com.mantz_it.rfanalyzer.digiext.lora.loraFrame
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - LoRa-APRS Tests</h1>
 *
 * Module:      TestLoraAprs.kt
 * Description: Pure-JVM unit tests for LoraAprsParser / LoraPayloadDecoder's LoRa-APRS pre-filter —
 *              no JNI/device needed, this is plain byte-array parsing.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestLoraAprs {

    // ---- Real-world fixture ----
    // A LoRa-APRS iGate beacon captured on 433.775 MHz (SF12). It uses the default sync word
    // 0x12, as MeshCore does, so only the magic-header pre-filter tells it from a MeshCore packet.
    // Decodes (latin-1) to: "<\xFF\x01DF4IAE-10>APLRG1,WIDE1-1:!L5N-0P_A<a  GLoRa APRS iGate Dossenheim"
    //
    // The info field ("!L5N-0P_A<a  GLoRa APRS iGate Dossenheim") is a standard APRS
    // *compressed* position report (APRS101.PDF §9.4.4), not the fixed-offset uncompressed
    // format: symbol-table-id='L' (1 byte, a legal non-'/'-or-'\' overlay char per spec),
    // base91 lat="5N-0", base91 lon="P_A<", symbol='a', course/speed="  " (blank - none
    // sent), compression-type='G', comment="LoRa APRS iGate Dossenheim". APLRG1 is the tocall
    // of the transmitting firmware (richonguzman/APRSPacketLib); the decoded position
    // (~49.4536N, ~8.6676E) lies within ~1.5km of Dossenheim, Germany - matching the comment.
    private val realAprsPayload = byteArrayOf(
        60, -1, 1, 68, 70, 52, 73, 65, 69, 45, 49, 48,
        62, 65, 80, 76, 82, 71, 49, 44, 87, 73, 68, 69,
        49, 45, 49, 58, 33, 76, 53, 78, 45, 48, 80, 95,
        65, 60, 97, 32, 32, 71, 76, 111, 82, 97, 32, 65,
        80, 82, 83, 32, 105, 71, 97, 116, 101, 32, 68, 111,
        115, 115, 101, 110, 104, 101, 105, 109,
    )

    // Real MeshCore ADVERT fixture from TestMeshCore.kt's realAdvertPayload (same sync word,
    // 0x12 header byte — used here to prove the magic-header gate doesn't cross-match it).
    private val realMeshCoreAdvertPayload = byteArrayOf(
        0x12, 0x00, 0xe7.toByte(), 0xe8.toByte(), 0x22, 0xdc.toByte(), 0x88.toByte(), 0x5a,
        0x37, 0x3c, 0xee.toByte(), 0xa9.toByte(), 0xf3.toByte(), 0xeb.toByte(), 0x4e, 0xd8.toByte(),
        0xa2.toByte(), 0x8a.toByte(), 0x45, 0x63, 0x96.toByte(), 0x77, 0x54, 0x2e,
        0xf4.toByte(), 0x9a.toByte(), 0x26, 0xb8.toByte(), 0x74, 0x27, 0x02, 0xfa.toByte(),
        0x2d, 0xc2.toByte(), 0x3d, 0xee.toByte(), 0x1d, 0x6a, 0xd8.toByte(), 0xc8.toByte(),
        0x47, 0x04, 0x8c.toByte(), 0x08, 0x88.toByte(), 0x2a, 0x8b.toByte(), 0xf9.toByte(),
        0x51, 0x74, 0xd5.toByte(), 0x6d, 0x7e, 0x75, 0xa5.toByte(), 0xf3.toByte(),
        0xe0.toByte(), 0x45, 0x8a.toByte(), 0xc3.toByte(), 0xe5.toByte(), 0xd5.toByte(), 0xbd.toByte(), 0x2c,
        0xc2.toByte(), 0x54, 0xa3.toByte(), 0x42, 0x6d, 0x13, 0xdf.toByte(), 0xde.toByte(),
        0xcf.toByte(), 0x92.toByte(), 0xaf.toByte(), 0x0a, 0xc2.toByte(), 0x29, 0xe2.toByte(), 0x7c,
        0xbd.toByte(), 0xb0.toByte(), 0xe1.toByte(), 0x23, 0x13, 0xb8.toByte(), 0xd4.toByte(), 0x19,
        0x45, 0x21, 0x8c.toByte(), 0xfe.toByte(), 0xc6.toByte(), 0x2c, 0x41, 0xfe.toByte(),
        0xfa.toByte(), 0xdd.toByte(), 0x6f, 0x64, 0x8c.toByte(), 0x0e, 0x81.toByte(), 0x45,
        0x37, 0x45, 0x38, 0x32, 0x32, 0x44, 0x43,
    )

    @Test
    fun testRealAprsFixtureDirect() {
        val packet = LoraAprsParser.tryParse(realAprsPayload)
        assertTrue("Expected a LoraAprsPacket", packet != null)
        packet!!

        assertEquals("DF4IAE-10", packet.fromCall)
        assertEquals("APLRG1", packet.toCall)
        assertEquals(listOf("WIDE1-1"), packet.path)

        assertTrue("Expected PositionReport info", packet.info is PositionReport)
        val position = packet.info as PositionReport
        assertEquals('!', position.dti)
        assertEquals('L', position.symbolTable)
        assertEquals('a', position.symbol)
        assertNotNull("Expected a decoded latitude (compressed position)", position.lat)
        assertNotNull("Expected a decoded longitude (compressed position)", position.lon)
        // Dossenheim, Germany (~49.4497N, 8.6552E) - matches the beacon's own comment text.
        assertEquals(49.4536, position.lat!!, 0.001)
        assertEquals(8.6676, position.lon!!, 0.001)
        assertNull("Compressed course/speed bytes were blank (\"  \") in this fixture", position.course)
        assertNull("Compressed course/speed bytes were blank (\"  \") in this fixture", position.speedKnots)
        assertEquals("LoRa APRS iGate Dossenheim", position.comment)
    }

    @Test
    fun testRealAprsFixtureViaDispatch() {
        // Default sync word (0x12, same as MeshCore) - the pre-filter must still catch it.
        val message = LoraPayloadDecoder.decode(realAprsPayload, syncWord = 0x12)
        assertTrue("Expected LoraAprsPacket via sync word 0x12", message is LoraAprsPacket)
    }

    @Test
    fun testRealAprsFixtureViaDispatchRegardlessOfSyncWord() {
        // The magic-header pre-filter runs before the sync-word switch, so it must catch this
        // payload even if it were (hypothetically) reported under a different sync word.
        val message = LoraPayloadDecoder.decode(realAprsPayload, syncWord = 0x2B)
        assertTrue("Expected LoraAprsPacket regardless of sync word", message is LoraAprsPacket)
    }

    @Test
    fun testRealMeshCoreFixtureIsNotMisdetectedAsAprs() {
        assertNull(
            "Magic-header gate must not false-positive on real MeshCore traffic",
            LoraAprsParser.tryParse(realMeshCoreAdvertPayload),
        )
        val message = LoraPayloadDecoder.decode(realMeshCoreAdvertPayload, syncWord = 0x12)
        assertTrue("MeshCore fixture should still dispatch to MeshCoreParser", message !is LoraAprsPacket)
    }

    @Test
    fun testMissingMagicHeaderReturnsNull() {
        assertNull(LoraAprsParser.tryParse("DF4IAE-10>APLRG1,WIDE1-1:!L5N-0P_A".toByteArray()))
    }

    @Test
    fun testTruncatedPayloadReturnsNull() {
        assertNull(LoraAprsParser.tryParse(byteArrayOf(0x3C, 0xFF.toByte())))
        assertNull(LoraAprsParser.tryParse(byteArrayOf(0x3C, 0xFF.toByte(), 0x01)))
    }

    @Test
    fun testMagicHeaderWithoutValidTnc2HeaderReturnsNull() {
        val payload = byteArrayOf(0x3C, 0xFF.toByte(), 0x01) + "not a tnc2 frame".toByteArray()
        assertNull(LoraAprsParser.tryParse(payload))
    }

    @Test
    fun testRandomGarbageDoesNotMatch() {
        val random = java.util.Random(42)
        repeat(50) {
            val garbage = ByteArray(64)
            random.nextBytes(garbage)
            // A payload could randomly start with the magic bytes but then fail the TNC2
            // structural check - either way, this must never throw.
            LoraAprsParser.tryParse(garbage)
        }
    }

    // ---- Mic-E and third-party traffic ----

    private fun loraAprs(tnc2: String) =
        LoraAprsParser.tryParse(byteArrayOf(0x3C, 0xFF.toByte(), 0x01) + tnc2.toByteArray(Charsets.ISO_8859_1))!!

    /** The TNC2 header's destination ("TO", SSID ignored) carries a Mic-E report's latitude. */
    @Test
    fun aMicEPacketIsDecodedWithItsDestination() {
        val micE = loraAprs("KF6DQ-5>S4QUYZ-1,WIDE1-1:'.Kel ~v/]\"6A}TM-D700").info as MicEInfo
        assertEquals(34.265, micE.lat!!, 1e-9)
        assertEquals(-118.795, micE.lon!!, 1e-9)
        assertEquals("Kenwood TM-D700", micE.device)
    }

    /** A LoRa iGate relaying another station's packet: the relay is heard, the originator is where
     *  the packet's position goes, and names the frame as the other party. */
    @Test
    fun relayedTrafficBelongsToItsOriginator() = runBlocking {
        val relayed = loraFrame(loraAprs("DB0ABC-10>APLRG1:}DL1ABC-7>APLRT1,TCPIP,DB0ABC-10*:!4903.50N/00841.75E>Car"))
        assertEquals("LoraAprs:DB0ABC-10", relayed.objectKey)
        assertEquals("LoraAprs:DL1ABC-7", relayed.secondaryObjectKey)
        // a frame that cannot be attributed names no one
        assertNull(loraFrame(relayed.data, crcValid = false).secondaryObjectKey)

        val stations = memoryObjectStore(LoraAprsStationFamily)
        applyLoraAprsFrame(stations, relayed, relayed.data as LoraAprsPacket)
        val relay = stations.liveObject("LoraAprs:DB0ABC-10")
        assertEquals(1, relay.packetCount)
        assertNull(relay.lat)
        assertNull(relay.state.comment)
        val origin = stations.liveObject("LoraAprs:DL1ABC-7")
        assertEquals(49.0583, origin.lat!!, 1e-4)
        assertEquals("Car", origin.state.comment)
        assertEquals("DL1ABC-7", ((relayed.data as LoraAprsPacket).info as ThirdPartyInfo).source)
    }
}
