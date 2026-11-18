package com.mantz_it.rfanalyzer.digiext.adsb

import com.mantz_it.rfanalyzer.database.COUNTRY_CODE_TO_NAME
import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import com.mantz_it.rfanalyzer.digiext.db.toTrackedObject
import com.mantz_it.rfanalyzer_ipc.digiext.AircraftIdPayload
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - ICAO Address Block Tests</h1>
 *
 * Module:      TestIcaoAddressBlocks.kt
 * Description: [ICAO_BLOCKS] (ICAO Annex 10 Vol III Table 9-1, Amendment 92) and the aircraft's
 *              address kind.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestIcaoAddressBlocks {

    @Test
    fun theTableKeepsTheAnnexInvariants() {
        assertEquals(196, ICAO_BLOCKS.size)
        ICAO_BLOCKS.zipWithNext().forEach { (a, b) -> assertTrue("%06X overlaps %06X".format(a.start, b.start), a.end < b.start) }
        for (block in ICAO_BLOCKS) {
            val size = block.end - block.start + 1
            assertTrue("%06X: a power of two".format(block.start), size > 0 && size and (size - 1) == 0)
            assertTrue("%06X: aligned to its size".format(block.start), block.start % size == 0)
            assertTrue(block.end <= 0xFFFFFF)
        }
        val states = ICAO_BLOCKS.mapNotNull { it.isoCode }
        assertEquals("one block per member state", 193, states.toSet().size)
        assertEquals(193, states.size)
        states.forEach { assertTrue("$it has a name", COUNTRY_CODE_TO_NAME[it] != null) }
        assertEquals(3, ICAO_BLOCKS.count { it.use != null })
    }

    @Test
    fun addressesFindTheirState() {
        assertEquals("DE", icaoBlockOf(0x3C6444)?.isoCode)   // Lufthansa
        assertEquals("CH", icaoBlockOf(0x4B1A00)?.isoCode)
        assertEquals("GB", icaoBlockOf(0x400000)?.isoCode)   // first address of the block
        assertEquals("GB", icaoBlockOf(0x43FFFF)?.isoCode)   // last one
        assertEquals("US", icaoBlockOf(0xA12345)?.isoCode)
        assertEquals("RU", icaoBlockOf(0x140000)?.isoCode)
        assertEquals("ZW", icaoBlockOf(0x004000)?.isoCode)   // the first block
        assertEquals("RS", icaoBlockOf(0x4C0000)?.isoCode)   // Yugoslavia's block went to Serbia
        assertEquals("🇩🇪 Germany", icaoBlockOf(0x3C6444)?.text)
        // ICAO's own blocks have no flag
        assertEquals(IcaoUse.TEMPORARY, icaoBlockOf(0xF00000)?.use)
        assertNull(icaoBlockOf(0xF00000)?.flag)
        assertEquals(IcaoUse.SPECIAL_USE, icaoBlockOf(0x899000)?.use)
        // unallocated, or not an address
        assertNull(icaoBlockOf(0x000000))
        assertNull(icaoBlockOf(0xFFFFFF))
        assertNull(icaoBlockOf(-1))
        assertNull(icaoBlockOf(0x1000000))
    }

    /** A DF 18 frame of another code format than 0 says nothing about a country - unless the address
     *  was, or later is, heard as an ICAO address. */
    @Test
    fun onlyAnIcaoAddressHasACountry() = runBlocking {
        fun frame(firstByte: Int, ts: Long) = AdsbSpec.packet(
            timestampMs = ts, frequencyHz = 1_090_000_000L, phy = Unit, data = AircraftIdPayload(4, 0, "TEST1"),
            raw = Bytes(byteArrayOf(firstByte.toByte(), 0x3C, 0x64, 0x44) + ByteArray(10)),
        )
        val df17 = 0x8D          // DF 17, CA 5
        val df18Cf0 = 0x90       // DF 18, CF 0: an ICAO address
        val df18Cf2 = 0x92       // DF 18, CF 2: fine TIS-B, the address may be a track number
        val store = memoryObjectStore(AdsbAircraftFamily)
        suspend fun state(f: AdsbFrame) = applyAdsbFrame(store, f).let { store.dirtyEntities().single().toTrackedObject(AdsbAircraftFamily) }

        state(frame(df18Cf2, 1_000)).let { assertEquals(false, it.state.addressIsIcao); assertNull(it.state.allocation(it.key)) }
        state(frame(df17, 2_000)).let { assertEquals(true, it.state.addressIsIcao); assertEquals("DE", it.state.allocation(it.key)?.isoCode) }
        state(frame(df18Cf2, 3_000)).let { assertEquals("stays an ICAO address", true, it.state.addressIsIcao) }
        assertEquals(true, frame(df18Cf0, 0).hasIcaoAddress)
        assertEquals(false, frame(df18Cf2, 0).hasIcaoAddress)
        // an aircraft stored before the field existed keeps its flag
        assertEquals("DE", AdsbAircraft().allocation("3C6444")?.isoCode)
    }
}
