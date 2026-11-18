package com.mantz_it.rfanalyzer.ui.screens

import com.mantz_it.rfanalyzer.location.LocationSheet
import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import android.content.res.Configuration
import android.util.Log
import android.view.ViewGroup
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.mantz_it.rfanalyzer.ui.tabs.AnalyzerTabs
import com.mantz_it.rfanalyzer.ui.tabs.AnalyzerTabsComposable
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.BookmarkFavoriteDialog
import com.mantz_it.rfanalyzer.ui.tabs.ControlDrawerSide
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.CopyOrMoveSheet
import com.mantz_it.rfanalyzer.ui.composable.CustomSideDrawerOverlay
import com.mantz_it.rfanalyzer.ui.composable.DrawerSide
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.EditBandBookmarkSheet
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.EditBookmarkListSheet
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.EditStationBookmarkSheet
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.ExportSheet
import com.mantz_it.rfanalyzer.ui.composable.FabAction
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.ui.composable.LicenseSheet
import com.mantz_it.rfanalyzer.ui.composable.TrialPill
import com.mantz_it.rfanalyzer.ui.composable.trialPillText
import com.mantz_it.rfanalyzer.ui.composable.OverlayIcon
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.StationCard

/**
 * <h1>RF Analyzer - Main Screen</h1>
 *
 * Module:      MainScreen.kt
 * Description: The main screen of the application which contains the AnalyzerSurface
 *              (FFT/Waterfall) and the Control Drawer which holds the AnalyzerTabs
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(analyzerSurface: AnalyzerSurface, viewModel: MainViewModel, appStateRepository: AppStateRepository) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    var selectedTab by rememberSaveable { mutableStateOf(AnalyzerTabs.SOURCE) }
    // A card's "Track on map" (MainViewModel.mapFocusRequest) opens the Digital tab; the tab itself
    // switches to its Map subtab and moves the camera.
    val pendingMapFocus by viewModel.mapFocusRequest.collectAsState()
    LaunchedEffect(pendingMapFocus) {
        if (pendingMapFocus != null) selectedTab = AnalyzerTabs.PACKETS
    }
    val controlDrawerSide by appStateRepository.controlDrawerSide.stateFlow.collectAsState()
    val selectedStations by viewModel.selectedStations.collectAsState()
    val expandedStationId by viewModel.expandedStationId.collectAsState()

    val isDrawerOpen = rememberSaveable { mutableStateOf(true) }
    // A tab requested from outside (the Digital Extension's launcher icon opens the Digital tab).
    // The drawer is opened too - a closed one would hide the tab.
    val pendingTab by viewModel.tabRequest.collectAsState()
    LaunchedEffect(pendingTab) {
        pendingTab?.let {
            selectedTab = it
            isDrawerOpen.value = true
            viewModel.consumeTabRequest()
        }
    }
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

    // The Digital tab is visible whenever digital features are shown (DigitalFeatures), also while
    // no digital mode runs: its history is stored (DigitalExtensionDatabase).
    val visibleTabs = AnalyzerTabs.entries.filter { it != AnalyzerTabs.PACKETS || DigitalFeatures.shown }

    // The trial's time left, always in sight while it runs (the FOSS build has no trial). The Base
    // Version has only the digital modes' trial.
    val isFullVersion by appStateRepository.isFullVersion.stateFlow.collectAsState()
    val isLicensed by appStateRepository.isLicensed.stateFlow.collectAsState()
    val trialSecondsLeft by appStateRepository.trialSecondsLeft.stateFlow.collectAsState()
    val digitalTrialSecondsLeft by appStateRepository.digitalTrialSecondsLeft.stateFlow.collectAsState()

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
                            // The surface (created in MainActivity) outlives the composition: detach it from its previous parent
                            (analyzerSurface.parent as? ViewGroup)?.removeView(analyzerSurface)
                            analyzerSurface
                        },
                        modifier = Modifier
                            .fillMaxSize()
                    )
                }
            },
            drawerHeader = {
                AnimatedContent(targetState = visibleTabs, label = "tabs") { tabs ->
                    PrimaryScrollableTabRow(
                        selectedTabIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
                        minTabWidth = 48.dp
                    ) {
                        tabs.forEach { tab ->
                            Tab(
                                selected = selectedTab == tab,
                                onClick = { selectedTab = tab }
                            ) {
                                Row(
                                    modifier = Modifier
                                        .height(48.dp)
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(tab.icon, contentDescription = tab.displayName)
                                    if (selectedTab == tab) {
                                        Spacer(Modifier.width(4.dp))
                                        Text(tab.displayName)
                                    }
                                }
                            }
                        }
                    }
                }
            },
            drawerContent = {
                if (selectedTab !in visibleTabs) {
                    selectedTab = AnalyzerTabs.DEMODULATION
                }
                AnalyzerTabsComposable(
                    mainViewModel = viewModel,
                    appStateRepository = appStateRepository,
                    sourceTabActions = viewModel.sourceTabActions,
                    displayTabActions = viewModel.displayTabActions,
                    demodulationTabActions = viewModel.demodulationTabActions,
                    recordingTabActions = viewModel.recordingTabActions,
                    settingsTabActions = viewModel.settingsTabActions,
                    aboutTabActions = viewModel.aboutTabActions,
                    digitalTabActions = viewModel.digitalTabActions,
                    selectedTab = selectedTab
                )
            },
            isDrawerOpen = isDrawerOpen.value,
            onDismiss = { isDrawerOpen.value = false },
            onOpen = { isDrawerOpen.value = true },
            // the Base Version's pill is the digital trial's alone
            floatingInfo = if (BuildConfig.IS_FOSS || isFullVersion || (isLicensed && !DigitalFeatures.shown)) null else { {
                TrialPill(trialPillText(trialSecondsLeft.takeIf { !isLicensed }, digitalTrialSecondsLeft.takeIf { DigitalFeatures.shown }), onClick = viewModel::showTrialInfo)
            } },
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
        val licenseSheetOpen by viewModel.licenseSheetOpen.collectAsState()
        if (licenseSheetOpen) {
            val billing by viewModel.billingState.collectAsState()
            val license by appStateRepository.license.stateFlow.collectAsState()
            val licenseSheetMessage by viewModel.licenseSheetMessage.collectAsState()
            LicenseSheet(
                billing = billing,
                license = license,
                trialSecondsLeft = trialSecondsLeft,
                digitalTrialSecondsLeft = digitalTrialSecondsLeft.takeIf { DigitalFeatures.shown },
                extensionsShown = DigitalFeatures.shown,
                message = licenseSheetMessage,
                onBuy = viewModel::onLicenseSheetBuy,
                onRetry = viewModel::retryBilling,
                onTermsClicked = {
                    viewModel.dismissLicenseSheet()
                    viewModel.openManual("legal/terms.html")
                },
                onDismiss = viewModel::dismissLicenseSheet,
            )
        }
        val locationSheetReason by viewModel.locationSheetReason.collectAsState()
        locationSheetReason?.let { reason ->
            val location by viewModel.userLocation.collectAsState()
            val guessedCountry by viewModel.guessedCountry.collectAsState()
            val chosenArea by appStateRepository.locationArea.stateFlow.collectAsState()
            val locator by appStateRepository.locationLocator.stateFlow.collectAsState()
            LocationSheet(
                location = location,
                guessedCountry = guessedCountry,
                automatic = chosenArea.isEmpty(),
                locatorInput = locator,
                actions = viewModel.locationSheetActions,
                onDismiss = viewModel::dismissLocationSheet,
                reason = reason.ifEmpty { null },
            )
        }
        if (selectedStations.isNotEmpty()) {
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
                            onTuneTo = { viewModel.dismissStationSheet(); viewModel.tuneToStation(station) },
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
                onExport = { uri -> viewModel.exportStation(stationWithBookmarkList, uri); stationToExport = null },
                buildExportJson = { viewModel.buildStationExportJson(stationWithBookmarkList) },
                onDismiss = { stationToExport = null },
            )
        }
    }
}