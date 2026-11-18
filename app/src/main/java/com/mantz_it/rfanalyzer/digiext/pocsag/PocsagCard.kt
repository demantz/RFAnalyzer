package com.mantz_it.rfanalyzer.digiext.pocsag

import androidx.compose.runtime.collectAsState
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLogLine
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLog
import com.mantz_it.rfanalyzer.digiext.LiveFeed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.ui.OverviewContext
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.CardListPadding
import com.mantz_it.rfanalyzer.digiext.ui.common.Fields
import com.mantz_it.rfanalyzer.digiext.ui.common.IdentityText
import com.mantz_it.rfanalyzer.digiext.ui.common.LiveTicker
import com.mantz_it.rfanalyzer.digiext.ui.common.LocalDigitalClock
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketCard
import com.mantz_it.rfanalyzer.digiext.ui.common.PayloadText
import com.mantz_it.rfanalyzer.digiext.ui.common.RadioLine
import com.mantz_it.rfanalyzer.digiext.ui.common.RawSection
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLines
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerWaiting
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.formatFrequency
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.common.packetActions
import com.mantz_it.rfanalyzer.digiext.ui.common.toHexDump
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay

/**
 * <h1>RF Analyzer - POCSAG Card</h1>
 *
 * Module:      PocsagCard.kt
 * Description: How POCSAG pages read: the page card, the ticker (with its queue: pages come in
 *              bursts), and the texts rows and lists show. A page is labelled with the reading it
 *              is shown in - never with what its function bits suggest.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** "Alpha", "Numeric", "Skyper", "Tone only", "Empty" - the reading a page is shown in. */
internal fun readingName(reading: PocsagReading): String = when (reading) {
    PocsagReading.NUMERIC -> "Numeric"
    PocsagReading.ALPHA -> "Alpha"
    PocsagReading.SKYPER -> "Skyper"
    PocsagReading.TONE -> "Tone only"
    PocsagReading.NONE -> "Empty"
}

/** What a page says: its text in the reading it is shown in, behind its Skyper source tag; the
 *  reading's name for a page without text. */
internal fun pocsagPageText(message: PocsagMessage, function: Int): String {
    val reading = message.primaryReading(function)
    val text = message.primaryText(function)?.toPrintable() ?: return readingName(reading)
    return message.skyperTag?.takeIf { reading == PocsagReading.SKYPER }?.let { "[${it.trim()}] $text" } ?: text
}

internal fun pocsagPageText(page: PocsagPage): String = pocsagPageText(page.data, page.phy.function)

/**
 * One page. Header: the capcode, the reading, the time. Payload: the text, up to three lines.
 * Opened: the other reading where it says something else, the function and the raw bits.
 */
@Composable
fun PocsagMessageCard(
    page: PocsagPage,
    expanded: Boolean,
    onExpand: () -> Unit,
    actions: CardActions,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = CardListPadding,
) {
    val reading = page.primaryReading
    val text = page.primaryText?.toPrintable()
    PacketCard(
        expanded = expanded,
        onExpand = onExpand,
        timestampMs = page.timestampMs,
        modifier = modifier,
        outerPadding = outerPadding,
        identity = {
            IdentityText(page.phy.address.toString(), leading = {
                Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            })
        },
        subtitle = metaLine(readingName(reading), page.data.skyperTag?.takeIf { reading == PocsagReading.SKYPER }?.trim()),
        payload = if (text != null) { open -> PayloadText(text, open, collapsedLines = 3) } else null,
        expandedContent = {
            Fields(fields {
                val secondary = page.data.secondaryText(page.phy.function)?.toPrintable()
                if (secondary != null && secondary != text) add(if (reading == PocsagReading.NUMERIC) "As alpha" else "As numeric", secondary, mono = true)
                if (reading == PocsagReading.SKYPER) add("As sent", page.data.alphaMessage?.toPrintable(), mono = true)
            })
            RadioLine(
                "Function ${page.phy.function}",
                "${page.phy.bitCount} bits",
                page.phy.damagedCodewords.size.takeIf { it > 0 }?.let { "$it damaged codeword${if (it == 1) "" else "s"}" },
                "${page.phy.baudRate} bit/s",
                page.frequencyHz.takeIf { it != 0L }?.let(::formatFrequency),
            )
            page.raw?.takeIf { it.size > 0 }?.let { raw ->
                RawSection("Raw · ${page.phy.bitCount} bits") { raw.toByteArray().toHexDump(8, 4) }
            }
        },
        actions = packetActions(page, actions, copyText = text, specific = listOf(copyTextAction("Copy capcode", page.phy.address.toString()))),
    )
}

private const val DEFAULT_MIN_DWELL_MS = 5_000L
private const val DEFAULT_SHORT_DWELL_MS = 1_000L
private const val DEFAULT_SHORT_TEXT_MAX_CHARS = 5
private const val DEFAULT_MAX_QUEUE = 5

/** A page older than this when it reaches the ticker is a replay (the tab was just opened), not a
 *  burst to read through: it is shown without holding up the queue. */
private const val STALE_REPLAY_MS = 60_000L

/** How long the ticker shows a page: long enough to read, short for a tone or a few digits. */
internal fun tickerDwellMs(
    text: String?,
    minDwellMs: Long = DEFAULT_MIN_DWELL_MS,
    shortDwellMs: Long = DEFAULT_SHORT_DWELL_MS,
    shortTextMaxChars: Int = DEFAULT_SHORT_TEXT_MAX_CHARS,
): Long = if ((text?.length ?: 0) < shortTextMaxChars) shortDwellMs else minDwellMs

/** The pages waiting behind the one on screen; a burst beyond [maxSize] drops the oldest, so the
 *  ticker stays near what is happening now. */
internal class TickerQueue(private val maxSize: Int) {
    private val items = ArrayDeque<PocsagPage>()

    val size: Int get() = items.size

    fun offer(page: PocsagPage) {
        items.addLast(page)
        while (items.size > maxSize) items.removeFirst()
    }

    fun poll(): PocsagPage? = items.removeFirstOrNull()
}

/** The text a page shows in the ticker; null for a page without one. */
internal fun tickerText(page: PocsagPage): String? = when (page.primaryReading) {
    PocsagReading.TONE, PocsagReading.NONE -> null
    else -> page.primaryText?.toPrintable()
}

/**
 * The POCSAG ticker: each page for a moment long enough to read it, the burst behind it queued
 * ("+3" in the meta line). Capcode and reading on line 1, the text below.
 */
@Composable
internal fun PocsagTicker(feed: LiveFeed<PocsagPage>, context: OverviewContext, lines: Int) {
    val pages = feed.latest
    val expansion = context.tickerExpansion
    val queue = remember { TickerQueue(DEFAULT_MAX_QUEUE) }
    // Wakes the display loop when a page is queued - it sleeps otherwise.
    val queued = remember { Channel<Unit>(Channel.CONFLATED) }
    var current by remember { mutableStateOf<PocsagPage?>(null) }
    var pending by remember { mutableIntStateOf(0) }
    val clock = LocalDigitalClock.current

    // The live flow holds this session's pages only (the repository empties it when one starts).
    LaunchedEffect(pages, queue, context.live?.session?.startMs) {
        pages.collect { page ->
            queue.offer(page)
            pending = queue.size
            queued.trySend(Unit)
        }
    }
    LaunchedEffect(queue) {
        while (true) {
            val next = queue.poll()
            if (next == null) {
                queued.receive()
                continue
            }
            pending = queue.size
            current = next
            if (clock() - next.timestampMs <= STALE_REPLAY_MS) delay(tickerDwellMs(tickerText(next)))
        }
    }

    val page = current
    LiveTicker(label = context.tickerLabel(page?.let { "${it.phy.baudRate} bit/s" }), flashKey = page?.timestampMs, expansion = expansion) {
        if (page == null) {
            TickerWaiting("Listening on ${formatFrequency(context.live?.frequencyHz ?: 0)}…", if (expansion.open) expansion.openLines else lines)
        } else if (expansion.open) {
            // Open, every page of the session at once instead of one at a time.
            val recent by feed.recent.collectAsState()
            TickerLog(
                recent.map { TickerLogLine(identity = it.phy.address.toString(), text = tickerText(it) ?: readingName(it.primaryReading), timestampMs = it.timestampMs) },
                expansion.openLines,
                identityWidth = 76.dp,
            )
        } else {
            TickerLines(
                lines = lines,
                identity = page.phy.address.toString(),
                icon = { Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(15.dp)) },
                meta = metaLine(readingName(page.primaryReading), if (pending > 0) "+$pending waiting" else null),
                timestampMs = page.timestampMs,
                payload = tickerText(page) ?: readingName(page.primaryReading),
            )
        }
    }
}
