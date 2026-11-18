package com.mantz_it.rfanalyzer.digiext.aprs

import com.mantz_it.rfanalyzer.digiext.db.isPlausiblePosition
import kotlin.math.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * <h1>RF Analyzer - APRS Info</h1>
 *
 * Module:      AprsInfo.kt
 * Description: The typed APRS information payloads, parsed app-side and shared by AX.25 and
 *              LoRa-APRS: the sealed [AprsInfo] hierarchy (position, Mic-E, object, message,
 *              telemetry, third-party, status and raw GPS reports) and the helpers that apply a
 *              report's fields to a station, read its symbol and spell out a PHG extension.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Applies the position/symbol/course/speed/altitude/comment fields carried by an [AprsInfo]
 *  variant via the given setters - the "sticky" per-field-group update of an
 *  [com.mantz_it.rfanalyzer.digiext.ax25.AprsStation] (AX.25 and LoRa-APRS alike): a setter only
 *  fires for the info types that actually carry that field group, so unrelated fields are left
 *  untouched. */
internal fun applyAprsFields(
    info: AprsInfo,
    onPosition: (lat: Double, lon: Double) -> Unit,
    onSymbol: (table: Char?, symbol: Char?) -> Unit,
    onCourseSpeed: (course: Double?, speedKnots: Double?) -> Unit,
    onAltitude: (feet: Int?) -> Unit,
    onComment: (text: String?) -> Unit,
) {
    // A report without a readable position, or with 0°/0° (a tracker without a fix), keeps the
    // station's last good one instead of wiping it or putting it into the Gulf of Guinea.
    fun position(lat: Double?, lon: Double?) {
        if (lat != null && lon != null && isPlausiblePosition(lat, lon)) onPosition(lat, lon)
    }
    when (info) {
        is PositionReport -> {
            position(info.lat, info.lon)
            onSymbol(info.symbolTable, info.symbol)
            onCourseSpeed(info.course?.toDouble(), info.speedKnots?.toDouble())
            onAltitude(info.altitudeFeet)
            onComment(info.comment)
        }
        is ObjectReport -> {
            position(info.lat, info.lon)
            onSymbol(info.symbolTable, info.symbol)
            onComment(info.comment)
        }
        is RawGpsOrUltimeterInfo -> {
            position(info.latitude, info.longitude)
            onCourseSpeed(info.course, info.speedKnots)
        }
        is MicEInfo -> {
            // without its destination a Mic-E report has no position: the station keeps the one it has
            if (info.lat != null && info.lon != null) {
                position(info.lat, info.lon)
                onCourseSpeed(info.course?.toDouble(), info.speedKnots?.toDouble())
                onAltitude(info.altitudeFeet)
            }
            if (info.symbol != null) onSymbol(info.symbolTable, info.symbol)
            onComment(info.comment)
        }
        is StatusInfo -> onComment(info.text)
        is AprsTextMessage -> onComment(info.text)
        // A relayed packet is its originator's report, not the relaying station's (see originReport).
        // TelemetryInfo / UnknownAprsInfo carry no position/comment fields.
        is ThirdPartyInfo, is TelemetryInfo, is UnknownAprsInfo -> {}
    }
}

/** The originating station of relayed (third-party) traffic and its report, or null when the
 *  inner packet's header or information field could not be read. */
fun ThirdPartyInfo.originReport(): Pair<String, AprsInfo>? {
    val origin = source ?: return null
    return innerInfo?.let { origin to it }
}

/* ----------------------------------------------------------------------
 * Typed APRS information payloads (sealed hierarchy), parsed app-side.
 * Shared by AX.25 (Ax25Parser) and LoRa-APRS (LoraAprsParser).
 * -------------------------------------------------------------------- */

/** The symbol an info payload carries, as (table, symbol), looking through a third-party wrapper. */
fun AprsInfo.symbolOrNull(): Pair<Char, Char>? = when (this) {
    is PositionReport -> if (symbolTable != null && symbol != null) symbolTable to symbol else null
    is ObjectReport -> if (symbolTable != null && symbol != null) symbolTable to symbol else null
    is MicEInfo -> if (symbolTable != null && symbol != null) symbolTable to symbol else null
    is ThirdPartyInfo -> innerInfo?.symbolOrNull()
    else -> null
}

/**
 * Spells out a raw `PHGphgd` extension: transmitter power, antenna height above average terrain,
 * antenna gain and directivity. Each is a single code character (APRS101 ch. 7): power = p² W,
 * height = 10·2^h ft, gain = g dB, direction = d·45° with 0 meaning omnidirectional. The height
 * code is defined as "ASCII minus '0'" and may run past '9'. Returns null for anything that is not
 * a well-formed PHG string.
 */
fun decodePhg(phg: String): String? {
    val codes = phg.removePrefix("PHG")
    if (codes.length < 4) return null
    val p = codes[0] - '0'; val h = codes[1] - '0'; val g = codes[2] - '0'; val d = codes[3] - '0'
    if (p !in 0..9 || h !in 0..30 || g !in 0..9 || d !in 0..9) return null
    val heightFt = 10L shl h
    val direction = when (d) {
        0 -> "omni"
        in 1..8 -> "${d * 45}°"
        else -> return null
    }
    return "${p * p} W, $heightFt ft, $g dB, $direction"
}

@Serializable
sealed class AprsInfo

// Raw fallback
@Serializable
@SerialName("UnknownAprsInfo")
data class UnknownAprsInfo(val raw: String) : AprsInfo() {
    override fun toString(): String = "Unknown(${raw.take(80)})"
}

/* -----------------------
   Position report (most common)
   Fields:
   - dti: one of ! = / @
   - raw coordinate strings and parsed lat/lon (nullable)
   - symbol table / symbol chars
   - optional timestampString (as-received form)
   - comment (remaining free text)
   - optional parsed extensions (phg, course, speed, altitude)
   ----------------------- */
@Serializable
@SerialName("PositionReport")
data class PositionReport(
    val dti: Char,
    val timestamp: String?,          // raw timestamp if present (e.g. "091842z" or "190116z")
    val latRaw: String?,
    val lonRaw: String?,
    val lat: Double?,                // decimal degrees (positive north)
    val lon: Double?,                // decimal degrees (positive east)
    val symbolTable: Char?,
    val symbol: Char?,
    val comment: String?,
    val phg: String?,                // raw PHG string if present (e.g. "PHG5360")
    val course: Int?,                // degrees clockwise
    val speedKnots: Int?,            // integer knots
    val altitudeFeet: Int?           // /A=123456 optional altitude (feet)
) : AprsInfo() {
    override fun toString(): String {
        return "Position($latRaw,$lonRaw ${lat?.let{"%.6f".format(Locale.US, it)}},${lon?.let{"%.6f".format(Locale.US, it)}} sym=${symbolTable}${symbol} ${comment?.take(40)})"
    }
}

/* -----------------------
   MIC-E: compact position report, half of it in the AX.25 destination address (decoded by
   MicEParser). The position, the position comment ([messageType]) and the ambiguity are only known
   when the destination was available and Mic-E encoded.
   ----------------------- */
@Serializable
@SerialName("MicEInfo")
data class MicEInfo(
    /** ` or ' (current / old GPS data), 0x1C or 0x1D (the same from Rev. 0 beta units). */
    val dti: Char,
    /** The 8 bytes after the DTI as received: longitude, speed/course, symbol code, symbol table. */
    val deviceBlock: String,
    /** Whether the sender can receive APRS messages, from the type code its status text starts
     *  with: ` and Kenwood's > and ] can, ' cannot; false when there is no type code. */
    val messagingCapable: Boolean,
    /** The status text without the type code, the altitude and the device suffix. */
    val comment: String?,
    /** Decimal degrees, positive north / east. */
    val lat: Double? = null,
    val lon: Double? = null,
    /** Degrees clockwise from north, 0..359; null when the sender reports none. */
    val course: Int? = null,
    /** 0..799 */
    val speedKnots: Int? = null,
    val altitudeFeet: Int? = null,
    val symbolTable: Char? = null,
    val symbol: Char? = null,
    /** The Mic-E position comment: "Off Duty", "En Route", "In Service", "Returning", "Committed",
     *  "Special", "Priority", "Emergency", "Custom 0".."Custom 6", or "Unknown" for a mix of
     *  standard and custom bits. */
    val messageType: String? = null,
    /** The sending radio or tracker, e.g. "Kenwood TM-D710", "Yaesu FT5D" - when its type code and
     *  suffix name one. */
    val device: String? = null,
    /** How many trailing latitude digits the sender left out (0..4, from the hundredths of a minute
     *  up to the tens of minutes); the same digits of the longitude are ignored. [lat]/[lon] have
     *  them as 0. */
    val positionAmbiguity: Int = 0,
) : AprsInfo() {
    override fun toString(): String = "MicE(${lat?.let { "%.5f".format(Locale.US, it) }},${lon?.let { "%.5f".format(Locale.US, it) }} " +
        "sym=${symbolTable}${symbol} $messageType device=$device comment=${comment?.take(40)})"
}

/* -----------------------
   Object Report: DTI ';'
   - objectName (9 chars padded), live/kill flag (* or _)
   - timestamp (optional), then location like position
   - comment
   ----------------------- */
@Serializable
@SerialName("ObjectReport")
data class ObjectReport(
    val objectName: String,
    /** Flag '_' after the name; '*' (or a missing flag) is a live object. */
    val killed: Boolean,
    val timestamp: String?,
    val latRaw: String?,
    val lonRaw: String?,
    val lat: Double?,
    val lon: Double?,
    val symbolTable: Char?,
    val symbol: Char?,
    val comment: String?
) : AprsInfo() {
    override fun toString(): String = "Object($objectName${if (killed) " killed" else ""} ${latRaw ?: "no-loc"} ${comment?.take(40)})"
}

/* -----------------------
   APRS Message: DTI ':'
   Format: :ADDRESSEE:Text  (addressee is exactly 9 chars, then ':' then text)
   Optionally contains {msgid at end.
   ----------------------- */
@Serializable
@SerialName("AprsTextMessage")
data class AprsTextMessage(
    val addressee: String,
    val text: String,
    val messageId: String?
) : AprsInfo() {
    override fun toString(): String = "Message(to=$addressee id=${messageId ?: "-" } text=${text.take(40)})"
}

/* -----------------------
   Telemetry: DTI 'T'
   - format: T#seq,analog1,analog2,...,analog5,bbbbbbbb
   - analogs can be integers or decimal strings in practice
   ----------------------- */
@Serializable
@SerialName("TelemetryInfo")
data class TelemetryInfo(
    val sequence: Int?,
    val analog: List<String>,
    val bits: String?   // optional trailing bits (eight binary digits as a string) or null
) : AprsInfo() {
    override fun toString(): String = "Telemetry(seq=${sequence ?: "?"} analog=${analog.joinToString(",")})"
}

/* -----------------------
   Third-party wrapper: DTI '}' - another station's packet relayed by the sender (an iGate gating
   it from the Internet, typically), as TNC2 text "SOURCE>DEST,PATH:info".
   - rawInner: the raw remainder (the encapsulated packet)
   - inner: best-effort parsed inner info (may be null)
   - source: the originating station, null if the inner header is unreadable
   ----------------------- */
@Serializable
@SerialName("ThirdPartyInfo")
data class ThirdPartyInfo(
    val rawInner: String,
    val innerInfo: AprsInfo?,
    val source: String? = null,
) : AprsInfo() {
    override fun toString(): String = "ThirdParty(from=${source ?: "?"} inner=${innerInfo?.javaClass?.simpleName ?: "raw:${rawInner.take(40)}"})"
}

/* -----------------------
   Status: DTI '>'.
   ----------------------- */
@Serializable
@SerialName("StatusInfo")
data class StatusInfo(
    val timestamp: String?,   // optional timestamp (e.g. "091842z")
    val text: String
) : AprsInfo() {
    override fun toString(): String = "Status(${timestamp ?: "-"} ${text.take(60)})"
}

/* -----------------------
   RawGPS: DTI '$'.
   ----------------------- */
@Serializable
@SerialName("RawGpsOrUltimeterInfo")
data class RawGpsOrUltimeterInfo(
    val raw: String,
    val type: String?,          // e.g. "GPRMC"
    val valid: Boolean?,        // A = valid, V = void
    val latitude: Double?,      // decimal degrees
    val longitude: Double?,     // decimal degrees
    val speedKnots: Double?,    // knots
    val course: Double?,        // degrees
    val timestamp: String?,     // hhmmss(.ss)
    val date: String?           // ddmmyy
) : AprsInfo() {
    override fun toString(): String {
        return "RawGPS($type ${latitude ?: "?"},${longitude ?: "?"} spd=${speedKnots ?: "?"})"
    }
}
