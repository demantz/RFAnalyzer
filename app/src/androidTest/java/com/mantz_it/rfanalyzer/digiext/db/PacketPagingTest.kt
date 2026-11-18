package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import androidx.paging.PagingSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * <h1>RF Analyzer - Packet Paging Tests</h1>
 *
 * Module:      PacketPagingTest.kt
 * Description: Instrumented tests for the Raw subtab's read path: [DigitalPacketDao.paging] over a
 *              [PacketQuery].
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class PacketPagingTest {

    private lateinit var db: DigitalExtensionDatabase
    private lateinit var dao: DigitalPacketDao

    @Before
    fun setUp() {
        db = testDigitalExtensionDatabase(InstrumentationRegistry.getInstrumentation().targetContext)
        dao = db.digitalPacketDao()
    }

    @After
    fun tearDown() = db.close()

    private fun packet(rowId: Long, timestamp: Long, searchText: String, frequencyHz: Long = 466_075_000L) = PacketEntity(
        mode = DigitalMode.POCSAG,
        id = rowId,
        timestampMs = timestamp,
        frequencyHz = frequencyHz,
        searchText = searchText,
        payloadJson = "{}",
    )

    private suspend fun load(query: PacketQuery): List<PacketEntity> {
        val result = dao.paging(query.toSql()).load(
            PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = true)
        )
        assertTrue("load must succeed, was $result", result is PagingSource.LoadResult.Page)
        return (result as PagingSource.LoadResult.Page).data
    }

    private fun search(term: String) = PacketQuery(DigitalMode.POCSAG, search = term)

    @Test
    fun searchMatchesASubstringAnywhere() = runBlocking {
        dao.insertAll(listOf(packet(1, 1_000, "1234567 Fieldday text"), packet(2, 2_000, "unrelated")))

        assertEquals(listOf(1L), load(search("3456")).map { it.id })
    }

    @Test
    fun shortSearchTermsMatchToo() = runBlocking {
        dao.insertAll(listOf(packet(1, 1_000, "1234567 Fieldday text"), packet(2, 2_000, "unrelated")))

        assertEquals(listOf(1L), load(search("Fi")).map { it.id })
        assertEquals(listOf(1L), load(search("5")).map { it.id })
    }

    @Test
    fun searchTermsAreMatchedLiterally() = runBlocking {
        dao.insertAll(
            listOf(packet(1, 1_000, "100% sure_thing"), packet(2, 2_000, "100 percent sure thing"), packet(3, 3_000, "say \"hi\" -now"))
        )

        assertEquals(listOf(1L), load(search("0%")).map { it.id })
        assertEquals(listOf(1L), load(search("e_")).map { it.id })
        assertEquals(listOf(1L), load(search("sure_thing")).map { it.id })
        assertEquals(listOf(3L), load(search("\"hi\" -now")).map { it.id })
    }

    @Test
    fun searchIsCaseInsensitive() = runBlocking {
        dao.insertAll(listOf(packet(1, 1_000, "Fieldday")))

        assertEquals(1, load(search("fi")).size)
        assertEquals(1, load(search("FIELD")).size)
    }

    /** The count and the list come from the same predicate, so they cannot disagree. */
    @Test
    fun countAgreesWithTheListForEveryKindOfTerm() = runBlocking {
        dao.insertAll((1..12L).map { packet(it, 1_000 + it, if (it % 2 == 0L) "even alpha" else "odd beta") })

        for (term in listOf("", "a", "al", "alpha", "beta", "zzz")) {
            assertEquals("term '$term'", load(search(term)).size, dao.count(search(term).toCountSql()))
        }
    }

    @Test
    fun timeAndFrequencyBoundsAndSortOrderApply() = runBlocking {
        dao.insertAll(
            listOf(
                packet(1, 1_000, "a", frequencyHz = 100), packet(2, 2_000, "a", frequencyHz = 200),
                packet(3, 3_000, "a", frequencyHz = 300), packet(4, 4_000, "a", frequencyHz = 400),
            )
        )

        assertEquals(listOf(4L, 3L, 2L, 1L), load(PacketQuery(DigitalMode.POCSAG)).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), load(PacketQuery(DigitalMode.POCSAG, newestFirst = false)).map { it.id })
        assertEquals(listOf(3L, 2L), load(PacketQuery(DigitalMode.POCSAG, minTime = 2_000, maxTime = 3_000)).map { it.id })
        assertEquals(listOf(4L, 3L), load(PacketQuery(DigitalMode.POCSAG, minFrequency = 300)).map { it.id })
        assertEquals(listOf(2L, 1L), load(PacketQuery(DigitalMode.POCSAG, maxFrequency = 200)).map { it.id })
        assertEquals(emptyList<Long>(), load(PacketQuery(DigitalMode.ADSB)).map { it.id })
    }

    @Test
    fun rowsWithARawPayloadLoad() = runBlocking {
        val rawBytes = byteArrayOf(1, 2, 3, 4, 5)
        dao.insertAll(listOf(packet(1, 1_000, "with raw payload").copy(raw = rawBytes, secondaryObjectKey = "other")))

        for (query in listOf(search("payload"), PacketQuery(DigitalMode.POCSAG))) {
            val row = load(query).single()
            assertTrue(rawBytes.contentEquals(row.raw))
            assertEquals("other", row.secondaryObjectKey)
        }
    }

    @Test
    fun insertAfterLoadInvalidatesTheSource() = runBlocking {
        dao.insertAll(listOf(packet(1, 1_000, "alpha")))

        val pagingSource = dao.paging(search("alpha").toSql())
        pagingSource.load(PagingSource.LoadParams.Refresh(key = null, loadSize = 20, placeholdersEnabled = true))

        dao.insertAll(listOf(packet(2, 2_000, "alpha two")))
        // The observer's invalidation callback runs asynchronously on the database's own coroutine
        // scope - give it a moment to fire before asserting.
        delay(200)

        assertTrue("a digital_packets write must invalidate the PagingSource so the UI refreshes", pagingSource.invalid)
    }
}
