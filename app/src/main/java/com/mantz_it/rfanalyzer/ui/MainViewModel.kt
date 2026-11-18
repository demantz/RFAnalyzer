package com.mantz_it.rfanalyzer.ui

import com.mantz_it.rfanalyzer.source.RecordingFileName
import com.mantz_it.rfanalyzer.source.KiwiSdrSource
import com.mantz_it.rfanalyzer.location.CountrySource
import com.mantz_it.rfanalyzer.database.StationFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.flowOn
import com.mantz_it.rfanalyzer.digiext.demodulationMode
import com.mantz_it.rfanalyzer.digiext.digitalMode
import com.mantz_it.rfanalyzer.digiext.buildModePresets
import com.mantz_it.rfanalyzer.digiext.MODE_SHEET_DEMODULATION_MODES
import com.mantz_it.rfanalyzer.digiext.ModeSheetStations
import com.mantz_it.rfanalyzer.network.DigitalStationList
import com.mantz_it.rfanalyzer.location.LocationRepository
import com.mantz_it.rfanalyzer.location.LocationSheetActions
import com.mantz_it.rfanalyzer.location.MapHome
import com.mantz_it.rfanalyzer.location.UserLocation
import kotlinx.coroutines.plus
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.DEFAULT_VERTICAL_SCALE_MAX
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.DEFAULT_VERTICAL_SCALE_MIN
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.sanitizeVerticalScale
import com.mantz_it.rfanalyzer.billing.BillingEvent
import com.mantz_it.rfanalyzer.billing.BillingResponse
import com.mantz_it.rfanalyzer.billing.BillingRepository
import com.mantz_it.rfanalyzer.billing.BillingState
import com.mantz_it.rfanalyzer.billing.License
import com.mantz_it.rfanalyzer.billing.Product
import com.mantz_it.rfanalyzer.billing.PurchaseStart
import com.mantz_it.rfanalyzer.ui.composable.ownedProductsText
import com.mantz_it.rfanalyzer.ui.composable.productName
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketRepository
import com.mantz_it.rfanalyzer.digiext.ui.MapMarkerState
import com.mantz_it.rfanalyzer.database.MapCameraPosition
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer.database.Recording
import com.mantz_it.rfanalyzer.database.RecordingDao
import com.mantz_it.rfanalyzer.database.Station
import com.mantz_it.rfanalyzer.database.StationRepository
import com.mantz_it.rfanalyzer.database.StationWithBookmarkList
import com.mantz_it.rfanalyzer.database.OnlineStationProvider
import com.mantz_it.rfanalyzer.database.calculateFileName
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.source.ClockCorrection
import com.mantz_it.rfanalyzer_ipc.DigitalDemodParams
import com.mantz_it.rfanalyzer_ipc.Ft8TimingMode
import com.mantz_it.rfanalyzer.ui.tabs.AboutTabActions
import com.mantz_it.rfanalyzer.ui.tabs.AnalyzerTabs
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationTabActions
import com.mantz_it.rfanalyzer.digiext.ui.DigitalTabActions
import com.mantz_it.rfanalyzer.digiext.ui.digitalOverviewSections
import com.mantz_it.rfanalyzer.ui.tabs.DisplayTabActions
import com.mantz_it.rfanalyzer.ui.tabs.FilesourceFileFormat
import com.mantz_it.rfanalyzer.digiext.ui.PacketDeleteRequest
import com.mantz_it.rfanalyzer.digiext.ui.PacketShareRequest
import com.mantz_it.rfanalyzer.ui.tabs.RecordingTabActions
import com.mantz_it.rfanalyzer.ui.tabs.SettingsTabActions
import com.mantz_it.rfanalyzer.ui.tabs.SourceTabActions
import com.mantz_it.rfanalyzer.ui.tabs.SourceType
import com.mantz_it.rfanalyzer.ui.composable.asSizeInBytesToString
import com.mantz_it.rfanalyzer.ui.composable.asStringWithUnit
import com.mantz_it.rfanalyzer.ui.composable.saturationFunction
import com.mantz_it.rfanalyzer.ui.screens.RecordingScreenActions
import com.mantz_it.rfanalyzer.ui.screens.StationsPage
import com.mantz_it.rfanalyzer.ui.screens.BookmarkManagerScreenActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import com.mantz_it.rfanalyzer.analyzer.UnfinishedRecording
import com.mantz_it.rfanalyzer.analyzer.UnfinishedRecordings
import com.mantz_it.rfanalyzer.analyzer.durationMs
import com.mantz_it.rfanalyzer.analyzer.recordingDetails
import com.mantz_it.rfanalyzer.digiext.ui.common.formatDuration
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import androidx.core.net.toUri
import com.mantz_it.rfanalyzer.database.Band
import com.mantz_it.rfanalyzer.database.BandWithBookmarkList
import com.mantz_it.rfanalyzer.database.BookmarkList
import com.mantz_it.rfanalyzer.database.BookmarkListType
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionRepository
import com.mantz_it.rfanalyzer.digiext.DigitalAction
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionStatus
import com.mantz_it.rfanalyzer.digiext.DigitalStatus
import com.mantz_it.rfanalyzer.digiext.decodes
import com.mantz_it.rfanalyzer.digiext.notice
import com.mantz_it.rfanalyzer.digiext.formatTrialTimeLeft
import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import com.mantz_it.rfanalyzer.digiext.shown
import com.mantz_it.rfanalyzer.digiext.shownOrAnalog
import com.mantz_it.rfanalyzer.digiext.playStorePackage
import com.mantz_it.rfanalyzer.digiext.stampedWithSampleTime
import com.mantz_it.rfanalyzer.database.DownloadState
import com.mantz_it.rfanalyzer.database.StationImporterExporter
import com.mantz_it.rfanalyzer.database.KiwiSdrPublicListRepository
import com.mantz_it.rfanalyzer.network.KiwiSdrPublicEntry
import com.mantz_it.rfanalyzer.network.SntpClient
import com.mantz_it.rfanalyzer.ui.composable.Quintuple
import com.mantz_it.rfanalyzer.ui.tabs.normalizeChannelWidth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.util.EnumMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min
import kotlin.math.roundToInt
import com.mantz_it.rfanalyzer.digiext.db.DigitalFilter
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.PacketQuery
import kotlinx.coroutines.flow.flowOf
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.ui.MapLayer
import com.mantz_it.rfanalyzer.digiext.ui.mapLayerOf
import com.mantz_it.rfanalyzer.database.StationOverlay

/**
 * <h1>RF Analyzer - MainViewModel</h1>
 *
 * Module:      MainViewModel.kt
 * Description: The ViewModel of the app. Contains application logic related to the UI.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


sealed class AppScreen(val route: String, open val subUrl: String = "") {
    data object WelcomeScreen: AppScreen("WelcomeScreen/")
    data object WhatsNewScreen: AppScreen("WhatsNewScreen/")
    data object MainScreen: AppScreen("MainScreen/")
    data object RecordingScreen: AppScreen("RecordingScreen/")
    data class BookmarkManagerScreen(val page: StationsPage? = null): AppScreen("BookmarkManagerScreen/", subUrl = page?.name ?: "")
    data object BookmarksTutorial: AppScreen("StationsTutorial/")
    data object LogFileScreen: AppScreen("LogFileScreen/")
    data object AboutScreen: AppScreen("AboutScreen/")
    data class ManualScreen(override val subUrl: String = "index.html") : AppScreen("ManualScreen/", subUrl)
}

data class SnackbarEvent(val message: String, val buttonText: String? = null, val callback: ((SnackbarResult) -> Unit)? = null)

/** Counts shown in the delete/share scope-selection dialogs, computed once when the dialog opens.
 *  [currentMode] is the mode the Digital tab is showing. */
data class PacketActionCounts(
    val filter: DigitalFilter,
    val matchCount: Int,
    val currentMode: DigitalMode?,
    val modeCount: Int,
    val totalCount: Int,
)

// The clock sync with network time for FT8's Internet time mode (MainViewModel.syncClockWithNetworkTime).
sealed interface ClockSyncState {
    object Idle : ClockSyncState
    object InProgress : ClockSyncState
    data class Error(val message: String) : ClockSyncState
    data class Success(val offsetMs: Long, val server: String) : ClockSyncState
}

/** See MainViewModel.mapFocusRequest. With [track], DigitalMapView keeps recentering on the object
 *  as its position updates, until the user pans or zooms; without, it is a one-shot jump. */
data class MapFocusRequest(val markerId: String, val lat: Double, val lon: Double, val zoom: Double = 12.0, val track: Boolean = false)

// The KiwiSDR public list picker (SourceTab). Not a sealed loading/success/error state, so a
// refresh shows a spinner over the previous results instead of blanking the dialog.
data class KiwiSdrPublicListUiState(
    val entries: List<KiwiSdrPublicEntry> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val appStateRepository: AppStateRepository,
    private val recordingDao:RecordingDao,
    private val stationRepository: StationRepository,
    private val stationImporterExporter: StationImporterExporter,
    private val billingRepository: BillingRepository,
    private val digitalExtensionRepository: DigitalExtensionRepository,
    private val packetRepository: DigitalPacketRepository,
    private val kiwiSdrPublicListRepository: KiwiSdrPublicListRepository,
    private val digitalExtensionStatus: DigitalExtensionStatus,
    private val locationRepository: LocationRepository,
) : ViewModel() {
    companion object {
        private const val TAG = "MainViewModel"
        private const val BASEBAND_RECORDING_LOCK_SNACKBAR_COOLDOWN_MS = 3000L
        private const val DIGITAL_TRIAL_HINT_SECONDS = 5 * 60
        private const val PENDING_PAYMENT_RECHECK_MS = 30_000L
        /** Where a first KiwiSDR session starts (40 m - busy at any time of day). */
        private const val KIWISDR_START_FREQUENCY = 7_100_000L
        /** A failed automatic download of an online list is tried again after this (or its interval, if shorter). */
        private const val AUTO_UPDATE_RETRY_MS = 15 * 60 * 1000L
    }

    // Throttles the "channel frequency is locked" snackbar so continuous gestures (tuner wheel,
    // waterfall drag) don't spam it on every tick.
    private var lastBasebandRecordingLockSnackbarTimestamp = 0L

    // Define UI actions
    private val _uiActions = MutableSharedFlow<UiAction?>()
    val uiActions: SharedFlow<UiAction?> = _uiActions
    sealed class UiAction {
        data object OnStartClicked: UiAction()
        data object OnStopClicked: UiAction()
        data object OnAutoscaleClicked: UiAction()
        data object OnShowLogFileClicked: UiAction()
        data class OnSaveLogToFileClicked(val destUri: Uri): UiAction()
        data object OnShareLogFileClicked: UiAction()
        data object OnDeleteLogFileClicked: UiAction()
        data object OnStartRecordingClicked: UiAction()
        data object OnStopRecordingClicked: UiAction()
        data object OnStartBasebandRecordingClicked: UiAction()
        data object OnStopBasebandRecordingClicked: UiAction()
        data class OnDeleteRecordingClicked(val filePath: String): UiAction()
        data object OnDeleteAllRecordingsClicked: UiAction()
        data class WriteInternalFileToFile(val filename: String, val destUri: Uri): UiAction()
        data class OnShareRecordingClicked(val filename: String): UiAction()
        /** Shares [json] as a file - a Digital tab card's export. */
        data class OnShareJsonExportClicked(val json: String, val filenamePrefix: String): UiAction()
        /** Like [OnShareJsonExportClicked], for an export too large to build as one string: [write] streams it into the file. */
        class OnShareJsonFileClicked(val filenamePrefix: String, val write: suspend (Appendable) -> Unit): UiAction()
        data class RenameFile(val file: File, val newName: String): UiAction()
        /** "Tune to" with a USB SDR selected: start the analyzer if that SDR is attached. */
        data object StartAnalyzerIfUsbSdrAttached: UiAction()
        data class ShowDialog(val title: String, val msg: String, val positiveButton: String? = null, val negativeButton: String? = null, val action: (() -> Unit)? = null, val negativeAction: (() -> Unit)? = null): UiAction()
        data object ShowDonationDialog: UiAction()
        /** Opens Google Play's purchase sheet for [product] (MainActivity passes itself to [purchase]). */
        data class LaunchPurchase(val product: Product): UiAction()
        data class OnStartExternalActivity(val intent: Intent): UiAction()
        data object RetryDigitalExtension: UiAction()
    }
    private fun sendActionToUi(uiAction: UiAction){ viewModelScope.launch { _uiActions.emit(uiAction) } }

    // Recording Database
    val recordings: StateFlow<List<Recording>> = recordingDao.getAllRecordings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), initialValue = emptyList())
    val totalRecordingSizeInBytes: StateFlow<Long> = recordingDao.getAllRecordings()
        .map { recordings -> recordings.sumOf { it.sizeInBytes } }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0L
        )
    private fun insertRecording(recording: Recording, onInsertionCompleted: ((recording: Recording) -> Unit)? = null) = viewModelScope.launch(Dispatchers.IO) {
        val id = recordingDao.insert(recording)
        val insertedRecording = recordingDao.get(id)
        if (onInsertionCompleted != null) {
            withContext(Dispatchers.Main) {
                onInsertionCompleted(insertedRecording)
            }
        }
    }
    private fun deleteRecording(recording: Recording, onDeletionCompleted: (() -> Unit)? = null) = viewModelScope.launch(Dispatchers.IO) {
        recordingDao.delete(recording)
        if (onDeletionCompleted != null) {
            withContext(Dispatchers.Main) {
                onDeletionCompleted()
            }
        }
    }
    /**
     * At start, off the main thread:
     * - Rows whose file is gone are removed: a cloud backup restores the recordings list but not
     *   the recordings (res/xml/backup_rules.xml). One file check per recording.
     * - A recording the app was closed during (force-stopped, crashed, killed) never got its row -
     *   ask whether to keep or delete it (UnfinishedRecordings). Two file checks. Dismissing the
     *   dialog asks again at the next start.
     */
    fun checkRecordingsAtStart(recordingsDir: File) = viewModelScope.launch(Dispatchers.IO) {
        if (appStateRepository.anyRecordingRunning.value) return@launch  // the app's process was running already
        recordingDao.getAllRecordings().first().filter { !File(it.filePath).exists() }.forEach {
            Log.i(TAG, "checkRecordingsAtStart: ${it.filePath} is gone - removing its row")
            recordingDao.delete(it)
        }
        // a file an older version left without its details is described by the current settings
        appStateRepository.sourceType.awaitInitialized()
        appStateRepository.sourceFrequencies[appStateRepository.sourceType.value]?.awaitInitialized()
        appStateRepository.sourceSampleRates[appStateRepository.sourceType.value]?.awaitInitialized()
        appStateRepository.channelFrequency.awaitInitialized()
        appStateRepository.demodulationMode.awaitInitialized()
        appStateRepository.recordingName.awaitInitialized()
        UnfinishedRecordings.find(recordingsDir) { isBaseband -> appStateRepository.recordingDetails(isBaseband) }
            .forEach(::askAboutUnfinishedRecording)
    }

    private fun askAboutUnfinishedRecording(unfinished: UnfinishedRecording) {
        val recording = unfinished.recording
        val kind = if (unfinished.isBaseband) "Baseband recording" else "Raw IQ recording (${recording.fileFormat.displayName})"
        val facts = listOf(
            "'${recording.name}'",
            "$kind, ${recording.frequency.asStringWithUnit("Hz")} at ${recording.sampleRate.asStringWithUnit("Sps")}",
            "Started ${SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(recording.date))}, " +
                "${formatDuration(recording.durationMs())} (${recording.sizeInBytes.asSizeInBytesToString()})",
        ).joinToString("\n")
        val guessed = if (unfinished.detailsKnown) "" else
            "\n\nAn older version of RF Analyzer made this recording without noting its frequency and sample rate: " +
            "shown are the current settings, which may not match."
        sendActionToUi(UiAction.ShowDialog(
            title = "Unfinished recording",
            msg = "RF Analyzer was closed while this recording was running, so it was never finished:\n\n$facts$guessed" +
                  "\n\nKeep it with your recordings, or delete it?",
            positiveButton = "Keep",
            negativeButton = "Delete",
            action = {
                viewModelScope.launch(Dispatchers.IO) {
                    insertRecording(UnfinishedRecordings.keep(unfinished))
                    showSnackbar(SnackbarEvent(message = "Recording '${recording.name}' kept (${recording.sizeInBytes.asSizeInBytesToString()})"))
                }
            },
            negativeAction = {
                viewModelScope.launch(Dispatchers.IO) {
                    UnfinishedRecordings.delete(unfinished)
                    showSnackbar(SnackbarEvent(message = "Unfinished recording '${recording.name}' deleted"))
                }
            },
        ))
    }

    private fun deleteRecordingWithUndo(recording: Recording) {
        deleteRecording(recording) {
            showSnackbar(
                SnackbarEvent(
                    message = "Recording ${recording.name} deleted (${recording.sizeInBytes.asSizeInBytesToString()})",
                    buttonText = "Undo",
                    callback = { snackbarResult ->
                        if (snackbarResult == SnackbarResult.ActionPerformed) {
                            insertRecording(recording)
                        } else {
                            sendActionToUi(UiAction.OnDeleteRecordingClicked(recording.filePath))
                        }
                    }
                )
            )
        }
    }
    private fun deleteAllRecordings() = viewModelScope.launch(Dispatchers.IO) { recordingDao.deleteAllRecordings() }

    // Stations Database
    val allBookmarkLists = stationRepository.getAllBookmarkLists().stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())
    @OptIn(ExperimentalCoroutinesApi::class)
    val displayedStations: StateFlow<List<StationWithBookmarkList>> =
        combine(
            appStateRepository.useCustomFftStationFilter.stateFlow,
            appStateRepository.stationFilterForList.state.stateFlow,
            appStateRepository.stationFilterForFft.state.stateFlow,
            appStateRepository.viewportStartFrequency.stateFlow,
            appStateRepository.viewportEndFrequency.stateFlow
        ) { useCustomFilter, filterList, filterFft, start, end -> Quintuple(useCustomFilter, filterList, filterFft, start, end) }
            .flatMapLatest { (useCustomFilter, filterList, filterFft, start, end) ->
                val bufferZone = appStateRepository.viewportSampleRate.value / 3
                val filter = if(useCustomFilter) filterFft else filterList
                stationRepository.getFilteredStations(filter, minFrequency = start - bufferZone, maxFrequency = end + bufferZone)
            }
            .stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val displayedBands: StateFlow<List<BandWithBookmarkList>> =
        combine(
            appStateRepository.useCustomFftBandFilter.stateFlow,
            appStateRepository.bandFilterForList.state.stateFlow,
            appStateRepository.bandFilterForFft.state.stateFlow,
            appStateRepository.viewportStartFrequency.stateFlow,
            appStateRepository.viewportEndFrequency.stateFlow
        ) { useCustomFilter, filterList, filterFft, start, end -> Quintuple(useCustomFilter, filterList, filterFft, start, end) }
            .flatMapLatest { (useCustomFilter, filterList, filterFft, start, end) ->
                val bufferZone = appStateRepository.viewportSampleRate.value / 3
                val filter = if(useCustomFilter) filterFft else filterList
                stationRepository.getFilteredBands(filter, minFrequency = start - bufferZone, maxFrequency = end + bufferZone)
            }
            .stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())

    val stationFavorites = stationRepository.getFavoriteStations().stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())
    val bandFavorites = stationRepository.getFavoriteBands().stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5_000), initialValue = emptyList())

    // Location (LocationRepository): the sheet is opened from Settings or by a feature that needs more than is known
    val userLocation: StateFlow<UserLocation> = locationRepository.location
    val guessedCountry: StateFlow<Pair<String, CountrySource>?> = locationRepository.guessedCountry
    /** Non-null while the location sheet is open: why it was opened ("" from Settings). */
    private val _locationSheetReason = MutableStateFlow<String?>(null)
    val locationSheetReason: StateFlow<String?> = _locationSheetReason
    fun showLocationSheet(reason: String = "") { _locationSheetReason.value = reason }
    fun dismissLocationSheet() { _locationSheetReason.value = null }
    /** The app came to the front: the phone may be in another country now. */
    fun refreshLocation() = locationRepository.refresh()
    val locationSheetActions = LocationSheetActions(
        onChooseAutomatic = { locationRepository.chooseCountry("") },
        onChooseCountry = locationRepository::chooseCountry,
        onChooseRegion = locationRepository::chooseRegion,
        onChoosePositionMode = locationRepository::choosePositionMode,
        onLocatorChanged = locationRepository::chooseLocator,
        hasLocationPermission = locationRepository::hasLocationPermission,
        onUpdateDeviceFix = { locationRepository.updateDeviceFix(force = true) },
    )

    private val _editedStation = MutableStateFlow<Station?>(null)
    val editedStation: StateFlow<Station?> = _editedStation.asStateFlow()
    fun showEditStationBookmarkSheet(station: Station?) {
        viewModelScope.launch {
            if (station == null) {
                val now = System.currentTimeMillis()
                _editedStation.value = Station(
                    id = 0,
                    bookmarkListId = allBookmarkLists.firstOrNull()?.firstOrNull { it.type == BookmarkListType.STATION }?.id ?: 0,
                    name = "",
                    frequency = appStateRepository.channelFrequency.value,
                    bandwidth = appStateRepository.channelWidth.value,
                    mode = appStateRepository.demodulationMode.value,
                    demodulationParameters = appStateRepository.captureCurrentDemodulationParameters(),
                    favorite = true,
                    createdAt = now,
                    updatedAt = now
                )
            } else {
                _editedStation.value = station
            }
        }
    }
    fun dismissEditStationBookmarkSheet() { _editedStation.value = null }
    private val _editedStationHasChanges = MutableStateFlow(false)
    val editedStationHasChanges = _editedStationHasChanges.asStateFlow()
    fun setEditedStationHasChanges(hasChanges: Boolean) { _editedStationHasChanges.value = hasChanges }

    private val _editedBand = MutableStateFlow<Band?>(null)
    val editedBand: StateFlow<Band?> = _editedBand.asStateFlow()
    fun showEditBandBookmarkSheet(band: Band?) {
        viewModelScope.launch {
            if (band == null) {
                val now = System.currentTimeMillis()
                _editedBand.value = Band(
                    id = 0,
                    bookmarkListId = allBookmarkLists.firstOrNull()?.firstOrNull { it.type == BookmarkListType.BAND }?.id ?: 0,
                    name = "",
                    startFrequency = appStateRepository.viewportStartFrequency.value,
                    endFrequency = appStateRepository.viewportEndFrequency.value,
                    favorite = true,
                    createdAt = now,
                    updatedAt = now
                )
            } else {
                _editedBand.value = band
            }
        }
    }
    fun dismissEditBandBookmarkSheet() { _editedBand.value = null }
    private val _editedBandHasChanges = MutableStateFlow(false)
    val editedBandHasChanges = _editedBandHasChanges.asStateFlow()
    fun setEditedBandHasChanges(hasChanges: Boolean) { _editedBandHasChanges.value = hasChanges }

    private val _selectedStationIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedStations: StateFlow<List<StationWithBookmarkList>> =
        combine(displayedStations, _selectedStationIds) { stationWithBookmarkList, selectedStationIds ->
            stationWithBookmarkList.filter { it.station.id in selectedStationIds }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )
    fun dismissStationSheet() {
        _selectedStationIds.update { emptySet() }
        _expandedStationId.update { null }
    }
    private val _expandedStationId = MutableStateFlow<Long?>(null)
    val expandedStationId: StateFlow<Long?> = _expandedStationId.asStateFlow()
    fun expandStation(station: Station) { _expandedStationId.value = if (_expandedStationId.value == station.id) null else station.id }
    fun insertStation(station: Station) = viewModelScope.launch(Dispatchers.IO) { stationRepository.insertStation(station) }
    fun insertBand(band: Band) = viewModelScope.launch(Dispatchers.IO) { stationRepository.insertBand(band) }
    fun insertBookmarkList(bookmarkList: BookmarkList) {
        viewModelScope.launch(Dispatchers.IO) {
            val newBookmarkListId = stationRepository.insertBookmarkList(bookmarkList)
            // Update currently edited Station/Band (if any) to point to the new bookmarkList
            if (bookmarkList.type == BookmarkListType.STATION && _editedStation.value != null) {
                _editedStation.value = _editedStation.value?.copy(bookmarkListId = newBookmarkListId)
            }
            if (bookmarkList.type == BookmarkListType.BAND && _editedBand.value != null) {
                _editedBand.value = _editedBand.value?.copy(bookmarkListId = newBookmarkListId)
            }
        }
    }
    fun deleteStation(station: Station) = viewModelScope.launch(Dispatchers.IO) {
        stationRepository.deleteStation(station)
        showSnackbar(SnackbarEvent(
            message = "Bookmark deleted.",
            buttonText = "Undo",
            callback = { snackbarResult ->
                if (snackbarResult == SnackbarResult.ActionPerformed) {
                    viewModelScope.launch(Dispatchers.IO) {
                        stationRepository.insertStation(station)
                    }
                }
            }
        )
        )
    }
    fun updateStation(station: Station) = viewModelScope.launch(Dispatchers.IO) { stationRepository.updateStation(station) }
    fun copyStation(station: Station, targetBookmarkList: BookmarkList) = viewModelScope.launch(Dispatchers.IO) {
        stationRepository.copyStation(station, targetBookmarkList)
    }
    fun moveStation(station: Station, targetBookmarkList: BookmarkList) = viewModelScope.launch(Dispatchers.IO) {
        stationRepository.moveStation(station, targetBookmarkList)
    }
    fun exportStation(stationWithBookmarkList: StationWithBookmarkList, uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val bookmarkLists = if (stationWithBookmarkList.bookmarkList != null) listOf(stationWithBookmarkList.bookmarkList) else emptyList()
        stationImporterExporter.export(uri, bookmarkLists, listOf(stationWithBookmarkList.station), emptyList())
    }
    fun buildStationExportJson(stationWithBookmarkList: StationWithBookmarkList): String {
        val bookmarkLists = if (stationWithBookmarkList.bookmarkList != null) listOf(stationWithBookmarkList.bookmarkList) else emptyList()
        return stationImporterExporter.buildExportJson(bookmarkLists, listOf(stationWithBookmarkList.station), emptyList())
    }
    /**
     * Tunes to a station (Tuning.kt): mode, bandwidth and AX.25 variant for good, its finer
     * parameters (squelch, rates, LoRa SF/chirp direction) only while the channel stays on it
     * (StationOverlay.kt); the viewport moves only if the station isn't in it. A station without a
     * mode (a spot of a mode RF Analyzer doesn't demodulate) keeps the current mode and bandwidth.
     */
    fun tuneToStation(station: Station): Boolean {
        val target = station.toTuneTarget()
        tuner.tune(target) {
            target.mode?.digitalMode(appStateRepository.demodulationWfmStereoAndRdsEnabled.value)?.let { onStationStarted(it, target) }
            startAnalyzerAfterTuneTo()
        }
        return true
    }

    /**
     * "Tune to" (a favorite, a label in the FFT, the station sheet, the Bookmark Manager) also starts
     * a stopped analyzer when it can without asking: a file chosen, a server set, or - checked by
     * MainActivity, which has the UsbManager - the selected USB SDR attached. Otherwise it only
     * tunes.
     */
    private fun startAnalyzerAfterTuneTo() {
        if (appStateRepository.analyzerRunning.value || appStateRepository.analyzerStartPending.value) return
        val ready = when (appStateRepository.sourceType.value) {
            SourceType.FILESOURCE -> appStateRepository.filesourceUri.value.isNotEmpty()
            SourceType.KIWISDR -> appStateRepository.kiwiSdrHost.value.isNotBlank()
            SourceType.RTLSDR -> if (appStateRepository.rtlsdrExternalServerEnabled.value) appStateRepository.rtlsdrExternalServerIP.value.isNotBlank() else null
            else -> null
        }
        when (ready) {
            true -> onStartStopClicked()
            null -> sendActionToUi(UiAction.StartAnalyzerIfUsbSdrAttached)
            false -> Unit
        }
    }

    /** Starts the analyzer like its Start button, if it isn't running - see startAnalyzerAfterTuneTo. */
    fun startAnalyzerIfStopped() {
        if (!appStateRepository.analyzerRunning.value && !appStateRepository.analyzerStartPending.value) onStartStopClicked()
    }
    fun moveViewportToBand(band: Band): Boolean {
        val bandMiddleFrequency = (band.endFrequency + band.startFrequency)/2
        val bandBandwidth = band.endFrequency - band.startFrequency
        if (appStateRepository.sourceSignalStartFrequency.value < band.startFrequency
            && appStateRepository.sourceSignalEndFrequency.value > band.endFrequency) {
            setViewportSampleRate(bandBandwidth)
            setViewportFrequency(bandMiddleFrequency)
            moveChannelIntoBand()
            return true
        }
        // Re-tune necessary
        val maxSourceBandwidth =
            if(appStateRepository.sourceAutomaticSampleRateAdjustment.value)
                appStateRepository.sourceSupportedSampleRates.value.last()
            else
                appStateRepository.sourceSampleRate.value
        val actualNewSampleRate = min(bandBandwidth, maxSourceBandwidth)
        val newSourceFrequency = band.startFrequency + actualNewSampleRate/2
        if (newSourceFrequency !in appStateRepository.sourceMinimumPossibleSignalFrequency.value..appStateRepository.sourceMaximumPossibleSignalFrequency.value) {
            showSnackbar(SnackbarEvent(message = "Frequency ${newSourceFrequency.asStringWithUnit("Hz")} is out of range for the current source"))
            return false
        } else if (appStateRepository.recordingRunning.value) {
            sendActionToUi(UiAction.ShowDialog(
                title = "Recording Running",
                msg = "Band-Frequency out of range for the current recording. Stop Recording?",
                positiveButton = "Yes, stop recording!",
                negativeButton = "Cancel",
                action = {
                    stopRecordingForRetune()
                    appStateRepository.sourceFrequency.set(newSourceFrequency)
                    setViewportSampleRate(actualNewSampleRate)
                    setViewportFrequency(band.startFrequency + actualNewSampleRate/2)
                    moveChannelIntoBand()
                }
            ))
        } else {
            appStateRepository.sourceFrequency.set(newSourceFrequency)
            setViewportSampleRate(actualNewSampleRate)
            setViewportFrequency(band.startFrequency + actualNewSampleRate/2)
            moveChannelIntoBand()
        }
        return true
    }

    /** A channel left outside the received signal by a band far away comes along into the view. */
    private fun moveChannelIntoBand() {
        if (!appStateRepository.basebandRecordingRunning.value) appStateRepository.moveChannelIntoSourceSignal()
    }

    private val stationProviderDownloadJobs: EnumMap<OnlineStationProvider, Job?> = EnumMap(OnlineStationProvider.entries.associateWith { null })
    /** When a provider's last download failed - an automatic update waits before trying again. */
    private val stationProviderFailedAt = java.util.concurrent.ConcurrentHashMap<OnlineStationProvider, Long>()

    private val _clockSyncState = MutableStateFlow<ClockSyncState>(ClockSyncState.Idle)
    val clockSyncState: StateFlow<ClockSyncState> = _clockSyncState.asStateFlow()
    private var clockSyncJob: Job? = null

    private val _kiwiSdrPublicListUiState = MutableStateFlow(KiwiSdrPublicListUiState())
    val kiwiSdrPublicListUiState: StateFlow<KiwiSdrPublicListUiState> = _kiwiSdrPublicListUiState.asStateFlow()
    private var kiwiSdrPublicListJob: Job? = null

    fun loadKiwiSdrPublicList(forceRefresh: Boolean = false) {
        kiwiSdrPublicListJob?.cancel()
        kiwiSdrPublicListJob = viewModelScope.launch {
            _kiwiSdrPublicListUiState.update { it.copy(isLoading = true, errorMessage = null) }
            kiwiSdrPublicListRepository.getList(forceRefresh)
                .onSuccess { entries -> _kiwiSdrPublicListUiState.update { it.copy(entries = entries, isLoading = false) } }
                .onFailure { e -> _kiwiSdrPublicListUiState.update { it.copy(isLoading = false, errorMessage = e.message ?: "Failed to load public KiwiSDR list") } }
        }
    }

    // Digital Extension
    val demodStatus = digitalExtensionRepository.demodStatus

    /** When the current capture session started - the Digital tab dims what was heard before it. */
    val currentSessionStartMs = digitalExtensionRepository.currentSessionStartMs
    /** The time of the samples being received - see SourceClock. */
    val sourceClock = digitalExtensionRepository.sourceClock

    val digitalTabFilter = appStateRepository.digitalTabFilter.state.stateFlow
    fun setDigitalTabFilter(filter: DigitalFilter) = appStateRepository.digitalTabFilter.set(filter)

    /** The Digital tab's Overview tables, per mode. */
    val overviewSections = digitalOverviewSections(digitalExtensionRepository, packetRepository, digitalTabFilter, viewModelScope)

    /** How each kind of tracked object with a position is drawn on the Map subtab. */
    internal val mapLayers: Map<ObjectKind, MapLayer<*>> =
        ObjectKind.entries.mapNotNull { kind -> mapLayerOf(kind, packetRepository)?.let { kind to it } }.toMap()

    /** Live position trails by objectKey, for the Map subtab. */
    val mapTrails = digitalExtensionRepository.trails.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val extensionConnectionState = digitalExtensionRepository.extensionConnectionState

    // Packets decoded since app start per mode - for the diagnostics overlay.
    val demodPacketCounts: StateFlow<Map<DigitalMode, Int>> = digitalExtensionRepository.packetCounts

    // Tuning to stations and presets (Tuning.kt).
    private val tuner = Tuner(
        appStateRepository,
        showMessage = { showSnackbar(SnackbarEvent(message = it)) },
        confirm = { title, msg, positive, action ->
            sendActionToUi(UiAction.ShowDialog(title = title, msg = msg, positiveButton = positive, negativeButton = "Cancel", action = action))
        },
        stopRecording = ::stopRecordingForRetune,
        stopBasebandRecording = ::stopBasebandRecordingForRetune,
    )

    /** A station of [mode] was tuned to: it is what the mode sheet's Start starts next, and the
     *  Overview follows its first packets (followFirstHeardSection). */
    private fun onStationStarted(mode: DigitalMode, target: TuneTarget) {
        target.key?.let { appStateRepository.modeSheetLastStations.getValue(mode).set(it) }
        followFirstHeardSection(mode)
    }

    // The mode sheet's stations (ModePresets.kt): the Digital Stations list's rows and the favorites.
    val modeSheetStations = ModeSheetStations(
        presets = combine(
            stationRepository.getModeSheetStations(MODE_SHEET_DEMODULATION_MODES),
            combine(appStateRepository.modeSheetLastStations.map { (mode, setting) -> setting.stateFlow.map { mode to it } }) { it.toMap() },
            locationRepository.location.map { it.position }.distinctUntilChanged(),
        ) { stations, lastUsed, position -> buildModePresets(stations, lastUsed, position) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap()),
        area = locationRepository.location.map { it.areaName }.stateIn(viewModelScope, SharingStarted.Eagerly, null),
        onStart = { mode, target ->
            if (!digitalBlocked()) tuner.tune(target) {
                onStationStarted(mode, target)
                // The Digital tab follows the demodulator only on a real mode change, so restarting the
                // live mode while another one is shown wouldn't switch to it by itself.
                setDigitalTabFilter(digitalTabFilter.value.copy(mode = mode))
                startAnalyzerIfStopped()
            }
        },
        onShowAll = { mode ->
            appStateRepository.stationFilterForList.set(StationFilter(mode = setOf(mode.demodulationMode())))
            navigate(AppScreen.BookmarkManagerScreen(StationsPage.STATIONS))
        },
        onChangeLocation = { showLocationSheet("The presets of the digital modes follow your country or region.") },
    )

    /**
     * After a start, a mode with several Overview sections (LoRa's protocols, AX.25's APRS and
     * packet stations) shows the one its first packet belongs to - which protocol a channel
     * carries isn't known before. Ends with that packet, another start, or the user picking a
     * section.
     */
    private var sectionFollowJob: Job? = null
    private fun followFirstHeardSection(mode: DigitalMode) {
        sectionFollowJob?.cancel()
        val sections = overviewSections[mode]?.takeIf { it.size > 1 } ?: return
        sectionFollowJob = viewModelScope.launch {
            val before = sections.map { it.activity.first() }
            val heard = sections.indices.map { i -> sections[i].activity.filter { it > before[i] }.map { i } }.merge().first()
            appStateRepository.digitalOverviewSections.getValue(mode).set(sections[heard].label)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val filteredPacketsPaging: Flow<PagingData<StoredPacket<*, *>>> = digitalTabFilter.flatMapLatest { filter ->
        // A replay's packets carry the recording's time (in a mode stamped with the samples' time):
        // while it plays, they are listed first.
        filter.toPacketQuery()?.let { query ->
            packetRepository.paging(query, currentFirst = { if (query.mode.stampedWithSampleTime) sourceClock.replayWindow() else null })
        } ?: flowOf(PagingData.empty())
    }.cachedIn(viewModelScope + Dispatchers.Default) // rows are decoded as pages load: off the main thread

    /** Where the maps start without anything else to show, and the user's locator square. */
    val mapHome: StateFlow<MapHome?> = locationRepository.mapHome
    /** The Digital tab Map's camera per shown mode, while the app runs - see DigitalMapView. */
    val digitalMapCameras: MutableMap<String, MapCameraPosition> = mutableMapOf()

    val kiwiSdrMapCameraPosition = appStateRepository.mapCameraPositionForKiwiSdrDialog.state.stateFlow
    fun setKiwiSdrMapCameraPosition(position: MapCameraPosition) = appStateRepository.mapCameraPositionForKiwiSdrDialog.set(position)

    val showMapTrails = appStateRepository.showMapTrails.stateFlow
    val showAllModesOnMap = appStateRepository.showAllModesOnMap.stateFlow

    // One-shot "show this marker on the map" request from a card's "Track on map" action: MainScreen
    // switches to the Digital tab, the Digital tab to its Map subtab, which selects the marker and
    // moves the camera to it; consumeMapFocusRequest() clears it so it doesn't fire again.
    private val _mapFocusRequest = MutableStateFlow<MapFocusRequest?>(null)
    val mapFocusRequest: StateFlow<MapFocusRequest?> = _mapFocusRequest
    fun requestMapFocus(markerId: String, lat: Double, lon: Double, track: Boolean = false) {
        _mapFocusRequest.value = MapFocusRequest(markerId, lat, lon, track = track)
    }
    fun consumeMapFocusRequest() { _mapFocusRequest.value = null }

    // One-shot "show this tab" request (e.g. the Digital tab, when opened from the Digital
    // Extension's launcher icon), observed and cleared by MainScreen, which owns the selected tab.
    private val _tabRequest = MutableStateFlow<AnalyzerTabs?>(null)
    val tabRequest: StateFlow<AnalyzerTabs?> = _tabRequest
    fun requestTab(tab: AnalyzerTabs) { _tabRequest.value = tab }
    fun consumeTabRequest() { _tabRequest.value = null }

    /**
     * The Map subtab's markers: the same tracked objects the Overview subtab lists for the shared
     * filter (same mode, time, frequency and search - one query definition, see
     * `DigitalExtensionRepository.objects`), minus those without a position.
     *
     * With [showAllModesOnMap] it is every mode's objects instead of the shown mode's. That is a
     * property of this one view, not of the filter (which stays single-mode): each family is asked
     * with the filter set to its own mode.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val mapMarkerState = combine(digitalTabFilter, showAllModesOnMap) { filter, showAll -> filter to showAll }.flatMapLatest { (filter, showAll) ->
        fun <S : Any> positioned(family: ObjectFamily<S>) = digitalExtensionRepository.objects(
            family,
            if (showAll) filter.copy(mode = family.kind.mode) else filter,
            withPositionOnly = true,
        )
        val key = if (showAll) "all" else filter.mode?.name ?: "none"
        combine(mapLayers.values.map { positioned(it.family) }) { lists -> MapMarkerState(lists.flatMap { it }, key) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MapMarkerState())

    var pendingDelete by mutableStateOf<PacketActionCounts?>(null)
        private set

    fun requestDelete(filter: DigitalFilter) {
        viewModelScope.launch {
            pendingDelete = computePacketActionCounts(filter)
        }
    }

    fun cancelDelete() { pendingDelete = null }

    fun confirmDelete(request: PacketDeleteRequest) {
        viewModelScope.launch {
            when (request) {
                is PacketDeleteRequest.CurrentMode -> digitalExtensionRepository.clear(request.mode)
                is PacketDeleteRequest.Everything -> digitalExtensionRepository.clear(null)
            }
            pendingDelete = null
        }
    }

    var pendingShare by mutableStateOf<PacketActionCounts?>(null)
        private set

    fun requestShare(filter: DigitalFilter) {
        viewModelScope.launch {
            pendingShare = computePacketActionCounts(filter)
        }
    }

    fun cancelShare() { pendingShare = null }

    fun confirmShare(request: PacketShareRequest) {
        val queries = when (request) {
            is PacketShareRequest.CurrentFilter -> listOfNotNull(request.filter.toPacketQuery())
            is PacketShareRequest.CurrentMode -> listOf(PacketQuery(request.mode))
            is PacketShareRequest.Everything -> DigitalMode.entries.map { PacketQuery(it) }
        }
        sendActionToUi(UiAction.OnShareJsonFileClicked("packets_export") { out -> packetRepository.exportJson(queries, out) })
        pendingShare = null
    }

    private suspend fun computePacketActionCounts(filter: DigitalFilter): PacketActionCounts {
        val currentMode = filter.mode ?: packetRepository.currentPacketMode()
        val matchCount = filter.toPacketQuery()?.let { packetRepository.count(it) } ?: 0
        val modeCount = currentMode?.let { packetRepository.getCountForMode(it) } ?: 0
        val totalCount = packetRepository.getTotalCount()
        return PacketActionCounts(filter, matchCount, currentMode, modeCount, totalCount)
    }



    // Navigation between Screens
    private val _navigationEvent = MutableSharedFlow<AppScreen>(extraBufferCapacity = 1)
    val navigationEvent = _navigationEvent.asSharedFlow()
    fun navigate(screen: AppScreen) { _navigationEvent.tryEmit(screen) }

    /** Opens the manual at [subUrl] - always, unlike the long-press help, which is gated on its setting. */
    fun openManual(subUrl: String) = navigate(AppScreen.ManualScreen(Uri.encode(subUrl)))
    fun openOverloadHelp() = openManual("troubleshooting.html#processing-overload")

    // Snackbar Messages (events collected in MainActivity to show snackbar)
    private val _snackbarEvent = MutableSharedFlow<SnackbarEvent>(extraBufferCapacity = 1)
    val snackbarEvent = _snackbarEvent.asSharedFlow()
    fun showSnackbar(snackbarEvent: SnackbarEvent) { _snackbarEvent.tryEmit(snackbarEvent) }

    // A StateFlow to hold the list of log content
    private val _logContent = MutableStateFlow<List<String>>(listOf("nothing loaded yet"))
    val logContent: StateFlow<List<String>> = _logContent

    // Should a loading indicator be shown?
    private val _isLoadingIndicatorVisible = MutableStateFlow<Boolean>(false)
    val isLoadingIndicatorVisible: StateFlow<Boolean> = _isLoadingIndicatorVisible
    fun showLoadingIndicator(show: Boolean) { _isLoadingIndicatorVisible.update { show }}

    // Digital extension: whether digital modes can be received - see DigitalAvailability.kt
    val digitalStatus: StateFlow<DigitalStatus> = digitalExtensionStatus.status

    /** The activity is back (maybe from the Play Store, after installing or updating RFA Digital). */
    fun recheckDigitalExtension() { digitalExtensionStatus.recheck() }

    fun onDigitalAction(action: DigitalAction) {
        action.playStorePackage()?.let { openUrl("https://play.google.com/store/apps/details?id=$it") }
        when (action) {
            DigitalAction.RETRY -> sendActionToUi(UiAction.RetryDigitalExtension)
            DigitalAction.UNLOCK_DIGITAL_MODES -> showLicenseSheet()
            else -> {}
        }
    }

    /** The trial pill above the drawer, and the digital trial's line in the Digital tab's mode sheet. */
    fun showTrialInfo() {
        val left = appStateRepository.trialSecondsLeft.value
        val digitalLeft = appStateRepository.digitalTrialSecondsLeft.value
        // The Base Version has no operating-time limit, only the digital modes' trial.
        val licensed = appStateRepository.isLicensed.value
        sendActionToUi(UiAction.ShowDialog(
            title = if (licensed) "Digital Modes Trial" else "Trial Version",
            msg = (if (licensed) "" else if (left > 0) "Operating time: ${formatTrialTimeLeft(left)} left of ${AppStateRepository.TRIAL_VERSTION_USAGE_TIME / 60} minutes.\n" else "Operating time: used up - each start runs for 30 seconds.\n") +
                  (if (!DigitalFeatures.shown) "" else if (digitalLeft > 0)
                      "Digital modes: ${formatTrialTimeLeft(digitalLeft)} of decoding left. It counts only while a digital mode is received; looking at what was received is free.\n"
                  else "Digital modes: trial used up - each start decodes for 30 seconds.\n") +
                  (if (licensed) "The full version upgrade unlocks digital modes for good." else "The full version unlocks everything for good."),
            positiveButton = buyFullVersionLabel(),
            negativeButton = "Close",
            action = ::showLicenseSheet,
        ))
    }

    /** Before something digital is started: if it couldn't work, says why (with the way out) and
     *  returns true. A mode that silently receives nothing must never be started. A used-up trial
     *  doesn't block: each start decodes for the grace period, then says so (see init). */
    private fun digitalBlocked(): Boolean {
        val availability = digitalExtensionStatus.recheck()
        if (availability.decodes) return false
        val notice = DigitalStatus(availability).notice() ?: return false
        sendActionToUi(UiAction.ShowDialog(
            title = notice.title,
            msg = notice.text,
            positiveButton = notice.action?.label,
            negativeButton = "Cancel",
            action = notice.action?.let { action -> { onDigitalAction(action) } },
        ))
        return true
    }

    // Billing
    val billingState: StateFlow<BillingState> = billingRepository.state

    private fun buyFullVersionLabel() = if (appStateRepository.license.value == License.BASE) "Upgrade to full version" else "Purchase options"

    // The license sheet: every "buy" and "upgrade" button opens it (LicenseSheet.kt).
    private val _licenseSheetOpen = MutableStateFlow(false)
    val licenseSheetOpen: StateFlow<Boolean> = _licenseSheetOpen
    // What went wrong with the last purchase attempt, shown in the sheet (a snackbar would be hidden behind it).
    private val _licenseSheetMessage = MutableStateFlow<String?>(null)
    val licenseSheetMessage: StateFlow<String?> = _licenseSheetMessage
    private var pendingRecheckJob: Job? = null
    fun showLicenseSheet() {
        _licenseSheetMessage.value = null
        _licenseSheetOpen.value = true
        billingRepository.refresh(withPrices = true)
        // Play reports a pending payment that completes to the running app, but not one that is
        // declined: while the sheet shows one, ask again now and then.
        pendingRecheckJob?.cancel()
        pendingRecheckJob = viewModelScope.launch {
            while (true) {
                delay(PENDING_PAYMENT_RECHECK_MS)
                if (billingRepository.state.value.purchases?.pending?.isNotEmpty() == true) billingRepository.refresh()
            }
        }
    }
    fun dismissLicenseSheet() {
        _licenseSheetOpen.value = false
        _licenseSheetMessage.value = null
        pendingRecheckJob?.cancel()
    }
    fun onLicenseSheetBuy(product: Product) {
        _licenseSheetMessage.value = null
        sendActionToUi(UiAction.LaunchPurchase(product))
    }
    /** In the sheet while it is open, else as a snackbar. */
    private fun reportPurchaseMessage(message: String) {
        if (_licenseSheetOpen.value) _licenseSheetMessage.value = message
        else showSnackbar(SnackbarEvent(message))
    }
    fun retryBilling() = billingRepository.refresh(withPrices = true)

    /** The About tab's license section came into view: its upgrade button shows the price. */
    fun onLicenseSectionShown() {
        if (appStateRepository.license.value != License.FULL) billingRepository.refresh(withPrices = true)
    }

    /** "Check purchases again" (About tab): what Google Play reports for the account in the Play Store. */
    fun checkPurchasesAgain() {
        viewModelScope.launch {
            val answered = billingRepository.query(withPrices = true)
            val purchases = billingRepository.state.value.purchases
            showSnackbar(SnackbarEvent(when {
                !answered || purchases == null -> "Google Play isn't reachable. Please check the internet connection and that you are signed in to the Play Store."
                purchases.owned.isEmpty() && purchases.pending.isEmpty() -> "Google Play reports no purchase for the Google account active in the Play Store."
                purchases.owned.isEmpty() -> "Google Play reports a pending purchase: ${purchases.pending.joinToString(" and ", transform = ::productName)}."
                else -> "Google Play reports: ${ownedProductsText(purchases.owned)}."
            }))
        }
    }

    /** Play's purchase result in words (a decline: Play has shown its own message already). */
    private fun purchaseFailedMessage(responseCode: Int) = when (responseCode) {
        BillingResponse.SERVICE_UNAVAILABLE, BillingResponse.NETWORK_ERROR, BillingResponse.SERVICE_DISCONNECTED, BillingResponse.SERVICE_TIMEOUT ->
            "Google Play couldn't be reached. Please check the internet connection and try again."
        BillingResponse.BILLING_UNAVAILABLE -> "The purchase wasn't completed: Google Play didn't accept the payment."
        BillingResponse.ITEM_UNAVAILABLE -> "Google Play doesn't offer this purchase right now. Please try again later."
        else -> "The purchase wasn't completed (Google Play reported error $responseCode). Please try again later."
    }

    private fun licenseName(license: License) = when (license) {
        License.FULL -> "Full Version"
        License.BASE -> "Base Version"
        License.TRIAL -> "Trial Version"
    }

    /** Google Play no longer reports a purchase: refunded - or, for someone who did pay, another
     *  Google account active in the Play Store, so this says how to get it back. */
    private fun showLicenseLostDialog(previous: License, license: License) {
        sendActionToUi(UiAction.ShowDialog(
            title = "Purchase Not Found",
            msg = "Google Play no longer reports your ${licenseName(previous)}" +
                  (if (license == License.BASE) " - only the Base Version" else "") + ".\n" +
                  "This happens after a refund, or when another Google account is active in the Play Store: " +
                  "switch the Play Store to the account you bought it with, then return to RF Analyzer.",
            positiveButton = "OK",
        ))
    }

    /** Opens Google Play's purchase sheet for [product], or says why not. */
    suspend fun purchase(activity: Activity, product: Product) {
        val message = when (billingRepository.purchase(activity, product)) {
            PurchaseStart.LAUNCHED, PurchaseStart.CANCELLED -> null
            PurchaseStart.NOT_PURCHASABLE -> {
                val purchases = billingRepository.state.value.purchases
                when {
                    purchases == null -> "Google Play hasn't answered yet - please try again in a moment."
                    purchases.pending.isNotEmpty() -> "A purchase is pending: it completes once Google Play confirms the payment."
                    purchases.license == License.FULL -> "Google Play reports that you already own the full version."
                    purchases.license == License.BASE -> "Google Play reports that you own the Base Version: the Full Version Upgrade completes it."
                    else -> "Google Play reports that this purchase isn't possible right now."
                }
            }
            PurchaseStart.PLAY_UNAVAILABLE -> "Google Play is not available. Please check the internet connection and that you are signed in to the Play Store."
            PurchaseStart.PRODUCT_UNAVAILABLE -> "Google Play doesn't offer this purchase right now. Please try again later."
            PurchaseStart.FAILED -> "Google Play couldn't start the purchase. Please try again later."
        }
        message?.let(::reportPurchaseMessage)
    }
    private fun showDigitalTrialEndedDialog() {
        sendActionToUi(UiAction.ShowDialog(
            title = "Digital Modes Trial Ended",
            msg = "The trial's time for digital decoding is used up. Each start still decodes for 30 seconds; everything received so far stays in the Digital tab.\n" +
                  (if (appStateRepository.license.value == License.BASE) "The full version upgrade unlocks digital modes for good." else "The full version unlocks digital modes for good."),
            positiveButton = buyFullVersionLabel(),
            negativeButton = "Close",
            action = ::showLicenseSheet,
        ))
    }
    fun showUsageTimeUsedUpDialog() {
        sendActionToUi(UiAction.ShowDialog(
            title = "End of Trial Version",
            msg = "The 60-minute operation time of the trial version is used up.\n" +
                  "To keep using it without interruption and support its further development, please consider buying RF Analyzer" +
                  (if (DigitalFeatures.shown) ": the Base Version, or the Full Version with the digital modes." else "."),
            positiveButton = buyFullVersionLabel(),
            negativeButton = "Cancel",
            action = ::showLicenseSheet,
        ))
        Log.d(TAG, "showUsageTimeUsedUpDialog")
    }

    // ACTIONS
    // MainScreen ACTIONS ------------------------------------------------------------------------

    // A function rather than a lambda in sourceTabActions: onKiwiSdrPublicReceiverSelected and the
    // tune paths call it too, and sourceTabActions can't reference itself in its initializer.
    private fun onStartStopClicked() {
        if (appStateRepository.analyzerRunning.value || appStateRepository.analyzerStartPending.value) {
            sendActionToUi(UiAction.OnStopClicked)

            // Show FOSS Donation Dialog (when the user presses Stop after a certain interval of app usage has passed):
            if (BuildConfig.IS_FOSS) {
                val counter = appStateRepository.donationDialogCounter.value
                val currentIntervalInSeconds = when (counter) {
                        0 -> 60 * 60          // 1 hour
                        1 -> 60 * 60 * 5      // 5 hours
                        2 -> 60 * 60 * 10     // 10 hours
                        3 -> 60 * 60 * 15     // 15 hours
                        else -> 60 * 60 * 20  // 20 hours
                    }
                if (appStateRepository.appUsageTimeInSeconds.value > appStateRepository.timestampOfLastDonationDialog.value + currentIntervalInSeconds) {
                    Log.i(TAG, "onStartStopClicked: Showing donation dialog (counter: $counter, interval: $currentIntervalInSeconds)")
                    sendActionToUi(UiAction.ShowDonationDialog)
                    appStateRepository.timestampOfLastDonationDialog.set(appStateRepository.appUsageTimeInSeconds.value)
                    appStateRepository.donationDialogCounter.set(counter + 1)
                }
            }
        } else {
            // Verify external server IP/Hostname is valid (RTL-SDR external server or KiwiSDR):
            val hostToValidate = when {
                appStateRepository.sourceType.value == SourceType.RTLSDR && appStateRepository.rtlsdrExternalServerEnabled.value ->
                    appStateRepository.rtlsdrExternalServerIP.value
                appStateRepository.sourceType.value == SourceType.KIWISDR ->
                    appStateRepository.kiwiSdrHost.value
                else -> null
            }
            if (hostToValidate != null) {
                val ipRegex = Regex("^((25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(\\.|$)){4}$")
                val hostnameRegex = Regex("^(?=.{1,253}\$)([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}\$")
                if (!(ipRegex.matches(hostToValidate) || hostnameRegex.matches(hostToValidate))) {
                    showSnackbar(SnackbarEvent(message = "Invalid IP or Hostname Value: $hostToValidate"))
                    Log.w(TAG,"onStartStopClicked: Invalid IP or Hostname: $hostToValidate")
                    return
                }
            }
            appStateRepository.analyzerStartPending.set(true)
            sendActionToUi(UiAction.OnStartClicked)
        }
    }

    val sourceTabActions = SourceTabActions(
        onStartStopClicked = ::onStartStopClicked,
        // the type's range and sample rates follow in AppStateRepository (for every way the type changes)
        onSourceTypeChanged = { newSourceType -> appStateRepository.sourceType.set(newSourceType) },
        onFrequencyChanged = { newFrequency ->
            if (newFrequency >= 0) {
                val updateFrequency: () -> Unit = {
                    appStateRepository.sourceFrequency.set(newFrequency)
                    // if channel is outside of the signal range, reset it to the center freq:
                    if (appStateRepository.channelFrequency.value !in appStateRepository.sourceSignalStartFrequency.value..appStateRepository.sourceSignalEndFrequency.value)
                        appStateRepository.channelFrequency.set(newFrequency)
                }
                if (appStateRepository.anyRecordingRunning.value)
                    sendActionToUi(
                        UiAction.ShowDialog(
                        title = "Stop Recording?",
                        msg = "The recording is still running. Stop recording to change the frequency?",
                        positiveButton = "Yes, stop recording!",
                        negativeButton = "No",
                        action = {
                            stopRecordingForRetune()
                            stopBasebandRecordingForRetune()
                            updateFrequency()
                        }
                    ))
                else
                    updateFrequency()
            }
        },
        onSampleRateChanged = { newSampleRate ->
            if (newSampleRate >= 0) {
                val updateSampleRate: () -> Unit = {
                    appStateRepository.sourceSampleRate.set(newSampleRate)
                    // if channel is outside of the signal range, reset it to the center freq:
                    if (appStateRepository.channelFrequency.value !in appStateRepository.sourceSignalStartFrequency.value..appStateRepository.sourceSignalEndFrequency.value)
                        appStateRepository.channelFrequency.set(appStateRepository.sourceFrequency.value)
                }
                if (appStateRepository.anyRecordingRunning.value)
                    sendActionToUi(
                        UiAction.ShowDialog(
                        title = "Stop Recording?",
                        msg = "The recording is still running. Stop recording to change the sample rate?",
                        positiveButton = "Yes, stop recording!",
                        negativeButton = "No",
                        action = {
                            stopRecordingForRetune()
                            stopBasebandRecordingForRetune()
                            updateSampleRate()
                        }
                    ))
                else
                    updateSampleRate()
            }
        },
        onAutomaticSampleRateAdjustmentChanged = appStateRepository.sourceAutomaticSampleRateAdjustment::set,
        onHackrfVgaGainIndexChanged = appStateRepository.hackrfVgaGainIndex::set,
        onHackrfLnaGainIndexChanged = appStateRepository.hackrfLnaGainIndex::set,
        onHackrfAmplifierEnabledChanged = appStateRepository.hackrfAmplifierEnabled::set,
        onHackrfAntennaPowerEnabledChanged = appStateRepository.hackrfAntennaPowerEnabled::set,
        onHackrfConverterOffsetChanged = appStateRepository.hackrfConverterOffset::set,
        onRtlsdrGainIndexChanged = appStateRepository.rtlsdrGainIndex::set,
        onRtlsdrIFGainIndexChanged = appStateRepository.rtlsdrIFGainIndex::set,
        onRtlsdrAgcEnabledChanged = appStateRepository.rtlsdrAgcEnabled::set,
        onRtlsdrManualGainEnabledChanged = appStateRepository.rtlsdrManualGainEnabled::set,
        onRtlsdrExternalServerEnabledChanged = appStateRepository.rtlsdrExternalServerEnabled::set,
        onRtlsdrExternalServerIPChanged = appStateRepository.rtlsdrExternalServerIP::set,
        onRtlsdrExternalServerPortChanged = appStateRepository.rtlsdrExternalServerPort::set,
        onRtlsdrConverterOffsetChanged = appStateRepository.rtlsdrConverterOffset::set,
        onRtlsdrFrequencyCorrectionChanged = appStateRepository.rtlsdrFrequencyCorrection::set,
        onRtlsdrEnableBiasTChanged = appStateRepository.rtlsdrEnableBiasT::set,
        onRtlsdrDirectSamplingModeChanged = appStateRepository.rtlsdrDirectSamplingMode::set,
        onAirspyAdvancedGainEnabledChanged = appStateRepository.airspyAdvancedGainEnabled::set,
        onAirspyVgaGainChanged = appStateRepository.airspyVgaGain::set,
        onAirspyLnaGainChanged = appStateRepository.airspyLnaGain::set,
        onAirspyMixerGainChanged = appStateRepository.airspyMixerGain::set,
        onAirspyLinearityGainChanged = appStateRepository.airspyLinearityGain::set,
        onAirspySensitivityGainChanged = appStateRepository.airspySensitivityGain::set,
        onAirspyGainPresetChanged = appStateRepository.airspyGainPreset::set,
        onAirspyRfBiasEnabledChanged = appStateRepository.airspyRfBiasEnabled::set,
        onAirspyConverterOffsetChanged = appStateRepository.airspyConverterOffset::set,
        onAirspyHfAgcEnabledChanged = appStateRepository.airspyHfAgcEnabled::set,
        onAirspyHfAgcThresholdChanged = appStateRepository.airspyHfAgcThreshold::set,
        onAirspyHfAttenuationChanged = appStateRepository.airspyHfAttenuation::set,
        onAirspyHfLnaEnabledChanged = appStateRepository.airspyHfLnaEnabled::set,
        onAirspyHfConverterOffsetChanged = appStateRepository.airspyHfConverterOffset::set,
        onHydraSdrAdvancedGainEnabledChanged = appStateRepository.hydraSdrAdvancedGainEnabled::set,
        onHydraSdrVgaGainChanged = appStateRepository.hydraSdrVgaGain::set,
        onHydraSdrLnaGainChanged = appStateRepository.hydraSdrLnaGain::set,
        onHydraSdrMixerGainChanged = appStateRepository.hydraSdrMixerGain::set,
        onHydraSdrLinearityGainChanged = appStateRepository.hydraSdrLinearityGain::set,
        onHydraSdrSensitivityGainChanged = appStateRepository.hydraSdrSensitivityGain::set,
        onHydraSdrGainPresetChanged = appStateRepository.hydraSdrGainPreset::set,
        onHydraSdrRfBiasEnabledChanged = appStateRepository.hydraSdrRfBiasEnabled::set,
        onHydraSdrRfPortIndexChanged = appStateRepository.hydraSdrRfPortIndex::set,
        onHydraSdrConverterOffsetChanged = appStateRepository.hydraSdrConverterOffset::set,
        onKiwiSdrHostChanged = appStateRepository.kiwiSdrHost::set,
        onKiwiSdrPortChanged = appStateRepository.kiwiSdrPort::set,
        onKiwiSdrPasswordChanged = appStateRepository.kiwiSdrPassword::set,
        onKiwiSdrUseTlsChanged = appStateRepository.kiwiSdrUseTls::set,
        onKiwiSdrPublicListRequested = { forceRefresh -> loadKiwiSdrPublicList(forceRefresh) },
        onKiwiSdrPublicReceiverSelected = { entry ->
            appStateRepository.kiwiSdrHost.set(entry.host)
            appStateRepository.kiwiSdrPort.set(entry.port)
            // public receivers are open and listed as plain connections: settings meant for
            // another (private) receiver would make this one fail
            appStateRepository.kiwiSdrUseTls.set(false)
            appStateRepository.kiwiSdrPassword.set("")
            // the first KiwiSDR session starts on the shared default (97 MHz), outside its 0-30 MHz
            if (appStateRepository.sourceFrequency.value !in KiwiSdrSource.MIN_FREQUENCY..KiwiSdrSource.MAX_FREQUENCY)
                appStateRepository.sourceFrequency.set(KIWISDR_START_FREQUENCY)
            onStartStopClicked()
        },
        onOpenFileChosen = { uri, filename -> setFilesourceUri(uri.toString(), filename) },
        onViewRecordingsClicked = { navigate(AppScreen.RecordingScreen) },
        onFilesourceFileFormatChanged = appStateRepository.filesourceFileFormat::set,
        onFilesourceRepeatChanged = appStateRepository.filesourceRepeatEnabled::set,
        onKiwiSdrMapCameraPositionChanged = ::setKiwiSdrMapCameraPosition,
    )

    val displayTabActions = DisplayTabActions(
        onVerticalScaleChanged = { min, max ->
            val (newMin, newMax) = sanitizeVerticalScale(min, max)
            appStateRepository.viewportVerticalScaleMin.set(newMin)
            appStateRepository.viewportVerticalScaleMax.set(newMax)
        },
        onAutoscaleClicked = { sendActionToUi(UiAction.OnAutoscaleClicked) },
        onResetScalingClicked = { analyzerSurfaceActions.onViewportVerticalScaleChanged(Pair(DEFAULT_VERTICAL_SCALE_MIN, DEFAULT_VERTICAL_SCALE_MAX)) },
        onFftSizeChanged = appStateRepository.fftSize::set,
        onAverageLengthChanged = appStateRepository.fftAverageLength::set,
        onPeakHoldEnabledChanged = appStateRepository.fftPeakHold::set,
        onMaxFrameRateChanged = appStateRepository.maxFrameRate::set,
        onColorMapChanged = appStateRepository.waterfallColorMap::set,
        onWaterfallFpsChanged = appStateRepository.waterfallFps::set,
        onDrawingTypeChanged = appStateRepository.fftDrawingType::set,
        onRelativeFrequencyEnabledChanged = appStateRepository.fftRelativeFrequency::set,
        onFftWaterfallRatioChanged = appStateRepository.fftWaterfallRatio::set,
        onFftPaddingRatioChanged = appStateRepository.fftPaddingRatio::set,
        onManualFftSizeEnabledChanged = appStateRepository.manualFftSizeEnabled::set,
    )

    // AX.25's baud rate <-> channel width, both ways (onAx25VariantChanged, onChannelWidthChanged).
    // Index-aligned with DemodulationMode.AX25.channelWidthSteps and DemodulationTab.kt's ax25VariantSteps.
    private val ax25VariantToChannelWidth = listOf(300 to 300, 1200 to 12500, 9600 to 25000)

    // POCSAG's baud rate <-> channel width, many-to-one (Auto/512/1200 share a width); see
    // onChannelWidthChanged.
    private val pocsagBaudRateToChannelWidth = mapOf(0 to 12500, 512 to 12500, 1200 to 12500, 2400 to 25000)

    private fun onAx25VariantChanged(newVariant: Int) {
        appStateRepository.ax25Variant.set(newVariant)
        val newWidth = ax25VariantToChannelWidth.first { it.first == newVariant }.second
        appStateRepository.channelWidth.set(DemodulationMode.AX25.normalizeChannelWidth(newWidth))
    }

    // Hand edits of a parameter a tuned station may set (StationOverlay.kt): the value becomes the
    // user's setting and leaves the overlay.
    private fun setSquelchEnabled(enabled: Boolean) {
        appStateRepository.squelchEnabled.set(enabled)
        appStateRepository.releaseOverlayParameter { copy(squelchEnabled = null) }
    }
    /** [marginDb] above the noise floor (Squelch.kt). */
    private fun setSquelchMargin(marginDb: Float) {
        appStateRepository.squelchMargin.set(marginDb.coerceIn(0f, AppStateRepository.MAX_SQUELCH_MARGIN))
        appStateRepository.releaseOverlayParameter { copy(squelchMarginDb = null) }
    }

    private fun onPocsagBaudRateChanged(newBaudRate: Int) {
        appStateRepository.pocsagBaudRate.set(newBaudRate)
        appStateRepository.releaseOverlayParameter { copy(pocsagBaudRate = null) }
        val newWidth = pocsagBaudRateToChannelWidth.getValue(newBaudRate)
        appStateRepository.channelWidth.set(DemodulationMode.POCSAG.normalizeChannelWidth(newWidth))
    }

    private fun onPocsagBaudRateChangedWithoutWidth(newBaudRate: Int) {
        appStateRepository.pocsagBaudRate.set(newBaudRate)
        appStateRepository.releaseOverlayParameter { copy(pocsagBaudRate = null) }
    }

    // A fixed RTTY frequency shift doubles as its channel width, in Hz-for-Hz lockstep, so the width
    // indicator is a direct visual check that the shift setting matches the signal (see
    // onChannelWidthChanged below and AppStateRepository.kt's rttyFrequencyShift). Auto shift
    // leaves the width alone: the demodulator finds the tones within ±400 Hz either way.
    private fun onRttyFrequencyShiftChanged(newShift: Float) {
        appStateRepository.rttyFrequencyShift.set(newShift)
        appStateRepository.releaseOverlayParameter { copy(rttyFrequencyShift = null) }
        if (newShift != DigitalDemodParams.Rtty.AUTO)
            appStateRepository.channelWidth.set(DemodulationMode.RTTY.normalizeChannelWidth(newShift.roundToInt()))
    }

    private fun onChannelWidthChanged(newWidth: Int) {
        val mode = appStateRepository.demodulationMode.value
        val normalizedWidth = mode.normalizeChannelWidth(newWidth)
        appStateRepository.channelWidth.set(normalizedWidth)
        when (mode) {
            DemodulationMode.AX25 -> {
                val newVariant = ax25VariantToChannelWidth.minByOrNull { abs(it.second - normalizedWidth) }!!.first
                if (newVariant != appStateRepository.ax25Variant.value) appStateRepository.ax25Variant.set(newVariant)
            }
            DemodulationMode.POCSAG -> {
                val currentBaudRate = appStateRepository.pocsagBaudRate.value
                val newBaudRate = when {
                    normalizedWidth >= 25000 -> 2400
                    pocsagBaudRateToChannelWidth[currentBaudRate] == normalizedWidth -> currentBaudRate // already in this bucket, don't fight Auto/512/1200
                    else -> 1200
                }
                if (newBaudRate != currentBaudRate) onPocsagBaudRateChangedWithoutWidth(newBaudRate)
            }
            DemodulationMode.RTTY -> {
                // With auto shift the width is only a visual aid; a fixed shift follows it.
                val currentShift = appStateRepository.rttyFrequencyShift.value
                val newShift = normalizedWidth.toFloat()
                if (currentShift != DigitalDemodParams.Rtty.AUTO && newShift != currentShift) {
                    appStateRepository.rttyFrequencyShift.set(newShift)
                    appStateRepository.releaseOverlayParameter { copy(rttyFrequencyShift = null) }
                }
            }
            else -> {}
        }
    }

    val demodulationTabActions = DemodulationTabActions(
        onDemodulationModeChanged = { newDemodulationMode ->
            val applyModeChange: () -> Unit = {
                // initialize channel freq if it is out of the viewport range:
                var channelFrequency = appStateRepository.channelFrequency.value
                if (appStateRepository.demodulationMode.value == DemodulationMode.OFF && newDemodulationMode != DemodulationMode.OFF) {
                    if (channelFrequency < appStateRepository.viewportStartFrequency.value || channelFrequency > appStateRepository.viewportEndFrequency.value) {
                        channelFrequency = appStateRepository.viewportFrequency.value
                    }
                    appStateRepository.channelFrequency.set(channelFrequency)
                }
                appStateRepository.demodulationMode.set(newDemodulationMode)
            }
            // changing the demodulation mode changes the baseband sample rate, which would corrupt an in-progress baseband recording:
            if (appStateRepository.basebandRecordingRunning.value && newDemodulationMode != appStateRepository.demodulationMode.value)
                sendActionToUi(
                    UiAction.ShowDialog(
                    title = "Stop Recording?",
                    msg = "The baseband recording is still running. Stop recording to change the demodulation mode?",
                    positiveButton = "Yes, stop recording!",
                    negativeButton = "No",
                    action = {
                        stopBasebandRecordingForRetune()
                        applyModeChange()
                    }
                ))
            else
                applyModeChange()
        },
        onDigitalTabClicked = { _ -> digitalBlocked() },
        onWfmStereoAndRdsEnabledChanged = { enabled ->
            if (!enabled || !digitalBlocked()) appStateRepository.demodulationWfmStereoAndRdsEnabled.set(enabled)
        },
        onDigitalAction = ::onDigitalAction,
        onWfmRdsPtyTableChanged = appStateRepository.demodulationWfmRdsPtyTable::set,
        onChannelFrequencyChanged = { newChannelFrequency ->
            setChannelFrequency(newChannelFrequency)
        },
        onTunerWheelDelta = { delta ->
            val minimumStepSize = appStateRepository.tuneStepHz.value
            val absDelta = abs(delta)
            val factor = appStateRepository.viewportSampleRate.value / 1000f / minimumStepSize
            val amplification = factor * saturationFunction(x=absDelta-1, a=3f, k=5f)
            val amplifiedDelta = amplification * delta * (if(appStateRepository.reverseTuningWheel.value) -1 else 1)
            val finalDelta = if (amplifiedDelta > 0) amplifiedDelta.coerceAtLeast(1f) else amplifiedDelta.coerceAtMost( -1f )
            val newChannelFrequency = (appStateRepository.channelFrequency.value + finalDelta.toLong()*minimumStepSize)
            val stepAlignedNewChannelFrequency = newChannelFrequency / minimumStepSize * minimumStepSize
            setChannelFrequency(stepAlignedNewChannelFrequency, askUser = false, onErrorMessage = { }) // don't show any messages or dialogs when the user uses the wheel
        },
        onChannelWidthChanged = ::onChannelWidthChanged,
        onTuneStepHzChanged = appStateRepository.tuneStepHz::set,
        onPocsagBaudRateChanged = ::onPocsagBaudRateChanged,
        onMeshCoreCustomChannelsChanged = appStateRepository.meshCoreCustomChannels::set,
        onAx25VariantChanged = ::onAx25VariantChanged,
        onRttyBaudRateChanged = {
            appStateRepository.rttyBaudRate.set(it)
            appStateRepository.releaseOverlayParameter { copy(rttyBaudRate = null) }
        },
        onRttyFrequencyShiftChanged = ::onRttyFrequencyShiftChanged,
        onRttyPolarityChanged = {
            appStateRepository.rttyPolarity.set(it)
            appStateRepository.releaseOverlayParameter { copy(rttyPolarity = null) }
        },
        onPsk31BaudRateChanged = {
            appStateRepository.psk31BaudRate.set(it)
            appStateRepository.releaseOverlayParameter { copy(psk31BaudRate = null) }
        },
        onR09BaudRateChanged = {
            appStateRepository.r09BaudRate.set(it)
            appStateRepository.releaseOverlayParameter { copy(r09BaudRate = null) }
        },
        onLoraSpreadingFactorsChanged = {
            appStateRepository.loraSpreadingFactors.set(it)
            appStateRepository.releaseOverlayParameter { copy(loraSpreadingFactors = null) }
        },
        onLoraIqModeChanged = {
            appStateRepository.loraIqMode.set(it)
            appStateRepository.releaseOverlayParameter { copy(loraIqMode = null) }
        },
        onStationOverlayDismissed = { appStateRepository.stationOverlay.set(null) },
        onFt8TimingModeChanged = appStateRepository.ft8TimingMode::set,
        onFt8ManualOffsetChanged = appStateRepository.ft8TimeOffsetMs::set,
        onSyncClockClicked = { syncClockWithNetworkTime(showResult = true) },
        onSquelchEnabledChanged = ::setSquelchEnabled,
        onSquelchChanged = ::setSquelchMargin,
        onZoomChanged = { zoom ->
            val newVpSampleRate = ((1f - zoom) * appStateRepository.sourceSampleRate.value).toLong()
            val sampleRateDiff = newVpSampleRate - appStateRepository.viewportSampleRate.value
            val channelToCenterOffset = appStateRepository.viewportFrequency.value - appStateRepository.channelFrequency.value
            val offsetRatio = channelToCenterOffset / (appStateRepository.viewportSampleRate.value.toFloat() / 2)   // -1..1
            val newVpFrequency = appStateRepository.viewportFrequency.value + (sampleRateDiff / 2 * offsetRatio).toLong()
            appStateRepository.viewportSampleRate.set(newVpSampleRate)
            setViewportFrequency(newVpFrequency)
        },
        onAudioMuteClicked = { appStateRepository.audioMuted.set(!appStateRepository.audioMuted.value) },
        onAudioVolumeLevelChanged = appStateRepository.audioVolumeLevel::set,
        onOpenBookmarksClicked = { navigate(AppScreen.BookmarkManagerScreen()) },
        onOverloadHelpClicked = ::openOverloadHelp,
        onAddStationBookmarkClicked = { showEditStationBookmarkSheet(null) },
        onAddBandBookmarkClicked = { showEditBandBookmarkSheet(null) },
        onTuneToStation = { tuneToStation(it) },
        onViewBand = { moveViewportToBand(it) },
        onShareJsonExportClicked = { json, filenamePrefix -> sendActionToUi(UiAction.OnShareJsonExportClicked(json, filenamePrefix)) },
        onTrackOnMapClicked = { markerId, lat, lon -> requestMapFocus(markerId, lat, lon, track = true) },
        onOpenExternalUrlClicked = ::openUrl,
    )

    val recordingTabActions = RecordingTabActions(
        onNameChanged = { appStateRepository.recordingName.set(it.replace('/', '_')) },
        onOnlyRecordWhenSquelchIsSatisfiedChanged = appStateRepository.recordOnlyWhenSquelchIsSatisfied::set,
        onSquelchChanged = ::setSquelchMargin,
        onStopAfterThresholdChanged = { appStateRepository.recordingStopAfterThreshold.set(it.coerceAtLeast(0)) },
        onStopAfterUnitChanged = appStateRepository.recordingstopAfterUnit::set,
        onStartRecordingClicked = {
            if(appStateRepository.recordingRunning.value) {
                sendActionToUi(UiAction.OnStopRecordingClicked)
            } else {
                if(!appStateRepository.isLicensed.value && !BuildConfig.IS_FOSS) {
                    if (appStateRepository.isAppUsageTimeUsedUp.value) {
                        showUsageTimeUsedUpDialog()
                        return@RecordingTabActions
                    }
                }
                // start recording:
                sendActionToUi(UiAction.OnStartRecordingClicked)
            }
        },
        onStartBasebandRecordingClicked = {
            if(appStateRepository.basebandRecordingRunning.value) {
                sendActionToUi(UiAction.OnStopBasebandRecordingClicked)
            } else {
                if(!appStateRepository.isLicensed.value && !BuildConfig.IS_FOSS) {
                    if (appStateRepository.isAppUsageTimeUsedUp.value) {
                        showUsageTimeUsedUpDialog()
                        return@RecordingTabActions
                    }
                }
                // start recording:
                sendActionToUi(UiAction.OnStartBasebandRecordingClicked)
            }
        },
        onViewRecordingsClicked = { navigate(AppScreen.RecordingScreen) }
    )

    val digitalTabActions = DigitalTabActions(
        onFilterChanged = ::setDigitalTabFilter,
        onDeleteClicked = ::requestDelete,
        onShareClicked = ::requestShare,
        onDeleteConfirmed = ::confirmDelete,
        onDeleteCancelled = ::cancelDelete,
        onShareConfirmed = ::confirmShare,
        onShareCancelled = ::cancelShare,
        onShareJsonExportClicked = { json, filenamePrefix -> sendActionToUi(UiAction.OnShareJsonExportClicked(json, filenamePrefix)) },
        onShareQueryClicked = { query, filenamePrefix -> sendActionToUi(UiAction.OnShareJsonFileClicked(filenamePrefix) { out -> packetRepository.exportJson(listOf(query), out) }) },
        onTrackOnMapClicked = { markerId, lat, lon -> requestMapFocus(markerId, lat, lon, track = true) },
        onOpenExternalUrlClicked = ::openUrl,
        onTuneToFrequency = ::tuneToFrequency,
        onShowTrailsChanged = appStateRepository.showMapTrails::set,
        onShowAllModesOnMapChanged = appStateRepository.showAllModesOnMap::set,
        packetCountsByMode = packetRepository::getCountsByMode,
        overviewSection = { mode -> appStateRepository.digitalOverviewSections.getValue(mode).stateFlow },
        onOverviewSectionSelected = { mode, label ->
            sectionFollowJob?.cancel()
            appStateRepository.digitalOverviewSections.getValue(mode).set(label)
        },
        modeSheetStations = modeSheetStations,
        onDigitalTrialClicked = ::showTrialInfo,
        onDigitalAction = ::onDigitalAction,
        onQuickStop = { if (appStateRepository.analyzerRunning.value || appStateRepository.analyzerStartPending.value) sendActionToUi(UiAction.OnStopClicked) },
        onOverloadHelpClicked = ::openOverloadHelp,
        onOpenManual = ::openManual,
    )

    val settingsTabActions = SettingsTabActions(
        onScreenOrientationChanged = appStateRepository.screenOrientation::set,
        onFontSizeChanged = appStateRepository.fontSize::set,
        onKeepChannelCenteredChanged = { newValue ->
            appStateRepository.keepChannelCentered.set(newValue)
            if (newValue)
                setViewportFrequency(appStateRepository.channelFrequency.value)
        },
        onColorThemeChanged = appStateRepository.colorTheme::set,
        onLongPressHelpEnabledChanged = appStateRepository.longPressHelpEnabled::set,
        onReverseTuningWheelChanged = appStateRepository.reverseTuningWheel::set,
        onStartAnalyzerOnUsbAttachChanged = appStateRepository.startAnalyzerOnUsbAttach::set,
        onControlDrawerSideChanged = appStateRepository.controlDrawerSide::set,
        onEnableLowPerformanceModeChanged = appStateRepository.enableLowPerformanceMode::set,
        onLowPerformanceModeFilterQualityChanged = appStateRepository.lowPerformanceModeFilterQuality::set,
        onLocationClicked = { showLocationSheet() },
        onShowDebugInformationChanged = appStateRepository.showDebugInformation::set,
        onLoggingEnabledChanged = appStateRepository.loggingEnabled::set,
        onShowLogClicked = { sendActionToUi(UiAction.OnShowLogFileClicked) },
        onSaveLogToFileClicked = { destUri -> sendActionToUi(UiAction.OnSaveLogToFileClicked(destUri)) },
        onShareLogClicked = { sendActionToUi(UiAction.OnShareLogFileClicked) },
        onDeleteLogClicked = {
            showSnackbar(SnackbarEvent(
                message = "Log file deleted",
                buttonText = "Undo",
                callback = { snackbarResult ->
                    if (snackbarResult != SnackbarResult.ActionPerformed) {
                        sendActionToUi(UiAction.OnDeleteLogFileClicked)
                    }
                }
            )
            )
        },
    )

    val aboutTabActions = AboutTabActions(
        onAboutClicked = { navigate(AppScreen.AboutScreen) },
        onManualClicked = { navigate(AppScreen.ManualScreen()) },
        onTutorialClicked = { screen -> navigate(screen) },
        onLicenseOptionsClicked = ::showLicenseSheet,
        onCheckPurchasesClicked = ::checkPurchasesAgain,
        onLicenseSectionShown = ::onLicenseSectionShown,
        onLegalPageClicked = ::openManual,
    )

    // Surface ACTIONS ------------------------------------------------------------------------
    val analyzerSurfaceActions = AnalyzerSurfaceActions(
        onViewportFrequencyChanged = this::setViewportFrequency,
        onViewportSampleRateChanged = this::setViewportSampleRate,
        onViewportVerticalScaleChanged = { requestedScale ->
            val verticalScalePair = sanitizeVerticalScale(requestedScale.first, requestedScale.second)
            appStateRepository.viewportVerticalScaleMin.set(verticalScalePair.first)
            appStateRepository.viewportVerticalScaleMax.set(verticalScalePair.second)
        },
        onChannelFrequencyChanged = { newFrequency ->
            // A tap or drag in the plot is a gesture like the tuner wheel: no dialogs, and during a
            // baseband recording only the throttled "locked" snackbar (setChannelFrequency).
            setChannelFrequency(newFrequency, askUser = false, onErrorMessage = { })
        },
        onChannelWidthChanged = ::onChannelWidthChanged,
        // the line is dragged in the plot's dB: it moves the margin above the noise floor by as much
        onSquelchChanged = { lineDb -> setSquelchMargin(appStateRepository.effectiveSquelchMargin.value + lineDb - appStateRepository.squelchThresholdDb.value) },
        onStationsClicked = { stationIds, longPress ->
            if (stationIds.size == 1 && !longPress) {
                // Short Press on single Station -> tune directly to station
                val station = displayedStations.value.firstOrNull { it.station.id == stationIds.first() }?.station
                if (station != null) tuneToStation(station)
            } else {
                // Otherwise show the BottomSheet with Stations (a single one opened):
                _selectedStationIds.update { stationIds }
                _expandedStationId.value = stationIds.singleOrNull()
            }
        }
    )

    // RecordingScreen ACTIONS ------------------------------------------------------------------------
    val recordingsScreenActions = RecordingScreenActions(
        onDelete = { recording -> deleteRecordingWithUndo(recording) },
        onPlay = { recording ->
            showLoadingIndicator(true)
            // function which loads the recording and starts the analyzer:
            fun playRecording() {
                if(!appStateRepository.analyzerRunning.value) {
                    appStateRepository.sourceType.set(SourceType.FILESOURCE)
                    appStateRepository.filesourceUri.set(recording.filePath)
                    appStateRepository.filesourceFilename.set(File(recording.filePath).name)
                    appStateRepository.sourceFrequency.set(recording.frequency)
                    appStateRepository.sourceSampleRate.set(recording.sampleRate)
                    appStateRepository.filesourceFileFormat.set(recording.fileFormat)
                    appStateRepository.filesourceTimestamp.set(recording.date)
                    navigate(AppScreen.MainScreen)
                    Log.i(TAG, "recordingScreenActions.onPlay: Start playing")
                    sendActionToUi(UiAction.OnStartClicked)
                    showLoadingIndicator(false)
                }
            }
            if(appStateRepository.analyzerRunning.value) {
                Log.i(TAG, "recordingScreenActions.onPlay: Stopping analyzer")
                sendActionToUi(UiAction.OnStopClicked)  // stop current analyzer
                Log.i(TAG, "recordingScreenActions.onPlay: Start playing in 2 seconds...")
                viewModelScope.launch {
                    delay(2000)  // delay for 2 seconds to give the analyzer time to shut down properly
                    playRecording()
                }
            } else {
                playRecording() // start playing immediately if analyzer was off previously
            }
        },
        onToggleFavorite = { recording ->
            viewModelScope.launch(Dispatchers.IO) {
                recordingDao.toggleFavorite(recording.id)
            }
        },
        onSaveToStorage = { recording, destUri -> sendActionToUi(UiAction.WriteInternalFileToFile(recording.filePath, destUri)) },
        onShare = { recording -> sendActionToUi(UiAction.OnShareRecordingClicked(recording.filePath)) },
        onDeleteAll = {
            sendActionToUi(UiAction.ShowDialog(
                title = "Delete ALL Recordings?",
                msg = "Do you really want to delete all recordings (cannot be un-done)?",
                positiveButton = "Yes, delete all!",
                negativeButton = "Cancel",
                action = {
                    deleteAllRecordings()
                    sendActionToUi(UiAction.OnDeleteAllRecordingsClicked)
                }
            ))
        },
        onToggleDisplayOnlyFavorites = { appStateRepository.displayOnlyFavoriteRecordings.set(!appStateRepository.displayOnlyFavoriteRecordings.value) },
        renameRecording = { recording, newNameRaw ->
            viewModelScope.launch(Dispatchers.IO) {
                val newName = newNameRaw.replace('/', '_')
                recordingDao.rename(recording.id, newName)
                val renamedRecording = recordingDao.get(recording.id)
                val oldFile = File(recording.filePath)
                val newFileName = renamedRecording.calculateFileName()
                sendActionToUi(UiAction.RenameFile(oldFile, newFileName))
                val newPath = "${oldFile.parent}/${newFileName}"
                recordingDao.setFilePath(recording.id, newPath)
            }
        }
    )

    // BookmarkManagerScreen ACTIONS ------------------------------------------------------------------------
    val bookmarkManagerScreenActions = BookmarkManagerScreenActions(
        tuneTo = { tuneToStation(it) },
        moveViewportToBand = { moveViewportToBand(it) },
        openCallsign = { openCallsign(it) },
        openLocation = { openLocation(it) },
        startStationProviderDownload = { startStationProviderDownload(it) },
        cancelStationProviderDownload = { cancelStationProviderDownload(it) },
        showBookmarkManagerTutorial = { navigate(AppScreen.BookmarksTutorial) }
    )

    init {
        Log.d(TAG, "init: MainViewModel initializing...")

        // The Digital tab's shown mode follows the tuned digital mode (DigitalFilter): set while
        // unset and on every real mode change, so a mode picked in between lasts until the next
        // one; an analog mode keeps the last shown. previousMode/seenFirstMode tell a real change
        // from combine() re-emitting the same mode (Stereo & RDS switched while another mode is tuned).
        viewModelScope.launch {
            var previousMode: DigitalMode? = null
            var seenFirstMode = false
            combine(
                appStateRepository.demodulationMode.stateFlow,
                appStateRepository.demodulationWfmStereoAndRdsEnabled.stateFlow,
            ) { _, _ -> packetRepository.currentPacketMode() }
                .collect { mode ->
                    val current = digitalTabFilter.value
                    if (mode != null && (current.mode == null || (seenFirstMode && mode != previousMode))) {
                        setDigitalTabFilter(current.copy(mode = mode))
                    }
                    previousMode = mode
                    seenFirstMode = true
                }
        }

        // Create default bookmarkLists and OnlineStationProviderSettings
        viewModelScope.launch {
            stationRepository.createDefaultBookmarkListsIfMissing()
            stationRepository.createOnlineStationProviderSettingsIfMissing()
        }

        // The digital station list's rows follow the user's area (and a newer bundled list after an
        // app update) - and whether digital features are shown (DigitalFeatures)
        viewModelScope.launch {
            locationRepository.awaitLoaded()
            combine(locationRepository.location.map { DigitalStationList.areaKey(it) }, DigitalFeatures.shownFlow) { area, shown -> area to shown }
                .distinctUntilChanged().collect {
                    stationRepository.applyDigitalStationList()
                }
        }

        // Automatically Download OnlineStationProviders according to user settings
        viewModelScope.launch {
            delay(15*1000) // Wait 15s after start. Gives the app time to initialize.
            fun tickerFlow(periodMs: Long = 10_000L) = flow {
                while (true) { emit(Unit); delay(periodMs) }
            }
            combine(
                stationRepository.getOnlineStationProviderSettings(),
                tickerFlow() // triggers every 10s
            ) { settings, _ -> settings }.collect { settings ->
                val now = System.currentTimeMillis()
                for (setting in settings) {
                    val provider = OnlineStationProvider.fromDbId(setting.id) ?: continue
                    val timeSinceLastUpdateSeconds = (now - setting.lastUpdatedTimestamp) / 1000
                    // after a failure (offline, server down) not again on every tick
                    val retryAfterMs = minOf(setting.autoUpdateIntervalSeconds * 1000L, AUTO_UPDATE_RETRY_MS)
                    val failedRecently = stationProviderFailedAt[provider]?.let { now - it < retryAfterMs } == true
                    if (setting.autoUpdateEnabled && timeSinceLastUpdateSeconds >= setting.autoUpdateIntervalSeconds && !failedRecently) {
                        startStationProviderDownload(provider)
                    }
                }
            }
        }

        viewModelScope.launch {
            appStateRepository.isFullVersion.awaitInitialized()
            appStateRepository.isBaseVersion.awaitInitialized()
            // The usage time is saved every 30 s while the analyzer runs (AnalyzerService), so each
            // change is one 30 s step: a used-up trial stops the analyzer at the first save after
            // its start - the trial's grace period.
            viewModelScope.collectAppState(appStateRepository.appUsageTimeInSeconds) {
                if (BuildConfig.IS_FOSS || !appStateRepository.analyzerRunning.value) return@collectAppState
                // A digital mode runs on its own trial (AppStateRepository.digitalFeaturesUnlocked) -
                // also with the Base Version -, with the same grace period: its end, or a start
                // after it, stops here.
                if (appStateRepository.demodulationMode.value.isDigitalExtension) {
                    if (!appStateRepository.digitalFeaturesUnlocked.value) {
                        sendActionToUi(UiAction.OnStopClicked)
                        showDigitalTrialEndedDialog()
                    }
                } else if (!appStateRepository.isLicensed.value && appStateRepository.isAppUsageTimeUsedUp.value) {
                    sendActionToUi(UiAction.OnStopClicked)
                    showUsageTimeUsedUpDialog()
                }
            }
        }

        // Channel widths stored by 2.2.1 outside today's ranges (WFM up to 150 kHz, NFM down to
        // 3 kHz) are normalized once: the display, squelch and new bookmarks use the stored width
        viewModelScope.launch {
            for ((mode, setting) in appStateRepository.channelWidths) {
                setting.awaitInitialized()
                val normalized = mode.normalizeChannelWidth(setting.value)
                if (normalized != setting.value) setting.set(normalized)
            }
        }

        // A digital mode while digital features are hidden (DigitalFeatures): left from a backup
        // restored into the FOSS build, or the extension removed.
        viewModelScope.launch {
            appStateRepository.demodulationMode.awaitInitialized()
            DigitalFeatures.shownFlow.collect { shown ->
                val mode = appStateRepository.demodulationMode.value
                if (!mode.shown(shown)) appStateRepository.demodulationMode.set(mode.shownOrAnalog(shown) ?: DemodulationMode.OFF)
            }
        }

        // The digital trial: a hint 5 minutes before it ends (the end itself stops the analyzer
        // with a dialog above; the Digital tab's notice stays with the buy button).
        viewModelScope.launch {
            appStateRepository.digitalTrialUsedSeconds.awaitInitialized()
            var previousLeft = appStateRepository.digitalTrialSecondsLeft.value
            viewModelScope.collectAppState(appStateRepository.digitalTrialSecondsLeft) { left ->
                if (appStateRepository.digitalTrialRunning.value &&
                    previousLeft > DIGITAL_TRIAL_HINT_SECONDS && left in 1..DIGITAL_TRIAL_HINT_SECONDS)
                    showSnackbar(SnackbarEvent("${formatTrialTimeLeft(left)} of the digital modes trial left"))
                previousLeft = left
            }
        }

        // A license change Google Play reported - not the cached license at start.
        if (!BuildConfig.IS_FOSS) {
            viewModelScope.launch {
                appStateRepository.isFullVersion.awaitInitialized()
                appStateRepository.isBaseVersion.awaitInitialized()
                var previous = appStateRepository.license.value
                viewModelScope.collectAppState(appStateRepository.license) { license ->
                    if (license == previous) return@collectAppState
                    Log.i(TAG, "init (collect license): $previous -> $license")
                    if (license > previous) {
                        dismissLicenseSheet()
                        showSnackbar(SnackbarEvent("RF Analyzer ${licenseName(license)} unlocked - thank you!"))
                    } else showLicenseLostDialog(previous, license)
                    previous = license
                }
            }
            viewModelScope.launch {
                billingRepository.events.collect { event ->
                    when (event) {
                        // the open sheet shows a pending payment itself
                        is BillingEvent.PurchasePending -> if (!_licenseSheetOpen.value)
                            showSnackbar(SnackbarEvent("Payment pending: the ${productName(event.product)} unlocks as soon as Google Play confirms the payment."))
                        is BillingEvent.AlreadyOwned -> reportPurchaseMessage("Google Play reports this purchase as already made - checking your purchases.")
                        is BillingEvent.PurchaseFailed -> reportPurchaseMessage(purchaseFailedMessage(event.responseCode))
                    }
                }
            }
        }

        // FT8's Internet time needs a synced clock: sync whenever it comes into use and isn't synced yet
        viewModelScope.launch {
            combine(
                appStateRepository.ft8TimingMode.stateFlow,
                appStateRepository.demodulationMode.stateFlow,
                appStateRepository.analyzerRunning.stateFlow,
            ) { timing, mode, running -> timing == Ft8TimingMode.INTERNET_TIME && mode == DemodulationMode.FT8 && running }
                .distinctUntilChanged()
                .collect { needed -> if (needed && _clockSyncState.value !is ClockSyncState.Success) syncClockWithNetworkTime(showResult = false) }
        }

        // Observe and handle the analyzer events
        viewModelScope.launch {
            appStateRepository.analyzerEvents.collect { event ->
                when (event) {
                    // Stored by the service (AnalyzerService.storeFinishedRecording) - only the snackbar here.
                    is AppStateRepository.AnalyzerEvent.RecordingFinished -> {
                        val recording = event.recording
                        showSnackbar(SnackbarEvent(
                            message = "Recording '${recording.name}' finished (${recording.sizeInBytes.asSizeInBytesToString()})",
                            buttonText = "Delete Recording",
                            callback = { askDeletionResult ->
                                if(askDeletionResult == SnackbarResult.ActionPerformed) deleteRecordingWithUndo(recording)
                            }
                        ))
                    }
                    is AppStateRepository.AnalyzerEvent.SourceFailure ->
                        showSnackbar(SnackbarEvent(message = event.message))
                    is AppStateRepository.AnalyzerEvent.IQSourceInformation ->
                        showSnackbar(SnackbarEvent(message = event.message))
                    null -> Log.e(TAG, "Event from AnalyzerService was null!")
                }
            }
        }
    }

    private fun setViewportSampleRate(newSampleRate: Long) {
        appStateRepository.viewportSampleRate.set(newSampleRate)

        // Automatically re-adjust the sample rate of the source if we zoom too far out or in (only if not recording!)
        if (appStateRepository.sourceAutomaticSampleRateAdjustment.value && appStateRepository.analyzerRunning.value && !appStateRepository.anyRecordingRunning.value) {
            val optimalSampleRates = appStateRepository.sourceSupportedSampleRates.value
            val bestSampleRate = optimalSampleRates.firstOrNull { it > appStateRepository.viewportSampleRate.value } ?: optimalSampleRates.last()
            if(appStateRepository.sourceSampleRate.value != bestSampleRate) {
                appStateRepository.sourceSampleRate.set(bestSampleRate)
            }
        }
    }

    private fun setViewportFrequency(newViewportFrequnecy: Long) {
        val coercedFrequency = newViewportFrequnecy.coerceIn(appStateRepository.sourceSignalStartFrequency.value, appStateRepository.sourceSignalEndFrequency.value).coerceAtLeast(0)
        appStateRepository.viewportFrequency.set(coercedFrequency)

        if (appStateRepository.keepChannelCentered.value)
            appStateRepository.channelFrequency.set(coercedFrequency)

        // Automatically re-tune the source if we scrolled the samples out of the visible window:
        if (appStateRepository.analyzerRunning.value &&
            (appStateRepository.sourceSignalEndFrequency.value < appStateRepository.viewportEndFrequency.value ||
                    appStateRepository.sourceSignalStartFrequency.value > appStateRepository.viewportStartFrequency.value)
        ) {
            if(!appStateRepository.anyRecordingRunning.value) {
                val tuneFreq = coercedFrequency.coerceIn(appStateRepository.sourceMinimumFrequency.value, appStateRepository.sourceMaximumFrequency.value)
                appStateRepository.sourceFrequency.set(tuneFreq)
            }
        }
    }

    /** The RDS stations' "Tune" action (and their EON frequencies): retunes like the frequency chooser. */
    fun tuneToFrequency(frequencyHz: Long) = setChannelFrequency(frequencyHz)

    /** Keeps the viewport on a new channel frequency inside the current source signal. A [jump]
     *  (bookmark, typed frequency) centers the channel unless it already sits comfortably inside the
     *  view; a continuous gesture (tuner wheel, waterfall drag) only drags the nearer edge along. */
    private fun moveViewportToChannel(newChannelFrequency: Long, jump: Boolean) {
        if (appStateRepository.keepChannelCentered.value) {
            setViewportFrequency(newChannelFrequency)
            return
        }
        val viewportStart = appStateRepository.viewportStartFrequency.value
        val viewportEnd = appStateRepository.viewportEndFrequency.value
        val span = appStateRepository.viewportSampleRate.value
        if (jump) {
            val margin = span / 10
            if (newChannelFrequency in (viewportStart + margin)..(viewportEnd - margin)) return
            // Don't center past the edge of the received signal: the channel stays visible either way.
            val sourceStart = appStateRepository.sourceSignalStartFrequency.value
            val sourceEnd = appStateRepository.sourceSignalEndFrequency.value
            val centered = if (sourceEnd - sourceStart > span)
                newChannelFrequency.coerceIn(sourceStart + span / 2, sourceEnd - span / 2)
            else
                newChannelFrequency
            setViewportFrequency(centered)
        } else if (newChannelFrequency !in viewportStart..viewportEnd) {
            setViewportFrequency(if (newChannelFrequency < viewportStart) newChannelFrequency + span / 2 else newChannelFrequency - span / 2)
        }
    }

    /** Stops the IQ recording before a retune ("Yes, stop recording!"): sends the stop and clears
     *  the state at once (the service clears it too when the file is closed), so the retune that
     *  follows doesn't ask again. */
    private fun stopRecordingForRetune() {
        if (!appStateRepository.recordingRunning.value) return
        sendActionToUi(UiAction.OnStopRecordingClicked)
        appStateRepository.recordingRunning.set(false)
    }

    /** The same for the baseband recording. */
    private fun stopBasebandRecordingForRetune() {
        if (!appStateRepository.basebandRecordingRunning.value) return
        sendActionToUi(UiAction.OnStopBasebandRecordingClicked)
        appStateRepository.basebandRecordingRunning.set(false)
    }

    /** [newChannelWidth] is the width the channel will have once [onSuccess] ran (a bookmark brings
     *  its own) - a re-tune sizes its DC-peak offset by it. */
    private fun setChannelFrequency(newChannelFrequency: Long, askUser: Boolean = true, onErrorMessage: ((String) -> Unit)? = null, newChannelWidth: Int = appStateRepository.channelWidth.value, onSuccess: (() -> Unit)? = null) {
        // Baseband recording locks the channel frequency: ask for confirmation on deliberate changes.
        // Continuous gestures (tuner wheel, waterfall tap/drag - askUser=false) are silently ignored,
        // but show a cooldown-throttled snackbar so the user gets at least some feedback.
        if (appStateRepository.basebandRecordingRunning.value && newChannelFrequency != appStateRepository.channelFrequency.value) {
            if (askUser) {
                sendActionToUi(UiAction.ShowDialog(
                    title = "Stop Recording?",
                    msg = "The baseband recording is still running. Stop recording to change the channel frequency?",
                    positiveButton = "Yes, stop recording!",
                    negativeButton = "No",
                    action = {
                        stopBasebandRecordingForRetune()
                        setChannelFrequency(newChannelFrequency, askUser, onErrorMessage, newChannelWidth, onSuccess)
                    }
                ))
            } else {
                val now = System.currentTimeMillis()
                if (now - lastBasebandRecordingLockSnackbarTimestamp > BASEBAND_RECORDING_LOCK_SNACKBAR_COOLDOWN_MS) {
                    lastBasebandRecordingLockSnackbarTimestamp = now
                    showSnackbar(SnackbarEvent(message = "Channel frequency is locked while a baseband recording is running"))
                }
            }
            return
        }
        if (newChannelFrequency in appStateRepository.sourceSignalStartFrequency.value..appStateRepository.sourceSignalEndFrequency.value) {
            moveViewportToChannel(newChannelFrequency, jump = askUser)
            appStateRepository.channelFrequency.set(newChannelFrequency)
            if (onSuccess != null) onSuccess()
        }
        else {
            // Re-tune necessary
            val retuneAndUpdateChannel: () -> Unit = {
                // TODO: if automatic sample rate is set, we need to include the edge case where the channel would be in range if we increase the sample rate
                // Avoid the DC peak by not tuning directly to the channel, but keep the whole channel
                // inside the received signal (a wide channel's 2x offset can exceed half the sample rate).
                val sampleRate = appStateRepository.sourceSampleRate.value
                val dcOffset = if (!appStateRepository.sourceType.value.hasDcSpike) 0L
                    else minOf(2L * newChannelWidth, (sampleRate - newChannelWidth) / 2).coerceAtLeast(0)
                val newSourceFrequency = (newChannelFrequency + dcOffset)
                    .coerceIn(appStateRepository.sourceMinimumFrequency.value, appStateRepository.sourceMaximumFrequency.value)
                appStateRepository.sourceFrequency.set(newSourceFrequency)
                moveViewportToChannel(newChannelFrequency, jump = askUser)
                appStateRepository.channelFrequency.set(newChannelFrequency)
                if (onSuccess != null) onSuccess()
            }
            if (newChannelFrequency !in appStateRepository.sourceMinimumPossibleSignalFrequency.value..appStateRepository.sourceMaximumPossibleSignalFrequency.value) {
                val errorMsg = "Frequency ${newChannelFrequency.asStringWithUnit("Hz")} is out of range for the current source"
                if (onErrorMessage != null) onErrorMessage(errorMsg)
                else showSnackbar(SnackbarEvent(message = errorMsg))
            } else if (appStateRepository.recordingRunning.value) {
                if (askUser) {
                    sendActionToUi(UiAction.ShowDialog(
                        title = "Recording Running",
                        msg = "Frequency out of range for the current recording. Stop Recording?",
                        positiveButton = "Yes, stop recording!",
                        negativeButton = "Cancel",
                        action = {
                            stopRecordingForRetune()
                            retuneAndUpdateChannel()
                        }
                    ))
                } else if (onErrorMessage != null) onErrorMessage("Frequency out of range for the current recording")
            } else {
                retuneAndUpdateChannel()
            }
        }
    }

    fun setFilesourceUri(uri: String, filename: String?) {
        var fileFormat = appStateRepository.filesourceFileFormat.value
        var frequency  = appStateRepository.sourceFrequency.value
        var sampleRate = appStateRepository.sourceSampleRate.value
        var timestamp = System.currentTimeMillis()

        // What the file name tells (RecordingFileName): format, frequency, sample rate, start time
        if (filename != null) {
            val parsed = RecordingFileName.parse(filename)
            fileFormat = parsed.fileFormat ?: fileFormat
            frequency = parsed.frequencyHz ?: frequency
            sampleRate = parsed.sampleRate ?: sampleRate
            timestamp = parsed.startTimeMs ?: timestamp
        }
        Log.i(TAG, "setFilesourceUri: [uri=$uri] [filename=$filename] [fileFormat=$fileFormat] [frequency=$frequency] [sampleRate=$sampleRate]")
        appStateRepository.sourceType.set(SourceType.FILESOURCE)
        appStateRepository.filesourceUri.set(uri)
        appStateRepository.filesourceFilename.set(filename ?: uri)
        appStateRepository.sourceFrequency.set(frequency)
        appStateRepository.sourceSampleRate.set(sampleRate)
        appStateRepository.filesourceFileFormat.set(fileFormat)
        appStateRepository.filesourceTimestamp.set(timestamp)
    }

    fun loadLogs(logFile: File) {
        showLoadingIndicator(true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lines = logFile.readLines()
                _logContent.value = lines
            } catch (e: Exception) {
                _logContent.value = listOf("Error loading log file: ${e.message}")
            } finally {
                showLoadingIndicator(false)
            }
        }
    }

    /** Open the location in a map app or external website */
    fun openLocation(station: Station) {
        val coords = station.coordinates
        val uri = if (coords != null) "geo:${coords.latitude},${coords.longitude}?q=${coords.latitude},${coords.longitude}".toUri()
        else if (station.countryCode != null && station.address != null) "geo:0,0?q=${station.countryCode},${Uri.encode(station.address)}".toUri()
        else if (station.countryCode != null) "geo:0,0?q=${station.countryCode}".toUri()
        else if (station.address != null) "geo:0,0?q=${Uri.encode(station.address)}".toUri()
        else null
        if (uri == null) return
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        sendActionToUi(UiAction.OnStartExternalActivity(intent))
    }

    /** Open the QRZ.com page for the callsign */
    fun openCallsign(callsign: String) {
        if (callsign.isBlank()) return
        openUrl("https://www.qrz.com/db/${Uri.encode(callsign)}")
    }

    /** Opens a URL or URI (http(s):, geo:, ...) in an external app - the digital cards' external
     *  lookups and map links (PacketCardWidgets.kt), the Play Store pages. */
    fun openUrl(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        sendActionToUi(UiAction.OnStartExternalActivity(intent))
    }

    fun startStationProviderDownload(provider: OnlineStationProvider) {
        val downloadState = appStateRepository.onlineStationDownloadState.getValue(provider)
        var job = stationProviderDownloadJobs.getValue(provider)
        if (job != null) {
            Log.w(TAG, "startStationProviderDownload: Download Job for ${provider.displayName} already running!")
            return
        }
        job = viewModelScope.launch {
            try {
                Log.i(TAG, "startStationProviderDownload: Download Job for ${provider.displayName} started!")
                downloadState.set(DownloadState.InProgress)
                stationRepository.startOnlineStationProviderUpdate(provider)
                Log.i(TAG, "startStationProviderDownload: Download Job for ${provider.displayName} successful!")
                downloadState.set(DownloadState.Success)
                stationProviderFailedAt.remove(provider)
            } catch (e: CancellationException) {
                Log.i(TAG, "startStationProviderDownload: Download Job for ${provider.displayName} canceled!")
                downloadState.set(DownloadState.Idle)
                throw e // <- MUST rethrow (for smooth cancellation)
            } catch (e: Exception) {
                Log.w(TAG, "startStationProviderDownload: Error during Download Job for ${provider.displayName}: ${e.message}")
                stationProviderFailedAt[provider] = System.currentTimeMillis()
                downloadState.set(DownloadState.Error(e.message ?: "Unknown error"))
            } finally {
                stationProviderDownloadJobs[provider] = null
            }
        }
        stationProviderDownloadJobs[provider] = job
    }

    fun cancelStationProviderDownload(provider: OnlineStationProvider) {
        val job = stationProviderDownloadJobs.getValue(provider)
        if (job == null) {
            Log.w(TAG, "cancelStationProviderDownload: No Download Job for ${provider.displayName}!")
            return
        }
        job.cancel()
        stationProviderDownloadJobs[provider] = null
    }

    /** Measures the clock error by SNTP and corrects every wall-clock sample timestamp with it (FT8's Internet time). */
    fun syncClockWithNetworkTime(showResult: Boolean) {
        if (clockSyncJob != null) {
            Log.w(TAG, "syncClockWithNetworkTime: already running!")
            return
        }
        clockSyncJob = viewModelScope.launch {
            try {
                _clockSyncState.value = ClockSyncState.InProgress
                val measurement = SntpClient.measure()
                ClockCorrection.offsetMs = measurement.offsetMs
                _clockSyncState.value = ClockSyncState.Success(measurement.offsetMs, measurement.server)
                if (showResult) showSnackbar(SnackbarEvent("Clock corrected by ${measurement.offsetMs} ms (${measurement.server})"))
            } catch (e: CancellationException) {
                _clockSyncState.value = ClockSyncState.Idle
                throw e // <- MUST rethrow (for smooth cancellation)
            } catch (e: Exception) {
                Log.w(TAG, "syncClockWithNetworkTime: failed: ${e.message}")
                _clockSyncState.value = ClockSyncState.Error(e.message ?: "Unknown error")
                if (showResult) showSnackbar(SnackbarEvent("Time sync failed: ${e.message ?: "Unknown error"}"))
            } finally {
                clockSyncJob = null
            }
        }
    }
}
