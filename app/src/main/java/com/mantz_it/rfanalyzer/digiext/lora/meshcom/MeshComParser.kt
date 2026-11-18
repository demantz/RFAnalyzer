package com.mantz_it.rfanalyzer.digiext.lora.meshcom

import com.mantz_it.rfanalyzer.digiext.lora.MeshComAck
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrame
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrameType
import com.mantz_it.rfanalyzer.digiext.lora.MeshComHey
import com.mantz_it.rfanalyzer.digiext.lora.MeshComInfo
import com.mantz_it.rfanalyzer.digiext.lora.MeshComNeighbour
import com.mantz_it.rfanalyzer.digiext.lora.MeshComNeighbourReport
import com.mantz_it.rfanalyzer.digiext.lora.MeshComPosition
import com.mantz_it.rfanalyzer.digiext.lora.MeshComSignalReport
import com.mantz_it.rfanalyzer.digiext.lora.MeshComText
import com.mantz_it.rfanalyzer.digiext.lora.MeshComTextKind
import com.mantz_it.rfanalyzer.digiext.lora.LoraPayloadMessage

/**
 * <h1>RF Analyzer - MeshCom Parser</h1>
 *
 * Module:      MeshComParser.kt
 * Description: MeshCom frames from a LoRa payload. MeshCom shares Meshtastic's sync word (0x2B),
 *              so a frame is recognised by its structure alone: a type byte, a printable callsign
 *              path up to '>', the destination ended by the type character, the payload ended by
 *              a NUL, and the trailer with its checksum - binary Meshtastic headers never pass
 *              that. The format (written from the firmware and checked against real frames):
 *
 *              type(1) | msg id(4, LE) | hops+flags(1) | "SRC[,RELAY…]>[VIA,…]DEST" | type |
 *              payload | 0x00 | hw(1) | mod(1) | sum(2, BE) | fw(1) | last hw(1) | sub(1) | 0x7E
 *
 *              The sum covers every byte up to the mod byte. Binary ACKs are 12 bytes: 'A', ack
 *              id(4, LE), 0x80 | hops, acknowledged msg id(4, LE), 0x01, 0x00.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object MeshComParser {
    private const val MIN_FRAME = 16
    private const val MAX_PATH = 120 // the firmware's path limit, plus the separator
    private const val DAMAGED_CHECK_BYTES = 24

    /** A callsign: an optional prefix character and letter, digits, 1-3 letters, an optional SSID
     *  (the firmware's pattern for the originator and the last transmitter). */
    private val CALLSIGN = Regex("[A-Z0-9]?[A-Z]?[0-9]+[A-Z]{1,3}(-?[0-9]{1,2})?")

    fun tryParse(payload: ByteArray): LoraPayloadMessage? = tryParseAck(payload) ?: tryParseFrame(payload)

    /**
     * Whether a frame [tryParse] rejected still starts like a MeshCom frame: a type byte, and mostly
     * printable text where the callsign path is. For a frame whose LoRa CRC failed: bit errors in
     * the path or the separators break the structure, and Meshtastic's parser - which takes
     * anything - must not get it. Meshtastic's header is binary there (node and packet ids).
     */
    fun looksDamaged(payload: ByteArray): Boolean {
        if (payload.size < MIN_FRAME || payload[0].toInt().toChar() !in ":!@") return false
        val path = payload.copyOfRange(6, minOf(payload.size, 6 + DAMAGED_CHECK_BYTES))
        return path.count { printable(it) } >= path.size * 3 / 4
    }

    private fun tryParseAck(b: ByteArray): MeshComAck? {
        if (b.size != 12 && !(b.size == 15 && b[11].toInt() == 3)) return null
        if (b[0] != 'A'.code.toByte() || b[5].toInt() and 0x80 == 0 || b[10].toInt() != 0x01) return null
        if (b.size == 12 && b[11].toInt() != 0) return null
        return MeshComAck(ackId = uint32(b, 1), hopsLeft = b[5].toInt() and 0x7F, ackedMsgId = uint32(b, 6))
    }

    private fun tryParseFrame(b: ByteArray): MeshComFrame? {
        if (b.size < MIN_FRAME || b.size > 255) return null
        val typeByte = b[0]
        val type = when (typeByte.toInt().toChar()) {
            ':' -> MeshComFrameType.TEXT
            '!' -> MeshComFrameType.POSITION
            '@' -> MeshComFrameType.HEY
            else -> return null
        }
        // source path: printable ASCII up to '>'
        val srcEnd = (6 until minOf(b.size, 6 + MAX_PATH)).firstOrNull { b[it] == '>'.code.toByte() } ?: return null
        if (srcEnd == 6 || (6 until srcEnd).any { !printable(b[it]) }) return null
        val sourcePath = String(b, 6, srcEnd - 6, Charsets.US_ASCII).split(',')
        if (!CALLSIGN.matches(sourcePath.first()) || !CALLSIGN.matches(sourcePath.last()) || sourcePath.first() == "DE") return null
        // destination path: printable ASCII up to the type character
        val dstStart = srcEnd + 1
        val dstEnd = (dstStart until minOf(b.size, dstStart + MAX_PATH)).firstOrNull { b[it] == typeByte } ?: return null
        if (dstEnd == dstStart || (dstStart until dstEnd).any { !printable(b[it]) }) return null
        val destPath = String(b, dstStart, dstEnd - dstStart, Charsets.US_ASCII).split(',')
        if (destPath.any { it.isEmpty() }) return null
        // payload up to the NUL, then hw, mod and the sum
        val nul = (dstEnd + 1 until b.size).firstOrNull { b[it].toInt() == 0 } ?: return null
        if (nul + 4 >= b.size) return null
        val hw = b[nul + 1].toInt() and 0xFF
        val mod = b[nul + 2].toInt() and 0xFF
        val sum = ((b[nul + 3].toInt() and 0xFF) shl 8) or (b[nul + 4].toInt() and 0xFF)
        val computed = (0..nul + 2).sumOf { b[it].toInt() and 0xFF } and 0xFFFF
        val payload = String(b, dstEnd + 1, nul - dstEnd - 1, Charsets.UTF_8)
        // the four bytes after the sum are there in every current frame
        val tail = nul + 5
        val firmware = b.getOrNull(tail)?.toInt()?.and(0xFF)
        val lastHw = b.getOrNull(tail + 1)?.toInt()?.and(0xFF)
        val sub = b.getOrNull(tail + 2)?.toInt()?.toChar()?.takeIf { it.isLetter() }?.toString()
        return MeshComFrame(
            frameType = type,
            msgId = uint32(b, 1),
            hopsLeft = b[5].toInt() and 0x0F,
            flags = b[5].toInt() and 0xF0,
            sourcePath = sourcePath,
            destPath = destPath,
            payload = payload,
            hardware = hw,
            country = mod shr 4,
            modulation = mod and 0x0F,
            checksumValid = computed == sum,
            firmware = firmware,
            lastHardware = lastHw?.takeIf { it and 0x80 != 0 }?.and(0x7F),
            firmwareSubVersion = sub,
            info = info(type, destPath.last(), payload),
        )
    }

    private fun info(type: MeshComFrameType, destination: String, payload: String): MeshComInfo = when (type) {
        MeshComFrameType.TEXT -> text(destination, payload)
        MeshComFrameType.POSITION -> position(payload) ?: MeshComText(payload)
        MeshComFrameType.HEY -> if (destination == "HN") neighbourReport(payload) else hey(payload)
    }

    private val ACK = Regex("""(\S+)\s*:(ack|rej)(\d+)""")
    private val ACK_REQUEST = Regex("""\{(\d{1,5})$""")

    internal fun text(destination: String, payload: String): MeshComText {
        ACK.matchEntire(payload)?.let { return MeshComText(payload, MeshComTextKind.ACK, it.groupValues[3].toIntOrNull()) }
        return when {
            destination == "100001" -> MeshComText(payload, MeshComTextKind.TELEMETRY)
            // the servers' clocks differ despite the tag: UTC, local time, an hour ahead - shown as sent
            payload.startsWith("{CET}") -> MeshComText(payload.removePrefix("{CET}").trim().removeSurrounding("<", ">"), MeshComTextKind.TIME)
            payload.startsWith("{ping}") -> MeshComText(payload, MeshComTextKind.PING)
            payload.startsWith("{pong}") -> MeshComText(payload, MeshComTextKind.PONG)
            payload.startsWith("{SET}") || payload.startsWith("{mcp}") || payload.startsWith("RM1 ") -> MeshComText(payload, MeshComTextKind.COMMAND)
            else -> ACK_REQUEST.find(payload)?.takeIf { CALLSIGN.matches(destination) }
                ?.let { MeshComText(payload.substring(0, it.range.first), MeshComTextKind.CHAT, it.groupValues[1].toInt()) }
                ?: MeshComText(payload)
        }
    }

    // DDMM.mm N|S, symbol table, DDDMM.mm E|W, symbol code, the rest
    private val POSITION = Regex("""(\d{2})(\d{2}\.\d{2})([NS])(.)(\d{3})(\d{2}\.\d{2})([EW])(.)(.*)""", RegexOption.DOT_MATCHES_ALL)
    private val TAG_START = Regex("""/([A-Z])=|/N[1-9]""")
    private val TAG = Regex("""/([A-Z])=([^/]*)|/N(\d{1,2})""")

    internal fun position(payload: String): MeshComPosition? {
        val m = POSITION.matchEntire(payload) ?: return null
        val g = m.groupValues
        val lat = (g[1].toInt() + g[2].toDouble() / 60).let { if (g[3] == "S") -it else it }
        val lon = (g[5].toInt() + g[6].toDouble() / 60).let { if (g[7] == "W") -it else it }
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val rest = g[9]
        val tagsAt = TAG_START.find(rest)?.range?.first ?: rest.length
        val text = rest.substring(0, tagsAt)
        val nameAt = text.lastIndexOf('#')
        val comment = (if (nameAt >= 0) text.substring(0, nameAt) else text).trim().ifEmpty { null }
        val name = if (nameAt >= 0) text.substring(nameAt + 1).trim().ifEmpty { null } else null
        val tags = HashMap<Char, String>()
        var neighbours: Int? = null
        for (t in TAG.findAll(rest.substring(tagsAt))) {
            if (t.groupValues[3].isNotEmpty()) neighbours = t.groupValues[3].toInt()
            else tags[t.groupValues[1][0]] = t.groupValues[2]
        }
        fun float(c: Char) = tags[c]?.toFloatOrNull()
        fun int(c: Char) = tags[c]?.toIntOrNull()
        return MeshComPosition(
            latitude = lat, longitude = lon, symbolTable = g[4][0], symbolCode = g[8][0],
            comment = comment, name = name,
            batteryPercent = int('B'), altitudeFt = int('A'), neighbours = neighbours,
            pressureHpa = float('P'), humidityPercent = float('H'), temperatureC = float('T'), temperature2C = float('O'),
            qnhHpa = float('Q'), gasKohm = float('G'), co2Ppm = int('C'),
            groups = tags['R']?.split(';')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty(),
            voltageV = float('U'), currentMa = float('I'),
        )
    }

    /** `R<n>;` and one `<neighbours>,<rssi>,<snr>;` per relay (older relays: `<rssi>,<snr>;`, older
     *  originators: `R` alone). */
    internal fun hey(payload: String): MeshComHey {
        if (!payload.startsWith("R")) return MeshComHey()
        var neighbours: Int? = null
        val reports = ArrayList<MeshComSignalReport>()
        for ((i, token) in payload.substring(1).split(';').filter { it.isNotBlank() }.withIndex()) {
            val fields = token.split(',').map { it.trim().toIntOrNull() }
            when {
                i == 0 && fields.size == 1 -> neighbours = fields[0]
                fields.size == 3 && fields.all { it != null } -> reports += MeshComSignalReport(fields[0], -fields[1]!!, fields[2]!!)
                fields.size == 2 && fields.all { it != null } -> reports += MeshComSignalReport(null, -fields[0]!!, fields[1]!!)
            }
        }
        return MeshComHey(neighbours, reports)
    }

    /** `R<heard>;N<listed>[+];CALL,<snr>;…` */
    internal fun neighbourReport(payload: String): MeshComNeighbourReport {
        var heard: Int? = null
        var more = false
        val neighbours = ArrayList<MeshComNeighbour>()
        for (token in payload.split(';').filter { it.isNotBlank() }) {
            when {
                token.startsWith("R") && token.drop(1).all { it.isDigit() } -> heard = token.drop(1).toIntOrNull()
                token.startsWith("N") && token.drop(1).trimEnd('+').all { it.isDigit() } -> more = token.endsWith("+")
                token.contains(',') -> token.split(',', limit = 2).let { neighbours += MeshComNeighbour(it[0], it[1].toFloatOrNull()) }
            }
        }
        return MeshComNeighbourReport(heard, neighbours, more)
    }

    private fun printable(b: Byte) = b in 0x20..0x7E

    private fun uint32(b: ByteArray, at: Int): Long =
        (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
            ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)
}

/** The board a MeshCom hardware id stands for (the firmware's table), null if unknown. */
fun meshComHardwareName(id: Int): String? = when (id) {
    0 -> "MeshCom server"
    1 -> "TTGO T-LoRa V2"
    2 -> "TTGO T-LoRa V1"
    3 -> "TTGO T-LoRa V2 1.6"
    4 -> "T-Beam"
    5 -> "T-Beam SX1268"
    6 -> "T-Beam 0.7"
    7 -> "T-Echo"
    8 -> "T-Deck"
    9 -> "RAK4631"
    10 -> "Heltec V2.1"
    11 -> "Heltec V1"
    12 -> "T-Beam AXP2101"
    39 -> "EBYTE E22 (ESP32)"
    40 -> "T5 e-paper"
    41 -> "Heltec Wireless Tracker"
    42 -> "Heltec Wireless Stick V3"
    43 -> "Heltec V3"
    44 -> "Heltec Vision Master E290"
    45 -> "T-Beam SX1262"
    46 -> "T-Deck Plus"
    47 -> "T-Beam Supreme"
    48 -> "ESP32-S3 + EBYTE E22"
    49 -> "LilyGO T-Pager"
    50 -> "T-Deck Pro"
    51 -> "T-Beam 1W"
    52 -> "Heltec V4"
    53 -> "T-ETH-Elite SX1262"
    54 -> "Heltec T114"
    55 -> "LilyGO T3-S3 V1.3"
    56 -> "T-Connect Pro"
    57 -> "Heltec Wireless Paper"
    58 -> "Heltec Vision Master E213"
    59, 60 -> "ESP32 LoRa-APRS module"
    61 -> "T-Watch S3"
    else -> null
}

/** "4.40a" - the originator's firmware, null for the server or if not sent. */
fun meshComFirmware(frame: MeshComFrame): String? =
    frame.firmware?.takeIf { it > 0 }?.let { "4.$it${frame.firmwareSubVersion.orEmpty()}" }
