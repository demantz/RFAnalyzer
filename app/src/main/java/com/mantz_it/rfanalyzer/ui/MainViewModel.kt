package com.mantz_it.rfanalyzer.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.compose.material3.SnackbarResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.DEFAULT_VERTICAL_SCALE_MAX
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.DEFAULT_VERTICAL_SCALE_MIN
import com.mantz_it.rfanalyzer.database.BillingRepositoryInterface
import com.mantz_it.rfanalyzer.database.Recording
import com.mantz_it.rfanalyzer.database.RecordingDao
import com.mantz_it.rfanalyzer.database.Station
import com.mantz_it.rfanalyzer.database.StationRepository
import com.mantz_it.rfanalyzer.database.StationWithBookmarkList
import com.mantz_it.rfanalyzer.database.OnlineStationProvider
import com.mantz_it.rfanalyzer.database.calculateFileName
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.source.AirspyHfSource
import com.mantz_it.rfanalyzer.source.AirspySource
import com.mantz_it.rfanalyzer.source.HackrfSource
import com.mantz_it.rfanalyzer.source.HydraSdrSource
import com.mantz_it.rfanalyzer.ui.composable.AboutTabActions
import com.mantz_it.rfanalyzer.ui.composable.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.DecoderTabActions
import com.mantz_it.rfanalyzer.ui.composable.DemodulationTabActions
import com.mantz_it.rfanalyzer.ui.composable.DisplayTabActions
import com.mantz_it.rfanalyzer.ui.composable.FilesourceFileFormat
import com.mantz_it.rfanalyzer.ui.composable.RecordingTabActions
import com.mantz_it.rfanalyzer.ui.composable.SettingsTabActions
import com.mantz_it.rfanalyzer.ui.composable.SourceTabActions
import com.mantz_it.rfanalyzer.ui.composable.SourceType
import com.mantz_it.rfanalyzer.ui.composable.asSizeInBytesToString
import com.mantz_it.rfanalyzer.ui.composable.asStringWithUnit
import com.mantz_it.rfanalyzer.ui.composable.saturationFunction
import com.mantz_it.rfanalyzer.ui.screens.RecordingScreenActions
import com.mantz_it.rfanalyzer.ui.screens.StationsPage
import com.mantz_it.rfanalyzer.ui.screens.BookmarkManagerScreenActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.abs
import androidx.core.net.toUri
import com.mantz_it.rfanalyzer.database.Band
import com.mantz_it.rfanalyzer.database.BandWithBookmarkList
import com.mantz_it.rfanalyzer.database.BookmarkList
import com.mantz_it.rfanalyzer.database.BookmarkListType
import com.mantz_it.rfanalyzer.database.DemodulationParameters
import com.mantz_it.rfanalyzer.database.DownloadState
import com.mantz_it.rfanalyzer.database.StationImporterExporter
import com.mantz_it.rfanalyzer.ui.composable.Quintuple
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import java.util.EnumMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.min

/**
 * <h1>RF Analyzer - MainViewModel</h1>
 *
 * Module:      MainViewModel.kt
 * Description: The ViewModel of the app. Contains application logic related to the UI.
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


sealed class AppScreen(val route: String, open val subUrl: String = "") {
    data object WelcomeScreen: AppScreen("WelcomeScreen/")
    data object MainScreen: AppScreen("MainScreen/")
    data object RecordingScreen: AppScreen("RecordingScreen/")
    data class BookmarkManagerScreen(val page: StationsPage? = null): AppScreen("BookmarkManagerScreen/", subUrl = page?.name ?: "")
    data object BookmarksTutorial: AppScreen("StationsTutorial/")
    data object LogFileScreen: AppScreen("LogFileScreen/")
    data object AboutScreen: AppScreen("AboutScreen/")
    data class ManualScreen(override val subUrl: String = "index.html") : AppScreen("ManualScreen/", subUrl)
}

data class SnackbarEvent(val message: String, val buttonText: String? = null, val callback: ((SnackbarResult) -> Unit)? = null)

@HiltViewModel
class MainViewModel @Inject constructor(
    private val appStateRepository: AppStateRepository,
    private val recordingDao:RecordingDao,
    private val stationRepository: StationRepository,
    private val stationImporterExporter: StationImporterExporter,
    private val billingRepository: BillingRepositoryInterface
) : ViewModel() {
    companion object {
        private const val TAG = "MainViewModel"
        private const val GRACE_PERIOD_SECONDS = 30
    }

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
        data object OnShowDecodedLogClicked: UiAction()
        data class OnSaveDecodedLogClicked(val destUri: Uri): UiAction()
        data object OnShareDecodedLogClicked: UiAction()
        data class OnDeleteRecordingClicked(val filePath: String): UiAction()
        data object OnDeleteAllRecordingsClicked: UiAction()
        data class WriteInternalFileToFile(val filename: String, val destUri: Uri): UiAction()
        data class OnShareRecordingClicked(val filename: String): UiAction()
        data class RenameFile(val file: File, val newName: String): UiAction()
        data class ShowDialog(val title: String, val msg: String, val positiveButton: String? = null, val negativeButton: String? = null, val action: (() -> Unit)? = null): UiAction()
        data object ShowDonationDialog: UiAction()
        data object OnBuyFullVersionClicked: UiAction()
        data class OnStartExternalActivity(val intent: Intent): UiAction()
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
                    demodulationParameters = if(appStateRepository.squelchEnabled.value)
                        DemodulationParameters(squelch = DemodulationParameters.Squelch(enabled = true, thresholdDb = appStateRepository.squelch.value))
                        else null,
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
    fun tuneToStation(station: Station): Boolean {
        if (!appStateRepository.analyzerRunning.value) {
            showSnackbar(SnackbarEvent(message = "Analyzer not running."))
            return false
        }
        setChannelFrequency(station.frequency, askUser = true, onErrorMessage = {
            showSnackbar(SnackbarEvent(message = "Frequency (${station.frequency.asStringWithUnit("Hz")}) out of range for Source (${appStateRepository.sourceName.value})"))
        }) {
            // successfully set channel frequency
            if (station.mode != DemodulationMode.OFF)
                appStateRepository.demodulationMode.set(station.mode)
            appStateRepository.channelWidth.set(station.bandwidth.coerceIn(
                appStateRepository.demodulationMode.value.minChannelWidth,
                appStateRepository.demodulationMode.value.maxChannelWidth
            ))
            station.demodulationParameters?.let { demodulationParameters ->
                if (demodulationParameters.squelch != null) {
                    appStateRepository.squelchEnabled.set(demodulationParameters.squelch.enabled)
                    if (demodulationParameters.squelch.enabled)
                        appStateRepository.squelch.set(demodulationParameters.squelch.thresholdDb)
                }
            }
        }
        return true
    }
    fun moveViewportToBand(band: Band): Boolean {
        if (!appStateRepository.analyzerRunning.value) {
            showSnackbar(SnackbarEvent(message = "Analyzer not running."))
            return false
        }
        val bandMiddleFrequency = (band.endFrequency + band.startFrequency)/2
        val bandBandwidth = band.endFrequency - band.startFrequency
        if (appStateRepository.sourceSignalStartFrequency.value < band.startFrequency
            && appStateRepository.sourceSignalEndFrequency.value > band.endFrequency) {
            setViewportSampleRate(bandBandwidth)
            setViewportFrequency(bandMiddleFrequency)
            return true
        }
        // Re-tune necessary
        val maxSourceBandwidth =
            if(appStateRepository.sourceAutomaticSampleRateAdjustment.value)
                appStateRepository.sourceSupportedSampleRates.value.last()
            else
                appStateRepository.sourceSampleRate.value
        val actualNewSampleRate = min(bandBandwidth, maxSourceBandwidth)
        val newSourceFrequency = band.startFrequency + actualNewSampleRate
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
                    appStateRepository.sourceFrequency.set(newSourceFrequency)
                    setViewportSampleRate(actualNewSampleRate)
                    setViewportFrequency(band.startFrequency + actualNewSampleRate/2)
                }
            ))
        } else {
            appStateRepository.sourceFrequency.set(newSourceFrequency)
            setViewportSampleRate(actualNewSampleRate)
            setViewportFrequency(band.startFrequency + actualNewSampleRate/2)
        }
        return true
    }

    private val stationProviderDownloadJobs: EnumMap<OnlineStationProvider, Job?> = EnumMap(OnlineStationProvider.entries.associateWith { null })

    // Navigation between Screens
    private val _navigationEvent = MutableSharedFlow<AppScreen>(extraBufferCapacity = 1)
    val navigationEvent = _navigationEvent.asSharedFlow()
    fun navigate(screen: AppScreen) { _navigationEvent.tryEmit(screen) }

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

    // Billing
    fun checkPurchases() {
        billingRepository.queryPurchases()
    }
    fun buyFullVersion(activity: Activity) {
        billingRepository.purchaseFullVersion(activity)
    }
    private var gracePeriodCountdown = GRACE_PERIOD_SECONDS
    fun showUsageTimeUsedUpDialog() {
        sendActionToUi(UiAction.ShowDialog(
            title = "End of Trial Version",
            msg = "The 60-minute operation time of the trial version is used up.\n" +
                  "To continue using the app without interruption and support its further development, please consider purchasing the full version.",
            positiveButton = "Buy full version",
            negativeButton = "Cancel",
            action = {
                sendActionToUi(UiAction.OnBuyFullVersionClicked)
            }
        ))
        Log.d(TAG, "showUsageTimeUsedUpDialog")
    }

    // ACTIONS
    // MainScreen ACTIONS ------------------------------------------------------------------------
    val sourceTabActions = SourceTabActions(
        onStartStopClicked = {
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
                // Verify RTL SDR external IP/Hostname is valid:
                if (appStateRepository.sourceType.value == SourceType.RTLSDR && appStateRepository.rtlsdrExternalServerEnabled.value) {
                    val value = appStateRepository.rtlsdrExternalServerIP.value
                    val ipRegex = Regex("^((25[0-5]|2[0-4]\\d|1\\d{2}|[1-9]?\\d)(\\.|$)){4}$")
                    val hostnameRegex = Regex("^(?=.{1,253}\$)([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)+[a-zA-Z]{2,}\$")
                    if (!(ipRegex.matches(value) || hostnameRegex.matches(value))) {
                        showSnackbar(SnackbarEvent(message = "Invalid IP or Hostname Value: $value"))
                        Log.w(TAG,"sourceTabActions.onStartStopClicked: Invalid IP or Hostname: $value")
                        return@SourceTabActions
                    }
                }
                appStateRepository.analyzerStartPending.set(true)
                sendActionToUi(UiAction.OnStartClicked)
            }
        },
        onSourceTypeChanged = { newSourceType ->
            appStateRepository.sourceType.set(newSourceType)
            appStateRepository.sourceSupportedSampleRates.set(newSourceType.defaultSupportedSampleRates)
            when (newSourceType) {
                SourceType.HACKRF -> {
                    appStateRepository.sourceMinimumFrequency.set(HackrfSource.MIN_FREQUENCY)
                    appStateRepository.sourceMaximumFrequency.set(HackrfSource.MAX_FREQUENCY)
                }

                SourceType.RTLSDR -> {
                    // We don't know the Tuner yet. So don't be restrictive:
                    appStateRepository.sourceMinimumFrequency.set(0)
                    appStateRepository.sourceMaximumFrequency.set(0)
                }

                SourceType.AIRSPY -> {
                    appStateRepository.sourceMinimumFrequency.set(AirspySource.MIN_FREQUENCY)
                    appStateRepository.sourceMaximumFrequency.set(AirspySource.MAX_FREQUENCY)
                }

                SourceType.AIRSPYHF -> {
                    appStateRepository.sourceMinimumFrequency.set(AirspyHfSource.MIN_FREQUENCY)
                    appStateRepository.sourceMaximumFrequency.set(AirspyHfSource.MAX_FREQUENCY)
                }

                SourceType.HYDRASDR -> {
                    appStateRepository.sourceMinimumFrequency.set(HydraSdrSource.MIN_FREQUENCY)
                    appStateRepository.sourceMaximumFrequency.set(HydraSdrSource.MAX_FREQUENCY)
                }

                SourceType.FILESOURCE -> Unit // no need to set any values
            }
        },
        onFrequencyChanged = { newFrequency ->
            if (newFrequency >= 0) {
                val updateFrequency: () -> Unit = {
                    appStateRepository.sourceFrequency.set(newFrequency)
                    // if channel is outside of the signal range, reset it to the center freq:
                    if (appStateRepository.channelFrequency.value !in appStateRepository.sourceSignalStartFrequency.value..appStateRepository.sourceSignalEndFrequency.value)
                        appStateRepository.channelFrequency.set(newFrequency)
                }
                if (appStateRepository.recordingRunning.value)
                    sendActionToUi(
                        UiAction.ShowDialog(
                        title = "Stop Recording?",
                        msg = "The recording is still running? Stop recording to change frequency?",
                        positiveButton = "Yes, stop recording!",
                        negativeButton = "No",
                        action = {
                            appStateRepository.recordingRunning.set(false)
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
                if (appStateRepository.recordingRunning.value)
                    sendActionToUi(
                        UiAction.ShowDialog(
                        title = "Stop Recording?",
                        msg = "The recording is still running? Stop recording to change sample rate?",
                        positiveButton = "Yes, stop recording!",
                        negativeButton = "No",
                        action = {
                            appStateRepository.recordingRunning.set(false)
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
        onHydraSdrRfBiasEnabledChanged = appStateRepository.hydraSdrRfBiasEnabled::set,
        onHydraSdrRfPortChanged = appStateRepository.hydraSdrRfPort::set,
        onHydraSdrConverterOffsetChanged = appStateRepository.hydraSdrConverterOffset::set,
        onOpenFileChosen = { uri, filename -> setFilesourceUri(uri.toString(), filename) },
        onViewRecordingsClicked = { navigate(AppScreen.RecordingScreen) },
        onFilesourceFileFormatChanged = appStateRepository.filesourceFileFormat::set,
        onFilesourceRepeatChanged = appStateRepository.filesourceRepeatEnabled::set,
    )

    val displayTabActions = DisplayTabActions(
        onVerticalScaleChanged = { newMin, newMax ->
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
    )

    val demodulationTabActions = DemodulationTabActions(
        onDemodulationModeChanged = { newDemodulationMode ->
            // initialize channel freq if it is out of the viewport range:
            var channelFrequency = appStateRepository.channelFrequency.value
            if (appStateRepository.demodulationMode.value == DemodulationMode.OFF && newDemodulationMode != DemodulationMode.OFF) {
                if (channelFrequency < appStateRepository.viewportStartFrequency.value || channelFrequency > appStateRepository.viewportEndFrequency.value) {
                    channelFrequency = appStateRepository.viewportFrequency.value
                }
                appStateRepository.channelFrequency.set(channelFrequency)
            }
            appStateRepository.demodulationMode.set(newDemodulationMode)
        },
        onChannelFrequencyChanged = { newChannelFrequency ->
            setChannelFrequency(newChannelFrequency)
        },
        onTunerWheelDelta = { delta ->
            val minimumStepSize = appStateRepository.demodulationMode.value.tuneStepDistance
            val absDelta = abs(delta)
            val factor = appStateRepository.viewportSampleRate.value / 1000f / minimumStepSize
            val amplification = factor * saturationFunction(x=absDelta-1, a=3f, k=5f)
            val amplifiedDelta = amplification * delta * (if(appStateRepository.reverseTuningWheel.value) -1 else 1)
            val finalDelta = if (amplifiedDelta > 0) amplifiedDelta.coerceAtLeast(1f) else amplifiedDelta.coerceAtMost( -1f )
            val newChannelFrequency = (appStateRepository.channelFrequency.value + finalDelta.toLong()*minimumStepSize)
            val stepAlignedNewChannelFrequency = newChannelFrequency / minimumStepSize * minimumStepSize
            setChannelFrequency(stepAlignedNewChannelFrequency, askUser = false, onErrorMessage = { }) // don't show any messages or dialogs when the user uses the wheel
        },
        onChannelWidthChanged = { newWidth ->
            appStateRepository.channelWidth.set(newWidth.coerceIn(
                appStateRepository.demodulationMode.value.minChannelWidth,
                appStateRepository.demodulationMode.value.maxChannelWidth
            ))
        },
        onSquelchEnabledChanged = appStateRepository.squelchEnabled::set,
        onSquelchChanged = appStateRepository.squelch::set,
        onKeepChannelCenteredChanged = { newValue ->
            appStateRepository.keepChannelCentered.set(newValue)
            if (newValue)
                setViewportFrequency(appStateRepository.channelFrequency.value)
        },
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
        onAddStationBookmarkClicked = { showEditStationBookmarkSheet(null) },
        onAddBandBookmarkClicked = { showEditBandBookmarkSheet(null) },
        onTuneToStation = { tuneToStation(it) },
        onViewBand = { moveViewportToBand(it) },
    )

    val decoderTabActions = DecoderTabActions(
        onEnabledChanged = appStateRepository.decoderEnabled::set,
        onTargetSampleRateChanged = { appStateRepository.decoderTargetSampleRate.set(it) },
        onChannelizedChanged = appStateRepository.decoderChannelized::set,
        onConversionModeChanged = appStateRepository.decoderConversionMode::set,
        onAutoLevelChanged = appStateRepository.decoderAutoLevel::set,
        onMinSnrChanged = { appStateRepository.decoderMinSnr.set(it) },
        onReportMetaChanged = appStateRepository.decoderReportMeta::set,
        onLogToFileChanged = appStateRepository.decoderLogToFile::set,
        onShowLogClicked = { sendActionToUi(UiAction.OnShowDecodedLogClicked) },
        onSaveLogToFileClicked = { destUri -> sendActionToUi(UiAction.OnSaveDecodedLogClicked(destUri)) },
        onShareLogClicked = { sendActionToUi(UiAction.OnShareDecodedLogClicked) },
        onFlexDecodersChanged = { specs -> appStateRepository.decoderFlexDecoders.set(specs) },
    )

    val recordingTabActions = RecordingTabActions(
        onNameChanged = { appStateRepository.recordingName.set(it.replace('/', '_')) },
        onOnlyRecordWhenSquelchIsSatisfiedChanged = appStateRepository.recordOnlyWhenSquelchIsSatisfied::set,
        onSquelchChanged = appStateRepository.squelch::set,
        onStopAfterThresholdChanged = { appStateRepository.recordingStopAfterThreshold.set(it.coerceAtLeast(0)) },
        onStopAfterUnitChanged = appStateRepository.recordingstopAfterUnit::set,
        onStartRecordingClicked = {
            if(appStateRepository.recordingRunning.value) {
                sendActionToUi(UiAction.OnStopRecordingClicked)
            } else {
                if(!appStateRepository.isFullVersion.value && !BuildConfig.IS_FOSS) {
                    if (appStateRepository.isAppUsageTimeUsedUp.value) {
                        showUsageTimeUsedUpDialog()
                        return@RecordingTabActions
                    }
                }
                // start recording:
                sendActionToUi(UiAction.OnStartRecordingClicked)
            }
        },
        onViewRecordingsClicked = { navigate(AppScreen.RecordingScreen) }
    )

    val settingsTabActions = SettingsTabActions(
        onScreenOrientationChanged = appStateRepository.screenOrientation::set,
        onFontSizeChanged = appStateRepository.fontSize::set,
        onColorThemeChanged = appStateRepository.colorTheme::set,
        onLongPressHelpEnabledChanged = appStateRepository.longPressHelpEnabled::set,
        onReverseTuningWheelChanged = appStateRepository.reverseTuningWheel::set,
        onControlDrawerSideChanged = appStateRepository.controlDrawerSide::set,
        onEnableLowPerformanceModeChanged = appStateRepository.enableLowPerformanceMode::set,
        onLowPerformanceModeFilterQualityChanged = appStateRepository.lowPerformanceModeFilterQuality::set,
        onRtlsdrAllowOutOfBoundFrequencyChanged = appStateRepository.rtlsdrAllowOutOfBoundFrequency::set,
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
        onBuyFullVersionClicked = { sendActionToUi(UiAction.OnBuyFullVersionClicked) }
    )

    // Surface ACTIONS ------------------------------------------------------------------------
    val analyzerSurfaceActions = AnalyzerSurfaceActions(
        onViewportFrequencyChanged = this::setViewportFrequency,
        onViewportSampleRateChanged = this::setViewportSampleRate,
        onViewportVerticalScaleChanged = { verticalScalePair ->
            appStateRepository.viewportVerticalScaleMin.set(verticalScalePair.first)
            appStateRepository.viewportVerticalScaleMax.set(verticalScalePair.second)
            val coercedSquelch = appStateRepository.squelch.value.coerceIn(verticalScalePair.first, verticalScalePair.second)
            if (appStateRepository.squelch.value != coercedSquelch)
                appStateRepository.squelch.set(coercedSquelch)
        },
        onChannelFrequencyChanged = { newFrequency ->
            appStateRepository.channelFrequency.set(newFrequency)
            if (appStateRepository.keepChannelCentered.value)
                setViewportFrequency(newFrequency)
        },
        onChannelWidthChanged = { newWidth -> appStateRepository.channelWidth.set(newWidth.coerceIn(appStateRepository.demodulationMode.value.minChannelWidth, appStateRepository.demodulationMode.value.maxChannelWidth)) },
        onSquelchChanged = appStateRepository.squelch::set,
        onStationsClicked = { stationIds, longPress ->
            if (stationIds.size == 1 && !longPress) {
                // Short Press on single Station -> tune directly to station
                val station = displayedStations.value.firstOrNull { it.station.id == stationIds.first() }?.station
                if (station != null) tuneToStation(station)
            } else {
                // Otherwise show the BottomSheet with Stations:
                _selectedStationIds.update { stationIds }
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

        // Create default bookmarkLists and OnlineStationProviderSettings
        viewModelScope.launch {
            stationRepository.createDefaultBookmarkListsIfMissing()
            stationRepository.createOnlineStationProviderSettingsIfMissing()
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
                    val timeSinceLastUpdateSeconds = (now - setting.lastUpdatedTimestamp) / 1000
                    if (setting.autoUpdateEnabled && timeSinceLastUpdateSeconds >= setting.autoUpdateIntervalSeconds) {
                        OnlineStationProvider.fromDbId(setting.id)?.let { startStationProviderDownload(it) }
                    }
                }
            }
        }

        viewModelScope.launch {
            appStateRepository.isFullVersion.awaitInitialized()
            viewModelScope.collectAppState(appStateRepository.appUsageTimeInSeconds) { usageTime ->
                if (!BuildConfig.IS_FOSS && !appStateRepository.isFullVersion.value) {
                    // TRIAL
                    if (usageTime % 30 == 0) {
                        Log.d(TAG, "init (collect appUsageTimeInSeconds): usageTime=$usageTime  (start query for purchases...)")
                        checkPurchases()
                    }
                    if (appStateRepository.isAppUsageTimeUsedUp.value) {
                        if (gracePeriodCountdown != 0) {
                            gracePeriodCountdown--
                        } else {
                            sendActionToUi(UiAction.OnStopClicked)
                            gracePeriodCountdown = GRACE_PERIOD_SECONDS
                            showUsageTimeUsedUpDialog()
                        }
                    }
                }
            }
        }

        if (!BuildConfig.IS_FOSS) {
            viewModelScope.launch {
                appStateRepository.isFullVersion.awaitInitialized()
                viewModelScope.collectAppState(appStateRepository.isFullVersion) { isFullVersion ->
                    if (isFullVersion) {
                        Log.d(TAG, "init (collect isFullVersion): isFullVersion -> TRUE!")
                        showSnackbar(SnackbarEvent("RF Analyzer FULL VERSION unlocked!"))
                    } else {
                        Log.d(TAG, "init (collect isFullVersion): isFullVersion -> FALSE!")
                        showSnackbar(SnackbarEvent("RF Analyzer Full Version refunded. App is now TRIAL VERSION."))
                    }
                }
            }
        }

        viewModelScope.collectAppState(appStateRepository.isPurchasePending) { isPurchasePending ->
            if (isPurchasePending) {
                Log.d(TAG, "init (collect isPurchasePending): There is a pending purchase!")
                showSnackbar(SnackbarEvent("Purchase of FULL VERSION is pending.."))
            }
        }

        // Observe and handle the analyzer events
        viewModelScope.launch {
            appStateRepository.analyzerEvents.collect { event ->
                when (event) {
                    is AppStateRepository.AnalyzerEvent.RecordingFinished -> {
                        val newRecording = Recording(
                            name = appStateRepository.recordingName.value,
                            frequency = appStateRepository.sourceFrequency.value,
                            sampleRate = appStateRepository.sourceSampleRate.value,
                            date = appStateRepository.recordingStartedTimestamp.value,
                            fileFormat = when(appStateRepository.sourceType.value) {
                                SourceType.HACKRF -> FilesourceFileFormat.HACKRF
                                SourceType.RTLSDR -> FilesourceFileFormat.RTLSDR
                                SourceType.AIRSPY -> FilesourceFileFormat.AIRSPY
                                SourceType.AIRSPYHF -> FilesourceFileFormat.AIRSPYHF
                                SourceType.HYDRASDR -> FilesourceFileFormat.HYDRASDR
                                SourceType.FILESOURCE -> appStateRepository.filesourceFileFormat.value
                            },
                            sizeInBytes = event.finalSize,
                            filePath = event.recordingFile.absolutePath,
                            favorite = false
                        )
                        sendActionToUi(UiAction.RenameFile(event.recordingFile, newRecording.calculateFileName()))
                        val newFilePath = "${event.recordingFile.parent}/${newRecording.calculateFileName()}"
                        insertRecording(newRecording.copy(filePath = newFilePath)) { insertedRecording ->
                            appStateRepository.recordingStartedTimestamp.set(0L)
                            appStateRepository.recordingRunning.set(false)
                            showSnackbar(SnackbarEvent(
                                message = "Recording '${insertedRecording.name}' finished (${insertedRecording.sizeInBytes.asSizeInBytesToString()})",
                                buttonText = "Delete Recording",
                                callback = { askDeletionResult ->
                                    if(askDeletionResult == SnackbarResult.ActionPerformed) deleteRecordingWithUndo(insertedRecording)
                                }
                            ))
                        }
                    }
                    is AppStateRepository.AnalyzerEvent.SourceFailure ->
                        showSnackbar(SnackbarEvent(message = event.message))
                    null -> Log.e(TAG, "Event from AnalyzerService was null!")
                }
            }
        }
    }

    private fun setViewportSampleRate(newSampleRate: Long) {
        appStateRepository.viewportSampleRate.set(newSampleRate)

        // Automatically re-adjust the sample rate of the source if we zoom too far out or in (only if not recording!)
        if (appStateRepository.sourceAutomaticSampleRateAdjustment.value && appStateRepository.analyzerRunning.value && !appStateRepository.recordingRunning.value) {
            val optimalSampleRates = appStateRepository.sourceSupportedSampleRates.value
            val bestSampleRate = optimalSampleRates.firstOrNull { it > appStateRepository.viewportSampleRate.value } ?: optimalSampleRates.last()
            if(appStateRepository.sourceSampleRate.value != bestSampleRate) {
                appStateRepository.sourceSampleRate.set(bestSampleRate)
            }
        }
    }

    private fun setViewportFrequency(newViewportFrequnecy: Long) {
        val coercedFrequency = newViewportFrequnecy.coerceIn(appStateRepository.sourceSignalStartFrequency.value, appStateRepository.sourceSignalEndFrequency.value).coerceAtLeast(0)
        //Log.d(TAG, "setViewportFrequency: BEFORE: [vpFreq=${appStateRepository.viewportFrequency.value}] [vpStartFreq=${appStateRepository.viewportStartFrequency.value}]")
        appStateRepository.viewportFrequency.set(coercedFrequency)
        //Log.d(TAG, "setViewportFrequency: AFTER:  [vpFreq=${appStateRepository.viewportFrequency.value}] [vpStartFreq=${appStateRepository.viewportStartFrequency.value}]")

        if (appStateRepository.keepChannelCentered.value)
            appStateRepository.channelFrequency.set(coercedFrequency)

        // Automatically re-tune the source if we scrolled the samples out of the visible window:
        if (appStateRepository.analyzerRunning.value &&
            (appStateRepository.sourceSignalEndFrequency.value < appStateRepository.viewportEndFrequency.value ||
                    appStateRepository.sourceSignalStartFrequency.value > appStateRepository.viewportStartFrequency.value)
        ) {
            if(!appStateRepository.recordingRunning.value) {
                val tuneFreq = coercedFrequency.coerceIn(appStateRepository.sourceMinimumFrequency.value, appStateRepository.sourceMaximumFrequency.value)
                appStateRepository.sourceFrequency.set(tuneFreq)
            }
        }
    }

    private fun setChannelFrequency(newChannelFrequency: Long, askUser: Boolean = true, onErrorMessage: ((String) -> Unit)? = null, onSuccess: (() -> Unit)? = null) {
        if (newChannelFrequency in appStateRepository.sourceSignalStartFrequency.value..appStateRepository.sourceSignalEndFrequency.value) {
            // move viewport if necessary:
            if (appStateRepository.keepChannelCentered.value) {
                setViewportFrequency(newChannelFrequency)
            } else if (newChannelFrequency !in appStateRepository.viewportStartFrequency.value..appStateRepository.viewportEndFrequency.value) {
                val newViewportFrequency =
                    if (newChannelFrequency < appStateRepository.viewportStartFrequency.value)
                        newChannelFrequency + appStateRepository.viewportSampleRate.value/2
                    else
                        newChannelFrequency - appStateRepository.viewportSampleRate.value/2
                setViewportFrequency(newViewportFrequency)
            }
            appStateRepository.channelFrequency.set(newChannelFrequency)
            if (onSuccess != null) onSuccess()
        }
        else {
            // Re-tune necessary
            val retuneAndUpdateChannel: () -> Unit = {
                // TODO: if automatic sample rate is set, we need to include the edge case where the channel would be in range if we increase the sample rate
                val newSourceFrequency = newChannelFrequency + appStateRepository.channelWidth.value*2    // avoid DC peak by tuning not directly to channel frequnecy
                appStateRepository.sourceFrequency.set(newSourceFrequency)
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

        // Try to extract frequency, sample rate, and file format from file name
        if (filename != null) {
            try {
                // 1. Format. Search for strings like hackrf, rtl-sdr, ...
                if (filename.matches(".*hackrf.*".toRegex()) || filename.matches(".*HackRF.*".toRegex()) ||
                    filename.matches(".*HACKRF.*".toRegex()) || filename.matches(".*hackrfone.*".toRegex())
                ) fileFormat = FilesourceFileFormat.HACKRF
                if (filename.matches(".*rtlsdr.*".toRegex()) || filename.matches(".*rtl-sdr.*".toRegex()) ||
                    filename.matches(".*RTLSDR.*".toRegex()) || filename.matches(".*RTL-SDR.*".toRegex())
                ) fileFormat = FilesourceFileFormat.RTLSDR
                if (filename.matches(".*airspy.*".toRegex()) || filename.matches(".*Airspy.*".toRegex()) ||
                    filename.matches(".*AIRSPY.*".toRegex()) || filename.matches(".*AirSpy.*".toRegex())
                ) fileFormat = FilesourceFileFormat.AIRSPY
                if (filename.matches(".*airspyhf.*".toRegex()) || filename.matches(".*AirspyHF.*".toRegex()) ||
                    filename.matches(".*AIRSPYHF.*".toRegex()) || filename.matches(".*AirSpyHF.*".toRegex())
                ) fileFormat = FilesourceFileFormat.AIRSPYHF
                if (filename.matches(".*hydrasdr.*".toRegex()) || filename.matches(".*HydraSDR.*".toRegex()) ||
                    filename.matches(".*HYDRASDR.*".toRegex()) || filename.matches(".*HydraSdr.*".toRegex())
                ) fileFormat = FilesourceFileFormat.HYDRASDR

                // 2. Sampe Rate. Search for pattern XXXXXXXSps
                if (filename.matches(".*(_|-|\\s)([0-9]+)(sps|Sps|SPS).*".toRegex())) {
                    sampleRate = filename.replaceFirst(".*(_|-|\\s)([0-9]+)(sps|Sps|SPS).*".toRegex(), "$2").toLong()
                }
                if (filename.matches(".*(_|-|\\s)([0-9]+)(ksps|Ksps|kSps|KSps|KSPS).*".toRegex())) {
                    sampleRate = filename.replaceFirst(".*(_|-|\\s)([0-9]+)(ksps|Ksps|kSps|KSps|KSPS).*".toRegex(), "$2").toLong() * 1000
                }
                if (filename.matches(".*(_|-|\\s)([0-9]+)(msps|Msps|mSps|MSps|MSPS).*".toRegex())) {
                    sampleRate = filename.replaceFirst(".*(_|-|\\s)([0-9]+)(msps|Msps|mSps|MSps|MSPS).*".toRegex(), "$2").toLong() * 1000000
                }

                // 3. Frequency. Search for pattern XXXXXXXHz
                if (filename.matches(".*(_|-|\\s)([0-9]+)(hz|Hz|HZ).*".toRegex())) {
                    frequency = filename.replaceFirst(".*(_|-|\\s)([0-9]+)(hz|Hz|HZ).*".toRegex(), "$2").toLong()
                }
                if (filename.matches(".*(_|-|\\s)([0-9]+)(khz|Khz|kHz|KHz|KHZ).*".toRegex())) {
                    frequency = filename.replaceFirst(".*(_|-|\\s)([0-9]+)(khz|Khz|kHz|KHz|KHZ).*".toRegex(), "$2").toLong() * 1000
                }
                if (filename.matches(".*(_|-|\\s)([0-9]+)(mhz|Mhz|mHz|MHz|MHZ).*".toRegex())) {
                    frequency = filename.replaceFirst(".*(_|-|\\s)([0-9]+)(mhz|Mhz|mHz|MHz|MHZ).*".toRegex(), "$2").toLong() * 1000000
                }
            } catch (e: NumberFormatException) {
                Log.i(TAG, "setFilesourceUri: Error parsing filename: " + e.message)
            }
        }
        Log.i(TAG, "setFilesourceUri: [uri=$uri] [filename=$filename] [fileFormat=$fileFormat] [frequency=$frequency] [sampleRate=$sampleRate]")
        appStateRepository.sourceType.set(SourceType.FILESOURCE)
        appStateRepository.filesourceUri.set(uri)
        appStateRepository.filesourceFilename.set(filename ?: uri)
        appStateRepository.sourceFrequency.set(frequency)
        appStateRepository.sourceSampleRate.set(sampleRate)
        appStateRepository.filesourceFileFormat.set(fileFormat)
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
        val url = "https://www.qrz.com/db/${Uri.encode(callsign)}"
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
            } catch (e: CancellationException) {
                Log.i(TAG, "startStationProviderDownload: Download Job for ${provider.displayName} canceled!")
                downloadState.set(DownloadState.Idle)
                throw e // <- MUST rethrow (for smooth cancellation)
            } catch (e: Exception) {
                Log.w(TAG, "startStationProviderDownload: Error during Download Job for ${provider.displayName}: ${e.message}")
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
}
