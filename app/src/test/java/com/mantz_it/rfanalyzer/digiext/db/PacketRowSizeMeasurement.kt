package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer.digiext.interpret
import com.mantz_it.rfanalyzer.digiext.k2k.K2kPhy
import com.mantz_it.rfanalyzer.digiext.k2k.K2kText
import com.mantz_it.rfanalyzer.digiext.k2k.RttySpec
import com.mantz_it.rfanalyzer_ipc.Ax25Variant
import com.mantz_it.rfanalyzer_ipc.digiext.AdsbPacket
import com.mantz_it.rfanalyzer_ipc.digiext.AirbornePositionPayload
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Packet
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Phy
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
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
import org.junit.Test

/**
 * <h1>RF Analyzer - Packet Row Size Measurement</h1>
 *
 * Module:      PacketRowSizeMeasurement.kt
 * Description: Measures the on-disk cost of one persisted packet row per mode. Not a pass/fail
 *              correctness test - it prints a table and asserts nothing about absolute sizes,
 *              because the answer depends on real-world payload content.
 *
 * The per-mode retention defaults ([defaultRetention]) are based on these numbers (estimates from
 * the @Serializable class shapes were ~40% low for POCSAG). Re-run this after changing a mode's
 * packet or data types, and re-check the defaults if a mode's row cost moves materially.
 *
 * Every row is built the way the app builds it - the mode's spec interprets an extension packet -
 * so what is measured is the stored form itself: payloadJson + searchText + raw BLOB + phyJson,
 * plus [STORAGE_OVERHEAD_BYTES] for SQLite's row overhead and the four indices (an approximation;
 * the rest is measured exactly).
 *
 * Run: ./gradlew testPlayDebugUnitTest --tests "com.mantz_it.rfanalyzer.digiext.db.PacketRowSizeMeasurement"
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class PacketRowSizeMeasurement {

    /** ~40 B SQLite row header/page overhead + ~25 B per entry of the four indices. */
    private val STORAGE_OVERHEAD_BYTES = 140

    private fun report(row: PacketEntity) {
        val raw = row.raw?.size ?: 0
        val phy = row.phyJson?.length ?: 0
        val total = row.payloadJson.length + row.searchText.length + raw + phy + STORAGE_OVERHEAD_BYTES
        println(
            "PacketRowSize %-8s payload=%5d  search=%4d  +raw=%3d +phy=%3d +overhead=%3d  => %5d B/row  (%.1f MB per 100k rows)"
                .format(row.mode.name, row.payloadJson.length, row.searchText.length, raw, phy, STORAGE_OVERHEAD_BYTES, total, total * 100_000 / 1_048_576.0)
        )
    }

    private fun report(packet: DigitalPacket) = report(interpret(packet, frequencyHz = 100_000_000L).row())

    /** One 7-octet AX.25 address subfield. */
    private fun address(call: String, ssid: Int, last: Boolean) =
        call.padEnd(6).map { (it.code shl 1).toByte() }.toByteArray() + byteArrayOf(((ssid shl 1) or (if (last) 1 else 0)).toByte())

    @Test
    fun measureRowSizePerMode() {
        println()

        // ADS-B: 112-bit airborne position frame - the shortest and highest-rate payload.
        report(AdsbPacket(
            raw = Bytes(byteArrayOf(0x8D.toByte(), 0x3C, 0x4B, 0x21) + ByteArray(10) { (it * 17).toByte() }),
            payload = AirbornePositionPayload(typeCode = 11, altitudeFt = 34000, altSource = "BARO", cprOddFrame = false, cprLat = 74158, cprLon = 50194),
        ))

        // RDS: a 2A group carrying one RadioText segment - a station sends 11.4 groups per second.
        report(RdsPacket(
            raw = Bytes(byteArrayOf(0xD3.toByte(), 0xC2.toByte(), 0x25, 0x40, 0x4A, 0x61, 0x7A, 0x7A)),
            events = listOf(RdsEvent.RadioText("Now playing: Jazz FM - the best of the 80s")),
        ))

        // FT8: a standard message, "DL1ABC K1ABC -30".
        report(Ft8Packet(
            timestampMs = 1_700_000_000_000L, phy = Ft8Phy(snr = -12.5f, timeDeltaMs = 320),
            raw = Bytes(byteArrayOf(0x68, 0x89.toByte(), 0x42, 0x30, 0x4D, 0xEF.toByte(), 0x1A, 0x9F.toByte(), 0xA5.toByte(), 0x48, 0x00, 0x00)),
        ))

        // AX.25/APRS: a position report via one digipeater.
        report(Ax25Packet(
            raw = Bytes(
                address("APRS", 0, false) + address("DL1ABC", 9, false) + address("WIDE1", 1, true) + byteArrayOf(0x03, 0xF0.toByte()) +
                    "!4903.50N/07201.75W>088/036/A=001234 Mobile, QRV 145.500".toByteArray(Charsets.ISO_8859_1)
            ),
            phy = Ax25Phy(Ax25Variant.VHF_1200),
        ))

        // R09: a full R09.16 telegram.
        report(R09Packet(raw = Bytes(byteArrayOf(0x91.toByte(), 0xA6.toByte(), 0x42, 0x31, 0x94.toByte(), 0x13, 0x57, 0x86.toByte(), 0x93.toByte())),
            phy = R09Phy(snrDb = 12.3f, frequencyOffsetHz = 410f, deviationHz = 2140f)))

        // POCSAG: a typical alphanumeric page.
        val pocsagText = "Club net tonight at 20:00 local time on 145.500 MHz - all are welcome :)"
        report(PocsagPacket(
            raw = Bytes(ByteArray(pocsagText.length * 7 / 8 + 1) { (it * 13).toByte() }),
            phy = PocsagPhy(address = 1234567L, function = 3, baudRate = 1200, bitCount = pocsagText.length * 7),
        ))

        // LoRa: a 64-byte payload no parser recognizes.
        report(LoraPacket(
            raw = Bytes(ByteArray(64) { it.toByte() }),
            phy = LoraPhy(spreadingFactor = 7, codingRate = 1, bandwidthHz = 125_000, hasCrc = true, crcValid = true, syncWord = 0x2B),
        ))

        // RTTY: a finished transmission of a typical CQ call.
        report(RttySpec.toRow(RttySpec.packet(
            timestampMs = 1_700_000_000_000L, frequencyHz = 14_080_000L, raw = null, endTimestampMs = 1_700_000_030_000L,
            phy = K2kPhy(baudRate = 45.45f, frequencyShift = 170f),
            data = K2kText("CQ CQ CQ DE DL1ABC DL1ABC DL1ABC PSE K", fromCallsign = "DL1ABC"),
        )))

        println()
    }
}
