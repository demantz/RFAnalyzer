package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.db.DETAILS_PAGE_SIZE
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf

/**
 * <h1>RF Analyzer - Message List</h1>
 *
 * Module:      MessageList.kt
 * Description: One object's messages inside its opened row or on its details page: time and
 *              payload (two lines at most), newest first; a message opens into its card in place.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * Deliberately a plain [Column], not a lazy list: it lives inside a row of the Overview's
 * LazyColumn, where a nested vertical scroller would be measured with infinite height. [maxRows]
 * and the query's own limit bound it.
 *
 * While a message is open the list holds still ([items] as they were when it was opened): a list
 * inserting above the message being read would move it away. New arrivals are counted instead.
 */
@Composable
fun <D, K : Any> MessageList(
    items: List<D>,
    key: (D) -> K,
    time: (D) -> Long,
    text: (D) -> String,
    modifier: Modifier = Modifier,
    title: String? = null,
    mono: Boolean = false,
    /** Callsigns in an item's text, drawn in the identity colour - see [identityRanges]. */
    identities: ((D) -> Collection<String>)? = null,
    /** A short value after the text - FT8's SNR. */
    extra: ((D) -> String?)? = null,
    extraWidth: Dp = 44.dp,
    maxRows: Int = 20,
    emptyText: String = "No stored messages.",
    /** "+N more" below the list leads here (the details page), when given. */
    onMore: (() -> Unit)? = null,
    /** How many messages there are - [items] may be only the newest of them. */
    total: Int? = null,
    /** Reads further back ("Show more" below the list) - the details page. */
    onLoadMore: (() -> Unit)? = null,
    /** An opened message's card. A tap on it that it doesn't take itself closes the message - not
     *  the opened row around the list, which would take it otherwise. */
    card: (@Composable (item: D, collapse: () -> Unit) -> Unit)? = null,
) {
    var openKey: K? by remember { mutableStateOf(null) }
    var frozen: List<D>? by remember { mutableStateOf(null) }
    val shown = frozen?.let { frozenRows(it.take(maxRows), items, key) } ?: remember(items, maxRows) { items.take(maxRows) }
    val newCount = frozen?.let { newRowCount(it, items, key) } ?: 0
    val close = { frozen = null; openKey = null }
    val nowMs = System.currentTimeMillis()

    Column(modifier = modifier.fillMaxWidth()) {
        title?.let {
            Text(it, style = DigitalTextStyles.faint, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
        }
        if (shown.isEmpty()) {
            Text(emptyText, style = DigitalTextStyles.faint, modifier = Modifier.padding(vertical = 6.dp))
            return@Column
        }
        if (newCount > 0) {
            Divider()
            Text(
                text = if (newCount == 1) "1 new message - tap to show" else "$newCount new messages - tap to show",
                style = DigitalTextStyles.meta.copy(color = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth().clickable(onClick = close).padding(vertical = 6.dp),
            )
        }
        shown.forEach { item ->
            val itemKey = key(item)
            // Keyed, so a row's opening animation and its card's state stay with the message when
            // the list changes around it.
            androidx.compose.runtime.key(itemKey) {
                val open = openKey == itemKey
                Divider()
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            if (open) close() else {
                                if (frozen == null) frozen = items
                                openKey = itemKey
                            }
                        }
                        .padding(vertical = 5.dp),
                ) {
                    Text(
                        text = formatShortTime(nowMs, time(item)),
                        style = DigitalTextStyles.faint,
                        maxLines = 1,
                        modifier = Modifier.width(46.dp).padding(top = 1.dp),
                    )
                    Text(
                        text = identityStyled(text(item), identities?.invoke(item).orEmpty()),
                        style = if (mono) DigitalTextStyles.mono else DigitalTextStyles.rowPayload.copy(fontSize = DigitalTextStyles.value.fontSize),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                    extra?.invoke(item)?.let {
                        Text(
                            text = it,
                            style = DigitalTextStyles.meta,
                            textAlign = TextAlign.End,
                            maxLines = 1,
                            modifier = Modifier.width(extraWidth).padding(start = 6.dp, top = 1.dp),
                        )
                    }
                }
                AnimatedVisibility(visible = open && card != null) {
                    Box(modifier = Modifier.fillMaxWidth().clickable(onClick = close)) {
                        card?.invoke(item, close)
                    }
                }
            }
        }
        val more = maxOf(total ?: 0, (frozen ?: items).size) - shown.size
        if (frozen == null && (more > 0 || onLoadMore != null)) {
            Divider()
            val action = onLoadMore ?: onMore
            Text(
                text = when {
                    onLoadMore != null && more > 0 -> "Show ${formatCount(minOf(more, DETAILS_PAGE_SIZE))} more (${formatCount(more)} older)"
                    onLoadMore != null -> "Show older messages"
                    onMore != null -> "+${formatCount(more)} older - all on the details page"
                    else -> "+${formatCount(more)} older"
                },
                style = DigitalTextStyles.faint.let { if (action != null) it.copy(color = MaterialTheme.colorScheme.primary) else it },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (action != null) Modifier.clickable(onClick = action) else Modifier)
                    .padding(vertical = 6.dp),
            )
        }
    }
}

/** An object's messages as a [MessageList] shows them: [items] (the newest), [total] (all stored)
 *  and, on the details page, [loadMore] while there are older ones to read. */
class ObjectMessages<T>(val items: List<T>, val total: Int?, val loadMore: (() -> Unit)?)

/**
 * The messages of the object [objectKey]: the opened row's newest, or - [full], the details page - a
 * window that grows by [DETAILS_PAGE_SIZE] with each "Show more", so hundreds of messages load as
 * they are asked for. While the next window loads, the previous one stays on screen.
 */
@Composable
fun <T> rememberMessages(objectKey: String, packets: ObjectPackets<T>, full: Boolean): ObjectMessages<T> {
    var limit by remember(objectKey, full) { mutableIntStateOf(if (full) DETAILS_PAGE_SIZE else packets.rowLimit) }
    val flow = remember(objectKey, packets, limit) { packets.newest(objectKey, limit) }
    var items by remember(objectKey, packets) { mutableStateOf(emptyList<T>()) }
    LaunchedEffect(flow) { flow.collect { items = it } }
    val total = remember(objectKey, packets) { packets.count(objectKey) }.collectAsState(null).value
    val loadMore = if (full && total != null && items.size >= limit && items.size < total) { { limit += DETAILS_PAGE_SIZE } } else null
    return ObjectMessages(items, total, loadMore)
}

@Composable
private fun Divider() = HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

/**
 * The rows of a list frozen at [frozen]: the same rows in the same order, each showing its current
 * content from [live] where it is still listed and its frozen copy where it no longer is.
 */
internal fun <D, K : Any> frozenRows(frozen: List<D>, live: List<D>, key: (D) -> K): List<D> {
    val liveByKey = live.associateBy(key)
    return frozen.map { liveByKey[key(it)] ?: it }
}

/** How many rows have arrived since the freeze. [frozen] is the whole list as it was then, not
 *  just the rows shown, so one that was merely beyond the row limit does not count as new. */
internal fun <D, K : Any> newRowCount(frozen: List<D>, live: List<D>, key: (D) -> K): Int {
    val frozenKeys = frozen.mapTo(HashSet(), key)
    return live.count { key(it) !in frozenKeys }
}
