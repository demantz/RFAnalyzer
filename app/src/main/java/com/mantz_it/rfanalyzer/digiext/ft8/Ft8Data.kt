package com.mantz_it.rfanalyzer.digiext.ft8

import com.mantz_it.rfanalyzer.database.Coordinates
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketSpec
import com.mantz_it.rfanalyzer.digiext.db.ObjectStores
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.Ft8Packet
import com.mantz_it.rfanalyzer_ipc.digiext.Ft8Phy
import kotlinx.serialization.Serializable
import com.mantz_it.rfanalyzer.digiext.db.LatLon
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore

/**
 * <h1>RF Analyzer - FT8 Data</h1>
 *
 * Module:      Ft8Data.kt
 * Description: The data layer of FT8: [Ft8Spec] (the extension decodes the 77 payload bits,
 *              [Ft8Parser] unpacks the message), the transmitting and called station of a message
 *              as the packet's object keys, and the tracked [Ft8Station] with its family and the
 *              fold of a decode into it.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * An FT8 station (callsign, the object's key), derived from the exchanges where it was the
 * transmitting party. Its message history is not kept here - it is queried from `digital_packets`
 * by callsign.
 */
@Serializable
data class Ft8Station(
    /** The station it last called - a callsign, never a CQ/QRZ/DE token. Sticky: a CQ calls nobody
     *  and keeps it. */
    val lastCalledCallsign: String? = null,
    /** Sticky: kept across exchanges that don't carry a grid. */
    val grid: String? = null,
    val lastSnr: Float,
    val bestSnr: Float,
    val lastTimeDeltaMs: Long = 0,
    /** The row's preview is rendered from this (see [ft8MessageSummary]). */
    val lastMessage: Ft8Message? = null,
)

/** A decoded FT8 transmission. */
typealias Ft8Decode = StoredPacket<Ft8Phy, Ft8Message>

/** The 77 message bits, MSB first, as '0'/'1' - the first 77 bits of [raw][StoredPacket.raw]. */
val Ft8Decode.messageBits: String get() = rawBits(0, FT8_MESSAGE_BIT_COUNT)

/** The 14 CRC bits that follow the message bits. */
val Ft8Decode.crcBits: String get() = rawBits(FT8_MESSAGE_BIT_COUNT, FT8_CRC_BIT_COUNT)

private const val FT8_MESSAGE_BIT_COUNT = 77
private const val FT8_CRC_BIT_COUNT = 14

private fun Ft8Decode.rawBits(from: Int, count: Int): String {
    val bytes = raw ?: return ""
    return (from until (from + count).coerceAtMost(bytes.size * 8)).joinToString("") { i ->
        if ((bytes[i / 8].toInt() shr (7 - i % 8)) and 1 == 1) "1" else "0"
    }
}

/**
 * Returns (transmittingCallsign, calledCallsign) for an FT8 message that carries a resolvable
 * transmitter, else null. The second callsign slot is the transmitting station (also in a CQ);
 * TextMessage/TelemetryMessage carry no callsign at all. The called station is null when the first
 * slot holds no station: a token (CQ, "CQ DX", QRZ, DE) or a hash nobody was heard with.
 *
 * The transmitter is also the packet's `objectKey` (the [Ft8Station]'s key) and the
 * called station its `secondaryObjectKey` ([Ft8Spec]).
 */
internal fun ft8Transmitter(message: Ft8Message): Pair<String, String?>? {
    val (transmitter, called) = when (message) {
        is StandardMessage -> message.call2 to message.call1
        is EUVHFMessage -> message.call2 to message.call1
        is RTTYMessage -> message.call2 to message.call1
        is NonstandardCallMessage -> message.call2 to message.call1
        is FieldDayMessage -> message.call2 to message.call1
        is EuVhfContestMessage -> message.call2 to message.call1
        // the DXpedition sends, answering two stations
        is DXpeditionMessage -> message.call3 to message.call2
        is TextMessage, is TelemetryMessage, is UnknownMessage -> return null
    }
    return transmitter?.knownCall()?.let { it to called?.takeUnless { c -> c.token }?.knownCall() }
}

/** The callsign, unless it is a hash nobody was heard with ("<...>"). */
private fun Callsign.knownCall(): String? = callsign.takeUnless { fromHash && it == "..." }

private fun ft8Grid(message: Ft8Message): String? = when (message) {
    is StandardMessage -> (message.report as? LocationReport)?.grid
    is EUVHFMessage -> (message.report as? LocationReport)?.grid
    is EuVhfContestMessage -> message.grid
    else -> null
}

internal val Ft8StationFamily = ObjectFamily(
    kind = ObjectKind.FT8_STATION,
    serializer = Ft8Station.serializer(),
    displayName = { key, _ -> key },
    // A station's position is the centre of its grid square - too coarse for a trail.
    position = { station -> station.grid?.let { Coordinates.fromGridLocator(it) }?.let { LatLon(it.latitude, it.longitude) } },
    trail = false,
)

internal suspend fun applyFt8Decode(stations: ObjectStore<Ft8Station>, decode: Ft8Decode) {
    val (transmitter, called) = ft8Transmitter(decode.data) ?: return
    val snr = decode.phy.snr
    stations.update(transmitter, decode.timestampMs, decode.knownFrequencyHz, create = { Ft8Station(lastSnr = snr, bestSnr = snr) }) {
        it.copy(
            lastCalledCallsign = called ?: it.lastCalledCallsign,
            grid = ft8Grid(decode.data) ?: it.grid,
            lastSnr = snr,
            bestSnr = maxOf(it.bestSnr, snr),
            lastTimeDeltaMs = decode.phy.timeDeltaMs,
            lastMessage = decode.data,
        )
    }
}

/** The extension decodes the signal into the 77 payload bits, the app unpacks the message ([Ft8Parser]). */
internal object Ft8Spec : DigitalPacketSpec<Ft8Packet, Ft8Phy, Ft8Message>(
    mode = DigitalMode.FT8,
    phySerializer = Ft8Phy.serializer(),
    dataSerializer = Ft8Message.serializer(),
    parserVersion = Ft8Parser.PARSER_VERSION,
) {
    override fun phyOf(packet: Ft8Packet) = packet.phy
    override fun parse(raw: Bytes, phy: Ft8Phy) = Ft8Parser.parse(raw)

    /** The transmitting callsign; `""` for a free-text or telemetry message, which carries none. */
    override fun objectKey(raw: Bytes?, phy: Ft8Phy, data: Ft8Message) = ft8Transmitter(data)?.first ?: ""
    override fun secondaryObjectKey(raw: Bytes?, phy: Ft8Phy, data: Ft8Message) = ft8Transmitter(data)?.second ?: ""
    override suspend fun fold(packet: Ft8Decode, objects: ObjectStores) = applyFt8Decode(objects[Ft8StationFamily], packet)
}
