package com.mantz_it.rfanalyzer.ui.screens

import com.mantz_it.rfanalyzer.location.LocationRepository
import android.net.Uri
import android.util.Log
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.mantz_it.rfanalyzer.database.*
import com.mantz_it.rfanalyzer.ui.SnackbarEvent
import com.mantz_it.rfanalyzer.ui.composable.IconFrequencyBand
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * <h1>RF Analyzer - BookmarkManagerViewModel</h1>
 *
 * Module:      BookmarkManagerViewModel.kt
 * Description: View Model for the BookmarkManagerScreen
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

enum class StationsPage(val displayName: String, val imageVector: ImageVector) {
    BOOKMARKLISTS("Lists", Icons.Default.List),
    STATIONS("Stations", Icons.Default.Radio),
    BANDS("Bands", IconFrequencyBand),
    SETTINGS("Settings", Icons.Default.Settings),
}

// Helper class to group online sources and bookmarkLists into a single list
sealed interface BookmarkListListItem {
    data class Header(val title: String, val icon: ImageVector, val expanded: Boolean, val toggle: () -> Unit) : BookmarkListListItem
    data class OnlineSource(val providerWithSettings: OnlineStationProviderWithSettings) : BookmarkListListItem
    data class BookmarkListItem(val bookmarkList: BookmarkList) : BookmarkListListItem
}

sealed class DeleteRequest {
    data class DeleteStations(val stationIds: List<Long>) : DeleteRequest()
    data class DeleteBands(val bandIds: List<Long>) : DeleteRequest()
    data class DeleteBookmarkList(val bookmarkList: BookmarkList, val count: Int) : DeleteRequest()
}


@HiltViewModel
class BookmarkManagerViewModel @Inject constructor(
    private val repository: StationRepository,
    private val stationImporterExporter: StationImporterExporter,
    private val appStateRepository: AppStateRepository,
    locationRepository: LocationRepository,
) : ViewModel() {

    companion object {
        private const val TAG = "BookmarkManagerViewModel"
    }

    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvent = _snackbarEvent.asSharedFlow()
    fun showSnackbar(snackbarEvent: SnackbarEvent) { _snackbarEvent.tryEmit(snackbarEvent) }
    fun showMessage(msg: String) { showSnackbar(SnackbarEvent(msg)) }

    val displayDefaultImportDialog = appStateRepository.displayDefaultImportDialog.stateFlow
    /** Preselects the import dialog's region. */
    val ituRegion = locationRepository.location.map { it.ituRegion }.stateIn(viewModelScope, SharingStarted.Eagerly, locationRepository.location.value.ituRegion)
    fun showDefaultImportDialog() { appStateRepository.displayDefaultImportDialog.set(true) }
    fun dismissDefaultImportDialog() { appStateRepository.displayDefaultImportDialog.set(false) }

    private val _legacyDbAvailable = MutableStateFlow(false)
    val legacyDbAvailable = _legacyDbAvailable.asStateFlow()
    val displayedLegacyImportDialog = appStateRepository.displayedLegacyImportDialog.stateFlow

    fun dismissImportLegacyDialog() {
        appStateRepository.displayedLegacyImportDialog.set(true)
    }
    init {
        Log.d(TAG, "init: BookmarkManagerViewModel initializing...")
        viewModelScope.launch {
            Log.d(TAG, "init: Legacy Import Dialog already shown: ${appStateRepository.displayedLegacyImportDialog.stateFlow.value}")
            val legacyDbUri = stationImporterExporter.getLegacyDbUri()
            if (legacyDbUri != null)
                _legacyDbAvailable.value = true
        }
    }

    // page state
    val page = appStateRepository.bookmarkManagerScreenPage.stateFlow
    var tempBackNavigationEnabled by mutableStateOf(false)
        private set
    fun setPage(p: StationsPage) {
        Log.i(TAG, "setPage: $p")
        appStateRepository.bookmarkManagerScreenPage.set(p)
        // any normal navigation will disable temp back navigation
        tempBackNavigationEnabled = false
    }
    fun setPageAndEnableTempBackNavigation(p: StationsPage) {
        Log.i(TAG, "setPageAndEnableTempBackNavigation: $p")
        appStateRepository.bookmarkManagerScreenPage.set(p)
        tempBackNavigationEnabled = true
    }

    // Settings
    val displayStationsInFft = appStateRepository.displayStationsInFft.stateFlow
    fun setDisplayStationsInFft(newVal: Boolean) { appStateRepository.displayStationsInFft.set(newVal) }
    val useCustomFftStationFilter = appStateRepository.useCustomFftStationFilter.stateFlow
    fun setUseCustomFftStationFilter(newVal: Boolean) { appStateRepository.useCustomFftStationFilter.set(newVal) }
    val displayBandsInFft = appStateRepository.displayBandsInFft.stateFlow
    fun setDisplayBandsInFft(newVal: Boolean) { appStateRepository.displayBandsInFft.set(newVal) }
    val useCustomFftBandFilter = appStateRepository.useCustomFftBandFilter.stateFlow
    fun setSynchronizeBandFilters(newVal: Boolean) { appStateRepository.useCustomFftBandFilter.set(newVal) }

    // all BookmarkLists
    val allBookmarkLists = repository.getAllBookmarkLists()

    // filtered Stations
    val stationFilterForList = appStateRepository.stationFilterForList.state.stateFlow
    fun setStationFilterForList(filter: StationFilter) = appStateRepository.stationFilterForList.set(filter)
    val stationFilterForFft = appStateRepository.stationFilterForFft.state.stateFlow
    fun setStationFilterForFft(filter: StationFilter) = appStateRepository.stationFilterForFft.set(filter)

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredStationsPaging = stationFilterForList.flatMapLatest { filter ->
        repository.getFilteredStationsPaging(filter)
    }.cachedIn(viewModelScope)

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredStationsInFftCount = stationFilterForFft.flatMapLatest { filter ->
        repository.getStationIdsFiltered(filter).map { it.size }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // filtered Bands
    val bandFilterForList = appStateRepository.bandFilterForList.state.stateFlow
    fun setBandFilterForList(filter: BandFilter) = appStateRepository.bandFilterForList.set(filter)
    val bandFilterForFft = appStateRepository.bandFilterForFft.state.stateFlow
    fun setBandFilterForFft(filter: BandFilter) = appStateRepository.bandFilterForFft.set(filter)

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredBands = bandFilterForList.flatMapLatest { filter ->
        repository.getFilteredBands(filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredBandsInFftCount = bandFilterForFft.flatMapLatest { filter ->
        repository.getFilteredBands(filter).map { it.size }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // filtered BookmarkLists
    val bookmarkListFilterSearchString = appStateRepository.bookmarkListFilterSearchString.stateFlow
    fun setBookmarkListFilterSearchString(filter: String) = appStateRepository.bookmarkListFilterSearchString.set(filter)

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredBookmarkLists = bookmarkListFilterSearchString.flatMapLatest { filter ->
        repository.getFilteredBookmarkLists(filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // filtered OnlineStationProviderWithSettings
    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredOnlineStationProviderWithSettings = bookmarkListFilterSearchString.flatMapLatest { filter ->
        repository.getFilteredOnlineStationProviderWithSettings(filter)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // grouped bookmarkLists/online sources
    private val _onlineSourcesExpanded = MutableStateFlow(true)
    val onlineSourcesExpanded: StateFlow<Boolean> = _onlineSourcesExpanded.asStateFlow()
    fun toggleOnlineSourcesExpanded() { _onlineSourcesExpanded.value = !_onlineSourcesExpanded.value}
    private val _stationBookmarksExpanded = MutableStateFlow(true)
    val stationBookmarksExpanded: StateFlow<Boolean> = _stationBookmarksExpanded.asStateFlow()
    fun toggleStationBookmarksExpanded() { _stationBookmarksExpanded.value = !_stationBookmarksExpanded.value}
    private val _bandBookmarksExpanded = MutableStateFlow(true)
    val bandBookmarksExpanded: StateFlow<Boolean> = _bandBookmarksExpanded.asStateFlow()
    fun toggleBandBookmarksExpanded() { _bandBookmarksExpanded.value = !_bandBookmarksExpanded.value}
    val bookmarkListScreenItems: StateFlow<List<BookmarkListListItem>> =
        combine(
            onlineSourcesExpanded,
            stationBookmarksExpanded,
            bandBookmarksExpanded,
            filteredOnlineStationProviderWithSettings,
            filteredBookmarkLists
        ) { onlineExpanded, stationsExpanded, bandsExpanded, onlineProviderWithSettings, bookmarkLists ->
            buildList {
                add(BookmarkListListItem.Header("Local Station Lists", icon = Icons.Default.Radio, stationsExpanded, ::toggleStationBookmarksExpanded))
                if (stationsExpanded) {
                    bookmarkLists.filter { it.type == BookmarkListType.STATION }.forEach { cat ->
                        add(BookmarkListListItem.BookmarkListItem(cat))
                    }
                }
                add(BookmarkListListItem.Header("Local Band Lists", icon = IconFrequencyBand, bandsExpanded, ::toggleBandBookmarksExpanded))
                if (bandsExpanded) {
                    bookmarkLists.filter { it.type == BookmarkListType.BAND }.forEach { cat ->
                        add(BookmarkListListItem.BookmarkListItem(cat))
                    }
                }
                add(BookmarkListListItem.Header("Online Station Lists", icon = Icons.Default.Cloud, onlineExpanded, ::toggleOnlineSourcesExpanded))
                if (onlineExpanded) {
                    onlineProviderWithSettings.forEach { src ->
                        add(BookmarkListListItem.OnlineSource(src))
                    }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())


    // selection + select-mode
    var selectMode by mutableStateOf(false)
        private set
    fun toggleSelectMode() { selectMode = !selectMode }

    // Selection for Stations

    var selectedStations by mutableStateOf<Set<Long>>(emptySet())
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    val allVisibleStationsSelected: StateFlow<Boolean> =
        combine(
            stationFilterForList.flatMapLatest { filter -> repository.getStationIdsFiltered(filter) },
            snapshotFlow { selectedStations }
        ) { visibleIds, selected ->
            visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false
        )

    fun selectToggleStation(station: Station) {
        selectedStations = if (station.id in selectedStations) selectedStations.minus(station.id) else selectedStations.plus(station.id)
    }
    fun selectAllVisibleStations() {
        viewModelScope.launch {
            selectedStations = repository.getStationIdsFiltered(stationFilterForList.value).first().toSet()
        }
    }
    fun clearSelectionStation() { selectedStations = emptySet() }

    // Selection for Bands
    var selectedBands by mutableStateOf<Set<Long>>(emptySet())
        private set

    @OptIn(ExperimentalCoroutinesApi::class)
    val allVisibleBandsSelected: StateFlow<Boolean> =
        combine(
            filteredBands,
            snapshotFlow { selectedBands }
        ) { visibleBands, selected ->
            val visibleIds = visibleBands.map { it.band.id }.toSet()
            visibleIds.isNotEmpty() && selected.containsAll(visibleIds)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            false
        )

    fun selectToggleBand(band: Band) {
        selectedBands = if (band.id in selectedBands) selectedBands.minus(band.id) else selectedBands.plus(band.id)
    }
    fun selectAllVisibleBands() {
        viewModelScope.launch {
            selectedBands = filteredBands.first().map { it.band.id }.toSet()
        }
    }
    fun clearSelectionBand() { selectedBands = emptySet() }

    // multi copy/move
    var itemsToCopyOrMove: Pair<List<Any>, BookmarkListType>? by mutableStateOf(null)
        private set
    fun setItemsToCopyOrMove(items: List<Any>?, bookmarkListType: BookmarkListType?) {
        itemsToCopyOrMove = if (items != null && bookmarkListType != null)
            items to bookmarkListType
        else
            null
    }
    fun setItemsToCopyOrMoveFromSelection(bookmarkListType: BookmarkListType) {
        when (bookmarkListType) {
            BookmarkListType.STATION -> viewModelScope.launch { itemsToCopyOrMove = repository.getStationsWithBookmarkListByIds(selectedStations).first() to BookmarkListType.STATION }
            BookmarkListType.BAND -> viewModelScope.launch { itemsToCopyOrMove = repository.getBandsWithBookmarkListByIds(selectedBands).first() to BookmarkListType.BAND }
        }
    }
    fun setItemsToCopyOrMoveFromBookmarkList(bookmarkList: BookmarkList) {
        when (bookmarkList.type) {
            BookmarkListType.STATION -> viewModelScope.launch { itemsToCopyOrMove = repository.getStationsWithBookmarkListByBookmarkLists(setOf(bookmarkList.id)).first() to BookmarkListType.STATION }
            BookmarkListType.BAND -> viewModelScope.launch { itemsToCopyOrMove = repository.getBandsWithBookmarkListByBookmarkLists(setOf(bookmarkList.id)).first() to BookmarkListType.BAND }
        }
    }

    fun confirmItemsToCopyOrMove(targetBookmarkList: BookmarkList, copy: Boolean) {
        viewModelScope.launch {
            val (items, type) = itemsToCopyOrMove ?: return@launch
            if (copy) {
                if (type == BookmarkListType.STATION) {
                    repository.copyStations(items.filterIsInstance<StationWithBookmarkList>().map { it.station }, targetBookmarkList)
                    clearSelectionStation()
                } else {
                    repository.copyBands(items.filterIsInstance<BandWithBookmarkList>().map { it.band }, targetBookmarkList)
                    clearSelectionBand()
                }
            } else {
                if (type == BookmarkListType.STATION) {
                    repository.moveStations(items.filterIsInstance<StationWithBookmarkList>().map { it.station }, targetBookmarkList)
                    clearSelectionStation()
                } else {
                    repository.moveBands(items.filterIsInstance<BandWithBookmarkList>().map { it.band }, targetBookmarkList)
                    clearSelectionBand()
                }
            }
            setItemsToCopyOrMove(null, null)
            selectMode = false
        }
    }

    // multi deletion
    var pendingDelete by mutableStateOf<DeleteRequest?>(null)
        private set

    fun deleteSelected() {
        when(page.value) {
            StationsPage.STATIONS -> {
                pendingDelete = DeleteRequest.DeleteStations(selectedStations.toList())
            }
            StationsPage.BANDS -> {
                pendingDelete = DeleteRequest.DeleteBands(selectedBands.toList())
            }
            else -> Unit
        }
    }
    fun requestDeleteBookmarkList(bookmarkList: BookmarkList) {
        viewModelScope.launch {
            val count = if (bookmarkList.type == BookmarkListType.STATION)
                repository.getStationsWithBookmarkListByBookmarkLists(setOf(bookmarkList.id)).first().size
            else
                repository.getBandsWithBookmarkListByBookmarkLists(setOf(bookmarkList.id)).first().size
            pendingDelete = DeleteRequest.DeleteBookmarkList(bookmarkList, count)
        }
    }
    fun cancelDelete() { pendingDelete = null }
    fun confirmDelete() = viewModelScope.launch {
        when (val req = pendingDelete) {
            is DeleteRequest.DeleteStations -> {
                repository.deleteStationsByIds(req.stationIds)
                clearSelectionStation()
                selectMode = false
            }
            is DeleteRequest.DeleteBands -> {
                repository.deleteBandsByIds(req.bandIds)
                clearSelectionBand()
                selectMode = false
            }
            is DeleteRequest.DeleteBookmarkList -> {
                repository.deleteBookmarkList(req.bookmarkList)
                // prune filters if they referenced this bookmarkList id
                val cid = req.bookmarkList.id
                for (sf in listOf(appStateRepository.stationFilterForList, appStateRepository.stationFilterForFft)) {
                    if (sf.state.value.bookmarkLists.isNotEmpty() && cid in sf.state.value.bookmarkLists) {
                        sf.set(sf.state.value.copy(bookmarkLists = sf.state.value.bookmarkLists - cid))
                    }
                }
                for (bf in listOf(appStateRepository.bandFilterForList, appStateRepository.bandFilterForFft)) {
                    if (bf.state.value.bookmarkLists.isNotEmpty() && cid in bf.state.value.bookmarkLists) {
                        bf.set(bf.state.value.copy(bookmarkLists = bf.state.value.bookmarkLists - cid))
                    }
                }
            }
            null -> Log.w(TAG, "confirmDelete: pending delete request is null")
        }
        pendingDelete = null
    }

    // expanded card states
    var expandedBookmarkList by mutableStateOf<Long?>(null)
        private set
    var expandedStation by mutableStateOf<Long?>(null)
        private set
    var expandedBand by mutableStateOf<Long?>(null)
        private set
    var expandedOnlineStationProvider by mutableStateOf<String?>(null)
        private set
    fun expandBookmarkList(bookmarkList: BookmarkList) { expandedBookmarkList = if (expandedBookmarkList == bookmarkList.id) null else bookmarkList.id }
    fun expandStation(station: Station) { expandedStation = if (expandedStation == station.id) null else station.id }
    fun expandBand(band: Band) { expandedBand = if (expandedBand == band.id) null else band.id }
    fun expandOnlineStationProvider(provider: OnlineStationProvider) { expandedOnlineStationProvider = if (expandedOnlineStationProvider == provider.dbId) null else provider.dbId }

    // Online Providers
    val onlineStationProviderDownloadStates = appStateRepository.onlineStationDownloadState
    fun clearOnlineCache(provider: OnlineStationProvider) {
        viewModelScope.launch { repository.clearOnlineCache(provider) }
    }

    fun setOnlineStationProviderAutoUpdateEnable(provider: OnlineStationProvider, enabled: Boolean) {
        viewModelScope.launch { repository.setOnlineStationProviderEnabled(provider, enabled) }
    }

    fun setOnlineStationProviderAutoUpdateInterval(provider: OnlineStationProvider, interval: Int) {
        viewModelScope.launch { repository.setOnlineStationProviderUpdateInterval(provider, interval) }
    }

    // Insert/Update/Delete Stations/Bands/BookmarkLists
    private val _editedStation = MutableStateFlow<Station?>(null)
    private val _editedStationHasChanges = MutableStateFlow(false)
    private val _editedBand = MutableStateFlow<Band?>(null)
    private val _editedBandHasChanges = MutableStateFlow(false)
    private val _editedBookmarkList = MutableStateFlow<BookmarkList?>(null)
    val editedStation = _editedStation.asStateFlow()
    val editedStationHasChanges = _editedStationHasChanges.asStateFlow()
    val editedBand = _editedBand.asStateFlow()
    val editedBandHasChanges = _editedBandHasChanges.asStateFlow()
    val editedBookmarkList = _editedBookmarkList.asStateFlow()
    fun setEditedStation(station: Station?) { Log.d(TAG, "setEditedStation: $station"); _editedStation.value = station }
    fun setEditedStationHasChanges(hasChanges: Boolean) { _editedStationHasChanges.value = hasChanges }
    fun setEditedBand(band: Band?) { _editedBand.value = band }
    fun setEditedBandHasChanges(hasChanges: Boolean) { _editedBandHasChanges.value = hasChanges }
    fun setEditedBookmarkList(bookmarkList: BookmarkList?) { _editedBookmarkList.value = bookmarkList }
    fun setEditedStationToNewWithDefaults(defaultBookmarkListId: Long? = null) {
        _editedStation.value = Station(
            bookmarkListId = defaultBookmarkListId,
            frequency = appStateRepository.channelFrequency.value,
            bandwidth = appStateRepository.channelWidth.value,
            createdAt = System.currentTimeMillis(),
            mode = appStateRepository.demodulationMode.value,
            demodulationParameters = appStateRepository.captureCurrentDemodulationParameters(),
        )
    }
    fun setEditedBandToNewWithDefaults(defaultBookmarkListId: Long? = null) {
        _editedBand.value = Band(
            bookmarkListId = defaultBookmarkListId,
            startFrequency = appStateRepository.viewportStartFrequency.value,
            endFrequency = appStateRepository.viewportEndFrequency.value,
            createdAt = System.currentTimeMillis(),
        )
    }

    fun insertStation(station: Station) {
        viewModelScope.launch {
            Log.i(TAG, "insertStation: $station")
            repository.insertStation(station)
        }
    }

    fun insertBookmarkList(bookmarkList: BookmarkList) {
        viewModelScope.launch {
            Log.i(TAG, "insertBookmarkList: $bookmarkList")
            val newBookmarkListId = repository.insertBookmarkList(bookmarkList)
            // Update currently edited Station/Band (if any) to point to the new bookmarkList
            if (bookmarkList.type == BookmarkListType.STATION && _editedStation.value != null) {
                Log.i(TAG, "insertBookmarkList: set ${_editedStation.value?.bookmarkListId} to $newBookmarkListId")
                _editedStation.value = _editedStation.value?.copy(bookmarkListId = newBookmarkListId)
            }
            if (bookmarkList.type == BookmarkListType.BAND && _editedBand.value != null) {
                _editedBand.value = _editedBand.value?.copy(bookmarkListId = newBookmarkListId)
            }
        }
    }

    fun insertBand(band: Band) {
        viewModelScope.launch {
            Log.i(TAG, "insertBand: $band")
            repository.insertBand(band)
        }
    }

    fun deleteStation(station: Station) {
        viewModelScope.launch {
            repository.deleteStation(station)
            showSnackbar(SnackbarEvent("Station deleted", "Undo") {
                viewModelScope.launch { repository.insertStation(station) }
            })
        }
    }

    fun deleteBand(band: Band) {
        viewModelScope.launch {
            repository.deleteBand(band)
            showSnackbar(SnackbarEvent("Band deleted", "Undo") {
                viewModelScope.launch { repository.insertBand(band) }
            })
        }
    }

    fun updateStation(station: Station) {
        viewModelScope.launch { repository.updateStation(station) }
    }

    fun updateBand(band: Band) {
        viewModelScope.launch { repository.updateBand(band) }
    }

    //
    // ####  Import/Export  ######
    //

    // import state
    private val _parsedImportResult = MutableStateFlow<ParsedImport?>(null)
    val parsedImportResult: StateFlow<ParsedImport?> = _parsedImportResult.asStateFlow()
    private val _importUri = MutableStateFlow<Uri?>(null) // Store the URI to allow reparsing

    fun exportBackup(outUri: Uri) {
        viewModelScope.launch {
            if (stationImporterExporter.exportAll(outUri)) {
                Log.i(TAG, "exportBackup: success! dest=$outUri")
                showMessage("Backup successful!")
            } else {
                Log.w(TAG, "exportBackup: failed! dest=$outUri")
                showMessage("Backup failed!")
            }
        }
    }

    var itemsToExport: Pair<List<Any>, BookmarkListType>? by mutableStateOf(null)
        private set
    fun setItemsToExport(items: List<Any>?, bookmarkListType: BookmarkListType?) {
        itemsToExport = if (items != null && bookmarkListType != null)
            items to bookmarkListType
        else
            null
    }
    fun setItemsToExportFromSelection(bookmarkListType: BookmarkListType) {
        when (bookmarkListType) {
            BookmarkListType.STATION -> viewModelScope.launch { itemsToExport = repository.getStationsWithBookmarkListByIds(selectedStations).first() to BookmarkListType.STATION }
            BookmarkListType.BAND -> viewModelScope.launch { itemsToExport = repository.getBandsWithBookmarkListByIds(selectedBands).first() to BookmarkListType.BAND }
        }
    }
    fun setItemsToExportFromBookmarkList(bookmarkList: BookmarkList) {
        when (bookmarkList.type) {
            BookmarkListType.STATION -> viewModelScope.launch { itemsToExport = repository.getStationsWithBookmarkListByBookmarkLists(setOf(bookmarkList.id)).first() to BookmarkListType.STATION }
            BookmarkListType.BAND -> viewModelScope.launch { itemsToExport = repository.getBandsWithBookmarkListByBookmarkLists(setOf(bookmarkList.id)).first() to BookmarkListType.BAND }
        }
    }
    // itemsToExport as the (bookmarkLists, stations, bands) to export: to a file the user chose
    // (confirmItemsToExport) or as JSON for the ExportSheet's "Share" (buildExportJson).
    private fun resolveExportData(items: List<Any>, type: BookmarkListType): Triple<List<BookmarkList>, List<Station>, List<Band>> {
        return if (type == BookmarkListType.STATION) {
            val stationItems = items.filterIsInstance<StationWithBookmarkList>()
            val bookmarkLists: List<BookmarkList> = stationItems.mapNotNull { it.bookmarkList }.distinct()
            val stations: List<Station> = stationItems.map { it.station }
            Triple(bookmarkLists, stations, emptyList())
        } else {
            val bandItems = items.filterIsInstance<BandWithBookmarkList>()
            val bookmarkLists: List<BookmarkList> = bandItems.mapNotNull { it.bookmarkList }.distinct()
            val bands: List<Band> = bandItems.map { it.band }
            Triple(bookmarkLists, emptyList(), bands)
        }
    }

    fun buildExportJson(): String {
        val (items, type) = itemsToExport ?: return stationImporterExporter.buildExportJson(emptyList(), emptyList(), emptyList())
        val (bookmarkLists, stations, bands) = resolveExportData(items, type)
        return stationImporterExporter.buildExportJson(bookmarkLists, stations, bands)
    }

    fun confirmItemsToExport(uri: Uri) {
        viewModelScope.launch {
            val (items, type) = itemsToExport ?: return@launch
            val (bookmarkLists, stations, bands) = resolveExportData(items, type)
            val success = stationImporterExporter.export(uri, bookmarkLists, stations, bands)
            if (success) {
                showMessage("Export successful!")
                setItemsToExport(null, null)
                if (type == BookmarkListType.STATION) clearSelectionStation() else clearSelectionBand()
                selectMode = false
            } else {
                showMessage("Export failed!")
            }
        }
    }

    fun startImport(inUri: Uri) {
        _importUri.value = inUri
        parseSelectedFile(inUri, forcedFormat = null)
    }

    fun startImportLegacy() {
        viewModelScope.launch {
            val uri = stationImporterExporter.getLegacyDbUri()
            if (uri == null) {
                Log.e(TAG, "startImportLegacy: no legacy db found")
                return@launch
            }
            parseSelectedFile(uri, forcedFormat = ImportFormat.LEGACY_RFANALYZER_DB)
        }
    }

    fun reparseWithFormat(format: ImportFormat) {
        _importUri.value?.let { uri ->
            parseSelectedFile(uri, forcedFormat = format)
        }
    }

    private fun parseSelectedFile(uri: Uri, forcedFormat: ImportFormat?) {
        viewModelScope.launch {
            _parsedImportResult.value = stationImporterExporter.tryToParseUri(uri, forcedFormat)
        }
    }

    fun confirmImport(option: ImportOption, targetStationBookmarkList: BookmarkList?, targetBandBookmarkList: BookmarkList?, prefix: String) {
        viewModelScope.launch {
            _parsedImportResult.value?.let {
                val (success, message) = stationImporterExporter.importParsedData(it, option, targetStationBookmarkList, targetBandBookmarkList, prefix)
                Log.i(TAG, "Import finished. Success: $success, Message: $message")
                if (success)
                    showMessage("Import successful: $message")
                else
                    showMessage("Import failed: $message")
            }
            dismissImport() // Clear the state after import is done
        }
    }

    fun dismissImport() {
        _parsedImportResult.value = null
        _importUri.value = null
    }

    fun importBackup(inUri: Uri) {
        viewModelScope.launch {
            val (success, message) = stationImporterExporter.importAndRestore(uri = inUri)
            Log.i(TAG, "Import finished. Success: $success, Message: $message")
            if (success)
                showMessage("Import successful: $message")
            else
                showMessage("Import failed: $message")
        }
    }

    fun importDefaultLists(region: Int, selectedSets: Set<DefaultImportSet>) {
        if (region !in 1..3) {
            Log.d(TAG, "importDefaultLists: Invalid region: $region")
        }
        viewModelScope.launch {
            val succeeded = mutableListOf<String>()
            val failed = mutableListOf<String>()
            for (set in selectedSets) {
                if (stationImporterExporter.importDefaultSet(set, region)) succeeded.add(set.bookmarkListName(region))
                else failed.add(set.title)
            }
            if (succeeded.isNotEmpty()) setPage(StationsPage.BOOKMARKLISTS)
            val message = buildString {
                if (succeeded.isNotEmpty()) append("Imported: ${succeeded.joinToString(", ")}. ")
                if (failed.isNotEmpty()) append("Failed: ${failed.joinToString(", ")}.")
            }.trim()
            if (message.isNotBlank()) showMessage(message)
            appStateRepository.displayDefaultImportDialog.set(false)
        }
    }
}
