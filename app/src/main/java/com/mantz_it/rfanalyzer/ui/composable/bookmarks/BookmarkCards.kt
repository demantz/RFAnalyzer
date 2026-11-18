package com.mantz_it.rfanalyzer.ui.composable.bookmarks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Web
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.database.Band
import com.mantz_it.rfanalyzer.database.BandWithBookmarkList
import com.mantz_it.rfanalyzer.database.COUNTRY_CODE_TO_NAME
import com.mantz_it.rfanalyzer.database.BookmarkList
import com.mantz_it.rfanalyzer.database.BookmarkListType
import com.mantz_it.rfanalyzer.database.Coordinates
import com.mantz_it.rfanalyzer.database.DownloadState
import com.mantz_it.rfanalyzer.database.OnlineStationProvider
import com.mantz_it.rfanalyzer.database.OnlineStationProviderSettings
import com.mantz_it.rfanalyzer.database.OnlineStationProviderWithSettings
import com.mantz_it.rfanalyzer.database.Schedule
import com.mantz_it.rfanalyzer.database.SourceProvider
import com.mantz_it.rfanalyzer.database.Station
import com.mantz_it.rfanalyzer.database.StationWithBookmarkList
import com.mantz_it.rfanalyzer.database.SubBand
import com.mantz_it.rfanalyzer.ui.RFAnalyzerTheme
import kotlin.math.abs
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.IconTuneBand
import com.mantz_it.rfanalyzer.ui.composable.IconTuneStation
import com.mantz_it.rfanalyzer.ui.composable.LocalShowHelp
import com.mantz_it.rfanalyzer.ui.composable.asDateTimeWithRelative
import com.mantz_it.rfanalyzer.ui.composable.asStringWithUnit
import com.mantz_it.rfanalyzer.ui.composable.frequencyRangeString
import com.mantz_it.rfanalyzer.ui.composable.contrastingTextColor
import com.mantz_it.rfanalyzer.ui.composable.countryCodeToFlagEmoji
import com.mantz_it.rfanalyzer.ui.composable.toTimeSpanString
import com.mantz_it.rfanalyzer.util.extractCoreCallsign

/**
 * <h1>RF Analyzer - BookmarkCards</h1>
 *
 * Module:      BookmarkCards.kt
 * Description: Bookmark Cards (StationCard, BookmarkListCard, BandCard, OnlineSourceCard) for
 *              LazyColumn
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@Composable
fun StationCard(
    station: Station,
    bookmarkList: BookmarkList?,
    expanded: Boolean,
    selected: Boolean,
    onExpand: () -> Unit,
    selectMode: Boolean,
    onSelectToggle: () -> Unit,
    onTuneTo: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onCopyOrMove: () -> Unit,
    onExport: () -> Unit,
    openLocation: (Station) -> Unit,
    openCallsign: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(modifier = Modifier
        .fillMaxWidth()
        .padding(6.dp)
        .clickable { if (!selectMode) onExpand() else onSelectToggle() }) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Row {
                        Text(
                            station.name,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = if (!expanded) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row {
                        Text(station.frequency.asStringWithUnit("Hz"),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(end = 8.dp))
                        Text(
                            "(BW: ${station.bandwidth.toLong().asStringWithUnit("Hz")})  ",
                            style = MaterialTheme.typography.bodySmall)
                        if (station.mode != DemodulationMode.OFF)
                            Box(modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(start = 6.dp)
                                .background(Color(0xFF181818), shape = RoundedCornerShape(40.dp))
                            ) {
                                Text(
                                    station.mode.displayName,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        Spacer(modifier = Modifier.weight(1f))
                        if(station.favorite)
                            Icon(
                                imageVector = Icons.Default.Favorite,
                                contentDescription = "Is Favorite",
                                tint = Color.Red,
                                modifier = Modifier
                                    .padding(start = 6.dp)
                                    .size(16.dp)
                            )
                        if (station.source != SourceProvider.BOOKMARK) {
                            val backgroundColor = Color(station.source.color)
                            Box(modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(start = 6.dp)
                                .background(backgroundColor, shape = RoundedCornerShape(40.dp))
                            ) {
                                Text(station.source.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = backgroundColor.contrastingTextColor(),
                                    modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        } else if (bookmarkList != null) {
                            val backgroundColor = Color(bookmarkList.color ?: 0)
                            Box(modifier = Modifier
                                .align(Alignment.CenterVertically)
                                .padding(start = 6.dp)
                                .background(backgroundColor, shape = RoundedCornerShape(40.dp))
                            ) {
                                Text(bookmarkList.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = backgroundColor.contrastingTextColor(),
                                    modifier = Modifier.padding(horizontal = 8.dp))
                            }
                        }
                    }
                    if (station.notes != null)
                        Text(station.notes, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                if (selectMode) { Checkbox(checked = selected, onCheckedChange = { onSelectToggle() }) }
            }

            if (expanded) {
                if (station.callsign != null) {
                    Text("Callsign: ${station.callsign}", style = MaterialTheme.typography.bodySmall)
                }
                if (station.countryCode != null || station.address != null) {
                    val countryString = if (station.countryCode != null && station.countryName != null) "${countryCodeToFlagEmoji(station.countryCode)} ${station.countryName}" else null
                    val address = when {
                        station.address != null && station.address.isNotEmpty() && countryString != null -> "Address: ${station.address}, $countryString"
                        station.address != null && station.address.isNotEmpty() -> "Address: ${station.address}"
                        countryString != null -> "Country: $countryString"
                        else -> null
                    }
                    address?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (station.coordinates != null) {
                    Text("\uD83D\uDCCD${station.coordinates}", style = MaterialTheme.typography.bodySmall)
                }
                station.schedule?.let { schedule ->
                    Row(modifier = Modifier.padding(bottom = 6.dp)) {
                        Icon(Icons.Default.Schedule, contentDescription = "Schedule", modifier = Modifier.padding(end = 6.dp).align(Alignment.CenterVertically))
                        Column(modifier = Modifier.align(Alignment.CenterVertically)) {
                            Text("${schedule.startTimeUtc} - ${schedule.endTimeUtc} (UTC)", modifier = Modifier.padding(end = 16.dp))
                            if (schedule.days.isNotEmpty()) {
                                Row {
                                    for (day in Schedule.Weekday.entries) {
                                        Text(day.shortName, modifier = Modifier
                                            .then(if (day in schedule.days) {
                                                Modifier.border(1.dp, MaterialTheme.colorScheme.secondary, RoundedCornerShape(6.dp))
                                            } else Modifier)
                                            .padding(horizontal = 3.dp)
                                        )
                                        Spacer(Modifier.width(2.dp))
                                    }
                                }
                            }
                        }
                        if (schedule.remarks != null && schedule.remarks.isNotEmpty()) {
                            Text(
                                "Notes: \n${schedule.remarks}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray,
                                modifier = Modifier.align(Alignment.CenterVertically) .padding(start = 8.dp)
                            )
                        }
                    }
                }

                if (station.language != null)
                    Text("Language: ${station.language}", style = MaterialTheme.typography.bodySmall)
                if (station.spottime != null)
                    Text("Spotted: ${station.spottime.asDateTimeWithRelative()}", style = MaterialTheme.typography.bodySmall)

                if (station.source == SourceProvider.BOOKMARK) {
                    Text("Created: \t${station.createdAt.asDateTimeWithRelative()}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Text("Modified: \t${station.updatedAt.asDateTimeWithRelative()}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                } else {
                    Text("Updated: \t${station.updatedAt.asDateTimeWithRelative()}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }

                // Button row with actions
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(imageVector = if (station.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Toggle Favorite",
                            tint = if (station.favorite) Color.Red else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = onTuneTo) { Icon(IconTuneStation, contentDescription = "Tune") }
                    // Overflow menu
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More actions") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false } ) {
                            @Composable
                            fun ddItem(icon: ImageVector, text: String, onClick: () -> Unit)  {
                                DropdownMenuItem(
                                    text = { Row {
                                        Icon(icon, contentDescription = text, modifier = Modifier.padding(end = 6.dp))
                                        Text(text, modifier = Modifier.align(Alignment.CenterVertically))
                                    } },
                                    onClick = { showMenu = false; onClick() }
                                )
                            }
                            ddItem(Icons.Default.FileCopy, "Copy/Move", onCopyOrMove)
                            if (station.source == SourceProvider.BOOKMARK) {
                                ddItem(Icons.Default.Edit, "Edit", onEdit)
                                ddItem(Icons.Default.Upload, "Export", onExport)
                                ddItem(Icons.Default.Delete, "Delete", onDelete)
                            }
                            if (station.callsign != null) {
                                val callTmp = if (station.callsign.contains("/"))
                                    extractCoreCallsign(station.callsign)  // pure call sign without prefixes/suffixes
                                else null
                                val call = callTmp ?: station.callsign
                                ddItem(Icons.Default.Web, "QRZ.com/$call") { openCallsign(call) }
                            }
                            if (station.coordinates != null || station.countryCode != null || station.address != null) {
                                ddItem(Icons.Default.Map, "Open in Maps") { openLocation(station) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarkListCard(
    bookmarkList: BookmarkList,
    expanded: Boolean,
    onExpand: () -> Unit,
    onGotoBookmarkList: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCopyOrMove: () -> Unit,
    onExport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth().padding(6.dp).clickable { onExpand() }) {
        Column {
            Row {
                if (bookmarkList.color != null)
                    Box(modifier = Modifier.padding(start = 8.dp).size(20.dp).align(Alignment.CenterVertically).background(Color(bookmarkList.color), shape = RoundedCornerShape(32.dp)))
                Column(modifier = Modifier.weight(1f).padding(12.dp)) {
                    Text(bookmarkList.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    if (bookmarkList.notes != null)
                        Text(bookmarkList.notes, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                IconButton(
                    onClick = onGotoBookmarkList,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .height(60.dp)
                        .width(60.dp)
                        .padding(8.dp)
                        .background(Color(0x22000000), shape = RoundedCornerShape(8.dp))
                ) { Icon(Icons.Default.ChevronRight, contentDescription = "Open List") }
            }
            if (expanded) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onDelete, modifier = Modifier.align(Alignment.CenterVertically)) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                    IconButton(onClick = onEdit, modifier = Modifier.align(Alignment.CenterVertically)) { Icon(Icons.Default.Edit, contentDescription = "Edit") }
                    IconButton(onClick = onExport) { Icon(Icons.Default.Upload, contentDescription = "Export") }
                    IconButton(onClick = onCopyOrMove) { Icon(Icons.Default.FileCopy, contentDescription = "Copy/Move Items") }
                }
            }
        }
    }
}

@Composable
fun BandCard(
    band: Band,
    bookmarkList: BookmarkList?,
    expanded: Boolean,
    selected: Boolean,
    onExpand: () -> Unit,
    selectMode: Boolean,
    onSelectToggle: () -> Unit,
    onTuneTo: () -> Unit,
    onToggleFavorite: () -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onCopyOrMove: () -> Unit,
    onExport: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Card(modifier = Modifier.fillMaxWidth().padding(6.dp).clickable { if (!selectMode) onExpand() else onSelectToggle() }) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(band.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Row {
                Text( "↔\uFE0F ${frequencyRangeString(band.startFrequency, band.endFrequency)}", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.weight(1f))
                if(band.favorite)
                    Icon(imageVector = Icons.Default.Favorite,
                        contentDescription = "Is Favorite",
                        tint = Color.Red,
                        modifier = Modifier.padding(end = 6.dp).size(16.dp)
                    )
                if (bookmarkList != null) {
                    val backgroundColor = Color(bookmarkList.color ?: 0)
                    Box(modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .background(backgroundColor, shape = RoundedCornerShape(40.dp))
                        .clickable(onClick = { })
                    ) { Text(bookmarkList.name, style = MaterialTheme.typography.labelSmall, color = backgroundColor.contrastingTextColor(), modifier = Modifier.padding(horizontal = 8.dp)) }
                }
                if (selectMode) { Checkbox(checked = selected, onCheckedChange = { onSelectToggle() }) }
            }

            if (band.notes != null)
                Text(band.notes, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            if (expanded) {
                band.subBands.forEach { sb -> Text("• ${sb.name}: ${frequencyRangeString(sb.startFrequency, sb.endFrequency)}") }

                // Button row with actions
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = onToggleFavorite) {
                        Icon(imageVector = if (band.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Toggle Favorite",
                            tint = if (band.favorite) Color.Red else LocalContentColor.current,
                        )
                    }
                    IconButton(onClick = onTuneTo) { Icon(IconTuneBand, contentDescription = "Tune") }

                    // Overflow menu
                    Box {
                        IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More actions") }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false } ) {
                            @Composable
                            fun ddItem(icon: ImageVector, text: String, onClick: () -> Unit)  {
                                DropdownMenuItem(
                                    text = { Row {
                                        Icon(icon, contentDescription = text, modifier = Modifier.padding(end = 6.dp))
                                        Text(text, modifier = Modifier.align(Alignment.CenterVertically))
                                    } },
                                    onClick = { showMenu = false; onClick() }
                                )
                            }
                            ddItem(Icons.Default.FileCopy, "Copy/Move", onCopyOrMove)
                            ddItem(Icons.Default.Edit, "Edit", onEdit)
                            ddItem(Icons.Default.Upload, "Export", onExport)
                            ddItem(Icons.Default.Delete, "Delete", onDelete)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineSourceCard(
    providerWithSettings: OnlineStationProviderWithSettings,
    downloadState: DownloadState,
    expanded: Boolean,
    onExpand: () -> Unit,
    onActionDisplay: () -> Unit,
    onActionSetEnable: (Boolean) -> Unit,
    onAutoUpdateIntervalChanged: (Int) -> Unit,
    onActionStartDownload: () -> Unit,
    onActionCancelDownload: () -> Unit,
    onActionClear: () -> Unit,
    helpLink: String
) {
    Card(modifier = Modifier.fillMaxWidth().padding(6.dp).clickable { onExpand() }) {
        fun generateNiceTimeSteps(minSeconds: Int, maxSeconds: Int): List<Int> {
            val niceSteps = listOf(
                // seconds
                1, 2, 3, 4, 5, 10, 15, 20, 30, 45,

                // minutes
                1 * 60,
                2 * 60,
                5 * 60,
                10 * 60,
                15 * 60,
                20 * 60,
                30 * 60,
                45 * 60,

                // hours
                1 * 60 * 60,
                90 * 60,          // 1.5 hours
                2 * 60 * 60,
                3 * 60 * 60,
                4 * 60 * 60,
                5 * 60 * 60,
                6 * 60 * 60,
                8 * 60 * 60,
                10 * 60 * 60,
                12 * 60 * 60,
                18 * 60 * 60,

                // days
                1 * 24 * 60 * 60,
                2 * 24 * 60 * 60,
                3 * 24 * 60 * 60,
                4 * 24 * 60 * 60,
                5 * 24 * 60 * 60,
                6 * 24 * 60 * 60,
                7 * 24 * 60 * 60,
                14 * 24 * 60 * 60,
                21 * 24 * 60 * 60,
                28 * 24 * 60 * 60,
                60 * 24 * 60 * 60,
                90 * 24 * 60 * 60,
                120 * 24 * 60 * 60,
                180 * 24 * 60 * 60,
            )
            return niceSteps
                .filter { it in minSeconds..maxSeconds }
                .distinct()
                .sorted()
        }
        val showHelp = LocalShowHelp.current
        Column {
            Row {
                Column(modifier = Modifier.padding(12.dp).weight(1f)) {
                    Text(providerWithSettings.provider.displayName, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(providerWithSettings.provider.description, style = MaterialTheme.typography.bodySmall)
                    if (expanded) {
                        val lastUpdated = providerWithSettings.settings.lastUpdatedTimestamp.let { if (it == 0L) "never" else it.asDateTimeWithRelative() }
                        Text("Last Updated: $lastUpdated", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        if (providerWithSettings.settings.autoUpdateEnabled) {
                            Text("Automatic Update: Enabled", style = MaterialTheme.typography.bodySmall)
                            Text("Update Interval: ${(providerWithSettings.settings.autoUpdateIntervalSeconds * 1000L).toTimeSpanString()}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            val steps = generateNiceTimeSteps(providerWithSettings.provider.minUpdateIntervalSeconds, providerWithSettings.provider.maxUpdateIntervalSeconds)
                            var index by remember { mutableIntStateOf(steps.indices.minBy { index -> abs(steps[index] - providerWithSettings.settings.autoUpdateIntervalSeconds) }) }
                            Slider(
                                value = index.toFloat(),
                                onValueChange = {
                                    index = it.toInt()
                                    onAutoUpdateIntervalChanged(steps[index])
                                },
                                valueRange = 0f..(steps.lastIndex.toFloat()),
                                steps = steps.size - 2,
                                modifier = Modifier.padding(start = 15.dp, end = 15.dp, top = 10.dp)
                            )
                        } else {
                            Text("Automatic Update: Disabled", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                IconButton(
                    onClick = onActionDisplay,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .height(60.dp)
                        .width(60.dp)
                        .padding(8.dp)
                        .background(Color(0x22000000), shape = RoundedCornerShape(8.dp))
                ) { Icon(Icons.Default.ChevronRight, contentDescription = "Open List") }
            }
            if (expanded) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    when (downloadState) {
                        DownloadState.Idle -> {
                            IconButton(onClick = onActionStartDownload) { Icon(Icons.Default.Download, contentDescription = "Start Download") }
                        }
                        DownloadState.Success -> {
                            Icon(Icons.Default.Check, contentDescription = "Download Complete", tint = Color.Green, modifier = Modifier.align(Alignment.CenterVertically))
                            IconButton(onClick = onActionStartDownload) { Icon(Icons.Default.Download, contentDescription = "Start Download") }
                        }
                        is DownloadState.Error -> {
                            Icon(Icons.Default.Error, contentDescription = "Download Failed", tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(end = 6.dp).align(Alignment.CenterVertically))
                            Text(downloadState.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f).align(Alignment.CenterVertically))
                            IconButton(onClick = onActionStartDownload) { Icon(Icons.Default.Download, contentDescription = "Start Download") }
                        }
                        DownloadState.InProgress -> {
                            CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterVertically))
                            IconButton(onClick = onActionCancelDownload) { Icon(Icons.Default.Cancel, contentDescription = "Cancel Download") }
                        }
                    }
                    if (providerWithSettings.settings.autoUpdateEnabled)
                        IconButton(onClick = { onActionSetEnable(false) }) {Icon(Icons.Default.Autorenew, tint = MaterialTheme.colorScheme.primary, contentDescription = "Auto-Update Enabled")}
                    else
                        IconButton(onClick = { onActionSetEnable(true) }) {Icon(Icons.Default.Autorenew, contentDescription = "Auto-Update Disabled")}
                    IconButton(onClick = onActionClear) { Icon(Icons.Default.DeleteSweep, contentDescription = "Clear cache") }
                    IconButton(onClick = { showHelp(helpLink) }) { Icon(Icons.Default.Help, contentDescription = "Help") }
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Station Card - Collapsed")
@Composable
private fun PreviewStationCardCollapsed() {
    val station = Station(
        id = 1,
        bookmarkListId = 1,
        name = "BBC World Service",
        frequency = 9410000,
        bandwidth = 8000,
        createdAt = 0,
        updatedAt = 0,
        mode = DemodulationMode.AM,
        callsign = "BBC",
        notes = "International news and cultural programming.",
        coordinates = Coordinates(51.5, -0.1),
        address = "Heidelberg",
        countryCode = "D"
    )
    val bookmarkList = BookmarkList(id = 10, name = "My Bookmarks", type = BookmarkListType.STATION, notes = "default bookmarkList for bookmarks", color = android.graphics.Color.argb(255, 220, 150, 250))
    RFAnalyzerTheme {
        StationCard(
            station = station,
            bookmarkList = bookmarkList,
            expanded = false,
            selected = false,
            selectMode = false,
            onExpand = {},
            onSelectToggle = {},
            onTuneTo = {},
            onToggleFavorite = { },
            onDelete = { },
            onEdit = { },
            onCopyOrMove = {},
            onExport = {},
            openLocation = {},
            openCallsign = {}
        )
    }
}

@Preview(showBackground = true, name = "Station Card - Expanded")
@Composable
private fun PreviewStationCardExpanded() {
    val now = System.currentTimeMillis()
    val station = Station(
        id = 1,
        bookmarkListId = 1,
        name = "Voice of America",
        frequency = 15580000,
        bandwidth = 9000,
        createdAt = 1337855555555,
        updatedAt = 1377855555555,
        favorite = true,
        mode = DemodulationMode.WFM,
        callsign = "DM4NTZ",
        spottime = now,
        schedule = Schedule(
            startTimeUtc = "1100",
            endTimeUtc = "1500",
            days = setOf(Schedule.Weekday.MONDAY, Schedule.Weekday.TUESDAY, Schedule.Weekday.FRIDAY),
            remarks = "test remark, now a really long note."
        ),
        notes = "Broadcasting in English to Africa.",
        coordinates = Coordinates(49.408835, 8.728703),
        source = SourceProvider.POTA
    )
    val bookmarkList = BookmarkList(id = 1, name = "Shortwave", type = BookmarkListType.STATION, notes = "All international SW stations", color = 0xFFFFFF)
    RFAnalyzerTheme {
        StationCard(
            station = station,
            bookmarkList = bookmarkList,
            expanded = true,
            selected = false,
            selectMode = false,
            onExpand = {},
            onSelectToggle = {},
            onTuneTo = {},
            onToggleFavorite = { },
            onDelete = { },
            onEdit = { },
            onCopyOrMove = {},
            onExport = {},
            openLocation = {},
            openCallsign = {}
        )
    }
}

@Preview(showBackground = true, name = "BookmarkList Card")
@Composable
private fun PreviewBookmarkList() {
    val bookmarkList = BookmarkList(id = 1, name = "Shortwave", type = BookmarkListType.STATION, notes = "All international SW stations", color = 0xFF000050.toInt())
    RFAnalyzerTheme {
        BookmarkListCard(
            bookmarkList = bookmarkList,
            expanded = true,
            onExpand = {},
            onGotoBookmarkList = { },
            onEdit = { },
            onDelete = { },
            onCopyOrMove = { },
            onExport = {},
        )
    }
}

@Preview(showBackground = true, name = "Band Card - Expanded")
@Composable
private fun PreviewBandCardExpanded() {
    val band = Band(
        id = 1,
        name = "40m",
        bookmarkListId = 1,
        startFrequency = 7000000,
        endFrequency = 7300000,
        subBands = listOf(
            SubBand(name = "CW", startFrequency = 7000000, endFrequency = 7040000),
            SubBand(name = "SSB", startFrequency = 7050000, endFrequency = 7200000)
        ),
        notes = "40m Amateur Radio Band",
        favorite = true,
        createdAt = 0,
        updatedAt = 0
    )
    val bookmarkList = BookmarkList(id = 1, name = "Shortwave", type = BookmarkListType.BAND, notes = "All international SW stations", color = 0xFF000050.toInt())
    RFAnalyzerTheme {
        BandCard(
            band = band,
            bookmarkList = bookmarkList,
            expanded = true,
            selected = false,
            onExpand = {},
            selectMode = false,
            onSelectToggle = {},
            onTuneTo = {},
            onToggleFavorite = { },
            onDelete = { },
            onEdit = { },
            onCopyOrMove = { },
            onExport = {}
        )
    }
}

@Preview(showBackground = true, name = "Online Source Card")
@Composable
private fun PreviewOnlineSourceCard() {
    val info = OnlineStationProviderWithSettings(
        OnlineStationProvider.EIBI,
        OnlineStationProviderSettings(
            id = "EIBI",
            true,
            0,
            lastUpdatedTimestamp = 123000000
        )
    )
    RFAnalyzerTheme {
        OnlineSourceCard(
            providerWithSettings = info,
            downloadState = DownloadState.InProgress,
            expanded = true,
            onExpand = {},
            onActionDisplay = {},
            onActionStartDownload = {},
            onActionCancelDownload = {},
            onActionClear = {},
            onActionSetEnable = {},
            onAutoUpdateIntervalChanged = {},
            helpLink = ""
        )
    }
}
