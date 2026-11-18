package com.mantz_it.rfanalyzer.digiext.k2k

import com.mantz_it.rfanalyzer.digiext.ui.common.LocalDigitalClock
import com.mantz_it.rfanalyzer.digiext.callsign.withFlag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.digiext.ui.OverviewContext
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.CardListPadding
import com.mantz_it.rfanalyzer.digiext.ui.common.DigitalTextStyles
import com.mantz_it.rfanalyzer.digiext.ui.common.Fields
import com.mantz_it.rfanalyzer.digiext.ui.common.LiveTicker
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketCard
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.PayloadText
import com.mantz_it.rfanalyzer.digiext.ui.common.RadioLine
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerWaiting
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.common.formatDuration
import com.mantz_it.rfanalyzer.digiext.ui.common.formatFrequency
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.common.packetActions
import com.mantz_it.rfanalyzer.digiext.ui.common.plural
import com.mantz_it.rfanalyzer.digiext.ui.common.pskReporterUrl
import com.mantz_it.rfanalyzer.digiext.ui.common.qrzUrl
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
import java.util.Locale
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.mantz_it.rfanalyzer.digiext.ui.common.isTeletypeTable

/**
 * <h1>RF Analyzer - K2K Card</h1>
 *
 * Module:      K2kCard.kt
 * Description: How RTTY and PSK31 transmissions read: the card (who to whom, length and duration
 *              small, the text in teletype font), the ticker with the tail of what is being
 *              received, and the texts of rows.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

internal fun formatBaud(baud: Float): String = "%.2f".format(Locale.ROOT, baud).trimEnd('0').trimEnd('.')

/** "45.45 Bd / 170 Hz" (RTTY) or "31.25 Bd" (PSK31); null when nothing is known. */
internal fun k2kSignalText(phy: K2kPhy): String? = phy.baudRate?.let { baud ->
    "${formatBaud(baud)} Bd" + (phy.frequencyShift?.let { " / ${it.toInt()} Hz" } ?: "")
}

/** "DL2OCE → KO4IEG", "DL2OCE", or null while no callsign was recognised. */
internal fun k2kCallsText(text: K2kText): String? = when {
    text.fromCallsign != null && text.toCallsign != null -> "${text.fromCallsign} → ${text.toCallsign}"
    else -> text.fromCallsign ?: text.toCallsign
}

/** "225 characters · 49 s · part 2". */
internal fun k2kSizeText(message: K2kTransmission): String = metaLine(
    plural(message.data.text.length, "character", "characters"),
    message.endTimestampMs?.let { formatDuration(it - message.timestampMs) },
    message.data.part.takeIf { it > 0 }?.let { "part $it" },
)

/** A row's text: who to whom, then the text on one line. */
internal fun k2kRowText(message: K2kTransmission): String =
    (k2kCallsText(message.data)?.let { "$it: " } ?: "") + message.data.text.trim().replace(LINE_BREAKS, " ⏎ ").replace(SPACES, " ")

private val LINE_BREAKS = Regex("\\s*(\r\n|\r|\n)+\\s*")
private val SPACES = Regex("[ \t]+")

/**
 * One transmission. Header: the callsigns when they were recognised, length and duration small,
 * the time. Payload: the text in teletype font. Opened: all of it, the signal parameters.
 */
@Composable
fun K2kMessageCard(
    message: K2kTransmission,
    expanded: Boolean,
    onExpand: () -> Unit,
    actions: CardActions,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = CardListPadding,
) {
    val text = message.data
    val calls = k2kCallsText(text)
    PacketCard(
        expanded = expanded,
        onExpand = onExpand,
        timestampMs = message.timestampMs,
        modifier = modifier,
        outerPadding = outerPadding,
        identity = {
            if (calls != null) Text(calls, style = DigitalTextStyles.identity, maxLines = 1, overflow = TextOverflow.Ellipsis)
            else Text(k2kSizeText(message), style = DigitalTextStyles.meta, maxLines = 1)
        },
        subtitle = if (calls != null) k2kSizeText(message) else null,
        payload = { open -> PayloadText(text.text, open, mono = true, collapsedLines = 3, identities = listOfNotNull(text.fromCallsign, text.toCallsign), preformatted = true) },
        expandedContent = {
            Fields(fields {
                text.fromCallsign?.let { add("From", withFlag(it), identity = true) }
                text.toCallsign?.let { add("To", withFlag(it), identity = true) }
                add("Length", k2kSizeText(message))
            })
            RadioLine(
                message.mode.name,
                k2kSignalText(message.phy),
                message.phy.reversed?.let { if (it) "reversed" else "normal" },
                message.frequencyHz.takeIf { it != 0L }?.let(::formatFrequency),
            )
        },
        actions = k2kMessageActions(message, actions),
    )
}

@Composable
internal fun k2kMessageActions(message: K2kTransmission, actions: CardActions): List<PacketMenuAction> {
    val callsigns = listOfNotNull(message.data.fromCallsign, message.data.toCallsign).distinct()
    val specific = buildList {
        callsigns.firstOrNull()?.let { call ->
            add(PacketMenuAction(label = "QRZ.com", prominent = true) { actions.onOpenUrl(qrzUrl(call)) })
            add(PacketMenuAction(label = "PSKReporter") { actions.onOpenUrl(pskReporterUrl(call)) })
        }
        callsigns.drop(1).forEach { call -> add(PacketMenuAction(label = "QRZ.com/$call") { actions.onOpenUrl(qrzUrl(call)) }) }
        callsigns.forEach { call -> add(copyTextAction("Copy $call", call)) }
    }
    return packetActions(message, actions, copyText = message.data.text.trim(), specific = specific)
}

/** The longest tail of a transmission the ticker lays out - a few lines (a few more when open),
 *  not a whole broadcast. */
private const val TICKER_TAIL_CHARS = 600
private const val OPEN_TICKER_TAIL_CHARS = 3000

/**
 * The K2K ticker: what is being received now - how long and how much on line 1, the tail of the
 * text below, newest line at the bottom, at a fixed height. A long broadcast scrolls through it
 * without the ticker growing. Open, it shows that much more of the text.
 */
@Composable
internal fun K2kTicker(current: K2kTransmission?, earlierText: String, context: OverviewContext, closedLines: Int) {
    val expansion = context.tickerExpansion
    val lines = if (expansion.open) expansion.openLines else closedLines
    val clock = LocalDigitalClock.current
    LiveTicker(label = context.tickerLabel(current?.phy?.let(::k2kSignalText)), flashKey = null, expansion = expansion) {
        if (current == null) {
            TickerWaiting("Listening on ${formatFrequency(context.live?.frequencyHz ?: 0)}…", lines)
            return@LiveTicker
        }
        // A long broadcast is stored in parts: the end of the previous ones stays above the new part.
        val tail = (earlierText + current.data.text).takeLast(if (expansion.open) OPEN_TICKER_TAIL_CHARS else TICKER_TAIL_CHARS)
            .replace("\r\n", "\n").replace('\r', '\n').trimEnd().replace(BLANK_LINES, "\n")
        if (lines > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(20.dp)) {
                Text(
                    metaLine(
                        "Receiving ${formatDuration(clock() - current.timestampMs)}",
                        "${formatCount(current.data.text.length)} characters",
                        current.data.part.takeIf { it > 0 }?.let { "part $it" },
                    ),
                    style = DigitalTextStyles.meta,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        val tailLines = if (lines > 1) lines - 1 else 1
        val height = with(LocalDensity.current) { (20.sp * tailLines).toDp() }
        // The text is laid out at its full height and aligned to the bottom of the clipped box, so
        // the newest line is the one that shows.
        // A table keeps its lines (cut at the right edge, scrollable sideways), like the opened card.
        val table = isTeletypeTable(tail)
        Box(contentAlignment = Alignment.BottomStart, modifier = Modifier.fillMaxWidth().height(height).clipToBounds()) {
            Text(
                tail,
                style = DigitalTextStyles.mono.copy(lineHeight = 20.sp),
                softWrap = !table,
                modifier = Modifier.fillMaxWidth().wrapContentHeight(Alignment.Bottom, unbounded = true)
                    .then(if (table) Modifier.horizontalScroll(rememberScrollState()) else Modifier),
            )
        }
    }
}

private val BLANK_LINES = Regex("(\\s*(\r\n|\r|\n)){2,}")
