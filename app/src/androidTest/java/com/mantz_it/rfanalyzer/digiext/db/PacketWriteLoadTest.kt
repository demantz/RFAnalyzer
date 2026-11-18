package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import java.io.File
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer_ipc.digiext.AdsbPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.AircraftIdPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionRepository

/**
 * <h1>RF Analyzer - Packet Write Load Tests</h1>
 *
 * Module:      PacketWriteLoadTest.kt
 * Description: Load tests of the digital packet write path at ADS-B's real-world peak: 200 frames/s
 *              for a minute are all stored and counted by their aircraft without a drop, and a
 *              burst far beyond the writer's queue capacity drops frames countably without blocking
 *              the caller.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Top of ADS-B's measured real-world range (10-40 aircraft in view). */
private const val AIRCRAFT_IN_VIEW = 40

/**
 * Load test of the write path at ADS-B's real-world peak: every frame goes through
 * [DigitalExtensionRepository.onPacket] exactly as it does from the Binder callback,
 * i.e. it is stored *and* folded into its aircraft by [DigitalStoreWriter].
 *
 * Pins two things only a sustained run shows: that the batched writer keeps up at design load
 * without silently losing a frame, and that saturation drops frames countably instead of
 * blocking the caller or corrupting state.
 *
 * Instrumented rather than host-JVM: needs a real SQLite engine and the writer's actual coroutine
 * scheduling.
 *
 * Run: ./gradlew connectedPlayDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.mantz_it.rfanalyzer.digiext.db.PacketWriteLoadTest
 */
@RunWith(AndroidJUnit4::class)
class PacketWriteLoadTest {

    private lateinit var db: DigitalExtensionDatabase
    private lateinit var writer: DigitalStoreWriter
    private lateinit var packets: DigitalPacketRepository
    private lateinit var repository: DigitalExtensionRepository
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var dataStoreFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = testDigitalExtensionDatabase(context)
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        // A throwaway, uniquely-named preferences file per run - this test only needs
        // AppStateRepository's default values (channelFrequency's fallback, an unset per-mode
        // retention override), never anything actually persisted to it.
        dataStoreFile = context.preferencesDataStoreFile("packet_write_load_test_${System.nanoTime()}")
        val dataStore = PreferenceDataStoreFactory.create(scope = dataStoreScope) { dataStoreFile }
        val appStateRepository = AppStateRepository(dataStore)
        val captureSessionRepository = CaptureSessionRepository(db.captureSessionDao())
        writer = DigitalStoreWriter(
            db, db.digitalPacketDao(), db.trackedObjectDao(), db.captureSessionDao(), captureSessionRepository, appStateRepository,
        )
        packets = DigitalPacketRepository(db.digitalPacketDao(), appStateRepository)
        repository = DigitalExtensionRepository(db, packets, writer, db.trackedObjectDao(), captureSessionRepository)
    }

    @After
    fun tearDown() {
        db.close()
        dataStoreScope.cancel()
        dataStoreFile.delete()
    }

    /** Every stored frame must be counted by exactly one aircraft. */
    private suspend fun framesCountedByAircraft(): Int =
        db.trackedObjectDao().query(ObjectQuery(DigitalMode.ADSB).toSql()).sumOf { it.packetCount }

    private fun syntheticAdsbFrame(id: Long): AdsbPacket {
        val icao = (id % AIRCRAFT_IN_VIEW).toInt()
        return AdsbPacket(
            timestampMs = System.currentTimeMillis(),
            frequencyHz = 1_090_000_000L,
            // DF 17 with the aircraft address in bytes 1-3.
            raw = Bytes(byteArrayOf(0x88.toByte(), (icao shr 16).toByte(), (icao shr 8).toByte(), icao.toByte()) + ByteArray(10) { (id + it).toByte() }),
            payload = AircraftIdPayload(typeCode = 4, category = 0, callsign = "TEST%04d".format(id % 10_000)),
        )
    }

    /**
     * ~200 frames/s for ~60s (12,000 frames) - the top of ADS-B's measured real-world range
     * (50-200 frames/s, ~10-40 aircraft). Asserts zero dropped writes and that every frame sent is
     * eventually persisted, i.e. the bounded channel + batched drain keeps up at design load rather
     * than merely not crashing under it.
     */
    @Test
    fun sustainedPeakAdsbRateDropsNoWrites() = runBlocking {
        val framesPerSecond = 200
        val durationSeconds = 60
        val totalFrames = framesPerSecond * durationSeconds

        val startNanos = System.nanoTime()
        var sent = 0L
        while (sent < totalFrames) {
            repository.onPacket(syntheticAdsbFrame(sent))
            sent++
            // Paced by checking elapsed wall time periodically rather than delay()-ing every frame
            // (which would add scheduling jitter dominating the actual send rate at this frequency).
            if (sent % 50 == 0L) {
                val targetElapsedMs = sent * 1000L / framesPerSecond
                val actualElapsedMs = (System.nanoTime() - startNanos) / 1_000_000
                val waitMs = targetElapsedMs - actualElapsedMs
                if (waitMs > 0) delay(waitMs)
            }
        }

        // Let the writer drain its last in-flight batch (500 ms window) with margin.
        delay(3_000)

        assertEquals(
            "write queue must not drop frames at ADS-B's designed peak rate",
            0L, writer.droppedCount(),
        )
        val written = packets.getCountForMode(DigitalMode.ADSB)
        assertEquals("every sent frame must have been persisted", totalFrames.toLong(), written.toLong())
        assertEquals("every stored frame must be counted by its aircraft", totalFrames, framesCountedByAircraft())
        assertEquals(AIRCRAFT_IN_VIEW, db.trackedObjectDao().countForMode(DigitalMode.ADSB))
    }

    /**
     * A burst well past the writer's queue capacity (8192) sent with no pacing at all - the queue's
     * designed failure mode (explicit, countable drops rather than unbounded growth or blocking the
     * caller) must actually behave that way: the entry point must never suspend/block (it is called
     * from the Binder callback thread in production), and every accepted frame must still be
     * written and counted correctly despite the drops.
     */
    @Test
    fun burstFarBeyondQueueCapacityDropsCountablyWithoutBlockingOrCorruption() = runBlocking {
        val burstSize = 20_000 // several times the queue capacity

        val elapsedMs = kotlin.system.measureTimeMillis {
            for (i in 0 until burstSize) {
                repository.onPacket(syntheticAdsbFrame(i.toLong()))
            }
        }
        // onPacket only enqueues (trySend), so 20,000 frames back-to-back are near-instant; the
        // generous bound catches a send that suspends or blocks on the database.
        assertTrue("the entry point must not block the caller even when the queue is saturated (took ${elapsedMs}ms)", elapsedMs < 5_000)

        delay(5_000) // drain whatever was accepted

        val written = packets.getCountForMode(DigitalMode.ADSB)
        val dropped = writer.droppedCount()
        assertEquals(
            "every frame must be either written or counted as dropped, never silently lost",
            burstSize.toLong(), written.toLong() + dropped,
        )
        assertTrue("a burst this far beyond capacity must actually drop some frames", dropped > 0)
        assertEquals("a dropped frame is dropped whole: neither stored nor counted", written, framesCountedByAircraft())
    }
}
