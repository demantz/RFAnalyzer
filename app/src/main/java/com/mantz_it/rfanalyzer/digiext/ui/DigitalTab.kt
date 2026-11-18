package com.mantz_it.rfanalyzer.digiext.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import com.mantz_it.rfanalyzer.digiext.ax25.Ax25Message
import com.mantz_it.rfanalyzer.digiext.k2k.K2kPhy
import com.mantz_it.rfanalyzer.digiext.k2k.K2kText
import com.mantz_it.rfanalyzer.digiext.lora.LoraPayloadMessage
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagMessage
import com.mantz_it.rfanalyzer.digiext.r09.R09Paket
import com.mantz_it.rfanalyzer_ipc.digiext.R09Phy
import com.mantz_it.rfanalyzer.digiext.rds.RdsGroupContent
import com.mantz_it.rfanalyzer_ipc.digiext.AdsbPayload
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Phy
import com.mantz_it.rfanalyzer.digiext.ft8.Ft8Message
import com.mantz_it.rfanalyzer_ipc.digiext.Ft8Phy
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPhy
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPhy
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import com.mantz_it.rfanalyzer.database.MapCameraPosition
import com.mantz_it.rfanalyzer.location.MapHome
import com.mantz_it.rfanalyzer.digiext.adsb.AdsbFrameCard
import com.mantz_it.rfanalyzer.digiext.ax25.Ax25FrameCard
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer.digiext.db.asPacketOf
import com.mantz_it.rfanalyzer.digiext.ModeSheetStations
import com.mantz_it.rfanalyzer.digiext.DigitalAction
import com.mantz_it.rfanalyzer.digiext.DigitalNotice
import com.mantz_it.rfanalyzer.ui.composable.DigitalStatusNotice
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer.digiext.ft8.Ft8MessageCard
import com.mantz_it.rfanalyzer.digiext.k2k.K2kMessageCard
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrameCard
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagMessageCard
import com.mantz_it.rfanalyzer.digiext.r09.R09MessageCard
import com.mantz_it.rfanalyzer.digiext.rds.RdsGroupCard
import com.mantz_it.rfanalyzer.digiext.ui.common.PagedPacketList
import com.mantz_it.rfanalyzer.ui.MapFocusRequest
import com.mantz_it.rfanalyzer.ui.PacketActionCounts
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import com.mantz_it.rfanalyzer.digiext.PositionPoint
import com.mantz_it.rfanalyzer.digiext.db.DigitalFilter
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.StoredModeCount
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.DigitalSurfaces
import com.mantz_it.rfanalyzer.digiext.ui.common.DigitalTextStyles
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.common.LocalDigitalClock
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerExpansion
import com.mantz_it.rfanalyzer.digiext.SourceClock
import com.mantz_it.rfanalyzer.digiext.stampedWithSampleTime
import com.mantz_it.rfanalyzer.digiext.db.PacketQuery

/**
 * <h1>RF Analyzer - Digital Tab</h1>
 *
 * Module:      DigitalTab.kt
 * Description: The Digital tab - the single place all decoded-traffic views live, as three
 *              subtabs over the same database: Overview (one row per tracked object), Raw (the
 *              packet-by-packet history) and Map. All three share one single-line
 *              DigitalTabHeader. Everything mode-specific about Overview lives in the modes' own
 *              OverviewSections, not here.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025-2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

data class DigitalTabActions(
    val onFilterChanged: (DigitalFilter) -> Unit,
    val onDeleteClicked: (DigitalFilter) -> Unit,
    val onShareClicked: (DigitalFilter) -> Unit,
    val onDeleteConfirmed: (PacketDeleteRequest) -> Unit,
    val onDeleteCancelled: () -> Unit,
    val onShareConfirmed: (PacketShareRequest) -> Unit,
    val onShareCancelled: () -> Unit,
    // Per-card/per-row actions.
    val onShareJsonExportClicked: (json: String, filenamePrefix: String) -> Unit,
    /** Shares every packet a query matches as a JSON file (an object's messages). */
    val onShareQueryClicked: (query: PacketQuery, filenamePrefix: String) -> Unit,
    val onTrackOnMapClicked: (markerId: String, lat: Double, lon: Double) -> Unit,
    val onOpenExternalUrlClicked: (url: String) -> Unit,
    /** RDS's row-level 'tune to frequency' action. */
    val onTuneToFrequency: (frequencyHz: Long) -> Unit,
    /** Map subtab. */
    val onShowTrailsChanged: (Boolean) -> Unit,
    val onShowAllModesOnMapChanged: (Boolean) -> Unit,
    /** Stored packets per mode, for the mode sheet - see ModeSheet.kt. */
    val packetCountsByMode: suspend () -> Map<DigitalMode, StoredModeCount>,
    /** The selected section (by label) of a mode with several - LoRa's sub-protocols. */
    val overviewSection: (DigitalMode) -> StateFlow<String>,
    val onOverviewSectionSelected: (DigitalMode, String) -> Unit,
    /** The mode sheet's stations and Start - see ModeSheet.kt. */
    val modeSheetStations: ModeSheetStations,
    val onQuickStop: () -> Unit,
    /** The button of a DigitalStatusNotice (install, update, retry). */
    val onDigitalAction: (DigitalAction) -> Unit,
    /** The digital trial's pill in the header. */
    val onDigitalTrialClicked: () -> Unit,
    /** "?" on the overload notice. */
    val onOverloadHelpClicked: () -> Unit,
    /** Help in ⋮ and in the mode sheet: the manual at a page ("ft8.html#in-the-digital-tab"). */
    val onOpenManual: (subUrl: String) -> Unit,
) {
    /** What a packet card's actions need. */
    val cardActions: CardActions get() = CardActions(onShareJsonExportClicked, onTrackOnMapClicked, onOpenExternalUrlClicked, onShareQueryClicked)
}

/**
 * The three ways to look at the same capture: [Overview] (one row per capcode, aircraft,
 * callsign, expanding into that object's messages), [Raw] (the packet-by-packet history) and
 * [Map]. [icon] backs the header's view switch.
 */
enum class PacketsView(val label: String, val icon: ImageVector) {
    Overview("Overview", Icons.Default.Dashboard),
    Raw("Raw", Icons.AutoMirrored.Filled.ListAlt),
    Map("Map", Icons.Default.Map),
}

@Composable
internal fun DigitalTabComposable(
    filter: DigitalFilter,
    pagingItems: LazyPagingItems<StoredPacket<*, *>>,
    /** The mode being demodulated right now - null while the analyzer is stopped. */
    liveMode: DigitalMode?,
    /** The receiver's channel frequency - the ticker's label and the mode sheet. */
    channelFrequency: Long,
    /** Why digital modes aren't received right now, if they aren't - see DigitalAvailability.kt. */
    digitalNotice: DigitalNotice?,
    /** The digital trial's decoding time left while it counts, else null. */
    digitalTrialSecondsLeft: Int?,
    /** When the current capture session started. */
    sessionStartMs: Long?,
    /** The time of the samples being received - see [SourceClock]. */
    sourceClock: SourceClock,
    pendingDelete: PacketActionCounts?,
    pendingShare: PacketActionCounts?,
    digitalTabActions: DigitalTabActions,
    /** The Overview subtab's tables, per mode - see [digitalOverviewSections]. */
    overviewSections: Map<DigitalMode, List<OverviewSection>>,
    /** Map subtab. */
    markerState: MapMarkerState,
    /** Where the Map starts without objects, and the user's locator square - see [MapHome]. */
    mapHome: MapHome?,
    showMapTrails: Boolean = true,
    showAllModesOnMap: Boolean = false,
    pendingFocus: MapFocusRequest? = null,
    onFocusHandled: () -> Unit = {},
    mapTrails: Map<String, List<PositionPoint>> = emptyMap(),
    mapLayers: Map<ObjectKind, MapLayer<*>> = emptyMap(),
    /** Where the Map's camera was per shown mode, for as long as the app runs. */
    mapCameraMemory: MutableMap<String, MapCameraPosition> = remember { mutableMapOf() },
    modifier: Modifier = Modifier,
) {
    var selectedView by rememberSaveable { mutableStateOf(PacketsView.Overview) }
    var fitMapRequest by remember { mutableIntStateOf(0) }

    // "Map" on a row or card from another place lands here: switch to the Map subtab.
    LaunchedEffect(pendingFocus) {
        if (pendingFocus != null) selectedView = PacketsView.Map
    }

    val overloadActive = rememberOverloadActive()
    var overloadMinimized by rememberSaveable { mutableStateOf(false) }
    // A mode stamped with the samples' time counts in it while received: a replayed recording's
    // packets carry the recording's time. The others carry the wall clock even then.
    val sampleTime = filter.mode?.stampedWithSampleTime == true
    val sourceSessionStartMs by sourceClock.sessionStartMs.collectAsState()
    val session = sourceSessionStartMs?.takeIf { sampleTime }?.let { SessionWindow(it, sourceClock) } ?: sessionStartMs?.let { SessionWindow(it) }
    val live = if (filter.mode != null && filter.mode == liveMode) LiveState(channelFrequency, session) else null
    val countsInSampleTime = live != null && sampleTime
    val clock: () -> Long = remember(countsInSampleTime, sourceClock) { if (countsInSampleTime) sourceClock::nowMs else System::currentTimeMillis }
    // Each subtab keeps its saveable state (sort order, opened row, scroll) while another is shown.
    val subtabStates = rememberSaveableStateHolder()

    Column(modifier = modifier.fillMaxSize()) {
        DigitalTabHeader(
            selectedView = selectedView,
            onViewChanged = { selectedView = it },
            liveMode = liveMode,
            channelFrequency = channelFrequency,
            filter = filter,
            onFilterChanged = digitalTabActions.onFilterChanged,
            modeSheetStations = digitalTabActions.modeSheetStations,
            onQuickStop = digitalTabActions.onQuickStop,
            onDeleteClicked = { digitalTabActions.onDeleteClicked(filter) },
            onShareClicked = { digitalTabActions.onShareClicked(filter) },
            showTrails = showMapTrails,
            onShowTrailsChanged = digitalTabActions.onShowTrailsChanged,
            showAllModesOnMap = showAllModesOnMap,
            onShowAllModesOnMapChanged = digitalTabActions.onShowAllModesOnMapChanged,
            onFitMap = { fitMapRequest++ },
            packetCountsByMode = digitalTabActions.packetCountsByMode,
            showOverloadPill = overloadActive && overloadMinimized,
            onOverloadPillClicked = { overloadMinimized = false },
            onOpenManual = digitalTabActions.onOpenManual,
            digitalNotice = digitalNotice,
            onDigitalAction = digitalTabActions.onDigitalAction,
            trialSecondsLeft = digitalTrialSecondsLeft,
            onTrialClicked = digitalTabActions.onDigitalTrialClicked,
        )
        digitalNotice?.let {
            DigitalStatusNotice(it, digitalTabActions.onDigitalAction, Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp))
        }
        Box(modifier = Modifier.weight(1f)) {
            CompositionLocalProvider(LocalDigitalClock provides clock) {
                subtabStates.SaveableStateProvider(selectedView.name) {
                    when (selectedView) {
                        PacketsView.Overview -> OverviewView(
                            sections = overviewSections[filter.mode],
                            filter = filter,
                            actions = digitalTabActions,
                            live = live,
                            modifier = Modifier.fillMaxSize(),
                        )
                        PacketsView.Raw -> RawView(pagingItems, filter, digitalTabActions, Modifier.fillMaxSize())
                        PacketsView.Map -> DigitalMapView(
                            markerState = markerState,
                            home = mapHome,
                            mode = filter.mode,
                            live = live != null,
                            session = live?.session,
                            showTrails = showMapTrails,
                            onShowTrailsChanged = digitalTabActions.onShowTrailsChanged,
                            showAllModes = showAllModesOnMap,
                            onShowAllModesChanged = digitalTabActions.onShowAllModesOnMapChanged,
                            fitRequest = fitMapRequest,
                            pendingFocus = pendingFocus,
                            onFocusHandled = onFocusHandled,
                            trails = mapTrails,
                            onShareJsonExportClicked = digitalTabActions.onShareJsonExportClicked,
                            onOpenUrlClicked = digitalTabActions.onOpenExternalUrlClicked,
                            layers = mapLayers,
                            cameraMemory = mapCameraMemory,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            if (overloadActive && !overloadMinimized) {
                OverloadNotice(
                    onHelpClicked = digitalTabActions.onOverloadHelpClicked,
                    onMinimize = { overloadMinimized = true },
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }

    pendingDelete?.let { counts ->
        PacketDeleteDialog(
            currentMode = counts.currentMode,
            modeCount = counts.modeCount,
            totalCount = counts.totalCount,
            onConfirm = digitalTabActions.onDeleteConfirmed,
            onDismiss = digitalTabActions.onDeleteCancelled,
        )
    }
    pendingShare?.let { counts ->
        PacketShareDialog(
            filter = counts.filter,
            matchCount = counts.matchCount,
            currentMode = counts.currentMode,
            modeCount = counts.modeCount,
            totalCount = counts.totalCount,
            onConfirm = digitalTabActions.onShareConfirmed,
            onDismiss = digitalTabActions.onShareCancelled,
        )
    }
}

/** How many lines a ticker gets in an Overview of [heightDp]: one at the drawer's default height,
 *  more as it is pulled up. */
internal fun tickerLinesFor(heightDp: Float): Int = when {
    heightDp < 260f -> 1
    heightDp < 420f -> 2
    else -> 3
}

/** How many lines an open ticker gets: about half the Overview, at least a few more than closed. */
internal fun openTickerLinesFor(heightDp: Float, closedLines: Int): Int =
    (heightDp * 0.5f / 20f).toInt().coerceIn(closedLines + 3, 16)

/**
 * The per-object collection: one row per tracked object, expanding into that object's stored
 * messages and then into a single message's card. [sections] are the shown mode's tables (null
 * while no mode is shown); a mode with several gets tabs at the start of the table's header row.
 */
@Composable
private fun OverviewView(
    sections: List<OverviewSection>?,
    filter: DigitalFilter,
    actions: DigitalTabActions,
    live: LiveState?,
    modifier: Modifier = Modifier,
) {
    // Drives the age columns during a lull, so ages keep counting up between packets.
    val clock = LocalDigitalClock.current
    var now by remember(clock) { mutableLongStateOf(clock()) }
    LaunchedEffect(clock) {
        while (true) {
            delay(5_000)
            now = clock()
        }
    }
    val mode = filter.mode
    if (sections.isNullOrEmpty() || mode == null) {
        EmptyText(
            "Digital modes turn signals into text, positions and messages: aircraft (ADS-B), pagers (POCSAG), " +
                "packet radio and APRS, mesh chats (LoRa), FT8 contacts, keyboard chats (PSK31), teleprinter weather reports (RTTY), " +
                "tram telegrams (R09) and the station names of FM broadcasts (RDS).\n\n" +
                "Tap the mode button above to pick a mode: look at what was received, or Start receiving it.",
            modifier.fillMaxSize(),
        )
        return
    }
    // Opened by a tap on the ticker; one state per mode, so both AX.25 sections share it.
    var tickerOpen by rememberSaveable(mode) { mutableStateOf(false) }
    BoxWithConstraints(modifier = modifier) {
        val tickerLines = tickerLinesFor(maxHeight.value)
        val expansion = TickerExpansion(tickerOpen, openTickerLinesFor(maxHeight.value, tickerLines)) { tickerOpen = !tickerOpen }
        if (sections.size == 1) {
            val context = OverviewContext(mode, filter, now, actions, live, tickerLines, expansion, sectionTabs = null)
            key(sections.single()) { sections.single().Content(context, Modifier.fillMaxSize()) }
        } else {
            SectionSwitcher(mode, sections, filter, now, actions, live, tickerLines, expansion)
        }
    }
}

/** Several sections of one mode, switched with tabs in the table's header row. The selection is a
 *  setting per mode (quick start sets it too); a tab that heard something since it was last
 *  selected shows a dot. */
@Composable
private fun SectionSwitcher(
    mode: DigitalMode,
    sections: List<OverviewSection>,
    filter: DigitalFilter,
    nowMs: Long,
    actions: DigitalTabActions,
    live: LiveState?,
    tickerLines: Int,
    tickerExpansion: TickerExpansion,
) {
    val selectedLabel by remember(mode) { actions.overviewSection(mode) }.collectAsState()
    val selected = sections.indexOfFirst { it.label == selectedLabel }.coerceAtLeast(0)
    val counts = sections.map { it.count.collectAsState(0).value }
    // A tab counts what is listed: while the filter narrows a section, it says of how many ("12/67").
    // Subscribed only while filtering, like the "12 of 67" of a mode without tabs.
    val filterActive = filter.isFilterActive()
    val unfiltered = sections.map { if (filterActive) it.unfilteredCount.collectAsState(null).value else null }
    val countTexts = counts.mapIndexed { i, count ->
        val all = unfiltered[i]
        if (all != null && all != count) "${formatCount(count)}/${formatCount(all)}" else formatCount(count)
    }
    // Activity counts packets since the app started; a section is marked while its count is ahead of
    // what it was when the section was last selected. Saveable: this subtree is disposed whenever
    // another subtab or drawer tab is shown.
    val activity = sections.map { it.activity.collectAsState(0L).value }
    var seen by rememberSaveable(mode) { mutableStateOf(List(sections.size) { 0L }) }
    val selectedActivity = activity[selected]
    LaunchedEffect(selected, selectedActivity) {
        if (seen.getOrNull(selected) != selectedActivity) seen = List(sections.size) { if (it == selected) selectedActivity else seen.getOrElse(it) { 0L } }
    }
    val tabs: @Composable () -> Unit = {
        SectionTabs(
            labels = sections.map { it.label },
            counts = countTexts,
            newActivity = sections.indices.map { it != selected && activity[it] > seen.getOrElse(it) { 0L } },
            selected = selected,
            onSelect = { actions.onOverviewSectionSelected(mode, sections[it].label) },
        )
    }
    val context = OverviewContext(mode, filter, nowMs, actions, live, tickerLines, tickerExpansion, sectionTabs = tabs)
    // Keyed: sections share their composables, and without the key a switch would hand the new
    // section the state the old one collected.
    key(sections[selected]) { sections[selected].Content(context, Modifier.fillMaxSize()) }
}

/** The section tabs: label and count, the selected one underlined. Scroll sideways when they do
 *  not fit (LoRa's five on a phone): an edge that hides more tabs fades out, and the selected tab
 *  is scrolled into view. */
@Composable
private fun SectionTabs(labels: List<String>, counts: List<String>, newActivity: List<Boolean>, selected: Int, onSelect: (Int) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val scroll = rememberScrollState()
    val tabStarts = remember(labels) { IntArray(labels.size) }
    val revealPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    LaunchedEffect(selected, labels) {
        scroll.animateScrollTo((tabStarts.getOrElse(selected) { 0 } - revealPx).coerceAtLeast(0))
    }
    val band = DigitalSurfaces.band
    Row(
        modifier = Modifier
            .drawWithContent {
                drawContent()
                val fade = 28.dp.toPx()
                if (scroll.canScrollForward) {
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent, band), startX = size.width - fade, endX = size.width), topLeft = Offset(size.width - fade, 0f))
                }
                if (scroll.canScrollBackward) {
                    drawRect(Brush.horizontalGradient(listOf(band, Color.Transparent), startX = 0f, endX = fade), size = Size(fade, size.height))
                }
            }
            .horizontalScroll(scroll),
    ) {
        labels.forEachIndexed { index, label ->
            val isSelected = index == selected
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .onPlaced { tabStarts[index] = it.positionInParent().x.toInt() }
                    .heightIn(min = 34.dp)
                    .clickable { onSelect(index) }
                    .drawBehind {
                        if (isSelected) {
                            val y = size.height - 1.dp.toPx()
                            drawLine(primary, Offset(0f, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
                        }
                    }
                    .padding(horizontal = 8.dp),
            ) {
                Text(
                    label,
                    style = DigitalTextStyles.value.copy(
                        fontWeight = FontWeight.Medium,
                        color = if (isSelected) primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                    maxLines = 1,
                )
                Text(
                    " " + counts[index],
                    style = DigitalTextStyles.faint,
                    maxLines = 1,
                )
                if (newActivity[index]) {
                    Box(
                        Modifier
                            .padding(start = 3.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(primary),
                    )
                }
            }
        }
    }
}

/**
 * The Raw subtab: every stored packet as its card, newest first. While the user reads further
 * down, new packets do not move the list; a button counts them and returns to the top.
 */
@Composable
private fun RawView(pagingItems: LazyPagingItems<StoredPacket<*, *>>, filter: DigitalFilter, actions: DigitalTabActions, modifier: Modifier) {
    var expandedPacketId: Long? by rememberSaveable { mutableStateOf(null) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Whether to follow new arrivals to the top. Only updated once a scroll settles: prepending a
    // row shifts firstVisibleItemIndex from 0 to 1 to keep the content anchored, which read
    // eagerly would look like a scroll.
    var stickToTop by rememberSaveable { mutableStateOf(true) }
    var countWhenLeft by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .filter { !it }
            .collect {
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
                if (stickToTop && !atTop) countWhenLeft = pagingItems.itemCount
                stickToTop = atTop
            }
    }
    // An opened card holds the list (like an opened row in TrackedTable): at RDS's ~11 groups/s it
    // would move away at once. Closing it resumes following with the next packet.
    var previousItemCount by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagingItems.itemCount, filter.newestFirst) {
        val newCount = pagingItems.itemCount
        if (previousItemCount > 0 && newCount > previousItemCount && filter.newestFirst && stickToTop && expandedPacketId == null)
            listState.scrollToItem(0)
        previousItemCount = newCount
    }

    Box(modifier = modifier) {
        PagedPacketList(
            items = pagingItems,
            key = { it.rowId },
            expandedKey = expandedPacketId,
            onExpandedKeyChange = { expandedPacketId = it },
            state = listState,
            emptyText = if (filter.isFilterActive()) "No packets match the filter." else "Nothing received yet.",
            modifier = Modifier.fillMaxSize(),
        ) { item, expanded, onExpand ->
            RawPacketCard(item, expanded, onExpand, actions.cardActions)
        }
        if (!stickToTop) {
            val newCount = (pagingItems.itemCount - countWhenLeft).coerceAtLeast(0)
            ExtendedFloatingActionButton(
                onClick = { coroutineScope.launch { listState.animateScrollToItem(0); stickToTop = true } },
                icon = { Icon(Icons.Default.KeyboardArrowUp, contentDescription = null) },
                text = { Text(if (filter.newestFirst && newCount > 0) "${formatCount(newCount)} new" else "Top", textAlign = TextAlign.Center) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp).height(40.dp),
            )
        }
    }
}

/** One stored packet's card, by its mode - the Raw subtab's dispatch. */
@Composable
internal fun RawPacketCard(packet: StoredPacket<*, *>, expanded: Boolean, onExpand: () -> Unit, actions: CardActions) {
    when (packet.mode) {
        DigitalMode.FT8 -> packet.asPacketOf<Ft8Phy, Ft8Message>()?.let { Ft8MessageCard(it, expanded, onExpand, actions) }
        DigitalMode.AX25 -> packet.asPacketOf<Ax25Phy, Ax25Message>()?.let { Ax25FrameCard(it, expanded, onExpand, actions) }
        DigitalMode.RDS -> packet.asPacketOf<Unit, RdsGroupContent>()?.let { RdsGroupCard(it, expanded, onExpand, actions) }
        DigitalMode.R09 -> packet.asPacketOf<R09Phy, R09Paket>()?.let { R09MessageCard(it, expanded, onExpand, actions) }
        DigitalMode.RTTY, DigitalMode.PSK31 -> packet.asPacketOf<K2kPhy, K2kText>()?.let { K2kMessageCard(it, expanded, onExpand, actions) }
        DigitalMode.ADSB -> packet.asPacketOf<Unit, AdsbPayload>()?.let { AdsbFrameCard(it, expanded, onExpand, actions) }
        DigitalMode.POCSAG -> packet.asPacketOf<PocsagPhy, PocsagMessage>()?.let { PocsagMessageCard(it, expanded, onExpand, actions) }
        DigitalMode.LORA -> packet.asPacketOf<LoraPhy, LoraPayloadMessage>()?.let { LoraFrameCard(it, expanded, onExpand, actions) }
    }
}
