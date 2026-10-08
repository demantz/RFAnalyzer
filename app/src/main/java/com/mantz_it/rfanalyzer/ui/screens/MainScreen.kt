package com.mantz_it.rfanalyzer.ui.screens

import android.content.res.Configuration
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.ListItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.BookmarkList
import com.mantz_it.rfanalyzer.database.BookmarkListType
import com.mantz_it.rfanalyzer.database.StationWithBookmarkList
import com.mantz_it.rfanalyzer.ui.AnalyzerSurface
import com.mantz_it.rfanalyzer.ui.AppScreen
import com.mantz_it.rfanalyzer.ui.MainViewModel
import com.mantz_it.rfanalyzer.ui.composable.AnalyzerTabsComposable
import com.mantz_it.rfanalyzer.ui.composable.BookmarkFavoriteDialog
import com.mantz_it.rfanalyzer.ui.composable.ControlDrawerSide
import com.mantz_it.rfanalyzer.ui.composable.CopyOrMoveSheet
import com.mantz_it.rfanalyzer.ui.composable.CustomSideDrawerOverlay
import com.mantz_it.rfanalyzer.ui.composable.DecoderStatusBadge
import com.mantz_it.rfanalyzer.ui.composable.DrawerSide
import com.mantz_it.rfanalyzer.ui.composable.EditBandBookmarkSheet
import com.mantz_it.rfanalyzer.ui.composable.EditBookmarkListSheet
import com.mantz_it.rfanalyzer.ui.composable.EditStationBookmarkSheet
import com.mantz_it.rfanalyzer.ui.composable.ExportSheet
import com.mantz_it.rfanalyzer.ui.composable.FabAction
import com.mantz_it.rfanalyzer.ui.composable.OverlayIcon
import com.mantz_it.rfanalyzer.ui.composable.StationCard

/**
 * <h1>RF Analyzer - Main Screen</h1>
 *
 * Module:      MainScreen.kt
 * Description: The main screen of the application which contains the AnalyzerSurface (FFT/Waterfall)
 * and the Control Drawer which holds the AnalyzerTabs
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(analyzerSurface: AnalyzerSurface, viewModel: MainViewModel, appStateRepository: AppStateRepository) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val controlDrawerSide by appStateRepository.controlDrawerSide.stateFlow.collectAsState()
    val decoderEnabled by appStateRepository.decoderEnabled.stateFlow.collectAsState()
    val decoderRunning by appStateRepository.decoderRunning.stateFlow.collectAsState()
    val decoderLastModel by appStateRepository.decoderLastModel.stateFlow.collectAsState()
    val decoderLastEventTimestamp by appStateRepository.decoderLastEventTimestamp.stateFlow.collectAsState()
    val selectedStations by viewModel.selectedStations.collectAsState()
    val expandedStationId by viewModel.expandedStationId.collectAsState()

    val isDrawerOpen = rememberSaveable { mutableStateOf(true) }
    var showFavoritesDialog by remember { mutableStateOf(false) }
    var showStationFavorites by remember { mutableStateOf(true) } // selected tab inside the favorites dialog

    val editedStation by viewModel.editedStation.collectAsState()
    val editedStationHasChanges by viewModel.editedStationHasChanges.collectAsState()
    var stationToCopyOrMove: StationWithBookmarkList? by remember { mutableStateOf(null) }
    var stationToExport: StationWithBookmarkList? by remember { mutableStateOf(null) }
    val editedBand by viewModel.editedBand.collectAsState()
    val editedBandHasChanges by viewModel.editedBandHasChanges.collectAsState()
    var editedBookmarkList: BookmarkList? by remember { mutableStateOf(null) }
    val allBookmarkLists by viewModel.allBookmarkLists.collectAsState()
    val stationFavorites by viewModel.stationFavorites.collectAsState()
    val bandFavorites by viewModel.bandFavorites.collectAsState()

    Box {
        CustomSideDrawerOverlay(
            drawerSide = if(isLandscape) {
                if (controlDrawerSide == ControlDrawerSide.RIGHT) DrawerSide.RIGHT
                else DrawerSide.LEFT
            } else DrawerSide.BOTTOM,
            content = {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { _ ->
                            Log.d("MainScreen", "AndroidView.factory: reusing surface: ${System.identityHashCode(analyzerSurface)} (parent: ${analyzerSurface.parent})")
                            // Detach from previous parent if it exists
                            (analyzerSurface.parent as? ViewGroup)?.removeView(analyzerSurface)
                            // Return analyzer surface:
                            analyzerSurface
                        },
                        modifier = Modifier
                            .fillMaxSize()
                    )
                    DecoderStatusBadge(
                        enabled = decoderEnabled,
                        running = decoderRunning,
                        lastModel = decoderLastModel,
                        lastEventTimestamp = decoderLastEventTimestamp,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp)
                    )
                }
            },
            drawerContent = { AnalyzerTabsComposable(
                mainViewModel = viewModel,
                appStateRepository = appStateRepository,
                sourceTabActions = viewModel.sourceTabActions,
                displayTabActions = viewModel.displayTabActions,
                demodulationTabActions = viewModel.demodulationTabActions,
                decoderTabActions = viewModel.decoderTabActions,
                recordingTabActions = viewModel.recordingTabActions,
                settingsTabActions = viewModel.settingsTabActions,
                aboutTabActions = viewModel.aboutTabActions,
            ) },
            isDrawerOpen = isDrawerOpen.value,
            onDismiss = { isDrawerOpen.value = false },
            onOpen = { isDrawerOpen.value = true },
            fabActions = listOf(
                FabAction(icon = Icons.Default.MenuBook, "Bookmark Manager", onClick = { viewModel.navigate(AppScreen.BookmarkManagerScreen()) }),
                FabAction(icon = Icons.Default.Add, "Add Station Bookmark", onClick = { viewModel.showEditStationBookmarkSheet(null) }),
                FabAction(icon = Icons.Default.Add, "Add Band Bookmark", onClick = { viewModel.showEditBandBookmarkSheet(null) }),
                FabAction(icon = Icons.Default.Favorite,
                    "Favorites",
                    customIconComposable = {
                        OverlayIcon(
                            Icons.Default.MenuBook,
                            Icons.Default.Favorite,
                            contentDescription = "Favorites",
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            mainIconSize = 32.dp,
                            badgeIconSize = 12.dp,
                            badgePadding = 3.dp,
                            badgeTint = Color.Red,
                            badgeAlignment = Alignment.CenterStart
                        )
                    },
                    onClick = { showFavoritesDialog = true }
                ),
            )
        )

        // Dialogs:
        if(showFavoritesDialog) {
            BookmarkFavoriteDialog(
                stationFavorites = stationFavorites,
                bandFavorites = bandFavorites,
                showStationFavoritesInitially = showStationFavorites,
                onTabChanged = { showStationFavorites = it },
                onOpenBookmarksClicked = { showFavoritesDialog = false; viewModel.navigate(AppScreen.BookmarkManagerScreen()) },
                onAddStationBookmarkClicked = { viewModel.showEditStationBookmarkSheet(null) },
                onAddBandBookmarkClicked = { viewModel.showEditBandBookmarkSheet(null) },
                onTuneToStation = { showFavoritesDialog = false; viewModel.tuneToStation(it) },
                onViewBand = { showFavoritesDialog = false; viewModel.moveViewportToBand(it) },
                onDismiss = { showFavoritesDialog = false }
            )
        }

        // Bottom Sheets:
        val tmpEditedStation = editedStation
        if (tmpEditedStation != null) {
            EditStationBookmarkSheet(
                station = tmpEditedStation,
                bookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.STATION },
                isUnsafed = editedStationHasChanges,
                onSave = { station -> viewModel.insertStation(station) },
                onSaveTmp = { viewModel.setEditedStationHasChanges(true); viewModel.showEditStationBookmarkSheet(it) },
                onDismiss = { viewModel.dismissEditStationBookmarkSheet(); viewModel.setEditedStationHasChanges(false) },
                onCreateNewBookmarkList = { editedBookmarkList = BookmarkList(name = "", type = BookmarkListType.STATION) }
            )
        }
        val tmpEditedBand = editedBand
        if (tmpEditedBand != null) {
            EditBandBookmarkSheet(
                band = tmpEditedBand,
                bookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.BAND },
                isUnsafed = editedBandHasChanges,
                onSave = { band -> viewModel.insertBand(band) },
                onSaveTmp = { viewModel.setEditedBandHasChanges(true); viewModel.showEditBandBookmarkSheet(it) },
                onDismiss = { viewModel.dismissEditBandBookmarkSheet(); viewModel.setEditedBandHasChanges(false) },
                onCreateNewBookmarkList = { editedBookmarkList = BookmarkList(name = "", type = BookmarkListType.BAND) }
            )
        }
        val tmpEditedBookmarkList = editedBookmarkList
        if (tmpEditedBookmarkList != null) {
            EditBookmarkListSheet(
                bookmarkList = tmpEditedBookmarkList,
                onSave = { bookmarkList -> viewModel.insertBookmarkList(bookmarkList) },
                onDismiss = { editedBookmarkList = null }
            )
        }
        if (selectedStations.isNotEmpty()) {
            if (selectedStations.size == 1)
                viewModel.expandStation(selectedStations.first().station)
            ModalBottomSheet(
                onDismissRequest = { viewModel.dismissStationSheet() }
            ) {
                LazyColumn {
                    items(selectedStations) { (station, bookmarkList) ->
                        StationCard(
                            station = station,
                            bookmarkList = bookmarkList,
                            expanded = expandedStationId == station.id,
                            selected = false,
                            onExpand = { viewModel.expandStation(station) },
                            selectMode = false,
                            onSelectToggle = { },
                            onTuneTo = { viewModel.tuneToStation(station) },
                            onToggleFavorite = { viewModel.updateStation(station.copy(favorite = !station.favorite)) },
                            onDelete = { viewModel.deleteStation(station) },
                            onEdit = { viewModel.showEditStationBookmarkSheet(station) },
                            onCopyOrMove = { stationToCopyOrMove = StationWithBookmarkList(station, bookmarkList) },
                            openCallsign = viewModel::openCallsign,
                            openLocation = viewModel::openLocation,
                            onExport = { stationToExport = StationWithBookmarkList(station, bookmarkList) },
                        )
                    }
                }
            }
        }

        stationToCopyOrMove?.let { stationWithBookmarkList ->
            CopyOrMoveSheet(
                itemsWithBookmarkList = listOf(stationWithBookmarkList),
                itemType = BookmarkListType.STATION,
                bookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.STATION },
                onCopy = { targetBookmarkList -> viewModel.copyStation(stationWithBookmarkList.station, targetBookmarkList); stationToCopyOrMove = null },
                onMove = { targetBookmarkList -> viewModel.moveStation(stationWithBookmarkList.station, targetBookmarkList); stationToCopyOrMove = null },
                onDismiss = { stationToCopyOrMove = null }
            )
        }

        stationToExport?.let { stationWithBookmarkList ->
            ExportSheet(
                itemsWithBookmarkList = listOf(stationWithBookmarkList),
                itemType = BookmarkListType.STATION,
                onExport = { uri -> viewModel.exportStation(stationWithBookmarkList, uri); stationToCopyOrMove = null },
                onDismiss = { stationToExport = null },
            )
        }
    }
}