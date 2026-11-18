package com.mantz_it.rfanalyzer.digiext.ax25

import com.mantz_it.rfanalyzer.digiext.aprs.AprsInfo
import com.mantz_it.rfanalyzer.digiext.aprs.AprsInfoParser
import com.mantz_it.rfanalyzer.digiext.aprs.AprsTextMessage
import com.mantz_it.rfanalyzer.digiext.aprs.ObjectReport
import com.mantz_it.rfanalyzer.digiext.aprs.ThirdPartyInfo
import com.mantz_it.rfanalyzer.digiext.aprs.applyAprsFields
import com.mantz_it.rfanalyzer.digiext.aprs.originReport
import com.mantz_it.rfanalyzer.digiext.db.DEFAULT_IDLE_TIMEOUT_MS
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketSpec
import com.mantz_it.rfanalyzer.digiext.db.LatLon
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore
import com.mantz_it.rfanalyzer.digiext.db.ObjectStores
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Packet
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Phy
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import kotlinx.serialization.Serializable

/**
 * <h1>RF Analyzer - AX.25 Data</h1>
 *
 * Module:      Ax25Data.kt
 * Description: The data layer of AX.25: [Ax25Parser] decodes a frame's address, control and PID
 *              fields and its information field - as APRS or as other packet-radio traffic - into
 *              an [Ax25Message], [Ax25Spec] is the mode's packet spec, and APRS reports are folded
 *              into [AprsStation] objects, a state shared with LoRa-APRS.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * An APRS reporting station or named APRS Object - what both the Overview and Map subtabs show.
 * Shared by AX.25/APRS and LoRa-APRS (the same information-field semantics over a different PHY).
 *
 * Its key is `"STATION:<fromCall>"` or `"OBJECT:<objectName>"` ([ax25IdentityOf]) for AX.25 and
 * `"LoraAprs:<fromCall>"` for LoRa-APRS: a station and an Object it reports can share one
 * [callsign] but never one key. The fields are sticky: a report updates only the field groups it
 * carries (see [applyAprsFields]).
 */
@Serializable
data class AprsStation(
    /** The reporting station (fromCall); for an Object, the station that reported it. */
    val callsign: String,
    /** Set only for an APRS Object. */
    val objectName: String? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val symbolTable: Char? = null,
    val symbol: Char? = null,
    val courseDeg: Double? = null,
    val speedKnots: Double? = null,
    val altitudeFeet: Int? = null,
    val comment: String? = null,
    val killed: Boolean = false,
    /** The last report; the row text is rendered from it (see [aprsStationRowText]). */
    val lastInfo: AprsInfo? = null,
) {
    val isObject: Boolean get() = objectName != null

    /** Folds one report into the station. */
    fun withInfo(info: AprsInfo): AprsStation {
        var station = this
        applyAprsFields(
            info,
            onPosition = { newLat, newLon -> station = station.copy(lat = newLat, lon = newLon) },
            onSymbol = { table, sym -> station = station.copy(symbolTable = table, symbol = sym) },
            onCourseSpeed = { course, speed -> station = station.copy(courseDeg = course, speedKnots = speed) },
            onAltitude = { feet -> station = station.copy(altitudeFeet = feet) },
            onComment = { text -> station = station.copy(comment = text) },
        )
        if (info is ObjectReport) station = station.copy(killed = info.killed)
        return station.copy(lastInfo = info)
    }
}

/** The key of an AX.25 APRS station or Object - both the stored frame's `objectKey` ([Ax25Spec]) and
 *  its station's key ([applyAx25Frame]). */
internal fun ax25IdentityOf(fromCall: String, info: AprsInfo): String {
    val objectName = (info as? ObjectReport)?.objectName
    return if (objectName != null) "OBJECT:$objectName" else "STATION:$fromCall"
}

/** Shared with LoRa-APRS, which stores the same state type under its own kind. */
internal fun aprsStationFamily(kind: ObjectKind, idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS) = ObjectFamily(
    kind = kind,
    serializer = AprsStation.serializer(),
    displayName = { _, station -> station.objectName ?: station.callsign },
    position = { station -> station.lat?.let { lat -> station.lon?.let { lon -> LatLon(lat, lon) } } },
    idleTimeoutMs = idleTimeoutMs,
)

internal val AprsStationFamily = aprsStationFamily(ObjectKind.APRS_STATION)

/**
 * Folds an APRS frame into its station or Object. Relayed (third-party) traffic is folded twice: the
 * relaying station is heard but takes nothing from the packet it relays (see [applyAprsFields]),
 * and the packet itself goes to its originator - or the Object it reports - as if it had been heard
 * directly, which is how it gets onto the map.
 */
internal suspend fun applyAx25Frame(stations: ObjectStore<AprsStation>, frame: Ax25Frame) {
    val info = frame.data.aprs ?: return // frames without APRS content aren't tracked
    val callsign = frame.data.source?.toString() ?: return
    suspend fun fold(from: String, report: AprsInfo) {
        val objectName = (report as? ObjectReport)?.objectName
        stations.update(ax25IdentityOf(from, report), frame.timestampMs, frame.knownFrequencyHz, create = { AprsStation(from, objectName) }) {
            it.withInfo(report)
        }
    }
    fold(callsign, info)
    val relayed = (info as? ThirdPartyInfo)?.takeIf { ax25OriginIdentityOf(callsign, it) != null } ?: return
    relayed.originReport()?.let { (origin, report) -> fold(origin, report) }
}

/** The originator (or reported Object) of relayed traffic - see [applyAx25Frame]. */
private fun ax25OriginIdentityOf(relay: String, info: ThirdPartyInfo): String? {
    val (origin, report) = info.originReport() ?: return null
    return ax25IdentityOf(origin, report).takeIf { it != ax25IdentityOf(relay, info) }
}

/** One address subfield of an AX.25 frame. */
@Serializable
data class Ax25Address(
    val callsign: String,
    /** 0..15 */
    val ssid: Int,
    /** The H bit: a digipeater that has already repeated the frame. */
    val hasBeenRepeated: Boolean = false,
) {
    override fun toString(): String = if (ssid == 0) callsign else "$callsign-$ssid"
}

/**
 * What an AX.25 frame says: its address field, control field and PID, and its information field -
 * as APRS ([aprs]) or as one of the other kinds of packet-radio traffic ([content]). The
 * information field's bytes are not repeated here; they are the tail of the frame's raw bytes
 * ([info]).
 */
@Serializable
data class Ax25Message(
    /** Destination, source, then the digipeaters; empty for a frame without a terminated address field. */
    val addresses: List<Ax25Address>,
    /** The control octet. */
    val control: Bytes,
    val pid: Int?,
    val aprs: AprsInfo?,
    /** The information field when it is not APRS. */
    val content: Ax25Content? = null,
) {
    val destination: Ax25Address? get() = addresses.getOrNull(0)
    val source: Ax25Address? get() = addresses.getOrNull(1)
    val digipeaters: List<Ax25Address> get() = addresses.drop(2)

    /** SABM, UI, I, RR, … - null for a frame without a control octet. */
    val frameType: Ax25FrameType?
        get() = control.toByteArray().firstOrNull()?.let { ax25FrameType(it.toInt() and 0xFF, destination, source) }

    /** Where the information field starts in the frame. */
    val headerLength: Int get() = if (addresses.isEmpty()) 0 else 7 * addresses.size + control.size + (if (pid != null) 1 else 0)
}

/** A received AX.25 frame. */
typealias Ax25Frame = StoredPacket<Ax25Phy, Ax25Message>

/** The information field: the frame's bytes after the address, control and PID fields. */
val Ax25Frame.info: Bytes get() = raw?.let { it.copyOfRange(data.headerLength.coerceAtMost(it.size), it.size) } ?: Bytes.EMPTY

/**
 * Decodes an AX.25 frame (address field to end of information field; no flags, no FCS).
 *
 * The station is the frame's source address. APRS's text form `SRC>DST,PATH:info` (TNC2) only
 * exists where a frame is written down as text - LoRa-APRS, or the inner packet of third-party
 * traffic - never inside an on-air frame, so the information field is parsed as it is.
 *
 * APRS travels only in UI frames with PID 0xF0 whose information field starts with an APRS data
 * type identifier. Everything else - a BBS's "Mail for:" beacon, an FBB bulletin list, a node's
 * ID, the text of a connected session (I frames, where "TEST…" would otherwise parse as APRS
 * telemetry) - is [Ax25Content].
 */
object Ax25Parser {
    /** Bump whenever [parse]'s output changes for the same frame; stored frames are re-parsed. */
    const val PARSER_VERSION = 1

    private const val PID_NO_LAYER_3 = 0xF0

    /** The first octet of an APRS information field (APRS 1.0.1 ch. 5), incl. old Mic-E (0x1C/0x1D). */
    private val APRS_DATA_TYPES = "!#\$%')*+,./:;<=>?@T[_`{}\u001c\u001d".toSet()

    /** Telemetry is the only identifier that is a letter, so free text starting with "T" would
     *  match it; real telemetry always continues with '#' ("T#005,..." / "T#MIC"). */
    private fun startsLikeAprs(info: ByteArray): Boolean {
        val dti = (info.firstOrNull()?.toInt() ?: return false).and(0xFF).toChar()
        return dti in APRS_DATA_TYPES && (dti != 'T' || info.getOrNull(1) == '#'.code.toByte())
    }

    fun parse(raw: Bytes): Ax25Message {
        val frame = raw.toByteArray()
        var offset = 0
        val addresses = mutableListOf<Ax25Address>()

        // One or more 7-octet address subfields; the last subfield's LSB (extension bit) is 1.
        var terminated = false
        while (offset + 7 <= frame.size) {
            val callsign = (0 until 6).map { ((frame[offset + it].toInt() and 0xFF) shr 1).toChar() }.joinToString("").trim()
            val ssidByte = frame[offset + 6].toInt() and 0xFF
            addresses += Ax25Address(callsign, ssid = (ssidByte shr 1) and 0x0F, hasBeenRepeated = (ssidByte and 0x80) != 0)
            offset += 7
            if (ssidByte and 0x01 != 0) { terminated = true; break }
        }
        // No end-of-address marker: a malformed frame, kept whole as its information field.
        if (!terminated) return Ax25Message(emptyList(), Bytes.EMPTY, pid = null, aprs = null)
        if (offset >= frame.size) return Ax25Message(addresses, Bytes.EMPTY, pid = null, aprs = null)

        // one control octet: the modulo-128 form is negotiated per connection, not visible in a frame
        val control = Bytes(frame.copyOfRange(offset, offset + 1))
        val type = ax25FrameType(frame[offset].toInt() and 0xFF, addresses.getOrNull(0), addresses.getOrNull(1))
        offset++

        // only I and UI frames have a PID
        val pid = if (offset < frame.size && (type.isInformation || type.isUi)) frame[offset].toInt() and 0xFF else null
        if (pid != null) offset++
        val info = frame.copyOfRange(offset, frame.size)
        val aprs = if (pid == PID_NO_LAYER_3 && type.isUi && startsLikeAprs(info)) AprsInfoParser.parseAprsInfo(info, addresses[0].callsign) else null
        val content = if (aprs == null) Ax25ContentParser.parse(addresses.getOrNull(0), pid, info) else null
        return Ax25Message(addresses, control, pid, aprs, content)
    }
}

/** AX.25 framing and APRS are parsed app-side; the extension sends the frame's bytes. */
internal object Ax25Spec : DigitalPacketSpec<Ax25Packet, Ax25Phy, Ax25Message>(
    mode = DigitalMode.AX25,
    phySerializer = Ax25Phy.serializer(),
    dataSerializer = Ax25Message.serializer(),
    parserVersion = Ax25Parser.PARSER_VERSION,
) {
    override fun phyOf(packet: Ax25Packet) = packet.phy
    override fun parse(raw: Bytes, phy: Ax25Phy) = Ax25Parser.parse(raw)

    /** The reporting station, or an Object it reports ([ax25IdentityOf]); for any other frame its
     *  sender as a packet station ([packetStationKey]). */
    override fun objectKey(raw: Bytes?, phy: Ax25Phy, data: Ax25Message): String {
        val callsign = data.source?.toString() ?: return ""
        return data.aprs?.let { ax25IdentityOf(callsign, it) } ?: packetStationKey(callsign)
    }

    /** An APRS message's addressee, so the addressee's detail lists the messages sent to it; the
     *  originator of relayed traffic, so its detail lists the packets relayed for it; the other end
     *  of a connected-mode frame. */
    override fun secondaryObjectKey(raw: Bytes?, phy: Ax25Phy, data: Ax25Message): String {
        (data.aprs as? AprsTextMessage)?.addressee?.trim()?.takeIf { it.isNotEmpty() }?.let { return "STATION:$it" }
        (data.aprs as? ThirdPartyInfo)?.let { relayed ->
            return data.source?.let { ax25OriginIdentityOf(it.toString(), relayed) } ?: ""
        }
        if (data.aprs != null) return ""
        val type = data.frameType ?: return ""
        return data.destination?.takeIf { !type.isUi }?.let { packetStationKey(it.toString()) } ?: ""
    }

    /** "CALL-SSID" as written; the stored form keeps call and SSID apart. */
    override fun searchTerms(packet: Ax25Frame) = packet.data.addresses.map { it.toString() }

    override suspend fun fold(packet: Ax25Frame, objects: ObjectStores) {
        applyAx25Frame(objects[AprsStationFamily], packet)
        applyPacketFrame(objects[PacketStationFamily], packet)
    }
}
