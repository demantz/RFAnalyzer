package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * <h1>RF Analyzer - Tracked Table</h1>
 *
 * Module:      TrackedTable.kt
 * Description: The Overview table every mode shares: one row per tracked object (or K2K
 *              transmission) - identity, payload, the columns that fit, and the metric the table
 *              is sorted by - expanding in place into what the mode shows about the object.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * A short value column (R09's Kurs, ADS-B's altitude). Columns have a [priority] (1 = most
 * important) and a fixed [width]; the table shows the ones that fit next to the identity and the
 * payload's minimum width and lists the others in the opened row ([factLabel]).
 *
 * The table never scrolls sideways: it lists *different* objects, and scrolling would push the
 * identity - the only thing saying which row is which - off the screen.
 */
data class TrackedColumn<T>(
    val label: String,
    val width: Dp,
    val value: (T) -> String,
    val priority: Int = 1,
    val alignment: TextAlign = TextAlign.End,
    /** The label when the column did not fit and its value is listed in the opened row instead. */
    val factLabel: String = label,
)

/**
 * What the table can be sorted by. The right-most column always shows the value of the metric it
 * is sorted by, so the order is visible in the rows themselves; the column header is the menu.
 */
data class TrackedMetric<T>(
    /** Name in the menu: "Last heard", "Packets", "Signal (SNR)". */
    val label: String,
    /** Column header: "Age", "Packets", "SNR". */
    val header: String,
    val value: (item: T, nowMs: Long) -> String,
    val sortKey: (T) -> Comparable<*>,
    /** The direction a pick starts in - names A to Z, numbers largest first. */
    val ascending: Boolean = false,
    /** The "last heard" metric: sorted descending, new arrivals come to the top and the table follows them. */
    val isRecency: Boolean = false,
    val width: Dp = 46.dp,
)

/** Which metric (index into the presenter's list) sorts the table, and in which direction. */
data class TrackedSortState(val metricIndex: Int = 0, val ascending: Boolean? = null) {
    fun ascending(metric: TrackedMetric<*>): Boolean = ascending ?: metric.ascending
}

private val ROW_HORIZONTAL_PADDING = 8.dp
private val CELL_GAP = 8.dp

/**
 * The columns that fit into [rowWidth] next to the identity, the metric and the payload's minimum
 * width, most important first, in their declared order.
 */
internal fun <T> visibleColumns(
    columns: List<TrackedColumn<T>>,
    rowWidth: Dp,
    identityWidth: Dp,
    metricWidth: Dp,
    hasPayload: Boolean,
    payloadMinFraction: Float = 0.42f,
): List<TrackedColumn<T>> {
    var free = rowWidth - ROW_HORIZONTAL_PADDING * 2 - identityWidth - metricWidth - CELL_GAP * 2
    if (hasPayload) free -= rowWidth * payloadMinFraction + CELL_GAP
    val chosen = HashSet<TrackedColumn<T>>()
    columns.sortedBy { it.priority }.forEach { column ->
        val needed = column.width + CELL_GAP
        if (needed <= free) {
            chosen += column
            free -= needed
        }
    }
    return columns.filter { it in chosen }
}

/** Width of the identity cell on a screen of [rowWidth], from the width a mode asks for on a
 *  typical phone (≈ 411 dp): a little less on small phones, more on tablets and landscape. */
internal fun scaledIdentityWidth(base: Dp, rowWidth: Dp): Dp = when {
    rowWidth < 380.dp -> base * 0.94f
    rowWidth >= 560.dp -> base * 1.3f
    else -> base
}

/**
 * The table: a header row ([headerStart] - the section tabs or count and summary - then the
 * column labels and the metric menu) and the rows.
 *
 * Each row is identity | payload | columns | metric. A payload that does not fit continues on a
 * second line under the identity and is cut with "…" after [payloadMaxLines] lines; an opened row
 * shows its whole identity and up to [openedPayloadMaxLines] lines of payload. A row whose
 * [lastSeenMs] moves forward flashes. Rows for which [dimmed] holds are drawn faded; while sorted
 * newest first a "Before this session" divider separates them ([dividerLabel]).
 */
@Composable
fun <T, K : Any> TrackedTable(
    items: List<T>,
    key: (T) -> K,
    identity: (T) -> String,
    columns: List<TrackedColumn<T>>,
    metrics: List<TrackedMetric<T>>,
    sortState: TrackedSortState,
    onSortChange: (TrackedSortState) -> Unit,
    expandedKey: K?,
    onExpandedKeyChange: (K?) -> Unit,
    nowMs: Long,
    lastSeenMs: (T) -> Long,
    headerStart: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
    identityWidth: Dp = 96.dp,
    identityIcon: (@Composable (T) -> Unit)? = null,
    payload: ((T) -> String?)? = null,
    /** Callsigns in a row's payload, drawn in the identity colour - see [identityRanges]. */
    payloadIdentities: ((T) -> Collection<String>)? = null,
    payloadMinFraction: Float = 0.42f,
    monoPayload: Boolean = false,
    payloadMaxLines: Int = 2,
    openedPayloadMaxLines: Int = Int.MAX_VALUE,
    dimmed: ((T) -> Boolean)? = null,
    dividerLabel: String? = null,
    pinnedKeys: Set<K> = emptySet(),
    /** Whether a row flashes when its object is heard again - not where that is all the time (RDS, ADS-B). */
    flashOnUpdate: Boolean = true,
    rowActions: (@Composable (T) -> List<PacketMenuAction>)? = null,
    emptyContent: @Composable () -> Unit = {},
    listState: LazyListState = rememberLazyListState(),
    detail: (@Composable (item: T, hiddenColumns: List<TrackedColumn<T>>) -> Unit)? = null,
) {
    val metricIndex = sortState.metricIndex.coerceIn(0, metrics.lastIndex)
    val metric = metrics[metricIndex]
    val ascending = sortState.ascending(metric)
    val newestFirst = metric.isRecency && !ascending
    val ordered = remember(items, metric, ascending, pinnedKeys) {
        val ordered = sortTrackedItems(items, metric, ascending)
        if (pinnedKeys.isEmpty()) ordered else {
            val (pinned, rest) = ordered.partition { key(it) in pinnedKeys }
            pinned + rest
        }
    }
    // Newest first, the rows heard this session come first: those heard after it - in real time,
    // while a recording replays - belong below the divider, not on top.
    val sorted = if (newestFirst && dimmed != null && dividerLabel != null) sessionFirst(ordered, pinnedKeys, key, dimmed) else ordered
    // A new order starts at its top - not at the row that happened to be on top before.
    val order = metricIndex to ascending
    val lastOrder = remember { arrayOf(order) }
    LaunchedEffect(order) {
        if (lastOrder[0] != order) {
            lastOrder[0] = order
            listState.scrollToItem(0)
        }
    }
    val dividerIndex = if (dimmed != null && dividerLabel != null && newestFirst) historyDividerIndex(sorted, dimmed) else -1

    // Follow new arrivals while the user is at the top: see shouldFollowTop. Keyed on row 0's
    // identity, since most updates move an existing object back to the top.
    val coroutineScope = rememberCoroutineScope()
    var follow by rememberSaveable { mutableStateOf(true) }
    val atTop by remember(listState) {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect { follow = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    val topKey = sorted.firstOrNull()?.let(key)
    val followTracker = remember { FollowTracker() }
    // An open row that is no longer listed (filtered out, deleted) does not hold the table.
    val rowOpen = expandedKey != null && sorted.any { key(it) == expandedKey }
    SideEffect {
        val active = shouldFollowTop(follow, newestFirst, rowExpanded = rowOpen, scrollInProgress = listState.isScrollInProgress)
        if (active && (topKey != followTracker.topKey || !followTracker.wasActive)) listState.requestScrollToItem(0)
        followTracker.topKey = topKey
        followTracker.wasActive = active
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val rowWidth = maxWidth
        val idWidth = scaledIdentityWidth(identityWidth, rowWidth)
        val shown = remember(columns, rowWidth, idWidth, metric.width, payloadMinFraction) {
            visibleColumns(columns, rowWidth, idWidth, metric.width, hasPayload = payloadMinFraction > 0f, payloadMinFraction = payloadMinFraction)
        }
        val hidden = remember(columns, shown) { columns - shown.toSet() }
        // The room line 1 leaves for the payload - known here, so a row lays out its two lines in
        // one pass instead of growing a frame after it appears. In px, each part rounded as the
        // row's layout rounds it: rounding the sum once can come out 1-2 px wider than the slot.
        val payloadWidthPx = with(LocalDensity.current) {
            val gap = CELL_GAP.roundToPx()
            constraints.maxWidth - ROW_HORIZONTAL_PADDING.roundToPx() * 2 - idWidth.roundToPx() - gap -
                shown.sumOf { gap + it.width.roundToPx() } - gap - metric.width.roundToPx()
        }

        Column(modifier = Modifier.fillMaxSize()) {
            TableHeader(
                headerStart = headerStart,
                columns = shown,
                metrics = metrics,
                metricIndex = metricIndex,
                ascending = ascending,
                onSortChange = onSortChange,
            )
            if (sorted.isEmpty()) {
                emptyContent()
                return@Column
            }
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth()) {
                    sorted.forEachIndexed { index, item ->
                        if (index == dividerIndex) {
                            item(key = "__history_divider__") { HistoryDivider(dividerLabel!!) }
                        }
                        item(key = key(item)) {
                            val itemKey = key(item)
                            TrackedTableRow(
                                identity = identity(item),
                                identityWidth = idWidth,
                                identityIcon = identityIcon?.let { { it(item) } },
                                payload = payload?.invoke(item),
                                payloadIdentities = payloadIdentities?.invoke(item).orEmpty(),
                                monoPayload = monoPayload,
                                payloadMaxLines = if (expandedKey == itemKey) openedPayloadMaxLines else payloadMaxLines,
                                payloadWidthPx = payloadWidthPx,
                                cells = shown.map { it.value(item) },
                                columns = shown,
                                metricValue = metric.value(item, nowMs),
                                metricWidth = metric.width,
                                lastSeenMs = lastSeenMs(item),
                                dimmed = dimmed?.invoke(item) == true,
                                pinned = itemKey in pinnedKeys,
                                flashOnUpdate = flashOnUpdate,
                                expanded = expandedKey == itemKey,
                                onExpand = { onExpandedKeyChange(if (expandedKey == itemKey) null else itemKey) },
                                actions = rowActions?.let { { it(item) } },
                                detail = detail?.let { { it(item, hidden) } },
                            )
                        }
                    }
                }
                if (!atTop) {
                    SmallFloatingActionButton(
                        onClick = {
                            follow = true
                            coroutineScope.launch { listState.animateScrollToItem(0) }
                        },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    ) {
                        Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Scroll to top")
                    }
                }
            }
        }
    }
}

/** What the follow logic saw on the previous composition - plain fields, read and written only
 *  from a SideEffect, so changing them never triggers recomposition. */
private class FollowTracker {
    var topKey: Any? = null
    var wasActive: Boolean = false
}

/**
 * Whether the table should keep itself scrolled to row 0 right now. Not while a row is expanded:
 * the user is reading it, and pulling the list up would move it away under their finger - the
 * scroll-to-top button appears instead as rows pile up above.
 */
internal fun shouldFollowTop(follow: Boolean, newestFirst: Boolean, rowExpanded: Boolean, scrollInProgress: Boolean): Boolean =
    follow && newestFirst && !rowExpanded && !scrollInProgress

/** [ordered] with the rows that are not [dimmed] before those that are, each in its order; pinned
 *  rows stay on top. */
internal fun <T, K> sessionFirst(ordered: List<T>, pinnedKeys: Set<K>, key: (T) -> K, dimmed: (T) -> Boolean): List<T> {
    val (pinned, rest) = ordered.partition { key(it) in pinnedKeys }
    val (current, before) = rest.partition { !dimmed(it) }
    return pinned + current + before
}

/** Index of the first dimmed row after at least one that is not, or -1: the "Before this session"
 *  divider only separates something from something. */
internal fun <T> historyDividerIndex(sorted: List<T>, dimmed: (T) -> Boolean): Int {
    val first = sorted.indexOfFirst(dimmed)
    return if (first > 0) first else -1
}

/** [items] in the order of [metric]. Stable, so equal values keep the order they came in. */
internal fun <T> sortTrackedItems(items: List<T>, metric: TrackedMetric<T>, ascending: Boolean): List<T> {
    @Suppress("UNCHECKED_CAST")
    val comparator = compareBy<T> { metric.sortKey(it) as Comparable<Any> }
    return items.sortedWith(if (ascending) comparator else comparator.reversed())
}

@Composable
private fun <T> TableHeader(
    headerStart: @Composable RowScope.() -> Unit,
    columns: List<TrackedColumn<T>>,
    metrics: List<TrackedMetric<T>>,
    metricIndex: Int,
    ascending: Boolean,
    onSortChange: (TrackedSortState) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val metric = metrics[metricIndex]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 32.dp)
            .background(DigitalSurfaces.band)
            .padding(start = 4.dp, end = ROW_HORIZONTAL_PADDING),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f), content = headerStart)
        columns.forEach { column ->
            Text(
                text = column.label,
                style = DigitalTextStyles.faint,
                textAlign = column.alignment,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(start = CELL_GAP).width(column.width),
            )
        }
        // The sort control: set off from the section tabs to its left by a separator and drawn as
        // a control (sort icon, no tab colour), not as one more tab. Exactly as wide as the metric
        // cells with their gap, so the column labels to its left stay over their cells.
        val separator = MaterialTheme.colorScheme.outlineVariant
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
            modifier = Modifier
                .width(metric.width + CELL_GAP)
                .heightIn(min = 32.dp)
                .drawBehind {
                    val inset = size.height * 0.22f
                    drawLine(separator, Offset(0f, inset), Offset(0f, size.height - inset), strokeWidth = 1.dp.toPx())
                }
                .clickable { menuOpen = true },
        ) {
            Icon(
                Icons.AutoMirrored.Filled.Sort,
                contentDescription = "Sort by",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 2.dp).size(14.dp),
            )
            Text(
                text = metric.header,
                style = DigitalTextStyles.meta.copy(fontWeight = FontWeight.Medium),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f, fill = false),
            )
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                Text(
                    "Sort by",
                    style = DigitalTextStyles.faint,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
                metrics.forEachIndexed { index, m ->
                    val selected = index == metricIndex
                    DropdownMenuItem(
                        text = { Text(m.label + if (selected) (if (ascending) "  ↑" else "  ↓") else "") },
                        trailingIcon = if (selected) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            menuOpen = false
                            // Picking the current metric again reverses the order.
                            onSortChange(if (selected) TrackedSortState(index, !ascending) else TrackedSortState(index))
                        },
                    )
                }
            }
        }
    }
    HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun HistoryDivider(label: String) {
    Column {
        Text(
            text = label,
            style = DigitalTextStyles.faint,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
        )
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

private const val FLASH_MS = 1200
private const val NEW_ARRIVAL_MS = 3000L

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TrackedTableRow(
    identity: String,
    identityWidth: Dp,
    identityIcon: (@Composable () -> Unit)?,
    payload: String?,
    payloadIdentities: Collection<String>,
    monoPayload: Boolean,
    payloadMaxLines: Int,
    payloadWidthPx: Int,
    cells: List<String>,
    columns: List<TrackedColumn<*>>,
    metricValue: String,
    metricWidth: Dp,
    lastSeenMs: Long,
    dimmed: Boolean,
    pinned: Boolean,
    flashOnUpdate: Boolean,
    expanded: Boolean,
    onExpand: () -> Unit,
    actions: (@Composable () -> List<PacketMenuAction>)?,
    detail: (@Composable () -> Unit)?,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    // Resolved only while the menu is open: a mode's actions may subscribe to the object's packets
    // (share messages), which must not happen for every visible row.
    val resolvedActions = if (actions != null && menuOpen) actions() else emptyList()

    // A new packet for this object flashes the row: a row that was already there and moved, or one
    // that just arrived. Not the rows of a table that is merely opened.
    val flash = remember { Animatable(0f) }
    var previousLastSeen by remember { mutableStateOf<Long?>(null) }
    val clock = LocalDigitalClock.current
    LaunchedEffect(lastSeenMs) {
        val previous = previousLastSeen
        previousLastSeen = lastSeenMs
        val isNew = if (previous == null) clock() - lastSeenMs < NEW_ARRIVAL_MS else lastSeenMs > previous
        if (isNew && flashOnUpdate) {
            flash.snapTo(1f)
            flash.animateTo(0f, tween(FLASH_MS))
        }
    }
    val flashColor = MaterialTheme.colorScheme.primary
    val pinnedColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
    val expandedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)

    // The opened area is part of the row: a tap on it closes the row, a long press opens the menu.
    // Its buttons and messages take their own taps.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (pinned) drawRect(pinnedColor)
                if (expanded) drawRect(expandedColor)
                if (flash.value > 0f) drawRect(flashColor.copy(alpha = 0.22f * flash.value))
            }
            .combinedClickable(
                onClick = onExpand,
                onLongClickLabel = if (actions == null) null else "Row actions",
                onLongClick = if (actions == null) null else {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menuOpen = true
                    }
                },
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .alpha(if (dimmed && !expanded) 0.5f else 1f)
                .padding(horizontal = ROW_HORIZONTAL_PADDING, vertical = 5.dp),
        ) {
            OverflowRow(
                text = payload,
                identities = payloadIdentities,
                mono = monoPayload,
                maxLines = payloadMaxLines,
                slotWidthPx = payloadWidthPx,
                leading = {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alignByBaseline().width(identityWidth)) {
                        identityIcon?.let {
                            it()
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = identity,
                            style = DigitalTextStyles.identity,
                            maxLines = if (expanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                trailing = {
                    cells.forEachIndexed { index, cell ->
                        Text(
                            text = cell,
                            style = DigitalTextStyles.cell,
                            textAlign = columns[index].alignment,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.alignByBaseline().padding(start = CELL_GAP).width(columns[index].width),
                        )
                    }
                    Text(
                        text = metricValue,
                        style = DigitalTextStyles.faint,
                        textAlign = TextAlign.End,
                        maxLines = 1,
                        modifier = Modifier.alignByBaseline().padding(start = CELL_GAP).width(metricWidth),
                    )
                },
            )
            Box { PacketActionDropdown(expanded = menuOpen, onDismissRequest = { menuOpen = false }, actions = resolvedActions) }
        }
        AnimatedVisibility(visible = expanded) {
            Column(modifier = Modifier.padding(start = ROW_HORIZONTAL_PADDING, end = ROW_HORIZONTAL_PADDING, bottom = 6.dp)) {
                detail?.invoke()
            }
        }
        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/**
 * [leading] (the identity), [text] in the room left over ([slotWidthPx]), then [trailing] (columns
 * and metric) on line 1; text that does not fit continues on full-width lines below, cut with "…"
 * after [maxLines] lines in all. Where line 1 ends is measured here, with word wrap, so both lines
 * are there from the row's first frame. Line 1's parts share one baseline: an opened row's identity
 * may wrap.
 */
@Composable
private fun OverflowRow(
    text: String?,
    identities: Collection<String>,
    mono: Boolean,
    maxLines: Int,
    slotWidthPx: Int,
    leading: @Composable RowScope.() -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    val style = if (mono) DigitalTextStyles.mono else DigitalTextStyles.rowPayload
    val measurer = rememberTextMeasurer(cacheSize = 0)
    val density = LocalDensity.current
    val headEnd = remember(text, slotWidthPx, style, density) {
        if (text == null || maxLines <= 1) -1
        else {
            val layout = measurer.measure(text, style, maxLines = 1, constraints = Constraints(maxWidth = slotWidthPx.coerceAtLeast(1)), density = density)
            if (layout.hasVisualOverflow) layout.getLineEnd(0) else -1
        }
    }
    val styled = text?.let { identityStyled(it, identities) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 22.dp)) {
            leading()
            Box(modifier = Modifier.alignByBaseline().weight(1f).padding(start = CELL_GAP)) {
                if (text != null && styled != null) {
                    Text(
                        text = if (headEnd in 1 until text.length) styled.subSequence(0, headEnd) else styled,
                        style = style,
                        maxLines = 1,
                        overflow = if (headEnd in 1 until text.length) TextOverflow.Clip else TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
        }
        if (text != null && styled != null && headEnd in 1 until text.length) {
            val rest = headEnd + (text.length - headEnd - text.substring(headEnd).trimStart().length)
            Text(
                text = styled.subSequence(rest, text.length),
                style = style,
                maxLines = maxLines - 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
