package com.mantz_it.rfanalyzer.database

import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import android.graphics.Color
import androidx.paging.PagingSource
import androidx.room.*
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer_ipc.DigitalDemodParams
import com.mantz_it.rfanalyzer_ipc.RttyPolarity
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlin.math.abs
import kotlin.math.floor
import com.mantz_it.rfanalyzer_ipc.LoraSpreadingFactors
import com.mantz_it.rfanalyzer_ipc.LoraIqMode

/**
 * <h1>RF Analyzer - StationDao</h1>
 *
 * Module:      StationDao.kt
 * Description: Data Access Object for all entities related to Bookmarks:
 *              - Station
 *              - Band
 *              - BookmarkList
 *              - OnlineStationProviderSettings
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@Serializable
data class Coordinates(val latitude: Double, val longitude: Double) {
    @Serializable
    val version: Int = 1
    override fun toString(): String {
        return "${toDMS()} [${toGridLocator()}]"
    }

    fun toGridLocator(): String {
        return coordinatesToGridLocator(latitude, longitude)
    }

    fun toDMS(): String {
        return "${toDMS(latitude, true)}, ${toDMS(longitude, false)}"
    }

    companion object {
        fun coordinatesToGridLocator(lat: Double, lon: Double): String {
            // Adjust coordinates
            val adjLon = lon + 180.0
            val adjLat = lat + 90.0

            // First pair of letters (fields)
            val lonField = ('A' + (adjLon / 20).toInt())
            val latField = ('A' + (adjLat / 10).toInt())

            // Digits (squares)
            val lonSquare = ((adjLon % 20) / 2).toInt()
            val latSquare = (adjLat % 10).toInt()

            // Subsquare letters
            val lonSub = ('a' + (((adjLon % 2) / 2) * 24).toInt())
            val latSub = ('a' + (((adjLat % 1) / 1) * 24).toInt())

            return "$lonField$latField$lonSquare$latSquare$lonSub$latSub"
        }

        fun toDMS(value: Double, isLatitude: Boolean): String {
            val hemisphere = if (isLatitude) {
                if (value >= 0) "N" else "S"
            } else {
                if (value >= 0) "E" else "W"
            }
            val absValue = abs(value)
            val degrees = floor(absValue).toInt()
            val minutesFull = (absValue - degrees) * 60
            val minutes = floor(minutesFull).toInt()
            val seconds = (minutesFull - minutes) * 60
            return String.format("%d°%d'%.1f\"%s", degrees, minutes, seconds, hemisphere)
        }

        fun fromGridLocator(locator: String): Coordinates? {
            val out = maidenheadToLatLon(locator)
            return if(out == null) null
            else Coordinates(out.first, out.second)
        }

        fun fromDMS(dms: String): Coordinates? {
            val parts = dms.split(',').map { it.trim() }
            if (parts.size != 2) return null

            val lat = parseDMSPart(parts[0], isLatitude = true) ?: return null
            val lon = parseDMSPart(parts[1], isLatitude = false) ?: return null

            return Coordinates(lat, lon)
        }

        private fun parseDMSPart(part: String, isLatitude: Boolean): Double? {
            val validHemispheres = if (isLatitude) "NS" else "EW"
            fun valuesToDouble(deg: String, min: String, sec: String, hem: String): Double? {
                val degrees = deg.toDoubleOrNull() ?: return null
                val minutes = min.toDoubleOrNull() ?: 0.0
                val seconds = sec.toDoubleOrNull() ?: 0.0
                val sign = if (hem.equals("S", ignoreCase = true) || hem.equals(
                        "W",
                        ignoreCase = true
                    )
                ) -1.0 else 1.0
                val out = sign * (degrees + minutes / 60.0 + seconds / 3600.0)
                return out
            }
            if (part.contains('°')) {
                // formats like: 40°50'10"N
                val regex =
                    """(\d{1,3})°\s?(\d{1,2})'\s?(\d{1,2}(?:\.\d+)?)"\s?([$validHemispheres])""".toRegex(
                        RegexOption.IGNORE_CASE
                    )
                val match = regex.find(part)
                match?.let {
                    val (degStr, minStr, secStr, hemStr) = it.destructured
                    return valuesToDouble(degStr, minStr, secStr, hemStr)
                }
            } else {
                // formats like: 138E40'59", 52N31
                val regex =
                    """(\d{1,3})([$validHemispheres])(\d\d)'?(\d\d)?"?""".toRegex(RegexOption.IGNORE_CASE)
                val match = regex.find(part)
                match?.let {
                    val (degStr, hemStr, minStr, secStr) = it.destructured
                    return valuesToDouble(degStr, minStr, secStr, hemStr)
                }
            }
            return null
        }

        private val MAIDENHEAD = Regex("""[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2})?)?""", RegexOption.IGNORE_CASE)

        /** Centre of a 4, 6 or 8 character Maidenhead locator ("JN49", "JN49hl", "JN49hl37");
         *  null for anything else - also for a half-typed one, without throwing. */
        fun maidenheadToLatLon(locator: String): Pair<Double, Double>? {
            if (!MAIDENHEAD.matches(locator)) return null
            var lon = (locator[0].uppercaseChar() - 'A') * 20.0 - 180.0 + locator[2].digitToInt() * 2.0
            var lat = (locator[1].uppercaseChar() - 'A') * 10.0 - 90.0 + locator[3].digitToInt() * 1.0
            var lonCell = 2.0
            var latCell = 1.0
            if (locator.length >= 6) {
                lonCell /= 24; latCell /= 24
                lon += (locator[4].lowercaseChar() - 'a') * lonCell
                lat += (locator[5].lowercaseChar() - 'a') * latCell
            }
            if (locator.length == 8) {
                lonCell /= 10; latCell /= 10
                lon += locator[6].digitToInt() * lonCell
                lat += locator[7].digitToInt() * latCell
            }
            return Pair(lat + latCell / 2, lon + lonCell / 2) // the centre of the cell
        }
    }
}

@Serializable
data class Schedule(
    val version: Int = 1,
    val startTimeUtc: String = "0000",      // e.g. "0930"  for 9:30 Utc
    val endTimeUtc: String = "2400",
    val days: Set<Weekday> = emptySet(),
    val remarks: String? = null
) {
    @Serializable
    enum class Weekday(val shortName: String, val fullName: String) {
        MONDAY("Mo", "Monday"),
        TUESDAY("Tu", "Tuesday"),
        WEDNESDAY("We", "Wednesday"),
        THURSDAY("Th", "Thursday"),
        FRIDAY("Fr", "Friday"),
        SATURDAY("Sa", "Saturday"),
        SUNDAY("Su", "Sunday");

        companion object {
            fun fromShortName(short: String): Weekday? =
                entries.firstOrNull { it.shortName.equals(short, ignoreCase = true) }

            val ordered = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY, SUNDAY)
        }
    }
    fun isEmpty(): Boolean {
        return startTimeUtc == "0000" && endTimeUtc == "2400" && days.isEmpty() && remarks?.ifEmpty { null } == null
    }
    fun isNotEmpty(): Boolean = !isEmpty()
    inline fun ifEmpty(alt: () -> Schedule?): Schedule? = if(isEmpty()) alt() else this
}



@Serializable
data class DemodulationParameters(
    val version: Int = 1,
    @Serializable(with = SquelchSerializer::class)
    val squelch: Squelch?,
    val rtty: Rtty? = null,
    val pocsag: Pocsag? = null,
    val ax25: Ax25? = null,
    val psk31: Psk31? = null,
    val r09: R09? = null,
    val lora: Lora? = null,
) {
    @Serializable
    data class Squelch(
        val enabled: Boolean = false,
        /** dB above the noise floor (see Squelch.kt). */
        val marginDb: Float = AppStateRepository.DEFAULT_SQUELCH_MARGIN,
    )
    @Serializable
    data class Rtty(
        val baudRate: Float = DigitalDemodParams.Rtty.AUTO,        // AUTO (0) / 45.45 / 50 / 75 / 100
        val frequencyShift: Float = DigitalDemodParams.Rtty.AUTO,  // AUTO (0) / 85 / 170 / 450 / 850 Hz
        val polarity: RttyPolarity = RttyPolarity.AUTO,
    )
    @Serializable
    data class Pocsag(
        val baudRate: Int = 0,  // 0 = Auto (try 512/1200/2400 in parallel), else 512/1200/2400
    )
    @Serializable
    data class Psk31(
        val baudRate: Float = DigitalDemodParams.Psk31.AUTO,      // AUTO (0) / 31.25 / 62.5 / 125
    )
    @Serializable
    data class R09(
        val baudRate: Int = DigitalDemodParams.R09.AUTO,        // AUTO (0) / 2400 / 1200
    )
    @Serializable
    data class Lora(
        val spreadingFactors: Int = LoraSpreadingFactors.AUTO, // a LoraSpreadingFactors set; AUTO = SF7-12
        val iqMode: LoraIqMode = LoraIqMode.AUTO,
    )
    @Serializable
    data class Ax25(
        val baudRate: Int = 1200,  // 300 (HF), 1200 (VHF/Bell202), 9600 (G3RUH): the AX.25 variant
    )
}


enum class SourceProvider(val displayName: String, val color: Int) {
    BOOKMARK("Bookmark", Color.argb(255, 0, 0, 150) /*dark blue*/),
    EIBI("EiBi DB", Color.argb(255, 150, 0, 0) /*dark red*/),
    POTA("POTA Spots", Color.argb(255, 0, 150, 0) /*dark green*/),
    SOTA("SOTA Spots", Color.argb(255, 230, 230, 230) /*light grey*/),
    DIGITAL_LIST("Digital Stations", Color.argb(255, 0, 150, 150) /*teal*/),
}

/** Whether [this] source is offered (DigitalFeatures: the digital station list only while digital features are shown). */
val SourceProvider.shown: Boolean get() = this != SourceProvider.DIGITAL_LIST || DigitalFeatures.shown

@Serializable
data class SubBand(
    val version: Int = 1,
    val name: String,
    val startFrequency: Long,
    val endFrequency: Long,
    val notes: String? = null
)

enum class BookmarkListType(val displayName: String) {
    STATION("Stations"),
    BAND("Bands"),
}

/**
 * 2.2.1 stored the squelch threshold as an absolute level ("thresholdDb", in a half-dB unit;
 * pre-release builds of 2.3 "thresholdDbfs"). It depended on the SDR, its gain and the FFT size and
 * isn't carried over: a bookmark that had one gets the default margin above the noise floor.
 */
object SquelchSerializer : JsonTransformingSerializer<DemodulationParameters.Squelch>(DemodulationParameters.Squelch.serializer()) {
    private val LEGACY_KEYS = setOf("thresholdDb", "thresholdDbfs")

    override fun transformDeserialize(element: JsonElement): JsonElement =
        if (element !is JsonObject) element else JsonObject(element.filterKeys { it !in LEGACY_KEYS })
}

// --- Type Converters ---
/** Stored station JSON may hold fields this build doesn't know (written by a newer one, restored
 *  from a backup): they are skipped rather than failing every query that reads the row. */
private val stationJson = Json { ignoreUnknownKeys = true }

class StationTypeConverters {
    @TypeConverter
    fun fromCoordinates(coords: Coordinates?): String? =
        coords?.let { stationJson.encodeToString(Coordinates.serializer(), it) }

    @TypeConverter
    fun toCoordinates(json: String?): Coordinates? =
        json?.let { stationJson.decodeFromString(Coordinates.serializer(), it) }

    @TypeConverter
    fun fromSchedule(schedule: Schedule?): String? =
        schedule?.let { stationJson.encodeToString(Schedule.serializer(), it) }

    @TypeConverter
    fun toSchedule(json: String?): Schedule? =
        json?.let { stationJson.decodeFromString(Schedule.serializer(), it) }

    @TypeConverter
    fun fromSubBands(subBands: List<SubBand>?): String? =
        subBands?.let { stationJson.encodeToString(ListSerializer(SubBand.serializer()), it) }

    @TypeConverter
    fun toSubBands(json: String?): List<SubBand>? =
        json?.let { stationJson.decodeFromString(ListSerializer(SubBand.serializer()), it) }

    @TypeConverter
    fun fromDemodulationParameters(value: DemodulationParameters?): String? =
        value?.let { stationJson.encodeToString(it) }

    @TypeConverter
    fun toDemodulationParameters(json: String?): DemodulationParameters? =
        json?.let { stationJson.decodeFromString<DemodulationParameters>(it) }
}

// --- Entity Wrappers ---

data class StationWithBookmarkList(
    @Embedded
    val station: Station,

    @Relation(
        parentColumn = "bookmarkListId",
        entityColumn = "id"
    )
    val bookmarkList: BookmarkList?
)

data class BandWithBookmarkList(
    @Embedded
    val band: Band,

    @Relation(
        parentColumn = "bookmarkListId",
        entityColumn = "id"
    )
    val bookmarkList: BookmarkList?
)


// --- Entities ---

@Serializable
@Entity(tableName = "bookmarkLists")
data class BookmarkList(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    val type: BookmarkListType = BookmarkListType.STATION,
    val notes: String? = null,
    val color: Int? = null
)

@Serializable
@Entity(
    tableName = "stations",
    indices = [
        Index(value = ["source", "remoteHash"], unique = true),
        Index(value = ["frequency"]),
        Index(value = ["favorite"]),
        Index(value = ["bookmarkListId"]),
        Index(value = ["source"])
    ]
)
data class Station(
    @PrimaryKey(autoGenerate = true) @Transient val id: Long = 0,
    @Transient val remoteHash: String? = null,    // null when source=BOOKMARK, otherwise unique hash
    val bookmarkListId: Long? = null,
    val name: String = "",
    val frequency: Long = 0L,
    val bandwidth: Int = 0,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val mode: DemodulationMode = DemodulationMode.OFF,
    val demodulationParameters: DemodulationParameters? = null,
    val notes: String? = null,
    val favorite: Boolean = false,
    val language: String? = null,
    val callsign: String? = null,
    val countryCode: String? = null,
    @Transient val countryName: String? = null,  // always derived from countryCode
    val address: String? = null,
    val coordinates: Coordinates? = null,
    val schedule: Schedule? = null,
    val spottime: Long? = null,
    @Transient val source: SourceProvider = SourceProvider.BOOKMARK
)

@Serializable
@Entity(
    tableName = "bands",
    indices = [
        Index(value = ["startFrequency"]),
        Index(value = ["endFrequency"]),
        Index(value = ["bookmarkListId"]),
        Index(value = ["favorite"])
    ]
)
data class Band(
    @PrimaryKey(autoGenerate = true) @Transient val id: Long = 0,
    val name: String = "",
    val bookmarkListId: Long? = null,
    val startFrequency: Long = 0L,
    val endFrequency: Long = 0L,
    val subBands: List<SubBand> = emptyList(),
    val notes: String? = null,
    val favorite: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val displayColor: Int? = null
)

@Serializable
@Entity(tableName = "onlineStationProviderSettings")
data class OnlineStationProviderSettings(
    @PrimaryKey val id: String,
    val autoUpdateEnabled: Boolean,
    val autoUpdateIntervalSeconds: Int,
    val lastUpdatedTimestamp: Long
)

// --- DAO Interfaces ---

@Dao
interface BookmarkListDao {
    @Query("SELECT COUNT(*) FROM bookmarkLists WHERE type = :type")
    suspend fun count(type: BookmarkListType): Int

    @Query("SELECT * FROM bookmarkLists ORDER BY name ASC")
    fun getAll(): Flow<List<BookmarkList>>

    @Query("SELECT * FROM bookmarkLists WHERE id = :id LIMIT 1")
    suspend fun getBookmarkListById(id: Long): BookmarkList?

    @Query("SELECT * FROM bookmarkLists WHERE name = :name AND (:type IS NULL OR type = :type) LIMIT 1")
    fun findByNameAndType(name: String, type: BookmarkListType?): Flow<BookmarkList?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(bookmarkList: BookmarkList): Long

    @Update
    suspend fun update(bookmarkList: BookmarkList)

    @Delete
    suspend fun delete(bookmarkList: BookmarkList)

    @Query("DELETE FROM bookmarkLists")
    suspend fun deleteAllBookmarkLists()
}

@Dao
interface StationDao {
    @Query("SELECT * FROM stations WHERE favorite = 1 ORDER BY frequency ASC")
    fun getFavoriteStations(): Flow<List<Station>>

    @Query("SELECT * FROM stations WHERE id IN (:ids)")
    fun getStationsByIds(ids: Set<Long>): Flow<List<Station>>

    @Query("SELECT * FROM stations WHERE source = :source")
    fun getStationsBySource(source: SourceProvider): Flow<List<Station>>

    /** The mode sheet's candidates: the stations of [modes] from [listSource] and the favorites. */
    @Query("SELECT * FROM stations WHERE mode IN (:modes) AND (source = :listSource OR favorite = 1)")
    fun getModeSheetStations(modes: List<DemodulationMode>, listSource: SourceProvider): Flow<List<Station>>

    @Query("SELECT * FROM stations WHERE source = :source AND remoteHash IN (:hashes)")
    suspend fun getBySourceAndHashes(source: SourceProvider, hashes: List<String>): List<Station>

    @Query("SELECT * FROM stations WHERE bookmarkListId IN (:bookmarkListIds)")
    fun getStationsByBookmarkListIds(bookmarkListIds: Set<Long>): Flow<List<Station>>

    @Transaction
    @Query("""
        SELECT s.*, c.*
        FROM stations s
        LEFT JOIN bookmarkLists c ON c.id = s.bookmarkListId
        WHERE
            (:onlyFavorites = 0 OR s.favorite = 1)
        AND (:minFrequency = 0 OR s.frequency >= :minFrequency)
        AND (:maxFrequency = 0 OR s.frequency <= :maxFrequency)        
        AND (:sourcesSize = 0 OR s.source IN (:sources))
        AND (:bookmarkListsSize = 0 OR s.bookmarkListId IN (:bookmarkLists) OR s.source != 'BOOKMARK')
        AND (:search = '' OR (
            s.name LIKE '%' || :search || '%' OR
            s.notes LIKE '%' || :search || '%' OR
            s.language LIKE '%' || :search || '%' OR
            s.callsign LIKE '%' || :search || '%' OR
            s.countryCode LIKE '%' || :search || '%' OR
            s.countryName LIKE '%' || :search || '%'
        ))
        AND (:modeSize = 0 OR s.mode IN (:modes))
        AND (:onlyOnAirNow = 0
            OR s.schedule IS NULL
            OR (
                (
                    s.schedule NOT LIKE '%"days":[%'  -- no days field OR empty days
                    OR s.schedule LIKE '%"days":[]%'
                    OR s.schedule LIKE '%' || '"' || :currentWeekday || '"' || '%'
                )
                AND (
                    s.schedule NOT LIKE '%"startTimeUtc":"%'
                    OR CAST(substr(s.schedule, instr(s.schedule, '"startTimeUtc":"') + 16, 4) AS INTEGER) <= :currentTimeUtc
                )
                AND (
                    s.schedule NOT LIKE '%"endTimeUtc":"%'
                    OR CAST(substr(s.schedule, instr(s.schedule, '"endTimeUtc":"') + 14, 4) AS INTEGER) >= :currentTimeUtc
                )
            )
        )
        ORDER BY s.frequency ASC, s.name ASC, s.source ASC
    """)
    fun getStationsFiltered(
        onlyFavorites: Boolean,
        minFrequency: Long,
        maxFrequency: Long,
        sources: Set<SourceProvider>,
        sourcesSize: Int,
        bookmarkLists: Set<Long>,
        bookmarkListsSize: Int,
        modes: Set<DemodulationMode>,
        modeSize: Int,
        search: String,
        onlyOnAirNow: Boolean,
        currentTimeUtc: Int,  // e.g. 1530
        currentWeekday: String,  // e.g. "MONDAY"
    ): Flow<List<StationWithBookmarkList>>

    @Transaction
    @Query("""
        SELECT s.*, c.*
        FROM stations s
        LEFT JOIN bookmarkLists c ON c.id = s.bookmarkListId
        WHERE
            (:onlyFavorites = 0 OR s.favorite = 1)
        AND (:minFrequency = 0 OR s.frequency >= :minFrequency)
        AND (:maxFrequency = 0 OR s.frequency <= :maxFrequency)        
        AND (:sourcesSize = 0 OR s.source IN (:sources))
        AND (:bookmarkListsSize = 0 OR s.bookmarkListId IN (:bookmarkLists) OR s.source != 'BOOKMARK')
        AND (:search = '' OR (
            s.name LIKE '%' || :search || '%' OR
            s.notes LIKE '%' || :search || '%' OR
            s.language LIKE '%' || :search || '%' OR
            s.callsign LIKE '%' || :search || '%' OR
            s.countryCode LIKE '%' || :search || '%' OR
            s.countryName LIKE '%' || :search || '%'
        ))
        AND (:modeSize = 0 OR s.mode IN (:modes))
        AND (:onlyOnAirNow = 0
            OR s.schedule IS NULL
            OR (
                (
                    s.schedule NOT LIKE '%"days":[%'  -- no days field OR empty days
                    OR s.schedule LIKE '%"days":[]%'
                    OR s.schedule LIKE '%' || '"' || :currentWeekday || '"' || '%'
                )
                AND (
                    s.schedule NOT LIKE '%"startTimeUtc":"%'
                    OR CAST(substr(s.schedule, instr(s.schedule, '"startTimeUtc":"') + 16, 4) AS INTEGER) <= :currentTimeUtc
                )
                AND (
                    s.schedule NOT LIKE '%"endTimeUtc":"%'
                    OR CAST(substr(s.schedule, instr(s.schedule, '"endTimeUtc":"') + 14, 4) AS INTEGER) >= :currentTimeUtc
                )
            )
        )
        ORDER BY s.frequency ASC, s.name ASC, s.source ASC
    """)
    fun getStationsFilteredPaging(
        onlyFavorites: Boolean,
        minFrequency: Long,
        maxFrequency: Long,
        sources: Set<SourceProvider>,
        sourcesSize: Int,
        bookmarkLists: Set<Long>,
        bookmarkListsSize: Int,
        modes: Set<DemodulationMode>,
        modeSize: Int,
        search: String,
        onlyOnAirNow: Boolean,
        currentTimeUtc: Int,  // e.g. 1530
        currentWeekday: String,  // e.g. "MONDAY"
    ): PagingSource<Int, StationWithBookmarkList>

    @Query("""
        SELECT s.id
        FROM stations s
        LEFT JOIN bookmarkLists c ON c.id = s.bookmarkListId
        WHERE
            (:onlyFavorites = 0 OR s.favorite = 1)
        AND (:minFrequency = 0 OR s.frequency >= :minFrequency)
        AND (:maxFrequency = 0 OR s.frequency <= :maxFrequency)        
        AND (:sourcesSize = 0 OR s.source IN (:sources))
        AND (:bookmarkListsSize = 0 OR s.bookmarkListId IN (:bookmarkLists) OR s.source != 'BOOKMARK')
        AND (:search = '' OR (
            s.name LIKE '%' || :search || '%' OR
            s.notes LIKE '%' || :search || '%' OR
            s.language LIKE '%' || :search || '%' OR
            s.callsign LIKE '%' || :search || '%' OR
            s.countryCode LIKE '%' || :search || '%' OR
            s.countryName LIKE '%' || :search || '%'
        ))
        AND (:modeSize = 0 OR s.mode IN (:modes))
        AND (:onlyOnAirNow = 0
            OR s.schedule IS NULL
            OR (
                (
                    s.schedule NOT LIKE '%"days":[%'  -- no days field OR empty days
                    OR s.schedule LIKE '%"days":[]%'
                    OR s.schedule LIKE '%' || '"' || :currentWeekday || '"' || '%'
                )
                AND (
                    s.schedule NOT LIKE '%"startTimeUtc":"%'
                    OR CAST(substr(s.schedule, instr(s.schedule, '"startTimeUtc":"') + 16, 4) AS INTEGER) <= :currentTimeUtc
                )
                AND (
                    s.schedule NOT LIKE '%"endTimeUtc":"%'
                    OR CAST(substr(s.schedule, instr(s.schedule, '"endTimeUtc":"') + 14, 4) AS INTEGER) >= :currentTimeUtc
                )
            )
        )
        ORDER BY s.frequency ASC, s.name ASC, s.source ASC
    """)
    fun getStationIdsFiltered(
        onlyFavorites: Boolean,
        minFrequency: Long,
        maxFrequency: Long,
        sources: Set<SourceProvider>,
        sourcesSize: Int,
        bookmarkLists: Set<Long>,
        bookmarkListsSize: Int,
        modes: Set<DemodulationMode>,
        modeSize: Int,
        search: String,
        onlyOnAirNow: Boolean,
        currentTimeUtc: Int,  // e.g. 1530
        currentWeekday: String,  // e.g. "MONDAY"
    ): Flow<List<Long>>


    @Query("DELETE FROM stations WHERE source = :source")
    suspend fun clearBySource(source: SourceProvider)

    @Transaction
    suspend fun replaceAllBySource(stations: List<Station>, source: SourceProvider) {
        val hashes = stations.mapNotNull { it.remoteHash }

        val existing = getBySourceAndHashes(source, hashes)
            .associateBy { it.remoteHash }

        // insert id for all stations that are already in the db
        val entities = stations.map { station ->
            val oldEntry = existing[station.remoteHash]
            station.copy(id = oldEntry?.id ?: 0)
        }

        insertAll(entities)
        deleteMissingForSource(source, hashes)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(station: Station): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stations: List<Station>)

    @Update
    suspend fun update(station: Station)

    @Query("UPDATE stations SET bookmarkListId = :bookmarkListId, updatedAt = :updatedAt, remoteHash = null, source = :source WHERE id IN (:ids)")
    suspend fun moveToBookmarkListByIds(ids: List<Long>, bookmarkListId: Long, updatedAt: Long, source: SourceProvider)

    @Delete
    suspend fun delete(station: Station)

    @Query("DELETE FROM stations WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("DELETE FROM stations WHERE bookmarkListId = :bookmarkListId")
    suspend fun deleteByBookmarkList(bookmarkListId: Long)

    @Query("""
        DELETE FROM stations
        WHERE source = :source
          AND remoteHash IS NOT NULL
          AND remoteHash NOT IN (:hashes)
    """)
    suspend fun deleteMissingForSource(
        source: SourceProvider,
        hashes: List<String>
    )

}

@Dao
interface BandDao {
    @Query("SELECT * FROM bands ORDER BY startFrequency ASC")
    fun getAllBands(): Flow<List<Band>>

    @Query("SELECT * FROM bands WHERE favorite = 1 ORDER BY startFrequency ASC")
    fun getFavoriteBands(): Flow<List<Band>>

    @Query("SELECT * FROM bands WHERE bookmarkListId IN (:bookmarkListIds)")
    fun getBandsByBookmarkListIds(bookmarkListIds: Set<Long>): Flow<List<Band>>

    @Query("SELECT * FROM bands WHERE id IN (:ids)")
    fun getBandsByIds(ids: Set<Long>): Flow<List<Band>>

    @Query("""
        SELECT * FROM bands
        WHERE
            (:onlyFavorites = 0 OR favorite = 1)
        AND (:minFrequency = 0 OR endFrequency >= :minFrequency)
        AND (:maxFrequency = 0 OR startFrequency <= :maxFrequency)
        AND (:bookmarkListsSize = 0 OR bookmarkListId IN (:bookmarkLists))
        ORDER BY startFrequency ASC
    """)
    fun getBandsFiltered(
        onlyFavorites: Boolean,
        minFrequency: Long,
        maxFrequency: Long,
        bookmarkLists: Set<Long>,
        bookmarkListsSize: Int
    ): Flow<List<Band>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(band: Band): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(bands: List<Band>)

    @Update
    suspend fun update(band: Band)

    @Query("UPDATE bands SET bookmarkListId = :bookmarkListId, updatedAt = :updatedAt WHERE id IN (:ids)")
    suspend fun moveToBookmarkListByIds(ids: List<Long>, bookmarkListId: Long, updatedAt: Long)

    @Delete
    suspend fun delete(band: Band)

    @Query("DELETE FROM bands")
    suspend fun deleteAllBands()

    @Query("DELETE FROM bands WHERE bookmarkListId = :bookmarkListId")
    suspend fun deleteByBookmarkList(bookmarkListId: Long)

    @Query("DELETE FROM bands WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM bands WHERE id = :id LIMIT 1")
    suspend fun getBandById(id: Long): Band?
}

@Dao
interface OnlineStationProviderSettingsDao {
    @Query("SELECT * FROM onlineStationProviderSettings ORDER BY id ASC")
    fun getAllOnlineStationProviderSettings(): Flow<List<OnlineStationProviderSettings>>

    @Query("SELECT * FROM onlineStationProviderSettings WHERE id = :id LIMIT 1")
    fun getById(id: String): Flow<OnlineStationProviderSettings?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(settings: OnlineStationProviderSettings): Long

    @Update
    suspend fun update(settings: OnlineStationProviderSettings)

    @Delete
    suspend fun delete(settings: OnlineStationProviderSettings)
}

val COUNTRY_CODE_TO_NAME: Map<String, String?> = mapOf(
    "AF" to "Afghanistan",
    "AL" to "Albania",
    "DZ" to "Algeria",
    "AS" to "American Samoa",
    "AD" to "Andorra",
    "AO" to "Angola",
    "AI" to "Anguilla",
    "AQ" to "Antarctica",
    "AG" to "Antigua & Barbuda",
    "AR" to "Argentina",
    "AM" to "Armenia",
    "AW" to "Aruba",
    "AU" to "Australia",
    "AT" to "Austria",
    "AZ" to "Azerbaijan",
    "BS" to "Bahamas",
    "BH" to "Bahrain",
    "BD" to "Bangladesh",
    "BB" to "Barbados",
    "BY" to "Belarus",
    "BE" to "Belgium",
    "BZ" to "Belize",
    "BJ" to "Benin",
    "BM" to "Bermuda",
    "BT" to "Bhutan",
    "BO" to "Bolivia",
    "BA" to "Bosnia & Herzegovina",
    "BW" to "Botswana",
    "BV" to "Bouvet Island",
    "BR" to "Brazil",
    "IO" to "British Indian Ocean Territory",
    "VG" to "British Virgin Islands",
    "BN" to "Brunei",
    "BG" to "Bulgaria",
    "BF" to "Burkina Faso",
    "BI" to "Burundi",
    "KH" to "Cambodia",
    "CM" to "Cameroon",
    "CA" to "Canada",
    "CV" to "Cape Verde",
    "BQ" to "Caribbean Netherlands",
    "KY" to "Cayman Islands",
    "CF" to "Central African Republic",
    "TD" to "Chad",
    "CL" to "Chile",
    "CN" to "China",
    "CX" to "Christmas Island",
    "CC" to "Cocos (Keeling) Islands",
    "CO" to "Colombia",
    "KM" to "Comoros",
    "CG" to "Congo - Brazzaville",
    "CD" to "Congo - Kinshasa",
    "CK" to "Cook Islands",
    "CR" to "Costa Rica",
    "HR" to "Croatia",
    "CU" to "Cuba",
    "CW" to "Curaçao",
    "CY" to "Cyprus",
    "CZ" to "Czechia",
    "CI" to "Côte d’Ivoire",
    "DK" to "Denmark",
    "DJ" to "Djibouti",
    "DM" to "Dominica",
    "DO" to "Dominican Republic",
    "EC" to "Ecuador",
    "EG" to "Egypt",
    "SV" to "El Salvador",
    "GQ" to "Equatorial Guinea",
    "ER" to "Eritrea",
    "EE" to "Estonia",
    "SZ" to "Eswatini",
    "ET" to "Ethiopia",
    "FK" to "Falkland Islands (Islas Malvinas)",
    "FO" to "Faroe Islands",
    "FJ" to "Fiji",
    "FI" to "Finland",
    "FR" to "France",
    "GF" to "French Guiana",
    "PF" to "French Polynesia",
    "TF" to "French Southern Territories",
    "GA" to "Gabon",
    "GM" to "Gambia",
    "GE" to "Georgia",
    "DE" to "Germany",
    "GH" to "Ghana",
    "GI" to "Gibraltar",
    "GR" to "Greece",
    "GL" to "Greenland",
    "GD" to "Grenada",
    "GP" to "Guadeloupe",
    "GU" to "Guam",
    "GT" to "Guatemala",
    "GG" to "Guernsey",
    "GN" to "Guinea",
    "GW" to "Guinea-Bissau",
    "GY" to "Guyana",
    "HT" to "Haiti",
    "HM" to "Heard & McDonald Islands",
    "HN" to "Honduras",
    "HK" to "Hong Kong",
    "HU" to "Hungary",
    "IS" to "Iceland",
    "IN" to "India",
    "ID" to "Indonesia",
    "IR" to "Iran",
    "IQ" to "Iraq",
    "IE" to "Ireland",
    "IM" to "Isle of Man",
    "IL" to "Israel",
    "IT" to "Italy",
    "JM" to "Jamaica",
    "JP" to "Japan",
    "JE" to "Jersey",
    "JO" to "Jordan",
    "KZ" to "Kazakhstan",
    "KE" to "Kenya",
    "KI" to "Kiribati",
    "KW" to "Kuwait",
    "KG" to "Kyrgyzstan",
    "LA" to "Laos",
    "LV" to "Latvia",
    "LB" to "Lebanon",
    "LS" to "Lesotho",
    "LR" to "Liberia",
    "LY" to "Libya",
    "LI" to "Liechtenstein",
    "LT" to "Lithuania",
    "LU" to "Luxembourg",
    "MO" to "Macao",
    "MG" to "Madagascar",
    "MW" to "Malawi",
    "MY" to "Malaysia",
    "MV" to "Maldives",
    "ML" to "Mali",
    "MT" to "Malta",
    "MH" to "Marshall Islands",
    "MQ" to "Martinique",
    "MR" to "Mauritania",
    "MU" to "Mauritius",
    "YT" to "Mayotte",
    "MX" to "Mexico",
    "FM" to "Micronesia",
    "MD" to "Moldova",
    "MC" to "Monaco",
    "MN" to "Mongolia",
    "ME" to "Montenegro",
    "MS" to "Montserrat",
    "MA" to "Morocco",
    "MZ" to "Mozambique",
    "MM" to "Myanmar (Burma)",
    "NA" to "Namibia",
    "NR" to "Nauru",
    "NP" to "Nepal",
    "NL" to "Netherlands",
    "NC" to "New Caledonia",
    "NZ" to "New Zealand",
    "NI" to "Nicaragua",
    "NE" to "Niger",
    "NG" to "Nigeria",
    "NU" to "Niue",
    "NF" to "Norfolk Island",
    "KP" to "North Korea",
    "MK" to "North Macedonia",
    "MP" to "Northern Mariana Islands",
    "NO" to "Norway",
    "OM" to "Oman",
    "PK" to "Pakistan",
    "PW" to "Palau",
    "PS" to "Palestine",
    "PA" to "Panama",
    "PG" to "Papua New Guinea",
    "PY" to "Paraguay",
    "PE" to "Peru",
    "PH" to "Philippines",
    "PN" to "Pitcairn Islands",
    "PL" to "Poland",
    "PT" to "Portugal",
    "PR" to "Puerto Rico",
    "QA" to "Qatar",
    "RO" to "Romania",
    "RU" to "Russia",
    "RW" to "Rwanda",
    "RE" to "Réunion",
    "WS" to "Samoa",
    "SM" to "San Marino",
    "SA" to "Saudi Arabia",
    "SN" to "Senegal",
    "RS" to "Serbia",
    "SC" to "Seychelles",
    "SL" to "Sierra Leone",
    "SG" to "Singapore",
    "SX" to "Sint Maarten",
    "SK" to "Slovakia",
    "SI" to "Slovenia",
    "SB" to "Solomon Islands",
    "SO" to "Somalia",
    "ZA" to "South Africa",
    "GS" to "South Georgia & South Sandwich Islands",
    "KR" to "South Korea",
    "SS" to "South Sudan",
    "ES" to "Spain",
    "LK" to "Sri Lanka",
    "BL" to "St. Barthélemy",
    "SH" to "St. Helena",
    "KN" to "St. Kitts & Nevis",
    "LC" to "St. Lucia",
    "MF" to "St. Martin",
    "PM" to "St. Pierre & Miquelon",
    "VC" to "St. Vincent & Grenadines",
    "SD" to "Sudan",
    "SR" to "Suriname",
    "SJ" to "Svalbard & Jan Mayen",
    "SE" to "Sweden",
    "CH" to "Switzerland",
    "SY" to "Syria",
    "ST" to "São Tomé & Príncipe",
    "TW" to "Taiwan",
    "TJ" to "Tajikistan",
    "TZ" to "Tanzania",
    "TH" to "Thailand",
    "TL" to "Timor-Leste",
    "TG" to "Togo",
    "TK" to "Tokelau",
    "TO" to "Tonga",
    "TT" to "Trinidad & Tobago",
    "TN" to "Tunisia",
    "TR" to "Turkey",
    "TM" to "Turkmenistan",
    "TC" to "Turks & Caicos Islands",
    "TV" to "Tuvalu",
    "UM" to "U.S. Outlying Islands",
    "VI" to "U.S. Virgin Islands",
    "UG" to "Uganda",
    "UA" to "Ukraine",
    "AE" to "United Arab Emirates",
    "GB" to "United Kingdom",
    "US" to "United States",
    "UY" to "Uruguay",
    "UZ" to "Uzbekistan",
    "VU" to "Vanuatu",
    "VA" to "Vatican City",
    "VE" to "Venezuela",
    "VN" to "Vietnam",
    "WF" to "Wallis & Futuna",
    "EH" to "Western Sahara",
    "YE" to "Yemen",
    "ZM" to "Zambia",
    "ZW" to "Zimbabwe",
    "AX" to "Åland Islands",
)
