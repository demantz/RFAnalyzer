package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer.digiext.lora.LoraSpec
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPhy
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPacket
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPhy
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.AdsbPacket
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionRepository
import com.mantz_it.rfanalyzer.digiext.adsb.AdsbAircraftFamily
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticNodeInfo
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPacket
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPosition
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticInfo
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNodeFamily
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagPager
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagPagerFamily
import com.mantz_it.rfanalyzer_ipc.digiext.AircraftIdPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * <h1>RF Analyzer - Digital Store Writer Tests</h1>
 *
 * Module:      DigitalStoreWriterTest.kt
 * Description: End-to-end tests of the write path against a real database: packets go in through
 *              [DigitalExtensionRepository]'s entry points exactly as they do from the Binder
 *              callbacks, and the assertions read the tables back.
 *
 * These pin the consistency guarantees the storage design exists for: a packet and its tracked
 * object are written together, an object survives an app restart intact, and clearing leaves
 * nothing behind.
 *
 * Run: ./gradlew connectedPlayDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mantz_it.rfanalyzer.digiext.db.DigitalStoreWriterTest
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class DigitalStoreWriterTest {

    private lateinit var db: DigitalExtensionDatabase
    private lateinit var appStateRepository: AppStateRepository
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File

    /** Everything a running app process holds. A second one on the same [db] is an app restart. */
    private inner class AppProcess {
        val sessions = CaptureSessionRepository(db.captureSessionDao())
        val writer = DigitalStoreWriter(
            db, db.digitalPacketDao(), db.trackedObjectDao(), db.captureSessionDao(), sessions, appStateRepository,
        )
        val packets = DigitalPacketRepository(db.digitalPacketDao(), appStateRepository)
        val repository = DigitalExtensionRepository(db, packets, writer, db.trackedObjectDao(), sessions)
    }

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = testDigitalExtensionDatabase(context)
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStoreFile = context.preferencesDataStoreFile("digital_store_writer_test_${System.nanoTime()}")
        val dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile }
        appStateRepository = AppStateRepository(dataStore)
    }

    @After
    fun tearDown() {
        db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    private suspend fun awaitUntil(what: String, timeoutMs: Long = 5_000, condition: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(25)
        }
        assertTrue("timed out waiting for: $what", condition())
    }

    private fun AppProcess.page(address: Long, timestamp: Long) = repository.onPacket(
        PocsagPacket(timestampMs = timestamp, frequencyHz = 439_987_500L, raw = Bytes.EMPTY, phy = PocsagPhy(address, function = 1, baudRate = 1200, bitCount = 0))
    )

    /** A DF 17 identification frame of aircraft [icao]. */
    private fun AppProcess.aircraftId(icao: Int, timestamp: Long, callsign: String = "DLH123") = repository.onPacket(
        AdsbPacket(
            timestampMs = timestamp, frequencyHz = 1_090_000_000L,
            raw = Bytes(byteArrayOf(0x88.toByte(), (icao shr 16).toByte(), (icao shr 8).toByte(), icao.toByte()) + ByteArray(10)),
            payload = AircraftIdPayload(typeCode = 4, category = 3, callsign = callsign),
        )
    )

    /** An already-decoded Meshtastic frame, submitted as the repository would after parsing one -
     *  building real encrypted payload bytes is not what these tests are about. */
    private fun AppProcess.meshtastic(sender: Long, timestamp: Long, info: MeshtasticInfo) = writer.submit(
        Interpreted(
            LoraSpec,
            LoraSpec.packet(
                timestampMs = timestamp, frequencyHz = 869_525_000L, raw = Bytes(byteArrayOf(1, 2, 3)),
                phy = LoraPhy(spreadingFactor = 11, codingRate = 1, bandwidthHz = 250_000, hasCrc = true, crcValid = true, syncWord = 0x2B),
                data = MeshtasticPacket(
                    dest = 0xFFFFFFFFL, sender = sender, packetId = timestamp, hopLimit = 3, wantAck = false,
                    hopStart = 3, viaMqtt = false, channelHash = 0x08, info = info,
                ),
            ),
        )
    )

    private suspend fun packetCount(mode: DigitalMode) = db.digitalPacketDao().getCountForMode(mode)
    private suspend fun objectCount(mode: DigitalMode) = db.trackedObjectDao().countForMode(mode)

    /** An object list and its count follow the writer's commits - a new pager appears, a further
     *  page of a known one is counted - however the list paces its reads. */
    @Test
    fun anObjectListFollowsTheCommits() = runBlocking {
        val app = AppProcess()
        val listed = MutableStateFlow<List<TrackedObject<PocsagPager>>>(emptyList())
        val counted = MutableStateFlow(0)
        val collectors = listOf(
            launch(Dispatchers.Default) { app.repository.objects(PocsagPagerFamily, ObjectQuery(DigitalMode.POCSAG)).collect { listed.value = it } },
            launch(Dispatchers.Default) { app.repository.objectCount(PocsagPagerFamily, DigitalFilter(mode = DigitalMode.POCSAG)).collect { counted.value = it } },
        )
        try {
            for (i in 1..5) {
                app.page(address = 1000L + i, timestamp = 1_000L * i)
                awaitUntil("pager $i listed and counted") { listed.value.size == i && counted.value == i }
            }
            app.page(address = 1001, timestamp = 10_000)
            awaitUntil("the second page counted") { listed.value.first { it.key == "1001" }.packetCount == 2 }
            assertEquals(5, counted.value)
        } finally {
            collectors.forEach { it.cancel() }
        }
    }

    /** No tracked object may claim a packet that is not stored, or lag behind one that is. */
    @Test
    fun everyObjectCountsExactlyItsStoredPackets() = runBlocking {
        val app = AppProcess()
        repeat(3) { app.page(address = 4520, timestamp = 1_000L + it) }
        repeat(2) { app.page(address = 77, timestamp = 2_000L + it) }
        repeat(4) { app.aircraftId(icao = 0x3C6444, timestamp = 3_000L + it) }
        awaitUntil("all packets written") { packetCount(DigitalMode.POCSAG) == 5 && packetCount(DigitalMode.ADSB) == 4 }

        // Checked the instant the packets are visible: the objects are in the same transaction.
        for (mode in listOf(DigitalMode.POCSAG, DigitalMode.ADSB)) {
            val objects = db.trackedObjectDao().query(ObjectQuery(mode).toSql())
            assertTrue(objects.isNotEmpty())
            for (obj in objects) {
                val stored = db.digitalPacketDao().count(PacketQuery(mode, objectKey = obj.objectKey).toCountSql())
                assertEquals("${obj.mode}/${obj.objectKey}", stored, obj.packetCount)
            }
        }
        assertEquals(3, db.trackedObjectDao().find(DigitalMode.POCSAG, "4520")!!.packetCount)
        assertEquals(0L, app.writer.droppedCount())
    }

    /** What one process learned about an object must survive the next process hearing only part
     *  of it again: a node known by name stays named when a position-only packet arrives after a
     *  restart, and the position is added rather than replacing the rest. */
    @Test
    fun restartKeepsWhatWasKnownAboutAnObject() = runBlocking {
        val firstRun = AppProcess()
        firstRun.meshtastic(sender = 0x1234, timestamp = 1_000, info = MeshtasticNodeInfo(portnum = 4, nodeId = "!1234", longName = "Hilltop Relay", shortName = "HILL", hwModel = 43))
        firstRun.meshtastic(sender = 0x1234, timestamp = 2_000, info = MeshtasticNodeInfo(portnum = 4, nodeId = "!1234", longName = "Hilltop Relay", shortName = "HILL", hwModel = 43))
        awaitUntil("first run written") { db.trackedObjectDao().find(DigitalMode.LORA, "!1234")?.packetCount == 2 }

        val secondRun = AppProcess()
        secondRun.meshtastic(sender = 0x1234, timestamp = 9_000, info = MeshtasticPosition(portnum = 3, latitude = 49.5, longitude = 8.4, altitudeM = 120))
        awaitUntil("second run written") { db.trackedObjectDao().find(DigitalMode.LORA, "!1234")?.packetCount == 3 }

        val row = db.trackedObjectDao().find(DigitalMode.LORA, "!1234")!!
        assertEquals("Hilltop Relay", row.displayName)
        assertEquals(1_000L, row.firstSeenMs)
        assertEquals(9_000L, row.lastSeenMs)
        assertEquals(49.5, row.lat!!, 0.0)

        val node = secondRun.repository.objects(MeshtasticNodeFamily, ObjectQuery(DigitalMode.LORA)).first().single()
        assertEquals("Hilltop Relay", node.state.longName)
        assertEquals("HILL", node.state.shortName)
        assertEquals(43, node.state.hwModel)
        assertEquals(49.5, node.lat!!, 0.0)
        assertEquals(3, node.packetCount)
    }

    @Test
    fun clearingAModeRemovesItsPacketsObjectsAndMemoryButNothingElse() = runBlocking {
        val app = AppProcess()
        repeat(3) { app.page(address = 4520, timestamp = 1_000L + it) }
        app.aircraftId(icao = 0x3C6444, timestamp = 3_000)
        awaitUntil("written") { packetCount(DigitalMode.POCSAG) == 3 && packetCount(DigitalMode.ADSB) == 1 }

        app.repository.clear(DigitalMode.POCSAG)

        assertEquals(0, packetCount(DigitalMode.POCSAG))
        assertEquals(0, objectCount(DigitalMode.POCSAG))
        assertEquals(1, packetCount(DigitalMode.ADSB))
        assertEquals(1, objectCount(DigitalMode.ADSB))

        // The in-memory state went with it: the pager starts over rather than resurrecting its count.
        app.page(address = 4520, timestamp = 5_000)
        awaitUntil("page after clear written") { packetCount(DigitalMode.POCSAG) == 1 }
        val pager = db.trackedObjectDao().find(DigitalMode.POCSAG, "4520")!!
        assertEquals(1, pager.packetCount)
        assertEquals(5_000L, pager.firstSeenMs)
    }

    @Test
    fun clearingEverythingEmptiesAllThreeTables() = runBlocking {
        val app = AppProcess()
        val finished = db.captureSessionDao().insert(CaptureSessionEntity(startedAtMs = 1, endedAtMs = 2, sourceType = "File"))
        app.page(address = 4520, timestamp = 1_000)
        app.aircraftId(icao = 0x3C6444, timestamp = 3_000)
        awaitUntil("written") { packetCount(DigitalMode.POCSAG) == 1 && packetCount(DigitalMode.ADSB) == 1 }
        assertTrue(finished > 0)

        app.repository.clear(null)

        assertEquals(0, db.digitalPacketDao().getTotalCount())
        assertEquals(0, objectCount(DigitalMode.POCSAG))
        assertEquals(0, objectCount(DigitalMode.ADSB))
        assertNull(db.captureSessionDao().getMostRecent())
    }

    /** A clear is ordered with the packet stream: what was queued before it is gone afterwards. */
    @Test
    fun clearAlsoRemovesPacketsStillQueuedBeforeIt() = runBlocking {
        val app = AppProcess()
        repeat(50) { app.page(address = 4520, timestamp = 1_000L + it) }

        app.repository.clear(DigitalMode.POCSAG)

        delay(1_500) // well past the batch window - nothing queued earlier may surface now
        assertEquals(0, packetCount(DigitalMode.POCSAG))
        assertEquals(0, objectCount(DigitalMode.POCSAG))
    }

    /** The Overview table and the Map are the same query; the Map only drops position-less objects. */
    @Test
    fun overviewAndMapSelectTheSameObjects() = runBlocking {
        val app = AppProcess()
        app.meshtastic(sender = 0x1, timestamp = 1_000, info = MeshtasticPosition(portnum = 3, latitude = 49.5, longitude = 8.4, altitudeM = null))
        app.meshtastic(sender = 0x2, timestamp = 2_000, info = MeshtasticNodeInfo(portnum = 4, nodeId = "!2", longName = "No GPS", shortName = "NG"))
        app.aircraftId(icao = 0x3C6444, timestamp = 3_000)
        awaitUntil("written") { objectCount(DigitalMode.LORA) == 2 && objectCount(DigitalMode.ADSB) == 1 }

        val filter = DigitalFilter(mode = DigitalMode.LORA)
        val overview = app.repository.objects(MeshtasticNodeFamily, filter).first()
        val map = app.repository.objects(MeshtasticNodeFamily, filter, withPositionOnly = true).first()
        assertEquals(setOf("!1", "!2"), overview.map { it.key }.toSet())
        assertEquals(listOf("!1"), map.map { it.key })
        assertEquals(overview.single { it.key == "!1" }, map.single())

        // Another mode's objects never leak into either.
        assertTrue(app.repository.objects(AdsbAircraftFamily, filter).first().isEmpty())
        assertTrue(app.repository.objects(MeshtasticNodeFamily, DigitalFilter(mode = DigitalMode.ADSB)).first().isEmpty())
        assertEquals(listOf("!2"), app.repository.objects(MeshtasticNodeFamily, filter.copy(search = "no gps")).first().map { it.key })
    }

    /** One undecodable row must not take the whole list down. */
    @Test
    fun anUndecodableRowIsSkippedNotFatal() = runBlocking {
        val app = AppProcess()
        app.page(address = 4520, timestamp = 1_000)
        awaitUntil("written") { objectCount(DigitalMode.POCSAG) == 1 }
        val good = db.trackedObjectDao().find(DigitalMode.POCSAG, "4520")!!
        db.trackedObjectDao().upsertAll(listOf(good.copy(objectKey = "broken", stateJson = "{\"not\":\"a pager\"}")))

        val pagers = app.repository.objects(PocsagPagerFamily, ObjectQuery(DigitalMode.POCSAG)).first()
        assertEquals(listOf("4520"), pagers.map { it.key })
    }
}
