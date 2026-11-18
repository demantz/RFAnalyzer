package com.mantz_it.rfanalyzer.digiext.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.SourceClock
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedColumn
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedSortState
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedTable
import com.mantz_it.rfanalyzer.digiext.ui.common.formatAge
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLinePrefixes
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer.digiext.db.markerId
import com.mantz_it.rfanalyzer.digiext.toExportJson
import com.mantz_it.rfanalyzer.digiext.ui.common.ActionStrip
import com.mantz_it.rfanalyzer.digiext.ui.common.DigitalTextStyles
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.common.formatWhen
import com.mantz_it.rfanalyzer.digiext.ui.common.geoMapUrl
import kotlinx.coroutines.flow.Flow
import com.mantz_it.rfanalyzer.digiext.db.PacketQuery
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions

/**
 * <h1>RF Analyzer - Mode Presenter</h1>
 *
 * Module:      ModePresenter.kt
 * Description: How a digital mode presents its objects in the Digital tab's Overview: the row
 *              (identity, payload, columns, sort metrics), the opened row (facts, actions,
 *              messages) and the details page - and the composable that renders one section.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** What one row of a section is called: "Station"/"Stations". */
data class Noun(val one: String, val many: String) {
    fun of(count: Int): String = if (count == 1) one else many
}

/**
 * Describes how one mode renders its Overview table. It turns everything mode-specific into data
 * the shared table consumes, instead of branching on the mode inside the composables.
 *
 * [T] is what one row shows - a [TrackedObject] for every mode but K2K (see [TrackedObjectPresenter]).
 */
interface ModePresenter<T> {
    val noun: Noun

    /** Stable identity for Compose keys. */
    fun objectKey(item: T): String

    /** The left-most cell: the callsign, address or name the user scans for. */
    fun identity(item: T): String

    /** Small icon before the identity (an APRS symbol, an aircraft). */
    val identityIcon: (@Composable (T) -> Unit)? get() = null

    /** Width of the identity cell on a typical phone (scaled on smaller and larger screens). */
    val identityWidth: Dp get() = 96.dp

    fun lastSeenMs(item: T): Long

    /** What the row says, in the room between the identity and the columns, continuing below. */
    fun payload(item: T): String? = null
    /** The callsigns [payload] names, drawn in the identity colour. */
    fun payloadIdentities(item: T): Collection<String> = emptyList()
    val monoPayload: Boolean get() = false
    /** The share of the row the payload keeps before columns are shown: 0 for a mode whose rows
     *  are their columns (R09), less where the columns matter more than the occasional text. */
    val payloadMinFraction: Float get() = 0.42f
    val payloadMaxLines: Int get() = 2
    /** The payload's lines in an opened row: all of it, unless the opened row shows it anyway. */
    val openedPayloadMaxLines: Int get() = Int.MAX_VALUE

    /** Short value columns, shown as far as they fit - see [TrackedColumn]. */
    val columns: List<TrackedColumn<T>> get() = emptyList()

    /** What the table can be sorted by; the first is the default. A stored list (not a getter that
     *  builds one), so the table's ordering is not recomputed on every recomposition. */
    val metrics: List<TrackedMetric<T>>

    /** An object not heard for this long is drawn faded, or null where staleness means nothing
     *  (a pager that has not paged in an hour is quiet, not gone). */
    val staleThresholdMs: Long? get() = null

    /** Rows kept at the very top regardless of order (RDS's station being received). */
    val pinned: ((T) -> Boolean)? get() = null

    /** Whether a row flashes when its object is heard again. Not for a mode whose objects are
     *  heard several times a second (an RDS station, an aircraft): their rows would never rest. */
    val flashOnUpdate: Boolean get() = true

    /** The few figures after the count in the header: "27 with position · 231 frames". [session]
     *  while the mode is being received. */
    fun summary(items: List<T>, session: SessionWindow?): String = ""

    /** What the opened row and the details page tell beyond the row itself. */
    fun facts(item: T, nowMs: Long): List<Field> = emptyList()

    /** What the object is called on its details page: "APRS station". */
    val kindLabel: String get() = noun.one

    /** The object's actions: [PacketMenuAction.prominent] ones become buttons in the opened row
     *  and on the details page, the rest go behind "⋯" and into the long-press menu.
     *  Composable so an action can reach the clipboard; invoked only for an opened row or menu,
     *  so it may subscribe to the object's packets. */
    @Composable
    fun actions(item: T): List<PacketMenuAction> = emptyList()

    /** The object's messages: the opened row shows the latest ([full] false, with [onMore]
     *  leading to the details page), the details page all that are stored. */
    @Composable
    fun Messages(item: T, full: Boolean, onMore: (() -> Unit)?) {}

    /** Whether the opened row offers "Details". */
    val hasDetailsPage: Boolean get() = true

    /** The opened row: facts (with the columns that did not fit), actions, messages. A mode whose
     *  detail is not a list of messages (RDS, K2K) renders something else. */
    @Composable
    fun Opened(item: T, hiddenColumns: List<TrackedColumn<T>>, nowMs: Long, onDetails: (() -> Unit)?) {
        Column {
            InlineFacts(withHiddenColumns(item, hiddenColumns, facts(item, nowMs)))
            val actions = actions(item)
            val strip = if (onDetails != null) listOf(detailsAction(onDetails)) + actions else actions
            ActionStrip(strip, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            Messages(item, full = false, onMore = onDetails)
        }
    }

    /** The details page's groups below the actions. */
    @Composable
    fun DetailsContent(item: T, nowMs: Long) {
        DetailsGroup(kindLabel.substringAfterLast(' ').replaceFirstChar { it.uppercase() }) {
            com.mantz_it.rfanalyzer.digiext.ui.common.Fields(withHiddenColumns(item, columns, facts(item, nowMs)).filter { it.value.isNotBlank() })
        }
        DetailsGroup("Messages") { Messages(item, full = true, onMore = null) }
    }
}

/** The columns that did not fit, as facts in front of [facts] - those a fact already names left
 *  out, and those without a value (a cell's "–"). */
fun <T> withHiddenColumns(item: T, hiddenColumns: List<TrackedColumn<T>>, facts: List<Field>): List<Field> {
    val named = facts.mapTo(HashSet()) { it.label }
    return hiddenColumns.filter { it.factLabel !in named }.map { Field(it.factLabel, it.value(item)) }.filter { it.value != "–" } + facts
}

/** A presenter of tracked objects: identity, key and last-seen time come from the object itself. */
interface TrackedObjectPresenter<S : Any> : ModePresenter<TrackedObject<S>> {
    override fun objectKey(item: TrackedObject<S>): String = item.key
    override fun identity(item: TrackedObject<S>): String = item.displayName
    override fun lastSeenMs(item: TrackedObject<S>): Long = item.lastSeenMs
}

/** "Details ›" - the opened row's way to the details page. */
fun detailsAction(onClick: () -> Unit) =
    PacketMenuAction(label = "Details", icon = Icons.AutoMirrored.Filled.KeyboardArrowRight, prominent = true, onClick = onClick)

/** The metrics every kind of tracked object has. */
fun <S : Any> defaultObjectMetrics(nameLabel: String = "Name"): List<TrackedMetric<TrackedObject<S>>> = listOf(
    lastHeardMetric { it.lastSeenMs },
    packetsMetric(),
    firstHeardMetric(),
    nameMetric(nameLabel) { it.displayName.lowercase() },
)

/** Last heard, newest first - the default order, in which the table follows new arrivals. */
fun <T> lastHeardMetric(lastSeenMs: (T) -> Long): TrackedMetric<T> = TrackedMetric(
    label = "Last heard", header = "Age",
    value = { item, now -> formatAge(now, lastSeenMs(item)) },
    sortKey = lastSeenMs, isRecency = true,
)

fun <S : Any> packetsMetric(label: String = "Packets", header: String = "Packets"): TrackedMetric<TrackedObject<S>> = TrackedMetric(
    label = label, header = header, value = { item, _ -> formatCount(item.packetCount) }, sortKey = { it.packetCount }, width = 52.dp,
)

fun <S : Any> firstHeardMetric(): TrackedMetric<TrackedObject<S>> = TrackedMetric(
    label = "First heard", header = "First", value = { item, now -> formatAge(now, item.firstSeenMs) }, sortKey = { it.firstSeenMs },
)

/** Sorts by name, A to Z; the column keeps showing the age, its header (the sort control) says how
 *  the table is sorted. */
fun <T> nameMetric(label: String = "Name", lastSeenMs: ((T) -> Long)? = null, sortKey: (T) -> Comparable<*>): TrackedMetric<T> = TrackedMetric(
    label = label, header = "A–Z",
    value = { item, now -> lastSeenMs?.let { formatAge(now, it(item)) } ?: "" },
    sortKey = sortKey, ascending = true,
)

fun <S : Any> nameMetric(label: String = "Name", sortKey: (TrackedObject<S>) -> Comparable<*>): TrackedMetric<TrackedObject<S>> =
    nameMetric(label, { it.lastSeenMs }, sortKey)

/** "Heard 12 × · first 06.11.2026 20:13:38" - the bookkeeping every tracked object has. */
fun heardFact(item: TrackedObject<*>, nowMs: Long): Field =
    Field("Heard", "${formatCount(item.packetCount)} × · first ${formatWhen(nowMs, item.firstSeenMs)}")

/**
 * Facts as running text - "Position 34.50733, -117.12967  |  Altitude 906 m  |  Symbol Truck" - so
 * the opened row stays a few lines high instead of a list. Not " · " between facts: values use it
 * themselves ("Heard 12 × · first …").
 */
@Composable
fun InlineFacts(facts: List<Field>, modifier: Modifier = Modifier) {
    val shown = facts.filter { it.value.isNotBlank() }
    if (shown.isEmpty()) return
    val labelColor = MaterialTheme.colorScheme.outline
    val identityColor = MaterialTheme.colorScheme.primary
    val text = buildAnnotatedString {
        shown.forEachIndexed { i, f ->
            if (i > 0) withStyle(SpanStyle(color = labelColor)) { append("  |  ") }
            withStyle(SpanStyle(color = labelColor)) { append(f.label) }
            append(' ')
            // A short value is not broken across lines: its spaces become non-breaking ones.
            val value = f.value.replace(' ', ' ').let { if (it.length > 24) f.value else it }
            if (f.identity) withStyle(SpanStyle(color = identityColor)) { append(value) } else append(value)
        }
    }
    Text(text, style = DigitalTextStyles.value.copy(lineHeight = DigitalTextStyles.label.lineHeight), modifier = modifier.fillMaxWidth())
}

/**
 * "Copy to clipboard" of an object or packet as JSON. `@Composable` since reaching the clipboard
 * needs [LocalContext].
 */
@Composable
fun copyToClipboardAction(clipLabel: String, json: String, label: String = "Copy as JSON"): PacketMenuAction {
    val context = LocalContext.current
    return PacketMenuAction(label = label) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(clipLabel, json))
    }
}

/** "Copy callsign" / "Copy text" / ... - puts plain text on the clipboard. */
@Composable
fun copyTextAction(label: String, text: String, prominent: Boolean = false): PacketMenuAction {
    val context = LocalContext.current
    return PacketMenuAction(label = label, prominent = prominent) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
}

/**
 * What [source] lists for one object - its stored packets, or an RDS station's TMC messages -
 * kept current while the caller is composed.
 */
@Composable
fun <T> rememberDetail(objectKey: String, source: (objectKey: String) -> Flow<List<T>>): List<T> {
    val flow = remember(objectKey, source) { source(objectKey) }
    // Keyed so another object starts from an empty list rather than the previous object's.
    return key(flow) { flow.collectAsState(emptyList()).value }
}

/**
 * One tracked object's stored packets, newest first, as its message list and "Share messages" read
 * them: the newest up to a limit ([rowLimit] in an opened row, more on the details page), how many
 * there are, and the query behind both.
 */
class ObjectPackets<T>(
    val newest: (objectKey: String, limit: Int) -> Flow<List<T>>,
    val count: (objectKey: String) -> Flow<Int>,
    val query: (objectKey: String) -> PacketQuery,
    val rowLimit: Int,
    /** The newest one whose raw bytes match (see [PacketQuery.rawHexGlob]), however old - a kind
     *  of packet too rare to be among the newest. */
    val newestMatching: suspend (objectKey: String, rawHexOffset: Int, rawHexGlob: String) -> T? = { _, _, _ -> null },
)

/** The packets an opened row reads ([ObjectPackets.rowLimit]). */
@Composable
fun <T> rememberDetail(objectKey: String, packets: ObjectPackets<T>): List<T> {
    val flow = remember(objectKey, packets) { packets.newest(objectKey, packets.rowLimit) }
    return key(flow) { flow.collectAsState(emptyList()).value }
}

/** "Share messages as JSON" for an object: all its stored packets, streamed into the file. */
fun <T> ObjectPackets<T>.share(objectKey: String, actions: CardActions): (filenamePrefix: String) -> Unit =
    { prefix -> actions.onShareQuery(query(objectKey), prefix) }

/**
 * A tracked object's actions - the same on its row, its details page and its Map popup: the kind's
 * own [specific] ones (lookups first, as buttons), "Map" for an object with a position (in the Map
 * popup "Follow": keep the map on it), then share and copy behind "⋯".
 */
@Composable
internal fun <S : Any> trackedObjectActions(
    item: TrackedObject<S>,
    family: ObjectFamily<S>,
    onShareJson: (json: String, filenamePrefix: String) -> Unit,
    onTrackOnMap: () -> Unit,
    onOpenUrl: (url: String) -> Unit,
    /** "Share messages as JSON": every stored packet of the object (see [ObjectPackets.share]). */
    shareMessages: ((filenamePrefix: String) -> Unit)? = null,
    specific: List<PacketMenuAction> = emptyList(),
    /** False in the Map popup, which is the map already. */
    withMap: Boolean = true,
): List<PacketMenuAction> {
    val kind = family.kind
    val json = item.toExportJson(family)
    val lat = item.lat
    val lon = item.lon
    return buildList {
        if (withMap && lat != null && lon != null) add(PacketMenuAction(label = "Map", icon = Icons.Default.Map, prominent = true, onClick = onTrackOnMap))
        addAll(specific)
        add(PacketMenuAction(label = "Share ${kind.label} as JSON") { onShareJson(json, "${kind.name.lowercase()}_export") })
        shareMessages?.let { share -> add(PacketMenuAction(label = "Share messages as JSON") { share("${kind.name.lowercase()}_messages_export") }) }
        add(copyToClipboardAction(kind.label, json))
        if (lat != null && lon != null) add(PacketMenuAction(label = "Open in maps app") { onOpenUrl(geoMapUrl(lat, lon)) })
        if (!withMap && lat != null && lon != null) add(PacketMenuAction(label = "Follow", onClick = onTrackOnMap))
    }
}

/** "Map" from an Overview row: selects the object's marker, if it has a position. */
fun TrackedObject<*>.trackOnMap(onTrackOnMapClicked: (markerId: String, lat: Double, lon: Double) -> Unit): () -> Unit = {
    val lat = lat
    val lon = lon
    if (lat != null && lon != null) onTrackOnMapClicked(markerId, lat, lon)
}

/** What a section needs to know about the mode being received, while the shown mode is the live one. */
data class LiveState(
    val frequencyHz: Long,
    /** The current capture session: rows heard outside it are dimmed. */
    val session: SessionWindow?,
)

/**
 * The time the current capture session covers, from [startMs]; a packet was heard this session when
 * its time lies in it. For a mode stamped with the samples' time ([clock] set - see
 * [com.mantz_it.rfanalyzer.digiext.stampedWithSampleTime]) it ends at the session's newest sample:
 * a replayed recording's session is the part of the recording played so far, and what was heard
 * after it in real time lies outside. Otherwise it is open-ended.
 */
data class SessionWindow(val startMs: Long, private val clock: SourceClock? = null) {
    operator fun contains(timestampMs: Long): Boolean = timestampMs >= startMs && (clock == null || timestampMs <= clock.sessionEndMs())
}

/**
 * One section's Overview: the [ticker] (only while live), the table with its header row and, in
 * place of both, an object's details page.
 *
 * Sort order, the opened row and the open details page live here, [rememberSaveable] per section,
 * so they survive rotation and drawer tab switches; the table's scroll position survives the
 * details page.
 */
@Composable
fun <T> TrackedModeSection(
    items: List<T>,
    presenter: ModePresenter<T>,
    nowMs: Long,
    live: LiveState?,
    headerStart: @Composable RowScope.(count: String, summary: String) -> Unit,
    emptyText: String,
    modifier: Modifier = Modifier,
    filterActive: Boolean = false,
    /** How many there are without the filter - shown as "12 of 67" while filtered. */
    unfilteredCount: Int? = null,
    ticker: (@Composable () -> Unit)? = null,
) {
    var sortState by rememberSaveable(
        stateSaver = listSaver(
            save = { listOf(it.metricIndex, it.ascending) },
            restore = { TrackedSortState(it[0] as Int, it[1] as Boolean?) },
        )
    ) { mutableStateOf(TrackedSortState()) }
    var expandedKey: String? by rememberSaveable { mutableStateOf(null) }
    var detailsKey: String? by rememberSaveable { mutableStateOf(null) }
    val listState = rememberLazyListState()

    val detailsItem = detailsKey?.let { k -> items.firstOrNull { presenter.objectKey(it) == k } }
    // An object that left the list (filtered out, deleted) closes its page - for good, not until
    // it comes back. An empty list is a list still loading.
    if (detailsKey != null && detailsItem == null && items.isNotEmpty()) detailsKey = null
    if (detailsItem != null) {
        BackHandler { detailsKey = null }
        DetailsPage(detailsItem, presenter, nowMs, onBack = { detailsKey = null }, modifier = modifier)
        return
    }

    val session = live?.session
    val staleMs = presenter.staleThresholdMs
    val dimmed: ((T) -> Boolean)? = when {
        session != null -> { item -> presenter.lastSeenMs(item) !in session }
        staleMs != null -> { item -> nowMs - presenter.lastSeenMs(item) > staleMs }
        else -> null
    }
    val pinnedKeys = remember(items, nowMs, presenter) {
        presenter.pinned?.let { pinned -> items.filter(pinned).mapTo(HashSet()) { presenter.objectKey(it) } } ?: emptySet()
    }
    val count = items.size
    val countText = buildString {
        append(formatCount(count))
        if (filterActive && unfilteredCount != null && unfilteredCount != count) append(" of ${formatCount(unfilteredCount)}")
        append(' ')
        append(presenter.noun.of(if (filterActive && unfilteredCount != null) unfilteredCount else count))
    }
    val summary = remember(items, session, presenter) { presenter.summary(items, session) }

    Column(modifier = modifier.fillMaxSize()) {
        ticker?.invoke()
        TrackedTable(
            items = items,
            key = { presenter.objectKey(it) },
            identity = { presenter.identity(it) },
            columns = presenter.columns,
            metrics = presenter.metrics,
            sortState = sortState,
            onSortChange = { sortState = it },
            expandedKey = expandedKey,
            onExpandedKeyChange = { expandedKey = it },
            nowMs = nowMs,
            lastSeenMs = { presenter.lastSeenMs(it) },
            headerStart = { headerStart(countText, summary) },
            identityWidth = presenter.identityWidth,
            identityIcon = presenter.identityIcon,
            payload = { presenter.payload(it) },
            payloadIdentities = { presenter.payloadIdentities(it) },
            payloadMinFraction = presenter.payloadMinFraction,
            monoPayload = presenter.monoPayload,
            payloadMaxLines = presenter.payloadMaxLines,
            openedPayloadMaxLines = presenter.openedPayloadMaxLines,
            dimmed = dimmed,
            dividerLabel = if (session != null) "Before this session" else null,
            pinnedKeys = pinnedKeys,
            flashOnUpdate = presenter.flashOnUpdate,
            rowActions = { presenter.actions(it) },
            emptyContent = {
                EmptyText(if (filterActive) "No ${presenter.noun.many.lowercase()} match the filter." else emptyText)
            },
            listState = listState,
            detail = { item, hidden ->
                presenter.Opened(
                    item, hidden, nowMs,
                    onDetails = if (presenter.hasDetailsPage) { { detailsKey = presenter.objectKey(item) } } else null,
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** The count, then the summary in the room that is left - the header row of a single-section mode. */
@Composable
fun RowScope.CountAndSummary(count: String, summary: String) {
    StartThenSummary(
        start = {
            Text(
                count,
                style = DigitalTextStyles.meta.copy(fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface, fontSize = DigitalTextStyles.value.fontSize),
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp),
            )
        },
        summary = summary,
        modifier = Modifier.weight(1f),
    )
}

/**
 * [start] (the count or the section tabs - the tabs scroll when wider than the row) at its full
 * width, then as many of the summary's parts as fit whole, dropped from the end: the first part
 * alone is cut with "…", and left out where that is too little to read, rather than cut to "· 1…".
 */
@Composable
internal fun StartThenSummary(start: @Composable () -> Unit, summary: String, modifier: Modifier) {
    val prefixes = remember(summary) { metaLinePrefixes(summary) }
    Layout(
        content = {
            start()
            prefixes.forEach { Text("·  $it", style = DigitalTextStyles.faint, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0)
        val gap = 8.dp.roundToPx()
        val startPlaceable = measurables[0].measure(loose)
        val rest = constraints.maxWidth - startPlaceable.width - gap
        val candidates = measurables.drop(1)
        val fitting = candidates.lastOrNull { it.maxIntrinsicWidth(constraints.maxHeight) <= rest }
            ?: candidates.firstOrNull()?.takeIf { rest >= 72.dp.roundToPx() }
        val summaryPlaceable = fitting?.measure(loose.copy(maxWidth = rest))
        val height = maxOf(startPlaceable.height, summaryPlaceable?.height ?: 0)
        layout(constraints.maxWidth, height) {
            startPlaceable.place(0, (height - startPlaceable.height) / 2)
            summaryPlaceable?.place(startPlaceable.width + gap, (height - summaryPlaceable.height) / 2)
        }
    }
}

@Composable
internal fun EmptyText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.outline,
        modifier = modifier.padding(horizontal = 12.dp, vertical = 12.dp),
    )
}

/** A details page's titled group: the app's outlined box with the title in its border. */
@Composable
fun DetailsGroup(title: String, content: @Composable () -> Unit) {
    com.mantz_it.rfanalyzer.digiext.ui.common.OutlinedGroup(
        label = title,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 8.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun <T> DetailsPage(item: T, presenter: ModePresenter<T>, nowMs: Long, onBack: () -> Unit, modifier: Modifier) {
    DetailsPageScaffold(
        title = presenter.identity(item),
        kindLabel = presenter.kindLabel,
        icon = presenter.identityIcon?.let { { it(item) } },
        onBack = onBack,
        modifier = modifier,
    ) {
        ActionStrip(presenter.actions(item), maxButtons = 4)
        presenter.DetailsContent(item, nowMs)
    }
}
