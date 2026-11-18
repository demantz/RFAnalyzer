package com.mantz_it.rfanalyzer.network

import android.util.Log
import com.mantz_it.rfanalyzer.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * <h1>RF Analyzer - KiwiSDR Public List Fetcher</h1>
 *
 * Module:      KiwiSdrPublicListFetcher.kt
 * Description: Fetches the public KiwiSDR receiver directory from kiwisdr.com/public/.
 *
 * The page has no JSON API. A first GET returns a "tap to view" stub whose script unlocks the
 * list with one GET carrying an `x-kiwi-auth` header (the token is in the stub), as a browser
 * does when the user taps; the next GET returns the list: one `<div class='cl-entry ...'>` per
 * receiver with an `<a href>` (host and port) and `<!-- key=value -->` comments holding the
 * other fields, read with regexes.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object KiwiSdrPublicListFetcher {
    private const val TAG = "KiwiSdrPublicListFetcher"
    private const val PUBLIC_LIST_URL = "http://kiwisdr.com/public/"
    private const val PROXY_DEFAULT_PORT = 80 // kiwisdr.com's reverse-proxied hosts publish no explicit port
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private val USER_AGENT = "RF Analyzer ${BuildConfig.VERSION_NAME}"

    private val AUTH_TOKEN_REGEX = Regex("""x-kiwi-auth['"]\s*,\s*['"]([0-9a-fA-F]+)['"]""")
    private val ENTRY_START_REGEX = Regex("""<div[^>]+class=['"]cl-entry""")
    private val HREF_REGEX = Regex("""href=['"]https?://([^:'"/]+)(?::(\d+))?['"]""")
    private val COMMENT_BLOCK_REGEX = Regex("""<!--(.*?)-->""", RegexOption.DOT_MATCHES_ALL)
    private val KV_LINE_REGEX = Regex("""^\s*(\w+)=(.*?)\s*$""", RegexOption.MULTILINE)
    private val GPS_REGEX = Regex("""\(\s*([-\d.]+)\s*,\s*([-\d.]+)\s*\)""")

    suspend fun fetchPublicReceivers(): List<KiwiSdrPublicEntry> = withContext(Dispatchers.IO) {
        // 1) the stub page, with the x-kiwi-auth token
        val stubHtml = httpGet(PUBLIC_LIST_URL)
        val token = AUTH_TOKEN_REGEX.find(stubHtml)?.groupValues?.get(1)
            ?: throw IllegalStateException("KiwiSDR public list: could not find x-kiwi-auth token (site format may have changed)")

        // 2) the GET with the token unlocks the list (its body is not used)
        httpGet(PUBLIC_LIST_URL, extraHeader = "x-kiwi-auth" to token)

        // 3) the receiver list
        val listHtml = httpGet(PUBLIC_LIST_URL)
        parseEntries(listHtml)
    }

    // internal for the parser test (a saved HTML fixture)
    internal fun parseEntries(html: String): List<KiwiSdrPublicEntry> {
        val blocks = html.split(ENTRY_START_REGEX).drop(1) // part before the first entry is page boilerplate
        return blocks.mapNotNull { parseEntry(it) }
    }

    private fun parseEntry(block: String): KiwiSdrPublicEntry? {
        val hrefMatch = HREF_REGEX.find(block) ?: return null
        val host = hrefMatch.groupValues[1]
        val port = hrefMatch.groupValues[2].toIntOrNull() ?: PROXY_DEFAULT_PORT

        val kv = buildMap {
            COMMENT_BLOCK_REGEX.findAll(block).forEach { comment ->
                KV_LINE_REGEX.findAll(comment.groupValues[1]).forEach { line ->
                    put(line.groupValues[1], line.groupValues[2])
                }
            }
        }

        val gpsMatch = kv["gps"]?.let { GPS_REGEX.find(it) }
        var lat = gpsMatch?.groupValues?.get(1)?.toDoubleOrNull()
        var lon = gpsMatch?.groupValues?.get(2)?.toDoubleOrNull()
        if (lat != null && lon != null && kotlin.math.abs(lat) < 0.001 && kotlin.math.abs(lon) < 0.001) {
            // (0.00, 0.00) is the site's placeholder for "no GPS fix yet", not a real location
            lat = null
            lon = null
        }
        val snrParts = kv["snr"]?.split(",")

        // `offline=`/`status=` can say "no"/"active" for receivers the site marks as failing (e.g.
        // unreachable) with the 'cl-err-entry' class of the entry's <div>, before any <!-- -->
        // comment - that class counts as offline too.
        val isErrorEntry = block.substringBefore("<!--").contains("cl-err-entry")

        return KiwiSdrPublicEntry(
            id = kv["id"]?.takeIf { it.isNotBlank() } ?: "$host:$port",
            name = kv["name"]?.takeIf { it.isNotBlank() } ?: host,
            host = host,
            port = port,
            lat = lat,
            lon = lon,
            grid = kv["grid"]?.takeIf { it.isNotBlank() },
            loc = kv["loc"]?.takeIf { it.isNotBlank() },
            usersCurrent = kv["users"]?.toIntOrNull() ?: 0,
            usersMax = kv["users_max"]?.toIntOrNull() ?: 0,
            offline = isErrorEntry || kv["offline"] == "yes" || (kv["status"] != null && kv["status"] != "active"),
            swVersion = kv["sw_version"]?.takeIf { it.isNotBlank() },
            antenna = kv["antenna"]?.takeIf { it.isNotBlank() },
            snrAll = snrParts?.getOrNull(0)?.toDoubleOrNull(),
            snrHf = snrParts?.getOrNull(1)?.toDoubleOrNull(),
            bands = kv["bands"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun httpGet(urlString: String, extraHeader: Pair<String, String>? = null): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.setRequestProperty("User-Agent", USER_AGENT)
        extraHeader?.let { (key, value) -> connection.setRequestProperty(key, value) }
        return try {
            connection.connect()
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }
}
