package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * <h1>RF Analyzer - Tracked Object DAO Tests</h1>
 *
 * Module:      TrackedObjectDaoTest.kt
 * Description: Instrumented tests for `tracked_objects` ([TrackedObjectDao] and [ObjectQuery]'s
 *              SQL) and [CaptureSessionDao]'s session lifecycle.
 *
 * Run: ./gradlew connectedPlayDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mantz_it.rfanalyzer.digiext.db.TrackedObjectDaoTest
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class TrackedObjectDaoTest {

    private lateinit var db: DigitalExtensionDatabase
    private lateinit var dao: TrackedObjectDao
    private lateinit var sessionDao: CaptureSessionDao

    @Before
    fun setUp() {
        db = testDigitalExtensionDatabase(InstrumentationRegistry.getInstrumentation().targetContext)
        dao = db.trackedObjectDao()
        sessionDao = db.captureSessionDao()
    }

    @After
    fun tearDown() = db.close()

    private fun node(
        objectKey: String,
        kind: ObjectKind = ObjectKind.MESHCORE_NODE,
        displayName: String = objectKey,
        firstSeenMs: Long = 1_000,
        lastSeenMs: Long = 2_000,
        packetCount: Int = 1,
        frequencyHz: Long = 869_618_000L,
        lat: Double? = null,
        lon: Double? = null,
        searchText: String = objectKey,
    ) = TrackedObjectEntity(
        mode = kind.mode, kind = kind, objectKey = objectKey, displayName = displayName,
        firstSeenMs = firstSeenMs, lastSeenMs = lastSeenMs, packetCount = packetCount,
        frequencyHz = frequencyHz, lat = lat, lon = lon, searchText = searchText, stateJson = "{}", stateVersion = 1,
    )

    private suspend fun keys(query: ObjectQuery): List<String> = dao.query(query.toSql()).map { it.objectKey }

    @Test
    fun upsertReplacesTheRowOfTheSameModeAndKey() = runBlocking {
        dao.upsertAll(listOf(node("a", packetCount = 1)))
        dao.upsertAll(listOf(node("a", packetCount = 5, displayName = "renamed")))

        assertEquals(1, dao.countForMode(DigitalMode.LORA))
        val row = dao.find(DigitalMode.LORA, "a")!!
        assertEquals(5, row.packetCount)
        assertEquals("renamed", row.displayName)
    }

    /** The same key in two modes is two objects. */
    @Test
    fun identityIsScopedToTheMode() = runBlocking {
        dao.upsertAll(listOf(node("2504", kind = ObjectKind.POCSAG_PAGER), node("2504", kind = ObjectKind.ADSB_AIRCRAFT)))

        assertEquals(1, dao.countForMode(DigitalMode.POCSAG))
        assertEquals(1, dao.countForMode(DigitalMode.ADSB))
        assertNull(dao.find(DigitalMode.FT8, "2504"))
    }

    /** LoRa's families share one mode and are told apart by kind, not by key prefix. */
    @Test
    fun kindSeparatesTheFamiliesOfOneMode() = runBlocking {
        dao.upsertAll(
            listOf(
                node("MeshCore:aa", kind = ObjectKind.MESHCORE_NODE), node("!1234", kind = ObjectKind.MESHTASTIC_NODE),
                node("LoRaWAN:01", kind = ObjectKind.LORAWAN_DEVICE), node("LoraAprs:DF4IAE", kind = ObjectKind.LORA_APRS_STATION),
            )
        )

        assertEquals(4, keys(ObjectQuery(DigitalMode.LORA)).size)
        assertEquals(listOf("!1234"), keys(ObjectQuery(DigitalMode.LORA, kind = ObjectKind.MESHTASTIC_NODE)))
        assertEquals(emptyList<String>(), keys(ObjectQuery(DigitalMode.AX25)))
    }

    @Test
    fun aSingleTimeBoundIsAnsweredByFirstAndLastSeen() = runBlocking {
        dao.upsertAll(
            listOf(
                node("old", firstSeenMs = 500, lastSeenMs = 1_000), node("mid", firstSeenMs = 1_500, lastSeenMs = 2_000),
                node("new", firstSeenMs = 2_500, lastSeenMs = 3_000),
            )
        )

        assertEquals(listOf("new", "mid"), keys(ObjectQuery(DigitalMode.LORA, heardSinceMs = 2_000)))
        assertEquals(listOf("mid", "old"), keys(ObjectQuery(DigitalMode.LORA, heardUntilMs = 2_000)))
    }

    private fun sighting(objectKey: String, timestamp: Long, frequencyHz: Long) = PacketEntity(
        mode = DigitalMode.LORA, timestampMs = timestamp, frequencyHz = frequencyHz,
        searchText = "", payloadJson = "{}", objectKey = objectKey,
    )

    /** Heard once inside the range is enough, however often the object was heard outside it. */
    @Test
    fun anObjectHeardInsideTheRangeAndAgainOutsideIsStillListed() = runBlocking {
        dao.upsertAll(
            listOf(
                // Heard at 1500 on 200 and, last, at 9000 on 900.
                node("returned", firstSeenMs = 1_500, lastSeenMs = 9_000, frequencyHz = 900),
                // Heard at 500 and 9000 only: it spans the range without a sighting inside it.
                node("spanning", firstSeenMs = 500, lastSeenMs = 9_000, frequencyHz = 900),
                node("inside", firstSeenMs = 1_200, lastSeenMs = 1_800, frequencyHz = 200),
                node("before", firstSeenMs = 100, lastSeenMs = 200, frequencyHz = 200),
            )
        )
        db.digitalPacketDao().insertAll(
            listOf(
                sighting("returned", 1_500, 200), sighting("returned", 9_000, 900),
                sighting("spanning", 500, 200), sighting("spanning", 9_000, 900),
                sighting("inside", 1_200, 200), sighting("inside", 1_800, 200),
                sighting("before", 100, 200), sighting("before", 200, 200),
            )
        )

        assertEquals(
            listOf("inside", "returned"),
            keys(ObjectQuery(DigitalMode.LORA, heardSinceMs = 1_000, heardUntilMs = 2_000)).sorted(),
        )
        // The frequency range is met by an earlier sighting, not the last one.
        assertEquals(
            listOf("before", "inside", "returned", "spanning"),
            keys(ObjectQuery(DigitalMode.LORA, minFrequency = 150, maxFrequency = 250)).sorted(),
        )
        // Both at once must hold for the same sighting.
        assertEquals(
            listOf("spanning"),
            keys(ObjectQuery(DigitalMode.LORA, heardUntilMs = 600, minFrequency = 150, maxFrequency = 250, heardSinceMs = 300)),
        )
        assertEquals(
            emptyList<String>(),
            keys(ObjectQuery(DigitalMode.LORA, heardSinceMs = 8_000, minFrequency = 150, maxFrequency = 250)),
        )
    }

    /** Without packets to consult (retention), the last sighting is still answered by the row itself. */
    @Test
    fun theLastSightingNeedsNoPackets() = runBlocking {
        dao.upsertAll(listOf(node("a", firstSeenMs = 100, lastSeenMs = 1_500, frequencyHz = 200)))

        assertEquals(listOf("a"), keys(ObjectQuery(DigitalMode.LORA, heardSinceMs = 1_000, heardUntilMs = 2_000, minFrequency = 150)))
        assertEquals(emptyList<String>(), keys(ObjectQuery(DigitalMode.LORA, heardSinceMs = 1_000, heardUntilMs = 1_200)))
    }

    @Test
    fun searchIsACaseInsensitiveLiteralSubstring() = runBlocking {
        dao.upsertAll(listOf(node("a", searchText = "a Base_Station 100%"), node("b", searchText = "b BaseXStation 100")))

        assertEquals(listOf("a", "b").sorted(), keys(ObjectQuery(DigitalMode.LORA, search = "base")).sorted())
        assertEquals(listOf("a"), keys(ObjectQuery(DigitalMode.LORA, search = "e_s")))
        assertEquals(listOf("a"), keys(ObjectQuery(DigitalMode.LORA, search = "0%")))
    }

    @Test
    fun positionOnlyKeepsObjectsWithBothCoordinates() = runBlocking {
        dao.upsertAll(listOf(node("here", lat = 49.5, lon = 8.4), node("nowhere"), node("half", lat = 49.5)))

        assertEquals(listOf("here"), keys(ObjectQuery(DigitalMode.LORA, withPositionOnly = true)))
    }

    @Test
    fun everySortOrders() = runBlocking {
        dao.upsertAll(
            listOf(
                node("a", displayName = "zulu", firstSeenMs = 30, lastSeenMs = 100, packetCount = 2),
                node("b", displayName = "Alpha", firstSeenMs = 10, lastSeenMs = 300, packetCount = 9),
                node("c", displayName = "mike", firstSeenMs = 20, lastSeenMs = 200, packetCount = 5),
            )
        )

        assertEquals(listOf("b", "c", "a"), keys(ObjectQuery(DigitalMode.LORA)))
        assertEquals(listOf("a", "c", "b"), keys(ObjectQuery(DigitalMode.LORA, ascending = true)))
        assertEquals(listOf("b", "c", "a"), keys(ObjectQuery(DigitalMode.LORA, sort = ObjectSort.FIRST_SEEN, ascending = true)))
        assertEquals(listOf("b", "c", "a"), keys(ObjectQuery(DigitalMode.LORA, sort = ObjectSort.PACKET_COUNT)))
        assertEquals(listOf("b", "c", "a"), keys(ObjectQuery(DigitalMode.LORA, sort = ObjectSort.NAME, ascending = true)))
    }

    @Test
    fun deleteByModeLeavesOtherModesAlone() = runBlocking {
        dao.upsertAll(listOf(node("a"), node("2504", kind = ObjectKind.POCSAG_PAGER)))

        dao.deleteByMode(DigitalMode.LORA)

        assertEquals(0, dao.countForMode(DigitalMode.LORA))
        assertEquals(1, dao.countForMode(DigitalMode.POCSAG))
    }

    // ------------------------------------------------------------------
    // capture_sessions
    // ------------------------------------------------------------------

    private fun session(startedAtMs: Long, endedAtMs: Long? = null) =
        CaptureSessionEntity(startedAtMs = startedAtMs, endedAtMs = endedAtMs, sourceType = "HackRF")

    /** The app can be killed without endSession() ever running. */
    @Test
    fun abandonedSessionsAreClosedWhenANewOneStarts() = runBlocking {
        sessionDao.insert(session(startedAtMs = 1_000))
        val current = sessionDao.insert(session(startedAtMs = 5_000))

        sessionDao.closeAbandonedSessions(endedAtMs = 5_000, exceptId = current)

        assertEquals(5_000L, sessionDao.getMostRecent()!!.startedAtMs)
        assertNull("the running session stays open", sessionDao.getMostRecent()!!.endedAtMs)
        sessionDao.deleteEnded()
        assertEquals("only the abandoned one was ended, so only it is deleted", current, sessionDao.getMostRecent()!!.id)
    }

    @Test
    fun deleteEndedKeepsARunningSession() = runBlocking {
        val ended = sessionDao.insert(session(startedAtMs = 1_000))
        sessionDao.markEnded(ended, 2_000)
        val running = sessionDao.insert(session(startedAtMs = 3_000))

        sessionDao.deleteEnded()

        assertEquals(running, sessionDao.getMostRecent()!!.id)
        sessionDao.markEnded(running, 4_000)
        sessionDao.deleteEnded()
        assertNull(sessionDao.getMostRecent())
    }
}
