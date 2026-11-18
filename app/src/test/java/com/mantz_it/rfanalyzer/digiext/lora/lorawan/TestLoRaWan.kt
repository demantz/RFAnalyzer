package com.mantz_it.rfanalyzer.digiext.lora.lorawan

import com.mantz_it.rfanalyzer.digiext.lora.loraFrame
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import kotlinx.coroutines.runBlocking
import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import com.mantz_it.rfanalyzer.digiext.lora.loraIdentityOf
import com.mantz_it.rfanalyzer.digiext.lora.LoRaWanFrame
import com.mantz_it.rfanalyzer.digiext.lora.LoRaWanMType
import com.mantz_it.rfanalyzer.digiext.lora.LoraPayloadDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - LoRaWAN Tests</h1>
 *
 * Module:      TestLoRaWan.kt
 * Description: Pure-JVM unit tests for LoRaWanParser / LoraPayloadDecoder's sync-word-0x34
 *              dispatch — no JNI/device needed, this is plain byte-array parsing. The real fixtures
 *              are LoRaWAN downlinks (inverted IQ) the extension's receiver decoded from a
 *              recording.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestLoRaWan {

    // ---- Real fixtures (decoded from the lorawan_dl_candidate_*.cf32 recordings) ----

    private val realJoinAccept = byteArrayOf(
        0x20, 0x66, 0xeb.toByte(), 0x17, 0xdd.toByte(), 0x8b.toByte(), 0x3a, 0x53,
        0x40, 0x75, 0x80.toByte(), 0x25, 0xdf.toByte(), 0x56, 0x5e, 0xb2.toByte(),
        0x62, 0xb6.toByte(), 0x99.toByte(), 0x4a, 0x57, 0xe4.toByte(), 0xd8.toByte(), 0x5c,
        0x25, 0x55, 0xea.toByte(), 0x25, 0x8e.toByte(), 0x5a, 0x20, 0x33,
        0x9c.toByte(),
    )

    private val realDataDown1 = byteArrayOf(
        0x60, 0xfd.toByte(), 0x7c, 0xf2.toByte(), 0x28, 0x20, 0x46, 0xba.toByte(),
        0x62, 0xc9.toByte(), 0x75, 0x53,
    )

    @Test
    fun testRealJoinAcceptFixture() {
        val frame = LoRaWanParser.tryParse(realJoinAccept)
        assertTrue("Expected a LoRaWanFrame", frame != null)
        frame!!

        assertEquals(LoRaWanMType.JOIN_ACCEPT, frame.mtype)
        assertEquals(0, frame.major)
        assertEquals(null, frame.devAddr)
        assertEquals(32, frame.encryptedTail.size)

        assertTrue(LoraPayloadDecoder.decode(realJoinAccept, syncWord = 0x34) is LoRaWanFrame)
    }

    @Test
    fun testRealUnconfirmedDataDownFixture() {
        val frame = LoRaWanParser.tryParse(realDataDown1)
        assertTrue("Expected a LoRaWanFrame", frame != null)
        frame!!

        assertEquals(LoRaWanMType.UNCONFIRMED_DATA_DOWN, frame.mtype)
        assertEquals(0, frame.major)
        assertEquals("28F27CFD", frame.devAddr)
        assertEquals(false, frame.adr)
        assertEquals(true, frame.ack)
        assertEquals(false, frame.fPending)
        assertEquals(0, frame.fOptsLen)
        assertEquals(47686, frame.fCnt)
        assertEquals(null, frame.fPort) // no FRMPayload - empty ACK-only downlink
        assertEquals(4, frame.encryptedTail.size) // MIC only
        assertTrue("fOptsLen=0 should produce an empty fOpts list", frame.fOpts.isEmpty())
        assertEquals(0, frame.fOptsUndecoded.size)

        assertTrue(LoraPayloadDecoder.decode(realDataDown1, syncWord = 0x34) is LoRaWanFrame)
    }

    // ---- Structural validity / edge cases ----

    @Test
    fun testDataFrameTooShortReturnsNull() {
        // MHDR + DevAddr + FCtrl + FCnt + MIC = 12 bytes minimum for a Data message.
        val mhdr = (LoRaWanMType.UNCONFIRMED_DATA_UP.ordinal shl 5).toByte()
        assertNull(LoRaWanParser.tryParse(byteArrayOf(mhdr) + ByteArray(10)))
    }

    @Test
    fun testFOptsLenInconsistentWithSizeReturnsNull() {
        // Declares fOptsLen=15 in FCtrl but the payload has no room for 15 FOpts bytes + MIC.
        val mhdr = (LoRaWanMType.UNCONFIRMED_DATA_UP.ordinal shl 5).toByte()
        val devAddr = byteArrayOf(0, 0, 0, 0)
        val fctrl = byteArrayOf(0x0F) // fOptsLen = 15
        val fCnt = byteArrayOf(0, 0)
        val mic = byteArrayOf(0, 0, 0, 0)
        val payload = byteArrayOf(mhdr) + devAddr + fctrl + fCnt + mic // no room for 15 FOpts bytes
        assertNull(LoRaWanParser.tryParse(payload))
    }

    @Test
    fun testDataFrameWithFPort() {
        val mhdr = (LoRaWanMType.CONFIRMED_DATA_UP.ordinal shl 5).toByte()
        val devAddr = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val fctrl = byteArrayOf(0x80.toByte()) // ADR=1, fOptsLen=0
        val fCnt = byteArrayOf(0x05, 0x00)
        val fPort = byteArrayOf(0x01)
        val frmPayload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val mic = byteArrayOf(0, 0, 0, 0)
        val payload = byteArrayOf(mhdr) + devAddr + fctrl + fCnt + fPort + frmPayload + mic

        val frame = LoRaWanParser.tryParse(payload)
        assertTrue(frame != null)
        frame!!
        assertEquals(LoRaWanMType.CONFIRMED_DATA_UP, frame.mtype)
        assertEquals("04030201", frame.devAddr)
        assertEquals(true, frame.adr)
        assertEquals(5, frame.fCnt)
        assertEquals(1, frame.fPort)
        assertEquals(7, frame.encryptedTail.size) // FPort(1)+FRMPayload(2)+MIC(4)
    }

    // ---- FOpts MAC-command walking ----

    /** Builds a minimal Data-frame payload with the given FOpts bytes and no FPort/FRMPayload. */
    private fun buildDataFramePayload(mtype: LoRaWanMType, fOptsBytes: ByteArray): ByteArray {
        val mhdr = (mtype.ordinal shl 5).toByte()
        val devAddr = byteArrayOf(0, 0, 0, 0)
        val fctrl = byteArrayOf((fOptsBytes.size and 0xF).toByte()) // fOptsLen bits 0-3, other bits 0
        val fCnt = byteArrayOf(0, 0)
        val mic = byteArrayOf(0, 0, 0, 0)
        return byteArrayOf(mhdr) + devAddr + fctrl + fCnt + fOptsBytes + mic
    }

    @Test
    fun testFOptsSingleLinkCheckReqUplink() {
        // cid=0x02 (LinkCheckReq), uplink -> 0-byte payload per TS001 Table 14.
        val payload = buildDataFramePayload(LoRaWanMType.UNCONFIRMED_DATA_UP, fOptsBytes = byteArrayOf(0x02))
        val frame = LoRaWanParser.tryParse(payload)
        assertTrue(frame != null)
        frame!!
        assertEquals(1, frame.fOpts.size)
        assertEquals(0x02, frame.fOpts[0].cid)
        assertEquals(0, frame.fOpts[0].payload.size)
        assertEquals(0, frame.fOptsUndecoded.size)
    }

    @Test
    fun testFOptsMultipleCommandsSequential() {
        // cid=0x03 (LinkADRAns, uplink, 1-byte payload) followed by cid=0x06 (DevStatusAns,
        // uplink, 2-byte payload), concatenated back to back with no separator.
        val fOpts = byteArrayOf(0x03, 0x11, 0x06, 0x22, 0x33)
        val payload = buildDataFramePayload(LoRaWanMType.UNCONFIRMED_DATA_UP, fOptsBytes = fOpts)
        val frame = LoRaWanParser.tryParse(payload)
        assertTrue(frame != null)
        frame!!
        assertEquals(2, frame.fOpts.size)
        assertEquals(0x03, frame.fOpts[0].cid)
        assertEquals(Bytes(byteArrayOf(0x11)), frame.fOpts[0].payload)
        assertEquals(0x06, frame.fOpts[1].cid)
        assertEquals(Bytes(byteArrayOf(0x22, 0x33)), frame.fOpts[1].payload)
        assertEquals(0, frame.fOptsUndecoded.size)
    }

    @Test
    fun testFOptsAnswersWithoutPayload() {
        // DutyCycleAns (0x04) and DeviceTimeReq (0x0D) carry no payload uplink (LoRaWAN 1.0.3 §5),
        // so the LinkADRAns after them is still found.
        val payload = buildDataFramePayload(LoRaWanMType.UNCONFIRMED_DATA_UP, fOptsBytes = byteArrayOf(0x04, 0x0D, 0x03, 0x07))
        val frame = LoRaWanParser.tryParse(payload)!!
        assertEquals(listOf(0x04, 0x0D, 0x03), frame.fOpts.map { it.cid })
        assertEquals(listOf(0, 0, 1), frame.fOpts.map { it.payload.size })
        assertEquals(0, frame.fOptsUndecoded.size)
    }

    @Test
    fun testFOptsDeviceTimeAnsDownlink() {
        // DeviceTimeAns: 4 bytes GPS seconds + 1 byte fractional second.
        val payload = buildDataFramePayload(LoRaWanMType.UNCONFIRMED_DATA_DOWN, fOptsBytes = byteArrayOf(0x0D, 1, 2, 3, 4, 5))
        val frame = LoRaWanParser.tryParse(payload)!!
        assertEquals(1, frame.fOpts.size)
        assertEquals(5, frame.fOpts[0].payload.size)
        assertEquals(0, frame.fOptsUndecoded.size)
    }

    @Test
    fun testFOptsDirectionChangesCommandLength() {
        // cid=0x03 is LinkADRAns (1-byte payload) uplink, LinkADRReq (4-byte payload) downlink.
        val uplinkPayload = buildDataFramePayload(LoRaWanMType.UNCONFIRMED_DATA_UP, fOptsBytes = byteArrayOf(0x03, 0xAA.toByte()))
        val uplinkFrame = LoRaWanParser.tryParse(uplinkPayload)
        assertTrue(uplinkFrame != null)
        assertEquals(1, uplinkFrame!!.fOpts.size)
        assertEquals(1, uplinkFrame.fOpts[0].payload.size)

        val downlinkPayload = buildDataFramePayload(
            LoRaWanMType.UNCONFIRMED_DATA_DOWN,
            fOptsBytes = byteArrayOf(0x03, 0x01, 0x02, 0x03, 0x04),
        )
        val downlinkFrame = LoRaWanParser.tryParse(downlinkPayload)
        assertTrue(downlinkFrame != null)
        assertEquals(1, downlinkFrame!!.fOpts.size)
        assertEquals(4, downlinkFrame.fOpts[0].payload.size)
        assertEquals(Bytes(byteArrayOf(0x01, 0x02, 0x03, 0x04)), downlinkFrame.fOpts[0].payload)
    }

    @Test
    fun testFOptsUnrecognizedCidStopsWalkingLeavesRemainderOpaque() {
        // Recognized LinkCheckReq(0x02, uplink, 0-byte) followed by an unrecognized
        // proprietary cid (0x80) - walking should stop there, not guess a length.
        val fOpts = byteArrayOf(0x02, 0x80.toByte(), 0xAA.toByte(), 0xBB.toByte())
        val payload = buildDataFramePayload(LoRaWanMType.UNCONFIRMED_DATA_UP, fOptsBytes = fOpts)
        val frame = LoRaWanParser.tryParse(payload)
        assertTrue(frame != null)
        frame!!
        assertEquals(1, frame.fOpts.size)
        assertEquals(0x02, frame.fOpts[0].cid)
        assertEquals(Bytes(byteArrayOf(0x80.toByte(), 0xAA.toByte(), 0xBB.toByte())), frame.fOptsUndecoded)
    }

    @Test
    fun testFOptsLenZeroProducesEmptyList() {
        val payload = buildDataFramePayload(LoRaWanMType.UNCONFIRMED_DATA_UP, fOptsBytes = ByteArray(0))
        val frame = LoRaWanParser.tryParse(payload)
        assertTrue(frame != null)
        frame!!
        assertTrue(frame.fOpts.isEmpty())
        assertEquals(0, frame.fOptsUndecoded.size)
    }

    @Test
    fun testJoinRequestAndProprietaryOnlyCarryMTypeAndMajor() {
        for (mtype in listOf(LoRaWanMType.JOIN_REQUEST, LoRaWanMType.REJOIN_REQUEST, LoRaWanMType.PROPRIETARY)) {
            val mhdr = ((mtype.ordinal shl 5) or 0x01).toByte() // major=1
            val body = ByteArray(20) { it.toByte() }
            val frame = LoRaWanParser.tryParse(byteArrayOf(mhdr) + body)
            assertTrue("Expected a LoRaWanFrame for $mtype", frame != null)
            frame!!
            assertEquals(mtype, frame.mtype)
            assertEquals(1, frame.major)
            assertEquals(null, frame.devAddr)
            assertEquals(20, frame.encryptedTail.size)
        }
    }

    @Test
    fun testEmptyPayloadReturnsNull() {
        assertNull(LoRaWanParser.tryParse(ByteArray(0)))
    }

    @Test
    fun testDispatchFallsBackToRawForStructurallyInvalidPayload() {
        // A Data-type MHDR (bits 7-5 = 010 = UNCONFIRMED_DATA_UP) with too few bytes for a
        // valid FHDR+MIC (< 12) is a structural rejection, not a crash - the dispatcher
        // should fall through to RawLoraPayload rather than emitting a bogus LoRaWanFrame.
        val tooShortDataFrame = byteArrayOf(0x40, 0x01, 0x02)
        val decoded = LoraPayloadDecoder.decode(tooShortDataFrame, syncWord = 0x34)
        assertTrue("Expected fallback to RawLoraPayload, got $decoded", decoded !is LoRaWanFrame)
    }

    /** MHDR 0x00, then JoinEUI, DevEUI and DevNonce little-endian, then the MIC. */
    private val joinRequest = byteArrayOf(0x00) +
        byteArrayOf(0x08, 0x07, 0x06, 0x05, 0x04, 0x03, 0x02, 0x01) +                       // JoinEUI 0102030405060708
        byteArrayOf(0xEF.toByte(), 0xCD.toByte(), 0xAB.toByte(), 0x89.toByte(), 0x67, 0x45, 0x23, 0x70) + // DevEUI 70234567 89ABCDEF
        byteArrayOf(0x34, 0x12) +                                                           // DevNonce 0x1234
        byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

    @Test
    fun testJoinRequestDecodesEuisAndNonce() {
        val frame = LoRaWanParser.tryParse(joinRequest)!!
        assertEquals(LoRaWanMType.JOIN_REQUEST, frame.mtype)
        assertEquals("0102030405060708", frame.joinEui)
        assertEquals("7023456789ABCDEF", frame.devEui)
        assertEquals(0x1234, frame.devNonce)
        assertNull(frame.devAddr)
        assertEquals("LoRaWAN:EUI:7023456789ABCDEF", loraIdentityOf(frame))
    }

    @Test
    fun testJoinRequestWithHighBitEuiFormatsUnsigned() {
        val payload = joinRequest.copyOf().also { it[16] = 0xF0.toByte() } // DevEUI's top byte
        val frame = LoRaWanParser.tryParse(payload)!!
        assertEquals("LoRaWAN:EUI:F023456789ABCDEF", loraIdentityOf(frame))
        assertEquals("EUI …89ABCDEF", lorawanDeviceName(frame.devAddr, frame.devEui))
    }

    @Test
    fun testJoinRequestOfAnotherLengthStaysOpaque() {
        val frame = LoRaWanParser.tryParse(joinRequest + byteArrayOf(0x00))!!
        assertNull(frame.devEui)
        assertEquals("", loraIdentityOf(frame))
    }

    /** A joining device gets its own row, keyed by DevEUI; repeated joins count on it. */
    @Test
    fun testJoinRequestsFoldIntoOneDevicePerDevEui() = runBlocking {
        val store = memoryObjectStore(LoRaWanDeviceFamily)
        val frame = LoRaWanParser.tryParse(joinRequest)!!
        for (i in 1..2) {
            applyLoRaWanFrame(store, loraFrame(frame, id = i.toLong(), ts = 1_000L * i, frequencyHz = 868_100_000L), frame)
        }
        store.publish()
        val device = store.live.value.single()
        assertEquals("LoRaWAN:EUI:7023456789ABCDEF", device.key)
        assertEquals("EUI …89ABCDEF", device.displayName)
        assertEquals(2, device.packetCount)
        assertEquals("7023456789ABCDEF", device.state.devEui)
        assertEquals("0102030405060708", device.state.joinEui)
        assertNull(device.state.devAddr)
    }

    /** A device's uplinks and the network's downlinks to it keep their own counters and signals. */
    @Test
    fun testUplinkAndDownlinkStayApart() = runBlocking {
        fun data(mtype: LoRaWanMType, fCnt: Int, fPort: Int): LoRaWanFrame {
            val mhdr = (mtype.ordinal shl 5).toByte()
            val payload = byteArrayOf(mhdr, 0x01, 0x02, 0x03, 0x04, 0x00, fCnt.toByte(), (fCnt shr 8).toByte(), fPort.toByte(), 0x55, 0, 0, 0, 0)
            return LoRaWanParser.tryParse(payload)!!
        }
        val store = memoryObjectStore(LoRaWanDeviceFamily)
        val up = data(LoRaWanMType.UNCONFIRMED_DATA_UP, fCnt = 300, fPort = 2)
        val down = data(LoRaWanMType.UNCONFIRMED_DATA_DOWN, fCnt = 7, fPort = 1)
        applyLoRaWanFrame(store, loraFrame(up, id = 1, ts = 1_000), up)
        applyLoRaWanFrame(store, loraFrame(down, id = 2, ts = 2_000), down)
        store.publish()
        val device = store.live.value.single().state
        assertEquals(LoRaWanMType.UNCONFIRMED_DATA_UP, device.mtype)
        assertEquals(300, device.fCnt)
        assertEquals(2, device.lastFPort)
        assertEquals(LoRaWanMType.UNCONFIRMED_DATA_DOWN, device.downlinkMType)
        assertEquals(7, device.downlinkFCnt)
        assertEquals(1, device.downlinkFPort)
    }
}
