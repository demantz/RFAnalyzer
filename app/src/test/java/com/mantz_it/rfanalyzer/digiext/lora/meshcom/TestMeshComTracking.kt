package com.mantz_it.rfanalyzer.digiext.lora.meshcom

import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import com.mantz_it.rfanalyzer.digiext.lora.LoraSpec
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrame
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrameType
import com.mantz_it.rfanalyzer.digiext.lora.MeshComTextKind
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPhy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - MeshCom Tracking Tests</h1>
 *
 * Module:      TestMeshComTracking.kt
 * Description: MeshCom frames into stations: the originator gets the content, the relay it was
 *              heard from the signal.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestMeshComTracking {

    private fun hex(s: String) = Bytes(ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() })

    private fun frame(hex: String, snr: Float, crcValid: Boolean = true) = hex(hex).let { raw ->
        val phy = LoraPhy(spreadingFactor = 11, codingRate = 2, bandwidthHz = 250_000, hasCrc = true, crcValid = crcValid, syncWord = 0x2B, snrDb = snr)
        LoraSpec.packet(timestampMs = 1_000L, frequencyHz = 433_175_000L, raw = raw, phy = phy, data = LoraSpec.parse(raw, phy))
    }

    private val position = "21468315A452444A3150572D31353E2A21343931362E30304E2F30303833362E3137452D4261747465727920506F776572656423444A3150572D31352F423D3038352F413D3030303530352F4E362F523D393B3236323B002B8816DB28AB617E"
    private val positionRelayed = "21468315A4D1444A3150572D31352C444C315943532D31323E2A21343931362E30304E2F30303833362E3137452D4261747465727920506F776572656423444A3150572D31352F423D3038352F413D3030303530352F4E362F523D393B3236323B002B8819C62883617E"
    private val gatewayHey = "402D00735014444C315943532D31323E48474052343B00038805DD2883617E"
    private val time = "3A5EE3F56AB24F45315841522D33332C444C315943532D31323E2A3A7B4345547D323032362D31312D31362030393A34353A33310000880EF00083237E"
    private val corrupted = "40B9B3412892444831484F2D312C4446314941502D312C444C315943532D31323E484052362C34372C373B342C3130332C343B002E830D1C2383697E"

    private fun stations(vararg frames: com.mantz_it.rfanalyzer.digiext.lora.LoraFrame): Map<String, TrackedObject<MeshComNode>> = runBlocking {
        val store = memoryObjectStore(MeshComNodeFamily)
        for (f in frames) if (!f.objectKey.isNullOrEmpty()) applyMeshComFrame(store, f, f.data as MeshComFrame)
        store.publish()
        store.live.value.associateBy { it.displayName }
    }

    @Test
    fun keysAndTheRelaysDetail() {
        val direct = frame(position, -20.5f)
        assertEquals("MeshCom:DJ1PW-15", direct.objectKey)
        assertNull(direct.secondaryObjectKey)
        val relayed = frame(positionRelayed, -20.2f)
        assertEquals("MeshCom:DJ1PW-15", relayed.objectKey)
        assertEquals("MeshCom:DL1YCS-12", relayed.secondaryObjectKey) // listed in the relay's detail too
        // a corrupted frame (wrong checksum) gets no station, even if the LoRa CRC had passed
        assertEquals("", frame(corrupted, -21f, crcValid = true).objectKey)
    }

    @Test
    fun theOriginatorGetsTheContentTheRelayTheSignal() {
        val s = stations(frame(position, -20.5f), frame(positionRelayed, -18f), frame(gatewayHey, -19.9f), frame(time, -19.5f))
        val dj1pw = s.getValue("DJ1PW-15")
        assertEquals(2, dj1pw.packetCount) // heard itself and relayed
        assertEquals(49 + 16.0 / 60, dj1pw.lat!!, 1e-9)
        assertEquals("Battery Powered", dj1pw.state.position?.comment)
        assertEquals("4.40a", dj1pw.state.firmware)
        assertEquals(-20.5f, dj1pw.state.snrDb) // only from the frame it sent itself
        val dl1ycs = s.getValue("DL1YCS-12")
        assertTrue(dl1ycs.state.isGateway)
        assertEquals(4, dl1ycs.state.neighbours)
        assertEquals(2, dl1ycs.state.relayed) // the position and the time beacon
        assertEquals(-19.5f, dl1ycs.state.snrDb)
        val server = s.getValue("OE1XAR-33")
        assertEquals(MeshComFrameType.TEXT, server.state.lastType)
        assertEquals(MeshComTextKind.TIME, server.state.lastTextKind)
        assertEquals(0, server.state.hardware)
        assertFalse(server.state.isGateway)
        assertNull(server.state.snrDb) // never heard directly
        assertEquals("Server time beacon", meshComRowText(server.state))
    }
}
