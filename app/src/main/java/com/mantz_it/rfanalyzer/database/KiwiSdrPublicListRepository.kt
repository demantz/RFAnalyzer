package com.mantz_it.rfanalyzer.database

import android.util.Log
import com.mantz_it.rfanalyzer.network.KiwiSdrPublicEntry
import com.mantz_it.rfanalyzer.network.KiwiSdrPublicListFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * <h1>RF Analyzer - KiwiSDR Public List Repository</h1>
 *
 * Module:      KiwiSdrPublicListRepository.kt
 * Description: Cache-aware access to the public KiwiSDR receiver directory. The parsed list is kept
 *              as JSON in AppStateRepository's settings and re-fetched from the network once it is
 *              older than [CACHE_TTL_MS].
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Singleton
class KiwiSdrPublicListRepository @Inject constructor(
    private val appStateRepository: AppStateRepository
) {
    companion object {
        private const val TAG = "KiwiSdrPublicListRepository"
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
    }

    private val entrySerializer = ListSerializer(KiwiSdrPublicEntry.serializer())

    /**
     * Returns the cached list if it is younger than [CACHE_TTL_MS] and [forceRefresh] is false;
     * otherwise fetches fresh data from kiwisdr.com/public/ and updates the cache. Falls back to
     * a stale cache on fetch/parse failure if one is available, so a transient network error
     * doesn't blank an already-populated dialog.
     */
    suspend fun getList(forceRefresh: Boolean = false): Result<List<KiwiSdrPublicEntry>> = withContext(Dispatchers.IO) {
        val cachedJson = appStateRepository.kiwiSdrPublicListCacheJson.value
        val cacheAgeMs = System.currentTimeMillis() - appStateRepository.kiwiSdrPublicListCacheTimestamp.value
        val cacheIsFresh = cachedJson.isNotEmpty() && cacheAgeMs < CACHE_TTL_MS

        if (!forceRefresh && cacheIsFresh) {
            decodeCache(cachedJson)?.let { return@withContext Result.success(it) }
            // cached JSON failed to decode (e.g. format changed) - fall through to a network fetch
        }

        runCatching { KiwiSdrPublicListFetcher.fetchPublicReceivers() }
            .onSuccess { entries -> storeCache(entries) }
            .recoverCatching { error ->
                Log.w(TAG, "getList: fetch failed, falling back to cache if present", error)
                decodeCache(cachedJson) ?: throw error
            }
    }

    private fun decodeCache(cachedJson: String): List<KiwiSdrPublicEntry>? =
        if (cachedJson.isEmpty()) null else runCatching { Json.decodeFromString(entrySerializer, cachedJson) }.getOrNull()

    private fun storeCache(entries: List<KiwiSdrPublicEntry>) {
        appStateRepository.kiwiSdrPublicListCacheJson.set(Json.encodeToString(entrySerializer, entries))
        appStateRepository.kiwiSdrPublicListCacheTimestamp.set(System.currentTimeMillis())
    }
}
