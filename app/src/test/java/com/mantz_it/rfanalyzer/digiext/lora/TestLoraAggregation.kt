package com.mantz_it.rfanalyzer.digiext.lora

import com.mantz_it.rfanalyzer.digiext.aprs.PositionReport
import com.mantz_it.rfanalyzer.digiext.aprs.UnknownAprsInfo
import com.mantz_it.rfanalyzer.digiext.ax25.AprsStation
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.lora.aprs.applyLoraAprsFrame
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreNode
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.applyMeshCoreFrame
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNode
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.applyMeshtasticFrame
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore
import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import com.mantz_it.rfanalyzer.digiext.lora.aprs.LoraAprsStationFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNodeFamily
import kotlinx.coroutines.runBlocking

/**
 * <h1>RF Analyzer - LoRa Aggregation Tests</h1>
 *
 * Module:      TestLoraAggregation.kt
 * Description: Pins how LoRa frames fold into tracked objects: the [isAttributable] gate (only
 *              intact or independently verified frames get an object key) and MeshCore's identity
 *              scoping. Frames are built through [LoraSpec], and folded only with a key, as
 *              [LoraSpec.fold] does.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestLoraAggregation {

    private fun <S : Any> fold(family: ObjectFamily<S>, block: suspend (ObjectStore<S>) -> Unit): List<TrackedObject<S>> = runBlocking {
        val store = memoryObjectStore(family)
        block(store)
        store.publish()
        store.live.value
    }

    private fun aggregateMeshCoreNodes(batch: List<LoraFrame>): List<TrackedObject<MeshCoreNode>> =
        fold(MeshCoreNodeFamily) { store ->
            batch.forEach { f -> (f.data as? MeshCorePacket)?.takeIf { !f.objectKey.isNullOrEmpty() }?.let { applyMeshCoreFrame(store, f, it) } }
        }

    private fun aggregateMeshtasticNodes(batch: List<LoraFrame>): List<TrackedObject<MeshtasticNode>> =
        fold(MeshtasticNodeFamily) { store ->
            batch.forEach { f -> (f.data as? MeshtasticPacket)?.takeIf { !f.objectKey.isNullOrEmpty() }?.let { applyMeshtasticFrame(store, f, it) } }
        }

    private fun aggregateLoraAprsStations(batch: List<LoraFrame>): List<TrackedObject<AprsStation>> =
        fold(LoraAprsStationFamily) { store ->
            batch.forEach { f -> (f.data as? LoraAprsPacket)?.takeIf { !f.objectKey.isNullOrEmpty() }?.let { applyLoraAprsFrame(store, f, it) } }
        }

    private fun advert(name: String, publicKey: ByteArray, signatureValid: Boolean = true) = MeshCorePacket(
        routeType = MeshCoreRouteType.FLOOD, payloadType = MeshCorePayloadType.ADVERT, payloadVersion = 0,
        transportCodes = null, pathHashSize = 1, path = emptyList(),
        info = MeshCoreAdvert(
            publicKey = Bytes(publicKey), timestamp = 0L, signature = Bytes(ByteArray(64)), flags = 0x01,
            latitude = null, longitude = null, feature1 = null, feature2 = null, name = name, signatureValid = signatureValid,
        ),
    )

    private fun groupText(senderName: String) = MeshCorePacket(
        routeType = MeshCoreRouteType.FLOOD, payloadType = MeshCorePayloadType.GRP_TXT, payloadVersion = 0,
        transportCodes = null, pathHashSize = 1, path = emptyList(),
        info = MeshCoreGroupText(channelName = "Public", timestamp = 0L, senderName = senderName, text = "hi"),
    )

    private fun meshtasticPacket(sender: Long) = MeshtasticPacket(
        dest = 0xFFFFFFFFL, sender = sender, packetId = 1L, hopLimit = 3, wantAck = false,
        hopStart = 3, viaMqtt = false, channelHash = 0x08,
        info = MeshtasticTextMessage(portnum = 1, text = "hi"),
    )

    private fun loraAprsPacket(fromCall: String) = LoraAprsPacket(
        fromCall = fromCall, toCall = "APRS", path = emptyList(),
        info = UnknownAprsInfo(raw = ""),
    )

    /** An ADVERT is keyed by pubkey, a group text only by a self-reported name. The two are never
     *  merged, in either arrival order: a packet's identity must not depend on what was heard
     *  before it. */
    @Test
    fun advertAndGroupTextOfTheSameNameStaySeparateInEitherOrder() {
        val publicKey = byteArrayOf(0xE7.toByte(), 0xE8.toByte(), 0x22, 0xDC.toByte())
        val advert = loraFrame(advert("E7E822DC", publicKey), hasCrc = true, crcValid = true, id = 1L)
        val text = loraFrame(groupText("E7E822DC"), hasCrc = true, crcValid = true, id = 2L)

        for (batch in listOf(listOf(advert, text), listOf(text, advert))) {
            val nodes = aggregateMeshCoreNodes(batch)
            assertEquals(setOf("MeshCore:E7E822DC", "MeshCoreGroup:E7E822DC"), nodes.map { it.key }.toSet())
            assertEquals(listOf(1, 1), nodes.map { it.packetCount })
        }
    }

    /** A CRC-failed frame's identity bytes are as suspect as the rest (a flipped bit in the sender id
     *  would invent a node), so it gets no object: the node counts its intact frames only. */
    @Test
    fun aCrcFailingFrameGetsNoObject() {
        val batch = listOf(
            loraFrame(meshtasticPacket(0x1234L), hasCrc = true, crcValid = true, id = 1L),
            loraFrame(meshtasticPacket(0x1234L), hasCrc = true, crcValid = false, id = 2L),
            loraFrame(meshtasticPacket(0x1236L), hasCrc = true, crcValid = false, id = 3L),
        )
        assertEquals(listOf("!1234", "", ""), batch.map { it.objectKey })
        val nodes = aggregateMeshtasticNodes(batch)
        assertEquals(listOf("!1234"), nodes.map { it.key })
        assertEquals(1, nodes.single().packetCount)
        assertEquals(0, aggregateLoraAprsStations(listOf(loraFrame(loraAprsPacket("DF4IAE-10"), hasCrc = true, crcValid = false))).size)
    }

    /** A frame sent without CRC counts as intact only from [LORA_MIN_RELIABILITY] on. */
    @Test
    fun aFrameWithoutCrcNeedsAReliableDecode() {
        fun key(reliability: Float?) = loraFrame(meshtasticPacket(0x1234L), hasCrc = false, crcValid = true, reliability = reliability).objectKey
        assertEquals("", key(null))
        assertEquals("", key(0.997f))
        assertEquals("!1234", key(LORA_MIN_RELIABILITY))
        assertEquals("!1234", key(1f))
    }

    /** Content with an integrity check of its own decides by that check, whatever the CRC says. */
    @Test
    fun verifiedContentDecidesByItsOwnCheck() {
        val key = byteArrayOf(0xE7.toByte(), 0xE8.toByte(), 0x22, 0xDC.toByte())
        assertEquals("MeshCoreGroup:Bob", loraFrame(groupText("Bob"), hasCrc = true, crcValid = false).objectKey)
        assertEquals("MeshCore:E7E822DC", loraFrame(advert("A", key), hasCrc = true, crcValid = false).objectKey)
        assertEquals("", loraFrame(advert("A", key, signatureValid = false), hasCrc = true, crcValid = true).objectKey)
    }

    private fun loraAprsPosition(fromCall: String, comment: String) = LoraAprsPacket(
        fromCall = fromCall, toCall = "APLRG1", path = emptyList(),
        info = PositionReport(
            dti = '!', timestamp = null, latRaw = null, lonRaw = null, lat = 49.45, lon = 8.66,
            symbolTable = 'L', symbol = 'a', comment = comment, phg = null, course = 90, speedKnots = 12, altitudeFeet = 400,
        ),
    )

    /** The station keeps what a good frame reports - the symbol included, which the Overview row
     *  and the map marker draw. */
    @Test
    fun aGoodLoraAprsFrameFillsPositionSymbolAndMotion() {
        val station = aggregateLoraAprsStations(
            listOf(loraFrame(loraAprsPosition("DF4IAE-10", "iGate"), hasCrc = true, crcValid = true)),
        ).single()
        assertEquals('L', station.state.symbolTable)
        assertEquals('a', station.state.symbol)
        assertEquals(49.45, station.lat!!, 1e-9)
        assertEquals(90.0, station.state.courseDeg!!, 1e-9)
        assertEquals(12.0, station.state.speedKnots!!, 1e-9)
        assertEquals(400, station.state.altitudeFeet)
        assertEquals("iGate", station.state.comment)
    }

    /** A node without a set location or fix reports 0°/0°: the node keeps its last good position,
     *  as an APRS station does. */
    @Test
    fun aZeroPositionKeepsTheLastGoodOne() {
        fun position(lat: Double, lon: Double) = meshtasticPacket(0x1234L).copy(info = MeshtasticPosition(portnum = 3, latitude = lat, longitude = lon, altitudeM = null))
        val node = aggregateMeshtasticNodes(listOf(
            loraFrame(position(49.41, 8.69), ts = 1_000L),
            loraFrame(position(0.0, 0.0), ts = 2_000L),
        )).single()
        assertEquals(49.41, node.lat!!, 1e-9)
        assertEquals(8.69, node.lon!!, 1e-9)

        val zeroAdvert = advert("Repeater", ByteArray(32) { 7 }).let { packet ->
            packet.copy(info = (packet.info as MeshCoreAdvert).copy(flags = 0x12, latitude = 0.0, longitude = 0.0))
        }
        assertNull(aggregateMeshCoreNodes(listOf(loraFrame(zeroAdvert))).single().lat)
    }
}
