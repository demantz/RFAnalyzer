package com.mantz_it.rfanalyzer.network

import android.util.Log
import com.mantz_it.rfanalyzer.database.COUNTRY_CODE_TO_NAME
import com.mantz_it.rfanalyzer.database.Coordinates
import com.mantz_it.rfanalyzer.database.SourceProvider
import com.mantz_it.rfanalyzer.database.Station
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.sha256
import java.time.Instant
import javax.net.ssl.HttpsURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * <h1>RF Analyzer - PotaStationFetcher</h1>
 *
 * Module:      PotaStationFetcher.kt
 * Description: Fetch stations (bookmarks) from Pota spots
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

object PotaStationFetcher {
    private const val TAG = "PotaStationFetcher"
    /** Connect and read timeout, so a stalled server can't hold the update (and block every
     *  retry as "already running"). */
    private const val TIMEOUT_MS = 15_000

    suspend fun fetchStations(url: String, oldStations: List<Station>): List<Station> = withContext(Dispatchers.IO) {
        Log.d(TAG, "fetchStation: Fetching $url")
        val url = URL(url)
        val connection = url.openConnection() as HttpsURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = "GET"
        val data = connection.inputStream.bufferedReader().readText()
        val jsonArray = JSONArray(data)
        val now = System.currentTimeMillis()
        Log.d(TAG, "fetchStations: Received ${jsonArray.length()} objects.")

        (0 until jsonArray.length()).mapNotNull { i ->
            val obj = jsonArray.getJSONObject(i)
            val spotId = obj.optInt("spotId", 0)
            val uniqueHash = sha256("POTA_$spotId")
            val oldStation = oldStations.find { it.remoteHash == uniqueHash }
            val spotFreq = obj.optDouble("frequency", 0.0) * 1000.0
            val name = obj.optString("activator", "Unknown")
            val parkId = obj.optString("reference", "")
            val countryCode = parkId.substringBefore("-")
            val parkName = obj.optString("name", "-")
            val (mode, freq) = spotTuning(obj.optString("mode"), spotFreq)
            val coordinates = Coordinates(obj.optDouble("latitude"), obj.optDouble("longitude"))
            val gridlocator = obj.optString("grid6")
            val comments = obj.optString("comments")
            if (freq > 0) Station(
                name = "$name (Park: $parkId)",
                callsign = name,
                bookmarkListId = null,
                frequency = freq.toLong(),
                bandwidth = mode.defaultChannelWidth,
                createdAt = now,
                updatedAt = now,
                favorite = oldStation?.favorite ?: false,
                mode = mode,
                notes = comments,
                coordinates = coordinates,
                countryCode = if(countryCode.length == 2) countryCode else null,
                countryName = COUNTRY_CODE_TO_NAME[countryCode],
                address = parkName,
                spottime = parsePotaTimestamp(obj.optString("spotTime")).toEpochMilli(),
                remoteHash = uniqueHash,
                source = SourceProvider.POTA
            ) else null
        }
    }

    private fun parsePotaTimestamp(timestamp: String): Instant {
        val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val localDateTime = LocalDateTime.parse(timestamp, formatter)
        return localDateTime.atZone(ZoneOffset.UTC).toInstant()
    }
}

/**
 * The mode and frequency to tune a POTA/SOTA spot with. The digital modes RF Analyzer demodulates
 * get that mode (with Auto parameters); other digital modes (FT4, JS8, "DATA", ...) are sent in USB,
 * so they get USB; an unknown mode keeps the current one (OFF). An FT8 spot is moved to its band's
 * dial frequency when it lies in the 3 kHz above it: the FT8 channel starts at the dial, and spots
 * often give the signal's own frequency.
 */
internal fun spotTuning(spotMode: String, frequencyHz: Double): Pair<DemodulationMode, Double> = when (spotMode.uppercase()) {
    "CW" -> DemodulationMode.CW to frequencyHz
    "FM" -> DemodulationMode.NFM to frequencyHz
    "AM" -> DemodulationMode.AM to frequencyHz
    "SSB" -> ssbModeForFrequency(frequencyHz) to frequencyHz
    "FT8" -> DemodulationMode.FT8 to (FT8_DIAL_FREQUENCIES_HZ.firstOrNull { frequencyHz - it in 0.0..3000.0 } ?: frequencyHz)
    "RTTY" -> DemodulationMode.RTTY to frequencyHz
    "PSK", "PSK31", "BPSK31", "PSK63", "PSK125" -> DemodulationMode.PSK31 to frequencyHz
    "FT4", "JS8", "DATA", "DIGI", "DIGITAL", "OLIVIA", "MFSK", "SSTV", "VARA", "WSPR", "Q65", "JT65", "JT9", "FST4" ->
        DemodulationMode.USB to frequencyHz
    else -> DemodulationMode.OFF to frequencyHz
}

/** FT8 dial frequencies of the amateur bands (the convention WSJT-X uses). */
private val FT8_DIAL_FREQUENCIES_HZ = listOf(
    1_840_000.0, 3_573_000.0, 5_357_000.0, 7_074_000.0, 10_136_000.0, 14_074_000.0, 18_100_000.0,
    21_074_000.0, 24_915_000.0, 28_074_000.0, 50_313_000.0, 144_174_000.0,
)

/** Amateur SSB convention for spots that only say "SSB": LSB below 10 MHz, USB above - and USB on
 *  60 m (5.3 MHz), where it is the rule too. */
internal fun ssbModeForFrequency(frequencyHz: Double): DemodulationMode =
    if (frequencyHz < 10_000_000.0 && frequencyHz !in 5_250_000.0..5_450_000.0) DemodulationMode.LSB
    else DemodulationMode.USB
