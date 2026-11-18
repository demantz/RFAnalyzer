package com.mantz_it.rfanalyzer.digiext.ax25

import com.mantz_it.rfanalyzer.digiext.aprs.AprsTextMessage
import com.mantz_it.rfanalyzer.digiext.aprs.MicEInfo
import com.mantz_it.rfanalyzer.digiext.aprs.PositionReport
import com.mantz_it.rfanalyzer.digiext.aprs.ThirdPartyInfo
import com.mantz_it.rfanalyzer.digiext.db.liveObject
import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import com.mantz_it.rfanalyzer_ipc.Ax25Variant
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Packet
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Phy
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - AX.25 Parser Tests</h1>
 *
 * Module:      TestAx25Parser.kt
 * Description: Tests for [Ax25Parser] - the app-side decode of an AX.25 frame's address, control
 *              and PID fields and of its information field as APRS - and the keys [Ax25Spec]
 *              derives from it.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestAx25Parser {

    /** One 7-octet address subfield: 6 space-padded, left-shifted callsign chars, then the SSID
     *  octet (SSID nibble, extension bit, H-bit). */
    private fun address(callsign: String, ssid: Int, isLast: Boolean, hasBeenRepeated: Boolean = false): ByteArray {
        val padded = callsign.padEnd(6).take(6)
        val bytes = ByteArray(7) { ((padded.getOrElse(it) { ' ' }.code shl 1) and 0xFF).toByte() }
        var ssidByte = (ssid shl 1) and 0x1E
        if (isLast) ssidByte = ssidByte or 0x01
        if (hasBeenRepeated) ssidByte = ssidByte or 0x80
        bytes[6] = ssidByte.toByte()
        return bytes
    }

    private fun uiFrame(source: String, ssid: Int, info: String): Bytes = Bytes(
        address("APRS", 0, isLast = false) + address(source, ssid, isLast = true) +
            byteArrayOf(0x03, 0xF0.toByte()) + info.toByteArray(Charsets.ISO_8859_1)
    )

    @Test
    fun decodesTwoAddressUiFrameWithPidAndInfo() {
        val raw = uiFrame("KF6YVS", 6, "!4903.50N/07201.75W-Test")
        val message = Ax25Parser.parse(raw)

        assertEquals("APRS", message.destination?.callsign)
        assertEquals("KF6YVS-6", message.source.toString())
        assertEquals(Bytes(byteArrayOf(0x03)), message.control)
        assertEquals(0xF0, message.pid)
        assertTrue(message.aprs is PositionReport)
        val frame = Ax25Spec.packet(timestampMs = 0, frequencyHz = 0, raw = raw, phy = Ax25Phy(Ax25Variant.VHF_1200), data = message)
        assertEquals("!4903.50N/07201.75W-Test", String(frame.info.toByteArray(), Charsets.ISO_8859_1))
    }

    /**
     * On air the station is the source address: a TNC2 "FROM>TO,PATH:" split over the information
     * field would make a car (symbol `>`) with a ':' in its comment the station
     * "!4903.50N/07201.75W" and lose its position.
     */
    @Test
    fun aCarWithAColonInItsCommentKeepsItsCallsignAndPosition() {
        val message = Ax25Parser.parse(uiFrame("KF6YVS", 9, "!4903.50N/07201.75W>Bat: 12.6V"))

        val position = message.aprs as PositionReport
        assertEquals(49.0583, position.lat!!, 0.001)
        assertEquals(-72.0292, position.lon!!, 0.001)
        assertEquals('>', position.symbol)
        assertEquals("STATION:KF6YVS-9", Ax25Spec.objectKey(null, Ax25Phy(Ax25Variant.VHF_1200), message))
    }

    /** An APRS message names its addressee, whose detail then lists it ([Ax25Spec.secondaryObjectKey]). */
    @Test
    fun aMessageNamesItsAddresseeAsTheOtherParty() {
        val message = Ax25Parser.parse(uiFrame("KF6YVS", 0, ":DL1ABC-7 :hello{12"))

        assertEquals("DL1ABC-7", (message.aprs as AprsTextMessage).addressee.trim())
        val phy = Ax25Phy(Ax25Variant.VHF_1200)
        assertEquals("STATION:KF6YVS", Ax25Spec.objectKey(null, phy, message))
        assertEquals("STATION:DL1ABC-7", Ax25Spec.secondaryObjectKey(null, phy, message))
    }

    @Test
    fun decodesDigipeaterAddressesAndHBit() {
        val raw = address("APRS", 0, isLast = false) + address("KF6YVS", 6, isLast = false) +
            address("WIDE1", 1, isLast = true, hasBeenRepeated = true) + byteArrayOf(0x03, 0xF0.toByte())
        val message = Ax25Parser.parse(Bytes(raw))

        assertEquals(listOf("WIDE1-1"), message.digipeaters.map { it.toString() })
        assertEquals(true, message.digipeaters.single().hasBeenRepeated)
    }

    @Test
    fun aNonAprsFrameBelongsToItsPacketStation() {
        val raw = address("NODE", 0, isLast = false) + address("KF6YVS", 0, isLast = true) + byteArrayOf(0x03, 0xCF.toByte(), 1, 2, 3)
        val message = Ax25Parser.parse(Bytes(raw))

        assertEquals(0xCF, message.pid)
        assertNull(message.aprs)
        assertEquals("PACKET:KF6YVS", Ax25Spec.objectKey(null, Ax25Phy(Ax25Variant.HF_300), message))
    }

    // ------------------------------------------------ packet radio besides APRS (frames as heard on Net105, 2026-10-25)

    private fun frame(dest: String, destSsid: Int, src: String, srcSsid: Int, control: Int, pid: Int?, info: ByteArray = ByteArray(0),
                      command: Boolean = true): Bytes = Bytes(
        address(dest, destSsid, isLast = false, hasBeenRepeated = command) + address(src, srcSsid, isLast = true, hasBeenRepeated = !command) +
            byteArrayOf(control.toByte()) + (pid?.let { byteArrayOf(it.toByte()) } ?: ByteArray(0)) + info
    )

    private val hf = Ax25Phy(Ax25Variant.HF_300)

    @Test
    fun aConnectRequestIsAnSabmBetweenTwoPacketStations() {
        val message = Ax25Parser.parse(frame("KB9KC", 7, "KE0GB", 0, 0x3F, null))
        val type = message.frameType!!
        assertEquals("SABM", type.name)
        assertTrue(type.pollFinal)
        assertEquals(true, type.command)
        assertEquals("SABM P", type.label)
        assertNull(message.pid); assertNull(message.aprs); assertNull(message.content)
        assertEquals("PACKET:KE0GB", Ax25Spec.objectKey(null, hf, message))
        assertEquals("PACKET:KB9KC-7", Ax25Spec.secondaryObjectKey(null, hf, message))
        assertEquals("DISC", Ax25Parser.parse(frame("KB9KC", 7, "KE0GB", 0, 0x53, null)).frameType!!.name)
    }

    /** Bit 7 of an I frame's control octet is N(R) - not the modulo-128 form. */
    @Test
    fun anIFrameWithHighSequenceNumbersKeepsItsPidAndText() {
        // N(R) 5, P 0, N(S) 2, I frame: 101 0 010 0 = 0xA4
        val message = Ax25Parser.parse(frame("TSTR1", 0, "TSTR2", 0, 0xA4, 0xF0, "TEST ARTICLE TEST ARTICLE\r\n".toByteArray()))
        val type = message.frameType!!
        assertEquals("I", type.name); assertEquals(2, type.ns); assertEquals(5, type.nr)
        assertEquals(Bytes(byteArrayOf(0xA4.toByte())), message.control)
        assertEquals(0xF0, message.pid)
        // "T" would be APRS telemetry - but APRS never travels in I frames
        assertNull(message.aprs)
        assertEquals("TEST ARTICLE TEST ARTICLE", (message.content as Ax25Content.Text).text)
    }

    @Test
    fun aMailBeaconListsTheCallsignsWithMailWaiting() {
        val message = Ax25Parser.parse(frame("MAIL", 0, "KB5YZB", 1, 0x03, 0xF0, "Mail for:  KB5YZB K5DAT KC0GIS".toByteArray()))
        assertNull(message.aprs)
        assertEquals(listOf("KB5YZB", "K5DAT", "KC0GIS"), (message.content as Ax25Content.MailBeacon).callsigns)
        assertEquals("PACKET:KB5YZB-1", Ax25Spec.objectKey(null, hf, message))
        assertEquals("", Ax25Spec.secondaryObjectKey(null, hf, message))
        // BPQ32's mailbox with nothing waiting (KJ5AHO-10>MBX on 40 m)
        val none = Ax25Parser.parse(frame("MBX", 0, "KJ5AHO", 10, 0x03, 0xF0, "Mail for: None".toByteArray()))
        assertEquals(emptyList<String>(), (none.content as Ax25Content.MailBeacon).callsigns)
    }

    @Test
    fun anFbbUnprotoListIsABulletinList() {
        val info = "49642  B   1260 EQUAK         HP2DFA 261025 EARTHQUAKE ALERT M5.2 - 59 km SW of Ashkāsham, Afghanistan".toByteArray(Charsets.UTF_8)
        val entries = (Ax25Parser.parse(frame("FBB", 0, "VA2PGN", 0, 0x03, 0xF0, info)).content as Ax25Content.BulletinList).entries
        val e = entries.single()
        assertEquals(49642L, e.number); assertEquals('B', e.type); assertEquals(1260, e.sizeBytes)
        assertEquals("EQUAK", e.to); assertEquals("HP2DFA", e.from); assertEquals("261025", e.date)
        assertEquals("EARTHQUAKE ALERT M5.2 - 59 km SW of Ashkāsham, Afghanistan", e.title)
    }

    /** BPQ32 sends its ID beacon with an empty destination; the text starts like no APRS type. */
    @Test
    fun aNodeBeaconWithAnEmptyDestinationIsText() {
        val message = Ax25Parser.parse(frame("", 0, "VA2PGN", 0, 0x03, 0xF0, "VA2PGN-4  FN46QD22JD  IGATE SAINT-GEORGES QC\r".toByteArray()))
        assertEquals("", message.destination?.callsign)
        assertNull(message.aprs)
        assertEquals("VA2PGN-4  FN46QD22JD  IGATE SAINT-GEORGES QC", (message.content as Ax25Content.Text).text)
        val node = Ax25Parser.parse(frame("ID", 0, "N0HI", 7, 0x03, 0xF0, "NODE: SOLBPQ:N0HI-7, Parker, CO Area BPQ Packet Node \r".toByteArray()))
        assertNull(node.aprs)
        assertTrue(node.content is Ax25Content.Text)
    }

    /** "T" is APRS telemetry only as "T#" - not VA2PGN-4's node ID (Net105, 2026-10-25). */
    @Test
    fun aBeaconStartingWithTIsTextNotTelemetry() {
        val beacon = Ax25Parser.parse(frame("ID", 0, "VA2PGN", 4, 0x03, 0xF0,
            "This node is located at Saint-Georges qc.ca any comments to: SYSOP@EXAMPLE.COM \rThanks.\r\r".toByteArray()))
        assertNull(beacon.aprs)
        assertTrue(beacon.content is Ax25Content.Text)
        assertEquals("PACKET:VA2PGN-4", Ax25Spec.objectKey(null, hf, beacon))
        assertTrue(Ax25Parser.parse(uiFrame("WB2OSZ", 15, "T#005,199,000,255,073,123,01101001")).aprs != null)
    }

    @Test
    fun aNetRomBroadcastListsItsRoutes() {
        fun call(c: String, ssid: Int) = address(c, ssid, isLast = false)
        val info = byteArrayOf(0xFF.toByte()) + "PGNBPQ".toByteArray() +
            call("KB9KC", 7) + "KCNODE".toByteArray() + call("KB9KC", 7) + byteArrayOf(200.toByte()) +
            call("N0HI", 7) + "SOLBPQ".toByteArray() + call("KB9KC", 7) + byteArrayOf(150.toByte())
        val nodes = Ax25Parser.parse(frame("NODES", 0, "VA2PGN", 4, 0x03, 0xCF, info)).content as Ax25Content.NetRomNodes
        assertEquals("PGNBPQ", nodes.alias)
        assertEquals(listOf("KB9KC-7", "N0HI-7"), nodes.routes.map { it.callsign })
        assertEquals(listOf("KCNODE", "SOLBPQ"), nodes.routes.map { it.alias })
        assertEquals(listOf(200, 150), nodes.routes.map { it.quality })
    }

    @Test
    fun aprsStatusAndMicEStayAprs() {
        assertTrue(Ax25Parser.parse(uiFrame("F5NVY", 0, ">251639zDX: F5ZWN-3 42.28.05N 2.06.86E 339,9km")).aprs != null)
        assertTrue(Ax25Parser.parse(uiFrame("F5NVY", 1, "`xaol \u001cj/`_1\r")).aprs != null)
    }

    /** No subfield ends the address field: the frame is malformed and kept whole as information. */
    @Test
    fun anUnterminatedAddressFieldIsMalformed() {
        val raw = Bytes(address("APRS", 0, isLast = false) + byteArrayOf(1, 2, 3))
        val message = Ax25Parser.parse(raw)

        assertTrue(message.addresses.isEmpty())
        assertEquals(0, message.headerLength)
    }

    @Test
    fun framesWithNoInfoFieldDecodeWithNullPid() {
        val raw = address("APRS", 0, isLast = false) + address("KF6YVS", 0, isLast = true) + byteArrayOf(0x03)
        val message = Ax25Parser.parse(Bytes(raw))

        assertNull(message.pid)
        assertNull(message.aprs)
    }

    // ------------------------------------------------ Mic-E and third-party traffic

    private fun uiFrameTo(destination: String, source: String, ssid: Int, info: String, digipeaters: List<String> = emptyList()): Bytes {
        val path = digipeaters.mapIndexed { i, digi -> address(digi, 0, isLast = i == digipeaters.lastIndex) }
        return Bytes(
            address(destination, 2, isLast = false) + address(source, ssid, isLast = path.isEmpty()) +
                path.fold(ByteArray(0)) { all, a -> all + a } + byteArrayOf(0x03, 0xF0.toByte()) + info.toByteArray(Charsets.ISO_8859_1)
        )
    }

    private val vhf = Ax25Phy(Ax25Variant.VHF_1200)

    private fun received(raw: Bytes, timestampMs: Long = 1_000) =
        Ax25Spec.packet(timestampMs = timestampMs, frequencyHz = 144_390_000, raw = raw, phy = vhf, data = Ax25Parser.parse(raw))

    /** A real TM-D700 beacon (KF6DQ-5): the latitude is in the destination address (S4QUYZ, SSID ignored). */
    @Test
    fun aMicEFrameIsDecodedWithItsDestinationAddress() {
        val message = Ax25Parser.parse(uiFrameTo("S4QUYZ", "KF6DQ", 5, "'.Kel ~v/]\"6A}TM-D700\r", listOf("WIDE2")))
        val micE = message.aprs as MicEInfo
        assertEquals(34.265, micE.lat!!, 1e-9)
        assertEquals(-118.795, micE.lon!!, 1e-9)
        assertEquals("Kenwood TM-D700", micE.device)
        assertEquals("STATION:KF6DQ-5", Ax25Spec.objectKey(null, vhf, message))
    }

    @Test
    fun aMicEFrameGivesItsStationAPosition() = runBlocking {
        val stations = memoryObjectStore(AprsStationFamily)
        applyAx25Frame(stations, received(uiFrameTo("S4QUYZ", "KF6DQ", 5, "'.Kel ~v/]\"6A}TM-D700\r")))
        val station = stations.liveObject("STATION:KF6DQ-5")
        assertEquals(34.265, station.lat!!, 1e-9)
        assertEquals('v', station.state.symbol)
        assertEquals(98.0, station.state.courseDeg)
        assertEquals(735, station.state.altitudeFeet)
        assertEquals("TM-D700", station.state.comment)
    }

    /**
     * An iGate relaying another station's packet (N6EX-3 for W6AHM, 2026-10): the frame is the
     * relay's, its content the originator's. The relay is heard but stays where it is; the
     * originator gets the position and lists the frame as the other party.
     */
    @Test
    fun relayedTrafficBelongsToItsOriginator() = runBlocking {
        val raw = uiFrameTo("APJI23", "N6EX", 3, "}W6AHM>APRS,TCPIP,N6EX-3*:@230135z3350.28N/11818.85W_269/010g010t065r000P000p000h64b10155v6")
        val relayed = received(raw)
        assertEquals("W6AHM", (relayed.data.aprs as ThirdPartyInfo).source)
        assertEquals("STATION:N6EX-3", relayed.objectKey)
        assertEquals("STATION:W6AHM", relayed.secondaryObjectKey)

        val stations = memoryObjectStore(AprsStationFamily)
        applyAx25Frame(stations, received(uiFrameTo("APJI23", "N6EX", 3, "!3349.00N/11817.00W&iGate"), timestampMs = 500))
        applyAx25Frame(stations, relayed)

        val relay = stations.liveObject("STATION:N6EX-3")
        assertEquals(2, relay.packetCount)
        assertEquals(1_000L, relay.lastSeenMs)
        assertEquals(33.81666, relay.lat!!, 1e-4)
        assertEquals('&', relay.state.symbol)
        assertEquals("iGate", relay.state.comment)

        val origin = stations.liveObject("STATION:W6AHM")
        assertEquals(1, origin.packetCount)
        assertEquals(33.838, origin.lat!!, 1e-4)
        assertEquals('_', origin.state.symbol)
        assertEquals("W6AHM", origin.state.callsign)
    }

    /** A relayed Object goes to the Object; a relayed message keeps its originator as the other party. */
    @Test
    fun relayedObjectsAndMessages() = runBlocking {
        val stations = memoryObjectStore(AprsStationFamily)
        val relayedObject = received(uiFrameTo("APJI23", "N6EX", 3, "}W6AHM>APRS,TCPIP,N6EX-3*:;LA LOAD  *220941z3352.18N\\11749.77W? 114 In 10 Minutes"))
        assertEquals("OBJECT:LA LOAD", relayedObject.secondaryObjectKey)
        applyAx25Frame(stations, relayedObject)
        val loadObject = stations.liveObject("OBJECT:LA LOAD")
        assertEquals("W6AHM", loadObject.state.callsign)
        assertEquals("114 In 10 Minutes", loadObject.state.comment)

        // KC6YRU gating a message from KG6OWS-7 to K6XTY (2026-10)
        val message = received(uiFrameTo("APU25N", "KC6YRU", 0, "}KG6OWS-7>APK101,TCPIP,KC6YRU*::K6XTY    : o3{P"))
        assertEquals("STATION:KC6YRU", message.objectKey)
        assertEquals("STATION:KG6OWS-7", message.secondaryObjectKey)
    }

    /** The whole path from the extension's packet: interpreted, keyed, and the station folded. */
    @Test
    fun anInterpretedPacketCarriesItsKeys() {
        val packet = Ax25Packet(timestampMs = 1_000, frequencyHz = 144_800_000, raw = uiFrame("KF6YVS", 9, "!4903.50N/07201.75W>Bat: 12.6V"), phy = Ax25Phy(Ax25Variant.VHF_1200))
        val frame = Ax25Spec.interpret(packet, packet.frequencyHz).packet

        assertEquals("STATION:KF6YVS-9", frame.objectKey)
        assertEquals("", frame.secondaryObjectKey)
        assertEquals(Ax25Parser.PARSER_VERSION, frame.parserVersion)
    }
}
