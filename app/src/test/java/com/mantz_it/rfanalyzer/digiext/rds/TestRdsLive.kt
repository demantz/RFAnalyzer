package com.mantz_it.rfanalyzer.digiext.rds

import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import com.mantz_it.rfanalyzer_ipc.digiext.RdsEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - RDS Station and TMC Fold Tests</h1>
 *
 * Module:      TestRdsLive.kt
 * Description: Pins how RDS groups fold into stations and TMC messages - in particular what "the
 *              station being received right now" means (the notion the Overview pins and the ticker
 *              shows), which must not be confused with a station that was tuned once and is now
 *              only being announced by a neighbour.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestRdsLive {

    private fun group(pi: Int, ts: Long, events: List<RdsEvent> = emptyList()) = rdsGroup(pi, ts, events)

    private class Folded(val stations: Map<String, TrackedObject<RdsStation>>, val tmcMessages: List<TrackedObject<ResolvedTmcMessage>>)

    private fun fold(vararg groups: RdsGroup): Folded = runBlocking {
        val stations = memoryObjectStore(RdsStationFamily)
        val tmc = memoryObjectStore(TmcMessageFamily)
        groups.forEach { applyRdsGroup(stations, tmc, it) }
        stations.publish()
        tmc.publish()
        Folded(stations.live.value.associateBy { it.key }, tmc.live.value)
    }

    private fun stationsAfter(vararg groups: RdsGroup) = fold(*groups).stations

    @Test
    fun aStationIsLiveOnlyWhileItsOwnGroupsKeepArriving() {
        val station = stationsAfter(group(0xD3A1, ts = 1_000L)).getValue("D3A1").state
        assertTrue(station.isLive(nowMs = 1_000L))
        assertTrue(station.isLive(nowMs = 1_000L + RDS_LIVE_WINDOW_MS - 1))
        assertFalse(station.isLive(nowMs = 1_000L + RDS_LIVE_WINDOW_MS))
    }

    /** Two sessions: A was tuned earlier, B is tuned now and announces A via EON. A's last-seen
     *  time moves, but A is not being received. */
    @Test
    fun anEonAnnouncementDoesNotMakeAPreviouslyTunedStationLive() {
        val later = 1_000_000L
        val stations = stationsAfter(
            group(0xD3A1, ts = 1_000L),
            group(0xD3A2, ts = later, events = listOf(RdsEvent.Eon(pi = 0xD3A1, ps = "SWR1"))),
        )
        val announced = stations.getValue("D3A1")
        assertEquals(later, announced.lastSeenMs)
        assertEquals(1_000L, announced.state.lastOwnGroupMs)
        assertEquals(1, announced.packetCount)
        assertTrue(announced.state.tuned)
        assertFalse(announced.state.isLive(later))
        assertTrue(stations.getValue("D3A2").state.isLive(later))
    }

    @Test
    fun aStationKnownOnlyThroughEonIsNeverLive() {
        val station = stationsAfter(group(0xD3A2, ts = 5_000L, events = listOf(RdsEvent.Eon(pi = 0xD3A9)))).getValue("D3A9")
        assertFalse(station.state.isLive(5_000L))
        assertEquals(0, station.packetCount)
    }

    /** The values of a real SWR1 RLP capture: the ODA list keeps every application announced, where
     *  a single last-wins field would flip between TMC and RadioText+. */
    @Test
    fun odaAnnouncementsAndTmcServiceInfoAreCollected() {
        val station = stationsAfter(
            group(
                0xD3A1, ts = 1_000L,
                events = listOf(
                    RdsEvent.AidAnnouncement(applicationGroup = 8, groupTypeA = true, aid = 0xCD46),
                    RdsEvent.AidVariant0(locationTable = 1, afi = true, enhancedMode = false, international = false, national = true, regional = true, urban = false, aid = 0xCD46),
                    RdsEvent.AidVariant1(gapGroups = 11, serviceId = 12, aid = 0xCD46),
                    RdsEvent.TmcTuningInfo(variant = 4, data2 = 0x5357, data3 = 0x5220),
                    RdsEvent.TmcTuningInfo(variant = 5, data2 = 0x2020, data3 = 0x2020),
                    RdsEvent.TmcTuningInfo(variant = 9, data2 = 0x058A, data3 = 0xD393),
                ),
            ),
            group(0xD3A1, ts = 2_000L, events = listOf(RdsEvent.AidAnnouncement(applicationGroup = 12, groupTypeA = true, aid = 0x4BD7))),
        ).getValue("D3A1").state

        assertEquals(mapOf("8A" to 0xCD46, "12A" to 0x4BD7), station.odaApplications)
        val tmc = station.tmcService!!
        assertEquals("SWR", tmc.providerName.trim())
        assertEquals(1, tmc.locationTable)
        assertEquals(12, tmc.serviceId)
        assertEquals(setOf(TmcScope.NATIONAL, TmcScope.REGIONAL), tmc.scope)
        assertEquals(mapOf("D393" to 10), tmc.otherNetworks)
        assertEquals("SWR · table 1 · SID 12 · national/regional · also on D393 (SID 10)", tmcServiceText(tmc))
    }

    private fun tmcMessage(eventCode: Int, locationCode: Int, expiryCode: Int = 0) = RdsEvent.TmcAssembledMessage(
        messageKey = "$eventCode:$locationCode:-1", continuityIndex = -1, singleGroup = true, complete = true,
        eventCode = eventCode, extent = 1, direction = false, diversionRecommended = false, locationCode = locationCode,
        durationOrCi = expiryCode,
    )

    /** A TMC message is an object of its own, keyed under its station; each retransmission counts. */
    @Test
    fun tmcMessagesAreObjectsOfTheirOwn() {
        val folded = fold(
            group(0xD3A1, ts = 1_000L, events = listOf(tmcMessage(101, 12345))),
            group(0xD3A1, ts = 2_000L, events = listOf(tmcMessage(101, 12345), tmcMessage(401, 23456))),
        )
        assertEquals(listOf("D3A1/101:12345:-1", "D3A1/401:23456:-1"), folded.tmcMessages.map { it.key }.sorted())
        val first = folded.tmcMessages.single { it.key == "D3A1/101:12345:-1" }
        assertEquals(2, first.packetCount)
        assertEquals(1_000L, first.firstSeenMs)
        assertEquals(2_000L, first.lastSeenMs)
        assertFalse("TMC text is not part of the station", folded.stations.getValue("D3A1").state.toString().contains("12345"))
    }

    /** Duration code 7 is "until midnight": no expiry of its own, only the retransmission window. */
    @Test
    fun aTmcMessageStopsBeingActiveOnceNoLongerRetransmitted() {
        val message = fold(group(0xD3A1, ts = 1_000L, events = listOf(tmcMessage(101, 12345, expiryCode = 7)))).tmcMessages.single()
        assertTrue(isActiveTmcMessage(message, stationLastOwnGroupMs = 1_000L))
        assertTrue(isActiveTmcMessage(message, stationLastOwnGroupMs = 1_000L + 30 * 60_000L))
        assertFalse(isActiveTmcMessage(message, stationLastOwnGroupMs = 1_000L + 30 * 60_000L + 1))
    }

    /** Duration code 0 is "+15 minutes" for a dynamic event. */
    @Test
    fun aTmcMessageExpiresWithItsOwnDuration() {
        val message = fold(group(0xD3A1, ts = 1_000L, events = listOf(tmcMessage(101, 12345, expiryCode = 0)))).tmcMessages.single()
        assertEquals(15, message.state.expiryMinutes)
        assertTrue(isActiveTmcMessage(message, stationLastOwnGroupMs = 1_000L + 15 * 60_000L))
        assertFalse(isActiveTmcMessage(message, stationLastOwnGroupMs = 1_000L + 15 * 60_000L + 1))
    }
}
