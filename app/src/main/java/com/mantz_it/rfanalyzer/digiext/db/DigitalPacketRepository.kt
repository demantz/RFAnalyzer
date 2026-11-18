package com.mantz_it.rfanalyzer.digiext.db

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.digiext.specOf
import com.mantz_it.rfanalyzer.digiext.toExportJson
import com.mantz_it.rfanalyzer.digiext.digitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/**
 * <h1>RF Analyzer - DigitalPacketRepository</h1>
 *
 * Module:      DigitalPacketRepository.kt
 * Description: Every read of `digital_packets` - the Raw subtab's paging, an expanded Overview
 *              row's detail list, counts and export - decoded to [StoredPacket]s by each mode's
 *              [PacketSpec]. Writing is [DigitalStoreWriter]'s job; tracked objects are
 *              `DigitalExtensionRepository`'s.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** How many stored packets an expanded tracked row reads. Well past what fits on a phone screen,
 *  and one index range scan either way; the details page reads on in steps of
 *  [DETAILS_PAGE_SIZE], the count says how many there are in all. */
const val DETAIL_LIMIT = 50

/** How many more packets the details page reads each time "Show more" is tapped. */
const val DETAILS_PAGE_SIZE = 200

/** ADS-B's expanded row groups frames by payload type, so it needs a deeper window to have
 *  something in each group. Not yet checked against a busy aircraft. */
const val ADSB_DETAIL_LIMIT = 200

/** How many finished K2K (RTTY/PSK31) transmissions the Digital tab's table shows. */
const val K2K_LIST_LIMIT = 500

private const val EXPORT_PAGE_SIZE = 500

/**
 * The search text of a stored row: every text leaf of [json] (a packet's `payloadJson` or a tracked
 * object's `stateJson`), plus [objectKey] and [extraTerms].
 *
 * One generic walk of the stored JSON instead of a field list per mode, so it covers every mode
 * and every field added later.
 *
 * What a user reads in the UI is searchable, bookkeeping is not:
 * - Text, always.
 * - Numbers up to [MAX_SEARCHABLE_NUMBER_LENGTH] characters: an altitude, a Meldepunkt, a signal
 *   report, an RDS alternative frequency. What is longer is not shown as stored - timestamps and
 *   ids, frequencies in Hz (shown in MHz, and the frequency filter's job), floats with computed
 *   digits (a heading, a position), identities stored in decimal but shown in hex - and its digits
 *   would only make short terms match everywhere.
 * - No booleans, and no byte arrays ([com.mantz_it.rfanalyzer_ipc.digiext.Bytes], stored as JSON
 *   arrays of integers). Where bytes are an identity (a public key, a path hash) the caller passes
 *   them as hex in [extraTerms]; [objectKey] already carries the primary one.
 */
internal fun genericSearchText(
    objectKey: String?,
    json: String,
    format: Json = storageJson,
    extraTerms: List<String> = emptyList(),
): String {
    val leaves = StringBuilder(objectKey.orEmpty())
    fun walk(element: JsonElement) {
        when (element) {
            is JsonObject -> element.values.forEach { walk(it) }
            is JsonArray -> if (!element.isRawBytes()) element.forEach { walk(it) }
            JsonNull -> {} // must precede `is JsonPrimitive` - JsonNull is itself a JsonPrimitive subtype
            is JsonPrimitive -> {
                val searchable = if (element.isString) element.content.isNotBlank()
                else element.booleanOrNull == null && element.content.length <= MAX_SEARCHABLE_NUMBER_LENGTH
                if (searchable) { leaves.append(' '); leaves.append(element.content) }
            }
        }
    }
    walk(format.parseToJsonElement(json))
    extraTerms.forEach { if (it.isNotBlank()) { leaves.append(' '); leaves.append(it) } }
    // SQLite's LIKE reads the text as a C string: nothing after a NUL would ever match, and
    // decoded payloads do carry them (POCSAG pads a message with NULs).
    return leaves.toString().replace("\u0000", "").trim()
}

/** Seven characters still hold every capcode (up to 2097151); timestamps and Hz frequencies have more. */
private const val MAX_SEARCHABLE_NUMBER_LENGTH = 7

/** Whether this array is a serialized byte array: non-empty and integers throughout. Stored JSON
 *  holds no other integer arrays - see [com.mantz_it.rfanalyzer.digiext.hexBytes]. */
internal fun JsonArray.isRawBytes(): Boolean =
    isNotEmpty() && all { it is JsonPrimitive && it !is JsonNull && !it.isString && it.longOrNull != null }

@Singleton
class DigitalPacketRepository @Inject constructor(
    private val dao: DigitalPacketDao,
    private val appStateRepository: AppStateRepository,
) {
    /** The mode the app is decoding right now, or null for an analog one - see [digitalMode]. */
    fun currentPacketMode(): DigitalMode? =
        appStateRepository.demodulationMode.value.digitalMode(appStateRepository.demodulationWfmStereoAndRdsEnabled.value)

    /** The frequency a packet is stored with: its own, or the tuned one when it carries none. */
    fun frequencyOrChannel(frequencyHz: Long): Long =
        frequencyHz.takeIf { it != 0L } ?: appStateRepository.channelFrequency.value

    /** A stored row as its mode's packet. */
    internal fun decode(entity: PacketEntity): StoredPacket<*, *> = specOf(entity.mode).fromRow(entity)

    /** Whether rows of [spec]'s mode were written by an older parser - only true after an update. */
    internal suspend fun hasOutdated(spec: PacketSpec<*, *>): Boolean = dao.hasOutdated(spec.mode, spec.parserVersion)

    /** Rows of a re-parsable mode that its current parser might read differently: written by an
     *  older parser, or - [withoutIdentity] - still without a tracked identity. */
    internal suspend fun reparseCandidates(spec: PacketSpec<*, *>, withoutIdentity: Boolean, afterId: Long, limit: Int): List<PacketEntity> =
        if (withoutIdentity) dao.findWithoutIdentity(spec.mode, afterId, limit)
        else dao.findOutdated(spec.mode, spec.parserVersion, afterId, limit)

    // ------------------------------------------------------------------
    // Queries - every one a [PacketQuery].
    // ------------------------------------------------------------------

    /** The Raw subtab's list. Rows are decoded as pages load - on the scope the caller caches
     *  the flow in, which must not be the main thread's. */
    fun paging(query: PacketQuery, currentFirst: () -> LongRange? = { null }): Flow<PagingData<StoredPacket<*, *>>> = Pager(
        config = PagingConfig(pageSize = 100, initialLoadSize = 200, prefetchDistance = 50, enablePlaceholders = true),
        // Asked again for every new source, i.e. after every commit: a replay's window grows.
        pagingSourceFactory = { dao.paging(query.copy(currentFirst = currentFirst()).toSql()) },
    ).flow.map { page -> page.map(::decode) }

    /** The newest (or, per [PacketQuery.newestFirst], oldest) [limit] packets of [spec]'s mode
     *  matching [query], kept current and decoded off the main thread. Bounded rather than paged:
     *  it renders inside an expanded row. A row that fails to decode is skipped. */
    internal fun <Ph : Any, D : Any> observe(spec: PacketSpec<Ph, D>, query: PacketQuery, limit: Int = DETAIL_LIMIT): Flow<List<StoredPacket<Ph, D>>> =
        dao.observe(query.copy(mode = spec.mode).toSql(limit))
            .map { rows -> rows.mapNotNull { row -> runCatching { spec.fromRow(row) }.getOrNull() } }
            .flowOn(Dispatchers.Default)

    /** The packets of one tracked object, newest first - the detail behind an expanded row. With
     *  [bidirectional] also those naming it as the other party ([PacketEntity.secondaryObjectKey]). */
    internal fun <Ph : Any, D : Any> detail(
        spec: PacketSpec<Ph, D>, objectKey: String, limit: Int = DETAIL_LIMIT, bidirectional: Boolean = false,
    ): Flow<List<StoredPacket<Ph, D>>> =
        observe(spec, detailQuery(spec, objectKey, bidirectional), limit)

    /** The newest packet of [spec]'s mode matching [query], or null. */
    internal suspend fun <Ph : Any, D : Any> newest(spec: PacketSpec<Ph, D>, query: PacketQuery): StoredPacket<Ph, D>? =
        dao.query(query.copy(mode = spec.mode).toSql(1)).firstOrNull()?.let { row -> runCatching { spec.fromRow(row) }.getOrNull() }

    /** How many packets [detail] would list without a limit, kept current. */
    internal fun detailCount(spec: PacketSpec<*, *>, objectKey: String, bidirectional: Boolean = false): Flow<Int> =
        dao.observeCount(detailQuery(spec, objectKey, bidirectional).toCountSql())

    /** The query behind [detail] and [detailCount] - also what "Share messages" exports. */
    internal fun detailQuery(spec: PacketSpec<*, *>, objectKey: String, bidirectional: Boolean = false) =
        PacketQuery(spec.mode, objectKey = objectKey, bidirectional = bidirectional)

    /**
     * Every packet matching [queries], query after query, in the export envelope as one JSON array.
     * Written to [out] a page at a time: a mode's whole retention budget is hundreds of megabytes
     * as JSON and must never be held in memory at once.
     */
    suspend fun exportJson(queries: List<PacketQuery>, out: Appendable) {
        out.append('[')
        var empty = true
        for (query in queries) {
            var last: PacketEntity? = null
            while (true) {
                val page = dao.query(query.toPageSql(EXPORT_PAGE_SIZE, after = last))
                if (page.isEmpty()) break
                for (row in page) {
                    out.append(if (empty) "\n" else ",\n").append(decode(row).toExportJson().prependIndent("    "))
                    empty = false
                }
                last = page.last()
            }
        }
        out.append(if (empty) "]" else "\n]")
    }

    suspend fun count(query: PacketQuery): Int = dao.count(query.toCountSql())

    suspend fun getCountForMode(mode: DigitalMode): Int = dao.getCountForMode(mode)

    suspend fun getTotalCount(): Int = dao.getTotalCount()

    /** Stored packets per mode and when the newest was received; a mode without any is absent. */
    suspend fun getCountsByMode(): Map<DigitalMode, StoredModeCount> =
        dao.getCountsByMode().mapNotNull { row -> DigitalMode.entries.firstOrNull { it.name == row.mode }?.let { it to StoredModeCount(row.count, row.lastMs) } }.toMap()
}

/** What the mode sheet says about a mode's stored data. */
data class StoredModeCount(val count: Int, val lastMs: Long)
