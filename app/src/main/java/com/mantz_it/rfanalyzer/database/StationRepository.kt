package com.mantz_it.rfanalyzer.database

import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import android.content.Context
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import com.mantz_it.rfanalyzer.location.LocationRepository
import com.mantz_it.rfanalyzer.network.DigitalStationList
import com.mantz_it.rfanalyzer.network.EiBiStationFetcher
import com.mantz_it.rfanalyzer.network.StationListFile
import com.mantz_it.rfanalyzer.network.PotaStationFetcher
import com.mantz_it.rfanalyzer.network.SotaStationFetcher
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import java.time.ZonedDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.collections.get
import kotlin.collections.map

/**
 * <h1>RF Analyzer - StationRepository</h1>
 *
 * Module:      StationRepository.kt
 * Description: Repository for all Bookmark-related Entities
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

data class StationFilter(
    val bookmarkLists: Set<Long> = emptySet(),            // empty = show all bookmarkLists
    val search: String = "",                           // "" = show all
    val minFrequency: Long = 0,                        // 0 = no filter
    val maxFrequency: Long = 0,                        // 0 = no filter
    val mode: Set<DemodulationMode> = emptySet(),      // empty = show all modulations
    val onlyFavorites: Boolean = false,                // false = show all
    val onlyOnAirNow: Boolean = false,                 // false = show all
    val sources: Set<SourceProvider> = emptySet()      // empty = show all sources
)

data class BandFilter(
    val bookmarkLists: Set<Long> = emptySet(),            // empty = show all bookmarkLists
    val search: String = "",                           // "" = show all
    val minFrequency: Long = 0,                        // 0 = no filter
    val maxFrequency: Long = 0,                        // 0 = no filter
    val onlyFavorites: Boolean = false,                // false = show all
)

enum class OnlineStationProvider(
    val displayName: String,
    val description: String,
    val type: SourceProvider,
    val dbId: String,
    val url: String,
    val defaultUpdateIntervalSeconds: Int,
    val minUpdateIntervalSeconds: Int,
    val maxUpdateIntervalSeconds: Int,
    val defaultAutoUpdateEnabled: Boolean = false,
) {
    EIBI(displayName = "EiBi",
        description = "Shortwave Radio Station Database",
        type = SourceProvider.EIBI,
        dbId = "EIBI",
        url = "https://afu-base.de/csv/eibi.csv",
        defaultUpdateIntervalSeconds = 60*60*24*30, // 30 days
        minUpdateIntervalSeconds = 60*60*24, // 1 day
        maxUpdateIntervalSeconds = 60*60*24*180), // 1/2 year
    POTA(displayName = "POTA",
        description = "Spots from the Parks-on-the-Air API",
        type = SourceProvider.POTA,
        dbId = "POTA",
        url = "https://api.pota.app/v1/spots",
        defaultUpdateIntervalSeconds = 60, // 1 minute
        minUpdateIntervalSeconds = 15, // 15 seconds
        maxUpdateIntervalSeconds = 60*60*3), // 3 hours
    SOTA(displayName = "SOTA",
        description = "Spots from the Summits-on-the-Air API",
        type = SourceProvider.SOTA,
        dbId = "SOTA",
        url = "https://api-db2.sota.org.uk/api/spots/-24/all/all",
        defaultUpdateIntervalSeconds = 60, // 1 minute
        minUpdateIntervalSeconds = 15, // 15 seconds
        maxUpdateIntervalSeconds = 60*60*3), // 3 hours
    // The presets of the digital modes (DigitalStationList.kt). Updated by default: the list is
    // online so that corrections and new entries reach every user.
    DIGITAL_STATIONS(displayName = "Digital Stations",
        description = "Presets of the digital modes for your area, from the RF Analyzer lists",
        type = SourceProvider.DIGITAL_LIST,
        dbId = "RFA_DIGITAL_STATIONS",
        url = "https://demantz.github.io/rfanalyzer_lists/digital_stations.json",
        defaultUpdateIntervalSeconds = 60*60*24*7, // 1 week
        minUpdateIntervalSeconds = 60*60*24, // 1 day
        maxUpdateIntervalSeconds = 60*60*24*180, // 1/2 year
        defaultAutoUpdateEnabled = true),

    ;

    /** Offered in the lists and settings (DigitalFeatures). */
    val shown: Boolean get() = type.shown

    fun createDefaultOnlineStationProviderSettings(): OnlineStationProviderSettings {
        return OnlineStationProviderSettings(
            id = dbId,
            autoUpdateEnabled = defaultAutoUpdateEnabled,
            autoUpdateIntervalSeconds = defaultUpdateIntervalSeconds,
            lastUpdatedTimestamp = 0L)
    }

    companion object {
        fun fromSourceProvider(sp: SourceProvider): OnlineStationProvider? {
            return when (sp) {
                SourceProvider.EIBI -> EIBI
                SourceProvider.POTA -> POTA
                SourceProvider.SOTA -> SOTA
                SourceProvider.DIGITAL_LIST -> DIGITAL_STATIONS
                SourceProvider.BOOKMARK -> null
            }
        }
        fun fromDbId(dbId: String): OnlineStationProvider? {
            return when (dbId) {
                EIBI.dbId -> EIBI
                POTA.dbId -> POTA
                SOTA.dbId -> SOTA
                DIGITAL_STATIONS.dbId -> DIGITAL_STATIONS
                else -> null
            }
        }
    }
}

data class OnlineStationProviderWithSettings(
    val provider: OnlineStationProvider,
    val settings: OnlineStationProviderSettings
)

sealed interface DownloadState {
    object Idle : DownloadState
    object InProgress : DownloadState
    data class Error(val message: String) : DownloadState
    object Success : DownloadState
}

@Singleton
class StationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stationDao: StationDao,
    private val bandDao: BandDao,
    private val bookmarkListDao: BookmarkListDao,
    private val onlineStationProviderSettingsDao: OnlineStationProviderSettingsDao,
    private val appStateRepository: AppStateRepository,
    private val locationRepository: LocationRepository,
) {
    companion object {
        private const val TAG = "StationRepository"
    }

    private val onlineStationUpdateMutex = Mutex()  // Mutex to ensure that only one DB update is running concurrently

    suspend fun createDefaultBookmarkListsIfMissing() {
        if (bookmarkListDao.count(BookmarkListType.STATION) == 0) {
            Log.d(TAG, "createDefaultBookmarkListsIfMissing: Creating default station list")
            bookmarkListDao.insert(BookmarkList(name = "Default Station List", color = 0xFFFFCA28.toInt(), type = BookmarkListType.STATION, notes = "Default list for station bookmarks"))
        }
        if (bookmarkListDao.count(BookmarkListType.BAND) == 0) {
            Log.d(TAG, "createDefaultBookmarkListsIfMissing: Creating default band list")
            bookmarkListDao.insert(BookmarkList(name = "Default Band List", color = 0xFFAB47BC.toInt(), type = BookmarkListType.BAND, notes = "Default list for band bookmarks"))
        }
    }

    suspend fun createOnlineStationProviderSettingsIfMissing() {
        val dbEntries = onlineStationProviderSettingsDao.getAllOnlineStationProviderSettings().first()
        // every provider of the build - the digital list too while digital features are hidden, so it is there once they are shown
        for (provider in OnlineStationProvider.entries.filter { it.type != SourceProvider.DIGITAL_LIST || DigitalFeatures.inBuild }) {
            if (dbEntries.none { it.id == provider.dbId }) {
                Log.d(TAG, "createOnlineStationProviderSettingsIfMissing: Creating default settings for ${provider.displayName}")
                onlineStationProviderSettingsDao.insert(provider.createDefaultOnlineStationProviderSettings())
            }
        }
    }

    // Retrieve
    fun getAllBands(): Flow<List<Band>> = bandDao.getAllBands()
    fun getAllBookmarkLists(): Flow<List<BookmarkList>> = bookmarkListDao.getAll()

    fun getStationsWithBookmarkListByBookmarkLists(bookmarkListIds: Set<Long>): Flow<List<StationWithBookmarkList>> =
        stationDao.getStationsByBookmarkListIds(bookmarkListIds).combine(getAllBookmarkLists()) { stations, bookmarkLists ->
            val bookmarkListMap = bookmarkLists.associateBy { it.id }
            stations.map { station -> StationWithBookmarkList(station, bookmarkListMap[station.bookmarkListId]) }
        }

    fun getBandsWithBookmarkListByBookmarkLists(bookmarkListIds: Set<Long>): Flow<List<BandWithBookmarkList>> =
        bandDao.getBandsByBookmarkListIds(bookmarkListIds).combine(getAllBookmarkLists()) { bands, bookmarkLists ->
            val bookmarkListMap = bookmarkLists.associateBy { it.id }
            bands.map { band -> BandWithBookmarkList(band, bookmarkListMap[band.bookmarkListId]) }
        }

    fun getStationsWithBookmarkListByIds(stationIds: Set<Long>): Flow<List<StationWithBookmarkList>> =
        stationDao.getStationsByIds(stationIds).combine(getAllBookmarkLists()) { stations, bookmarkLists ->
            val bookmarkListMap = bookmarkLists.associateBy { it.id }
            stations.map { station -> StationWithBookmarkList(station, bookmarkListMap[station.bookmarkListId]) }
        }

    fun getBandsWithBookmarkListByIds(bandIds: Set<Long>): Flow<List<BandWithBookmarkList>> =
        bandDao.getBandsByIds(bandIds).combine(getAllBookmarkLists()) { bands, bookmarkLists ->
            val bookmarkListMap = bookmarkLists.associateBy { it.id }
            bands.map { band -> BandWithBookmarkList(band, bookmarkListMap[band.bookmarkListId]) }
        }

    fun findBookmarkListByName(name: String, type: BookmarkListType? = null): Flow<BookmarkList?> =
        bookmarkListDao.findByNameAndType(name, type)

    fun getStationsBySource(source: SourceProvider) = stationDao.getStationsBySource(source)
    fun getModeSheetStations(modes: List<DemodulationMode>) = stationDao.getModeSheetStations(modes, SourceProvider.DIGITAL_LIST)

    // Update, Insert and Delete
    suspend fun insertStation(station: Station): Long = stationDao.insert(station.copy(countryName = COUNTRY_CODE_TO_NAME[station.countryCode]))
    suspend fun insertBand(band: Band): Long = bandDao.insert(band)
    suspend fun insertBookmarkList(bookmarkList: BookmarkList): Long = bookmarkListDao.insert(bookmarkList)

    suspend fun updateStation(station: Station) = stationDao.update(station.copy(countryName = COUNTRY_CODE_TO_NAME[station.countryCode]))
    suspend fun updateBand(band: Band) = bandDao.update(band)
    suspend fun updateBookmarkList(bookmarkList: BookmarkList) = bookmarkListDao.update(bookmarkList)

    suspend fun deleteStation(station: Station) = stationDao.delete(station)
    suspend fun deleteBand(band: Band) = bandDao.delete(band)
    suspend fun deleteBookmarkList(bookmarkList: BookmarkList) {
        if (bookmarkList.type == BookmarkListType.STATION) {
            stationDao.deleteByBookmarkList(bookmarkList.id)
        } else {
            bandDao.deleteByBookmarkList(bookmarkList.id)
        }
        bookmarkListDao.delete(bookmarkList)
        createDefaultBookmarkListsIfMissing()
    }
    suspend fun deleteStationsByIds(stationIds: List<Long>) = stationDao.deleteByIds(stationIds)
    suspend fun deleteBandsByIds(bandIds: List<Long>) = bandDao.deleteByIds(bandIds)
    suspend fun deleteAllBookmarkStationsBandsAndLists() {
        stationDao.clearBySource(SourceProvider.BOOKMARK)
        bandDao.deleteAllBands()
        bookmarkListDao.deleteAllBookmarkLists()
        createDefaultBookmarkListsIfMissing()
        Log.i(TAG, "deleteAllBookmarkStationsBandsAndLists: Deleted all bookmark stations, bands and lists.")
    }

    // move and copy
    suspend fun moveStation(station: Station, newBookmarkList: BookmarkList) {
        stationDao.update(station.copy(bookmarkListId = newBookmarkList.id, updatedAt = System.currentTimeMillis(), remoteHash = null, source = SourceProvider.BOOKMARK))
    }
    suspend fun moveStations(stations: List<Station>, newBookmarkList: BookmarkList) {
        stationDao.moveToBookmarkListByIds(stations.map { it.id }, newBookmarkList.id, updatedAt = System.currentTimeMillis(), source = SourceProvider.BOOKMARK)
    }
    suspend fun moveBand(band: Band, newBookmarkList: BookmarkList) {
        bandDao.update(band.copy(bookmarkListId = newBookmarkList.id, updatedAt = System.currentTimeMillis()))
    }
    suspend fun moveBands(bands: List<Band>, newBookmarkList: BookmarkList) {
        bandDao.moveToBookmarkListByIds(bands.map { it.id }, newBookmarkList.id, updatedAt = System.currentTimeMillis())
    }
    suspend fun copyStation(station: Station, newBookmarkList: BookmarkList) {
        stationDao.insert(station.copy(id = 0, bookmarkListId = newBookmarkList.id, updatedAt = System.currentTimeMillis(), remoteHash = null, source = SourceProvider.BOOKMARK))
    }
    suspend fun copyStations(stations: List<Station>, newBookmarkList: BookmarkList) {
        val newStations = stations.map { it.copy(id = 0, bookmarkListId = newBookmarkList.id, updatedAt = System.currentTimeMillis(), remoteHash = null, source = SourceProvider.BOOKMARK) }
        stationDao.insertAll(newStations)
    }
    suspend fun copyBand(band: Band, newBookmarkList: BookmarkList) {
        bandDao.insert(band.copy(id = 0, bookmarkListId = newBookmarkList.id, updatedAt = System.currentTimeMillis()))
    }
    suspend fun copyBands(bands: List<Band>, newBookmarkList: BookmarkList) {
        val newBands = bands.map { it.copy(id = 0, bookmarkListId = newBookmarkList.id, updatedAt = System.currentTimeMillis()) }
        bandDao.insertAll(newBands)
    }

    // Filtered Flows:
    private fun getEffectiveMinFrequency(minFrequency: Long?, filterFrequency: Long) = when {
        minFrequency == null -> filterFrequency
        filterFrequency == 0L -> minFrequency
        else -> maxOf(filterFrequency, minFrequency)
    }
    private fun getEffectiveMaxFrequency(maxFrequency: Long?, filterFrequency: Long) = when {
        maxFrequency == null -> filterFrequency
        filterFrequency == 0L -> maxFrequency
        else -> minOf(filterFrequency, maxFrequency)
    }

    fun getFilteredStations(
        filter: StationFilter,
        minFrequency: Long? = null,
        maxFrequency: Long? = null
    ): Flow<List<StationWithBookmarkList>> {
        val (weekday, time) = getCurrentWeekdayAndTimeUtc()
        return stationDao.getStationsFiltered(
            onlyFavorites = filter.onlyFavorites,
            minFrequency = getEffectiveMinFrequency(minFrequency, filter.minFrequency),
            maxFrequency = getEffectiveMaxFrequency(maxFrequency, filter.maxFrequency),
            sources = filter.sources,
            sourcesSize = filter.sources.size,
            bookmarkLists = filter.bookmarkLists,
            bookmarkListsSize = filter.bookmarkLists.size,
            modes = filter.mode,
            modeSize = filter.mode.size,
            search = filter.search,
            onlyOnAirNow = filter.onlyOnAirNow,
            currentTimeUtc = time.toInt(),
            currentWeekday = weekday,
        )
    }
    fun getFilteredStationsPaging(
        filter: StationFilter,
        minFrequency: Long? = null,
        maxFrequency: Long? = null
    ): Flow<PagingData<StationWithBookmarkList>> {
        val (weekday, time) = getCurrentWeekdayAndTimeUtc()
        return Pager(
            config = PagingConfig(
                pageSize = 100,
                initialLoadSize = 200,
                prefetchDistance = 50,
                enablePlaceholders = true
            ),
            pagingSourceFactory = {
                stationDao.getStationsFilteredPaging(
                    onlyFavorites = filter.onlyFavorites,
                    minFrequency = getEffectiveMinFrequency(minFrequency, filter.minFrequency),
                    maxFrequency = getEffectiveMaxFrequency(maxFrequency, filter.maxFrequency),
                    sources = filter.sources,
                    sourcesSize = filter.sources.size,
                    bookmarkLists = filter.bookmarkLists,
                    bookmarkListsSize = filter.bookmarkLists.size,
                    modes = filter.mode,
                    modeSize = filter.mode.size,
                    search = filter.search,
                    onlyOnAirNow = filter.onlyOnAirNow,
                    currentTimeUtc = time.toInt(),
                    currentWeekday = weekday,
                )
            }
        ).flow
    }

    fun getStationIdsFiltered(filter: StationFilter, minFrequency: Long? = null, maxFrequency: Long? = null): Flow<List<Long>> {
        val (weekday, time) = getCurrentWeekdayAndTimeUtc()
        return stationDao.getStationIdsFiltered(
            onlyFavorites = filter.onlyFavorites,
            minFrequency = getEffectiveMinFrequency(minFrequency, filter.minFrequency),
            maxFrequency = getEffectiveMaxFrequency(maxFrequency, filter.maxFrequency),
            sources = filter.sources,
            sourcesSize = filter.sources.size,
            bookmarkLists = filter.bookmarkLists,
            bookmarkListsSize = filter.bookmarkLists.size,
            modes = filter.mode,
            modeSize = filter.mode.size,
            search = filter.search,
            onlyOnAirNow = filter.onlyOnAirNow,
            currentTimeUtc = time.toInt(),
            currentWeekday = weekday,
        )
    }

    fun getFavoriteStations(): Flow<List<Station>> = stationDao.getFavoriteStations()

    fun getFilteredBands(filter: BandFilter, minFrequency: Long? = null, maxFrequency: Long? = null): Flow<List<BandWithBookmarkList>> {
        return bandDao.getBandsFiltered(
            onlyFavorites = filter.onlyFavorites,
            minFrequency = getEffectiveMinFrequency(minFrequency, filter.minFrequency),
            maxFrequency = getEffectiveMaxFrequency(maxFrequency, filter.maxFrequency),
            bookmarkLists = filter.bookmarkLists,
            bookmarkListsSize = filter.bookmarkLists.size
        )
            .combine(getAllBookmarkLists()) { bands, bookmarkLists ->
                val bookmarkListMap = bookmarkLists.associateBy { it.id }
                bands
                    .asSequence()
                    .filter { band ->
                        if (filter.search.isBlank()) return@filter true

                        band.name.contains(filter.search, true) ||
                                band.notes?.contains(filter.search, true) == true ||
                                band.subBands.any { it.name.contains(filter.search, true) } ||
                                band.subBands.any { it.notes?.contains(filter.search, true) == true }
                    }
                    .map { band ->
                        BandWithBookmarkList(
                            band = band,
                            bookmarkList = bookmarkListMap[band.bookmarkListId]
                        )
                    }
                    .toList()
            }
    }

    fun getFavoriteBands(): Flow<List<Band>> = bandDao.getFavoriteBands()

    fun getFilteredBookmarkLists(filterString: String): Flow<List<BookmarkList>> =
        getAllBookmarkLists().map { bookmarkLists ->
            if (filterString.isBlank()) bookmarkLists
            else bookmarkLists.filter { bookmarkList ->
                bookmarkList.name.contains(filterString, ignoreCase = true) ||
                        (bookmarkList.notes?.contains(filterString, ignoreCase = true) == true)
            }
        }

    // Online Source Providers
    private suspend fun fetchOnlineStationProvider(provider: OnlineStationProvider): List<Station> {
        val oldStations = stationDao.getStationsBySource(provider.type).first()
        return when(provider) {
            OnlineStationProvider.EIBI -> EiBiStationFetcher.fetchStations(provider.url, oldStations)
            OnlineStationProvider.POTA -> PotaStationFetcher.fetchStations(provider.url, oldStations)
            OnlineStationProvider.SOTA -> SotaStationFetcher.fetchStations(context, provider.url, oldStations)
            OnlineStationProvider.DIGITAL_STATIONS -> error("updated by updateDigitalStationList")
        }
    }

    suspend fun startOnlineStationProviderUpdate(onlineStationProvider: OnlineStationProvider) {
        Log.i(TAG, "startOnlineStationProviderUpdate: Fetching ${onlineStationProvider.displayName} (${onlineStationProvider.url}) ...")
        if (onlineStationProvider == OnlineStationProvider.DIGITAL_STATIONS) updateDigitalStationList()
        else {
            val stations = fetchOnlineStationProvider(onlineStationProvider)
            onlineStationUpdateMutex.withLock {
                Log.i(TAG, "startOnlineStationProviderUpdate: Replacing stations for ${onlineStationProvider.displayName} ...")
                stationDao.replaceAllBySource(stations, onlineStationProvider.type)
            }
        }
        onlineStationUpdateMutex.withLock {
            val settings = onlineStationProviderSettingsDao.getById(onlineStationProvider.dbId).first() ?: onlineStationProvider.createDefaultOnlineStationProviderSettings()
            val updatedSettings = settings.copy(lastUpdatedTimestamp = System.currentTimeMillis())
            onlineStationProviderSettingsDao.insert(updatedSettings)
        }
    }

    // The digital station list (DigitalStationList.kt): the newer of the bundled copy and the last download.
    private val digitalStationListCache get() = File(context.filesDir, DigitalStationList.ASSET_PATH)

    private fun currentDigitalStationList(): StationListFile? {
        val bundled = runCatching { context.assets.open(DigitalStationList.ASSET_PATH).use { it.readBytes() } }.getOrNull()
        val downloaded = runCatching { digitalStationListCache.takeIf { it.exists() }?.readBytes() }.getOrNull()
        return listOfNotNull(bundled, downloaded).mapNotNull(DigitalStationList::parse).maxByOrNull { it.version }
    }

    /** Downloads the published list and keeps it if it is newer than what the app has - only while
     *  digital features are shown; until the first update after they appear, the bundled list serves. */
    private suspend fun updateDigitalStationList() {
        if (SourceProvider.DIGITAL_LIST.shown) {
            val downloaded = DigitalStationList.download(OnlineStationProvider.DIGITAL_STATIONS.url)  // null: a newer format, keep ours
            if (downloaded != null && downloaded.first.version > (currentDigitalStationList()?.version ?: Long.MIN_VALUE)) withContext(Dispatchers.IO) {
                digitalStationListCache.parentFile?.mkdirs()
                digitalStationListCache.writeBytes(downloaded.second)
            }
        }
        applyDigitalStationList()
    }

    /**
     * Writes the digital station list's rows for the user's area - when the list or the area changed
     * since the rows were written. Clearing the rows (Bookmark Manager) therefore lasts until one of
     * them changes, as for the other online lists until their next update. While digital features
     * are hidden (DigitalFeatures) the list has no rows, so no list shows digital presets.
     */
    suspend fun applyDigitalStationList() = withContext(Dispatchers.IO) {
        appStateRepository.digitalStationListApplied.awaitInitialized()
        onlineStationUpdateMutex.withLock {
            if (!SourceProvider.DIGITAL_LIST.shown) {
                if (appStateRepository.digitalStationListApplied.value.isNotEmpty()) {
                    stationDao.clearBySource(SourceProvider.DIGITAL_LIST)
                    appStateRepository.digitalStationListApplied.set("")
                }
                return@withLock
            }
            val list = currentDigitalStationList() ?: return@withLock
            val location = locationRepository.location.value
            val key = "${list.version}|${DigitalStationList.areaKey(location)}"
            if (key == appStateRepository.digitalStationListApplied.value) return@withLock
            Log.i(TAG, "applyDigitalStationList: version ${list.version} for ${DigitalStationList.areaKey(location)}")
            val oldStations = stationDao.getStationsBySource(SourceProvider.DIGITAL_LIST).first()
            stationDao.replaceAllBySource(DigitalStationList.toStations(list, location, oldStations, System.currentTimeMillis()), SourceProvider.DIGITAL_LIST)
            appStateRepository.digitalStationListApplied.set(key)
        }
    }

    suspend fun clearOnlineCache(provider: OnlineStationProvider) {
        stationDao.clearBySource(provider.type)
    }

    fun getOnlineStationProviderSettings(): Flow<List<OnlineStationProviderSettings>> = onlineStationProviderSettingsDao.getAllOnlineStationProviderSettings()
    fun getFilteredOnlineStationProviderWithSettings(filterString: String): Flow<List<OnlineStationProviderWithSettings>> =
        getOnlineStationProviderSettings().map { settings ->
            settings.mapNotNull { setting ->
                OnlineStationProvider.fromDbId(setting.id)?.takeIf { it.shown }?.let { provider ->
                    OnlineStationProviderWithSettings(provider, setting)
                }
            }.filter { providerWithSettings ->
                filterString.isBlank() ||
                        providerWithSettings.provider.displayName.contains(filterString, ignoreCase = true) ||
                        providerWithSettings.provider.url.contains(filterString, ignoreCase = true) ||
                        providerWithSettings.provider.description.contains(filterString, ignoreCase = true)
            }
        }

    suspend fun setOnlineStationProviderEnabled(provider: OnlineStationProvider, enabled: Boolean) {
        val settings = onlineStationProviderSettingsDao.getById(provider.dbId).first() ?: provider.createDefaultOnlineStationProviderSettings()
        onlineStationProviderSettingsDao.insert(settings.copy(autoUpdateEnabled = enabled))
    }
    suspend fun setOnlineStationProviderUpdateInterval(provider: OnlineStationProvider, interval: Int) {
        val settings = onlineStationProviderSettingsDao.getById(provider.dbId).first() ?: provider.createDefaultOnlineStationProviderSettings()
        onlineStationProviderSettingsDao.insert(settings.copy(autoUpdateIntervalSeconds = interval))
    }
}

fun getCurrentWeekdayAndTimeUtc(): Pair<String, String> {
    val nowUtc = ZonedDateTime.now(ZoneOffset.UTC)

    // Weekday as full name in uppercase, e.g., "MONDAY"
    val weekday = nowUtc.dayOfWeek.name

    // Time as 4-digit HHmm, e.g., 0930
    val timeFormatter = DateTimeFormatter.ofPattern("HHmm")
    val time = nowUtc.format(timeFormatter)

    return weekday to time
}