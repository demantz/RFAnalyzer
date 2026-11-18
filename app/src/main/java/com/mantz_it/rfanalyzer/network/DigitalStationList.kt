package com.mantz_it.rfanalyzer.network

import android.util.Log
import com.mantz_it.rfanalyzer.database.Coordinates
import com.mantz_it.rfanalyzer.database.DemodulationParameters
import com.mantz_it.rfanalyzer.database.SourceProvider
import com.mantz_it.rfanalyzer.database.Station
import com.mantz_it.rfanalyzer.location.UserLocation
import com.mantz_it.rfanalyzer.location.ituRegionOf
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * <h1>RF Analyzer - Digital Station List</h1>
 *
 * Module:      DigitalStationList.kt
 * Description: The curated presets of the digital modes (FT8 bands, APRS, LoRa networks, POCSAG,
 *              R09 cities, ...) from the rfanalyzer_lists repository - one world-wide file, built
 *              and published there by a GitHub Action (its README describes the format). The app
 *              ships a copy (assets/lists) and downloads the published one; the newer [version]
 *              wins. Only the entries for the user's area become station rows (source
 *              DIGITAL_LIST): read-only like the other online lists, so a newer list replaces them
 *              without touching the user's own bookmarks; favorites stay by the entry's id.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@Serializable
data class StationListFile(
    val format: Int,
    val version: Long,
    val stations: List<StationListEntry>,
)

@Serializable
data class StationListEntry(
    val id: String,
    val name: String,
    val mode: String,
    val frequency: Long,
    val bandwidth: Int,
    /** ITU regions it is for; empty and no [countries] = everyone. */
    val regions: List<Int> = emptyList(),
    val countries: List<String> = emptyList(),
    val location: ListLocation? = null,
    /** A DemodulationParameters object without its version. */
    val params: JsonObject? = null,
    val notes: String? = null,
)

@Serializable
data class ListLocation(val lat: Double, val lon: Double)

object DigitalStationList {
    private const val TAG = "DigitalStationList"
    /** The bundled copy, built by rfanalyzer_lists' tools/build.py. */
    const val ASSET_PATH = "lists/digital_stations.json"
    /** The newest list format this build reads - a list in a newer one is ignored (app too old). */
    const val SUPPORTED_FORMAT = 1

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    /** The list in [bytes], or null if it can't be read or is of a newer format. */
    fun parse(bytes: ByteArray): StationListFile? = decode(bytes)?.takeIf { it.format <= SUPPORTED_FORMAT }

    private fun decode(bytes: ByteArray): StationListFile? = try {
        json.decodeFromString(StationListFile.serializer(), bytes.decodeToString())
    } catch (e: Exception) {
        Log.w(TAG, "parse: not a station list: ${e.message}")
        null
    }

    /**
     * A downloaded list: null if it is in a newer format - this app is too old for it and keeps the
     * list it has, which is no error (an update of the format must not make older apps report one
     * every time they check). Throws if the download is no station list at all.
     */
    fun fromDownload(bytes: ByteArray): StationListFile? {
        val list = decode(bytes) ?: throw IOException("Not a station list")
        if (list.format > SUPPORTED_FORMAT) {
            Log.i(TAG, "fromDownload: format ${list.format} is newer than $SUPPORTED_FORMAT - keeping the current list")
            return null
        }
        return list
    }

    /** Downloads the published list; throws on a network error or if it is no station list, null for a newer format. */
    suspend fun download(url: String): Pair<StationListFile, ByteArray>? = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpsURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode != HttpsURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode} from ${URL(url).host}")
            val bytes = connection.inputStream.use { it.readBytes() }
            fromDownload(bytes)?.let { it to bytes }
        } finally {
            connection.disconnect()
        }
    }

    /** "R1:DE" - what decides which entries apply; the rows are rewritten when it changes. */
    fun areaKey(location: UserLocation): String = "R${location.ituRegion?.number ?: 0}:${location.countryCode.orEmpty()}"

    /**
     * Whether [entry] is for a user at [location]: one for everyone always; one for countries if the
     * user's country is among them - or, if the user gave only a region, one of them lies in it; one
     * for regions if the user's region is among them. Without any location only the entries for
     * everyone apply.
     */
    fun appliesTo(entry: StationListEntry, location: UserLocation): Boolean {
        val region = location.ituRegion
        return when {
            entry.countries.isNotEmpty() -> location.countryCode?.let { it in entry.countries }
                ?: (region != null && entry.countries.any { ituRegionOf(it) == region })
            entry.regions.isNotEmpty() -> region != null && region.number in entry.regions
            else -> true
        }
    }

    /** The rows for [location]; favorites are carried over from [oldStations] by id. Entries of a
     *  mode this build doesn't know are left out. */
    fun toStations(list: StationListFile, location: UserLocation, oldStations: List<Station>, nowMs: Long): List<Station> {
        val favorites = oldStations.filter { it.favorite }.mapNotNull { it.remoteHash }.toSet()
        return list.stations.filter { appliesTo(it, location) }.mapNotNull { entry ->
            val mode = DemodulationMode.entries.firstOrNull { it.name == entry.mode } ?: return@mapNotNull null
            Station(
                remoteHash = REMOTE_HASH_PREFIX + entry.id,
                name = entry.name,
                frequency = entry.frequency,
                bandwidth = entry.bandwidth,
                createdAt = nowMs,
                updatedAt = nowMs,
                mode = mode,
                demodulationParameters = entry.params?.let { params ->
                    runCatching { json.decodeFromJsonElement(DemodulationParameters.serializer(), params) }
                        .onFailure { Log.w(TAG, "toStations: ${entry.id}: params not readable: ${it.message}") }
                        .getOrNull()
                },
                notes = entry.notes,
                favorite = REMOTE_HASH_PREFIX + entry.id in favorites,
                countryCode = entry.countries.singleOrNull(),
                coordinates = entry.location?.let { Coordinates(it.lat, it.lon) },
                source = SourceProvider.DIGITAL_LIST,
            )
        }
    }

    private const val REMOTE_HASH_PREFIX = "rfa:"
}
