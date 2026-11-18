package com.mantz_it.rfanalyzer.digiext.aprs

import kotlin.math.roundToInt

/**
 * <h1>RF Analyzer - Mic-E Parser</h1>
 *
 * Module:      MicE.kt
 * Description: Decodes a Mic-E report (APRS 1.0.1/1.2 ch. 10). Half of it travels in the AX.25
 *              destination address: the latitude, the three position comment bits, North/South, the
 *              +100° longitude offset and West/East. The information field holds the rest:
 *              longitude, speed and course, the symbol, then the status text - a device type code,
 *              an altitude, the comment and a device suffix.
 *
 * Without the destination (or with one that is not Mic-E encoded) there is no position and no
 * position comment; speed, course, symbol, altitude, device and comment are still decoded.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
internal object MicEParser {

    /** The standard position comments for the bits A/B/C = 111 down to 001; 000 is an emergency. */
    private val STANDARD_COMMENTS = listOf("Off Duty", "En Route", "In Service", "Returning", "Committed", "Special", "Priority")

    /** The two-character suffixes that end the status text of a device using the type code ` or '
     *  (the `mice` table of github.com/aprsorg/aprs-deviceid). */
    private val DEVICE_SUFFIXES = mapOf(
        "_ " to "Yaesu VX-8", "_\"" to "Yaesu FTM-350", "_#" to "Yaesu VX-8G", "_$" to "Yaesu FT1D",
        "_(" to "Yaesu FT2D", "_0" to "Yaesu FT3D", "_3" to "Yaesu FT5D", "_1" to "Yaesu FTM-300D",
        "_2" to "Yaesu FTM-200D", "_4" to "Yaesu FTM-500D", "_5" to "Yaesu FTM-510D", "_6" to "Yaesu FTX-1",
        "_7" to "Yaesu FTM-310D", "_)" to "Yaesu FTM-100D", "_%" to "Yaesu FTM-400DR",
        "(5" to "Anytone D578UV", "(8" to "Anytone D878UV",
        "|3" to "Byonics TinyTrak3", "|4" to "Byonics TinyTrak4",
        "^A" to "AxelStack APRS Connect", "^v" to "HinzTec anyfrog", "*v" to "KissOZ Tracker", "*9" to "NOR AVRT9",
        ":2" to "SQ8L VP-Tracker", " X" to "SainSonic AP510", "[1" to "APRSdroid",
    )

    /**
     * [info] is the whole information field, its data type identifier [dti] included; [destination]
     * the AX.25 destination callsign (an SSID is ignored).
     */
    fun parse(info: String, dti: Char, destination: String?): MicEInfo {
        val rest = info.substring(1)
        val block = rest.take(8)
        // Shorter than 9 bytes: a field that lost its non-printing bytes on the way - the spec says to ignore it.
        if (block.length < 8) return MicEInfo(dti = dti, deviceBlock = block, messagingCapable = false, comment = null)

        val dest = destination?.substringBefore('-')?.trim()?.uppercase()?.let(::decodeDestination)
        val longitude = dest?.takeIf { it.latitude != null }?.let { decodeLongitude(block, it) }
        val latitude = dest?.latitude?.takeIf { longitude != null }
        val (speedKnots, course) = decodeSpeedAndCourse(block)
        val status = decodeStatus(rest.substring(8))

        return MicEInfo(
            dti = dti,
            deviceBlock = block,
            messagingCapable = status.messagingCapable,
            comment = status.comment,
            lat = latitude,
            lon = longitude,
            course = course,
            speedKnots = speedKnots,
            altitudeFeet = status.altitudeFeet,
            symbolTable = block[7],
            symbol = block[6],
            messageType = dest?.messageType,
            device = status.device,
            positionAmbiguity = dest?.ambiguity?.takeIf { latitude != null } ?: 0,
        )
    }

    private class Destination(
        /** Null when the latitude digits are not a valid latitude. */
        val latitude: Double?,
        /** How many trailing latitude digits are left out (sent as spaces). */
        val ambiguity: Int,
        val longitudeOffset: Boolean,
        val west: Boolean,
        val messageType: String,
    )

    /** The six destination characters, or null if they are not Mic-E encoded. */
    private fun decodeDestination(dest: String): Destination? {
        if (dest.length != 6) return null
        // per position: the latitude digit (-1 = left out), and for positions 1-3 the comment bit
        val digits = IntArray(6)
        var bits = 0
        var standard = false
        var custom = false
        for (i in 0 until 6) {
            val c = dest[i]
            val commentByte = i < 3
            digits[i] = when (c) {
                in '0'..'9' -> c - '0'
                in 'A'..'J' -> if (commentByte) c - 'A' else return null
                'K' -> if (commentByte) -1 else return null
                'L' -> -1
                in 'P'..'Y' -> c - 'P'
                'Z' -> -1
                else -> return null
            }
            if (commentByte) {
                val bit = c in 'A'..'K' || c in 'P'..'Z'
                if (c in 'A'..'K') custom = true
                if (c in 'P'..'Z') standard = true
                bits = (bits shl 1) or (if (bit) 1 else 0)
            }
        }
        val messageType = when {
            standard && custom -> "Unknown"
            bits == 0 -> "Emergency"
            custom -> "Custom ${7 - bits}"
            else -> STANDARD_COMMENTS[7 - bits]
        }

        // Ambiguity leaves out digits from the right, at most the four of the minutes; a left-out
        // digit counts as 0 (APRS101: "the last two digits ... will be ignored").
        val ambiguity = digits.reversed().takeWhile { it < 0 }.size
        val latitude = if (ambiguity <= 4 && digits.take(6 - ambiguity).none { it < 0 }) {
            val d = digits.map { maxOf(it, 0) }
            val degrees = d[0] * 10 + d[1]
            val minutes = d[2] * 10 + d[3] + (d[4] * 10 + d[5]) / 100.0
            (degrees + minutes / 60.0).takeIf { minutes < 60 && it <= 90.0 }
        } else null
        val north = dest[3] in 'P'..'Z'
        return Destination(
            latitude = latitude?.let { if (north) it else -it },
            ambiguity = ambiguity,
            longitudeOffset = dest[4] in 'P'..'Z',
            west = dest[5] in 'P'..'Z',
            messageType = messageType,
        )
    }

    /** Degrees, minutes and hundredths from the d+28/m+28/h+28 bytes; null if out of range. */
    private fun decodeLongitude(block: String, dest: Destination): Double? {
        var degrees = block[0].code - 28 + (if (dest.longitudeOffset) 100 else 0)
        if (degrees in 180..189) degrees -= 80 else if (degrees in 190..199) degrees -= 190
        var minutes = block[1].code - 28
        if (minutes >= 60) minutes -= 60
        var hundredths = block[2].code - 28
        if (degrees !in 0..179 || minutes !in 0..59 || hundredths !in 0..99) return null
        // the latitude's ambiguity applies to the longitude too
        when (dest.ambiguity) {
            1 -> hundredths -= hundredths % 10
            2 -> hundredths = 0
            3 -> { minutes -= minutes % 10; hundredths = 0 }
            4 -> { minutes = 0; hundredths = 0 }
        }
        val longitude = degrees + (minutes + hundredths / 100.0) / 60.0
        return if (dest.west) -longitude else longitude
    }

    /**
     * Speed in knots (0..799) and course in degrees (0..359; null when unknown) from the SP+28,
     * DC+28 and SE+28 bytes. The spec's course 0 means "unknown" and 360 "due north", so 360 is
     * returned as 0 and 0 as null.
     */
    private fun decodeSpeedAndCourse(block: String): Pair<Int?, Int?> {
        val sp = block[3].code - 28
        val dc = block[4].code - 28
        val se = block[5].code - 28
        if (sp < 0 || dc < 0 || se < 0) return null to null
        var speed = sp * 10 + dc / 10
        if (speed >= 800) speed -= 800
        var course = (dc % 10) * 100 + se
        if (course >= 400) course -= 400
        return speed.takeIf { it in 0..799 } to course.takeIf { it in 1..360 }?.rem(360)
    }

    private class Status(val comment: String?, val altitudeFeet: Int?, val device: String?, val messagingCapable: Boolean)

    /**
     * The status text after the symbol: an optional type code, an optional altitude ("xxx}", base 91,
     * meters above 10 km below sea level) right after it, the comment, and the device suffix.
     *
     * The type code is what says whether the sender can receive APRS messages: ` (and Kenwood's
     * `>`/`]`) can, `'` cannot; without one it is not known and taken as no. The data type
     * identifier says nothing about it - it marks the GPS data current or old.
     */
    private fun decodeStatus(status: String): Status {
        val type = status.firstOrNull()?.takeIf { it in "`'>]" }
        var text = if (type != null) status.substring(1) else status

        var altitudeFeet: Int? = null
        if (text.length >= 4 && text[3] == '}' && text.take(3).all { it.code in 33..123 }) {
            val meters = (text[0].code - 33) * 91 * 91 + (text[1].code - 33) * 91 + (text[2].code - 33) - 10_000
            altitudeFeet = (meters * FEET_PER_METER).roundToInt()
            text = text.substring(4)
        }

        val device = when (type) {
            '>' -> when (text.lastOrNull()) {
                '=' -> "Kenwood TH-D72"
                '^' -> "Kenwood TH-D74"
                '&' -> "Kenwood TH-D75"
                else -> null
            }?.also { text = text.dropLast(1) } ?: "Kenwood TH-D7A"
            ']' -> if (text.lastOrNull() == '=') "Kenwood TM-D710".also { text = text.dropLast(1) } else "Kenwood TM-D700"
            '`', '\'' -> DEVICE_SUFFIXES[text.takeLast(2)]?.also { text = text.dropLast(2) }
            else -> null
        }
        return Status(
            comment = text.trim().takeIf { it.isNotEmpty() },
            altitudeFeet = altitudeFeet,
            device = device,
            messagingCapable = type != null && type != '\'',
        )
    }

    private const val FEET_PER_METER = 3.28084
}
