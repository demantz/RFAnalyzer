package com.mantz_it.rfanalyzer.network

import android.content.Context
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

/**
 * <h1>RF Analyzer - SotaStationFetcher</h1>
 *
 * Module:      SotaStationFetcher.kt
 * Description: Fetch stations (bookmarks) from Sota spots
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

object SotaStationFetcher {
    private const val TAG = "SotaStationFetcher"
    /** Connect and read timeout, so a stalled server can't hold the update (and block every
     *  retry as "already running"). */
    private const val TIMEOUT_MS = 15_000

    private var _summitMap: Map<String, Triple<Double, Double, String>>? = null   // summitId -> (lat, lon, countrycode)
    private suspend fun loadSummitMapFromAssets(context: Context) {
        if (_summitMap != null) return
        Log.d(TAG, "loadFromAssets: Loading summit map.")
        val newMap: MutableMap<String, Triple<Double, Double, String>> = mutableMapOf()
        withContext(Dispatchers.IO) {
            context.assets.open("summits.csv").bufferedReader().useLines { lines ->
                lines.drop(1).forEach { line -> // without the header line
                    val cols = line.split(',')
                    val summitCode = cols[0]
                    val lon = cols[1].toDouble()
                    val lat = cols[2].toDouble()
                    val country = cols[3]
                    newMap[summitCode] = Triple(lat, lon, country)
                }
            }
        }
        _summitMap = newMap
    }

    suspend fun fetchStations(context: Context, url: String, oldStations: List<Station>): List<Station> = withContext(Dispatchers.IO) {
        try {
            if (_summitMap == null) loadSummitMapFromAssets(context)
        } catch (e: Exception) {
            Log.e(TAG, "fetchStations: Error loading Summit Map: ${e.message}", e)
            return@withContext emptyList<Station>()
        }
        if (_summitMap == null) {
            Log.e(TAG, "fetchStations: Error loading Summit Map!")
            return@withContext emptyList()
        }
        val summitMap = _summitMap ?: return@withContext emptyList()
        Log.d(TAG, "fetchStations: Fetching $url")
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
            val spotId = obj.optInt("id", 0)
            val uniqueHash = sha256("POTA_$spotId")
            val oldStation = oldStations.find { it.remoteHash == uniqueHash }
            val freq = obj.optDouble("frequency", 0.0) * 1_000_000.0
            val name = obj.optString("activatorCallsign", "Unknown")
            val activatorName = obj.optString("activatorName", "-")
            val summitId = obj.optString("summitCode", "")
            val countryCode = summitMap[summitId]?.third
            val summitName = obj.optString("summitName", "-")
            val altitude = obj.optInt("AltM", 0)
            val (mode, tunedFreq) = spotTuning(obj.optString("mode"), freq)
            val summitMapEntry = summitMap[summitId]
            val coordinates = if(summitMapEntry != null) {
                val (summitLat, summitLon, summitCountryCode) = summitMapEntry
                Coordinates(summitLat, summitLon)
            } else null
            val comments = obj.optString("comments")
            if (freq > 0) Station(
                name = "$name (Summit: $summitId)",
                callsign = name,
                bookmarkListId = null,
                frequency = tunedFreq.toLong(),
                bandwidth = mode.defaultChannelWidth,
                createdAt = now,
                updatedAt = now,
                favorite = oldStation?.favorite ?: false,
                mode = mode,
                notes = "$activatorName on $summitName (${altitude}m); Comments: $comments",
                coordinates = coordinates,
                countryCode = if(countryCode?.length == 2) countryCode else null,
                countryName = COUNTRY_CODE_TO_NAME[countryCode],
                address = summitName,
                spottime = Instant.parse(obj.optString("timeStamp")).toEpochMilli(),
                remoteHash = uniqueHash,
                source = SourceProvider.SOTA
            ) else null
        }
    }
}

// The Python script that builds summits.csv (assets/summits.csv.gz: summit code, lon, lat, ISO country):
/*
#!/usr/bin/env python3

# Dennis Mantz

import geopandas as gpd
import pandas as pd
from shapely.geometry import Point
import pycountry

# https://www.naturalearthdata.com/http//www.naturalearthdata.com/download/10m/cultural/ne_10m_admin_0_countries.zip
# https://www.sotadata.org.uk/summitslist.csv
# cat summitslist.csv | cut -d',' -f 1,9,10 > summits_no_iso.csv

# ---------- CONFIG ----------
SUMMITS_CSV = "summits_no_iso.csv"
COUNTRIES_SHP = "ne_10m_admin_0_countries.shp"
OUTPUT_CSV = "summits.csv"
# ----------------------------


def iso3_to_iso2(iso3: str | None) -> str | None:
    if not iso3 or iso3 == "-99":
        return None
    country = pycountry.countries.get(alpha_3=iso3)
    if country == None:
        country = pycountry.countries.get(alpha_2=iso3[:2])
    return country.alpha_2 if country else None


def main():
    print("Loading country polygons...")
    countries = gpd.read_file(COUNTRIES_SHP)

    print("Loading summits CSV...")
    summits = pd.read_csv(SUMMITS_CSV, skiprows=1)

    iso_codes = []

    print("Processing summits...")
    for idx, row in summits.iterrows():
        lat = row["Latitude"]
        lon = row["Longitude"]
        summit_code = row["SummitCode"]

        point = Point(lon, lat)

        match = countries[countries.covers(point)]

        if match.empty:
            print(f"[WARN] No country found for {summit_code} ({lat}, {lon})")
            iso_codes.append(None)
            continue

        iso3 = match.iloc[0]["SOV_A3"]
        iso2 = iso3_to_iso2(iso3)

        if iso2 is None:
            print(f"[WARN] Could not convert ISO3 '{iso3}' for {summit_code}")
            iso_codes.append(None)
        else:
            iso_codes.append(iso2)

    summits["isoCountryCode"] = iso_codes

    print("Writing output CSV...")
    summits.to_csv(OUTPUT_CSV, index=False)

    print("Done.")


if __name__ == "__main__":
    main()
*/