package com.mantz_it.rfanalyzer.digiext.lora

import com.mantz_it.rfanalyzer.digiext.ax25.aprsReportCallsigns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLogLine
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLog
import com.mantz_it.rfanalyzer.digiext.LiveFeed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import com.mantz_it.rfanalyzer.digiext.db.markerId
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.ui.common.RecentPacketsList
import com.mantz_it.rfanalyzer.digiext.ui.common.RecentPacket
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.DataArray
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.eu868DataRateLabel
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.lorawanMTypeLabel
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.lorawanDeviceName
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.lorawanEuiVendor
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.lorawanMacCommandLabel
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.lorawanNetIdLookup
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreNode
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.isMeshComChat
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.meshComAckFields
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.meshComAckText
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.meshComCallsigns
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.meshComFields
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.meshComFrameKind
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.meshComPositionOf
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.meshComText
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNode
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.meshtasticHwModelLabel
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.meshtasticModemPresetGuess
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.meshtasticPortnumDescription
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.meshtasticPortnumLabel
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.meshtasticRoleLabel
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.formatMeshtasticUptime
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.meshtasticNodeLabel
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.meshtasticRoutingErrorLabel
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.meshtasticTelemetryReadings
import com.mantz_it.rfanalyzer.digiext.ui.common.aprsFiUrl
import com.mantz_it.rfanalyzer.digiext.ui.common.qrzUrl
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
import com.mantz_it.rfanalyzer.digiext.aprs.symbolOrNull
import com.mantz_it.rfanalyzer.digiext.ax25.aprsReportFields
import com.mantz_it.rfanalyzer.digiext.ax25.aprsReportText
import com.mantz_it.rfanalyzer.digiext.ax25.baseCallsign
import com.mantz_it.rfanalyzer.digiext.ax25.positionOrNull
import com.mantz_it.rfanalyzer.ui.composable.AprsSymbolIcon
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPhy
import com.mantz_it.rfanalyzer.digiext.ui.MapLayer
import com.mantz_it.rfanalyzer.digiext.ui.OverviewContext
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.CardListPadding
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.Fields
import com.mantz_it.rfanalyzer.digiext.ui.common.IdentityText
import com.mantz_it.rfanalyzer.digiext.ui.common.LiveTicker
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketCard
import com.mantz_it.rfanalyzer.digiext.ui.common.PayloadText
import com.mantz_it.rfanalyzer.digiext.ui.common.RadioLine
import com.mantz_it.rfanalyzer.digiext.ui.common.RawBytes
import com.mantz_it.rfanalyzer.digiext.ui.common.RawSection
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLines
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerWaiting
import com.mantz_it.rfanalyzer.digiext.ui.common.TypeTag
import com.mantz_it.rfanalyzer.digiext.ui.common.WarningText
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.formatDateTime
import com.mantz_it.rfanalyzer.digiext.ui.common.formatFrequency
import com.mantz_it.rfanalyzer.digiext.ui.common.formatLatLon
import com.mantz_it.rfanalyzer.digiext.ui.common.formatPosition
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.common.oneLine
import com.mantz_it.rfanalyzer.digiext.ui.common.packetActions
import com.mantz_it.rfanalyzer.digiext.ui.common.plural
import com.mantz_it.rfanalyzer.digiext.ui.common.rememberLatestLive
import com.mantz_it.rfanalyzer.digiext.ui.common.toHexDump
import com.mantz_it.rfanalyzer.digiext.ui.trackedObjectActions
import com.mantz_it.rfanalyzer.ui.composable.MapMarker
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets

/**
 * <h1>RF Analyzer - LoRa Card</h1>
 *
 * Module:      LoraCard.kt
 * Description: How LoRa frames read, for all five protocols: the card (who sent it, on which
 *              channel or port, the message itself in body size; problems as one warning word),
 *              the texts rows and tickers show, node facts and actions, and the Map layers.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The position a single LoRa frame reports, whichever protocol carries it. */
private fun LoraPayloadMessage.positionOrNull(): Pair<Double, Double>? = when (this) {
    is LoraAprsPacket -> info.positionOrNull()
    is MeshCorePacket -> (info as? MeshCoreAdvert)?.let { advert ->
        val lat = advert.latitude; val lon = advert.longitude
        if (advert.hasLocation && lat != null && lon != null) lat to lon else null
    }
    is MeshtasticPacket -> when (val info = info) {
        is MeshtasticPosition -> info.latitude?.let { lat -> info.longitude?.let { lon -> lat to lon } }
        is MeshtasticWaypoint -> info.latitude?.let { lat -> info.longitude?.let { lon -> lat to lon } }
        else -> null
    }
    is MeshComFrame -> meshComPositionOf(this)
    else -> null
}

private fun Bytes.hex(): String = toHex()

internal fun meshCoreNodeTypeLabel(nodeType: Int): String = when (nodeType) {
    MESHCORE_NODE_CHAT -> "Chat node"
    MESHCORE_NODE_REPEATER -> "Repeater"
    MESHCORE_NODE_ROOM_SERVER -> "Room server"
    MESHCORE_NODE_SENSOR -> "Sensor"
    else -> "Node type $nodeType"
}

private fun meshCoreNodeTypeFilterLabel(filter: Int): String =
    (1..4).filter { filter and (1 shl it) != 0 }.joinToString(", ") { meshCoreNodeTypeLabel(it) }.ifEmpty { "0x%02X".format(Locale.US, filter) }

/** "Group text", "Advert", "Anonymous request" - a MeshCore payload type in words. */
internal fun meshCorePayloadName(type: MeshCorePayloadType): String = when (type) {
    MeshCorePayloadType.REQ -> "Request"
    MeshCorePayloadType.RESPONSE -> "Response"
    MeshCorePayloadType.TXT_MSG -> "Direct message"
    MeshCorePayloadType.ACK -> "Acknowledgement"
    MeshCorePayloadType.ADVERT -> "Advert"
    MeshCorePayloadType.GRP_TXT -> "Group text"
    MeshCorePayloadType.GRP_DATA -> "Group data"
    MeshCorePayloadType.ANON_REQ -> "Anonymous request"
    MeshCorePayloadType.PATH -> "Path"
    MeshCorePayloadType.TRACE -> "Trace"
    MeshCorePayloadType.MULTIPART -> "Multipart"
    MeshCorePayloadType.CONTROL -> "Control"
    MeshCorePayloadType.RAW_CUSTOM -> "Custom"
    else -> "Reserved type"
}

private fun meshCoreRouteName(route: MeshCoreRouteType): String = when (route) {
    MeshCoreRouteType.TRANSPORT_FLOOD -> "transport flood"
    MeshCoreRouteType.FLOOD -> "flood"
    MeshCoreRouteType.DIRECT -> "direct"
    MeshCoreRouteType.TRANSPORT_DIRECT -> "transport direct"
}

/** The icon of a MeshCore sender: what kind of node, or a chat bubble for a message. */
private fun meshCoreIcon(info: MeshCoreInfo): ImageVector = when (info) {
    is MeshCoreAdvert -> when {
        info.isRoomServer -> Icons.Default.Public
        info.isRepeater -> Icons.Default.Router
        info.isSensor -> Icons.Default.Sensors
        else -> Icons.Default.Person
    }
    is MeshCoreGroupText -> Icons.Default.ChatBubble
    is MeshCoreEncryptedEnvelope, is MeshCoreAnonRequest -> Icons.Default.Lock
    else -> Icons.Default.DataArray
}

/** Who a frame is from, as far as it says: a node's name, a Meshtastic node number, a device. */
internal fun loraFrameIdentity(message: LoraPayloadMessage): String = when (message) {
    is RawLoraPayload -> "Unknown protocol"
    is MeshCorePacket -> when (val info = message.info) {
        is MeshCoreAdvert -> info.name?.takeIf { it.isNotBlank() } ?: info.publicKey.hex().take(8)
        is MeshCoreGroupText -> info.senderName.ifBlank { "Unnamed" }
        is MeshCoreAnonRequest -> info.senderPublicKey.hex().take(8)
        is MeshCoreDiscoverResponse -> info.publicKey.hex().take(8)
        is MeshCoreDiscoverRequest -> "Discovery request"
        else -> meshCorePayloadName(message.payloadType)
    }
    is MeshtasticPacket -> meshtasticNodeLabel(message.sender)
    is LoRaWanFrame -> if (message.devAddr != null || message.devEui != null) lorawanDeviceName(message.devAddr, message.devEui) else lorawanMTypeLabel(message.mtype)
    is LoraAprsPacket -> message.fromCall
    is MeshComFrame -> message.origin
    is MeshComAck -> "Gateway"
}

/** The small text after the identity: the channel or kind of message, the protocol where the
 *  section does not already say it. */
internal fun loraFrameKind(message: LoraPayloadMessage): String? = when (message) {
    is RawLoraPayload -> null
    is MeshCorePacket -> when (val info = message.info) {
        is MeshCoreGroupText -> info.channelName
        is MeshCoreGroupData -> "${info.channelName} data"
        // the payload text names the discovery ("Discovery response: Repeater, …"), not "Control"
        is MeshCoreAdvert, is MeshCoreDiscoverRequest, is MeshCoreDiscoverResponse -> null
        else -> meshCorePayloadName(message.payloadType).takeIf { loraFrameIdentity(message) != it }
    }
    is MeshtasticPacket -> meshtasticPortnumOf(message.info)?.let(::meshtasticPortnumLabel)
        ?.let { if (message.dest != 0xFFFFFFFFL) "$it to ${meshtasticNodeLabel(message.dest)}" else it }
    is LoRaWanFrame -> lorawanMTypeLabel(message.mtype).takeIf { message.devAddr != null || message.devEui != null }
    is LoraAprsPacket -> message.path.takeIf { it.isNotEmpty() }?.let { "via " + it.joinToString(", ") }
    is MeshComFrame -> meshComFrameKind(message)
    is MeshComAck -> "Acknowledgement"
}

/** What a frame says, in words - the message text before anything else. */
internal fun loraPayloadText(frame: LoraFrame): String = when (val message = frame.data) {
    is RawLoraPayload -> "${plural(frame.raw?.size ?: 0, "byte", "bytes")} of an unknown protocol"
    is MeshCorePacket -> when (val info = message.info) {
        is MeshCoreAdvert -> metaLine(
            "${meshCoreNodeTypeLabel(info.nodeType)} advert",
            info.latitude?.takeIf { info.hasLocation }?.let { lat -> info.longitude?.let { lon -> formatLatLon(lat, lon) } },
        )
        is MeshCoreGroupText -> info.text
        is MeshCoreGroupData -> "Data type ${info.dataType} · ${plural(info.data.size, "byte", "bytes")}"
        is MeshCoreEncryptedEnvelope -> "${plural(info.ciphertext.size, "byte", "bytes")}, encrypted"
        is MeshCoreAnonRequest -> "${plural(info.ciphertext.size, "byte", "bytes")}, encrypted"
        is MeshCoreAck -> "Acknowledgement ${info.checksum.hex()}"
        is MeshCoreTrace -> "Trace over ${plural(info.pathHashes.size, "hop", "hops")}" + info.snrs.takeIf { it.isNotEmpty() }?.joinToString(" · ", prefix = ": ") { "%.1f dB".format(Locale.US, it) }.orEmpty()
        is MeshCoreDiscoverRequest -> "Discovery request for ${meshCoreNodeTypeFilterLabel(info.typeFilter)}"
        is MeshCoreDiscoverResponse -> "Discovery response: ${meshCoreNodeTypeLabel(info.nodeType)}, heard the request at %.1f dB".format(Locale.US, info.snr)
        is MeshCoreOpaquePayload -> "${meshCorePayloadName(message.payloadType)} · ${plural(info.payload.size, "byte", "bytes")}"
    }
    is MeshtasticPacket -> meshtasticText(message.info)
    is LoRaWanFrame -> when {
        message.devEui != null -> message.joinEui?.let { "JoinEUI $it" } ?: "Join request"
        message.mtype.isData -> metaLine(message.fPort?.let { "FPort $it" }, message.fCnt?.let { "FCnt $it" }, message.fOpts.takeIf { it.isNotEmpty() }?.let { plural(it.size, "MAC command", "MAC commands") })
            .ifEmpty { lorawanMTypeLabel(message.mtype) }
        else -> "${plural(message.encryptedTail.size, "byte", "bytes")}, encrypted"
    }
    is LoraAprsPacket -> aprsReportText(message.info)
    is MeshComFrame -> meshComText(message)
    is MeshComAck -> meshComAckText(message)
}

/** One line of a Meshtastic payload. */
private fun meshtasticText(info: MeshtasticInfo): String = when (info) {
    is MeshtasticTextMessage -> info.text
    is MeshtasticPosition -> info.latitude?.let { lat -> info.longitude?.let { lon -> metaLine(formatLatLon(lat, lon), info.altitudeM?.let { "$it m" }) } } ?: "Position without a fix"
    is MeshtasticNodeInfo -> metaLine(info.longName ?: info.nodeId, info.shortName, info.hwModel?.let(::meshtasticHwModelLabel))
    is MeshtasticTelemetry -> meshtasticTelemetryReadings(info).take(4).joinToString(" · ") { "${it.first} ${it.second}" }.ifEmpty { "Telemetry" }
    is MeshtasticRouting -> info.errorReason?.let(::meshtasticRoutingErrorLabel) ?: "Route discovery"
    is MeshtasticTraceroute -> "Traceroute over ${plural(info.route.route.size, "hop", "hops")}"
    is MeshtasticNeighborInfo -> plural(info.neighbors.size, "neighbour", "neighbours")
    is MeshtasticWaypoint -> metaLine(info.name ?: "Waypoint #${info.id}", info.description?.takeIf { it.isNotBlank() })
    is MeshtasticOpaquePayload -> meshtasticPortnumDescription(info.portnum) ?: meshtasticPortnumLabel(info.portnum)
    is MeshtasticEncrypted -> "${plural(info.ciphertext.size, "byte", "bytes")}, encrypted (no key for this channel)"
}

/** One line for a frame, for list lines and map popups: who, what. */
fun loraFrameSummary(frame: LoraFrame): String = when (val message = frame.data) {
    is MeshCorePacket -> when (message.info) {
        is MeshCoreGroupText -> "${loraFrameKind(message)} · ${loraFrameIdentity(message)}: ${loraPayloadText(frame)}"
        else -> loraPayloadText(frame)
    }
    is LoraAprsPacket -> loraPayloadText(frame)
    else -> metaLine(loraFrameKind(message), loraPayloadText(frame))
}

/** The portnum of a decrypted Meshtastic payload, null while encrypted. */
internal fun meshtasticPortnumOf(info: MeshtasticInfo): Int? = when (info) {
    is MeshtasticTextMessage -> info.portnum
    is MeshtasticPosition -> info.portnum
    is MeshtasticNodeInfo -> info.portnum
    is MeshtasticTelemetry -> info.portnum
    is MeshtasticRouting -> info.portnum
    is MeshtasticTraceroute -> info.portnum
    is MeshtasticNeighborInfo -> info.portnum
    is MeshtasticWaypoint -> info.portnum
    is MeshtasticOpaquePayload -> info.portnum
    is MeshtasticEncrypted -> null
}

/** The one thing wrong with a frame, if anything: CRC error, unreliable decode, bad signature. */
internal fun loraWarning(phy: LoraPhy, message: LoraPayloadMessage): String? = when {
    phy.hasCrc && !phy.crcValid -> "CRC error"
    !phy.hasCrc && !isIntact(phy) -> "Unreliable"
    ((message as? MeshCorePacket)?.info as? MeshCoreAdvert)?.signatureValid == false -> "Bad signature"
    (message as? MeshComFrame)?.checksumValid == false -> "Bad checksum"
    else -> null
}

/** What varies from frame to frame and matters: hops and signal. */
internal fun loraMeta(frame: LoraFrame): String = metaLine(
    when (val message = frame.data) {
        is MeshCorePacket -> message.path.size.takeIf { it > 0 }?.let { plural(it, "hop", "hops") }
        is MeshtasticPacket -> (message.hopStart - message.hopLimit).takeIf { it > 0 }?.let { plural(it, "hop", "hops") }
        is MeshComFrame -> message.hops.takeIf { it > 0 }?.let { plural(it, "hop", "hops") }
        else -> null
    },
    frame.phy.snrDb?.let { "%.1f dB".format(Locale.US, it) },
)

/** The opened card's radio line: "SF8 · BW 62.5 kHz · CR 4/8 · SNR −13.9 dB · +211 Hz · preamble 5". */
private fun loraRadioLine(frame: LoraFrame): Array<String?> {
    val phy = frame.phy
    return arrayOf(
        "SF${phy.spreadingFactor}",
        "BW ${formatFrequency(phy.bandwidthHz.toLong())}",
        "CR 4/${phy.codingRate + 4}",
        phy.snrDb?.let { "SNR %.1f dB".format(Locale.US, it) },
        phy.frequencyErrorHz?.let { "%+.0f Hz".format(Locale.US, it) },
        phy.preambleSymbols?.let { "preamble $it" },
        "sync word 0x%02X".format(Locale.US, phy.syncWord).takeIf { phy.syncWord != 0x12 && phy.syncWord != 0x34 && phy.syncWord != 0x2B },
        "IQ inverted".takeIf { phy.iqInverted },
        "implicit header".takeIf { phy.implicitHeader },
        if (phy.hasCrc) "CRC error".takeIf { !phy.crcValid } else "no CRC" + (phy.reliability?.let { ", reliability %.3f".format(Locale.US, it) } ?: ""),
        frame.frequencyHz.takeIf { it != 0L }?.let(::formatFrequency),
    )
}

/** The fields of a frame, per protocol - everything the payload does not say, the header's sender,
 *  channel and path included: opened, the full date and time can take their room. */
private fun loraFields(frame: LoraFrame): List<Field> = fields {
    when (val message = frame.data) {
        is RawLoraPayload -> {}
        is MeshCorePacket -> {
            when (val info = message.info) {
                is MeshCoreGroupText -> {
                    add("From", info.senderName)
                    add("Channel", "${info.channelName} (decrypted with the channel key)")
                    add("Sent", "${formatDateTime(info.timestamp * 1000)} (sender clock)")
                }
                is MeshCoreAdvert -> {
                    add("Name", info.name)
                    add("Node", meshCoreNodeTypeLabel(info.nodeType))
                    info.latitude?.takeIf { info.hasLocation }?.let { lat -> info.longitude?.let { lon -> add("Position", formatPosition(lat, lon)) } }
                    add("Advert time", "${formatDateTime(info.timestamp * 1000)} (sender clock)")
                    add("Public key", info.publicKey.hex(), mono = true)
                    add("Signature", if (info.signatureValid) "valid" else "invalid")
                }
                is MeshCoreGroupData -> add("Channel", info.channelName)
                is MeshCoreEncryptedEnvelope -> {
                    add("To / from", listOfNotNull(info.destHash?.hex(), info.srcHash?.hex()).joinToString(" / ").ifEmpty { null }, mono = true)
                    add("Channel hash", info.channelHash?.hex(), mono = true)
                }
                is MeshCoreAnonRequest -> {
                    add("To", info.destHash.hex(), mono = true)
                    add("Sender key", info.senderPublicKey.hex(), mono = true)
                }
                is MeshCoreTrace -> add("Traced path", info.pathHashes.mapIndexed { i, hash -> hash.hex() + (info.snrs.getOrNull(i)?.let { " (%.1f dB)".format(Locale.US, it) } ?: "") }.joinToString(" → "))
                is MeshCoreDiscoverRequest -> info.since?.takeIf { it != 0L }?.let { add("Since", formatDateTime(it * 1000)) }
                is MeshCoreDiscoverResponse -> add("Public key", info.publicKey.hex(), mono = true)
                is MeshCoreAck, is MeshCoreOpaquePayload -> {}
            }
            add("Route", meshCoreRouteName(message.routeType) + if (message.path.isEmpty()) "" else
                " · ${plural(message.path.size, "hop", "hops")}: " + message.path.joinToString(" → ") { it.hex() })
            message.transportCodes?.let { add("Transport codes", it.hex(), mono = true) }
        }
        is MeshtasticPacket -> {
            add("From", meshtasticNodeLabel(message.sender))
            add("To", meshtasticNodeLabel(message.dest))
            // Older firmware sends no hop start (0): the hops taken are unknown then.
            add("Hops", "${message.hopStart - message.hopLimit} of ${message.hopStart}".takeIf { message.hopStart > 0 })
            add("Last relay", "!…%02x".format(Locale.US, message.relayNode).takeIf { message.relayNode != 0 })
            add("Key", message.decryptedWith)
            when (val info = message.info) {
                is MeshtasticTextMessage -> {}
                is MeshtasticPosition -> {
                    info.latitude?.let { lat -> info.longitude?.let { lon -> add("Position", formatPosition(lat, lon)) } }
                    add("Altitude", info.altitudeM?.let { "$it m" })
                    add("Speed", info.groundSpeed?.let { "$it m/s" })
                    add("Satellites", info.satsInView?.toString())
                }
                is MeshtasticNodeInfo -> {
                    add("Long name", info.longName)
                    add("Short name", info.shortName)
                    add("Hardware", info.hwModel?.let(::meshtasticHwModelLabel))
                    add("Role", info.role?.let(::meshtasticRoleLabel))
                    add("Licensed", info.isLicensed?.let { if (it) "yes" else "no" })
                }
                is MeshtasticTelemetry -> meshtasticTelemetryReadings(info).forEach { (label, value) -> add(label, value) }
                is MeshtasticNeighborInfo -> info.neighbors.forEach { n -> add(meshtasticNodeLabel(n.nodeId), n.snr?.let { "%.1f dB".format(Locale.US, it) } ?: "–") }
                is MeshtasticWaypoint -> {
                    info.latitude?.let { lat -> info.longitude?.let { lon -> add("Position", formatPosition(lat, lon)) } }
                    add("Expires", info.expireSec?.let { formatDateTime(it * 1000) })
                }
                is MeshtasticTraceroute -> add("Route", info.route.route.joinToString(" → ") { meshtasticNodeLabel(it) }.ifEmpty { "direct" })
                is MeshtasticRouting, is MeshtasticOpaquePayload, is MeshtasticEncrypted -> {}
            }
            add("Preset", meshtasticModemPresetGuess(frame.phy.spreadingFactor, frame.phy.bandwidthHz, frame.phy.codingRate)?.let { "$it (from the modulation)" })
        }
        is LoRaWanFrame -> {
            add("Message type", lorawanMTypeLabel(message.mtype))
            message.devAddr?.let { addr -> add("Network", lorawanNetIdLookup(addr.toLong(16))?.let { "${it.operator} (NetID ${it.netIdHex})" }) }
            message.devEui?.let { add("DevEUI", it + (lorawanEuiVendor(it)?.let { v -> " ($v)" } ?: "")) }
            message.joinEui?.let { add("JoinEUI", it + (lorawanEuiVendor(it)?.let { v -> " ($v)" } ?: "")) }
            add("DevNonce", message.devNonce?.toString())
            add("Flags", listOfNotNull("ADR".takeIf { message.adr == true }, "ACK".takeIf { message.ack == true },
                (if (message.mtype.isDownlink) "FPending" else "Class B").takeIf { message.fPending == true }).joinToString(", ").ifEmpty { null })
            message.fOpts.forEach { cmd -> add(lorawanMacCommandLabel(cmd.cid, message.mtype.isDownlink), cmd.payload.takeIf { !it.isEmpty() }?.hex() ?: "–") }
            add("Data rate", eu868DataRateLabel(frame.phy.spreadingFactor, frame.phy.bandwidthHz))
        }
        is LoraAprsPacket -> {
            fields.addAll(aprsReportFields(message.info))
            add("To", message.toCall, identity = true)
            add("Path", message.path.joinToString(", "))
        }
        is MeshComFrame -> fields.addAll(meshComFields(message))
        is MeshComAck -> fields.addAll(meshComAckFields(message))
    }
}

/**
 * One LoRa frame. Header: who sent it (with the node's icon or APRS symbol), the channel or kind
 * small, a warning word only when something is wrong, the protocol, the time. Payload: the message.
 * Opened: the protocol's fields, the radio line, the bytes.
 */
@Composable
fun LoraFrameCard(
    frame: LoraFrame,
    expanded: Boolean,
    onExpand: () -> Unit,
    actions: CardActions,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = CardListPadding,
) {
    val message = frame.data
    val position = message.positionOrNull()
    // The tracked object this frame folds into is its map marker; a frame without one has none.
    val objectKey = frame.objectKey.orEmpty()
    val markerId = loraKindOf(message)?.takeIf { objectKey.isNotEmpty() }?.let { markerId(it, objectKey) }
    val text = loraPayloadText(frame)
    val specific = buildList {
        if (position != null && markerId != null) {
            add(PacketMenuAction(label = "Map", icon = Icons.Default.Map, prominent = true) { actions.onTrackOnMap(markerId, position.first, position.second) })
        }
        (message as? LoraAprsPacket)?.fromCall?.let { call ->
            add(PacketMenuAction(label = "aprs.fi", prominent = true) { actions.onOpenUrl(aprsFiUrl(call)) })
            add(PacketMenuAction(label = "QRZ.com") { actions.onOpenUrl(qrzUrl(baseCallsign(call))) })
        }
        (message as? MeshComFrame)?.origin?.let { call ->
            add(PacketMenuAction(label = "QRZ.com") { actions.onOpenUrl(qrzUrl(baseCallsign(call))) })
        }
        loraIdentifierToCopy(message)?.let { (label, value) -> add(copyTextAction(label, value)) }
    }
    val encrypted = (message as? MeshCorePacket)?.info.let { it is MeshCoreEncryptedEnvelope || it is MeshCoreAnonRequest } ||
        (message as? MeshtasticPacket)?.info is MeshtasticEncrypted
    PacketCard(
        expanded = expanded,
        onExpand = onExpand,
        timestampMs = frame.timestampMs,
        modifier = modifier,
        outerPadding = outerPadding,
        identity = {
            IdentityText(loraFrameIdentity(message), leading = loraLeadingIcon(message))
        },
        subtitle = loraFrameKind(message),
        warning = loraWarning(frame.phy, message)?.let { { WarningText(it) } },
        // Which protocol the frame is - the Raw list mixes them all.
        trailing = { TypeTag(loraProtocolName(message)) },
        payload = { open -> PayloadText(text, open, color = if (encrypted) MaterialTheme.colorScheme.outline else androidx.compose.ui.graphics.Color.Unspecified, identities = loraCallsigns(message)) },
        expandedContent = {
            Fields(loraFields(frame))
            RadioLine(*loraRadioLine(frame))
            (message as? LoRaWanFrame)?.takeIf { it.devEui == null && !it.encryptedTail.isEmpty() }?.let { lw ->
                RawSection("Encrypted payload · ${plural(lw.encryptedTail.size, "byte", "bytes")}") { lw.encryptedTail.toByteArray().toHexDump(8, 4) }
            }
            RawBytes(frame.raw)
        },
        actions = packetActions(frame, actions, copyText = text.takeIf { isTextMessage(message) }, specific = specific),
    )
}

/** Whether the frame's payload is a message someone wrote (worth "Copy text"). */
private fun isTextMessage(message: LoraPayloadMessage): Boolean =
    (message as? MeshCorePacket)?.info is MeshCoreGroupText || (message as? MeshtasticPacket)?.info is MeshtasticTextMessage ||
        (message as? LoraAprsPacket)?.info is com.mantz_it.rfanalyzer.digiext.aprs.AprsTextMessage ||
        (message as? MeshComFrame)?.let(::isMeshComChat) == true

/** The callsigns a LoRa-APRS or MeshCom frame carries - where its text names one, it is drawn in the
 *  identity colour. The other mesh protocols name nodes, not callsigns. */
internal fun loraCallsigns(message: LoraPayloadMessage): List<String> = when (message) {
    is LoraAprsPacket -> listOfNotNull(message.fromCall, message.toCall) + message.path + aprsReportCallsigns(message.info)
    is MeshComFrame -> meshComCallsigns(message)
    else -> emptyList()
}

/** The leading icon of a frame's identity: an APRS symbol, a MeshCore node or message icon. */
private fun loraLeadingIcon(message: LoraPayloadMessage): (@Composable () -> Unit)? = when (message) {
    is LoraAprsPacket -> message.info.symbolOrNull()?.let { (table, sym) -> { AprsSymbolIcon(table, sym, size = 16.dp) } }
    is MeshCorePacket -> { { Icon(meshCoreIcon(message.info), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp)) } }
    is MeshComFrame -> when (val info = message.info) {
        is MeshComPosition -> { { AprsSymbolIcon(info.symbolTable, info.symbolCode, size = 16.dp) } }
        is MeshComHey, is MeshComNeighbourReport -> { { Icon(Icons.Default.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp)) } }
        is MeshComText -> { { Icon(Icons.Default.ChatBubble, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp)) } }
    }
    else -> null
}

/** The sender's identifier to copy, where the protocol has one. */
private fun loraIdentifierToCopy(message: LoraPayloadMessage): Pair<String, String>? = when (message) {
    is LoraAprsPacket -> "Copy callsign" to message.fromCall
    is MeshtasticPacket -> "Copy node ID" to meshtasticNodeLabel(message.sender)
    is LoRaWanFrame -> message.devAddr?.let { "Copy DevAddr" to it } ?: message.devEui?.let { "Copy DevEUI" to it }
    is MeshCorePacket -> when (val info = message.info) {
        is MeshCoreAdvert -> "Copy public key" to info.publicKey.hex()
        is MeshCoreAnonRequest -> "Copy sender key" to info.senderPublicKey.hex()
        is MeshCoreDiscoverResponse -> "Copy public key" to info.publicKey.hex()
        else -> null
    }
    is MeshComFrame -> "Copy callsign" to message.origin
    is MeshComAck, is RawLoraPayload -> null
}

/**
 * The LoRa ticker, above every section: the latest frame of any protocol - who, the channel and
 * signal small, the message below.
 */
@Composable
internal fun LoraTicker(feed: LiveFeed<LoraFrame>, context: OverviewContext, lines: Int) {
    val frame = rememberLatestLive(feed.latest, context.live?.session?.startMs)
    val expansion = context.tickerExpansion
    LiveTicker(label = context.tickerLabel(frame?.let { "SF${it.phy.spreadingFactor}" }), flashKey = frame?.timestampMs, expansion = expansion) {
        if (frame == null) {
            TickerWaiting("Listening on ${formatFrequency(context.live?.frequencyHz ?: 0)}…", if (expansion.open) expansion.openLines else lines)
        } else if (expansion.open) {
            val recent by feed.recent.collectAsState()
            TickerLog(
                recent.map { f ->
                    TickerLogLine(
                        identity = loraFrameIdentity(f.data),
                        text = metaLine(loraProtocolName(f.data), loraPayloadText(f)),
                        timestampMs = f.timestampMs,
                        identities = loraCallsigns(f.data),
                        icon = loraLeadingIcon(f.data),
                    )
                },
                expansion.openLines,
                identityWidth = 112.dp,
            )
        } else {
            TickerLines(
                lines = lines,
                identity = loraFrameIdentity(frame.data),
                icon = loraLeadingIcon(frame.data),
                meta = metaLine(loraProtocolName(frame.data), loraFrameKind(frame.data), loraMeta(frame)),
                timestampMs = frame.timestampMs,
                payload = loraPayloadText(frame),
                identities = loraCallsigns(frame.data),
            )
        }
    }
}

internal fun loraProtocolName(message: LoraPayloadMessage): String = when (message) {
    is MeshCorePacket -> "MeshCore"
    is MeshtasticPacket -> "Meshtastic"
    is LoRaWanFrame -> "LoRaWAN"
    is LoraAprsPacket -> "LoRa-APRS"
    is MeshComFrame, is MeshComAck -> "MeshCom"
    is RawLoraPayload -> "LoRa"
}

/** One MeshCore node's actions - see [trackedObjectActions]; shared by the row and the Map popup. */
@Composable
internal fun meshCoreNodeActions(
    node: TrackedObject<MeshCoreNode>,
    onShareJson: (json: String, filenamePrefix: String) -> Unit,
    onTrackOnMap: () -> Unit,
    onOpenUrl: (String) -> Unit,
    shareMessages: ((filenamePrefix: String) -> Unit)? = null,
    withMap: Boolean = true,
): List<PacketMenuAction> = trackedObjectActions(
    node, MeshCoreNodeFamily, onShareJson, onTrackOnMap, onOpenUrl, shareMessages, withMap = withMap,
    specific = listOfNotNull(node.state.publicKeyHex?.let { copyTextAction("Copy public key", it) }),
)

@Composable
internal fun meshtasticNodeActions(
    node: TrackedObject<MeshtasticNode>,
    onShareJson: (json: String, filenamePrefix: String) -> Unit,
    onTrackOnMap: () -> Unit,
    onOpenUrl: (String) -> Unit,
    shareMessages: ((filenamePrefix: String) -> Unit)? = null,
    withMap: Boolean = true,
): List<PacketMenuAction> = trackedObjectActions(
    node, MeshtasticNodeFamily, onShareJson, onTrackOnMap, onOpenUrl, shareMessages, withMap = withMap,
    specific = listOf(copyTextAction("Copy node ID", node.key)),
)

/** What a MeshCore node's row says: its last message or what kind of node it advertised. */
internal fun meshCoreRowText(node: MeshCoreNode): String? = when (node.lastPayloadType) {
    MeshCorePayloadType.GRP_TXT -> node.lastText?.let { "${node.channelName}: ${oneLine(it)}" }
    MeshCorePayloadType.ADVERT -> meshCoreNodeKind(node) + " advert"
    MeshCorePayloadType.CONTROL -> "Discovery response" // the only CONTROL payload that names its node
    else -> node.lastPayloadType?.let(::meshCorePayloadName)
}

internal fun meshCoreNodeKind(node: MeshCoreNode): String = when {
    node.isRoomServer -> "Room server"
    node.isRepeater -> "Repeater"
    node.isSensor -> "Sensor"
    node.isChatNode -> "Chat node"
    node.publicKeyHex == null -> "Group sender"
    else -> "Node"
}

/** What a MeshCore node tells beyond its row. */
internal fun meshCoreNodeFacts(node: TrackedObject<MeshCoreNode>): List<Field> = fields {
    add("Kind", meshCoreNodeKind(node.state) + if (node.state.publicKeyHex == null) " (known by its name only)" else "")
    node.lat?.let { lat -> node.lon?.let { lon -> add("Position", formatPosition(lat, lon)) } }
    add("Channel", node.state.channelName)
    add("Signal", node.state.snrDb?.let { "%.1f dB".format(Locale.US, it) })
    add("Modulation", node.state.modulation?.label)
    add("Public key", node.state.publicKeyHex, mono = true)
}

/** What a Meshtastic node's row says. */
internal fun meshtasticRowText(node: MeshtasticNode): String? = when {
    node.lastEncrypted -> "Encrypted (no key for this channel)"
    node.lastText != null && node.lastPortnum == 1 -> oneLine(node.lastText)
    node.lastText != null && node.lastPortnum != null -> "${meshtasticPortnumLabel(node.lastPortnum)}: ${oneLine(node.lastText)}"
    node.lastPortnum == 3 -> node.lat?.let { lat -> node.lon?.let { lon -> "Position ${formatLatLon(lat, lon)}" } } ?: "Position"
    // The name is the row's identity already.
    node.lastPortnum == 4 -> metaLine("Node info", node.hwModel?.let(::meshtasticHwModelLabel))
    node.lastPortnum == 67 -> metaLine("Telemetry", node.deviceMetrics?.batteryLevel?.let { "battery $it %" })
    else -> node.lastPortnum?.let(::meshtasticPortnumLabel)
}

internal fun meshtasticNodeFacts(node: TrackedObject<MeshtasticNode>): List<Field> = fields {
    val s = node.state
    // Only where the row shows the long name: else the number is the row's identity already.
    add("Node", node.key.takeIf { it != node.displayName })
    add("Short name", s.shortName)
    add("Hardware", s.hwModel?.let(::meshtasticHwModelLabel))
    add("Role", s.role?.let(::meshtasticRoleLabel))
    node.lat?.let { lat -> node.lon?.let { lon -> add("Position", formatPosition(lat, lon)) } }
    s.deviceMetrics?.let { dm ->
        add("Battery", metaLine(dm.batteryLevel?.let { "$it %" }, dm.voltage?.let { "%.2f V".format(Locale.US, it) }).ifEmpty { null })
        add("Uptime", dm.uptimeSeconds?.let(::formatMeshtasticUptime))
    }
    s.environmentMetrics?.let { em ->
        add("Environment", metaLine(em.temperature?.let { "%.1f °C".format(Locale.US, it) }, em.relativeHumidity?.let { "%.0f %%".format(Locale.US, it) }, em.barometricPressure?.let { "%.0f hPa".format(Locale.US, it) }).ifEmpty { null })
    }
    add("Neighbours", s.neighbors.takeIf { it.isNotEmpty() }?.joinToString(", ") { n -> meshtasticNodeLabel(n.nodeId) + (n.snr?.let { " %.1f dB".format(Locale.US, it) } ?: "") })
    add("Signal", s.snrDb?.let { "%.1f dB".format(Locale.US, it) })
    add("Licensed", s.isLicensed?.let { if (it) "yes" else "no" })
}

/** One mesh node in the Map subtab's popup. */
@Composable
internal fun MeshNodePopup(
    name: String,
    lastSeenMs: Long,
    kind: String?,
    text: String?,
    facts: List<Field>,
    actions: List<PacketMenuAction>,
    onClose: () -> Unit,
    recent: List<RecentPacket>,
) {
    PacketCard(
        expanded = true,
        onExpand = onClose,
        timestampMs = lastSeenMs,
        identity = { IdentityText(name) },
        subtitle = kind,
        payload = text?.let { { open -> PayloadText(it, open) } },
        expandedContent = {
            Column {
                Fields(facts)
                RecentPacketsList(recent)
            }
        },
        actions = actions,
        outerPadding = PaddingValues(0.dp),
        shortTime = true,
    )
}

/** The icon key of a mesh node's map marker. */
internal const val MESH_NODE_ICON = "mesh_node"

/** MeshCore nodes that advertise a position, on the Map subtab. */
internal fun meshCoreMapLayer(recent: (objectKey: String) -> Flow<List<RecentPacket>>) = MapLayer(
    family = MeshCoreNodeFamily,
    recent = recent,
    marker = { node, lat, lon, _ ->
        MapMarker(id = node.markerId, lat = lat, lon = lon, title = node.displayName, description = meshCoreRowText(node.state).orEmpty(), iconKey = MESH_NODE_ICON)
    },
    popup = { node, recent, map ->
        MeshNodePopup(
            name = node.displayName, lastSeenMs = node.lastSeenMs, kind = "MeshCore · ${meshCoreNodeKind(node.state)}", text = meshCoreRowText(node.state),
            facts = meshCoreNodeFacts(node).filter { it.label != "Kind" },
            actions = meshCoreNodeActions(node, map.onShareJson, map.onTrack, map.onOpenUrl, withMap = false),
            onClose = map.onClose, recent = recent,
        )
    },
)

/** Meshtastic nodes that report a position, on the Map subtab. */
internal fun meshtasticMapLayer(recent: (objectKey: String) -> Flow<List<RecentPacket>>) = MapLayer(
    family = MeshtasticNodeFamily,
    recent = recent,
    marker = { node, lat, lon, _ ->
        MapMarker(id = node.markerId, lat = lat, lon = lon, title = node.displayName, description = meshtasticRowText(node.state).orEmpty(), iconKey = MESH_NODE_ICON)
    },
    popup = { node, recent, map ->
        MeshNodePopup(
            name = node.displayName, lastSeenMs = node.lastSeenMs, kind = "Meshtastic", text = meshtasticRowText(node.state),
            facts = meshtasticNodeFacts(node),
            actions = meshtasticNodeActions(node, map.onShareJson, map.onTrack, map.onOpenUrl, withMap = false),
            onClose = map.onClose, recent = recent,
        )
    },
)

/** One LoRa object's frames, newest first, each opening into its card - shared by the protocol
 *  sections. */
@Composable
internal fun LoraFrameList(objectKey: String, framesFor: ObjectPackets<LoraFrame>, full: Boolean, onMore: (() -> Unit)?, actions: CardActions) {
    val frames = com.mantz_it.rfanalyzer.digiext.ui.common.rememberMessages(objectKey, framesFor, full)
    com.mantz_it.rfanalyzer.digiext.ui.common.MessageList(
        items = frames.items,
        total = frames.total,
        onLoadMore = frames.loadMore,
        key = { it.rowId },
        time = { it.timestampMs },
        text = ::loraFrameSummary,
        maxRows = if (full) Int.MAX_VALUE else 5,
        emptyText = "No stored frames.",
        onMore = onMore,
        card = { frame, collapse ->
            LoraFrameCard(frame = frame, expanded = true, onExpand = collapse, actions = actions, outerPadding = PaddingValues(top = 2.dp, bottom = 6.dp))
        },
    )
}
