package com.mantz_it.rfanalyzer.digiext.lora.meshcom

import androidx.compose.runtime.Composable
import com.mantz_it.rfanalyzer.digiext.ax25.baseCallsign
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.db.isPlausiblePosition
import com.mantz_it.rfanalyzer.digiext.db.markerId
import com.mantz_it.rfanalyzer.digiext.lora.MESHCOM_FLAG_MESH
import com.mantz_it.rfanalyzer.digiext.lora.MESHCOM_FLAG_SERVER
import com.mantz_it.rfanalyzer.digiext.lora.MESHCOM_FLAG_TRACK
import com.mantz_it.rfanalyzer.digiext.lora.MESH_NODE_ICON
import com.mantz_it.rfanalyzer.digiext.lora.MeshComAck
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrame
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrameType
import com.mantz_it.rfanalyzer.digiext.lora.MeshComHey
import com.mantz_it.rfanalyzer.digiext.lora.MeshComNeighbourReport
import com.mantz_it.rfanalyzer.digiext.lora.MeshComPosition
import com.mantz_it.rfanalyzer.digiext.lora.MeshComText
import com.mantz_it.rfanalyzer.digiext.lora.MeshComTextKind
import com.mantz_it.rfanalyzer.digiext.lora.MeshNodePopup
import com.mantz_it.rfanalyzer.digiext.ui.MapLayer
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.FieldsBuilder
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.RecentPacket
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.formatLatLon
import com.mantz_it.rfanalyzer.digiext.ui.common.formatPosition
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.common.oneLine
import com.mantz_it.rfanalyzer.digiext.ui.common.plural
import com.mantz_it.rfanalyzer.digiext.ui.common.qrzUrl
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
import com.mantz_it.rfanalyzer.digiext.ui.trackedObjectActions
import com.mantz_it.rfanalyzer.ui.composable.AprsSymbolIcons
import com.mantz_it.rfanalyzer.ui.composable.MapMarker
import kotlinx.coroutines.flow.Flow
import java.util.Locale
import kotlin.math.roundToInt

/**
 * <h1>RF Analyzer - MeshCom Card</h1>
 *
 * Module:      MeshComCard.kt
 * Description: How MeshCom frames and stations read: the frame's texts and fields (LoraCard
 *              dispatches to them), a station's row, facts and actions, and the Map layer.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** "group 262", "to DK5EN-90", null for everyone. */
private fun meshComRecipient(destination: String): String? = when {
    destination == "*" -> null
    destination.all { it.isDigit() } -> "group $destination"
    else -> "to $destination"
}

/** A position's place and the short facts after it: "49.2667, 8.6028 · Battery Powered · 85 % · 154 m". */
private fun positionText(p: MeshComPosition): String = metaLine(
    formatLatLon(p.latitude, p.longitude).takeIf { isPlausiblePosition(p.latitude, p.longitude) } ?: "no position",
    p.comment,
    p.batteryPercent?.let { "$it %" },
    p.altitudeFt?.let { "${(it * 0.3048).roundToInt()} m" },
    weatherText(p),
)

private fun weatherText(p: MeshComPosition): String? = metaLine(
    p.temperatureC?.let { "%.1f °C".format(Locale.US, it) },
    p.humidityPercent?.let { "%.0f %%".format(Locale.US, it) },
    (p.qnhHpa ?: p.pressureHpa)?.let { "%.0f hPa".format(Locale.US, it) },
).ifEmpty { null }

/** The small text after the frame's sender: who it is for, which relay it came from. */
internal fun meshComFrameKind(frame: MeshComFrame): String? = metaLine(
    when (frame.frameType) {
        MeshComFrameType.TEXT -> meshComRecipient(frame.destination)
        MeshComFrameType.POSITION -> null
        MeshComFrameType.HEY -> when (frame.destination) {
            "HG" -> "HEY from a gateway"
            "HN" -> "Neighbours"
            else -> "HEY"
        }
    },
    frame.lastTransmitter.takeIf { frame.hops > 0 }?.let { "via $it" },
).ifEmpty { null }

/** What a frame says, in words. */
internal fun meshComText(frame: MeshComFrame): String = when (val info = frame.info) {
    is MeshComText -> when (info.kind) {
        MeshComTextKind.CHAT -> info.text
        MeshComTextKind.ACK -> "Acknowledges message ${info.ackNumber ?: "?"}"
        MeshComTextKind.TIME -> "Server time ${info.text}"
        MeshComTextKind.PING -> "Ping"
        MeshComTextKind.PONG -> "Pong"
        MeshComTextKind.TELEMETRY -> "Telemetry: ${info.text}"
        MeshComTextKind.COMMAND -> info.text
    }
    is MeshComPosition -> positionText(info)
    is MeshComHey -> metaLine(
        info.neighbours?.let { "Hears ${plural(it, "station", "stations")}" } ?: "HEY",
        info.reports.takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "relays heard it at ") { "${it.rssiDbm} dBm / ${it.snrDb} dB" },
    )
    is MeshComNeighbourReport -> info.neighbours.takeIf { it.isNotEmpty() }
        ?.joinToString(", ", prefix = "Hears ", postfix = if (info.more) ", …" else "") { n -> n.callsign + (n.snrDb?.let { " (%.1f dB)".format(Locale.US, it) } ?: "") }
        ?: info.heard?.let { "Hears ${plural(it, "station", "stations")}" } ?: "Neighbour report"
}

internal fun meshComAckText(ack: MeshComAck): String = "A gateway acknowledges message %08X".format(Locale.US, ack.ackedMsgId)

/** Whether the frame is a text someone wrote. */
internal fun isMeshComChat(frame: MeshComFrame): Boolean = (frame.info as? MeshComText)?.kind == MeshComTextKind.CHAT

/** The callsigns a frame names: its path, the recipient, the neighbours it reports. */
internal fun meshComCallsigns(frame: MeshComFrame): List<String> =
    frame.sourcePath + frame.destPath.filter { it != "*" && it.any(Char::isLetter) } +
        ((frame.info as? MeshComNeighbourReport)?.neighbours?.map { it.callsign }.orEmpty())

/** The position of a frame, if it reports one. */
internal fun meshComPositionOf(frame: MeshComFrame): Pair<Double, Double>? =
    (frame.info as? MeshComPosition)?.takeIf { isPlausiblePosition(it.latitude, it.longitude) }?.let { it.latitude to it.longitude }

private fun FieldsBuilder.positionFields(p: MeshComPosition) {
    if (isPlausiblePosition(p.latitude, p.longitude)) add("Position", formatPosition(p.latitude, p.longitude))
    add("Name", p.name)
    add("Comment", p.comment)
    add("Battery", p.batteryPercent?.let { "$it %" })
    add("Altitude", p.altitudeFt?.let { "${(it * 0.3048).roundToInt()} m ($it ft)" })
    add("Temperature", metaLine(p.temperatureC?.let { "%.1f °C".format(Locale.US, it) }, p.temperature2C?.let { "%.1f °C (second sensor)".format(Locale.US, it) }).ifEmpty { null })
    add("Humidity", p.humidityPercent?.let { "%.1f %%".format(Locale.US, it) })
    add("Pressure", metaLine(p.pressureHpa?.let { "%.1f hPa".format(Locale.US, it) }, p.qnhHpa?.let { "QNH %.1f hPa".format(Locale.US, it) }).ifEmpty { null })
    add("Gas resistance", p.gasKohm?.let { "%.1f kΩ".format(Locale.US, it) })
    add("CO₂", p.co2Ppm?.let { "$it ppm" })
    add("Power", metaLine(p.voltageV?.let { "%.2f V".format(Locale.US, it) }, p.currentMa?.let { "%.1f mA".format(Locale.US, it) }).ifEmpty { null })
    add("Neighbours", p.neighbours?.toString())
    add("Groups", p.groups.takeIf { it.isNotEmpty() }?.joinToString(", "))
}

/** The fields of an opened frame: everything the text above doesn't say. */
internal fun meshComFields(frame: MeshComFrame): List<Field> = fields {
    add("From", frame.origin, identity = true)
    add("To", when (frame.destination) {
        "*" -> "everyone"
        "H", "HG" -> "everyone (HEY beacon)"
        "HN" -> "everyone (neighbour report)"
        else -> if (frame.destination.all { it.isDigit() }) "group ${frame.destination}" else frame.destination
    })
    add("Via (requested)", frame.destPath.dropLast(1).takeIf { it.isNotEmpty() }?.joinToString(", "))
    add("Path", frame.sourcePath.takeIf { it.size > 1 }?.joinToString(" → "))
    when (val info = frame.info) {
        is MeshComPosition -> positionFields(info)
        is MeshComHey -> info.reports.forEachIndexed { i, r ->
            add(frame.sourcePath.getOrElse(i + 1) { "Relay ${i + 1}" }, metaLine("heard the station before at ${r.rssiDbm} dBm, ${r.snrDb} dB", r.neighbours?.let { plural(it, "neighbour", "neighbours") }))
        }
        is MeshComNeighbourReport -> info.neighbours.forEach { n -> add(n.callsign, n.snrDb?.let { "%.1f dB".format(Locale.US, it) } ?: "–") }
        is MeshComText -> info.ackNumber?.takeIf { info.kind == MeshComTextKind.CHAT }?.let { add("Acknowledgement", "requested (number $it)") }
    }
    add("Message ID", "%08X (message %d of the node)".format(Locale.US, frame.msgId, frame.msgId and 0x3FF))
    add("Hops left", frame.hopsLeft.toString())
    add("Flags", listOfNotNull(
        "server".takeIf { frame.flags and MESHCOM_FLAG_SERVER != 0 },
        "track".takeIf { frame.flags and MESHCOM_FLAG_TRACK != 0 },
        "relaying".takeIf { frame.flags and MESHCOM_FLAG_MESH != 0 },
    ).joinToString(", ").ifEmpty { null })
    add("Hardware", meshComHardwareName(frame.hardware) ?: "board ${frame.hardware}")
    add("Firmware", meshComFirmware(frame))
    frame.lastHardware?.takeIf { frame.hops > 0 }?.let { add("Heard from", "${frame.lastTransmitter} (${meshComHardwareName(it) ?: "board $it"})") }
    add("Checksum", "wrong - the frame is corrupted".takeIf { !frame.checksumValid })
}

internal fun meshComAckFields(ack: MeshComAck): List<Field> = fields {
    add("Acknowledges", "%08X".format(Locale.US, ack.ackedMsgId))
    add("ACK ID", "%08X".format(Locale.US, ack.ackId))
    add("Hops left", ack.hopsLeft.toString())
}

/** What a station's row says: its last text, position or beacon. */
internal fun meshComRowText(node: MeshComNode): String? = when (node.lastType) {
    MeshComFrameType.TEXT -> node.lastText?.let { text ->
        when (node.lastTextKind) {
            MeshComTextKind.CHAT, null -> metaLine(meshComRecipient(node.lastTextTo ?: "*"), oneLine(text))
            MeshComTextKind.TIME -> "Server time beacon"
            MeshComTextKind.ACK -> "Acknowledgement"
            else -> oneLine(text)
        }
    }
    MeshComFrameType.POSITION -> node.position?.let(::positionText)
    MeshComFrameType.HEY -> metaLine("HEY", node.neighbours?.let { "hears ${plural(it, "station", "stations")}" })
    null -> node.relayed.takeIf { it > 0 }?.let { "Relayed ${plural(it, "frame", "frames")}" }
}

/** What a station tells beyond its row. */
internal fun meshComNodeFacts(node: TrackedObject<MeshComNode>): List<Field> = fields {
    val s = node.state
    s.position?.let { positionFields(it) }
    if (s.position?.neighbours == null) add("Neighbours", s.neighbours?.toString())
    add("Hears", s.neighbourList.takeIf { it.isNotEmpty() }?.joinToString(", ") { n -> n.callsign + (n.snrDb?.let { " %.1f dB".format(Locale.US, it) } ?: "") })
    add("Gateway", "yes (connected to the MeshCom server)".takeIf { s.isGateway })
    add("Hardware", s.hardware?.let { meshComHardwareName(it) ?: "board $it" })
    add("Firmware", s.firmware)
    add("Relayed", s.relayed.takeIf { it > 0 }?.let { plural(it, "frame", "frames") })
    add("Signal", s.snrDb?.let { "%.1f dB".format(Locale.US, it) })
    add("Modulation", s.modulation?.label)
}

@Composable
internal fun meshComNodeActions(
    node: TrackedObject<MeshComNode>,
    onShareJson: (json: String, filenamePrefix: String) -> Unit,
    onTrackOnMap: () -> Unit,
    onOpenUrl: (String) -> Unit,
    shareMessages: ((filenamePrefix: String) -> Unit)? = null,
    withMap: Boolean = true,
): List<PacketMenuAction> = trackedObjectActions(
    node, MeshComNodeFamily, onShareJson, onTrackOnMap, onOpenUrl, shareMessages, withMap = withMap,
    specific = listOfNotNull(
        PacketMenuAction(label = "QRZ.com", prominent = true) { onOpenUrl(qrzUrl(baseCallsign(node.displayName))) },
        copyTextAction("Copy callsign", node.displayName),
    ),
)

/** MeshCom stations that send their position, on the Map subtab - with their APRS symbol. */
internal fun meshComMapLayer(recent: (objectKey: String) -> Flow<List<RecentPacket>>) = MapLayer(
    family = MeshComNodeFamily,
    recent = recent,
    marker = { node, lat, lon, _ ->
        val p = node.state.position
        MapMarker(
            id = node.markerId, lat = lat, lon = lon, title = node.displayName, description = meshComRowText(node.state).orEmpty(),
            iconKey = p?.let { AprsSymbolIcons.iconKey(it.symbolTable, it.symbolCode) } ?: MESH_NODE_ICON,
            overlay = p?.let { AprsSymbolIcons.overlayChar(it.symbolTable)?.toString() },
        )
    },
    popup = { node, recent, map ->
        MeshNodePopup(
            name = node.displayName, lastSeenMs = node.lastSeenMs, kind = "MeshCom", text = meshComRowText(node.state),
            facts = meshComNodeFacts(node),
            actions = meshComNodeActions(node, map.onShareJson, map.onTrack, map.onOpenUrl, withMap = false),
            onClose = map.onClose, recent = recent,
        )
    },
)
