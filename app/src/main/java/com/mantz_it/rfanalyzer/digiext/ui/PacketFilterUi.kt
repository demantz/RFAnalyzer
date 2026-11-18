package com.mantz_it.rfanalyzer.digiext.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.mantz_it.rfanalyzer.digiext.db.DigitalFilter
import com.mantz_it.rfanalyzer.digiext.names
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.common.plural

/**
 * <h1>RF Analyzer - Packet Filter UI</h1>
 *
 * Module:      PacketFilterUi.kt
 * Description: The Digital tab's Time chip with its date/time pickers, and the delete/share scope
 *              dialogs. The Time chip is the one facet of the filter row (DigitalTabHeader.kt) that
 *              the shared FilterSpec variants (BookmarkFilterUiElements.kt) don't cover: rolling
 *              presets plus a custom date/time range.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

private val PACKET_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm").withZone(ZoneId.systemDefault())
fun formatDate(timestampMs: Long): String = PACKET_DATE_FORMATTER.format(Instant.ofEpochMilli(timestampMs))

/** Short enough for a chip: the year only where it isn't this one. */
private fun formatChipDate(timestampMs: Long): String {
    val time = Instant.ofEpochMilli(timestampMs).atZone(ZoneId.systemDefault())
    val pattern = if (time.year == java.time.LocalDate.now().year) "d MMM HH:mm" else "d MMM yy HH:mm"
    return DateTimeFormatter.ofPattern(pattern).format(time)
}

private const val HOUR_MS = 60 * 60 * 1000L

/** The rolling ranges on offer, by the [DigitalFilter.lastMs] they set. */
private val ROLLING_PRESETS = listOf("Last hour" to HOUR_MS, "Last 24h" to 24 * HOUR_MS, "Last 7d" to 7 * 24 * HOUR_MS)

/**
 * The time range is either rolling ([lastMs], set by a preset - "the last hour" keeps meaning the
 * last hour) or a custom, absolute one ([minTime]/[maxTime]); [onRollingChanged] and
 * [onRangeChanged] each replace the other kind.
 *
 * showSheet/onShowSheetChange/sheetState are hoisted to the caller (DigitalTabHeader.kt's filter
 * row) - see the comment there for why.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRangeFilterChip(
    label: String,
    lastMs: Long,
    minTime: Long,
    maxTime: Long,
    onRollingChanged: (Long) -> Unit,
    onRangeChanged: (Long, Long) -> Unit,
    showSheet: Boolean,
    onShowSheetChange: (Boolean) -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    customIcon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Schedule,
) {
    // The picker dialogs open from the sheet and change the range only when confirmed, so -
    // unlike showSheet - they never reorder the chips while showing and can stay local state.
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    val isActive = lastMs != 0L || minTime != 0L || maxTime != 0L

    val displayLabel = when {
        lastMs != 0L -> ROLLING_PRESETS.firstOrNull { it.second == lastMs }?.first ?: "Last ${lastMs / HOUR_MS}h"
        minTime != 0L && maxTime != 0L -> "${formatChipDate(minTime)} – ${formatChipDate(maxTime)}"
        minTime != 0L -> "since ${formatChipDate(minTime)}"
        maxTime != 0L -> "until ${formatChipDate(maxTime)}"
        else -> label
    }

    FilterChip(
        selected = isActive,
        onClick = { onShowSheetChange(true) },
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(displayLabel)
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
            }
        },
        leadingIcon = { Icon(customIcon, contentDescription = null) },
        modifier = modifier
    )

    if (showSheet) {
        // The caller's sheetState skips the partially expanded state: content taller than that
        // anchor would open partly collapsed, and a tap near its lower edge could be a drag.
        ModalBottomSheet(
            onDismissRequest = { onShowSheetChange(false) },
            sheetState = sheetState,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text("Select Time Range", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (ROLLING_PRESETS + ("All time" to 0L)).forEach { (presetLabel, presetMs) ->
                        FilterChip(
                            selected = if (presetMs == 0L) !isActive else lastMs == presetMs,
                            onClick = {
                                onRollingChanged(presetMs)
                                onShowSheetChange(false)
                            },
                            label = { Text(presetLabel) }
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(Modifier.height(12.dp))
                Text("Custom range (date & time)", style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showFromPicker = true }) {
                        Text(if (minTime != 0L) "From: ${formatDate(minTime)}" else "From: any")
                    }
                    TextButton(onClick = { showToPicker = true }) {
                        Text(if (maxTime != 0L) "To: ${formatDate(maxTime)}" else "To: any")
                    }
                }
            }
        }
    }

    if (showFromPicker) {
        DateTimePickerDialog(
            initialMillis = minTime.takeIf { it != 0L },
            onConfirm = { onRangeChanged(it, maxTime) },
            onDismiss = { showFromPicker = false },
        )
    }
    if (showToPicker) {
        DateTimePickerDialog(
            initialMillis = maxTime.takeIf { it != 0L },
            onConfirm = { onRangeChanged(minTime, it) },
            onDismiss = { showToPicker = false },
        )
    }
}

private val DATE_ONLY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy")

/**
 * Date and time picker for one end of a time range, time first: the dialog shows the selected date
 * as a button above the TimePicker clock; the button opens the calendar in a second dialog.
 *
 * Material3's DatePicker gives the picked date as UTC midnight in epoch millis whatever the local
 * zone, so the date goes through UTC and is combined with the hour and minute in the local zone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePickerDialog(
    initialMillis: Long?,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val initialLocal = remember(initialMillis) {
        initialMillis?.let { Instant.ofEpochMilli(it).atZone(zone) } ?: java.time.ZonedDateTime.now(zone)
    }
    var selectedDate by remember(initialMillis) { mutableStateOf(initialLocal.toLocalDate()) }
    val timeState = rememberTimePickerState(initialHour = initialLocal.hour, initialMinute = initialLocal.minute, is24Hour = true)
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Date & Time") },
        text = {
            Column {
                TextButton(onClick = { showDatePicker = true }) {
                    Icon(Icons.Default.Schedule, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(selectedDate.format(DATE_ONLY_FORMATTER))
                }
                Spacer(Modifier.height(8.dp))
                TimePicker(state = timeState)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val combined = selectedDate.atTime(timeState.hour, timeState.minute).atZone(zone).toInstant().toEpochMilli()
                onConfirm(combined)
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showDatePicker) {
        val dateState = rememberDatePickerState(
            initialSelectedDateMillis = selectedDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dateState.selectedDateMillis?.let {
                        selectedDate = Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = dateState) }
    }
}

// ---------------------------------------------------------------------------
// Delete / Share scope-selection dialogs
// ---------------------------------------------------------------------------

/** What to clear. There is deliberately no "matching the current filter" scope: a mode's packets
 *  and tracked objects are cleared together so neither is left describing data that is gone, and a
 *  filtered subset of packets has no matching subset of objects. */
sealed class PacketDeleteRequest {
    data class CurrentMode(val mode: DigitalMode, val count: Int) : PacketDeleteRequest()
    data class Everything(val totalCount: Int) : PacketDeleteRequest()
}

sealed class PacketShareRequest {
    data class CurrentFilter(val filter: DigitalFilter, val matchCount: Int) : PacketShareRequest()
    data class CurrentMode(val mode: DigitalMode, val count: Int) : PacketShareRequest()
    data class Everything(val totalCount: Int) : PacketShareRequest()
}

private enum class PacketActionScope { FILTER, MODE, ALL }

/** Confirms clearing a mode's (or all) recorded data - packets and tracked objects together. */
@Composable
fun PacketDeleteDialog(
    currentMode: DigitalMode?,
    modeCount: Int,
    totalCount: Int,
    onConfirm: (PacketDeleteRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    PacketScopeDialog(
        title = "Clear Recorded Data",
        confirmLabel = "Clear",
        matchCount = null,
        modeLabel = { mode, count -> "Everything recorded for ${mode.names.name} (${plural(count, mode.names.packetOne, mode.names.packetMany)})" },
        allLabel = { count -> "Everything, all modes (${plural(count, "packet", "packets")})" },
        currentMode = currentMode,
        modeCount = modeCount,
        totalCount = totalCount,
        onConfirm = { scope ->
            onConfirm(
                when (scope) {
                    PacketActionScope.MODE -> PacketDeleteRequest.CurrentMode(currentMode!!, modeCount)
                    else -> PacketDeleteRequest.Everything(totalCount)
                }
            )
        },
        onDismiss = onDismiss,
    )
}

@Composable
fun PacketShareDialog(
    filter: DigitalFilter,
    matchCount: Int,
    currentMode: DigitalMode?,
    modeCount: Int,
    totalCount: Int,
    onConfirm: (PacketShareRequest) -> Unit,
    onDismiss: () -> Unit,
) {
    PacketScopeDialog(
        title = "Share Packets",
        confirmLabel = "Share",
        // Without a filter the filter's packets are the mode's: offered once, as the mode.
        matchCount = matchCount.takeIf { filter.isFilterActive() },
        currentMode = currentMode,
        modeCount = modeCount,
        totalCount = totalCount,
        onConfirm = { scope ->
            onConfirm(
                when (scope) {
                    PacketActionScope.FILTER -> PacketShareRequest.CurrentFilter(filter, matchCount)
                    PacketActionScope.MODE -> PacketShareRequest.CurrentMode(currentMode!!, modeCount)
                    PacketActionScope.ALL -> PacketShareRequest.Everything(totalCount)
                }
            )
        },
        onDismiss = onDismiss,
    )
}

@Composable
private fun PacketScopeDialog(
    title: String,
    confirmLabel: String,
    /** Null hides the "matching current filter" scope. */
    matchCount: Int?,
    modeLabel: (DigitalMode, Int) -> String = { mode, count -> "All ${mode.names.name} ${mode.names.packetMany} (${formatCount(count)})" },
    allLabel: (Int) -> String = { count -> "Every packet, all modes (${formatCount(count)})" },
    currentMode: DigitalMode?,
    modeCount: Int,
    totalCount: Int,
    onConfirm: (PacketActionScope) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedScope by remember {
        mutableStateOf(
            when {
                matchCount != null -> PacketActionScope.FILTER
                currentMode != null -> PacketActionScope.MODE
                else -> PacketActionScope.ALL
            }
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (matchCount != null) {
                    ScopeOption(
                        label = "Packets matching the filter (${formatCount(matchCount)})",
                        selected = selectedScope == PacketActionScope.FILTER,
                        onClick = { selectedScope = PacketActionScope.FILTER },
                    )
                }
                ScopeOption(
                    label = if (currentMode != null) modeLabel(currentMode, modeCount) else "Current mode (none selected)",
                    selected = selectedScope == PacketActionScope.MODE,
                    enabled = currentMode != null,
                    onClick = { selectedScope = PacketActionScope.MODE },
                )
                ScopeOption(
                    label = allLabel(totalCount),
                    selected = selectedScope == PacketActionScope.ALL,
                    onClick = { selectedScope = PacketActionScope.ALL },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selectedScope) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun ScopeOption(label: String, selected: Boolean, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).padding(vertical = 6.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick, enabled = enabled)
        Text(label, Modifier.padding(start = 8.dp), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline)
    }
}
