package com.mantz_it.rfanalyzer.digiext.ax25

import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore
import kotlinx.serialization.Serializable

/**
 * <h1>RF Analyzer - Packet Station</h1>
 *
 * Module:      PacketStation.kt
 * Description: A packet-radio station heard sending something other than APRS: a BBS, a node, a
 *              user connecting to them. Keyed `"PACKET:<call>"` ([packetStationKey]) - the AX.25
 *              mode's APRS stations use `STATION:`/`OBJECT:`. A station only called by another (the
 *              destination of a connect request) is listed too, without packets of its own until it
 *              is heard.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Serializable
data class PacketStation(
    val callsign: String,
    /** Its last beacon or ID text (an unconnected text frame). */
    val beacon: String? = null,
    /** Its alias from a NET/ROM routing broadcast. */
    val nodeAlias: String? = null,
    /** Routes its last NET/ROM broadcast offered. */
    val routes: Int? = null,
    /** Who its last "Mail for:" beacon listed. */
    val mailFor: List<String>? = null,
    /** Messages and bulletins its FBB unproto lists announced. */
    val bulletins: Int = 0,
    val lastBulletin: FbbEntry? = null,
    /** Stations it had connected-mode frames with, latest first. */
    val peers: List<String> = emptyList(),
    /** The type of its last frame ("SABM", "I S2 R5", "UI"). */
    val lastFrame: String? = null,
) {
    /** BBS when it announces mail or bulletins, node when it routes, else none. */
    val role: String?
        get() = when {
            nodeAlias != null || beacon?.contains("node", ignoreCase = true) == true -> "NODE"
            mailFor != null || bulletins > 0 || beacon?.contains("BBS") == true -> "BBS"
            else -> null
        }
}

internal fun packetStationKey(callsign: String) = "PACKET:$callsign"

internal val PacketStationFamily = ObjectFamily(
    kind = ObjectKind.PACKET_STATION,
    serializer = PacketStation.serializer(),
    displayName = { _, station -> station.callsign },
)

private const val MAX_PEERS = 8

/** A non-APRS frame: its source's state, and for a connected-mode frame its destination's peers. */
internal suspend fun applyPacketFrame(stations: ObjectStore<PacketStation>, frame: Ax25Frame) {
    val message = frame.data
    if (message.aprs != null) return
    val source = message.source?.toString() ?: return
    val destination = message.destination?.toString()
    val type = message.frameType
    val connected = type != null && !type.isUi && destination != null
    stations.update(packetStationKey(source), frame.timestampMs, frame.knownFrequencyHz, create = { PacketStation(source) }) { s ->
        var st = s.copy(lastFrame = type?.label)
        when (val c = message.content) {
            is Ax25Content.MailBeacon -> st = st.copy(mailFor = c.callsigns)
            is Ax25Content.BulletinList -> st = st.copy(bulletins = st.bulletins + c.entries.size, lastBulletin = c.entries.last())
            is Ax25Content.NetRomNodes -> st = st.copy(nodeAlias = c.alias, routes = c.routes.size)
            is Ax25Content.Text -> if (type?.isUi != false) st = st.copy(beacon = c.text.take(BEACON_CHARS))
            null -> {}
        }
        if (connected) st = st.copy(peers = (listOf(destination!!) + st.peers).distinct().take(MAX_PEERS))
        st
    }
    if (connected) {
        // the called station: only its last-seen time moves, it has not been heard itself
        stations.update(packetStationKey(destination!!), frame.timestampMs, frame.knownFrequencyHz, counts = false,
            create = { PacketStation(destination) }) { s -> s.copy(peers = (listOf(source) + s.peers).distinct().take(MAX_PEERS)) }
    }
}

private const val BEACON_CHARS = 200
