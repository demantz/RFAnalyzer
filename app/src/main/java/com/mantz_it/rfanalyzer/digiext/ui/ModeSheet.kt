package com.mantz_it.rfanalyzer.digiext.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.digiext.DIGITAL_MODE_ORDER
import com.mantz_it.rfanalyzer.digiext.DigitalAction
import com.mantz_it.rfanalyzer.digiext.DigitalNotice
import com.mantz_it.rfanalyzer.digiext.formatTrialTimeLeft
import com.mantz_it.rfanalyzer.ui.composable.DigitalStatusNotice
import com.mantz_it.rfanalyzer.digiext.ModePreset
import com.mantz_it.rfanalyzer.digiext.ModePresets
import com.mantz_it.rfanalyzer.digiext.ModeSheetStations
import com.mantz_it.rfanalyzer.ui.TuneTarget
import com.mantz_it.rfanalyzer.digiext.db.StoredModeCount
import com.mantz_it.rfanalyzer.digiext.names
import com.mantz_it.rfanalyzer.digiext.ui.common.formatAge
import com.mantz_it.rfanalyzer.digiext.ui.common.outlinedButtonBorder
import com.mantz_it.rfanalyzer.digiext.ui.common.formatFrequency
import com.mantz_it.rfanalyzer.digiext.ui.common.plural
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

/**
 * <h1>RF Analyzer - Mode Sheet</h1>
 *
 * Module:      ModeSheet.kt
 * Description: The Digital tab's mode button opens this sheet. It lists every digital mode in the
 *              Demodulation tab's style (name, full name, description - from the same source)
 *              with two things to do with each:
 *              - **show** (tapping the row): look at that mode's stored data. Changes nothing
 *                about what is being demodulated.
 *              - **start** (the row's button): tune to one of the mode's stations and demodulate
 *                it (ModePresets.kt, Tuning.kt) - the one started last, in one tap. The chevron
 *                opens the mode's page in the sheet to choose another: the Digital Stations list's
 *                entries for the user's area and the favorites, and the way to all the mode's
 *                bookmarks. The received mode offers Stop instead.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModeSheet(
    /** The mode whose data the tab is showing. */
    shownMode: DigitalMode?,
    /** The mode being demodulated - null while the analyzer is stopped. */
    liveMode: DigitalMode?,
    channelFrequency: Long,
    packetCountsByMode: suspend () -> Map<DigitalMode, StoredModeCount>,
    onShow: (DigitalMode) -> Unit,
    stations: ModeSheetStations,
    onQuickStop: () -> Unit,
    /** "?": how the Digital tab and this sheet work, in the manual. */
    onHelp: () -> Unit,
    /** Why nothing can be started right now (see DigitalAvailability.kt) - Start then explains it too. */
    notice: DigitalNotice?,
    onDigitalAction: (DigitalAction) -> Unit,
    /** The digital trial's decoding time left while it counts, else null. */
    trialSecondsLeft: Int?,
    onTrialClicked: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState,
) {
    // Null until loaded: a row can't be offered for showing before it is known to hold anything.
    var counts by remember { mutableStateOf<Map<DigitalMode, StoredModeCount>?>(null) }
    LaunchedEffect(Unit) { counts = packetCountsByMode() }
    var pageFor by remember { mutableStateOf<DigitalMode?>(null) }
    val presets by stations.presets.collectAsState()
    val area by stations.area.collectAsState()
    val nowMs = remember { System.currentTimeMillis() }
    val start: (DigitalMode, TuneTarget) -> Unit = { mode, target -> stations.onStart(mode, target); onDismiss() }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        BackHandler(enabled = pageFor != null) { pageFor = null }
        val page = pageFor
        if (page != null) {
            StationPage(
                mode = page,
                presets = presets[page],
                area = area,
                onBack = { pageFor = null },
                onStart = { start(page, it) },
                onShowAll = { stations.onShowAll(page); onDismiss() },
                onChangeLocation = { stations.onChangeLocation(); onDismiss() },
            )
            return@ModalBottomSheet
        }
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            Text(
                "Digital Modes",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)) {
                Text(
                    "Tap a mode to look at its data. Start receives it, › chooses the station.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
                // As in the Demodulation tab's mode picker.
                IconButton(onClick = onHelp, modifier = Modifier.size(24.dp).padding(start = 2.dp)) {
                    Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "Help", modifier = Modifier.size(16.dp))
                }
            }
            if (trialSecondsLeft != null && trialSecondsLeft > 0) {
                Text(
                    "Digital modes trial: ${formatTrialTimeLeft(trialSecondsLeft)} of decoding left",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onTrialClicked).padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            notice?.let {
                DigitalStatusNotice(it, onAction = { action -> onDigitalAction(action); onDismiss() }, modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp))
            }
            DIGITAL_MODE_ORDER.forEach { mode ->
                val stored = counts?.get(mode)
                val live = mode == liveMode
                val startTarget = presets[mode]?.startTarget?.target
                ModeRow(
                    mode = mode,
                    storedLine = when {
                        live -> null
                        counts == null -> ""
                        stored == null -> "Nothing stored yet"
                        else -> "${plural(stored.count, mode.names.packetOne, mode.names.packetMany)} stored · last ${formatAge(nowMs, stored.lastMs)} ago"
                    },
                    receivingLine = if (live) "Receiving on ${formatFrequency(channelFrequency)}" else null,
                    startLine = if (!live) startTarget?.let { "Start: ${it.label} · ${formatFrequency(it.frequency)}" } else null,
                    shown = mode == shownMode,
                    live = live,
                    // The live mode is worth showing before its first packet has arrived.
                    onShow = { onShow(mode); onDismiss() }.takeIf { (stored?.count ?: 0) > 0 || live },
                    onStart = {
                        when {
                            live -> { onQuickStop(); onDismiss() }
                            startTarget != null -> start(mode, startTarget)
                            else -> pageFor = mode
                        }
                    },
                    onChoose = { pageFor = mode },
                )
            }
        }
    }
}

/**
 * One mode: tapping the row shows its stored data ([onShow], null while there is none to show),
 * the button starts it, the chevron opens its stations. Separate targets because they differ in
 * weight - showing is free and undone by showing something else, starting retunes and ends whatever
 * was being received.
 */
@Composable
private fun ModeRow(
    mode: DigitalMode,
    storedLine: String?,
    receivingLine: String?,
    startLine: String?,
    shown: Boolean,
    live: Boolean,
    onShow: (() -> Unit)?,
    onStart: () -> Unit,
    onChoose: () -> Unit,
) {
    val names = mode.names
    val nameColor = if (onShow != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (shown) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .then(if (onShow != null) Modifier.clickable(onClick = onShow) else Modifier)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(names.name, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = nameColor)
                if (names.fullName.isNotEmpty()) {
                    Text(
                        " (${names.fullName})",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Text(names.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            storedLine?.takeIf { it.isNotEmpty() }?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            receivingLine?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
            startLine?.let {
                Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (shown) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Shown",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 6.dp).size(20.dp),
            )
        }
        if (live) {
            OutlinedButton(onClick = onStart, contentPadding = PaddingValues(horizontal = 12.dp), border = outlinedButtonBorder(), modifier = Modifier.height(36.dp)) {
                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Stop", modifier = Modifier.padding(start = 4.dp))
            }
        } else {
            Button(onClick = onStart, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.height(36.dp)) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Start", modifier = Modifier.padding(start = 4.dp))
            }
        }
        IconButton(onClick = onChoose) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Choose a station")
        }
    }
}

/** A mode's stations, in place of the mode list: one tap starts one. */
@Composable
private fun StationPage(
    mode: DigitalMode,
    presets: ModePresets?,
    area: String?,
    onBack: () -> Unit,
    onStart: (TuneTarget) -> Unit,
    onShowAll: () -> Unit,
    onChangeLocation: () -> Unit,
) {
    val items = presets?.items.orEmpty()
    val lastKey = presets?.startTarget?.target?.key.takeIf { items.size > 1 }
    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 4.dp, end = 16.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text("${mode.names.name}: choose a station", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 16.dp, end = 4.dp)) {
            Text(
                if (area != null) "Presets for $area and your favorites" else "Set your location to get the presets for your area",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onChangeLocation) { Text(if (area != null) "Change" else "Set location") }
        }
        if (items.isEmpty()) {
            Text(
                "No stations yet: mark a ${mode.names.name} bookmark as favorite to find it here.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }
        items.forEach { preset ->
            StationRow(preset, last = preset.target.key != null && preset.target.key == lastKey, onClick = { onStart(preset.target) })
        }
        TextButton(onClick = onShowAll, modifier = Modifier.padding(start = 8.dp, top = 4.dp)) {
            Text("All ${mode.names.name} bookmarks…")
        }
    }
}

@Composable
private fun StationRow(preset: ModePreset, last: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(preset.target.label, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                listOfNotNull(
                    formatFrequency(preset.target.frequency),
                    preset.distanceKm?.let { "$it km" },
                    "last started".takeIf { last },
                ).joinToString(" · "),
                fontSize = 12.sp,
                color = if (last) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (preset.favorite) {
            Icon(Icons.Default.Star, contentDescription = "Favorite", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
    }
}
