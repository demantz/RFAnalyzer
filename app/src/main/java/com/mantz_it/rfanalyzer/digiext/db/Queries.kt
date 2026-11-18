package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import androidx.room.RoomRawQuery

/**
 * <h1>RF Analyzer - Digital Extension Queries</h1>
 *
 * Module:      Queries.kt
 * Description: The two read queries of the digital extension database - packets and tracked
 *              objects - and the one filter the Digital tab's subtabs all share. Every filtered
 *              read is one of these two, with its SQL built here and nowhere else.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * The Digital tab's filter, shared by the Overview, Raw and Map subtabs.
 *
 * Strictly single-mode: [mode] is one [DigitalMode] or null (nothing selected yet - every subtab
 * shows a placeholder). A table of capcodes and a table of aircraft share no columns, so there is
 * no "all modes" view.
 *
 * [mode] is the mode being *shown*, picked with the header's mode button. It follows the
 * demodulator: `MainViewModel` overwrites it whenever the tuned mode actually changes to a digital
 * one, whatever was picked in between (an analog mode leaves it as it is). Showing another mode's
 * stored data is therefore a one-shot override, and "is stored data being shown" is just
 * `mode != currentMode` - there is no separate flag.
 *
 * Every mode lists everything stored; the time range is what narrows it by time.
 *
 * The time range is either rolling - [lastMs], "the last hour", resolved against the clock each
 * time a query is built - or absolute, [minTime]/[maxTime]; setting one clears the other.
 *
 * Times are epoch millis, durations millis and frequencies Hz; 0 means unbounded.
 */
data class DigitalFilter(
    val mode: DigitalMode? = null,
    val lastMs: Long = 0L,
    val minTime: Long = 0L,
    val maxTime: Long = 0L,
    val minFrequency: Long = 0L,
    val maxFrequency: Long = 0L,
    val search: String = "",
    val newestFirst: Boolean = true,
) {
    fun hasTimeRange(): Boolean = lastMs != 0L || minTime != 0L || maxTime != 0L

    private fun since(nowMs: Long): Long = if (lastMs != 0L) nowMs - lastMs else minTime
    private fun until(): Long = if (lastMs != 0L) 0L else maxTime

    /** The raw-packet query for this filter, or null while no mode is selected. */
    fun toPacketQuery(nowMs: Long = System.currentTimeMillis()): PacketQuery? = mode?.let {
        PacketQuery(
            mode = it, minTime = since(nowMs), maxTime = until(),
            minFrequency = minFrequency, maxFrequency = maxFrequency,
            search = search, newestFirst = newestFirst,
        )
    }

    /** The tracked-object query for this filter, or null while no mode is selected. [nowMs]
     *  resolves a rolling time range. */
    fun toObjectQuery(nowMs: Long, kind: ObjectKind? = null, withPositionOnly: Boolean = false): ObjectQuery? {
        val mode = mode ?: return null
        return ObjectQuery(
            mode = mode, kind = kind,
            heardSinceMs = since(nowMs), heardUntilMs = until(),
            minFrequency = minFrequency, maxFrequency = maxFrequency,
            search = search, withPositionOnly = withPositionOnly,
        )
    }
}

/**
 * A read of `digital_packets`. With [objectKey] set it is one tracked object's packets; with
 * [bidirectional] also those that name it as the other party (FT8's called station, AX.25's
 * addressee - see [PacketEntity.secondaryObjectKey]).
 */
data class PacketQuery(
    val mode: DigitalMode,
    val objectKey: String? = null,
    val bidirectional: Boolean = false,
    val minTime: Long = 0L,
    val maxTime: Long = 0L,
    val minFrequency: Long = 0L,
    val maxFrequency: Long = 0L,
    val search: String = "",
    val newestFirst: Boolean = true,
    /** Newest first, the rows in this time range come before the others: a replayed recording's
     *  session while it plays, whose packets carry the recording's time (see SourceClock). */
    val currentFirst: LongRange? = null,
    /** Only rows whose raw bytes, as upper-case hex from hex digit [rawHexOffset] on, match this
     *  GLOB - a field inside the bytes, like an RDS group's type ("E[0-7]*" is 14A). */
    val rawHexGlob: String? = null,
    val rawHexOffset: Int = 0,
)

enum class ObjectSort(internal val column: String) {
    LAST_SEEN("lastSeenMs"),
    FIRST_SEEN("firstSeenMs"),
    PACKET_COUNT("packetCount"),
    NAME("displayName COLLATE NOCASE"),
}

/**
 * A read of `tracked_objects`.
 *
 * The time and frequency bounds select objects heard **at least once** inside them, however often
 * they were heard outside: an aircraft that passed yesterday and again today belongs to
 * yesterday's list too. Where the object's own first/last-seen columns cannot answer that, its
 * packets do (see [toSql]) - so a sighting whose packets retention has already evicted can no
 * longer be found, unless it was the object's last.
 */
data class ObjectQuery(
    val mode: DigitalMode,
    val kind: ObjectKind? = null,
    val heardSinceMs: Long = 0L,
    val heardUntilMs: Long = 0L,
    val minFrequency: Long = 0L,
    val maxFrequency: Long = 0L,
    val search: String = "",
    val withPositionOnly: Boolean = false,
    /** Only objects whose key starts with this - the TMC messages of one RDS station. */
    val keyPrefix: String? = null,
    val sort: ObjectSort = ObjectSort.LAST_SEEN,
    val ascending: Boolean = false,
)

private class Sql {
    private val where = ArrayList<String>()
    private val args = ArrayList<Any>()

    fun and(clause: String, vararg values: Any) {
        where += clause
        args.addAll(values)
    }

    fun and(clause: String, values: List<Any>) {
        where += clause
        args.addAll(values)
    }

    fun whereClause(): String = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")

    fun build(sql: String): RoomRawQuery {
        val bound = args.toList()
        return RoomRawQuery(sql) { statement ->
            bound.forEachIndexed { i, value ->
                when (value) {
                    is Long -> statement.bindLong(i + 1, value)
                    is String -> statement.bindText(i + 1, value)
                    else -> error("Unsupported bind type ${value::class}")
                }
            }
        }
    }
}

/** [term] as a `LIKE` pattern matching it anywhere, with `%`, `_` and the escape character itself
 *  taken literally. Pair with `ESCAPE '\'`. */
private fun likePattern(term: String): String = "%" + escapeLike(term) + "%"

private fun escapeLike(term: String): String =
    term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

private fun PacketQuery.predicate(): Sql = Sql().apply {
    and("mode = ?", mode.name)
    if (objectKey != null) {
        if (bidirectional) and("(objectKey = ? OR secondaryObjectKey = ?)", objectKey, objectKey)
        else and("objectKey = ?", objectKey)
    }
    if (minTime != 0L) and("timestampMs >= ?", minTime)
    if (maxTime != 0L) and("timestampMs <= ?", maxTime)
    if (minFrequency != 0L) and("frequencyHz >= ?", minFrequency)
    if (maxFrequency != 0L) and("frequencyHz <= ?", maxFrequency)
    if (rawHexGlob != null) and("substr(hex(raw), ?) GLOB ?", rawHexOffset + 1L, rawHexGlob)
    if (search.isNotEmpty()) {
        // A packet matches on its own text, or because the object it belongs to does: a callsign
        // is in an aircraft's identification frames only, but finds all of its frames this way,
        // the same aircraft the Overview lists for that search.
        //
        // A plain scan, on purpose: at the retention caps the first page takes under 200 ms, while
        // a trigram FTS index halved the insert rate and was slower for any common term.
        and(
            "(searchText LIKE ? ESCAPE '\\' OR objectKey IN " +
                "(SELECT objectKey FROM tracked_objects WHERE mode = ? AND searchText LIKE ? ESCAPE '\\'))",
            likePattern(search), mode.name, likePattern(search),
        )
    }
}

/** No `LIMIT` unless [limit] is given: paging wraps this as a subquery and appends its own. Rows
 *  of one timestamp are ordered by id - the (mode, timestampMs) index holds them in that order. */
internal fun PacketQuery.toSql(limit: Int? = null): RoomRawQuery {
    val predicate = predicate()
    val order = if (newestFirst) "DESC" else "ASC"
    val first = currentFirst?.takeIf { newestFirst }?.let { "(timestampMs BETWEEN ${it.first} AND ${it.last}) DESC, " } ?: ""
    return predicate.build(
        "SELECT * FROM digital_packets" + predicate.whereClause() +
            " ORDER BY $first" + "timestampMs $order, id $order" + (limit?.let { " LIMIT $it" } ?: "")
    )
}

/** The [limit] rows that follow [after] - the last row of the previous page, null for the first -
 *  in [toSql]'s order. Keyed on that row rather than an offset, so a deep page costs what the
 *  first one does. */
internal fun PacketQuery.toPageSql(limit: Int, after: PacketEntity?): RoomRawQuery {
    val predicate = predicate()
    val (order, beyond) = if (newestFirst) "DESC" to "<" else "ASC" to ">"
    if (after != null) {
        predicate.and("(timestampMs $beyond ? OR (timestampMs = ? AND id $beyond ?))", after.timestampMs, after.timestampMs, after.id)
    }
    return predicate.build("SELECT * FROM digital_packets" + predicate.whereClause() + " ORDER BY timestampMs $order, id $order LIMIT $limit")
}

internal fun PacketQuery.toCountSql(): RoomRawQuery {
    val predicate = predicate()
    return predicate.build("SELECT COUNT(*) FROM digital_packets" + predicate.whereClause())
}

private fun ObjectQuery.predicate(): Sql = Sql().apply {
    and("mode = ?", mode.name)
    if (kind != null) and("kind = ?", kind.name)
    // Necessary on their own, and all there is to it while only one of them is set.
    if (heardSinceMs != 0L) and("lastSeenMs >= ?", heardSinceMs)
    if (heardUntilMs != 0L) and("firstSeenMs <= ?", heardUntilMs)
    val hasFrequency = minFrequency != 0L || maxFrequency != 0L
    if (hasFrequency || (heardSinceMs != 0L && heardUntilMs != 0L)) {
        // The last sighting is known from the object's own row; any earlier one only from its
        // packets, found through the (mode, objectKey, timestampMs) index.
        val last = ArrayList<String>()
        val packet = arrayListOf("p.mode = tracked_objects.mode", "p.objectKey = tracked_objects.objectKey")
        val lastArgs = ArrayList<Any>()
        val packetArgs = ArrayList<Any>()
        fun bound(lastColumn: String, packetColumn: String, op: String, value: Long, onLast: Boolean = true) {
            if (value == 0L) return
            if (onLast) { last += "$lastColumn $op ?"; lastArgs += value }
            packet += "p.$packetColumn $op ?"; packetArgs += value
        }
        // `lastSeenMs >= since` already holds for every row that got this far.
        bound("lastSeenMs", "timestampMs", ">=", heardSinceMs, onLast = false)
        bound("lastSeenMs", "timestampMs", "<=", heardUntilMs)
        bound("frequencyHz", "frequencyHz", ">=", minFrequency)
        bound("frequencyHz", "frequencyHz", "<=", maxFrequency)
        and(
            "((${last.joinToString(" AND ")}) OR EXISTS (SELECT 1 FROM digital_packets p WHERE ${packet.joinToString(" AND ")}))",
            lastArgs + packetArgs,
        )
    }
    if (search.isNotEmpty()) and("searchText LIKE ? ESCAPE '\\'", likePattern(search))
    if (withPositionOnly) and("lat IS NOT NULL AND lon IS NOT NULL")
    if (keyPrefix != null) and("objectKey LIKE ? ESCAPE '\\'", escapeLike(keyPrefix) + "%")
}

internal fun ObjectQuery.toSql(): RoomRawQuery {
    val predicate = predicate()
    val order = if (ascending) "ASC" else "DESC"
    return predicate.build("SELECT * FROM $TRACKED_OBJECTS_TABLE" + predicate.whereClause() + " ORDER BY ${sort.column} $order")
}

internal fun ObjectQuery.toCountSql(): RoomRawQuery {
    val predicate = predicate()
    return predicate.build("SELECT COUNT(*) FROM $TRACKED_OBJECTS_TABLE" + predicate.whereClause())
}
