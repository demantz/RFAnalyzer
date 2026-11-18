package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * <h1>RF Analyzer - Digital Packet DAO Tests</h1>
 *
 * Module:      DigitalPacketDaoTest.kt
 * Description: Instrumented tests for [DigitalPacketDao]'s retention queries and per-object packet
 *              queries.
 *
 * Instrumented rather than host-JVM: the behaviour under test is the SQL itself - the OFFSET
 * semantics of [DigitalPacketDao.findEvictionCutoff], how [DigitalPacketDao.deleteOlderThan] treats
 * rows sharing the cutoff timestamp, and what [PacketQuery.toSql] selects. None of that is
 * observable without a real SQLite engine.
 *
 * Run: ./gradlew connectedPlayDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mantz_it.rfanalyzer.digiext.db.DigitalPacketDaoTest
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class DigitalPacketDaoTest {

    private lateinit var db: DigitalExtensionDatabase
    private lateinit var dao: DigitalPacketDao

    @Before
    fun setUp() {
        db = testDigitalExtensionDatabase(InstrumentationRegistry.getInstrumentation().targetContext)
        dao = db.digitalPacketDao()
    }

    @After
    fun tearDown() = db.close()

    private fun packet(
        mode: DigitalMode = DigitalMode.POCSAG,
        rowId: Long,
        timestamp: Long,
        payload: String = "{}",
    ) = PacketEntity(
        mode = mode,
        id = rowId,
        timestampMs = timestamp,
        frequencyHz = 466_075_000L,
        searchText = "capcode$rowId",
        payloadJson = payload,
    )

    @Test
    fun evictionCutoffIsNullWhenAtOrUnderThreshold() = runBlocking {
        dao.insertAll((1..10L).map { packet(rowId = it, timestamp = 1000 + it) })

        assertNull(dao.findEvictionCutoff(DigitalMode.POCSAG, threshold = 10))
        assertNull(dao.findEvictionCutoff(DigitalMode.POCSAG, threshold = 50))
    }

    /** Pins the deliberate off-by-one documented on [DigitalPacketDao.findEvictionCutoff]: the
     *  cutoff row is itself retained, so eviction trims to threshold + 1, never below threshold. */
    @Test
    fun evictionTrimsToThresholdPlusOne() = runBlocking {
        // Timestamps 1001..1100, all distinct.
        dao.insertAll((1..100L).map { packet(rowId = it, timestamp = 1_000 + it) })

        val cutoff = dao.findEvictionCutoff(DigitalMode.POCSAG, threshold = 40)
        assertEquals(1_060L, cutoff) // 41st-newest row; the newest 40 are 1061..1100
        dao.deleteOlderThan(DigitalMode.POCSAG, cutoff!!)

        // 1060..1100 survive: the 40 the threshold asked for, plus the cutoff row itself.
        assertEquals(41, dao.getCountForMode(DigitalMode.POCSAG))
    }

    /** Eviction must never delete rows the threshold said to keep. Where many rows share the cutoff
     *  timestamp (RDS emits several groups per millisecond) the strictly-older-than delete
     *  over-retains instead - asserted here so a later switch to `<=` can't slip through. */
    @Test
    fun evictionOverRetainsRatherThanOverDeletesOnTimestampTies() = runBlocking {
        // 50 rows all sharing one timestamp, then 10 newer ones.
        dao.insertAll((1..50L).map { packet(rowId = it, timestamp = 1_000) })
        dao.insertAll((51..60L).map { packet(rowId = it, timestamp = 2_000 + it) })

        val cutoff = dao.findEvictionCutoff(DigitalMode.POCSAG, threshold = 20)
        assertEquals(1_000L, cutoff) // the 21st-newest row falls inside the tied block
        dao.deleteOlderThan(DigitalMode.POCSAG, cutoff!!)

        // Nothing is strictly older than the cutoff, so all 60 survive - more than the threshold,
        // but never fewer.
        val remaining = dao.getCountForMode(DigitalMode.POCSAG)
        assertEquals(60, remaining)
        assertTrue("eviction must not drop below the threshold", remaining >= 20)
    }

    /** Paging an export through rows that share timestamps returns every row once, in list order -
     *  ties straddle page boundaries here on purpose. */
    @Test
    fun keysetPagesWalkEveryRowOnceInListOrder() = runBlocking {
        dao.insertAll((1..23L).map { packet(rowId = it, timestamp = 1_000L + it / 4) })
        for (newestFirst in listOf(true, false)) {
            val query = PacketQuery(DigitalMode.POCSAG, newestFirst = newestFirst)
            val paged = ArrayList<Long>()
            var last: PacketEntity? = null
            while (true) {
                val page = dao.query(query.toPageSql(limit = 5, after = last))
                if (page.isEmpty()) break
                paged += page.map { it.id }
                last = page.last()
            }
            assertEquals(dao.query(query.toSql()).map { it.id }, paged)
            assertEquals(23, paged.toSet().size)
        }
    }

    /** The start-up re-parse only runs when some row of the mode was written by an older parser. */
    @Test
    fun hasOutdatedFindsRowsOfAnOlderParserOnly() = runBlocking {
        dao.insertAll(
            listOf(
                packet(DigitalMode.POCSAG, 1, 1_000).copy(parserVersion = 2),
                packet(DigitalMode.LORA, 2, 2_000).copy(parserVersion = 1),
            )
        )
        assertTrue(dao.hasOutdated(DigitalMode.POCSAG, parserVersion = 3))
        assertFalse(dao.hasOutdated(DigitalMode.POCSAG, parserVersion = 2))
        assertFalse(dao.hasOutdated(DigitalMode.R09, parserVersion = 3))
    }

    /** Retention is per mode: trimming a firehose mode must not touch a collection mode. */
    @Test
    fun evictionIsScopedToOneMode() = runBlocking {
        dao.insertAll((1..100L).map { packet(DigitalMode.RDS, it, 1_000 + it) })
        dao.insertAll((1..30L).map { packet(DigitalMode.POCSAG, 100 + it, 1_000 + it) })

        val cutoff = dao.findEvictionCutoff(DigitalMode.RDS, threshold = 10)!!
        dao.deleteOlderThan(DigitalMode.RDS, cutoff)

        assertEquals(11, dao.getCountForMode(DigitalMode.RDS)) // threshold + 1, see above
        assertEquals(30, dao.getCountForMode(DigitalMode.POCSAG))
    }

    // ------------------------------------------------------------------
    // PacketQuery.search
    // ------------------------------------------------------------------

    /** A callsign is in an aircraft's identification frames only, but finds all of its frames. */
    @Test
    fun search_alsoMatchesThePacketsOfAMatchingObject() = runBlocking {
        fun frame(rowId: Long, objectKey: String, text: String) =
            packet(mode = DigitalMode.ADSB, rowId = rowId, timestamp = rowId).copy(objectKey = objectKey, searchText = text)
        dao.insertAll(
            listOf(
                frame(1, "3C6658", "3C6658 AircraftIdentificationPayload DLH7XP"),
                frame(2, "3C6658", "3C6658 AirbornePositionPayload 16400"),
                frame(3, "44CE6F", "44CE6F AirbornePositionPayload 16400"),
            )
        )
        db.trackedObjectDao().upsertAll(
            listOf("3C6658" to "3C6658 DLH7XP", "44CE6F" to "44CE6F").map { (key, text) ->
                TrackedObjectEntity(
                    mode = DigitalMode.ADSB, kind = ObjectKind.ADSB_AIRCRAFT, objectKey = key, displayName = key,
                    firstSeenMs = 1, lastSeenMs = 3, packetCount = 1, frequencyHz = 1_090_000_000L,
                    searchText = text, stateJson = "{}", stateVersion = 1,
                )
            }
        )

        suspend fun found(term: String) = dao.query(PacketQuery(DigitalMode.ADSB, search = term, newestFirst = false).toSql()).map { it.id }
        assertEquals(listOf(1L, 2L), found("dlh7"))
        assertEquals(listOf(1L, 2L), found("XP"))
        assertEquals(listOf(2L, 3L), found("16400"))
        assertEquals(emptyList<Long>(), found("nothing"))
        assertEquals(2, dao.count(PacketQuery(DigitalMode.ADSB, search = "dlh7").toCountSql()))
        // An object of another mode with the same key does not pull these packets in.
        assertEquals(emptyList<Long>(), dao.query(PacketQuery(DigitalMode.POCSAG, search = "dlh7").toSql()).map { it.id })
    }

    // ------------------------------------------------------------------
    // PacketQuery.objectKey - the expanded tracked row's message history
    // ------------------------------------------------------------------

    private fun keyed(
        mode: DigitalMode = DigitalMode.POCSAG,
        rowId: Long,
        timestamp: Long,
        objectKey: String?,
    ) = packet(mode = mode, rowId = rowId, timestamp = timestamp).copy(objectKey = objectKey)

    @Test
    fun objectQuery_returnsOnlyThatObjectNewestFirst() = runBlocking {
        dao.insertAll(
            listOf(
                keyed(rowId = 1, timestamp = 1_000, objectKey = "2504"),
                keyed(rowId = 2, timestamp = 3_000, objectKey = "2504"),
                keyed(rowId = 3, timestamp = 2_000, objectKey = "2504"),
                keyed(rowId = 4, timestamp = 9_000, objectKey = "4520"),
            ),
        )

        val rows = dao.observe(PacketQuery(DigitalMode.POCSAG, objectKey = "2504").toSql(limit = 50)).first()

        assertEquals(3, rows.size)
        assertEquals(listOf(3_000L, 2_000L, 1_000L), rows.map { it.timestampMs })
        assertTrue(rows.all { it.objectKey == "2504" })
    }

    @Test
    fun objectQuery_respectsLimit() = runBlocking {
        dao.insertAll((1..40L).map { keyed(rowId = it, timestamp = 1_000 + it, objectKey = "2504") })

        val rows = dao.observe(PacketQuery(DigitalMode.POCSAG, objectKey = "2504").toSql(limit = 10)).first()

        assertEquals(10, rows.size)
        // Newest-first means the limit keeps the *most recent* window, not the oldest.
        assertEquals(1_040L, rows.first().timestampMs)
        assertEquals(1_031L, rows.last().timestampMs)
    }

    /** The same capcode string under a different mode is a different object entirely. */
    @Test
    fun objectQuery_isScopedToMode() = runBlocking {
        dao.insertAll(
            listOf(
                keyed(mode = DigitalMode.POCSAG, rowId = 1, timestamp = 1_000, objectKey = "2504"),
                keyed(mode = DigitalMode.ADSB, rowId = 2, timestamp = 2_000, objectKey = "2504"),
            ),
        )

        assertEquals(1, dao.observe(PacketQuery(DigitalMode.POCSAG, objectKey = "2504").toSql(limit = 50)).first().size)
        assertEquals(1, dao.observe(PacketQuery(DigitalMode.ADSB, objectKey = "2504").toSql(limit = 50)).first().size)
    }

    /** A row without an object key must not surface under some capcode. */
    @Test
    fun objectQuery_ignoresUnkeyedRows() = runBlocking {
        dao.insertAll(
            listOf(
                keyed(rowId = 1, timestamp = 1_000, objectKey = null),
                keyed(rowId = 2, timestamp = 2_000, objectKey = "2504"),
            ),
        )

        val rows = dao.observe(PacketQuery(DigitalMode.POCSAG, objectKey = "2504").toSql(limit = 50)).first()
        assertEquals(1, rows.size)
        assertEquals(2L, rows.single().id)
    }

    // ------------------------------------------------------------------
    // PacketQuery.bidirectional - FT8/AX.25's two-party detail view
    // ------------------------------------------------------------------

    private fun bidirectional(
        rowId: Long,
        timestamp: Long,
        objectKey: String?,
        secondaryObjectKey: String?,
        mode: DigitalMode = DigitalMode.FT8,
    ) = packet(mode = mode, rowId = rowId, timestamp = timestamp)
        .copy(objectKey = objectKey, secondaryObjectKey = secondaryObjectKey)

    @Test
    fun bidirectionalObjectQuery_matchesEitherParty() = runBlocking {
        dao.insertAll(
            listOf(
                // W1AW transmitting, called DL1ABC.
                bidirectional(1, 1_000, objectKey = "W1AW", secondaryObjectKey = "DL1ABC"),
                // DL1ABC transmitting, called W1AW - the reverse exchange.
                bidirectional(2, 2_000, objectKey = "DL1ABC", secondaryObjectKey = "W1AW"),
                // Unrelated station pair.
                bidirectional(3, 3_000, objectKey = "PY2XYZ", secondaryObjectKey = "JA1XYZ"),
            ),
        )

        val rows = dao.observe(PacketQuery(DigitalMode.FT8, objectKey = "W1AW", bidirectional = true).toSql(limit = 50)).first()

        assertEquals("both directions of W1AW's exchange with DL1ABC must appear", 2, rows.size)
        assertEquals(listOf(2_000L, 1_000L), rows.map { it.timestampMs })
    }

    @Test
    fun bidirectionalObjectQuery_respectsLimitAndMode() = runBlocking {
        dao.insertAll(
            listOf(
                bidirectional(1, 1_000, objectKey = "W1AW", secondaryObjectKey = "DL1ABC"),
                bidirectional(2, 2_000, objectKey = "DL1ABC", secondaryObjectKey = "W1AW"),
                bidirectional(3, 3_000, objectKey = "W1AW", secondaryObjectKey = "K2XYZ", mode = DigitalMode.AX25),
            ),
        )

        assertEquals(1, dao.observe(PacketQuery(DigitalMode.FT8, objectKey = "W1AW", bidirectional = true).toSql(limit = 1)).first().size)
        assertEquals(1, dao.observe(PacketQuery(DigitalMode.AX25, objectKey = "W1AW", bidirectional = true).toSql(limit = 50)).first().size)
    }

    /** The count behind an opened row's "+N older" and the details page: every packet of the object. */
    @Test
    fun objectCount_countsAllOfTheObjectsPackets() = runBlocking {
        dao.insertAll((1..120L).map { keyed(rowId = it, timestamp = 1_000 + it, objectKey = if (it <= 80) "2504" else "4520") })
        assertEquals(80, dao.observeCount(PacketQuery(DigitalMode.POCSAG, objectKey = "2504").toCountSql()).first())
    }

    /** A field inside the raw bytes, matched on their hex - an RDS group's type in block B. */
    @Test
    fun rawHexGlob_matchesAFieldInsideTheBytes() = runBlocking {
        fun group(rowId: Long, blockB: Int) = keyed(DigitalMode.RDS, rowId, 1_000 + rowId, "D3A1").copy(
            raw = byteArrayOf(0xD3.toByte(), 0xA1.toByte(), (blockB ushr 8).toByte(), blockB.toByte(), 0, 0, 0, 0),
        )
        dao.insertAll(listOf(group(1, 0x0421), group(2, 0xE021), group(3, 0xE821), group(4, 0x4021)))
        suspend fun ids(glob: String) =
            dao.query(PacketQuery(DigitalMode.RDS, objectKey = "D3A1", rawHexGlob = glob, rawHexOffset = 4).toSql()).map { it.id }
        assertEquals(listOf(2L), ids("E[0-7]*"))   // 14A
        assertEquals(listOf(3L), ids("E[89A-F]*")) // 14B
        assertEquals(listOf(4L), ids("4[0-7]*"))   // 4A
    }
}
