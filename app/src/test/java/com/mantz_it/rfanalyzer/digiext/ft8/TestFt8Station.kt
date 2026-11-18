package com.mantz_it.rfanalyzer.digiext.ft8

import com.mantz_it.rfanalyzer.digiext.db.liveObject
import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.Ft8Phy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - FT8 Station Tests</h1>
 *
 * Module:      TestFt8Station.kt
 * Description: The station an FT8 station last called ([Ft8Station.lastCalledCallsign]) is a
 *              callsign, never a token.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestFt8Station {

    private val dl1abc = Callsign("DL1ABC", standard = true)

    private fun decode(call1: Callsign?, timestampMs: Long, report: Report? = LocationReport("JN49")) = Ft8Spec.packet(
        timestampMs = timestampMs, frequencyHz = 14_075_000, raw = Bytes(ByteArray(12)), phy = Ft8Phy(snr = -10f),
        data = StandardMessage(call1, rover1 = false, call2 = dl1abc, rover2 = false, roger = false, report = report),
    )

    private fun lastCalledAfter(vararg decodes: Ft8Decode): String? = runBlocking {
        val store = memoryObjectStore(Ft8StationFamily)
        decodes.forEach { applyFt8Decode(store, it) }
        store.liveObject("DL1ABC").state.lastCalledCallsign
    }

    @Test
    fun aCqCallsNobody() {
        listOf("CQ", "QRZ", "DE", "CQ DX", "CQ NA", "CQ 123").forEach { token ->
            val cq = decode(Callsign(token, token = true), timestampMs = 1_000)
            assertNull(token, lastCalledAfter(cq))
            assertEquals(token, "", cq.secondaryObjectKey)
            assertEquals(token, "DL1ABC", cq.objectKey)
        }
    }

    @Test
    fun theLastCalledStationSurvivesALaterCq() {
        val answer = decode(Callsign("K1ABC", standard = true), timestampMs = 1_000, report = SignalReport(-12))
        val cq = decode(Callsign("CQ", token = true), timestampMs = 16_000)
        assertEquals("K1ABC", answer.secondaryObjectKey)
        assertEquals("K1ABC", lastCalledAfter(answer, cq))
    }

    /** A hashed call is kept as it is stored (the call an earlier message had with that hash); a
     *  hash nobody was heard with names no one. */
    @Test
    fun aHashedCallIsAStationOnlyWhenItIsKnown() {
        assertEquals("PJ4/K1ABC", lastCalledAfter(decode(Callsign("PJ4/K1ABC", fromHash = true), timestampMs = 1_000)))
        val unknown = decode(Callsign("...", fromHash = true), timestampMs = 1_000)
        assertNull(lastCalledAfter(unknown))
        assertEquals("", unknown.secondaryObjectKey)
    }
}
