package com.mantz_it.rfanalyzer.digiext.aprs

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * <h1>RF Analyzer - APRS Info Parser</h1>
 *
 * Module:      AprsInfoParser.kt
 * Description: APRS information-field parser. Framing-agnostic: takes the raw Information field
 *              bytes plus the destination callsign (no other AX.25 fields, no LoRa PHY knowledge)
 *              and returns a typed AprsInfo. Shared by AX.25 (Ax25Parser, the frame's information
 *              field) and LoRa-APRS (LoraAprsParser, the body after the TNC2 header).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object AprsInfoParser {

    private const val WEATHER_SYMBOL = '_'

    /**
     * Parses the Information field by its data type identifier (its first byte). [destination] is
     * the packet's destination callsign (the AX.25 destination address, TNC2's "TO"): a Mic-E report
     * carries its latitude there.
     */
    fun parseAprsInfo(infoBytes: ByteArray, destination: String? = null): AprsInfo {
        val infoStr = try {
            String(infoBytes, charset("ISO-8859-1")).trimEnd('\r', '\n')
        } catch (e: Exception) {
            String(infoBytes)
        }
        if (infoStr.isEmpty()) return UnknownAprsInfo(raw = "")

        val dti = infoStr[0]
        return when (dti) {
            '!', '=', '/', '@' -> parsePosition(infoStr, dti)
            '`', '\'', '\u001c', '\u001d' -> MicEParser.parse(infoStr, dti, destination)
            ';' -> parseObject(infoStr)
            ':' -> parseMessage(infoStr)
            'T' -> parseTelemetry(infoStr)
            '}' -> parseThirdParty(infoStr)
            '>' -> parseStatus(infoStr)
            '$' -> parseRawGps(infoStr)
            else -> UnknownAprsInfo(raw = infoStr)
        }
    }

    /* -----------------------
       Position report (APRS101 ch. 8), best-effort: the DTIs '/' and '@' put a timestamp before
       the position, '!' and '=' do not.
       ----------------------- */
    private fun parsePosition(info: String, dti: Char): AprsInfo {
        var idx = 1
        var timestamp: String? = null

        // Only the zulu form ending in 'z' ("091842z") is recognised.
        if (dti == '/' || dti == '@') {
            val zPos = info.indexOf('z', idx)
            if (zPos > idx) {
                timestamp = info.substring(idx, zPos + 1)
                idx = zPos + 1
                if (idx < info.length && info[idx] == ' ') idx++
            }
        }

        val remaining = info.substring(idx)
        if (remaining.isEmpty()) return UnknownAprsInfo(raw = info)

        // The byte after the DTI (or timestamp) tells the two position formats apart: an
        // uncompressed latitude starts with its two degree digits, a compressed position with its
        // symbol table identifier, which is never a digit.
        return if (remaining[0].isDigit()) {
            parseUncompressedPosition(remaining, dti, timestamp)
        } else {
            parseCompressedPosition(remaining, dti, timestamp) ?: UnknownAprsInfo(raw = info)
        }
    }

    /* -----------------------
       Uncompressed position: DDMM.mmN/{table}DDDMM.mmE/W{symbol}{comment}
       ----------------------- */
    private fun parseUncompressedPosition(remaining: String, dti: Char, timestamp: String?): AprsInfo {
        // Fixed offsets: latitude (8), symbol table (1), longitude (9), symbol (1), comment.
        val latCandidate = if (remaining.length >= 8) remaining.substring(0, 8) else null
        val symbolTable = if (remaining.length >= 9) remaining[8] else null
        val lonCandidate = if (remaining.length >= 18) remaining.substring(9, 18) else null
        val symbol = if (remaining.length >= 19) remaining[18] else null
        val comment = if (remaining.length > 19) remaining.substring(19).trimStart() else null

        val (lat, latDec) = parseLat(latCandidate)
        val (lon, lonDec) = parseLon(lonCandidate)

        var phg: String? = null
        var course: Int? = null
        var speed: Int? = null
        var altitude: Int? = null
        if (!comment.isNullOrEmpty()) {
            val c = comment.trimStart()
            val phgMatch = Regex("""\bPHG(\d{4})\b""").find(c)
            if (phgMatch != null) phg = "PHG${phgMatch.groupValues[1]}"
            // Course/speed is the data extension "ddd/sss" (knots) right after the symbol (APRS101
            // ch. 7), not any "145/435" in the comment text. A weather station's (symbol '_') holds
            // wind direction/speed instead (ch. 12).
            val csMatch = Regex("""^(\d{3})/(\d{3})""").find(c)
            if (csMatch != null && symbol != WEATHER_SYMBOL) {
                course = csMatch.groupValues[1].toIntOrNull()
                speed = csMatch.groupValues[2].toIntOrNull()
            }
            // "/A=aaaaaa", the altitude in feet, anywhere in the comment
            val altMatch = Regex("""/A=([+-]?\d{5,6})""").find(c)
            if (altMatch != null) altitude = altMatch.groupValues[1].toIntOrNull()
        }

        return PositionReport(
            dti = dti,
            timestamp = timestamp,
            latRaw = latCandidate,
            lonRaw = lonCandidate,
            lat = latDec,
            lon = lonDec,
            symbolTable = symbolTable,
            symbol = symbol,
            comment = comment,
            phg = phg,
            course = course,
            speedKnots = speed,
            altitudeFeet = altitude
        )
    }

    /* -----------------------
       Compressed position (APRS101 ch. 9): symbol table id (1), base91 lat (4), base91 lon (4),
       symbol code (1), course/speed or radio range (2), compression type (1), then the comment.
       A symbol table id other than '/' or '\' is an overlay character on the alternate table
       (LoRa-APRS iGates often send a letter there), so it is kept as it is.
       ----------------------- */
    private fun parseCompressedPosition(remaining: String, dti: Char, timestamp: String?): AprsInfo? {
        if (remaining.length < 13) return null
        val symbolTable = remaining[0]
        val latField = remaining.substring(1, 5)
        val lonField = remaining.substring(5, 9)
        val symbol = remaining[9]
        val courseByte = remaining[10]
        val speedByte = remaining[11]
        // remaining[12], the compression type (GPS fix, NMEA source), is not kept.
        val comment = if (remaining.length > 13) remaining.substring(13).trimStart().takeIf { it.isNotEmpty() } else null

        // A base91 digit is a byte in '!'..'{' (33..123); anything else is not compressed data
        // (text that happens to start with a non-digit) and gets no position.
        if ((latField + lonField).any { it.code !in 33..123 }) return null

        fun base91Value(s: String): Long {
            var v = 0L
            for (c in s) v = v * 91 + (c.code - 33)
            return v
        }

        val lat = 90.0 - base91Value(latField) / 380926.0
        val lon = -180.0 + base91Value(lonField) / 190463.0

        // A literal space in the course byte means "no course/speed data present" (spec);
        // the speed byte is meaningless in that case too. A weather station's bytes are wind.
        var course: Int? = null
        var speedKnots: Int? = null
        if (symbol != WEATHER_SYMBOL && courseByte != ' ' && courseByte.code in 33..123 && speedByte.code in 33..123) {
            course = (courseByte.code - 33) * 4
            speedKnots = (1.08.pow(speedByte.code - 33) - 1).roundToInt()
        }

        return PositionReport(
            dti = dti,
            timestamp = timestamp,
            latRaw = latField,
            lonRaw = lonField,
            lat = lat,
            lon = lon,
            symbolTable = symbolTable,
            symbol = symbol,
            comment = comment,
            phg = null,
            course = course,
            speedKnots = speedKnots,
            altitudeFeet = null,
        )
    }

    private fun parseLat(s: String?): Pair<String?, Double?> {
        if (s == null || s.length < 8) return Pair(null, null)
        // expected DDMM.mmN
        val latRaw = s
        try {
            val deg = latRaw.substring(0,2).toInt()
            val min = latRaw.substring(2,7).toDouble() // includes decimal
            val hemi = latRaw[7]
            var dec = deg + (min / 60.0)
            if (hemi == 'S') dec = -dec
            return Pair(latRaw, dec)
        } catch (e: Exception) {
            return Pair(latRaw, null)
        }
    }

    private fun parseLon(s: String?): Pair<String?, Double?> {
        if (s == null || s.length < 9) return Pair(null, null)
        // expected DDDMM.mmE/W
        val lonRaw = s
        try {
            val deg = lonRaw.substring(0,3).toInt()
            val min = lonRaw.substring(3,8).toDouble() // includes decimal
            val hemi = lonRaw[8]
            var dec = deg + (min / 60.0)
            if (hemi == 'W') dec = -dec
            return Pair(lonRaw, dec)
        } catch (e: Exception) {
            return Pair(lonRaw, null)
        }
    }

    /* -----------------------
       Object: ';' + object name (9 chars padded) + live flag (*)/kill (_) + 7-char timestamp + location like Position
       ----------------------- */
    private fun parseObject(info: String): AprsInfo {
        // Example: ";ELYME    *190116z4122.06N/07212.98W#145.03 Packet Node..."
        if (info.length < 2) return UnknownAprsInfo(info)
        // object name occupies next 9 bytes after DTI
        val nameRaw = if (info.length >= 10) info.substring(1, 10) else info.substring(1).padEnd(9)
        val objectName = nameRaw.trimEnd()
        var idx = 10
        val flag = info.getOrNull(idx)
        val killed = flag == '_'
        if (flag == '*' || flag == '_') idx++
        val remainder = if (idx < info.length) info.substring(idx) else ""
        // The timestamp is the 7 characters after the flag: DDHHMM + 'z' or '/', or HHMMSS + 'h'.
        val timestamp = remainder.take(7).takeIf { OBJECT_TIMESTAMP.matches(it) }
        val posPart = remainder.drop(timestamp?.length ?: 0).trimStart()
        // The position as a '!' report - parsePosition expects a DTI in front.
        val posInfo = if (posPart.isNotEmpty()) parsePosition("!$posPart", '!') as? PositionReport else null

        return ObjectReport(
            objectName = objectName,
            killed = killed,
            timestamp = timestamp,
            latRaw = posInfo?.latRaw,
            lonRaw = posInfo?.lonRaw,
            lat = posInfo?.lat,
            lon = posInfo?.lon,
            symbolTable = posInfo?.symbolTable,
            symbol = posInfo?.symbol,
            // the text after the symbol and the data extension; all of it when no position could be read
            comment = if (posInfo != null) posInfo.comment?.let(::withoutDataExtension) else posPart.ifEmpty { null },
        )
    }

    private val OBJECT_TIMESTAMP = Regex("""\d{6}[zh/]""")

    /** The 7-byte data extension a comment may start with (APRS101 ch. 7): course/speed (a DF
     *  report adds bearing/quality), PHG, RNG, DFS, or an area object's line width and colour. */
    private val DATA_EXTENSION = Regex("""^([\d.]{3}/[\d.]{3}(/\d{3}/\d{3})?|PHG.{4}|RNG\d{4}|DFS.{4}|T\d{2}/C\d{2})""")

    /** [comment] without a leading data extension; null if nothing else is left. */
    private fun withoutDataExtension(comment: String): String? =
        comment.replaceFirst(DATA_EXTENSION, "").trim().ifEmpty { null }

    /* -----------------------
       Message: DTI ':'
       Format: :ADDRESSEE:Text{msgid  (ADDRESSEE is exactly 9 chars, the message id optional)
       Example: ":N2GH     :Hi, Dave!{001"
       ----------------------- */
    private fun parseMessage(info: String): AprsInfo {
        var idx = 1
        // A doubled ':' (some iGates' form) is skipped: the addressee starts after it.
        if (idx < info.length && info[idx] == ':') idx++
        val addressee = if (idx + 9 <= info.length) info.substring(idx, idx + 9) else info.substring(idx).padEnd(9)
        val add = addressee.trim()
        val sep = info.indexOf(':', idx + 9)
        val text = if (sep >= 0 && sep + 1 < info.length) info.substring(sep + 1) else ""
        val match = Regex("""\{([A-Za-z0-9]{1,5})$""").find(text)
        val msgId = match?.groupValues?.get(1)
        val textTrim = if (msgId != null) {
            text.substring(0, match.range.first)
        } else {
            text
        }
        return AprsTextMessage(
            addressee = add,
            text = textTrim,
            messageId = msgId
        )
    }

    /* -----------------------
       Telemetry: T#seq,val1,val2,...,bits
       Values separated by commas; analogs may be decimals in practice.
       ----------------------- */
    private fun parseTelemetry(info: String): AprsInfo {
        val payload = if (info.length > 1) info.substring(1) else ""
        val intern = if (payload.startsWith("#")) payload.substring(1) else payload
        val parts = intern.split(',')
        var seq: Int? = null
        val analogs = mutableListOf<String>()
        var bits: String? = null
        if (parts.isNotEmpty()) {
            // first part is sequence (3 digits) or maybe a decimal in practice
            seq = parts[0].toIntOrNull()
            if (parts.size > 1) {
                // last part may be an 8-character binary string (00000000)
                val last = parts.last()
                if (last.matches(Regex("""[01]{8}"""))) {
                    bits = last
                    analogs.addAll(parts.subList(1, parts.size - 1))
                } else {
                    analogs.addAll(parts.subList(1, parts.size))
                }
            }
        }
        return TelemetryInfo(sequence = seq, analog = analogs, bits = bits)
    }

    /* -----------------------
       Third-party wrapper: starts with '}' and then contains a nested APRS packet in TNC2 form.
       Its header names the originating station and the destination (a Mic-E report's latitude);
       its Information Part (after the first ':') is parsed recursively.
       ----------------------- */
    private fun parseThirdParty(info: String): AprsInfo {
        // e.g. "}WHO-IS>APJIW4,TCPIP,WB2OSZ-5*::WB2OSZ-7 :C/ARRL ..."
        val rest = info.substring(1)
        // the info field was read as ISO-8859-1: back to its bytes the same way
        val header = Tnc2FrameParser.splitHeader(rest.toByteArray(Charsets.ISO_8859_1))
        if (header != null) {
            val innerParsed = if (header.bodyBytes.isEmpty()) null else try {
                parseAprsInfo(header.bodyBytes, header.toCall)
            } catch (e: Exception) {
                null
            }
            return ThirdPartyInfo(rawInner = rest, innerInfo = innerParsed, source = header.fromCall.trim().ifEmpty { null })
        }
        // no "SOURCE>DEST" header: the information part is whatever follows the first ':'
        val firstColon = rest.indexOf(':')
        if (firstColon >= 0 && firstColon + 1 < rest.length) {
            val innerParsed = try {
                parseAprsInfo(rest.substring(firstColon + 1).toByteArray(Charsets.ISO_8859_1))
            } catch (e: Exception) {
                null
            }
            return ThirdPartyInfo(rawInner = rest, innerInfo = innerParsed)
        }
        return ThirdPartyInfo(rawInner = rest, innerInfo = null)
    }

    /* -----------------------
    Status: DTI '>'
    Format: >[timestamp]text
    Timestamp is optional and often ends with 'z'
    ----------------------- */
    private fun parseStatus(info: String): AprsInfo {
        val rest = if (info.length > 1) info.substring(1) else ""

        // a timestamp ending in 'z' within the first 9 characters
        val zIdx = rest.indexOf('z')
        return if (zIdx in 6..8) {
            val ts = rest.substring(0, zIdx + 1)
            val text = rest.substring(zIdx + 1).trimStart()
            StatusInfo(timestamp = ts, text = text)
        } else {
            StatusInfo(timestamp = null, text = rest)
        }
    }

    /* -----------------------
    Raw GPS / Ultimeter: DTI '$'
    Often NMEA-like sentences ($GPGGA, $ULTW, etc.)
    ----------------------- */
    private fun parseRawGps(info: String): AprsInfo {
        val raw = if (info.length > 1) info.substring(1) else ""

        // Only NMEA GPRMC is decoded.
        if (!raw.startsWith("GPRMC")) {
            return RawGpsOrUltimeterInfo(raw, null, null, null, null, null, null, null, null)
        }

        val parts = raw.split(',')

        if (parts.size < 10) {
            return RawGpsOrUltimeterInfo(raw, "GPRMC", null, null, null, null, null, null, null)
        }

        val time = parts[1].ifBlank { null }
        val status = parts[2]
        val valid = when (status) {
            "A" -> true
            "V" -> false
            else -> null
        }

        fun parseNmeaLat(value: String, hemi: String): Double? {
            if (value.isBlank() || hemi.isBlank()) return null
            return try {
                val deg = value.substring(0, 2).toInt()
                val min = value.substring(2).toDouble()
                var dec = deg + (min / 60.0)
                if (hemi == "S") dec = -dec
                dec
            } catch (e: Exception) {
                null
            }
        }
        fun parseNmeaLon(value: String, hemi: String): Double? {
            if (value.isBlank() || hemi.isBlank()) return null
            return try {
                val deg = value.substring(0, 3).toInt()
                val min = value.substring(3).toDouble()
                var dec = deg + (min / 60.0)
                if (hemi == "W") dec = -dec
                dec
            } catch (e: Exception) {
                null
            }
        }

        val lat = parseNmeaLat(parts[3], parts[4])
        val lon = parseNmeaLon(parts[5], parts[6])

        val speed = parts[7].toDoubleOrNull()
        val course = parts[8].toDoubleOrNull()
        val date = parts[9].ifBlank { null }

        return RawGpsOrUltimeterInfo(
            raw = raw,
            type = "GPRMC",
            valid = valid,
            latitude = lat,
            longitude = lon,
            speedKnots = speed,
            course = course,
            timestamp = time,
            date = date
        )
    }
}

/**
 * Splits the TNC2 "monitor format" text APRS frames are written in:
 * "FROM>TO,PATH1,PATH2:info" - what a LoRa-APRS payload carries after its magic
 * header (LoraAprsParser). An on-air AX.25 frame has binary address fields
 * instead (Ax25Parser).
 */
object Tnc2FrameParser {
    data class Header(
        val fromCall: String,
        val toCall: String?,
        val path: List<String>,
        val bodyBytes: ByteArray,
    )

    /** Returns null if there's no ':' separator, or no '>' before it. */
    fun splitHeader(raw: ByteArray): Header? {
        val text = try {
            String(raw, charset("ISO-8859-1"))
        } catch (e: Exception) {
            String(raw)
        }

        val idx = text.indexOf(':')
        if (idx <= 0) return null

        val headerText = text.substring(0, idx)
        val gt = headerText.indexOf('>')
        if (gt <= 0) return null

        val from = headerText.substring(0, gt)
        val after = headerText.substring(gt + 1)
        val parts = after.split(',')
        val to = parts.firstOrNull()?.ifBlank { null }
        val path = if (parts.size > 1) parts.subList(1, parts.size).map { it.trim() }.filter { it.isNotEmpty() } else emptyList()

        return Header(
            fromCall = from,
            toCall = to,
            path = path,
            bodyBytes = raw.copyOfRange(idx + 1, raw.size),
        )
    }
}
