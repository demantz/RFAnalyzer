package com.mantz_it.rfanalyzer.digiext.ui

import com.mantz_it.rfanalyzer.digiext.DigitalAction
import com.mantz_it.rfanalyzer.digiext.DigitalNotice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.analyzer.OverloadMonitor
import com.mantz_it.rfanalyzer.digiext.ModeSheetStations
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer.ui.composable.IconFrequencyFilter
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.FilterChipRow
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.FilterSpec
import com.mantz_it.rfanalyzer.digiext.db.DigitalFilter
import com.mantz_it.rfanalyzer.digiext.db.StoredModeCount
import com.mantz_it.rfanalyzer.digiext.manualPage
import com.mantz_it.rfanalyzer.digiext.names
import kotlinx.coroutines.delay

/**
 * <h1>RF Analyzer - DigitalTabHeader</h1>
 *
 * Module:      DigitalTabHeader.kt
 * Description: The Digital tab's header, one line: the mode button, the Overview/Raw/Map switch,
 *              the search button (which shows and hides the filter row below and carries a dot
 *              while a filter is set), the minimized overload notice and the overflow menu.
 *
 *              Which mode is shown is not a filter: the mode button picks it (see ModeSheet.kt).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DigitalTabHeader(
    selectedView: PacketsView,
    onViewChanged: (PacketsView) -> Unit,
    /** The mode being demodulated right now - null while the analyzer is stopped. */
    liveMode: DigitalMode?,
    /** The receiver's channel frequency, for "Receiving on …" in the mode sheet. */
    channelFrequency: Long,
    filter: DigitalFilter,
    onFilterChanged: (DigitalFilter) -> Unit,
    packetCountsByMode: suspend () -> Map<DigitalMode, StoredModeCount>,
    modeSheetStations: ModeSheetStations,
    onQuickStop: () -> Unit,
    onDeleteClicked: () -> Unit,
    onShareClicked: () -> Unit,
    showTrails: Boolean,
    onShowTrailsChanged: (Boolean) -> Unit,
    showAllModesOnMap: Boolean,
    onShowAllModesOnMapChanged: (Boolean) -> Unit,
    onFitMap: () -> Unit,
    /** The overload notice was minimized and the overload still lasts: show its pill. */
    showOverloadPill: Boolean,
    onOverloadPillClicked: () -> Unit,
    /** Opens the manual at a page: ⋮'s Help, the mode sheet's "?". */
    onOpenManual: (subUrl: String) -> Unit,
    /** For the mode sheet - see DigitalAvailability.kt. */
    digitalNotice: DigitalNotice?,
    onDigitalAction: (DigitalAction) -> Unit,
    /** The digital trial's decoding time left while it counts (a line in the mode sheet; the pill
     *  above the drawer shows it everywhere). */
    trialSecondsLeft: Int?,
    onTrialClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showModeSheet by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    // Not persisted: reopening the tab starts with the row showing exactly when a filter is set,
    // so a filter narrowing the lists is never out of sight unless it was hidden on purpose.
    var showFilterRow by remember { mutableStateOf(filter.isFilterActive()) }

    // A narrow drawer (small phones) gets a narrower view switch, so the mode's name keeps its room.
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
    val narrow = maxWidth < 360.dp
    Column(modifier = Modifier.fillMaxWidth()) {
        HeaderRow(
            gap = if (narrow) 4.dp else 8.dp,
            startPadding = if (narrow) 4.dp else 8.dp,
            modifier = Modifier.fillMaxWidth().height(48.dp),
            start = { ModeButton(shownMode = filter.mode, liveMode = liveMode, onClick = { showModeSheet = true }) },
            center = { ViewSwitch(selectedView, onViewChanged, segmentWidth = if (narrow) 34.dp else 40.dp) },
        ) {
            SearchButton(
                open = showFilterRow,
                filterActive = filter.isFilterActive(),
                onClick = { showFilterRow = !showFilterRow },
            )
            if (showOverloadPill) OverloadPill(onClick = onOverloadPillClicked)
            Box {
                IconButton(onClick = { showOverflowMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(expanded = showOverflowMenu, onDismissRequest = { showOverflowMenu = false }) {
                    // Each entry names what a tap switches to.
                    if (selectedView == PacketsView.Raw) {
                        DropdownMenuItem(
                            text = { Text(if (filter.newestFirst) "Oldest First" else "Newest First") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = null) },
                            onClick = { showOverflowMenu = false; onFilterChanged(filter.copy(newestFirst = !filter.newestFirst)) },
                        )
                    }
                    if (selectedView == PacketsView.Map) {
                        DropdownMenuItem(
                            text = { Text("Fit to Markers") },
                            leadingIcon = { Icon(Icons.Default.CenterFocusStrong, contentDescription = null) },
                            onClick = { showOverflowMenu = false; onFitMap() },
                        )
                        DropdownMenuItem(
                            text = { Text(if (showTrails) "Hide Paths" else "Show Paths") },
                            leadingIcon = { Icon(Icons.Default.Route, contentDescription = null) },
                            onClick = { showOverflowMenu = false; onShowTrailsChanged(!showTrails) },
                        )
                        DropdownMenuItem(
                            text = { Text(if (showAllModesOnMap) "Show Current Mode Only" else "Show All Modes") },
                            leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null) },
                            onClick = { showOverflowMenu = false; onShowAllModesOnMapChanged(!showAllModesOnMap) },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share…") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { showOverflowMenu = false; onShareClicked() },
                    )
                    DropdownMenuItem(
                        text = { Text("Delete…") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { showOverflowMenu = false; onDeleteClicked() },
                    )
                    // The shown mode's page, where its rows, cards and map are explained.
                    DropdownMenuItem(
                        text = { Text("Help") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = null) },
                        onClick = {
                            showOverflowMenu = false
                            onOpenManual(filter.mode?.let { "${it.manualPage}#in-the-digital-tab" } ?: "digital-tab.html")
                        },
                    )
                }
            }
        }

        if (showFilterRow) {
            DigitalFilterRow(
                filter = filter,
                onFilterChanged = onFilterChanged,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
            )
        }
    }
    }

    if (showModeSheet) {
        ModeSheet(
            shownMode = filter.mode,
            liveMode = liveMode,
            channelFrequency = channelFrequency,
            packetCountsByMode = packetCountsByMode,
            onShow = { mode -> onFilterChanged(filter.copy(mode = mode)) },
            stations = modeSheetStations,
            onQuickStop = onQuickStop,
            onHelp = { showModeSheet = false; onOpenManual("digital-tab.html#choosing-a-mode") },
            notice = digitalNotice,
            onDigitalAction = onDigitalAction,
            trialSecondsLeft = trialSecondsLeft,
            onTrialClicked = { showModeSheet = false; onTrialClicked() },
            onDismiss = { showModeSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
        )
    }
}

/**
 * The header's line: [start] (the mode button) at the left, [center] (the view switch) in the
 * middle of the whole line - whatever the mode's name - and [end] (the buttons) at the right. Where
 * the buttons need the room, the switch moves left of the middle; the mode button fills what is left
 * of the switch and ellipsizes its name before anything else gives way.
 */
@Composable
private fun HeaderRow(
    gap: Dp,
    startPadding: Dp,
    modifier: Modifier,
    start: @Composable () -> Unit,
    center: @Composable () -> Unit,
    end: @Composable RowScope.() -> Unit,
) {
    Layout(
        content = {
            Box { start() }
            Box { center() }
            Row(verticalAlignment = Alignment.CenterVertically, content = end)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight.takeIf { it != Constraints.Infinity } ?: 48.dp.roundToPx()
        val gapPx = gap.roundToPx()
        val startX = startPadding.roundToPx()
        val loose = Constraints(maxWidth = width, maxHeight = height)
        val endPlaceable = measurables[2].measure(loose)
        val centerPlaceable = measurables[1].measure(loose)
        val endX = width - endPlaceable.width
        val centerX = minOf((width - centerPlaceable.width) / 2, endX - gapPx - centerPlaceable.width).coerceAtLeast(startX)
        val startWidth = (centerX - gapPx - startX).coerceAtLeast(0)
        val startPlaceable = measurables[0].measure(loose.copy(minWidth = startWidth, maxWidth = startWidth))
        layout(width, height) {
            startPlaceable.place(startX, (height - startPlaceable.height) / 2)
            centerPlaceable.place(centerX, (height - centerPlaceable.height) / 2)
            endPlaceable.place(endX, (height - endPlaceable.height) / 2)
        }
    }
}

/**
 * Names the mode whose data the tab is showing ([shownMode]), which is not necessarily the one
 * being demodulated ([liveMode]): stored data of another mode can be looked at meanwhile. The two
 * cases look different - filled while the shown mode is the live one, outlined with a history icon
 * while it is not - because on stored data nothing new is going to arrive. The "▾" says it opens
 * the mode list. It takes taps across the header's full height and the whole width left of the view
 * switch - an easy target also for a short name like FT8.
 */
@Composable
private fun ModeButton(shownMode: DigitalMode?, liveMode: DigitalMode?, onClick: () -> Unit) {
    val showsStoredData = shownMode != null && shownMode != liveMode
    val live = shownMode != null && !showsStoredData
    val colors = MaterialTheme.colorScheme
    val content = if (live) colors.onPrimaryContainer else colors.onSurface
    val shape = RoundedCornerShape(18.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(shape)
                .then(if (live) Modifier.background(colors.primaryContainer) else Modifier.border(1.dp, colors.outline, shape))
                .indication(interaction, ripple())
                .padding(start = if (showsStoredData) 6.dp else 12.dp, end = 2.dp),
        ) {
            if (showsStoredData) {
                Icon(Icons.Default.History, contentDescription = "Stored data", tint = colors.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(
                shownMode?.names?.name ?: "Mode",
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.5.sp, color = content),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        }
    }
}

/**
 * Overview / Raw / Map as one dark pill of icon segments, the selected one lifted out of it. Each
 * segment takes taps across the header's full height and to the middle of its gaps; the pill itself
 * stays small.
 */
@Composable
private fun ViewSwitch(selected: PacketsView, onSelect: (PacketsView) -> Unit, segmentWidth: Dp) {
    val pillColor = MaterialTheme.colorScheme.surfaceContainerLowest
    Row(
        modifier = Modifier
            .height(48.dp)
            .drawBehind {
                val pillHeight = 38.dp.toPx()
                drawRoundRect(pillColor, topLeft = Offset(0f, (size.height - pillHeight) / 2), size = Size(size.width, pillHeight), cornerRadius = CornerRadius(pillHeight / 2))
            }
            .padding(horizontal = 2.dp),
    ) {
        PacketsView.entries.forEach { view ->
            val isSelected = view == selected
            val interaction = remember { MutableInteractionSource() }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxHeight()
                    .width(segmentWidth + 2.dp)
                    .clickable(interactionSource = interaction, indication = null, role = Role.Tab, onClick = { onSelect(view) }),
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(width = segmentWidth, height = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else pillColor)
                        .indication(interaction, ripple()),
                ) {
                    Icon(
                        view.icon,
                        contentDescription = view.label,
                        tint = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }
        }
    }
}

/** Shows and hides the filter row; a dot says a filter is set, open or not. */
@Composable
private fun SearchButton(open: Boolean, filterActive: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        colors = if (open) IconButtonDefaults.iconButtonColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) else IconButtonDefaults.iconButtonColors(),
    ) {
        BadgedBox(badge = { if (filterActive) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
            Icon(Icons.Default.Search, contentDescription = if (open) "Hide search and filters" else "Search and filter")
        }
    }
}

/** The minimized overload notice: a small red "!" that opens it again. It takes taps across the
 *  header's full height. */
@Composable
private fun OverloadPill(onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(48.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 30.dp, height = 24.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.error)
                .indication(interaction, ripple()),
        ) {
            Icon(Icons.Default.PriorityHigh, contentDescription = "Processing overload", tint = MaterialTheme.colorScheme.onError, modifier = Modifier.size(16.dp))
        }
    }
}

/** How long the overload notice stays after the last overload: a flickering overload must not
 *  make it come and go, which would move the header each time. */
private const val OVERLOAD_NOTICE_HOLD_MS = 30_000L

/** Whether an overload is going on (or ended less than [OVERLOAD_NOTICE_HOLD_MS] ago). */
@Composable
internal fun rememberOverloadActive(): Boolean {
    val state by OverloadMonitor.state.collectAsState()
    var active by remember { mutableStateOf(false) }
    LaunchedEffect(state.chipVisible) {
        if (state.chipVisible) active = true
        else {
            delay(OVERLOAD_NOTICE_HOLD_MS)
            active = false
        }
    }
    return active
}

/**
 * The overload notice, over the top of the content so it moves nothing: what happens, "?" for the
 * manual, and minimize, which turns it into the header's red pill for as long as it lasts.
 */
@Composable
internal fun OverloadNotice(onHelpClicked: () -> Unit, onMinimize: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shadowElevation = 6.dp,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 12.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                Text("Processing overload", style = MaterialTheme.typography.labelLarge)
                Text("Samples are dropped - packets may be missed.", style = MaterialTheme.typography.bodySmall)
            }
            IconButton(onClick = onHelpClicked) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "What does this mean?", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onMinimize) {
                Icon(Icons.Default.CloseFullscreen, contentDescription = "Minimize", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/**
 * The filter row - search plus one chip per facet, each opening its own compact bottom sheet, on a
 * single line that scrolls sideways if it has to. Opened without a filter it starts in the search
 * field, so searching is one tap.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DigitalFilterRow(
    filter: DigitalFilter,
    onFilterChanged: (DigitalFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Hoisted here rather than owned by TimeRangeFilterChip - a stable ancestor whose position
    // never changes - so its SheetState isn't rebuilt mid-interaction.
    var timeSheetOpen by remember { mutableStateOf(false) }
    val timeSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val startInSearch = remember { !filter.isFilterActive() }

    val specs = listOf(
        FilterSpec.FuzzySearchSpec(
            label = "Search",
            helpText = "Callsign, name, text…",
            search = filter.search,
            onChange = { onFilterChanged(filter.copy(search = it)) }
        ),
        FilterSpec.CustomSpec(
            label = "Time",
            // Pinned true while its sheet is open: FilterChipRow sorts active chips to the front,
            // and the chip must not move while its sheet is open underneath the user's thumb.
            active = filter.hasTimeRange() || timeSheetOpen,
            content = {
                TimeRangeFilterChip(
                    label = "Time",
                    lastMs = filter.lastMs,
                    minTime = filter.minTime,
                    maxTime = filter.maxTime,
                    onRollingChanged = { onFilterChanged(filter.copy(lastMs = it, minTime = 0L, maxTime = 0L)) },
                    onRangeChanged = { min, max -> onFilterChanged(filter.copy(lastMs = 0L, minTime = min, maxTime = max)) },
                    showSheet = timeSheetOpen,
                    onShowSheetChange = { timeSheetOpen = it },
                    sheetState = timeSheetState,
                )
            }
        ),
        FilterSpec.FrequencySpec(
            label = "Frequency",
            min = filter.minFrequency,
            max = filter.maxFrequency,
            icon = IconFrequencyFilter,
            onChange = { min, max -> onFilterChanged(filter.copy(minFrequency = min, maxFrequency = max)) }
        ),
    )

    FilterChipRow(
        specs = specs,
        expanded = false,
        onToggleExpand = {},
        // Clears the filters, not what is being looked at: mode and sort order stay.
        onClear = { onFilterChanged(DigitalFilter(mode = filter.mode, newestFirst = filter.newestFirst)) },
        modifier = modifier,
        alwaysExpanded = true,
        initiallySearching = startInSearch,
    )
}
