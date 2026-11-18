package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * <h1>RF Analyzer - Live Ticker</h1>
 *
 * Module:      LiveTicker.kt
 * Description: What is being received right now, above a mode's table - one of the app's outlined
 *              groups whose label says what is received ("AX.25 · 144.390 MHz"). The label is the
 *              live indicator; the ticker exists only while the shown mode is being received.
 *
 *              Fixed height: [lines] content lines, one at the drawer's default height and more
 *              when it is pulled up. A new packet replaces the content and flashes the border;
 *              nothing below it moves. Where more is worth reading - a teletype text, a log of the
 *              last packets - a tap opens the ticker to about half the Overview
 *              ([TickerExpansion]).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

private val LINE_HEIGHT = 20.dp

/** An expandable ticker: closed it shows its usual lines, [open] it shows [openLines]; a tap on it
 *  calls [onToggle]. */
class TickerExpansion(val open: Boolean, val openLines: Int, val onToggle: () -> Unit)

@Composable
fun LiveTicker(
    label: String,
    /** Changes with every new packet - flashes the border. */
    flashKey: Any?,
    modifier: Modifier = Modifier,
    /** Set for a ticker that opens: a chevron in the border, a tap toggles. */
    expansion: TickerExpansion? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    val border = remember(outline) { Animatable(outline) }
    // The first packet the ticker shows is the one the live flow replays - not news; every one
    // after it flashes.
    val shownKey = remember { arrayOfNulls<Any>(1) }
    LaunchedEffect(flashKey) {
        val previous = shownKey[0]
        shownKey[0] = flashKey
        if (flashKey != null && previous != null && flashKey != previous) {
            border.snapTo(primary)
            border.animateTo(outline, tween(1200))
        }
    }
    OutlinedGroup(
        label = label,
        borderColor = border.value,
        endLabel = expansion?.let { e ->
            {
                Icon(
                    if (e.open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (e.open) "Show less" else "Show more",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp)
            .then(if (expansion != null) Modifier.clickable(onClick = expansion.onToggle) else Modifier),
    ) {
        Column(modifier = Modifier.fillMaxWidth(), content = content)
    }
}

/** One line of an open ticker's log: who, what, when. */
class TickerLogLine(
    val identity: String,
    val text: String,
    val timestampMs: Long,
    val icon: (@Composable () -> Unit)? = null,
    /** Callsigns in [text], drawn in the identity colour. */
    val identities: Collection<String> = emptyList(),
)

/**
 * An open ticker's log: the session's last packets, newest on top, one line each, at the ticker's
 * full [lines] height (empty lines while there are fewer).
 */
@Composable
fun TickerLog(entries: List<TickerLogLine>, lines: Int, identityWidth: Dp = 96.dp) {
    val clock = LocalDigitalClock.current
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(clock) {
        while (true) {
            now = clock()
            delay(1000)
        }
    }
    Column(modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT * lines)) {
        entries.take(lines).forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.width(identityWidth)) {
                    entry.icon?.let {
                        it()
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(entry.identity, style = DigitalTextStyles.identity.copy(fontSize = 13.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    identityStyled(oneLine(entry.text), entry.identities),
                    style = DigitalTextStyles.rowPayload,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                )
                Text(formatAge(now, entry.timestampMs), style = DigitalTextStyles.faint, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/**
 * The usual ticker content: identity, the small meta and the age on line 1, the payload on the
 * remaining [lines] - 1 lines; all on one line when [lines] is 1.
 */
@Composable
fun TickerLines(
    lines: Int,
    identity: String,
    timestampMs: Long?,
    payload: String?,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    meta: String? = null,
    mono: Boolean = false,
    /** Callsigns in the payload, drawn in the identity colour - see [identityRanges]. */
    identities: Collection<String> = emptyList(),
) {
    val payloadStyle = (if (mono) DigitalTextStyles.mono else DigitalTextStyles.payload).copy(lineHeight = 20.sp)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(LINE_HEIGHT)) {
            icon?.let {
                it()
                Spacer(Modifier.width(4.dp))
            }
            // Capped rather than weighted: two weights would split the line and leave the age
            // floating in the middle instead of at the end.
            Text(identity, style = DigitalTextStyles.identity, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 168.dp))
            Spacer(Modifier.width(8.dp))
            if (lines == 1) {
                Text(identityStyled(oneLine(payload ?: meta.orEmpty()), identities), style = payloadStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else {
                Text(meta.orEmpty(), style = DigitalTextStyles.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            timestampMs?.let { TickerAge(it) }
        }
        if (lines > 1) {
            Text(
                text = identityStyled(payload?.let { if (mono) it.trim() else oneLine(it) }.orEmpty(), identities),
                style = payloadStyle,
                minLines = lines - 1,
                maxLines = lines - 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Listening on 144.390 MHz…" until the first packet, at the ticker's full height. */
@Composable
fun TickerWaiting(text: String, lines: Int) {
    Text(
        text = text,
        style = DigitalTextStyles.meta.copy(color = MaterialTheme.colorScheme.outline, lineHeight = 20.sp),
        minLines = lines,
        maxLines = lines,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The age of the shown packet, counting up by itself. */
@Composable
fun TickerAge(timestampMs: Long, modifier: Modifier = Modifier) {
    val clock = LocalDigitalClock.current
    var now by remember { mutableLongStateOf(clock()) }
    LaunchedEffect(timestampMs, clock) {
        while (true) {
            now = clock()
            delay(1000)
        }
    }
    Text(formatAge(now, timestampMs), style = DigitalTextStyles.faint, maxLines = 1, modifier = modifier.padding(start = 6.dp))
}

/**
 * The latest packet of a live flow, or null before this session's first one. The repository
 * empties the flow's replay when a session starts; [session] restarts the collection then, so a
 * ticker that stayed on screen lets go of the previous session's packet as well.
 */
@Composable
fun <P : StoredPacket<*, *>> rememberLatestLive(packets: Flow<P>, session: Any?): P? =
    key(session) { packets.collectAsState(initial = null).value }
