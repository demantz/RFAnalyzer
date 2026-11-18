package com.mantz_it.rfanalyzer.digiext.lora.meshcom

import com.mantz_it.rfanalyzer.digiext.interpret
import com.mantz_it.rfanalyzer.digiext.lora.LoraPayloadDecoder
import com.mantz_it.rfanalyzer.digiext.lora.LoraSpec
import com.mantz_it.rfanalyzer.digiext.lora.MeshComAck
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrame
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrameType
import com.mantz_it.rfanalyzer.digiext.lora.MeshComHey
import com.mantz_it.rfanalyzer.digiext.lora.MeshComNeighbourReport
import com.mantz_it.rfanalyzer.digiext.lora.MeshComPosition
import com.mantz_it.rfanalyzer.digiext.lora.MeshComSignalReport
import com.mantz_it.rfanalyzer.digiext.lora.MeshComText
import com.mantz_it.rfanalyzer.digiext.lora.MeshComTextKind
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPacket
import com.mantz_it.rfanalyzer.digiext.lora.RawLoraPayload
import com.mantz_it.rfanalyzer.digiext.lora.MESHCOM_FLAG_MESH
import com.mantz_it.rfanalyzer.digiext.lora.MESHCOM_FLAG_SERVER
import com.mantz_it.rfanalyzer.digiext.lora.MESHCOM_FLAG_TRACK
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPacket
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPhy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - MeshCom Parser Tests</h1>
 *
 * Module:      TestMeshComParser.kt
 * Description: MeshCom frames: real ones received on 433.175 MHz in 2026-11, and frames built
 *              here for what the recording didn't contain.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestMeshComParser {

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    /** A frame as a MeshCom node sends it (hw 43, mod 0x88, fw 4.40a), checksum included. */
    private fun frame(type: Char, path: String, payload: String, msgId: Long = 0x12345678, hopByte: Int = 0x14): ByteArray {
        val head = byteArrayOf(type.code.toByte(), msgId.toByte(), (msgId shr 8).toByte(), (msgId shr 16).toByte(), (msgId shr 24).toByte(), hopByte.toByte()) +
            (path + type + payload).toByteArray(Charsets.UTF_8) + byteArrayOf(0, 43, 0x88.toByte())
        val sum = head.sumOf { it.toInt() and 0xFF }
        return head + byteArrayOf((sum shr 8).toByte(), sum.toByte(), 40, (0x80 or 43).toByte(), 'a'.code.toByte(), 0x7E)
    }

    private fun parse(bytes: ByteArray) = MeshComParser.tryParse(bytes) as MeshComFrame

    // ---- real frames ----

    @Test
    fun aPositionBeaconAndItsRelayedCopy() {
        val direct = parse(hex("21468315A452444A3150572D31353E2A21343931362E30304E2F30303833362E3137452D4261747465727920506F776572656423444A3150572D31352F423D3038352F413D3030303530352F4E362F523D393B3236323B002B8816DB28AB617E"))
        assertEquals(MeshComFrameType.POSITION, direct.frameType)
        assertEquals(0xA4158346L, direct.msgId)
        assertEquals(2, direct.hopsLeft)
        assertEquals(MESHCOM_FLAG_TRACK or MESHCOM_FLAG_MESH, direct.flags)
        assertEquals(listOf("DJ1PW-15"), direct.sourcePath)
        assertEquals("*", direct.destination)
        assertTrue(direct.checksumValid)
        assertEquals(43, direct.hardware)
        assertEquals("Heltec V3", meshComHardwareName(direct.hardware))
        assertEquals(8, direct.country)
        assertEquals(8, direct.modulation)
        assertEquals("4.40a", meshComFirmware(direct))
        assertEquals(43, direct.lastHardware)
        val position = direct.info as MeshComPosition
        assertEquals(49 + 16.00 / 60, position.latitude, 1e-9)
        assertEquals(8 + 36.17 / 60, position.longitude, 1e-9)
        assertEquals('/', position.symbolTable)
        assertEquals('-', position.symbolCode)
        assertEquals("Battery Powered", position.comment)
        assertEquals("DJ1PW-15", position.name)
        assertEquals(85, position.batteryPercent)
        assertEquals(505, position.altitudeFt)
        assertEquals(6, position.neighbours)
        assertEquals(listOf(9, 262), position.groups)

        val relayed = parse(hex("21468315A4D1444A3150572D31352C444C315943532D31323E2A21343931362E30304E2F30303833362E3137452D4261747465727920506F776572656423444A3150572D31352F423D3038352F413D3030303530352F4E362F523D393B3236323B002B8819C62883617E"))
        assertEquals(direct.msgId, relayed.msgId) // the same message
        assertEquals(1, relayed.hopsLeft)
        assertEquals(MESHCOM_FLAG_SERVER or MESHCOM_FLAG_TRACK or MESHCOM_FLAG_MESH, relayed.flags) // the gateway relay adds "server"
        assertEquals("DJ1PW-15", relayed.origin)
        assertEquals("DL1YCS-12", relayed.lastTransmitter)
        assertEquals(1, relayed.hops)
        assertEquals(3, relayed.lastHardware) // the relay, a TTGO T-LoRa V2 1.6
        assertEquals(direct.info, relayed.info)
    }

    @Test
    fun theServersTimeBeacon() {
        val time = parse(hex("3A5EE3F56AB24F45315841522D33332C444C315943532D31323E2A3A7B4345547D323032362D31312D31362030393A34353A33310000880EF00083237E"))
        assertEquals(listOf("OE1XAR-33", "DL1YCS-12"), time.sourcePath)
        assertEquals(MeshComText("2026-11-16 09:45:31", MeshComTextKind.TIME), time.info)
        assertEquals(0, time.hardware) // the MeshCom server
        assertNull(meshComFirmware(time))
        assertNull(time.firmwareSubVersion) // '#'
        assertTrue(time.checksumValid)
    }

    @Test
    fun heyBeacons() {
        val gateway = parse(hex("402D00735014444C315943532D31323E48474052343B00038805DD2883617E"))
        assertEquals(MeshComFrameType.HEY, gateway.frameType)
        assertEquals("HG", gateway.destination) // from a gateway
        assertEquals(MeshComHey(neighbours = 4), gateway.info)
        val relayed = parse(hex("40ED8244AC93444332534B2D31322C444C315943532D31323E48474052333B352C3131352C2D363B002B880BC72383707E"))
        assertEquals(MeshComHey(neighbours = 3, reports = listOf(MeshComSignalReport(5, -115, -6))), relayed.info)
        // an originator before 4.35p sends "R" alone
        assertEquals(MeshComHey(), parse(hex("4029C09F7E14444B3855532D31323E4847405200048806442384647E")).info)
    }

    @Test
    fun aCorruptedFrameKeepsItsStructureButFailsTheChecksum() {
        // received with a LoRa CRC error
        val corrupted = parse(hex("40B9B3412892444831484F2D312C4446314941502D312C444C315943532D31323E484052362C34372C373B342C3130332C343B002E830D1C2383697E"))
        assertFalse(corrupted.checksumValid)
        assertEquals("H", corrupted.destination)
    }

    @Test
    fun aGatewayAck() {
        assertEquals(MeshComAck(ackId = 0x55CAE812, hopsLeft = 2, ackedMsgId = 0x3BCD01F8), MeshComParser.tryParse(hex("4112E8CA5582F801CD3B0100")))
    }

    @Test
    fun sharingMeshtasticsSyncWordTheStructureDecides() {
        for (h in listOf("402D00735014444C315943532D31323E48474052343B00038805DD2883617E", "4112E8CA5581F801CD3B0100"))
            assertFalse(LoraPayloadDecoder.decode(hex(h), 0x2B) is MeshtasticPacket)
        // a real Meshtastic frame (TestMeshtastic) stays Meshtastic
        val meshtastic = hex("ffffffffb02583699d6ed8eee40800dc134d038969bb6a46978f0e301c82079600495c08886bb861a2090f7ba24c6ed0c320bd6dcf9d10557626f2")
        assertNull(MeshComParser.tryParse(meshtastic))
        assertTrue(LoraPayloadDecoder.decode(meshtastic, 0x2B) is MeshtasticPacket)
    }

    /** Received on 2026-11-16 with a failed LoRa CRC: bit errors in the path or its separators. */
    @Test
    fun aDamagedFrameIsNotMeshtastic() {
        val damaged = listOf(
            // "DJ1PW-15,DL1YCS=12>*!4916.00N/00.36..4v.Battery Powhred..." - '=' in a callsign
            "216a8315a4d1444a3150572d31352c444c315943533d31323e2a21343931362e30304e2f30308833362e0234761d4261747465727920506f7768" +
                "72656443144a5150572d313529403d303c322f413d30353b3530352f4e3627523db93b3236323b002b8819f82883217e",
            // "DO5MB-1,DF1IAP-1,DJ1PW-..3.!4921.12N/00841.16E" - the '>' lost
            "2139809ad110444f354d422d312c4446314941502d312c444a3150572de1e833fa21343932312e31324e2f30303834312e3136452d2f423d31b0" +
                "742f49753830303532322f4e382f523d3236323b32363b000c88737d1898614e",
        ).map(::hex)
        for (frame in damaged) {
            assertNull(MeshComParser.tryParse(frame))
            assertTrue(MeshComParser.looksDamaged(frame))
            assertEquals(RawLoraPayload, LoraPayloadDecoder.decode(frame, 0x2B, intact = false))
        }
        // with a matching CRC it is left to Meshtastic: an intact MeshCom frame never fails that way
        assertTrue(LoraPayloadDecoder.decode(damaged[0], 0x2B) is MeshtasticPacket)
        // a Meshtastic frame with a failed CRC stays Meshtastic: its header is binary
        val meshtastic = hex("ffffffffb02583699d6ed8eee40800dc134d038969bb6a46978f0e301c82079600495c08886bb861a2090f7ba24c6ed0c320bd6dcf9d10557626f2")
        assertFalse(MeshComParser.looksDamaged(meshtastic))
        val directMessage = meshtastic.copyOf().also { it[0] = '!'.code.toByte() }
        assertFalse(MeshComParser.looksDamaged(directMessage))
        assertTrue(LoraPayloadDecoder.decode(directMessage, 0x2B, intact = false) is MeshtasticPacket)
    }

    /** The servers' time beacons: the clock as sent, whatever zone it is in. */
    @Test
    fun timeBeaconsAsSent() {
        assertEquals(MeshComText("16.11.26 13:20:39", MeshComTextKind.TIME), parse(frame(':', "OE1XAR-63>*", "{CET}<16.11.26 13:20:39>")).info)
        assertEquals(MeshComText("2026-11-16 12:10:32", MeshComTextKind.TIME), parse(frame(':', "OE1XAR-63>*", "{CET}2026-11-16 12:10:32")).info)
    }

    // ---- built frames ----

    @Test
    fun aDirectMessageAndItsAcknowledgement() {
        val dm = parse(frame(':', "DK5EN-98>DK5EN-90", "Hallo {aus} Heidelberg{902", msgId = 0x1AE1E386))
        assertEquals(MeshComText("Hallo {aus} Heidelberg", MeshComTextKind.CHAT, 902), dm.info)
        assertEquals(902L, dm.msgId and 0x3FFL) // the number is the message counter
        val ack = parse(frame(':', "DK5EN-90>DK5EN-98", "DK5EN-98 :ack902"))
        assertEquals(MeshComTextKind.ACK, (ack.info as MeshComText).kind)
        assertEquals(902, (ack.info as MeshComText).ackNumber)
        // a group text keeps a trailing "{123"
        assertEquals(MeshComText("Gruppe{123"), parse(frame(':', "DL1YCS-12>262", "Gruppe{123")).info)
        assertEquals("262", parse(frame(':', "DL1YCS-12>262", "x")).destination)
        assertEquals(listOf("F1ZKT-12", "*"), parse(frame(':', "DL1YCS-12>F1ZKT-12,*", "via")).destPath)
    }

    @Test
    fun aWeatherStationsPosition() {
        val wx = parse(frame('!', "DB0MMR-1>*", "4808.36N/01240.11ErDB0MMR Schrank#Repeater WX/B=100/A=001749/N6/P=956.8/H=64.3/T=19.7/O=12.9/F=481/Q=1019.0/G=256.0/V=3")).info as MeshComPosition
        assertEquals("DB0MMR Schrank", wx.comment)
        assertEquals("Repeater WX", wx.name)
        assertEquals('r', wx.symbolCode)
        assertEquals(956.8f, wx.pressureHpa)
        assertEquals(64.3f, wx.humidityPercent)
        assertEquals(19.7f, wx.temperatureC)
        assertEquals(12.9f, wx.temperature2C)
        assertEquals(1019.0f, wx.qnhHpa)
        assertEquals(256.0f, wx.gasKohm)
        assertEquals(6, wx.neighbours)
        // southern and western hemisphere, no comment
        val sw = parse(frame('!', "LU1ABC-1>*", "3436.50S/05822.20W#")).info as MeshComPosition
        assertEquals(-(34 + 36.5 / 60), sw.latitude, 1e-9)
        assertEquals(-(58 + 22.2 / 60), sw.longitude, 1e-9)
        assertNull(sw.comment)
    }

    @Test
    fun aNeighbourReport() {
        val report = parse(frame('@', "DK5EN-91>HN", "R5;N3+;DL1YCS-12,-6.5;DK5EN-90,3;DC2SK-12,-11")).info as MeshComNeighbourReport
        assertEquals(5, report.heard)
        assertTrue(report.more)
        assertEquals(listOf("DL1YCS-12", "DK5EN-90", "DC2SK-12"), report.neighbours.map { it.callsign })
        assertEquals(-6.5f, report.neighbours[0].snrDb)
    }

    /** Every kind is stored as the [com.mantz_it.rfanalyzer.digiext.lora.LoraSpec] row and reads back unchanged. */
    @Test
    fun everyKindRoundTripsThroughItsRow() {
        val payloads = listOf(
            hex("21468315A452444A3150572D31353E2A21343931362E30304E2F30303833362E3137452D4261747465727920506F776572656423444A3150572D31352F423D3038352F413D3030303530352F4E362F523D393B3236323B002B8816DB28AB617E"),
            hex("4112E8CA5582F801CD3B0100"),
            hex("402D00735014444C315943532D31323E48474052343B00038805DD2883617E"),
            frame(':', "DK5EN-98>DK5EN-90", "Hallo{902"),
            frame('@', "DK5EN-91>HN", "R5;N3+;DL1YCS-12,-6.5;DK5EN-90,3"),
        )
        for (payload in payloads) {
            val packet = LoraPacket(timestampMs = 1_000, frequencyHz = 433_175_000, raw = Bytes(payload), phy = LoraPhy(11, 2, 250_000, hasCrc = true, crcValid = true, syncWord = 0x2B))
            val interpreted = interpret(packet, packet.frequencyHz)
            assertTrue(interpreted.packet.data is MeshComFrame || interpreted.packet.data is MeshComAck)
            assertEquals(interpreted.packet.copy(rowId = 42), LoraSpec.fromRow(interpreted.row().copy(id = 42)))
        }
    }

    /** Every prefix of a real frame and random bytes behind a valid start: no exception, ever. */
    @Test
    fun truncatedOrGarbledFramesNeverThrow() {
        val real = hex("21468315A452444A3150572D31353E2A21343931362E30304E2F30303833362E3137452D4261747465727920506F776572656423444A3150572D31352F423D3038352F413D3030303530352F4E362F523D393B3236323B002B8816DB28AB617E")
        for (n in 0..real.size) MeshComParser.tryParse(real.copyOf(n))
        val nul = real.indexOf(0)
        assertNull("ends inside the checksum", MeshComParser.tryParse(real.copyOf(nul + 4)))
        val rnd = java.util.Random(1)
        repeat(20_000) {
            val b = ByteArray(16 + rnd.nextInt(80)).also(rnd::nextBytes)
            System.arraycopy(real, 0, b, 0, minOf(b.size, 6 + rnd.nextInt(30)))
            MeshComParser.tryParse(b)
        }
    }

    @Test
    fun notMeshCom() {
        assertNull(MeshComParser.tryParse(frame(':', "not a call>*", "x")))         // no callsign
        assertNull(MeshComParser.tryParse(frame('?', "DL1YCS-12>*", "x")))          // unknown type
        assertNull(MeshComParser.tryParse(hex("3a00000000141234")))                  // too short
        val noNul = frame(':', "DL1YCS-12>*", "x").let { it.copyOf(it.indexOf(0.toByte())) }
        assertNull(MeshComParser.tryParse(noNul + ByteArray(8) { 0x41 }))
    }
}
