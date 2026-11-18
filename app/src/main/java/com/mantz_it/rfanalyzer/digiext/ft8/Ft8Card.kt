package com.mantz_it.rfanalyzer.digiext.ft8

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import com.mantz_it.rfanalyzer.digiext.ui.common.RecentPacketsList
import com.mantz_it.rfanalyzer.digiext.ui.common.RecentPacket
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.mantz_it.rfanalyzer.digiext.callsign.callsignFlag
import com.mantz_it.rfanalyzer.digiext.callsign.dxccCountryOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.database.Coordinates
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.db.markerId
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.pskReporterUrl
import com.mantz_it.rfanalyzer.digiext.ui.common.qrzUrl
import kotlin.math.abs
import kotlin.math.roundToInt
import com.mantz_it.rfanalyzer.digiext.ui.MapLayer
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.CardListPadding
import com.mantz_it.rfanalyzer.digiext.ui.common.DigitalTextStyles
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.Fields
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketCard
import com.mantz_it.rfanalyzer.digiext.ui.common.PayloadText
import com.mantz_it.rfanalyzer.digiext.ui.common.identityStyled
import com.mantz_it.rfanalyzer.digiext.ui.common.RadioLine
import com.mantz_it.rfanalyzer.digiext.ui.common.RawSection
import com.mantz_it.rfanalyzer.digiext.ui.common.TypeTag
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.formatFrequency
import com.mantz_it.rfanalyzer.digiext.ui.common.packetActions
import com.mantz_it.rfanalyzer.digiext.ui.trackedObjectActions
import com.mantz_it.rfanalyzer.ui.composable.MapMarker
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * <h1>RF Analyzer - FT8 Card</h1>
 *
 * Module:      Ft8Card.kt
 * Description: How FT8 decodes read: the one-line card ("VO1HH → 8Z1IT RR73 · −5 dB +1.2 s"), the
 *              texts of rows and lists, a station's facts and actions, and the Map layer.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * An FT8 message split for display: who sends ([from]) to whom ([to]), what they exchange (the
 * report, grid, RR73 or the free text) and - for the less common types - what type it is.
 */
data class Ft8Line(val from: String?, val to: String?, val exchange: String, val tag: String? = null) {
    /** "VO1HH → 8Z1IT RR73". */
    val text: String
        get() = when {
            from != null && to != null -> "$from → $to" + if (exchange.isNotEmpty()) " $exchange" else ""
            from != null -> "$from $exchange".trim()
            else -> exchange
        }
}

private fun Callsign?.shown(suffix: String = ""): String = (this?.toString() ?: "<...>") + suffix

private fun reportText(roger: Boolean, report: Report?): String = when (report) {
    null -> if (roger) "R" else ""
    is LocationReport -> (if (roger) "R " else "") + report.grid
    is SignalReport -> (if (roger) "R" else "") + report
    else -> report.toString()
}

/**
 * [message] as from/to/exchange. call2 is the transmitting station and call1 the called one (see
 * [ft8Transmitter]); the roger "R" and a hashed callsign's "<…>" are kept, since both are part of
 * what was sent.
 */
fun ft8Line(message: Ft8Message): Ft8Line = when (message) {
    is StandardMessage -> Ft8Line(message.call2.shown(if (message.rover2) "/R" else ""), message.call1.shown(if (message.rover1) "/R" else ""), reportText(message.roger, message.report))
    is EUVHFMessage -> Ft8Line(message.call2.shown(if (message.portable2) "/P" else ""), message.call1.shown(if (message.portable1) "/P" else ""), reportText(message.roger, message.report), "EU VHF")
    is NonstandardCallMessage -> Ft8Line(message.call2.shown(), message.call1.shown(), message.report.value)
    is RTTYMessage -> Ft8Line(
        message.call2.shown(), message.call1.shown(),
        listOfNotNull("TU".takeIf { message.thankYou }, "R".takeIf { message.roger }, "${message.signal} ${message.state}").joinToString(" "),
        "RTTY contest",
    )
    is DXpeditionMessage -> Ft8Line(message.call3.shown(), message.call1.shown(), "RR73 · ${message.call2.shown()} ${SignalReport(message.report)}", "DXpedition")
    is FieldDayMessage -> Ft8Line(message.call2.shown(), message.call1.shown(), (if (message.roger) "R " else "") + "${message.transmitters}${message.entryClass} ${message.section}", "Field Day")
    is EuVhfContestMessage -> Ft8Line(message.call2.shown(), message.call1.shown(), (if (message.roger) "R " else "") + "${message.report}${"%04d".format(Locale.US, message.serial)} ${message.grid}", "Contest")
    is TextMessage -> Ft8Line(null, null, message.text.trim(), "Free text")
    is TelemetryMessage -> Ft8Line(null, null, "0x${message.hex}", "Telemetry")
    is UnknownMessage -> Ft8Line(null, null, message.text, "Unknown type")
}

/** One line for a message: "VO1HH → 8Z1IT RR73" - list lines, map descriptions. */
fun ft8MessageSummary(message: Ft8Message): String = ft8Line(message).text

/** A station's row text: its last message without repeating the station - "→ 8Z1IT RR73". */
internal fun ft8RowText(station: TrackedObject<Ft8Station>): String? {
    val message = station.state.lastMessage ?: return null
    val line = ft8Line(message)
    return if (line.from != null && line.from.removePrefix("<").removeSuffix(">") == station.key && line.to != null) {
        "→ ${line.to}" + if (line.exchange.isNotEmpty()) " ${line.exchange}" else ""
    } else line.text
}

fun formatFt8Snr(snr: Float): String = "%+d".format(Locale.US, snr.roundToInt())

fun formatFt8Delta(deltaMs: Long): String = "%+.1f s".format(Locale.US, deltaMs / 1000f)

/** SNR in the colour of its strength: green from 0 dB, amber down to −10 dB, red below. */
internal fun ft8SnrColor(snr: Float): Color = when {
    snr >= 0f -> Color(0xFF4CAF50)
    snr >= -10f -> Color(0xFFFFC107)
    else -> Color(0xFFF44336)
}

/** Δt in the colour of how far off the period it is: within 0.5 s fine, 1.5 s amber, more red. */
internal fun ft8DeltaColor(deltaMs: Long): Color = when {
    abs(deltaMs) <= 500 -> Color(0xFF4CAF50)
    abs(deltaMs) <= 1500 -> Color(0xFFFFC107)
    else -> Color(0xFFF44336)
}

/** "−5 dB  +1.2 s", each in its colour. */
@Composable
internal fun Ft8SignalTexts(snr: Float, deltaMs: Long, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Text("${formatFt8Snr(snr)} dB", style = DigitalTextStyles.meta.copy(color = ft8SnrColor(snr), fontWeight = FontWeight.Medium), maxLines = 1)
        Text(formatFt8Delta(deltaMs), style = DigitalTextStyles.meta.copy(color = ft8DeltaColor(deltaMs), fontWeight = FontWeight.Medium), maxLines = 1, modifier = Modifier.padding(start = 6.dp))
    }
}

fun extractGrid(received: Ft8Decode): String? = when (val msg = received.data) {
    is StandardMessage -> (msg.report as? LocationReport)?.grid
    is EUVHFMessage -> (msg.report as? LocationReport)?.grid
    is EuVhfContestMessage -> msg.grid
    else -> null
}

/** The real callsigns of a message, sender first - no tokens (CQ, QRZ, DE), no unknown hashes. */
fun ft8Callsigns(message: Ft8Message): List<String> {
    val calls = when (val msg = message) {
        is StandardMessage -> listOf(msg.call2, msg.call1)
        is EUVHFMessage -> listOf(msg.call2, msg.call1)
        is RTTYMessage -> listOf(msg.call2, msg.call1)
        is NonstandardCallMessage -> listOf(msg.call2, msg.call1)
        is DXpeditionMessage -> listOf(msg.call3, msg.call1, msg.call2)
        is FieldDayMessage -> listOf(msg.call2, msg.call1)
        is EuVhfContestMessage -> listOf(msg.call2, msg.call1)
        is TextMessage, is TelemetryMessage, is UnknownMessage -> emptyList()
    }
    return calls.filterNotNull().filterNot { it.token || it.callsign == "..." }.map { it.callsign }.distinct()
}

/** "🇨🇦 Canada" - the flag and DXCC entity of a callsign; null when the DXCC table does not know it. */
private fun countryText(callsign: String): String? =
    dxccCountryOf(callsign)?.let { listOfNotNull(callsignFlag(callsign), it.countryName).joinToString(" ") }

/**
 * One decode. Everything that matters fits the header line: who calls whom and what they exchange,
 * the grid, SNR and Δt in their colours, the time. Opened: the message as fields (the callsigns with
 * their countries, the exchange, the grid), the frequency, the raw bits.
 */
@Composable
fun Ft8MessageCard(
    message: Ft8Decode,
    expanded: Boolean,
    onExpand: () -> Unit,
    actions: CardActions,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = CardListPadding,
) {
    val line = ft8Line(message.data)
    val grid = extractGrid(message)
    val calls = ft8Callsigns(message.data)
    val specific = buildList {
        grid?.let { Coordinates.fromGridLocator(it) }?.let { coords ->
            // The transmitting station's marker - it carries this grid.
            val station = ft8Transmitter(message.data)?.first
            add(PacketMenuAction(label = "Map", icon = Icons.Default.Map, prominent = true) {
                actions.onTrackOnMap(station?.let { markerId(ObjectKind.FT8_STATION, it) } ?: grid, coords.latitude, coords.longitude)
            })
        }
        calls.firstOrNull()?.let { call ->
            add(PacketMenuAction(label = "QRZ.com", prominent = true) { actions.onOpenUrl(qrzUrl(call)) })
            add(PacketMenuAction(label = "PSKReporter", prominent = true) { actions.onOpenUrl(pskReporterUrl(call)) })
        }
        calls.drop(1).forEach { call -> add(PacketMenuAction(label = "QRZ.com/$call") { actions.onOpenUrl(qrzUrl(call)) }) }
        calls.forEach { call -> add(copyTextAction("Copy $call", call)) }
    }
    PacketCard(
        expanded = expanded,
        onExpand = onExpand,
        timestampMs = message.timestampMs,
        modifier = modifier,
        outerPadding = outerPadding,
        identity = {
            if (line.from != null) {
                // The callsigns in the identity colour, what they exchange in the text colour.
                Text(identityStyled(line.text, calls), style = DigitalTextStyles.identity.copy(color = MaterialTheme.colorScheme.onSurface), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(line.tag.orEmpty(), style = DigitalTextStyles.meta.copy(fontWeight = FontWeight.Medium), maxLines = 1)
            }
        },
        subtitle = grid?.takeIf { line.from != null && it !in line.exchange },
        tags = line.tag?.takeIf { line.from != null }?.let { { TypeTag(it) } },
        trailing = { Ft8SignalTexts(message.phy.snr, message.phy.timeDeltaMs) },
        payload = if (line.from == null) { open -> PayloadText(line.exchange, open, mono = message.data is TelemetryMessage) } else null,
        // Opened, the full date and time take the header's room: the fields say all it says.
        expandedContent = {
            Fields(fields {
                ft8Transmitter(message.data)?.let { (from, to) ->
                    add("From", countryText(from)?.let { "$from · $it" } ?: from)
                    // A token (CQ, "CQ DX", QRZ) or an unknown hash as sent: no country to look up.
                    add("To", to?.let { countryText(it)?.let { c -> "$it · $c" } ?: it } ?: line.to)
                }
                if (line.from != null) add("Exchange", line.exchange.takeIf { it != grid })
                grid?.let { g -> add("Grid", Coordinates.fromGridLocator(g)?.let { "$g (%.1f, %.1f)".format(Locale.US, it.latitude, it.longitude) } ?: g) }
                add("Type", line.tag)
            })
            RadioLine(message.frequencyHz.takeIf { it != 0L }?.let(::formatFrequency))
            RawSection("Raw · 77 + 14 bits") { "Message ${message.messageBits}\nCRC     ${message.crcBits}" }
        },
        actions = packetActions(message, actions, specific = specific),
    )
}

/** What is known about a station beyond its row. */
internal fun ft8StationFacts(station: TrackedObject<Ft8Station>): List<Field> = fields {
    add("Country", dxccCountryOf(station.key)?.let { "${listOfNotNull(callsignFlag(station.key), it.countryName).joinToString(" ")} (${it.continent})" })
    add("Grid", station.state.grid)
    add("SNR", "last ${formatFt8Snr(station.state.lastSnr)} dB · best ${formatFt8Snr(station.state.bestSnr)} dB")
    add("Δt", formatFt8Delta(station.state.lastTimeDeltaMs))
    add("Last called", station.state.lastCalledCallsign, identity = true)
    add("Frequency", station.frequencyHz?.let(::formatFrequency))
}

@Composable
internal fun ft8StationActions(
    station: TrackedObject<Ft8Station>,
    onShareJson: (json: String, filenamePrefix: String) -> Unit,
    onTrackOnMap: () -> Unit,
    onOpenUrl: (String) -> Unit,
    shareMessages: ((filenamePrefix: String) -> Unit)? = null,
    withMap: Boolean = true,
): List<PacketMenuAction> = trackedObjectActions(
    station, Ft8StationFamily, onShareJson, onTrackOnMap, onOpenUrl, shareMessages, withMap = withMap,
    specific = buildList {
        // PSKReporter first: with "Details" and "Map" an opened row has room for one more button
        add(PacketMenuAction(label = "PSKReporter", prominent = true) { onOpenUrl(pskReporterUrl(station.key)) })
        add(PacketMenuAction(label = "QRZ.com", prominent = true) { onOpenUrl(qrzUrl(station.key)) })
        add(copyTextAction("Copy callsign", station.key))
        station.state.lastCalledCallsign?.let { called -> add(PacketMenuAction(label = "QRZ.com/$called") { onOpenUrl(qrzUrl(called)) }) }
    },
)

/** One FT8 station in the Map subtab's popup. */
@Composable
internal fun TrackedFt8StationCard(
    station: TrackedObject<Ft8Station>,
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
        identity = { Text(listOfNotNull(callsignFlag(station.key), station.key).joinToString(" "), style = DigitalTextStyles.identity, maxLines = 1) },
        subtitle = station.state.grid,
        trailing = { Ft8SignalTexts(station.state.lastSnr, station.state.lastTimeDeltaMs) },
        payload = station.state.lastMessage?.let { message -> { open -> PayloadText(ft8MessageSummary(message), open, identities = ft8Callsigns(message)) } },
        expandedContent = {
            Column {
                Fields(ft8StationFacts(station))
                RecentPacketsList(recent)
            }
        },
        actions = ft8StationActions(station, onShareJson, onTrack, onOpenUrl, withMap = false),
        outerPadding = PaddingValues(0.dp),
        shortTime = true,
    )
}

/** A message as one line of a station's recent-messages list: its SNR, then what it said. The SNR is
 *  padded with figure spaces and uses the minus sign, which are as wide as a (tabular) digit and "+",
 *  so the messages line up. */
internal fun ft8RecentLine(decode: Ft8Decode): String =
    "${formatFt8Snr(decode.phy.snr).replace('-', '\u2212').padStart(3, '\u2007')} dB  ${ft8MessageSummary(decode.data)}"

/** FT8 stations on the Map subtab, at the centre of their grid square. */
internal fun ft8MapLayer(recent: (objectKey: String) -> Flow<List<RecentPacket>>) = MapLayer(
    family = Ft8StationFamily,
    recent = recent,
    marker = { station, lat, lon, _ ->
        MapMarker(
            id = station.markerId,
            lat = lat,
            lon = lon,
            title = station.displayName,
            description = station.state.lastMessage?.let(::ft8MessageSummary) ?: station.displayName,
            iconKey = FT8_GRID_ICON,
        )
    },
    popup = { station, recent, map ->
        TrackedFt8StationCard(
            station = station, onClose = map.onClose, onShareJson = map.onShareJson,
            onTrack = map.onTrack, onOpenUrl = map.onOpenUrl, recent = recent,
        )
    },
)

/** The map icon of a station placed at its grid square's centre (not an exact position). */
internal const val FT8_GRID_ICON = "ft8_grid"
