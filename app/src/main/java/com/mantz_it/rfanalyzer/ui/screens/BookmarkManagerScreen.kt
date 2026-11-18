package com.mantz_it.rfanalyzer.ui.screens

import com.mantz_it.rfanalyzer.location.ItuRegion
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.paging.compose.collectAsLazyPagingItems
import com.mantz_it.rfanalyzer.database.*
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.BandFilterRow
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.StationCard
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.BandCard
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.BookmarkListCard
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.CopyOrMoveSheet
import com.mantz_it.rfanalyzer.ui.composable.DEFAULT_MIN_BOX_HEIGHT
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.OnlineSourceCard
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.EditBandBookmarkSheet
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.EditBookmarkListSheet
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.EditStationBookmarkSheet
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.ExportSheet
import com.mantz_it.rfanalyzer.ui.composable.IconFrequencyBand
import com.mantz_it.rfanalyzer.ui.composable.IconFrequencyBandOpen
import com.mantz_it.rfanalyzer.ui.composable.LazyColumnWithScrollAwayHeaderElement
import com.mantz_it.rfanalyzer.ui.composable.LocalShowHelp
import com.mantz_it.rfanalyzer.ui.composable.OutlinedEnumDropDown
import com.mantz_it.rfanalyzer.ui.composable.OutlinedListDropDown
import com.mantz_it.rfanalyzer.ui.composable.SettingsActionRow
import com.mantz_it.rfanalyzer.ui.composable.SettingsInfoRow
import com.mantz_it.rfanalyzer.ui.composable.SettingsSection
import com.mantz_it.rfanalyzer.ui.composable.SettingsSwitchRow
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.StationsFilterRow
import com.mantz_it.rfanalyzer.ui.composable.rememberCreateFilePicker
import com.mantz_it.rfanalyzer.ui.composable.rememberOpenFilePicker
import com.mantz_it.rfanalyzer.ui.composable.rememberScrollAwayHeaderState

/**
 * <h1>RF Analyzer - BookmarkManagerScreen</h1>
 *
 * Module:      BookmarkManagerScreen.kt
 * Description: Screen for displaying Bookmarks. Also called the 'Bookmark Manager' inside the App
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

data class BookmarkManagerScreenActions(
    val tuneTo: ((Station) -> Boolean),
    val moveViewportToBand: ((Band) -> Boolean),
    val openLocation: ((Station) -> Unit),
    val openCallsign: ((callsign: String) -> Unit),
    val startStationProviderDownload: ((OnlineStationProvider) -> Unit),
    val cancelStationProviderDownload: ((OnlineStationProvider) -> Unit),
    val showBookmarkManagerTutorial: (() -> Unit),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarkManagerScreen(
    viewModel: BookmarkManagerViewModel,
    bookmarkManagerScreenActions: BookmarkManagerScreenActions,
    onPopBackStack: () -> Unit,
) {
    val page by viewModel.page.collectAsState()
    val tempBackstackNavigationEnabled = viewModel.tempBackNavigationEnabled

    val displayStationsInFft by viewModel.displayStationsInFft.collectAsState()
    val displayBandsInFft by viewModel.displayBandsInFft.collectAsState()
    val useCustomFftStationFilter by viewModel.useCustomFftStationFilter.collectAsState()
    val useCustomFftBandFilter by viewModel.useCustomFftBandFilter.collectAsState()
    val legacyDbAvailable by viewModel.legacyDbAvailable.collectAsState()
    val displayedImportLegacyDialog by viewModel.displayedLegacyImportDialog.collectAsState()
    val displayDefaultImportDialog by viewModel.displayDefaultImportDialog.collectAsState()

    val stationFilterForList by viewModel.stationFilterForList.collectAsState()
    val stationFilterForFft by viewModel.stationFilterForFft.collectAsState()
    val filteredStationsInFftCount by viewModel.filteredStationsInFftCount.collectAsState()
    val bandFilterForList by viewModel.bandFilterForList.collectAsState()
    val bandFilterForFft by viewModel.bandFilterForFft.collectAsState()
    val filteredBandsInFftCount by viewModel.filteredBandsInFftCount.collectAsState()
    val bookmarkListFilterSearchString by viewModel.bookmarkListFilterSearchString.collectAsState()
    val selectMode = viewModel.selectMode
    val selectedStations = viewModel.selectedStations
    val allVisibleStationsSelected by viewModel.allVisibleStationsSelected.collectAsState()
    val selectedBands = viewModel.selectedBands
    val allVisibleBandsSelected by viewModel.allVisibleBandsSelected.collectAsState()
    val expandedStation = viewModel.expandedStation
    val expandedBand = viewModel.expandedBand
    val expandedBookmarkList = viewModel.expandedBookmarkList
    val expandedOnlineStationProvider = viewModel.expandedOnlineStationProvider
    val itemsToCopyOrMove = viewModel.itemsToCopyOrMove
    val itemsToExport = viewModel.itemsToExport

    var filterRowExpanded by remember { mutableStateOf(false) }
    var restoreWarningDialogShown by remember { mutableStateOf(false) }

    val allBookmarkLists by viewModel.allBookmarkLists.collectAsState(initial = emptyList())
    val stationsWithBookmarkListPagingItems = viewModel.filteredStationsPaging.collectAsLazyPagingItems()
    val bandsWithBookmarkList by viewModel.filteredBands.collectAsState(initial = emptyList())
    val bookmarkListScreenItems by viewModel.bookmarkListScreenItems.collectAsState(initial = emptyList())

    val parsedImportResult by viewModel.parsedImportResult.collectAsState()

    val editedStation by viewModel.editedStation.collectAsState()
    val editedBand by viewModel.editedBand.collectAsState()
    val editedBookmarkList by viewModel.editedBookmarkList.collectAsState()
    val editedStationHasChanges by viewModel.editedStationHasChanges.collectAsState()
    val editedBandHasChanges by viewModel.editedBandHasChanges.collectAsState()

    val bookmarkListScrollState = rememberScrollAwayHeaderState()
    val stationsScrollState = rememberScrollAwayHeaderState()
    val bandsScrollState = rememberScrollAwayHeaderState()

    BackHandler(enabled = tempBackstackNavigationEnabled) {
        viewModel.setPage(StationsPage.BOOKMARKLISTS) // also disables the temp backstack behavior
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarEvent.collect { snackbarEvent ->
            val result = snackbarHostState.showSnackbar(
                message = snackbarEvent.message,
                actionLabel = snackbarEvent.buttonText,
                duration = SnackbarDuration.Short
            )
            snackbarEvent.callback?.let { it(result) }
        }
    }

    // When the station filter changes, scroll to the top (otherwise bad performance for large result sets. e.g. eibi)
    LaunchedEffect(stationFilterForList) {
        stationsScrollState.listState.scrollToItem(0, 0)
    }

    val exportBackupFileChooser = rememberCreateFilePicker(
        suggestedFileName = "RFAnalyzer_Stations_Backup.json",
        mimeType = "application/json",
        onAbort = { },
        onFileCreated = { uri -> viewModel.exportBackup(uri) }
    )
    val importBackupFileChooser = rememberOpenFilePicker(
        mimeTypes = arrayOf("application/json"),
        onAbort = { },
        onFileChosen = { uri, _ -> viewModel.importBackup(uri) }
    )

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(page.displayName) },
                navigationIcon = {
                    IconButton(onClick = onPopBackStack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if(page in setOf(StationsPage.BOOKMARKLISTS, StationsPage.SETTINGS)) {
                        val showHelp = LocalShowHelp.current
                        val helpLink = when(page) {
                            StationsPage.BOOKMARKLISTS -> "bookmarks.html#bookmark-manager"
                            StationsPage.SETTINGS -> "bookmarks.html#settings"
                            else -> ""
                        }
                        IconButton(onClick = { showHelp(helpLink) }) { Icon(Icons.Default.Help, contentDescription = "Help") }
                    }
                    if(page in setOf(StationsPage.STATIONS, StationsPage.BANDS)) {
                        if (selectMode) {
                            IconButton(onClick = viewModel::deleteSelected) { Icon(Icons.Default.Delete, contentDescription = "Delete") }
                            IconButton(onClick = {
                                when (page) {
                                    StationsPage.STATIONS -> {
                                        viewModel.setItemsToExportFromSelection(BookmarkListType.STATION)
                                    }
                                    StationsPage.BANDS -> {
                                        viewModel.setItemsToExportFromSelection(BookmarkListType.BAND)
                                    }
                                    else -> {}
                                }
                            }) { Icon(Icons.Default.Upload, contentDescription = "Export") }
                            IconButton(onClick = {
                                when (page) {
                                    StationsPage.STATIONS -> {
                                        viewModel.setItemsToCopyOrMoveFromSelection(BookmarkListType.STATION)
                                    }
                                    StationsPage.BANDS -> {
                                        viewModel.setItemsToCopyOrMoveFromSelection(BookmarkListType.BAND)
                                    }
                                    else -> {}
                                }
                            }) { Icon(Icons.Default.FileCopy, contentDescription = "Copy/Move") }
                            if (page == StationsPage.STATIONS) {
                                if (allVisibleStationsSelected)
                                    IconButton(onClick = viewModel::clearSelectionStation) { Icon(imageVector = Icons.Default.Deselect, contentDescription = "Deselect all") }
                                else
                                    IconButton(onClick = viewModel::selectAllVisibleStations) { Icon(imageVector = Icons.Default.SelectAll, contentDescription = "Select all") }
                            } else {
                                if (allVisibleBandsSelected)
                                    IconButton(onClick = viewModel::clearSelectionBand) { Icon(imageVector = Icons.Default.Deselect, contentDescription = "Deselect all") }
                                else
                                    IconButton(onClick = viewModel::selectAllVisibleBands) { Icon(imageVector = Icons.Default.SelectAll, contentDescription = "Select all") }
                            }
                        }
                        IconButton(onClick = { viewModel.toggleSelectMode() }) {
                            Icon(Icons.Default.Checklist,
                                contentDescription = "Select mode",
                                modifier = if(selectMode) Modifier.background(MaterialTheme.colorScheme.primary) else Modifier,
                                tint = if(selectMode) MaterialTheme.colorScheme.onPrimary else LocalContentColor.current)
                        }
                    }
                }
            )
        },
        bottomBar = { if (!isLandscape) {
            NavigationBar {
                StationsPage.entries.forEach {
                    NavigationBarItem(
                        selected = it == page,
                        onClick = { viewModel.setPage(it) },
                        icon = { Icon(it.imageVector, it.displayName, modifier = Modifier.size(28.dp)) },
                        label = { Text(it.displayName, style = MaterialTheme.typography.labelLarge) }
                    )
                }
            }
        } },
        modifier = Modifier.fillMaxSize()
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            Row {
                // Navigation Rail in landscape:
                if (isLandscape) {
                    NavigationRail {
                        StationsPage.entries.forEach {
                            NavigationRailItem(
                                selected = it == page,
                                onClick = { viewModel.setPage(it) },
                                icon = { Icon(it.imageVector, it.displayName, modifier = Modifier.size(28.dp)) },
                                label = { Text(it.displayName, style = MaterialTheme.typography.labelLarge) }
                            )
                        }
                    }
                }
                // Content area
                when (page) {
                    StationsPage.BOOKMARKLISTS -> {
                        LazyColumnWithScrollAwayHeaderElement(
                            state = bookmarkListScrollState,
                            lazyColumnContentPaddingValues = PaddingValues(bottom = 96.dp),
                            headerElement = {
                                OutlinedTextField(
                                    value = bookmarkListFilterSearchString,
                                    onValueChange = { viewModel.setBookmarkListFilterSearchString(it) },
                                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                                    trailingIcon = {
                                        if (bookmarkListFilterSearchString.isNotEmpty()) {
                                            IconButton(onClick = { viewModel.setBookmarkListFilterSearchString("") }) {
                                                Icon(Icons.Default.Clear, contentDescription = "Clear")
                                            }
                                        }
                                    },
                                    placeholder = { Text("Search in Name and Notes ...") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        ) {
                            items(
                                items = bookmarkListScreenItems,
                                key = {
                                    when (it) {
                                        is BookmarkListListItem.Header -> it.title
                                        is BookmarkListListItem.OnlineSource -> it.providerWithSettings.provider.name
                                        is BookmarkListListItem.BookmarkListItem -> it.bookmarkList.id
                                    }
                                }
                            ) { item ->
                                when (item) {
                                    is BookmarkListListItem.Header -> {
                                        Row(verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                                .clickable { item.toggle() }
                                                .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 8.dp)
                                        ) {
                                            Icon(item.icon, contentDescription = item.title)
                                            Text(
                                                text = item.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                modifier = Modifier.align(Alignment.CenterVertically).padding(start = 8.dp).weight(1f)
                                            )
                                            Icon(
                                                imageVector = Icons.Default.ExpandMore,
                                                contentDescription = null,
                                                modifier = Modifier.rotate(if (item.expanded) 180f else 0f)
                                            )
                                        }
                                    }
                                    is BookmarkListListItem.OnlineSource -> {
                                        val downloadState by viewModel.onlineStationProviderDownloadStates.getValue(item.providerWithSettings.provider).stateFlow.collectAsState()
                                        OnlineSourceCard(
                                            providerWithSettings = item.providerWithSettings,
                                            downloadState = downloadState,
                                            expanded = expandedOnlineStationProvider == item.providerWithSettings.provider.dbId,
                                            onExpand = { viewModel.expandOnlineStationProvider(item.providerWithSettings.provider) },
                                            onActionDisplay = {
                                                viewModel.setPageAndEnableTempBackNavigation(StationsPage.STATIONS)
                                                viewModel.setStationFilterForList(
                                                    viewModel.stationFilterForList.value.copy(
                                                        bookmarkLists = emptySet(),
                                                        sources = setOf(item.providerWithSettings.provider.type)
                                                    )
                                                )
                                            },
                                            onActionSetEnable = { viewModel.setOnlineStationProviderAutoUpdateEnable(item.providerWithSettings.provider, it) },
                                            onAutoUpdateIntervalChanged = { viewModel.setOnlineStationProviderAutoUpdateInterval(item.providerWithSettings.provider, it) },
                                            onActionStartDownload = { bookmarkManagerScreenActions.startStationProviderDownload(item.providerWithSettings.provider) },
                                            onActionCancelDownload = { bookmarkManagerScreenActions.cancelStationProviderDownload(item.providerWithSettings.provider) },
                                            onActionClear = { viewModel.clearOnlineCache(item.providerWithSettings.provider) },
                                            helpLink = when(item.providerWithSettings.provider) {
                                                OnlineStationProvider.EIBI -> "bookmarks.html#eibi-database"
                                                OnlineStationProvider.POTA -> "bookmarks.html#pota-spots"
                                                OnlineStationProvider.SOTA -> "bookmarks.html#sota-spots"
                                                OnlineStationProvider.DIGITAL_STATIONS -> "bookmarks.html#digital-stations"
                                            }
                                        )
                                    }
                                    is BookmarkListListItem.BookmarkListItem -> {
                                        BookmarkListCard(
                                            bookmarkList = item.bookmarkList,
                                            expanded = item.bookmarkList.id == expandedBookmarkList,
                                            onExpand = { viewModel.expandBookmarkList(item.bookmarkList) },
                                            onGotoBookmarkList = {
                                                if (item.bookmarkList.type == BookmarkListType.STATION) {
                                                    viewModel.setPageAndEnableTempBackNavigation(StationsPage.STATIONS)
                                                    viewModel.setStationFilterForList(
                                                        viewModel.stationFilterForList.value.copy(
                                                            bookmarkLists = setOf(item.bookmarkList.id),
                                                            sources = setOf(SourceProvider.BOOKMARK)
                                                        )
                                                    )
                                                } else {
                                                    viewModel.setPageAndEnableTempBackNavigation(StationsPage.BANDS)
                                                    viewModel.setBandFilterForList(
                                                        viewModel.bandFilterForList.value.copy(
                                                            bookmarkLists = setOf(item.bookmarkList.id)
                                                        )
                                                    )
                                                }
                                            },
                                            onEdit = {
                                                viewModel.setEditedBookmarkList(item.bookmarkList)
                                            },
                                            onDelete = { viewModel.requestDeleteBookmarkList(item.bookmarkList) },
                                            onCopyOrMove = { viewModel.setItemsToCopyOrMoveFromBookmarkList(item.bookmarkList) },
                                            onExport = { viewModel.setItemsToExportFromBookmarkList(item.bookmarkList) },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    StationsPage.STATIONS -> {
                        LazyColumnWithScrollAwayHeaderElement(
                            state = stationsScrollState,
                            lazyColumnContentPaddingValues = PaddingValues(bottom = 96.dp),
                            headerElement = {
                                StationsFilterRow(
                                    filter = stationFilterForList,
                                    allBookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.STATION },
                                    expanded = filterRowExpanded,
                                    onExpandChange = { filterRowExpanded = !filterRowExpanded },
                                    onFilterChanged = { viewModel.setStationFilterForList(it) },
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        ) {
                            items(
                                stationsWithBookmarkListPagingItems.itemCount,
                                key = { index -> stationsWithBookmarkListPagingItems[index]?.station?.id ?: index }
                            ) { index ->
                                val item = stationsWithBookmarkListPagingItems[index]
                                if(item != null) {
                                    val (station, bookmarkList) = item
                                    StationCard(
                                        station = station,
                                        bookmarkList = bookmarkList,
                                        expanded = expandedStation == station.id,
                                        selected = selectedStations.contains(station.id),
                                        onExpand = { viewModel.expandStation(station) },
                                        selectMode = selectMode,
                                        onSelectToggle = { viewModel.selectToggleStation(station) },
                                        onTuneTo = {
                                            if (bookmarkManagerScreenActions.tuneTo(station))
                                                onPopBackStack()
                                        },
                                        onToggleFavorite = { viewModel.updateStation(station.copy(favorite = !station.favorite)) },
                                        onDelete = { viewModel.deleteStation(station) },
                                        onEdit = {
                                            viewModel.setEditedStationHasChanges(false)
                                            viewModel.setEditedStation(station)
                                        },
                                        onCopyOrMove = {
                                            viewModel.setItemsToCopyOrMove(listOf(StationWithBookmarkList(station, bookmarkList)), BookmarkListType.STATION)
                                        },
                                        openLocation = bookmarkManagerScreenActions.openLocation,
                                        openCallsign = bookmarkManagerScreenActions.openCallsign,
                                        onExport = {
                                            viewModel.setItemsToExport(listOf(StationWithBookmarkList(station, bookmarkList)), BookmarkListType.STATION)
                                        },
                                    )
                                }
                            }
                        }
                    }
                    StationsPage.BANDS -> {
                        LazyColumnWithScrollAwayHeaderElement(
                            state = bandsScrollState,
                            lazyColumnContentPaddingValues = PaddingValues(bottom = 96.dp),
                            headerElement = {
                                BandFilterRow(
                                    filter = bandFilterForList,
                                    allBookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.BAND },
                                    expanded = filterRowExpanded,
                                    onExpandChange = {filterRowExpanded = !filterRowExpanded},
                                    onFilterChanged = { viewModel.setBandFilterForList(it) },
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        ) {
                            items(bandsWithBookmarkList, key = { it.band.id }) { (band, bookmarkList) ->
                                BandCard(
                                    band = band,
                                    bookmarkList = bookmarkList,
                                    expanded = expandedBand == band.id,
                                    selected = selectedBands.contains(band.id),
                                    onExpand = { viewModel.expandBand(band) },
                                    selectMode = selectMode,
                                    onSelectToggle = { viewModel.selectToggleBand(band) },
                                    onTuneTo = {
                                        if (bookmarkManagerScreenActions.moveViewportToBand(band))
                                            onPopBackStack()
                                    },
                                    onToggleFavorite = { viewModel.updateBand(band.copy(favorite = !band.favorite)) },
                                    onDelete = { viewModel.deleteBand(band) },
                                    onEdit = {
                                        viewModel.setEditedBandHasChanges(false)
                                        viewModel.setEditedBand(band)
                                    },
                                    onCopyOrMove = { viewModel.setItemsToCopyOrMove(listOf(BandWithBookmarkList(band, bookmarkList)), BookmarkListType.BAND) },
                                    onExport = {
                                        viewModel.setItemsToExport(listOf(BandWithBookmarkList(band, bookmarkList)), BookmarkListType.BAND)
                                    },
                                )
                            }
                        }
                    }
                    StationsPage.SETTINGS -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(vertical = 8.dp)
                        ) {

                            // --- Section: Display Settings -------------------------------------------------
                            item {
                                SettingsSection(title = "Display Stations")
                            }
                            item {
                                SettingsSwitchRow(
                                    title = "Show Stations in FFT",
                                    description = "Display station labels inside the FFT spectrum view",
                                    isChecked = displayStationsInFft,
                                    onCheckedChange = viewModel::setDisplayStationsInFft
                                )
                            }
                            if (displayStationsInFft) {
                                item {
                                    SettingsSwitchRow(
                                        title = "Custom Filter for FFT",
                                        description = "Enable to specify a custom filter for the station labels in the FFT spectrum view (independent of the filter in the Bookmark Manager list)",
                                        isChecked = useCustomFftStationFilter,
                                        onCheckedChange = viewModel::setUseCustomFftStationFilter
                                    )
                                }
                                item {
                                    AnimatedVisibility(
                                        visible = useCustomFftStationFilter,
                                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 40.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                                        ) {
                                            Text("Configure a filter for only the FFT display (labels in the FFT spectrum view will only show if matched by this filter):",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                            StationsFilterRow(
                                                filter = stationFilterForFft,
                                                allBookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.STATION },
                                                expanded = true,
                                                onExpandChange = { },
                                                onFilterChanged = { viewModel.setStationFilterForFft(it) },
                                                alwaysExpanded = true,
                                            )
                                            Text("Filter matches $filteredStationsInFftCount station bookmarks.")
                                        }
                                    }
                                }
                            }

                            item {
                                SettingsSection(title = "Display Bands")
                            }
                            item {
                                SettingsSwitchRow(
                                    title = "Show Bands in FFT",
                                    description = "Display band labels inside the FFT spectrum view",
                                    isChecked = displayBandsInFft,
                                    onCheckedChange = viewModel::setDisplayBandsInFft
                                )
                            }
                            if (displayBandsInFft) {
                                item {
                                    SettingsSwitchRow(
                                        title = "Custom Filter for FFT",
                                        description = "Enable to specify a custom filter for the band labels in the FFT spectrum view (independent of the filter in the Bookmark Manager list)",
                                        isChecked = useCustomFftBandFilter,
                                        onCheckedChange = viewModel::setSynchronizeBandFilters
                                    )
                                }
                                item {
                                    AnimatedVisibility(
                                        visible = useCustomFftBandFilter,
                                        enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                                        exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
                                    ) {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(start = 40.dp, end = 16.dp, top = 6.dp, bottom = 12.dp),
                                        ) {
                                            Text("Configure a filter for only the FFT display (labels in the FFT spectrum view will only show if matched by this filter):",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                modifier = Modifier.padding(bottom = 8.dp)
                                            )
                                            BandFilterRow(
                                                filter = bandFilterForFft,
                                                allBookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.BAND },
                                                expanded = true,
                                                onExpandChange = { },
                                                onFilterChanged = { viewModel.setBandFilterForFft(it) },
                                                alwaysExpanded = true,
                                            )
                                            Text("Filter matches $filteredBandsInFftCount band bookmarks.")
                                        }
                                    }
                                }
                            }

                            // --- Section: Backup / Restore -------------------------------------------------
                            item {
                                SettingsSection(title = "Backup & Restore")
                            }

                            item {
                                SettingsActionRow(
                                    title = "Export Database Backup",
                                    description = "Back up all bookmarks, bands and lists to a JSON file",
                                    icon = Icons.Default.Upload,
                                    onClick = exportBackupFileChooser
                                )
                            }

                            item {
                                SettingsActionRow(
                                    title = "Restore from Backup",
                                    description = "Import a previously exported JSON backup",
                                    icon = Icons.Default.Download,
                                    onClick = { restoreWarningDialogShown = true }
                                )
                            }

                            // --- Section: Import / Export -------------------------------------------------
                            item {
                                SettingsSection(title = "Import & Export")
                            }

                            item {
                                val importStationsFileChooser = rememberOpenFilePicker(
                                    mimeTypes = arrayOf(
                                        "application/json", "text/xml",
                                        "text/csv", "application/octet-stream", "*/*"
                                    ),
                                    onAbort = { },
                                    onFileChosen = { uri, _ -> viewModel.startImport(uri) }
                                )
                                SettingsActionRow(
                                    title = "Import stations/bands",
                                    icon = Icons.Default.FileOpen,
                                    onClick = importStationsFileChooser
                                )
                            }

                            item {
                                SettingsActionRow(
                                    title = "Import Default Lists",
                                    icon = IconFrequencyBandOpen,
                                    onClick = viewModel::showDefaultImportDialog
                                )
                            }

                            if(legacyDbAvailable) {
                                item {
                                    SettingsActionRow(
                                        title = "Import legacy (RF Analyzer 1.13)",
                                        icon = Icons.Default.History,
                                        onClick = viewModel::startImportLegacy
                                    )
                                }
                            }

                            item {
                                SettingsInfoRow(
                                    text = buildAnnotatedString {
                                        append("Stations, Bands and entire Lists can be exported from their respective lists using the export button ")
                                        appendInlineContent("exportIcon", "[export]")
                                        append(".")
                                    },
                                    inlineContent = mapOf(
                                        "exportIcon" to InlineTextContent(
                                            Placeholder(
                                                width = 16.sp,
                                                height = 16.sp,
                                                placeholderVerticalAlign = PlaceholderVerticalAlign.Center
                                            )
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Upload,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    )
                                )
                            }

                            // --- Section: Tutorial -------------------------------------------------
                            item {
                                SettingsSection(title = "Tutorial")
                            }

                            item {
                                SettingsActionRow(
                                    title = "Show Bookmark Manager Tutorial",
                                    icon = Icons.Default.Info,
                                    onClick = bookmarkManagerScreenActions.showBookmarkManagerTutorial
                                )
                            }
                        }
                    }
                }
            }

            // Floating elements (FAB, sheets, dialogs, etc)
            if (page in setOf(StationsPage.STATIONS, StationsPage.BANDS, StationsPage.BOOKMARKLISTS) &&
                editedStation == null && editedBand == null && editedBookmarkList == null
            ) {
                FloatingActionButton(
                    onClick = {
                        when(page) {
                            StationsPage.STATIONS -> {
                                val newBookmarkList =
                                    if (stationFilterForList.bookmarkLists.size == 1 && stationFilterForList.sources.size == 1) // only exactly one bookmarkList in the filter
                                        stationFilterForList.bookmarkLists.firstOrNull()
                                    else allBookmarkLists.firstOrNull { it.type == BookmarkListType.STATION }?.id
                                viewModel.setEditedStationHasChanges(false)
                                viewModel.setEditedStationToNewWithDefaults(newBookmarkList)
                            }
                            StationsPage.BANDS -> {
                                val newBookmarkList =
                                    if (bandFilterForList.bookmarkLists.size == 1) // only exactly one bookmarkList in the filter
                                        bandFilterForList.bookmarkLists.firstOrNull()
                                    else allBookmarkLists.firstOrNull { it.type == BookmarkListType.BAND }?.id
                                viewModel.setEditedBandHasChanges(false)
                                viewModel.setEditedBandToNewWithDefaults(newBookmarkList)
                            }
                            StationsPage.BOOKMARKLISTS -> {
                                viewModel.setEditedBookmarkList(BookmarkList())
                            }
                            else -> Unit
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)
                ) { Icon(Icons.Default.Add, contentDescription = "Add") }
            }

            // Restore Warning Dialog
            if (restoreWarningDialogShown) {
                AlertDialog(
                    onDismissRequest = { restoreWarningDialogShown = false },
                    title = { Text("Warning of Data Loss") },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                text = "You are about to restore your bookmark database from a backup file. This will irrecoverably delete your current database. \n\nIt is highly recommended to create a backup first:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable(onClick = exportBackupFileChooser),
                                shape = MaterialTheme.shapes.large,
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Download,
                                        contentDescription = "Create Backup",
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text(text = "Create Backup", style = MaterialTheme.typography.titleMedium)
                                        Text(text = "Create a backup of your current bookmark database.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "Only continue if you are ready to delete your current database:",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(Modifier.height(8.dp))
                            Card(
                                modifier = Modifier.fillMaxWidth().clickable(onClick = { restoreWarningDialogShown=false; importBackupFileChooser() }),
                                shape = MaterialTheme.shapes.large,
                                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                            ) {
                                Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Upload,
                                        contentDescription = "Restore Database",
                                        modifier = Modifier.size(32.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text(text = "Restore Database", style = MaterialTheme.typography.titleMedium)
                                        Text(text = "Restore bookmark database from a previous backup file.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {},
                    dismissButton = {
                        TextButton(onClick = { restoreWarningDialogShown = false }) { Text("Cancel") }
                    }
                )
            }

            // Display the Import Confirmation Sheet when a result is ready
            if (parsedImportResult != null) {
                ImportConfirmationSheet(
                    parsedImport = parsedImportResult!!,
                    allBookmarkLists = allBookmarkLists,
                    onConfirm = { option, targetStationBookmarkList, targetBandBookmarkList, prefix ->
                        viewModel.confirmImport(option, targetStationBookmarkList, targetBandBookmarkList, prefix)
                    },
                    onDismiss = { viewModel.dismissImport() },
                    onReparse = { format -> viewModel.reparseWithFormat(format) },
                )
            }

            // Display edit sheets
            val tmpEditedStation = editedStation
            if (tmpEditedStation != null) {
                EditStationBookmarkSheet(
                    station = tmpEditedStation,
                    bookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.STATION },
                    isUnsafed = editedStationHasChanges,
                    onSave = { station -> viewModel.insertStation(station) },
                    onSaveTmp = { viewModel.setEditedStation(it); viewModel.setEditedStationHasChanges(true)},
                    onDismiss = { viewModel.setEditedStation(null); viewModel.setEditedStationHasChanges(false) },
                    onCreateNewBookmarkList = { viewModel.setEditedBookmarkList(BookmarkList(type = BookmarkListType.STATION)) }
                )
            }
            val tmpEditedBand = editedBand
            if (tmpEditedBand != null) {
                EditBandBookmarkSheet(
                    band = tmpEditedBand,
                    bookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.BAND },
                    isUnsafed = editedBandHasChanges,
                    onSave = { band -> viewModel.insertBand(band) },
                    onSaveTmp = { viewModel.setEditedBand(it); viewModel.setEditedBandHasChanges(true) },
                    onDismiss = { viewModel.setEditedBand(null); viewModel.setEditedBandHasChanges(false) },
                    onCreateNewBookmarkList = { viewModel.setEditedBookmarkList(BookmarkList(type = BookmarkListType.BAND, name = "")) }
                )
            }
            val tmpEditedBookmarkList = editedBookmarkList
            if (tmpEditedBookmarkList != null) {
                EditBookmarkListSheet(
                    bookmarkList = tmpEditedBookmarkList,
                    onSave = { bookmarkList -> viewModel.insertBookmarkList(bookmarkList) },
                    onDismiss = { viewModel.setEditedBookmarkList(null) }
                )
            }

            itemsToCopyOrMove?.let { (items, type) ->
                CopyOrMoveSheet(
                    itemsWithBookmarkList = items,
                    itemType = type,
                    bookmarkLists = allBookmarkLists.filter { it.type == type },
                    onCopy = { targetBookmarkList -> viewModel.confirmItemsToCopyOrMove(targetBookmarkList, true) },
                    onMove = { targetBookmarkList -> viewModel.confirmItemsToCopyOrMove(targetBookmarkList, false) },
                    onDismiss = { viewModel.setItemsToCopyOrMove(null, null) }
                )
            }

            itemsToExport?.let { (items, type) ->
                ExportSheet(
                    itemsWithBookmarkList = items,
                    itemType = type,
                    onExport = { uri -> viewModel.confirmItemsToExport(uri) },
                    buildExportJson = { viewModel.buildExportJson() },
                    onDismiss = { viewModel.setItemsToExport(null, null) },
                )
            }

            // Delete Confirmation Dialog
            viewModel.pendingDelete?.let { request ->
                val message = when (request) {
                    is DeleteRequest.DeleteStations ->
                        "Delete ${request.stationIds.size} stations?"
                    is DeleteRequest.DeleteBands ->
                        "Delete ${request.bandIds.size} bands?"
                    is DeleteRequest.DeleteBookmarkList ->
                        "Delete list “${request.bookmarkList.name}” (Contains ${request.count} ${request.bookmarkList.type.displayName})?"
                }
                AlertDialog(
                    onDismissRequest = viewModel::cancelDelete,
                    title = { Text("Confirm Deletion") },
                    text = { Text(message) },
                    confirmButton = {
                        TextButton(onClick = viewModel::confirmDelete) { Text("Delete") }
                    },
                    dismissButton = {
                        TextButton(onClick = viewModel::cancelDelete) { Text("Cancel") }
                    }
                )
            }

            // Import Legacy DB Dialog (if it has not been shown)
            if (!displayedImportLegacyDialog && legacyDbAvailable) {
                AlertDialog(
                    onDismissRequest = { viewModel.dismissImportLegacyDialog() },
                    title = { Text("Import Old Bookmarks?") },
                    text = { Text("A bookmark database from the legacy RF Analyzer 1.13 app has been found. Import these Bookmarks into RF Analyzer 2?\n\nNote: The import Option is also available under 'Settings'.") },
                    confirmButton = {
                        TextButton(onClick = { viewModel.startImportLegacy() }) {
                            Text("Import")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { viewModel.dismissImportLegacyDialog() }) {
                            Text("Skip")
                        }
                    }
                )
            } else if (displayDefaultImportDialog) {
                val ituRegion by viewModel.ituRegion.collectAsState()
                ImportDefaultListsDialog(
                    locationRegion = ituRegion,
                    onStartImport = { region, selectedSets -> viewModel.importDefaultLists(region, selectedSets) },
                    onDismiss = { viewModel.dismissDefaultImportDialog() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportConfirmationSheet(
    parsedImport: ParsedImport,
    allBookmarkLists: List<BookmarkList>,
    onConfirm: (option: ImportOption, targetStationBookmarkList: BookmarkList?, targetBandBookmarkList: BookmarkList?, prefix: String) -> Unit,
    onDismiss: () -> Unit,
    onReparse: (ImportFormat) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Import Preview", style = MaterialTheme.typography.titleLarge)

            // Status and Format Selector
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Detected Format:", modifier = Modifier.weight(1f))
                var formatDropdownExpanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { formatDropdownExpanded = true }) {
                        Text(parsedImport.detectedFormat.name)
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = formatDropdownExpanded,
                        onDismissRequest = { formatDropdownExpanded = false }
                    ) {
                        ImportFormat.entries.filter { it != ImportFormat.UNKNOWN }.forEach { format ->
                            DropdownMenuItem(
                                text = { Text(format.name) },
                                onClick = {
                                    onReparse(format)
                                    formatDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Display parsing result
            if (parsedImport.status == ParseStatus.SUCCESS) {
                Text("Found ${parsedImport.bookmarkLists.size} lists (containing ${parsedImport.stations.size} stations and ${parsedImport.bands.size} bands):")
                if (parsedImport.bookmarkLists.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                        parsedImport.bookmarkLists.take(10).forEach { bookmarkList ->
                            SuggestionChip(onClick = {}, label = { Text(bookmarkList.name) })
                        }
                        if (parsedImport.bookmarkLists.size > 10) SuggestionChip(onClick = {}, label = { Text("...") } )
                    }
                }
            } else {
                Text(
                    "Error: ${parsedImport.errorMessage}",
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (parsedImport.status == ParseStatus.SUCCESS) {
                // Import Options
                var selectedOption by remember { mutableStateOf(ImportOption.IMPORT_AS_IS) }
                var targetStationBookmarkList by remember { mutableStateOf<BookmarkList?>(allBookmarkLists.firstOrNull { it.type == BookmarkListType.STATION }) }
                var targetBandBookmarkList by remember { mutableStateOf<BookmarkList?>(allBookmarkLists.firstOrNull { it.type == BookmarkListType.BAND }) }
                var prefix by remember { mutableStateOf("") }

                Text("Import Options", style = MaterialTheme.typography.titleMedium)
                OutlinedEnumDropDown(
                    label = "Import Mode",
                    enumClass = ImportOption::class,
                    getDisplayName = { it.label },
                    selectedEnum = selectedOption,
                    onSelectionChanged = { selectedOption = it },
                    modifier = Modifier.fillMaxWidth().height(DEFAULT_MIN_BOX_HEIGHT),
                    background = MaterialTheme.colorScheme.surfaceContainerLow,
                    helpSubPath = "bookmarks.html#import-mode"
                )
                Text(
                    text = selectedOption.description,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp)
                )

                if (selectedOption == ImportOption.IMPORT_WITH_PREFIX) {
                    OutlinedTextField(
                        value = prefix,
                        onValueChange = { prefix = it },
                        label = { Text("Prefix for List Names") },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        singleLine = true,
                    )
                }

                if (selectedOption == ImportOption.IMPORT_INTO_SINGLE) {

                    if (parsedImport.stations.isNotEmpty()) {
                        val stationBookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.STATION }
                        OutlinedListDropDown(
                            label = "Target List for Stations",
                            items = stationBookmarkLists,
                            getDisplayName = { it?.name ?: "<none>" },
                            selectedItem = targetStationBookmarkList,
                            onSelectionChanged = { targetStationBookmarkList = it },
                            modifier = Modifier.fillMaxWidth().height(DEFAULT_MIN_BOX_HEIGHT).padding(top = 8.dp),
                            background = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    }

                    if (parsedImport.bands.isNotEmpty()) {
                        val bandBookmarkLists = allBookmarkLists.filter { it.type == BookmarkListType.BAND }
                        OutlinedListDropDown(
                            label = "Target List for Bands",
                            items = bandBookmarkLists,
                            getDisplayName = { it?.name ?: "<none>" },
                            selectedItem = targetBandBookmarkList,
                            onSelectionChanged = { targetBandBookmarkList = it },
                            modifier = Modifier.fillMaxWidth().height(DEFAULT_MIN_BOX_HEIGHT).padding(top = 8.dp),
                            background = MaterialTheme.colorScheme.surfaceContainerLow
                        )
                    }
                }

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = { onConfirm(selectedOption, targetStationBookmarkList, targetBandBookmarkList, prefix) }) {
                        Text("Import")
                    }
                }
            }
        }
    }
}

@Composable
fun ImportDefaultListsDialog(
    /** The region of the user's location, preselected; null if it isn't known. */
    locationRegion: ItuRegion?,
    onStartImport: (region: Int, selectedSets: Set<DefaultImportSet>) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedRegion by remember { mutableStateOf(locationRegion ?: ItuRegion.REGION_1) }
    val checkedSets = remember {
        mutableStateMapOf<DefaultImportSet, Boolean>().apply {
            DefaultImportSet.entries.forEach { put(it, true) }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Import Default Lists",
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {

                Text(
                    text = "Select your region and which bundled lists you would like to import into the bookmark database:",
                    style = MaterialTheme.typography.bodyMedium
                )

                // --- Region selection ---
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    ItuRegion.entries.forEach { region ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedRegion = region }
                        ) {
                            RadioButton(
                                selected = selectedRegion == region,
                                onClick = { selectedRegion = region }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(text = region.label + if (region == locationRegion) " (your location)" else "", style = MaterialTheme.typography.bodyMedium)
                                Text(text = region.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                Divider()

                // --- Importable list checkboxes ---
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DefaultImportSet.entries.forEach { set ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { checkedSets[set] = !(checkedSets[set] ?: false) }
                        ) {
                            Checkbox(
                                checked = checkedSets[set] ?: false,
                                onCheckedChange = { checkedSets[set] = it }
                            )
                            Spacer(Modifier.width(8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = set.title, style = MaterialTheme.typography.bodyLarge)
                                Text(text = set.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val selectedSets = DefaultImportSet.entries.filter { checkedSets[it] == true }.toSet()
            TextButton(
                enabled = selectedSets.isNotEmpty(),
                onClick = {
                    onStartImport(selectedRegion.number, selectedSets)
                }
            ) {
                Text("Start Import")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    )
}
