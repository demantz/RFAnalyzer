package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import com.mantz_it.rfanalyzer.digiext.adsb.AdsbAircraft
import com.mantz_it.rfanalyzer.digiext.adsb.AdsbAircraftFamily
import com.mantz_it.rfanalyzer.digiext.adsb.AdsbCategory
import com.mantz_it.rfanalyzer.digiext.adsb.CprHalf
import com.mantz_it.rfanalyzer.digiext.adsb.applyAdsbFrame
import com.mantz_it.rfanalyzer.digiext.aprs.StatusInfo
import com.mantz_it.rfanalyzer.digiext.ax25.AprsStation
import com.mantz_it.rfanalyzer.digiext.ax25.AprsStationFamily
import com.mantz_it.rfanalyzer.digiext.ax25.FbbEntry
import com.mantz_it.rfanalyzer.digiext.ax25.PacketStation
import com.mantz_it.rfanalyzer.digiext.ax25.PacketStationFamily
import com.mantz_it.rfanalyzer.digiext.ft8.Ft8Station
import com.mantz_it.rfanalyzer.digiext.ft8.Ft8StationFamily
import com.mantz_it.rfanalyzer.digiext.lora.LoRaWanMType
import com.mantz_it.rfanalyzer.digiext.lora.LoraModulation
import com.mantz_it.rfanalyzer.digiext.lora.MeshCorePayloadType
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticDeviceMetrics
import com.mantz_it.rfanalyzer.digiext.lora.aprs.LoraAprsStationFamily
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.LoRaWanDevice
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.LoRaWanDeviceFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreNode
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNode
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrameType
import com.mantz_it.rfanalyzer.digiext.lora.MeshComNeighbour
import com.mantz_it.rfanalyzer.digiext.lora.MeshComPosition
import com.mantz_it.rfanalyzer.digiext.lora.MeshComTextKind
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.MeshComNode
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.MeshComNodeFamily
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagAlphaEncoding
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagMessage
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagPager
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagPagerFamily
import com.mantz_it.rfanalyzer.digiext.pocsag.applyPocsagPage
import com.mantz_it.rfanalyzer.digiext.pocsag.pocsagPage
import com.mantz_it.rfanalyzer.digiext.r09.MeldepunktStatus
import com.mantz_it.rfanalyzer.digiext.r09.R09Bus
import com.mantz_it.rfanalyzer.digiext.r09.R09BusFamily
import com.mantz_it.rfanalyzer.digiext.rds.RdsStation
import com.mantz_it.rfanalyzer.digiext.rds.RdsStationFamily
import com.mantz_it.rfanalyzer.digiext.rds.ResolvedTmcMessage
import com.mantz_it.rfanalyzer.digiext.rds.TmcMessageFamily
import com.mantz_it.rfanalyzer.digiext.rds.TmcScope
import com.mantz_it.rfanalyzer.digiext.rds.TmcService
import com.mantz_it.rfanalyzer.digiext.rds.applyRdsGroup
import com.mantz_it.rfanalyzer.digiext.adsb.AdsbSpec
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.AircraftIdPayload
import com.mantz_it.rfanalyzer_ipc.digiext.AirborneVelocityPayload
import com.mantz_it.rfanalyzer.digiext.ft8.Callsign
import com.mantz_it.rfanalyzer.digiext.ft8.LocationReport
import com.mantz_it.rfanalyzer.digiext.r09.R09Paket
import com.mantz_it.rfanalyzer_ipc.digiext.RdsEvent
import com.mantz_it.rfanalyzer.digiext.rds.rdsGroup
import com.mantz_it.rfanalyzer.digiext.ft8.StandardMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Object Store Tests</h1>
 *
 * Module:      TestObjectStore.kt
 * Description: Pins the rules [ObjectStore.update] applies the same way for every kind of object,
 *              and the property the storage design rests on: the store is a write-through cache of
 *              `tracked_objects`, so what is known about an object survives the store forgetting
 *              it - an app restart or an idle eviction.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestObjectStore {

    private suspend fun ObjectStore<PocsagPager>.page(address: Long, ts: Long, alpha: String? = null, frequencyHz: Long = 439_987_500L) =
        applyPocsagPage(
            this,
            pocsagPage(
                function = 3, alpha = alpha, encoding = if (alpha != null) PocsagAlphaEncoding.PLAIN else PocsagAlphaEncoding.UNKNOWN,
                address = address, timestampMs = ts, frequencyHz = frequencyHz,
            ),
        )

    @Test
    fun aNewProcessContinuesWhereTheLastOneStopped() = runBlocking {
        val rows = mutableMapOf<String, TrackedObjectEntity>()
        val first = memoryObjectStore(PocsagPagerFamily, rows)
        first.page(4520, ts = 1_000, alpha = "FIRST")
        first.page(4520, ts = 2_000, alpha = "SECOND")
        first.commitTo(rows)

        // A fresh store, as after an app restart, hearing the pager again.
        val second = memoryObjectStore(PocsagPagerFamily, rows)
        second.page(4520, ts = 9_000, alpha = "THIRD")
        second.commitTo(rows)

        val row = rows.getValue("4520")
        assertEquals(3, row.packetCount)
        assertEquals(1_000L, row.firstSeenMs)
        assertEquals(9_000L, row.lastSeenMs)
        assertEquals("THIRD", second.liveObject("4520").state.message.alphaMessage)
    }

    /** An object dropped from memory and then heard again keeps counting up from its stored total,
     *  and is written on its very next packet. */
    @Test
    fun anEvictedObjectKeepsCountingWhenItReturns() = runBlocking {
        val rows = mutableMapOf<String, TrackedObjectEntity>()
        val store = memoryObjectStore(PocsagPagerFamily, rows)
        repeat(30) { store.page(77, ts = 1_000L + it) }
        store.commitTo(rows)

        store.evictIdle(nowMs = System.currentTimeMillis() + PocsagPagerFamily.idleTimeoutMs + 1)
        store.publish()
        assertTrue(store.live.value.isEmpty())

        store.page(77, ts = 5_000)
        assertEquals(1, store.dirtyEntities().size)
        store.commitTo(rows)
        assertEquals(31, rows.getValue("77").packetCount)
        assertEquals(5_000L, rows.getValue("77").lastSeenMs)
    }

    @Test
    fun evictionNeverDropsAnUnwrittenChange() = runBlocking {
        val store = memoryObjectStore(PocsagPagerFamily)
        store.page(77, ts = 1_000)
        store.evictIdle(nowMs = Long.MAX_VALUE)
        assertEquals(1, store.dirtyEntities().size)
    }

    @Test
    fun onlyChangedObjectsAreWritten() = runBlocking {
        val rows = mutableMapOf<String, TrackedObjectEntity>()
        val store = memoryObjectStore(PocsagPagerFamily, rows)
        store.page(1, ts = 1_000)
        store.page(2, ts = 1_000)
        store.commitTo(rows)
        assertTrue(store.dirtyEntities().isEmpty())

        store.page(2, ts = 2_000)
        assertEquals(listOf("2"), store.dirtyEntities().map { it.objectKey })
    }

    /** The bookkeeping lives in the row's columns only; the state holds the kind's own fields. */
    @Test
    fun bookkeepingIsInTheColumnsNotTheState() = runBlocking {
        val store = memoryObjectStore(AdsbAircraftFamily)
        // DF 17, aircraft address 3C6444.
        val raw = Bytes(byteArrayOf(0x88.toByte(), 0x3C, 0x64, 0x44) + ByteArray(10))
        val frame = AdsbSpec.packet(
            timestampMs = 1_000, frequencyHz = 1_090_000_000L, raw = raw, phy = Unit,
            data = AircraftIdPayload(typeCode = 4, category = 3, callsign = "DLH123"),
        )
        applyAdsbFrame(store, frame)
        applyAdsbFrame(store, frame.copy(timestampMs = 2_000, data = AirborneVelocityPayload(subtype = 1, speedKt = 420, headingDeg = 90.0, vertRateFpm = 0, vertRateSource = "BARO")))

        val row = store.dirtyEntities().single()
        assertEquals(ObjectKind.ADSB_AIRCRAFT, row.kind)
        assertEquals(DigitalMode.ADSB, row.mode)
        assertEquals("3C6444", row.objectKey)
        assertEquals("DLH123", row.displayName)
        assertEquals(2, row.packetCount)
        assertEquals(2_000L, row.lastSeenMs)
        assertEquals(1_090_000_000L, row.frequencyHz)
        assertEquals(AdsbAircraftFamily.stateVersion, row.stateVersion)
        assertFalse("bookkeeping must not be repeated in the state", row.stateJson.contains("lastSeen"))
        assertEquals(AdsbCategory.A3, storageJson.decodeFromString(AdsbAircraft.serializer(), row.stateJson).category)
        assertTrue("callsign must be searchable", row.searchText.contains("DLH123"))
        assertTrue("key must be searchable", row.searchText.contains("3C6444"))
    }

    /** A re-parse can fold in a packet older than what the object already knows: it counts, but the
     *  state keeps describing the newest packet. */
    @Test
    fun anOlderPacketCountsButDoesNotOverwriteTheState() = runBlocking {
        val store = memoryObjectStore(PocsagPagerFamily)
        store.page(77, ts = 2_000, alpha = "NEW", frequencyHz = 2L)
        store.page(77, ts = 1_000, alpha = "OLD", frequencyHz = 1L)

        val pager = store.liveObject("77")
        assertEquals("NEW", pager.state.message.alphaMessage)
        assertEquals(2, pager.packetCount)
        assertEquals(1_000L, pager.firstSeenMs)
        assertEquals(2_000L, pager.lastSeenMs)
        assertEquals(2L, pager.frequencyHz)
    }

    @Test
    fun anUntrustedPacketCountsButDoesNotChangeTheState() = runBlocking {
        val store = memoryObjectStore(PocsagPagerFamily)
        store.page(77, ts = 1_000, alpha = "GOOD")
        store.update("77", 2_000, frequencyHz = null, trusted = false, create = { error("exists") }) { error("must not fold") }

        val pager = store.liveObject("77")
        assertEquals("GOOD", pager.state.message.alphaMessage)
        assertEquals(2, pager.packetCount)
        assertEquals(2_000L, pager.lastSeenMs)
    }

    /** A station only known from a neighbour's EON announcement: heard of, but never heard. */
    @Test
    fun anAnnouncementMovesLastSeenWithoutCounting() = runBlocking {
        val stations = memoryObjectStore(RdsStationFamily)
        val tmc = memoryObjectStore(TmcMessageFamily)
        val eon = RdsEvent.Eon(pi = 0xD3A3, ps = "SWR3", alternativeFrequencies = listOf(94.8, 97.5), afComplete = true)
        val group = rdsGroup(pi = 0xD3A1, ts = 5_000, events = listOf(eon), groupType = 14, pty = 10, frequencyHz = 99_900_000L)
        applyRdsGroup(stations, tmc, group)

        val neighbour = stations.liveObject("D3A3")
        assertEquals(0, neighbour.packetCount)
        assertEquals(5_000L, neighbour.lastSeenMs)
        assertEquals("SWR3", neighbour.displayName)
        assertEquals("never tuned: its own AF list gives it a frequency", 94_800_000L, neighbour.frequencyHz)
        assertEquals(1, stations.liveObject("D3A1").packetCount)
        assertEquals(99_900_000L, stations.liveObject("D3A1").frequencyHz)
    }

    @Test
    fun aTrailRecordsPositionChangesOnly() = runBlocking {
        val store = memoryObjectStore(AprsStationFamily)
        suspend fun at(ts: Long, lat: Double, lon: Double) =
            store.update("STATION:DF4IAE", ts, null, create = { AprsStation("DF4IAE") }) { it.copy(lat = lat, lon = lon) }
        at(1_000, 49.0, 8.0)
        at(2_000, 49.0, 8.0)
        at(3_000, 49.1, 8.1)
        store.publish()

        val trail = store.trails.value.getValue("STATION:DF4IAE")
        assertEquals(listOf(3_000L, 1_000L), trail.map { it.timestampMs })
        assertEquals(49.1, store.liveObject("STATION:DF4IAE").lat!!, 0.0)
    }

    @Test
    fun aRowThatNoLongerDecodesStartsTheObjectOver() = runBlocking {
        val rows = mutableMapOf<String, TrackedObjectEntity>()
        val store = memoryObjectStore(PocsagPagerFamily, rows)
        store.page(77, ts = 1_000)
        store.commitTo(rows)
        rows["77"] = rows.getValue("77").copy(stateJson = "{\"not\":\"a pager\"}")

        val restarted = memoryObjectStore(PocsagPagerFamily, rows)
        restarted.page(77, ts = 2_000)
        assertEquals(1, restarted.liveObject("77").packetCount)
    }

    @Serializable
    private data class RenamedState(val name: String)

    /** A non-additive state change is migrated as JSON before the old row is decoded. */
    @Test
    fun anOlderStateVersionIsMigratedBeforeDecoding() {
        val family = ObjectFamily(
            kind = ObjectKind.POCSAG_PAGER,
            serializer = RenamedState.serializer(),
            displayName = { _, state -> state.name },
            stateVersion = 2,
            migrateState = { state, fromVersion ->
                if (fromVersion < 2) JsonObject(mapOf("name" to state.getValue("label"))) else state
            },
        )
        val v1Row = TrackedObjectEntity(
            mode = DigitalMode.POCSAG, kind = ObjectKind.POCSAG_PAGER, objectKey = "1", displayName = "old",
            firstSeenMs = 1, lastSeenMs = 2, packetCount = 3, frequencyHz = null, searchText = "",
            stateJson = """{"label":"old"}""", stateVersion = 1,
        )
        assertEquals(RenamedState("old"), v1Row.toTrackedObject(family).state)
        assertEquals("rewritten at the current version", 2, v1Row.toTrackedObject(family).toEntity(family).stateVersion)
    }

    @Test
    fun clearForgetsEverything() = runBlocking {
        val store = memoryObjectStore(PocsagPagerFamily)
        store.page(77, ts = 1_000)
        store.publish()
        store.clear()
        store.publish()
        assertTrue(store.live.value.isEmpty())
        assertTrue(store.dirtyEntities().isEmpty())
    }

    // ------------------------------------------------------------------
    // A stored object must decode to what was written - per kind.
    // ------------------------------------------------------------------

    private fun <S : Any> assertRoundTrip(family: ObjectFamily<S>, state: S, expected: S = state) {
        val tracked = TrackedObject(family.kind, "key", "name", 1_000, 2_000, 3, 100_000_000L, 49.5, 8.4, state)
        assertEquals(tracked.copy(state = expected), tracked.toEntity(family).toTrackedObject(family))
    }

    @Test
    fun everyKindRoundTripsThroughItsRow() {
        assertRoundTrip(
            PocsagPagerFamily,
            PocsagPager(function = 3, message = PocsagMessage(alphaMessage = "a", numericMessage = "1", alphaEncoding = PocsagAlphaEncoding.SKYPER, skyperMessage = "s", skyperTag = "t")),
        )
        // The CPR halves are decode state and come back empty.
        val aircraft = AdsbAircraft(
            callsign = "DLH123", category = AdsbCategory.A3, onGround = false, lat = 49.5, lon = 8.4, positionMs = 1_500,
            altitudeFt = 35_000, speedKt = 420, headingDeg = 90.0, vertRateFpm = -64, squawk = "7000",
            emergencyState = 0, adsbVersion = 2, nacp = 9, sil = 3,
        )
        assertRoundTrip(AdsbAircraftFamily, aircraft.copy(airborneEven = CprHalf(1, 2, 3)), expected = aircraft)
        assertRoundTrip(
            Ft8StationFamily,
            Ft8Station(
                lastCalledCallsign = "K1ABC", grid = "JN49", lastSnr = -12f, bestSnr = -3f, lastTimeDeltaMs = 200,
                lastMessage = StandardMessage(Callsign("K1ABC"), false, Callsign("DF4IAE"), false, false, LocationReport("JN49")),
            ),
        )
        assertRoundTrip(
            RdsStationFamily,
            RdsStation(
                pty = 10, programService = "SWR3", radioText = "now playing",
                trafficProgram = true, trafficAnnouncement = false, music = true,
                clockTime = RdsEvent.ClockTime("t", 2026, 10, 16, 12, 30, 2.0),
                alternativeFrequencies = listOf(99.9, 101.1), alternativeFrequenciesComplete = true,
                otherNetworks = listOf(RdsEvent.Eon(pi = 0xD3A3, ps = "SWR3")),
                odaApplications = mapOf("8A" to 0xCD46), tuned = true, lastOwnGroupMs = 2_000,
                tmcService = TmcService(providerName = "SWR     ", locationTable = 1, serviceId = 12, scope = setOf(TmcScope.NATIONAL), otherNetworks = mapOf("D393" to 10)),
            ),
        )
        assertRoundTrip(
            TmcMessageFamily,
            ResolvedTmcMessage(
                messageKey = "101:12345:-1", eventCode = 101, eventText = "stationary traffic", nature = "Info",
                urgency = "Normal", direction = null, extent = 2, locationCode = 12345, diversionAdvised = false, expiryMinutes = 60,
            ),
        )
        val station = AprsStation(
            callsign = "DF4IAE", lat = 49.5, lon = 8.4, symbolTable = '/', symbol = '>', courseDeg = 90.0,
            speedKnots = 12.0, altitudeFeet = 300, comment = "QRV", lastInfo = StatusInfo(null, "QRV"),
        )
        assertRoundTrip(AprsStationFamily, station)
        assertRoundTrip(AprsStationFamily, station.copy(objectName = "FIRE", killed = true))
        assertRoundTrip(LoraAprsStationFamily, AprsStation(callsign = "DF4IAE-10", lat = 49.5, lon = 8.4))
        assertRoundTrip(
            PacketStationFamily,
            PacketStation(
                callsign = "VA2PGN", beacon = "VA2PGN-4 IGATE", nodeAlias = "PGNBPQ", routes = 3, mailFor = listOf("VA2PGN"), bulletins = 2,
                lastBulletin = FbbEntry(49642, 'B', 1260, "EQUAK", "HP2DFA", "261025", "EARTHQUAKE ALERT"), peers = listOf("KE0GB"), lastFrame = "UI",
            ),
        )
        assertRoundTrip(
            R09BusFamily,
            R09Bus(
                liniennummer = 5, kursnummer = 12, zielnummer = 301, lastZw = 2,
                lastMeldepunkt = MeldepunktStatus(4711, setOf(R09Paket.Tabelleneintrag.VORANM, R09Paket.Tabelleneintrag.HPTANM), R09Paket.Tabelleneintrag.HPTANM, 2_000),
                meldepunkte = setOf(4709, 4710, 4711),
            ),
        )
        assertRoundTrip(
            MeshCoreNodeFamily,
            MeshCoreNode(
                name = "Node", publicKeyHex = "e7e822dc", isRepeater = true, lat = 49.5, lon = 8.4,
                lastPayloadType = MeshCorePayloadType.ADVERT, modulation = LoraModulation(8, 62_500, 1),
            ),
        )
        assertRoundTrip(
            MeshtasticNodeFamily,
            MeshtasticNode(
                shortName = "LN", longName = "Long Name", hwModel = 43, role = 0, isLicensed = false, lat = 49.5, lon = 8.4,
                deviceMetrics = MeshtasticDeviceMetrics(batteryLevel = 80, voltage = 4.0f, channelUtilization = 1f, airUtilTx = 0.5f, uptimeSeconds = 60),
                telemetryTimestampSec = 1_700_000_000, lastPortnum = 3, modulation = LoraModulation(11, 250_000, 1),
            ),
        )
        assertRoundTrip(
            MeshComNodeFamily,
            MeshComNode(
                position = MeshComPosition(49.2667, 8.6028, '/', '-', comment = "Battery Powered", name = "DJ1PW-15", batteryPercent = 85, altitudeFt = 505, neighbours = 6, groups = listOf(9, 262)),
                hardware = 43, firmware = "4.40a", isGateway = true, neighbours = 6, neighbourList = listOf(MeshComNeighbour("DL1YCS-12", -6.5f)),
                lastType = MeshComFrameType.TEXT, lastText = "Hallo", lastTextKind = MeshComTextKind.CHAT, lastTextTo = "262",
                modulation = LoraModulation(11, 250_000, 2), snrDb = -19.5f, relayed = 3,
            ),
        )
        assertRoundTrip(
            LoRaWanDeviceFamily,
            LoRaWanDevice(devAddr = "260B1234", joinEui = "70B3D57ED0000000", mtype = LoRaWanMType.UNCONFIRMED_DATA_UP, fCnt = 17, lastFPort = 2),
        )
    }

    /** A restored aircraft has no CPR pairing state, so its position must come from the stored one
     *  until a fresh even/odd pair arrives - not vanish. */
    @Test
    fun aRestoredAircraftKeepsItsLastPosition() = runBlocking {
        val rows = mutableMapOf<String, TrackedObjectEntity>()
        val first = memoryObjectStore(AdsbAircraftFamily, rows)
        first.update("3C6444", 1_000, null, create = ::AdsbAircraft) {
            it.copy(lat = 49.5, lon = 8.4, altitudeFt = 1_000, airborneEven = CprHalf(1, 2, 1_000))
        }
        first.commitTo(rows)

        val restored = memoryObjectStore(AdsbAircraftFamily, rows)
        restored.update("3C6444", 2_000, null, create = ::AdsbAircraft) { it }
        val aircraft = restored.liveObject("3C6444")
        assertEquals(49.5, aircraft.lat!!, 0.0)
        assertEquals(1_000, aircraft.state.altitudeFt)
        assertNull(aircraft.state.airborneEven)
        assertNull(aircraft.state.callsign)
    }
}
