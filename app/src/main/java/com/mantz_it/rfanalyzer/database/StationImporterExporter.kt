package com.mantz_it.rfanalyzer.database

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.toString

/**
 * <h1>RF Analyzer - StationImporterExporter</h1>
 *
 * Module:      StationImporterExporter.kt
 * Description: Helper class for importing and exporting bookmarks. Supports import from RF Analyzer
 *              json, SDR# xml, SDR++ json and legacy RF Analyzer 1.13 sqlite
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@Serializable
data class FullExportFile(
    val metadata: ExportMetadata = ExportMetadata(),
    val bookmarkLists: List<BookmarkList> = emptyList(),
    val stations: List<Station> = emptyList(),
    val bands: List<Band> = emptyList(),
) {
    @Serializable
    data class ExportMetadata(
        val schemaVersion: Int = CURRENT_DB_SCHEMA_VERSION,
        val appName: String = "RF Analyzer",
        val appVersion: String = BuildConfig.VERSION_NAME,
        val fullBackup: Boolean = false // indicates that this is a full backup of the bookmark db
    )
}
// A type alias for the parsed data structure for clarity
private typealias ParsedData = Triple<List<BookmarkList>, List<Station>, List<Band>>

enum class ImportFormat { INTERNAL_JSON, LEGACY_RFANALYZER_DB, SDRPP, SDRSHARP, UNKNOWN }

enum class ImportOption(val label: String, val description: String) {
    IMPORT_AS_IS("Import as is", "Import as is, merging lists without any modifications."),
    IMPORT_INTO_SINGLE("Import into single list", "Import stations/bands into a single new list and discard imported lists."),
    IMPORT_WITH_PREFIX("Import with prefix", "Import stations/bands into a new lists by prefixing the imported lists with a tag.")
}

// One entry per bundled JSON list that can be imported from the "Import Default Lists" dialog.
enum class DefaultImportSet(
    val title: String,
    val description: String,
    val bookmarkListType: BookmarkListType,
    val bookmarkListColor: Int
) {
    AMATEUR_BANDS(
        title = "Amateur Radio Band Plan",
        description = "IARU band plan for the selected region.",
        bookmarkListType = BookmarkListType.BAND,
        bookmarkListColor = 0xFF26A69A.toInt() // teal
    ) {
        override fun assetFileName(region: Int) = "BandPlans/iaru${region}_bandplan.json"
        override fun bookmarkListName(region: Int) = "Amateur Radio Bands"
        override fun bookmarkListNotes(region: Int) = "Band Plans from the IARU (Region $region)"
    },
    LICENSE_FREE_BANDS(
        title = "License-Free Bands",
        description = "CB, PMR446/FRS/MURS, GMRS and similar license-free allocations for the selected region.",
        bookmarkListType = BookmarkListType.BAND,
        bookmarkListColor = 0xFF7CB342.toInt() // light green
    ) {
        override fun assetFileName(region: Int) = "BandPlans/license_free_r${region}_bandplan.json"
        override fun bookmarkListName(region: Int) = "License-Free Bands"
        override fun bookmarkListNotes(region: Int) = "License-free band allocations for Region $region"
    },
    ISM_MARITIME_AIR(
        title = "ISM, Maritime and Air Band Plan",
        description = "Global ISM, maritime and aviation band allocations.",
        bookmarkListType = BookmarkListType.BAND,
        bookmarkListColor = 0xFFAF4706.toInt() // orange
    ) {
        override fun assetFileName(region: Int) = "BandPlans/ISM_maritime_air.json"
        override fun bookmarkListName(region: Int) = "ISM, Maritime, Air Bands"
        override fun bookmarkListNotes(region: Int) = "Global band allocations such as ISM, Maritime and Air Bands."
    };

    abstract fun assetFileName(region: Int): String
    abstract fun bookmarkListName(region: Int): String
    abstract fun bookmarkListNotes(region: Int): String
}

enum class ParseStatus {
    SUCCESS,
    WRONG_FORMAT, // The file could be read, but the content doesn't match the expected format.
    PARSE_ERROR,    // An exception occurred during parsing (e.g., malformed XML/JSON).
    FILE_READ_ERROR,
    UNSUPPORTED,
}

data class ParsedImport(
    val status: ParseStatus,
    val detectedFormat: ImportFormat,
    val data: ParsedData,
    val fullBackup: Boolean = false,  // indicates that the parsed data is a full backup of the bookmark db
    val errorMessage: String? = null
) {
    // Convenience properties for easier access in the UI
    val bookmarkLists: List<BookmarkList> get() = data.first
    val stations: List<Station> get() = data.second
    val bands: List<Band> get() = data.third
}


// ---------- Main Importer/Exporter ----------

@Singleton
class StationImporterExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val stationRepository: StationRepository
) {
    companion object {
        private const val TAG = "StationImporterExporter"
    }
    private val json = Json { prettyPrint = true; encodeDefaults = true; explicitNulls = false; ignoreUnknownKeys = true }

    // Export full DB into internal JSON (but only bookmarks, not other sources such as POTA, etc)
    suspend fun exportAll(outUri: Uri): Boolean {
        Log.d(TAG, "exportAll: Exporting all bookmarks to $outUri")
        val cats = stationRepository.getAllBookmarkLists().first()
        val stations = stationRepository.getStationsBySource(SourceProvider.BOOKMARK).first()
        val bands = stationRepository.getAllBands().first()
        val export = FullExportFile(metadata = FullExportFile.ExportMetadata(fullBackup = true),bookmarkLists = cats, stations = stations, bands = bands)
        val exportContent = json.encodeToString(export)
        return withContext(Dispatchers.IO) {
            saveFile(outUri, exportContent.toByteArray())
        }
    }

    // Builds the JSON export content without writing it anywhere - used both by export() below
    // (which writes it to a destination Uri) and by the "Share" export flow (ExportSheet), which
    // writes it to a temp file instead.
    fun buildExportJson(bookmarkLists: List<BookmarkList>, stations: List<Station>, bands: List<Band>): String {
        val export = FullExportFile(bookmarkLists = bookmarkLists, stations = stations, bands = bands)
        return json.encodeToString(export)
    }

    // Export selected stations bands and bookmarkLists
    suspend fun export(outUri: Uri, bookmarkLists: List<BookmarkList>, stations: List<Station>, bands: List<Band>): Boolean {
        Log.d(TAG, "export: Exporting ${stations.size} stations, ${bands.size} bands, and ${bookmarkLists.size} to $outUri")
        val exportContent = buildExportJson(bookmarkLists, stations, bands)
        return withContext(Dispatchers.IO) {
            saveFile(outUri, exportContent.toByteArray())
        }
    }

    // Import a bundled default list (band plan or station list) from assets:
    suspend fun importDefaultSet(set: DefaultImportSet, region: Int): Boolean {
        val fileName = set.assetFileName(region)
        val content = try {
            context.assets.open(fileName).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "importDefaultSet: Exception reading file $fileName: ${e.message}", e)
            null
        } ?: return false

        val parsedImport = parseInternalJson(content)
        if (parsedImport.status != ParseStatus.SUCCESS) {
            Log.e(TAG, "importDefaultSet: Could not parse file $fileName")
            return false
        }

        val bookmarkListId = stationRepository.insertBookmarkList(BookmarkList(
            id = 0,
            name = set.bookmarkListName(region),
            type = set.bookmarkListType,
            notes = set.bookmarkListNotes(region),
            color = set.bookmarkListColor,
        ))
        val (success, errorMsg) = when (set.bookmarkListType) {
            BookmarkListType.STATION -> processParsedData(parsedImport, ImportOption.IMPORT_INTO_SINGLE, bookmarkListId, null, "")
            BookmarkListType.BAND -> processParsedData(parsedImport, ImportOption.IMPORT_INTO_SINGLE, null, bookmarkListId, "")
        }
        if (!success) Log.e(TAG, "importDefaultSet: Failed to import ${set.title} (region $region): $errorMsg")
        return success
    }

    suspend fun tryToParseUri(uri: Uri, forcedFormat: ImportFormat? = null): ParsedImport {
        val fileContent = withContext(Dispatchers.IO) {
            try {
                readFile(uri)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to read file for URI: $uri", e)
                null
            }
        }

        if (fileContent == null) {
            return ParsedImport(ParseStatus.FILE_READ_ERROR, ImportFormat.UNKNOWN, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "Failed to read file content.")
        }

        if (forcedFormat != null) {
            // User has manually selected a format, try only that one.
            return when (forcedFormat) {
                ImportFormat.LEGACY_RFANALYZER_DB -> parseFromLegacySqlite(fileContent)
                ImportFormat.INTERNAL_JSON -> parseInternalJson(fileContent)
                ImportFormat.SDRPP -> parseFromSdrpp(fileContent)
                ImportFormat.SDRSHARP -> parseFromSdrSharp(fileContent)
                else -> ParsedImport(ParseStatus.UNSUPPORTED, forcedFormat, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "This format is not supported for import.")
            }
        }

        // Automatic detection logic
        val fileExtension = File(uri.path.orEmpty()).extension.lowercase()

        // Try parsers in a smart order (most specific to most general)
        val parsersToTry = when(fileExtension) {
            "json" -> listOf(::parseInternalJson, ::parseFromSdrpp)
            "xml" -> listOf(::parseFromSdrSharp)
            "db" -> listOf(::parseFromLegacySqlite)
            else -> listOf( // Try all if extension is unknown
                ::parseInternalJson,
                ::parseFromLegacySqlite,
                ::parseFromSdrpp,
                ::parseFromSdrSharp
            )
        }

        for (parser in parsersToTry) {
            val result = parser(fileContent)
            if (result.status == ParseStatus.SUCCESS) {
                // Found a successful parser, return its result immediately.
                return result
            }
        }

        // If no parser succeeded
        return ParsedImport(ParseStatus.WRONG_FORMAT, ImportFormat.UNKNOWN, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "Could not determine file format. Please select one manually.")
    }

    suspend fun importParsedData(parsedImport: ParsedImport, option: ImportOption, targetStationBookmarkList: BookmarkList? = null, targetBandBookmarkList: BookmarkList? = null, prefix: String = ""): Pair<Boolean, String> {
        if (parsedImport.status != ParseStatus.SUCCESS) {
            return Pair(false, "Cannot import data that was not successfully parsed.")
        }

        if (option == ImportOption.IMPORT_INTO_SINGLE) {
            if (parsedImport.stations.isNotEmpty() && (targetStationBookmarkList == null || targetStationBookmarkList.type != BookmarkListType.STATION)) {
                return Pair(false, "Target station list must be of type STATION.")
            }
            if (parsedImport.bands.isNotEmpty() && (targetBandBookmarkList == null || targetBandBookmarkList.type != BookmarkListType.BAND)) {
                return Pair(false, "Target band list must be of type BAND.")
            }
        }

        return processParsedData(parsedImport, option, targetStationBookmarkList?.id, targetBandBookmarkList?.id, prefix)
    }

    suspend fun importAndRestore(uri: Uri): Pair<Boolean, String> {
        val parsedResult = tryToParseUri(uri, ImportFormat.INTERNAL_JSON)

        if (parsedResult.status != ParseStatus.SUCCESS) {
            return Pair(false, "Invalid backup file: ${parsedResult.errorMessage}")
        }
        if (parsedResult.stations.isEmpty() && parsedResult.bands.isEmpty() && parsedResult.bookmarkLists.isEmpty()) {
            return Pair(false, "Backup file is empty.")
        }
        if (!parsedResult.fullBackup) {
            return Pair(false, "File is not a full backup of a bookmark database.")
        }

        // clear database before restore
        stationRepository.deleteAllBookmarkStationsBandsAndLists()
        return importParsedData(parsedResult, ImportOption.IMPORT_AS_IS)
    }

    private suspend fun processParsedData(parsedImport: ParsedImport, option: ImportOption, targetStationBookmarkListId: Long?, targetBandBookmarkListId: Long?, prefix: String): Pair<Boolean, String> {
        val (parsedBookmarkLists, parsedStations, parsedBands) = parsedImport.data
        val defaultListName = if (option == ImportOption.IMPORT_WITH_PREFIX) "$prefix (${parsedImport.detectedFormat}) ${LocalDate.now()}"
            else "Imported (${parsedImport.detectedFormat}) ${LocalDate.now()}"
        if (parsedStations.isEmpty() && parsedBands.isEmpty()) {
            return Pair(true, "No stations or bands found to import.")
        }

        val legacyCatIdToNewCatId = mutableMapOf<Long, Long>()
        val singleStationList = parsedBookmarkLists.singleOrNull { it.type == BookmarkListType.STATION }
        val singleBandList = parsedBookmarkLists.singleOrNull { it.type == BookmarkListType.BAND }
        val defaultStationBookmarkList =
            if (option != ImportOption.IMPORT_INTO_SINGLE && singleStationList == null
                && parsedStations.any { station -> station.bookmarkListId == null || !parsedBookmarkLists.any { it.id == station.bookmarkListId && it.type == BookmarkListType.STATION } } // is there any station that does not have a valid bookmarkList set?
            ) {
                stationRepository.insertBookmarkList(BookmarkList(
                    id = 0,
                    name = defaultListName,
                    type = BookmarkListType.STATION,
                ))
            } else null
        val defaultBandBookmarkList =
            if (option != ImportOption.IMPORT_INTO_SINGLE && singleBandList == null
                && parsedBands.any { band -> band.bookmarkListId == null || !parsedBookmarkLists.any { it.id == band.bookmarkListId && it.type == BookmarkListType.BAND} } // is there any band that does not have a valid bookmarkList set?
            ) {
                stationRepository.insertBookmarkList(BookmarkList(
                    id = 0,
                    name = defaultListName,
                    type = BookmarkListType.BAND,
                ))
            } else null


        // Step 1: Create new bookmarkLists based on import option
        if (option == ImportOption.IMPORT_AS_IS || option == ImportOption.IMPORT_WITH_PREFIX) {
            for (parsedBookmarkList in parsedBookmarkLists) {
                val newBookmarkListName = if (option == ImportOption.IMPORT_WITH_PREFIX) prefix + parsedBookmarkList.name else parsedBookmarkList.name
                val existing = stationRepository.findBookmarkListByName(newBookmarkListName, type = parsedBookmarkList.type).first()
                val newId = existing?.id ?: stationRepository.insertBookmarkList(
                    parsedBookmarkList.copy(id = 0, name = newBookmarkListName)
                )
                legacyCatIdToNewCatId[parsedBookmarkList.id] = newId
            }
        }

        // Step 2: Insert stations with mapped bookmarkList IDs
        for (station in parsedStations) {
            val finalBookmarkListId = when (option) {
                ImportOption.IMPORT_INTO_SINGLE -> targetStationBookmarkListId
                else -> {
                    val newId = station.bookmarkListId?.let { legacyCatIdToNewCatId[it] }
                    newId ?: singleStationList?.let { legacyCatIdToNewCatId[it.id] } ?: defaultStationBookmarkList
                }
            }
            val stationToInsert = station.copy(
                id = 0,
                bookmarkListId = finalBookmarkListId,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                source = SourceProvider.BOOKMARK
            )
            stationRepository.insertStation(stationToInsert)
        }

        // Step 3: Insert bands with mapped bookmarkList IDs
        for (band in parsedBands) {
            val finalBookmarkListId = when (option) {
                ImportOption.IMPORT_INTO_SINGLE -> targetBandBookmarkListId
                else -> {
                    val newId = band.bookmarkListId?.let { legacyCatIdToNewCatId[it] }
                    newId ?: singleBandList?.let { legacyCatIdToNewCatId[it.id] } ?: defaultBandBookmarkList
                }
            }
            val bandToInsert = band.copy(
                id = 0,
                bookmarkListId = finalBookmarkListId,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            stationRepository.insertBand(bandToInsert)
        }

        val catCount = when(option) {
            ImportOption.IMPORT_INTO_SINGLE -> if (parsedStations.isNotEmpty() || parsedBands.isNotEmpty()) 1 else 0
            else -> legacyCatIdToNewCatId.size
        }
        return Pair(true, "Imported $catCount lists, ${parsedStations.size} stations, and ${parsedBands.size} bands.")
    }

    // ---------- PARSING FUNCTIONS ----------

    private suspend fun parseInternalJson(rawContent: ByteArray): ParsedImport {
        return try {
            val content = rawContent.toString(Charsets.UTF_8)
            if (!content.contains("\"stations\"") || !content.contains("\"bookmarkLists\"")) {
                return ParsedImport(ParseStatus.WRONG_FORMAT, ImportFormat.INTERNAL_JSON, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "Content does not match internal JSON format.")
            }
            val file = json.decodeFromString<FullExportFile>(content)
            val data = ParsedData(file.bookmarkLists, file.stations, file.bands)
            ParsedImport(ParseStatus.SUCCESS, ImportFormat.INTERNAL_JSON, data, file.metadata.fullBackup)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse as internal JSON", e)
            ParsedImport(ParseStatus.PARSE_ERROR, ImportFormat.INTERNAL_JSON, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "File is not a valid internal JSON backup.")
        }
    }

    private suspend fun parseFromLegacySqlite(content: ByteArray): ParsedImport {
        // SQLiteDatabase requires a file path. We write the byte array to a temporary file.
        val tempFile = withContext(Dispatchers.IO) {
            File.createTempFile("temp_legacy_import", ".db", context.cacheDir).also {
                FileOutputStream(it).use { fos -> fos.write(content) }
            }
        }

        val bookmarkLists = mutableListOf<BookmarkList>()
        val stations = mutableListOf<Station>()
        val catMap = mutableMapOf<Long, BookmarkList>()

        return withContext(Dispatchers.IO) {
            try {
                val db = SQLiteDatabase.openDatabase(tempFile.path, null, SQLiteDatabase.OPEN_READONLY)
                db.use {
                    // RF Analyzer 1.x's schema: its names (categories) must stay as they are
                    // --- Read bookmarkLists ---
                    it.rawQuery("SELECT _id, categoryName, description FROM bookmarkCategories", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val id = cursor.getLong(0)
                            val name = cursor.getString(1) ?: "Unnamed"
                            val desc = cursor.getString(2)
                            val bookmarkList = BookmarkList(id = id, name = name, type = BookmarkListType.STATION, notes = desc, color = generatedColorForName(name))
                            catMap[id] = bookmarkList
                            bookmarkLists.add(bookmarkList)
                        }
                    }

                    // --- Read bookmarks ---
                    it.rawQuery("SELECT _id, name, comment, categoryId, frequency, channelWidth, mode, squelch FROM bookmarks", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(cursor.getColumnIndexOrThrow("name")) ?: "Unnamed"
                            val comment = cursor.getString(cursor.getColumnIndexOrThrow("comment"))
                            val catIdLegacy = cursor.getLong(cursor.getColumnIndexOrThrow("categoryId"))
                            val freq = cursor.getLong(cursor.getColumnIndexOrThrow("frequency"))
                            val width = cursor.getInt(cursor.getColumnIndexOrThrow("channelWidth"))
                            val modeInt = cursor.getInt(cursor.getColumnIndexOrThrow("mode"))
                            val mode = when (modeInt) {
                                1 -> DemodulationMode.AM
                                2 -> DemodulationMode.NFM
                                3 -> DemodulationMode.WFM
                                4 -> DemodulationMode.LSB
                                5 -> DemodulationMode.USB
                                else -> DemodulationMode.OFF
                            }
                            val station = Station(
                                id = 0, // Will be set by DB
                                bookmarkListId = catIdLegacy, // Temporarily store legacy ID for mapping
                                name = name,
                                frequency = freq,
                                bandwidth = width,
                                mode = mode,
                                notes = comment,
                                createdAt = System.currentTimeMillis(),
                                updatedAt = System.currentTimeMillis(),
                                // the legacy absolute squelch level means nothing on today's scale: the default margin
                                demodulationParameters = DemodulationParameters(squelch = DemodulationParameters.Squelch(enabled = true)),
                                source = SourceProvider.BOOKMARK
                            )
                            stations.add(station)
                        }
                    }
                }
                val data = ParsedData(bookmarkLists, stations, emptyList())
                ParsedImport(ParseStatus.SUCCESS, ImportFormat.LEGACY_RFANALYZER_DB, data)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse legacy SQLite DB", e)
                ParsedImport(ParseStatus.PARSE_ERROR, ImportFormat.LEGACY_RFANALYZER_DB, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "Failed to read from legacy SQLite DB. File may be corrupt or in the wrong format.")
            } finally {
                tempFile.delete()
            }
        }
    }

    private suspend fun parseFromSdrpp(rawContent: ByteArray): ParsedImport {
        return try {
            val content = rawContent.toString(Charsets.UTF_8)
            if (!content.contains("\"lists\"") || !content.contains("\"bookmarks\"")) {
                return ParsedImport(ParseStatus.WRONG_FORMAT, ImportFormat.SDRPP, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "Content does not match SDR++ format.")
            }
            val root = json.parseToJsonElement(content).jsonObject
            val lists = root["lists"]?.jsonObject ?: return ParsedImport(ParseStatus.PARSE_ERROR, ImportFormat.SDRPP, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "Missing 'lists' object in SDR++ JSON.")

            val bookmarkLists = mutableListOf<BookmarkList>()
            val stations = mutableListOf<Station>()
            var tempCatId = 1L

            for ((listName, listValue) in lists) {
                val currentCatId = tempCatId++
                bookmarkLists.add(BookmarkList(id = currentCatId, name = listName, type = BookmarkListType.STATION, color = generatedColorForName(listName)))

                val bookmarks = listValue.jsonObject["bookmarks"]?.jsonObject ?: continue
                for ((bookmarkName, bookmarkValue) in bookmarks) {
                    val obj = bookmarkValue.jsonObject
                    val freq = obj["frequency"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
                    val bw = obj["bandwidth"]?.jsonPrimitive?.doubleOrNull?.toInt() ?: 0
                    val (mode, hint) = when(obj["mode"]?.jsonPrimitive?.intOrNull) { // https://github.com/AlexandreRouma/SDRPlusPlus/blob/master/misc_modules/frequency_manager/src/main.cpp#L40
                        0 -> DemodulationMode.NFM to null
                        1 -> DemodulationMode.WFM to null
                        2 -> DemodulationMode.AM to null
                        3 -> DemodulationMode.OFF to "mode: DSB"
                        4 -> DemodulationMode.USB to null
                        5 -> DemodulationMode.CW to null
                        6 -> DemodulationMode.LSB to null
                        7 -> DemodulationMode.OFF to "mode: RAW"
                        else -> DemodulationMode.OFF to null
                    }
                    val notes = if (hint != null) "(from SDR++) $hint" else "(from SDR++)"

                    stations.add(Station(
                        id = 0,
                        bookmarkListId = currentCatId, // Use temp ID for mapping
                        name = bookmarkName,
                        frequency = freq,
                        bandwidth = bw,
                        mode = mode,
                        notes = notes,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        source = SourceProvider.BOOKMARK
                    ))
                }
            }
            val data = ParsedData(bookmarkLists, stations, emptyList())
            ParsedImport(ParseStatus.SUCCESS, ImportFormat.SDRPP, data)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse as SDR++ JSON", e)
            ParsedImport(ParseStatus.PARSE_ERROR, ImportFormat.SDRPP, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "File is not a valid SDR++ JSON.")
        }
    }

    private suspend fun parseFromSdrSharp(rawContent: ByteArray): ParsedImport {
        return try {
            val content = rawContent.toString(Charsets.UTF_8)
            val stationEntryRegex = "<MemoryEntry>(.*?)</MemoryEntry>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val bandEntryRegex = "<RangeEntry (.*?)</RangeEntry>".toRegex(RegexOption.DOT_MATCHES_ALL)
            val stationTagRegex = "<([A-Za-z0-9]+)>(.*?)</\\1>".toRegex()
            val bandTagRegex = "([A-Za-z]+)=\"(.*?)\"".toRegex()
            val stationEntries = stationEntryRegex.findAll(content).map { it.groupValues[1] }.toList()
            val bandEntries = bandEntryRegex.findAll(content).map { it.groupValues[1] }.toList()
            if (stationEntries.isEmpty() && bandEntries.isEmpty())
                return ParsedImport(ParseStatus.WRONG_FORMAT, ImportFormat.SDRSHARP, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "Content does not appear to be an SDR# bookmarks XML file.")

            val bookmarkLists = mutableListOf<BookmarkList>()
            val stations = mutableListOf<Station>()
            val bands = mutableListOf<Band>()
            val groupNameToId = mutableMapOf<String, Long>()
            var tempCatId = 1L

            for (entry in stationEntries) {
                val map = stationTagRegex.findAll(entry).associate { it.groupValues[1] to it.groupValues[2].trim() }
                val groupName = map["GroupName"] ?: "SDR# Stations"

                val currentCatId = groupNameToId.getOrPut(groupName) {
                    val newId = tempCatId++
                    bookmarkLists.add(BookmarkList(id = newId, name = groupName, type = BookmarkListType.STATION, color = generatedColorForName(groupName)))
                    newId
                }
                val detectorType = map["DetectorType"]
                val (mode, hint) = if (detectorType == null) DemodulationMode.OFF to null else when(detectorType.uppercase()) {
                    "NFM" -> DemodulationMode.NFM to null
                    "WFM" -> DemodulationMode.WFM to null
                    "AM" -> DemodulationMode.AM to null
                    "DSB" -> DemodulationMode.OFF to "mode: DSB"
                    "USB" -> DemodulationMode.USB to null
                    "CW" -> DemodulationMode.CW to null
                    "LSB" -> DemodulationMode.LSB to null
                    "RAW" -> DemodulationMode.OFF to "mode: RAW"
                    else -> DemodulationMode.OFF to "mode: $detectorType"
                }

                var notes = map["Notes"]
                if (hint != null) {
                    notes = (notes?.let { "$it; " } ?: "") + hint
                }

                stations.add(Station(
                    id = 0,
                    bookmarkListId = currentCatId,
                    name = map["Name"] ?: "Unnamed",
                    frequency = map["Frequency"]?.toLongOrNull() ?: 0L,
                    bandwidth = map["FilterBandwidth"]?.toIntOrNull() ?: 0,
                    mode = mode,
                    notes = notes,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    favorite = map["IsFavourite"]?.equals("true", ignoreCase = true) ?: false,
                    source = SourceProvider.BOOKMARK
                ))
            }
            val bandList = BookmarkList(
                id = tempCatId++,
                name = "SDR# Bands",
                type = BookmarkListType.BAND,
                color = generatedColorForName("SDR# Bands")
            )
            if (bandEntries.isNotEmpty())
                bookmarkLists.add(bandList)
            for(entry in bandEntries) {
                val map = bandTagRegex.findAll(entry).associate { it.groupValues[1] to it.groupValues[2].trim() }
                val name = entry.substringAfter(">", "Unnamed")
                bands.add(Band(
                    id = 0,
                    bookmarkListId = bandList.id,
                    name = name,
                    startFrequency = map["minFrequency"]?.toLongOrNull() ?: 0L,
                    endFrequency = map["maxFrequency"]?.toLongOrNull() ?: 0L,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                ))
            }
            val data = ParsedData(bookmarkLists, stations, bands)
            ParsedImport(ParseStatus.SUCCESS, ImportFormat.SDRSHARP, data)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse SDR# XML", e)
            ParsedImport(ParseStatus.PARSE_ERROR, ImportFormat.SDRSHARP, ParsedData(emptyList(), emptyList(), emptyList()), errorMessage = "An error occurred while parsing the SDR# XML file.")
        }
    }

    // ---------- Utilities ----------
    fun getLegacyDbUri(): Uri? {
        val dbFile = File("${context.dataDir}/databases/Bookmarks.db")
        return if (dbFile.exists()) dbFile.toUri() else null
    }

    private fun generatedColorForName(name: String): Int {
        val hsv = floatArrayOf((name.hashCode() % 360).toFloat(), 0.35f, 0.95f)
        return Color.HSVToColor(hsv)
    }

    private fun saveFile(uri: Uri, content: ByteArray): Boolean {
        return try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(content) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "saveFile: Exception while saving file: ${e.message}", e)
            false
        }
    }

    private fun readFile(uri: Uri): ByteArray? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "readFile: Exception reading file: ${e.message}", e)
            null
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "readFile: File too large to load in memory", e)
            return null
        }
    }
}
