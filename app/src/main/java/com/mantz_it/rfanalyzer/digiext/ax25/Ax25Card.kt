package com.mantz_it.rfanalyzer.digiext.ax25

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLogLine
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLog
import com.mantz_it.rfanalyzer.digiext.LiveFeed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.markerId
import com.mantz_it.rfanalyzer.digiext.ui.common.RecentPacketsList
import com.mantz_it.rfanalyzer.digiext.ui.common.RecentPacket
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.aprs.AprsInfo
import com.mantz_it.rfanalyzer.digiext.aprs.AprsTextMessage
import com.mantz_it.rfanalyzer.digiext.aprs.MicEInfo
import com.mantz_it.rfanalyzer.digiext.aprs.ObjectReport
import com.mantz_it.rfanalyzer.digiext.aprs.PositionReport
import com.mantz_it.rfanalyzer.digiext.aprs.RawGpsOrUltimeterInfo
import com.mantz_it.rfanalyzer.digiext.aprs.StatusInfo
import com.mantz_it.rfanalyzer.digiext.aprs.TelemetryInfo
import com.mantz_it.rfanalyzer.digiext.aprs.ThirdPartyInfo
import com.mantz_it.rfanalyzer.digiext.aprs.UnknownAprsInfo
import com.mantz_it.rfanalyzer.digiext.aprs.decodePhg
import com.mantz_it.rfanalyzer.digiext.aprs.symbolOrNull
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.aprsFiUrl
import com.mantz_it.rfanalyzer.digiext.ui.common.qrzUrl
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
import com.mantz_it.rfanalyzer.ui.composable.AprsSymbolIcon
import com.mantz_it.rfanalyzer.ui.composable.AprsSymbolIcons
import com.mantz_it.rfanalyzer_ipc.Ax25Variant
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.Flow
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
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
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLines
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerWaiting
import com.mantz_it.rfanalyzer.digiext.ui.common.TypeTag
import com.mantz_it.rfanalyzer.digiext.ui.common.WarningText
import com.mantz_it.rfanalyzer.digiext.ui.common.feetToMeters
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.formatFrequency
import com.mantz_it.rfanalyzer.digiext.ui.common.formatKnots
import com.mantz_it.rfanalyzer.digiext.ui.common.formatLatLon
import com.mantz_it.rfanalyzer.digiext.ui.common.formatPosition
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.common.oneLine
import com.mantz_it.rfanalyzer.digiext.ui.common.packetActions
import com.mantz_it.rfanalyzer.digiext.ui.common.plural
import com.mantz_it.rfanalyzer.digiext.ui.common.rememberLatestLive
import com.mantz_it.rfanalyzer.digiext.ui.trackedObjectActions
import com.mantz_it.rfanalyzer.ui.composable.MapMarker
import java.util.Locale

/**
 * <h1>RF Analyzer - AX.25 Card</h1>
 *
 * Module:      Ax25Card.kt
 * Description: How AX.25 frames and APRS stations read: the frame card, the texts rows and lists
 *              show, the station's facts and actions, the ticker and the Map layer. APRS reports
 *              are put into words ("34.5073, −117.1297 · 352° 49 kn · 1218 m") - shared with
 *              LoRa-APRS.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The position an info payload carries - a relayed packet's is the originator's, not the frame's. */
internal fun AprsInfo.positionOrNull(): Pair<Double, Double>? {
    val (lat, lon) = when (this) {
        is PositionReport -> lat to lon
        is ObjectReport -> lat to lon
        is MicEInfo -> lat to lon
        is RawGpsOrUltimeterInfo -> latitude to longitude
        else -> return null
    }
    // 0,0 is what a tracker without a fix sends, not a place.
    return if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) lat to lon else null
}

fun ax25VariantBaudHz(variant: Ax25Variant): Int = when (variant) {
    Ax25Variant.HF_300 -> 300
    Ax25Variant.VHF_1200 -> 1200
    Ax25Variant.G3RUH_9600 -> 9600
}

/** "WIDE1-1*, WIDE2-1" - the digipeater path, a star on those that repeated it. */
internal fun ax25Path(message: Ax25Message): String? =
    message.digipeaters.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.toString() + if (it.hasBeenRepeated) "*" else "" }

/** "via WIDE1-1*, WIDE2-1". */
fun ax25PathText(message: Ax25Message): String? = ax25Path(message)?.let { "via $it" }

/** The callsign without its SSID, for lookups that know only the person (QRZ.com). */
internal fun baseCallsign(callsign: String): String = callsign.substringBefore('-')

/** What a non-APRS frame is, in one word, for its card: Mail, Bulletins, NET/ROM, Connect, Text. */
internal fun ax25FrameTag(message: Ax25Message): String? = when (val content = message.content) {
    is Ax25Content.MailBeacon -> "Mail"
    is Ax25Content.BulletinList -> "Bulletins"
    is Ax25Content.NetRomNodes -> "NET/ROM"
    is Ax25Content.Text -> if (message.frameType?.isInformation == true) "Connected" else "Text"
    null -> when (message.frameType?.name) {
        "SABM", "SABME" -> "Connect"
        "DISC" -> "Disconnect"
        "UA" -> "Acknowledge"
        "DM" -> "Refused"
        null -> null
        else -> message.frameType?.name
    }.takeIf { message.aprs == null }
}

/** One line for an APRS report in words - the text of a row, a ticker, a card and a list. */
fun aprsReportText(info: AprsInfo): String = when (info) {
    is PositionReport -> metaLine(cleanComment(info.comment), positionText(info.lat, info.lon), motionText(info.course?.toDouble(), info.speedKnots?.toDouble()), info.altitudeFeet?.let(::feetToMeters))
    is MicEInfo -> metaLine(cleanComment(info.comment) ?: info.messageType?.takeIf { it != "Off Duty" }, positionText(info.lat, info.lon), motionText(info.course?.toDouble(), info.speedKnots?.toDouble()), info.altitudeFeet?.let(::feetToMeters))
        .ifEmpty { "Mic-E without a position" }
    is AprsTextMessage -> "→ ${info.addressee.trim()}: ${info.text}"
    is TelemetryInfo -> "Telemetry #${info.sequence ?: "?"}: ${info.analog.joinToString(", ")}"
    is ObjectReport -> metaLine((if (info.killed) "Deleted object " else "Object ") + info.objectName.trim(), positionText(info.lat, info.lon), cleanComment(info.comment))
    is StatusInfo -> oneLine(info.text)
    is RawGpsOrUltimeterInfo -> metaLine("GPS", positionText(info.latitude, info.longitude), motionText(info.course, info.speedKnots))
    is ThirdPartyInfo -> (info.source?.let { "Relayed for $it: " } ?: "Relayed: ") + (info.innerInfo?.let(::aprsReportText) ?: oneLine(info.rawInner))
    is UnknownAprsInfo -> oneLine(info.raw)
}

private fun positionText(lat: Double?, lon: Double?): String? =
    if (lat != null && lon != null && !(lat == 0.0 && lon == 0.0)) formatLatLon(lat, lon) else null

/** "352° 49 kn" for a moving station; nothing for one standing still. */
private fun motionText(course: Double?, speedKnots: Double?): String? = when {
    speedKnots == null || speedKnots < 1 -> null
    course != null && course > 0 -> "${course.roundToInt()}° ${speedKnots.roundToInt()} kn"
    else -> "${speedKnots.roundToInt()} kn"
}

/** A comment without the data extensions some stations leave in it ("000/000", "PHG5360", "/A=…"). */
internal fun cleanComment(comment: String?): String? = comment
    ?.replace(COMMENT_EXTENSIONS, " ")
    ?.let(::oneLine)
    ?.trim('/', ' ')
    ?.takeIf { it.isNotBlank() }

private val COMMENT_EXTENSIONS = Regex("^\\d{3}/\\d{3}|PHG\\d{4}|/A=-?\\d{6}")

/** One line for a non-APRS information field. */
fun ax25ContentText(content: Ax25Content): String = when (content) {
    is Ax25Content.MailBeacon -> if (content.callsigns.isEmpty()) "No mail waiting" else "Mail for " + content.callsigns.joinToString(" ")
    is Ax25Content.BulletinList -> content.entries.joinToString(" | ") { "${it.from} → ${it.to}: ${it.title}" }
    is Ax25Content.NetRomNodes -> "Node ${content.alias} · ${plural(content.routes.size, "route", "routes")}"
    is Ax25Content.Text -> oneLine(content.text)
}

/** The callsigns an APRS report names in its text: a message's addressee, the station a frame was
 *  relayed for. */
internal fun aprsReportCallsigns(info: AprsInfo?): List<String> = when (info) {
    is AprsTextMessage -> listOf(info.addressee.trim())
    is ThirdPartyInfo -> listOfNotNull(info.source) + aprsReportCallsigns(info.innerInfo)
    else -> emptyList()
}

/** Every callsign a frame carries - where its text names one, it is drawn in the identity colour. */
internal fun ax25FrameCallsigns(frame: Ax25Frame): List<String> {
    val message = frame.data
    return message.addresses.map { it.toString() } + aprsReportCallsigns(message.aprs) + when (val content = message.content) {
        is Ax25Content.MailBeacon -> content.callsigns
        is Ax25Content.BulletinList -> content.entries.flatMap { listOf(it.from, it.to) }
        else -> emptyList()
    }
}

/** What a frame says, in one line: the APRS report, the packet content, or what the frame does in
 *  a connection ("Connect request to KB9KC-7"). */
fun ax25FrameText(frame: Ax25Frame): String {
    val message = frame.data
    message.aprs?.let { return aprsReportText(it) }
    message.content?.let { return ax25ContentText(it) }
    val type = message.frameType
    val to = message.destination?.toString()
    return when (type?.name) {
        "SABM", "SABME" -> "Connect request to $to"
        "DISC" -> "Disconnect from $to"
        "UA" -> "Acknowledged to $to"
        "DM" -> "Refused to $to"
        null -> "Unreadable frame"
        else -> type.meaning?.replaceFirstChar { it.uppercase() }?.let { "$it · $to" } ?: type.label
    }
}

/** The frame line of an opened card: "UI · PID F0 · 66 B". */
private fun frameLine(frame: Ax25Frame): String = metaLine(
    frame.data.frameType?.let { type -> type.label + when (type.command) { true -> " (command)"; false -> " (response)"; null -> "" } },
    frame.data.pid?.let { "PID %02X".format(Locale.US, it) },
    frame.raw?.let { "${it.size} B" },
    "${ax25VariantBaudHz(frame.phy.variant)} Bd",
    frame.frequencyHz.takeIf { it != 0L }?.let(::formatFrequency),
)

/** The fields of an APRS report, for an opened card. */
internal fun aprsReportFields(info: AprsInfo): List<Field> = fields {
    when (info) {
        is PositionReport -> {
            info.positionOrNull()?.let { (lat, lon) -> add("Position", formatPosition(lat, lon)) }
            info.course?.takeIf { it > 0 }?.let { add("Course", "$it°") }
            info.speedKnots?.let { add("Speed", formatKnots(it.toDouble())) }
            info.altitudeFeet?.let { add("Altitude", feetToMeters(it)) }
            add("Symbol", AprsSymbolIcons.label(info.symbolTable, info.symbol))
            info.phg?.let { add("Station", decodePhg(it) ?: it) }
            add("Messaging", if (info.dti == '=' || info.dti == '@') "Yes" else null)
            add("Sent", info.timestamp)
        }
        is MicEInfo -> {
            info.positionOrNull()?.let { (lat, lon) -> add("Position", formatPosition(lat, lon)) }
            // Mic-E has its own "unknown" (null): 0 is north.
            info.course?.let { add("Course", "$it°") }
            info.speedKnots?.let { add("Speed", formatKnots(it.toDouble())) }
            info.altitudeFeet?.let { add("Altitude", feetToMeters(it)) }
            add("Symbol", AprsSymbolIcons.label(info.symbolTable, info.symbol))
            add("Status", info.messageType)
            add("Radio", info.device)
        }
        is ObjectReport -> {
            add("Object", info.objectName.trim() + if (info.killed) " (deleted)" else "")
            info.positionOrNull()?.let { (lat, lon) -> add("Position", formatPosition(lat, lon)) }
            add("Symbol", AprsSymbolIcons.label(info.symbolTable, info.symbol))
            add("Sent", info.timestamp)
        }
        is AprsTextMessage -> {
            add("To", info.addressee.trim(), identity = true)
            add("Message ID", info.messageId)
        }
        is TelemetryInfo -> {
            add("Sequence", info.sequence?.toString())
            info.analog.forEachIndexed { i, v -> add("A${i + 1}", v) }
            add("Bits", info.bits)
        }
        is StatusInfo -> add("Sent", info.timestamp)
        is RawGpsOrUltimeterInfo -> {
            add("Sentence", info.type)
            add("Fix", info.valid?.let { if (it) "Valid" else "None" })
            info.positionOrNull()?.let { (lat, lon) -> add("Position", formatPosition(lat, lon)) }
            info.speedKnots?.let { add("Speed", formatKnots(it)) }
            add("Time (UTC)", info.timestamp)
        }
        is ThirdPartyInfo -> {
            add("Originator", info.source, identity = true)
            info.innerInfo?.let { inner -> fields.addAll(aprsReportFields(inner)) }
        }
        is UnknownAprsInfo -> {}
    }
}

/** The fields of a non-APRS frame. */
private fun ax25ContentFields(message: Ax25Message): List<Field> = fields {
    when (val content = message.content) {
        is Ax25Content.MailBeacon -> add("Mail for", content.callsigns.joinToString(" ").ifEmpty { "nobody" }, identity = content.callsigns.isNotEmpty())
        is Ax25Content.BulletinList -> content.entries.forEach { e ->
            add("#${e.number}", "${e.title} · ${fbbTypeName(e.type)} ${e.from} → ${e.to} · ${e.sizeBytes} B · ${fbbDate(e.date)}")
        }
        is Ax25Content.NetRomNodes -> content.routes.forEach { r -> add(r.alias.ifEmpty { r.callsign }, "${r.callsign} via ${r.neighbour} · quality ${r.quality}") }
        is Ax25Content.Text, null -> {}
    }
    message.frameType?.meaning?.let { add("Frame", it.replaceFirstChar { c -> c.uppercase() }) }
    add("To", message.destination?.toString(), identity = true)
}

private fun fbbTypeName(type: Char) = when (type) { 'B' -> "bulletin"; 'P' -> "personal"; 'T' -> "traffic"; else -> type.toString() }

/** YYMMDD as "25.10.2026". */
private fun fbbDate(d: String) = if (d.length == 6) "${d.substring(4, 6)}.${d.substring(2, 4)}.20${d.substring(0, 2)}" else d

/** The station a frame's "Map" action selects: an Object's own marker, not its reporter's. */
private fun Ax25Frame.mapMarkerId(): String? =
    objectKey?.takeIf { it.isNotEmpty() }?.let { markerId(ObjectKind.APRS_STATION, it) }

/**
 * One AX.25 frame. Header: the APRS symbol and the sender, the path small, the time. Payload: the
 * report in words. Opened: its fields, the frame line, the raw bytes.
 */
@Composable
fun Ax25FrameCard(
    frame: Ax25Frame,
    expanded: Boolean,
    onExpand: () -> Unit,
    actions: CardActions,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = CardListPadding,
) {
    val message = frame.data
    val source = message.source?.toString() ?: "Unknown"
    val aprs = message.aprs
    val symbol = aprs?.symbolOrNull()
    val position = aprs?.positionOrNull()
    val text = ax25FrameText(frame)
    val tag = ax25FrameTag(message)
    val specific = buildList {
        position?.let { (lat, lon) ->
            add(PacketMenuAction(label = "Map", icon = Icons.Default.Map, prominent = true) {
                actions.onTrackOnMap(frame.mapMarkerId() ?: markerId(ObjectKind.APRS_STATION, "STATION:$source"), lat, lon)
            })
        }
        if (aprs != null) add(PacketMenuAction(label = "aprs.fi", prominent = true) { actions.onOpenUrl(aprsFiUrl(source)) })
        add(PacketMenuAction(label = "QRZ.com/${baseCallsign(source)}") { actions.onOpenUrl(qrzUrl(baseCallsign(source))) })
        add(copyTextAction("Copy callsign", source))
    }
    PacketCard(
        expanded = expanded,
        onExpand = onExpand,
        timestampMs = frame.timestampMs,
        modifier = modifier,
        outerPadding = outerPadding,
        identity = {
            IdentityText(source, leading = symbol?.let { (table, sym) -> { AprsSymbolIcon(table, sym, size = 18.dp) } })
        },
        subtitle = ax25PathText(message),
        tags = tag?.let { { TypeTag(it) } },
        payload = if (text.isNotBlank()) { open -> PayloadText(text, open, mono = message.content is Ax25Content.Text && message.aprs == null, identities = ax25FrameCallsigns(frame)) } else null,
        expandedContent = {
            // The path is repeated here: opened, the full date and time take its room in the header.
            // A Mic-E frame's destination is half of its position, not a software identifier.
            Fields((if (aprs != null) aprsReportFields(aprs) + fields { add("Software", message.destination?.toString().takeIf { aprs !is MicEInfo }) } else ax25ContentFields(message)) +
                fields { add("Path", ax25Path(message)) })
            RadioLine(frameLine(frame))
            RawBytes(frame.raw)
        },
        actions = packetActions(frame, actions, copyText = text.takeIf { hasReadableText(message) }, specific = specific),
    )
}

// "Copy text" (a prominent button) only where the frame says something in words - like LoRa's
// text messages - not for every position report or connect request.
private fun hasReadableText(message: Ax25Message): Boolean =
    message.aprs is AprsTextMessage || message.aprs is StatusInfo || message.content is Ax25Content.Text ||
        message.content is Ax25Content.BulletinList

/** What a station's row says: its text (comment, message, status) before bare coordinates, then
 *  how it moves and how high it is; "no position" for one that never sent any. */
internal fun aprsStationRowText(station: TrackedObject<AprsStation>): String {
    val s = station.state
    return when (val info = s.lastInfo) {
        is AprsTextMessage, is StatusInfo, is TelemetryInfo, is ThirdPartyInfo -> aprsReportText(info)
        else -> metaLine(
            if (s.killed) "Deleted" else null,
            if (s.isObject) cleanComment(s.comment) else cleanComment(s.comment) ?: AprsSymbolIcons.label(s.symbolTable, s.symbol),
            motionText(s.courseDeg, s.speedKnots),
            s.altitudeFeet?.let(::feetToMeters),
            if (station.lat == null) "no position" else null,
        ).ifEmpty { info?.let(::aprsReportText).orEmpty() }
    }
}

/** What a station tells beyond its row. */
internal fun aprsStationFacts(station: TrackedObject<AprsStation>): List<Field> = fields {
    val s = station.state
    if (s.isObject) add("Reported by", s.callsign, identity = true)
    if (s.killed) add("Object", "deleted")
    station.lat?.let { lat -> station.lon?.let { lon -> add("Position", formatPosition(lat, lon)) } }
    s.altitudeFeet?.let { add("Altitude", feetToMeters(it)) }
    s.speedKnots?.takeIf { it >= 1 }?.let { add("Speed", formatKnots(it)) }
    s.courseDeg?.takeIf { it > 0 }?.let { add("Course", "${it.roundToInt()}°") }
    add("Symbol", AprsSymbolIcons.label(s.symbolTable, s.symbol))
    (s.lastInfo as? MicEInfo)?.let { mic ->
        add("Status", mic.messageType)
        add("Radio", mic.device)
    }
}

/**
 * One station's actions: Map, aprs.fi and QRZ.com as buttons, the rest behind "⋯". Shared by the
 * Overview row (and LoRa-APRS's) and the Map popup ([withMap] false), so they cannot drift apart.
 */
@Composable
internal fun aprsStationActions(
    station: TrackedObject<AprsStation>,
    family: ObjectFamily<AprsStation>,
    onShareJson: (json: String, filenamePrefix: String) -> Unit,
    onTrackOnMap: () -> Unit,
    onOpenUrl: (String) -> Unit,
    shareMessages: ((filenamePrefix: String) -> Unit)? = null,
    withMap: Boolean = true,
): List<PacketMenuAction> {
    val call = station.state.callsign
    return trackedObjectActions(
        station, family, onShareJson, onTrackOnMap, onOpenUrl, shareMessages, withMap = withMap,
        specific = listOf(
            PacketMenuAction(label = "aprs.fi", prominent = true) { onOpenUrl(aprsFiUrl(station.state.objectName?.trim() ?: call)) },
            PacketMenuAction(label = "QRZ.com", prominent = true) { onOpenUrl(qrzUrl(baseCallsign(call))) },
            copyTextAction("Copy callsign", call),
        ),
    )
}

/** The APRS symbol of a station, for its row, popup and ticker. */
@Composable
internal fun StationSymbol(station: AprsStation, size: androidx.compose.ui.unit.Dp = 16.dp) {
    AprsSymbolIcon(station.symbolTable, station.symbol, size = size, reserveSpace = true)
}

/**
 * The AX.25 ticker, above both sections: the latest frame - sender, what kind and the path small,
 * the report in words below. "Listening…" until the first frame of this session.
 */
@Composable
internal fun Ax25Ticker(feed: LiveFeed<Ax25Frame>, context: OverviewContext, lines: Int) {
    val frame = rememberLatestLive(feed.latest, context.live?.session?.startMs)
    val expansion = context.tickerExpansion
    LiveTicker(
        label = context.tickerLabel(frame?.let { "${ax25VariantBaudHz(it.phy.variant)} Bd" }),
        flashKey = frame?.timestampMs,
        expansion = expansion,
    ) {
        if (frame == null) {
            TickerWaiting("Listening on ${formatFrequency(context.live?.frequencyHz ?: 0)}…", if (expansion.open) expansion.openLines else lines)
        } else if (expansion.open) {
            val recent by feed.recent.collectAsState()
            TickerLog(
                recent.map { f ->
                    val symbol = f.data.aprs?.symbolOrNull()
                    TickerLogLine(
                        identity = f.data.source?.toString() ?: "?",
                        text = ax25FrameText(f),
                        timestampMs = f.timestampMs,
                        identities = ax25FrameCallsigns(f),
                        icon = symbol?.let { (table, sym) -> { AprsSymbolIcon(table, sym, size = 14.dp) } },
                    )
                },
                expansion.openLines,
            )
        } else {
            val message = frame.data
            val symbol = message.aprs?.symbolOrNull()
            TickerLines(
                lines = lines,
                identity = message.source?.toString() ?: "?",
                icon = symbol?.let { (table, sym) -> { AprsSymbolIcon(table, sym, size = 16.dp) } },
                meta = metaLine(aprsKindName(message.aprs) ?: ax25FrameTag(message), ax25PathText(message)),
                timestampMs = frame.timestampMs,
                payload = ax25FrameText(frame),
                identities = ax25FrameCallsigns(frame),
            )
        }
    }
}

/** What kind of APRS report, in a word, for the ticker's meta line. */
internal fun aprsKindName(info: AprsInfo?): String? = when (info) {
    is PositionReport, is MicEInfo, is RawGpsOrUltimeterInfo -> "Position"
    is AprsTextMessage -> "Message"
    is StatusInfo -> "Status"
    is ObjectReport -> "Object"
    is TelemetryInfo -> "Telemetry"
    is ThirdPartyInfo -> "Relayed"
    is UnknownAprsInfo -> "APRS"
    null -> null
}

/**
 * One [AprsStation] - a reporting station or named Object - in the Map subtab's popup. Also used
 * for LoRa-APRS stations.
 */
@Composable
internal fun TrackedAprsStationCard(
    station: TrackedObject<AprsStation>,
    family: ObjectFamily<AprsStation>,
    onClose: () -> Unit,
    onShareJson: (json: String, filenamePrefix: String) -> Unit,
    onTrack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    recent: List<RecentPacket>,
) {
    PacketCard(
        expanded = true,
        onExpand = onClose,
        timestampMs = station.lastSeenMs,
        identity = { IdentityText(station.displayName, leading = { StationSymbol(station.state, 18.dp) }) },
        subtitle = if (station.state.isObject) "by ${station.state.callsign}" else null,
        warning = if (station.state.killed) { { WarningText("Deleted", error = false) } } else null,
        payload = { open -> PayloadText(aprsStationRowText(station), open) },
        expandedContent = {
            Column {
                Fields(aprsStationFacts(station))
                RecentPacketsList(recent)
            }
        },
        actions = aprsStationActions(station, family, onShareJson, onTrack, onOpenUrl, withMap = false),
        outerPadding = PaddingValues(0.dp),
        shortTime = true,
    )
}

/** APRS stations and Objects on the Map subtab, AX.25 and LoRa-APRS alike: the station's own symbol,
 *  turned to its course. */
internal fun aprsMapLayer(family: ObjectFamily<AprsStation>, recent: (objectKey: String) -> Flow<List<RecentPacket>>) = MapLayer(
    family = family,
    recent = recent,
    marker = { station, lat, lon, _ ->
        MapMarker(
            id = station.markerId,
            lat = lat,
            lon = lon,
            title = station.displayName + if (station.state.killed) " (deleted)" else "",
            description = aprsStationRowText(station),
            iconKey = AprsSymbolIcons.iconKey(station.state.symbolTable, station.state.symbol),
            overlay = AprsSymbolIcons.overlayChar(station.state.symbolTable)?.toString(),
            rotationDeg = station.state.courseDeg?.toFloat(),
        )
    },
    popup = { station, recent, map ->
        TrackedAprsStationCard(
            station = station, family = family, onClose = map.onClose, onShareJson = map.onShareJson,
            onTrack = map.onTrack, onOpenUrl = map.onOpenUrl, recent = recent,
        )
    },
)
