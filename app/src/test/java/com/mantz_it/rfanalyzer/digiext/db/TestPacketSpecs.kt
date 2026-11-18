package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer.digiext.familyOf
import com.mantz_it.rfanalyzer.digiext.interpret
import com.mantz_it.rfanalyzer.digiext.k2k.K2kPhy
import com.mantz_it.rfanalyzer.digiext.k2k.K2kText
import com.mantz_it.rfanalyzer.digiext.k2k.RttySpec
import com.mantz_it.rfanalyzer.digiext.specOf
import com.mantz_it.rfanalyzer.digiext.toExportJson
import com.mantz_it.rfanalyzer_ipc.Ax25Variant
import com.mantz_it.rfanalyzer_ipc.digiext.AdsbPacket
import com.mantz_it.rfanalyzer_ipc.digiext.AircraftIdPayload
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Packet
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Phy
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Ft8Packet
import com.mantz_it.rfanalyzer_ipc.digiext.Ft8Phy
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPacket
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPhy
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPacket
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPhy
import com.mantz_it.rfanalyzer_ipc.digiext.R09Packet
import com.mantz_it.rfanalyzer_ipc.digiext.R09Phy
import com.mantz_it.rfanalyzer_ipc.digiext.RdsEvent
import com.mantz_it.rfanalyzer_ipc.digiext.RdsPacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import com.mantz_it.rfanalyzer.digiext.r09.R09Spec
import org.junit.Test

/**
 * <h1>RF Analyzer - Packet Spec Tests</h1>
 *
 * Module:      TestPacketSpecs.kt
 * Description: Pins the stored form of every mode's packets and the registry that maps modes to
 *              their specs.
 *
 * A row's `phyJson` and `payloadJson` are a storage format, not an implementation detail: renaming
 * a property of a phy or data class, or changing a default (rows store only non-default values),
 * silently changes what is written and how existing rows read. The compiler cannot catch that, so
 * the exact phy JSON is asserted here.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestPacketSpecs {

    private fun address(call: String, ssid: Int, last: Boolean) =
        call.padEnd(6).map { (it.code shl 1).toByte() }.toByteArray() + byteArrayOf(((ssid shl 1) or (if (last) 1 else 0)).toByte())

    private val samples: List<DigitalPacket> = listOf(
        PocsagPacket(timestampMs = 1_000, frequencyHz = 466_075_000, raw = Bytes(byteArrayOf(0x12, 0x34, 0x56)), phy = PocsagPhy(1234567, 3, 1200, 21)),
        LoraPacket(timestampMs = 2_000, frequencyHz = 869_525_000, raw = Bytes(ByteArray(16) { it.toByte() }), phy = LoraPhy(8, 1, 62_500, hasCrc = true, crcValid = false)),
        Ax25Packet(
            timestampMs = 3_000, frequencyHz = 144_800_000, phy = Ax25Phy(Ax25Variant.VHF_1200),
            raw = Bytes(address("APRS", 0, false) + address("DF4IAE", 9, true) + byteArrayOf(0x03, 0xF0.toByte()) + ">QRV".toByteArray()),
        ),
        R09Packet(
            timestampMs = 4_000, frequencyHz = 170_200_000, raw = Bytes(byteArrayOf(0x91.toByte(), 0x0A, 0x42, 0x31)),
            phy = R09Phy(baudRate = 1200, snrDb = 7.5f, frequencyOffsetHz = -812f, deviationHz = 2150f),
        ),
        // free text "TNX FER QSO" (77 message bits, the CRC left 0)
        Ft8Packet(
            timestampMs = 5_000, frequencyHz = 14_075_500, phy = Ft8Phy(snr = -12.5f, timeDeltaMs = 320),
            raw = Bytes(byteArrayOf(0x00, 0x0E, 0x80.toByte(), 0x8D.toByte(), 0x84.toByte(), 0x81.toByte(), 0xF2.toByte(), 0x78, 0x0E, 0x00, 0x00, 0x00)),
        ),
        AdsbPacket(
            timestampMs = 6_000, frequencyHz = 1_090_000_000, raw = Bytes(byteArrayOf(0x8D.toByte(), 0x3C, 0x64, 0x44) + ByteArray(10)),
            payload = AircraftIdPayload(typeCode = 4, category = 3, callsign = "DLH123"),
        ),
        RdsPacket(timestampMs = 7_000, frequencyHz = 99_900_000, raw = Bytes(byteArrayOf(0xD3.toByte(), 0xA1.toByte(), 0x25, 0x40, 0, 0, 0, 0)), events = listOf(RdsEvent.RadioText("Hello"))),
    )

    @Test
    fun everyModeHasItsSpecAndEveryKindItsFamily() {
        DigitalMode.entries.forEach { assertEquals(it, specOf(it).mode) }
        ObjectKind.entries.forEach { assertEquals(it, familyOf(it).kind) }
    }

    /** A packet read back from its row is the packet that was stored. */
    @Test
    fun everyModeRoundTripsThroughItsRow() {
        samples.forEach { sample ->
            val stored = interpret(sample, sample.frequencyHz).packet
            val read = specOf(sample.mode).fromRow(interpret(sample, sample.frequencyHz).row().copy(id = 42))
            assertEquals(sample.mode.name, stored.copy(rowId = 42), read)
        }
        val transmission = RttySpec.packet(
            timestampMs = 1_000, frequencyHz = 14_080_000, raw = null, endTimestampMs = 9_000,
            phy = K2kPhy(45.45f, 170f), data = K2kText("CQ DE DF4IAE", fromCallsign = "DF4IAE"),
        )
        assertEquals(transmission.copy(rowId = 7), RttySpec.fromRow(RttySpec.toRow(transmission).copy(id = 7)))
    }

    @Test
    fun phyIsStoredWithReadableKeys() {
        val phyJson = samples.associate { it.mode to interpret(it, it.frequencyHz).row().phyJson }
        assertEquals("""{"address":1234567,"function":3,"baudRate":1200,"bitCount":21}""", phyJson[DigitalMode.POCSAG])
        assertEquals("""{"spreadingFactor":8,"codingRate":1,"bandwidthHz":62500,"hasCrc":true,"crcValid":false}""", phyJson[DigitalMode.LORA])
        assertEquals("""{"variant":"VHF_1200"}""", phyJson[DigitalMode.AX25])
        assertEquals("""{"snr":-12.5,"timeDeltaMs":320}""", phyJson[DigitalMode.FT8])
        assertEquals("""{"baudRate":1200,"snrDb":7.5,"frequencyOffsetHz":-812.0,"deviationHz":2150.0}""", phyJson[DigitalMode.R09])
        // Everything ADS-B and RDS know beyond their content is in the raw bits.
        assertNull(phyJson[DigitalMode.ADSB])
        assertNull(phyJson[DigitalMode.RDS])
        assertEquals(
            """{"baudRate":45.45,"frequencyShift":170.0}""",
            RttySpec.toRow(RttySpec.packet(0, 0, null, K2kPhy(45.45f, 170f), K2kText("x"))).phyJson,
        )
        assertEquals(
            """{"baudRate":50.0,"frequencyShift":450.0,"reversed":true}""",
            RttySpec.toRow(RttySpec.packet(0, 0, null, K2kPhy(50f, 450f, reversed = true), K2kText("x"))).phyJson,
        )
    }

    /** An R09 row without a phy reads as the phy's defaults (2400 bit/s, nothing measured). */
    @Test
    fun anR09RowWithoutPhyReadsAsTheDefaults() {
        val sample = samples.single { it.mode == DigitalMode.R09 }
        val row = interpret(sample, sample.frequencyHz).row().copy(phyJson = null)
        assertEquals(R09Phy(), R09Spec.fromRow(row).phy)
    }

    /** Keys derived from the raw bits as well as from the content. */
    @Test
    fun keysComeFromTheModesIdentity() {
        val keys = samples.associate { it.mode to interpret(it, it.frequencyHz).packet.objectKey }
        assertEquals("1234567", keys[DigitalMode.POCSAG])
        assertEquals("", keys[DigitalMode.LORA]) // a MeshCore request names no sender
        assertEquals("STATION:DF4IAE-9", keys[DigitalMode.AX25])
        assertEquals("", keys[DigitalMode.FT8]) // free text names no station
        assertEquals("3C6444", keys[DigitalMode.ADSB])
        assertEquals("D3A1", keys[DigitalMode.RDS])
    }

    /** Parser versions: app-parsed modes stamp theirs, extension-decoded modes 0. */
    @Test
    fun appParsedModesAreStampedWithTheirParserVersion() {
        samples.forEach { sample ->
            val packet = interpret(sample, sample.frequencyHz).packet
            assertEquals(sample.mode.name, specOf(sample.mode).parserVersion, packet.parserVersion)
            val parsedHere = sample.mode in setOf(DigitalMode.POCSAG, DigitalMode.LORA, DigitalMode.AX25, DigitalMode.R09, DigitalMode.FT8)
            assertEquals(sample.mode.name, parsedHere, packet.parserVersion > 0)
        }
    }

    /** The export envelope: what, when, where, whose, the raw bits as hex, and the mode's own parts. */
    @Test
    fun aPacketExportsInTheEnvelope() {
        val adsb = samples.single { it.mode == DigitalMode.ADSB }
        val json = interpret(adsb, adsb.frequencyHz).packet.toExportJson()
        assertTrue(json, json.contains("\"mode\": \"ADSB\""))
        assertTrue(json, json.contains("\"time\": \"1970-01-01T00:00:06Z\""))
        assertTrue(json, json.contains("\"objectKey\": \"3C6444\""))
        assertTrue(json, json.contains("\"raw\": \"8D3C6444"))
        assertTrue(json, json.contains("\"callsign\": \"DLH123\""))
    }
}
