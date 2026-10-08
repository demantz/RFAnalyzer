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
import com.mantz_it.rfanalyzer.decoder.FlexDecoder
import com.mantz_it.rfanalyzer.ui.composable.DemodulationMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.collections.toString

/**
 * <h1>RF Analyzer - StationImporterExporter</h1>
 *
 * Module:      StationImporterExporter.kt
 * Description: Helper class for importing and exporting bookmarks.
 *              Supports import from RF Analyzer json, SDR# xml, SDR++ json and legacy RF Analyzer 1.13 sqlite
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@Serializable
data class FullExportFile(
    val metadata: ExportMetadata = ExportMetadata(),
    val bookmarkLists: List<BookmarkList> = emptyList(),
    val stations: List<Station> = emptyList(),
    val bands: List<Band> = emptyList(),
    /** User-defined rtl_433 flex decoders (unknown protocol decoders). */
    val flexDecoders: List<FlexDecoder> = emptyList(),
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
    val errorMessage: String? = null,
    val flexDecoders: List<FlexDecoder> = emptyList(),  // custom decoders carried by the file
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
    private val stationRepository: StationRepository,
    private val appStateRepository: AppStateRepository
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
        val export = FullExportFile(
            metadata = FullExportFile.ExportMetadata(fullBackup = true),
            bookmarkLists = cats,
            stations = stations,
            bands = bands,
            flexDecoders = currentFlexDecoders(),
        )
        val exportContent = json.encodeToString(export)
        return withContext(Dispatchers.IO) {
            saveFile(outUri, exportContent.toByteArray())
        }
    }

    // Export selected stations bands and bookmarkLists
    suspend fun export(outUri: Uri, bookmarkLists: List<BookmarkList>, stations: List<Station>, bands: List<Band>): Boolean {
        Log.d(TAG, "export: Exporting ${stations.size} stations, ${bands.size} bands, and ${bookmarkLists.size} to $outUri")
        val export = FullExportFile(
            bookmarkLists = bookmarkLists,
            stations = stations,
            bands = bands,
            flexDecoders = currentFlexDecoders(),
        )
        val exportContent = json.encodeToString(export)
        return withContext(Dispatchers.IO) {
            saveFile(outUri, exportContent.toByteArray())
        }
    }

    // Import Band plan from assets:
    suspend fun importIaruBandPlan(region: Int): Boolean {
        val fileName = "BandPlans/iaru${region}_bandplan.json"
        val content = try {
            context.assets.open(fileName).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "importIaruBandPlan: Exception reading file $fileName: ${e.message}", e)
            null
        }
        if (content == null) {
            Log.e(TAG, "importIaruBandPlan: Could not read file $fileName")
            return false
        }

        val parsedImport = parseInternalJson(content)
        if (parsedImport.status != ParseStatus.SUCCESS) {
            Log.e(TAG, "importIaruBandPlan: Could not parse file $fileName")
            return false
        }

        val bookmarkListId = stationRepository.insertBookmarkList(BookmarkList(
            id = 0,
            name = "Amateur Radio Bands",
            type = BookmarkListType.BAND,
            notes = "Band Plans from the IARU (Region $region)",
            color = 0xFF26A69A.toInt(), // teal
        ))
        val (success, errorMsg) = processParsedData(parsedImport, ImportOption.IMPORT_INTO_SINGLE, null, bookmarkListId, "")
        if (success) return true
        else {
            Log.e(TAG, "importIaruBandPlan: Failed to import band plan (region $region): $errorMsg")
            return false
        }
    }

    // Import ISM, maritime, air bands
    suspend fun importISMBandPlan(): Boolean {
        val fileName = "BandPlans/ISM_maritime_air.json"
        val content = try {
            context.assets.open(fileName).use { it.readBytes() }
        } catch (e: Exception) {
            Log.e(TAG, "importIaruBandPlan: Exception reading file $fileName: ${e.message}", e)
            null
        }
        if (content == null) {
            Log.e(TAG, "importISMBandPlan: Could not read file $fileName")
            return false
        }

        val parsedImport = parseInternalJson(content)
        if (parsedImport.status != ParseStatus.SUCCESS) {
            Log.e(TAG, "importISMBandPlan: Could not parse file $fileName")
            return false
        }

        val bookmarkListId = stationRepository.insertBookmarkList(BookmarkList(
            id = 0,
            name = "ISM, Maritme, Air Bands",
            type = BookmarkListType.BAND,
            notes = "Global band allocations such as ISM, Maritime and Air Bands.",
            color = 0xFFAF4706.toInt(), // orange
        ))
        val (success, errorMsg) = processParsedData(parsedImport, ImportOption.IMPORT_INTO_SINGLE, null, bookmarkListId, "")
        if (success) return true
        else {
            Log.e(TAG, "importISMBandPlan: Failed to import band plan: $errorMsg")
            return false
        }
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

        val result = processParsedData(parsedImport, option, targetStationBookmarkList?.id, targetBandBookmarkList?.id, prefix)
        if (result.first) applyImportedFlexDecoders(parsedImport.flexDecoders)
        return result
    }

    private fun currentFlexDecoders(): List<FlexDecoder> =
        appStateRepository.decoderFlexDecoders.value.map { FlexDecoder.fromSpec(it) }

    /** Merges flex decoders carried by an import file into the current list. */
    private fun applyImportedFlexDecoders(decoders: List<FlexDecoder>) {
        if (decoders.isEmpty()) return
        val existing = appStateRepository.decoderFlexDecoders.value.toMutableList()
        var added = 0
        decoders.forEach { decoder ->
            val spec = decoder.toSpec()
            if (spec.isNotBlank() && spec !in existing) {
                existing.add(spec)
                added++
            }
        }
        if (added > 0) {
            appStateRepository.decoderFlexDecoders.set(existing)
            Log.i(TAG, "applyImportedFlexDecoders: imported $added flex decoder(s)")
        }
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
        if (parsedStations.isEmpty() && parsedBands.isEmpty()) {
            return Pair(true, "No stations or bands found to import.")
        }

        val legacyCatIdToNewCatId = mutableMapOf<Long, Long>()
        val defaultStationBookmarkList =
            if (option != ImportOption.IMPORT_INTO_SINGLE
                && parsedStations.any { station -> station.bookmarkListId == null || !parsedBookmarkLists.any { it.id == station.bookmarkListId && it.type == BookmarkListType.STATION } } // is there any station that does not have a valid bookmarkList set?
            ) {
                stationRepository.insertBookmarkList(BookmarkList(
                    id = 0,
                    name = if(option == ImportOption.IMPORT_WITH_PREFIX) "${prefix}_default" else "import_default", // TODO: better import name
                    type = BookmarkListType.STATION,
                ))
            } else null
        val defaultBandBookmarkList =
            if (option != ImportOption.IMPORT_INTO_SINGLE
                && parsedBands.any { band -> band.bookmarkListId == null || !parsedBookmarkLists.any { it.id == band.bookmarkListId && it.type == BookmarkListType.BAND} } // is there any band that does not have a valid bookmarkList set?
            ) {
                stationRepository.insertBookmarkList(BookmarkList(
                    id = 0,
                    name = if(option == ImportOption.IMPORT_WITH_PREFIX) "${prefix}_default" else "import_default", // TODO: better import name
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
                    newId ?: defaultStationBookmarkList
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
                    newId ?: defaultBandBookmarkList
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
            ParsedImport(
                ParseStatus.SUCCESS, ImportFormat.INTERNAL_JSON, data, file.metadata.fullBackup,
                flexDecoders = file.flexDecoders,
            )
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
                    // --- Read bookmarkLists ---
                    it.rawQuery("SELECT _id, bookmarkListName, description FROM bookmarkBookmarkLists", null).use { cursor ->
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
                    it.rawQuery("SELECT _id, name, comment, bookmarkListId, frequency, channelWidth, mode, squelch FROM bookmarks", null).use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(cursor.getColumnIndexOrThrow("name")) ?: "Unnamed"
                            val comment = cursor.getString(cursor.getColumnIndexOrThrow("comment"))
                            val catIdLegacy = cursor.getLong(cursor.getColumnIndexOrThrow("bookmarkListId"))
                            val freq = cursor.getLong(cursor.getColumnIndexOrThrow("frequency"))
                            val width = cursor.getInt(cursor.getColumnIndexOrThrow("channelWidth"))
                            val modeInt = cursor.getInt(cursor.getColumnIndexOrThrow("mode"))
                            val squelch = cursor.getDouble(cursor.getColumnIndexOrThrow("squelch"))
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
                                demodulationParameters = DemodulationParameters(squelch = DemodulationParameters.Squelch(enabled = true, thresholdDb = squelch.toFloat())),
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
