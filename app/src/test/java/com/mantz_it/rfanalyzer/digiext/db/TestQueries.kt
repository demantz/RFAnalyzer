package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Query Tests</h1>
 *
 * Module:      TestQueries.kt
 * Description: Pins how the shared filter turns into the two database queries. Their SQL is
 *              exercised against a real database in the instrumented `DigitalPacketDaoTest` and
 *              `TrackedObjectDaoTest`.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestQueries {

    @Test
    fun noModeMeansNoQuery() {
        assertNull(DigitalFilter().toPacketQuery())
        assertNull(DigitalFilter().toObjectQuery(nowMs = 1))
    }

    /** Every mode lists everything stored unless a time range narrows it. */
    @Test
    fun withoutATimeRangeEveryModeListsEverything() {
        DigitalMode.entries.forEach { mode ->
            assertEquals(0L, DigitalFilter(mode = mode).toObjectQuery(nowMs = 10_000_000)!!.heardSinceMs)
        }
    }

    @Test
    fun anExplicitTimeRangeBoundsWhenTheObjectWasHeard() {
        val now = 10_000_000L
        val past = DigitalFilter(mode = DigitalMode.ADSB, minTime = 8_000, maxTime = 9_000).toObjectQuery(now)!!
        assertEquals(8_000L, past.heardSinceMs)
        assertEquals(9_000L, past.heardUntilMs)

        val untilOnly = DigitalFilter(mode = DigitalMode.ADSB, maxTime = 9_000).toObjectQuery(now)!!
        assertEquals(0L, untilOnly.heardSinceMs)
    }

    /** A rolling range is resolved against the clock every time a query is built. */
    @Test
    fun aRollingRangeFollowsTheClock() {
        val filter = DigitalFilter(mode = DigitalMode.ADSB, lastMs = 3_600_000)
        assertEquals(6_400_000L, filter.toObjectQuery(nowMs = 10_000_000)!!.heardSinceMs)
        assertEquals(16_400_000L, filter.toObjectQuery(nowMs = 20_000_000)!!.heardSinceMs)
        assertEquals(0L, filter.toObjectQuery(nowMs = 20_000_000)!!.heardUntilMs)

        val packets = filter.toPacketQuery(nowMs = 10_000_000)!!
        assertEquals(6_400_000L, packets.minTime)
        assertEquals(0L, packets.maxTime)
    }

    /**
     * One bound alone is answered by the object's own first/last-seen columns. A closed range or a
     * frequency range is not - an object heard inside it and again outside still belongs - so the
     * object's packets are consulted as well.
     */
    @Test
    fun objectsHeardOnceInsideTheRangeAreFoundThroughTheirPackets() {
        fun where(query: ObjectQuery) = query.toSql().sql.substringAfter(" WHERE ").substringBefore(" ORDER BY")

        assertEquals("mode = ? AND lastSeenMs >= ?", where(ObjectQuery(DigitalMode.ADSB, heardSinceMs = 1)))
        assertEquals("mode = ? AND firstSeenMs <= ?", where(ObjectQuery(DigitalMode.ADSB, heardUntilMs = 2)))
        assertEquals(
            "mode = ? AND lastSeenMs >= ? AND firstSeenMs <= ? AND ((lastSeenMs <= ?) OR EXISTS (SELECT 1 FROM digital_packets p " +
                "WHERE p.mode = tracked_objects.mode AND p.objectKey = tracked_objects.objectKey " +
                "AND p.timestampMs >= ? AND p.timestampMs <= ?))",
            where(ObjectQuery(DigitalMode.ADSB, heardSinceMs = 1, heardUntilMs = 2)),
        )
        assertEquals(
            "mode = ? AND ((frequencyHz >= ? AND frequencyHz <= ?) OR EXISTS (SELECT 1 FROM digital_packets p " +
                "WHERE p.mode = tracked_objects.mode AND p.objectKey = tracked_objects.objectKey " +
                "AND p.frequencyHz >= ? AND p.frequencyHz <= ?))",
            where(ObjectQuery(DigitalMode.ADSB, minFrequency = 1, maxFrequency = 2)),
        )
    }

    /** A packet search also finds the packets of every object the term matches. */
    @Test
    fun packetSearchAlsoMatchesThroughTheTrackedObject() {
        val sql = PacketQuery(DigitalMode.ADSB, search = "DLH").toSql().sql
        assertTrue(sql.contains("OR objectKey IN (SELECT objectKey FROM tracked_objects WHERE mode = ? AND searchText LIKE ?"))
    }

    /** Without a time or frequency range, the packet query carries just the search and the order. */
    @Test
    fun scopeDoesNotApplyToPackets() {
        val query = DigitalFilter(mode = DigitalMode.ADSB, search = "DLH", newestFirst = false).toPacketQuery()!!
        assertEquals(PacketQuery(DigitalMode.ADSB, search = "DLH", newestFirst = false), query)
    }

    @Test
    fun onlyTheClausesInUseAreEmitted() {
        assertEquals(
            "SELECT * FROM digital_packets WHERE mode = ? ORDER BY timestampMs DESC, id DESC",
            PacketQuery(DigitalMode.POCSAG).toSql().sql,
        )
        assertEquals(
            "SELECT * FROM digital_packets WHERE mode = ? AND (objectKey = ? OR secondaryObjectKey = ?) ORDER BY timestampMs ASC, id ASC LIMIT 50",
            PacketQuery(DigitalMode.FT8, objectKey = "DF4IAE", bidirectional = true, newestFirst = false).toSql(limit = 50).sql,
        )
        assertEquals(
            "SELECT * FROM tracked_objects WHERE mode = ? AND kind = ? AND lastSeenMs >= ? AND lat IS NOT NULL AND lon IS NOT NULL ORDER BY lastSeenMs DESC",
            ObjectQuery(DigitalMode.LORA, kind = ObjectKind.MESHCORE_NODE, heardSinceMs = 1, withPositionOnly = true).toSql().sql,
        )
        assertEquals(
            "SELECT COUNT(*) FROM tracked_objects WHERE mode = ? AND kind = ? AND lastSeenMs >= ?",
            ObjectQuery(DigitalMode.ADSB, kind = ObjectKind.ADSB_AIRCRAFT, heardSinceMs = 1).toCountSql().sql,
        )
    }

    /** While a recording replays, its session's packets are listed first - they carry the
     *  recording's time. Only newest first. */
    @Test
    fun aReplaysPacketsComeFirst() {
        assertEquals(
            "SELECT * FROM digital_packets WHERE mode = ? ORDER BY (timestampMs BETWEEN 1000 AND 2000) DESC, timestampMs DESC, id DESC",
            PacketQuery(DigitalMode.LORA, currentFirst = 1_000L..2_000L).toSql().sql,
        )
        assertEquals(
            "SELECT * FROM digital_packets WHERE mode = ? ORDER BY timestampMs ASC, id ASC",
            PacketQuery(DigitalMode.LORA, newestFirst = false, currentFirst = 1_000L..2_000L).toSql().sql,
        )
    }

    /** A page after the first continues behind the previous page's last row, in the same order. */
    @Test
    fun aPageIsKeyedOnThePreviousPagesLastRow() {
        val last = PacketEntity(id = 7, mode = DigitalMode.ADSB, timestampMs = 1_000, frequencyHz = 0, payloadJson = "{}", searchText = "")
        assertEquals(
            "SELECT * FROM digital_packets WHERE mode = ? ORDER BY timestampMs DESC, id DESC LIMIT 500",
            PacketQuery(DigitalMode.ADSB).toPageSql(500, after = null).sql,
        )
        assertEquals(
            "SELECT * FROM digital_packets WHERE mode = ? AND (timestampMs < ? OR (timestampMs = ? AND id < ?)) ORDER BY timestampMs DESC, id DESC LIMIT 500",
            PacketQuery(DigitalMode.ADSB).toPageSql(500, after = last).sql,
        )
        assertEquals(
            "SELECT * FROM digital_packets WHERE mode = ? AND (timestampMs > ? OR (timestampMs = ? AND id > ?)) ORDER BY timestampMs ASC, id ASC LIMIT 500",
            PacketQuery(DigitalMode.ADSB, newestFirst = false).toPageSql(500, after = last).sql,
        )
    }
}
