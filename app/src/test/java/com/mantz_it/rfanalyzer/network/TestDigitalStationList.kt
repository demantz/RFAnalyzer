package com.mantz_it.rfanalyzer.network

import com.mantz_it.rfanalyzer.database.SourceProvider
import com.mantz_it.rfanalyzer.location.CountrySource
import com.mantz_it.rfanalyzer.location.ItuRegion
import com.mantz_it.rfanalyzer.location.UserLocation
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer_ipc.LoraIqMode
import com.mantz_it.rfanalyzer_ipc.RttyPolarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * <h1>RF Analyzer - Digital Station List Tests</h1>
 *
 * Module:      TestDigitalStationList.kt
 * Description: Tests for [DigitalStationList]: the bundled list is readable, an entry applies by
 *              country, ITU region or to everyone, rows carry their demodulation parameters and
 *              location and keep their favorites, a newer format or garbage is ignored, and the
 *              area key changes only with the country or region.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestDigitalStationList {

    private val germany = UserLocation(countryCode = "DE", countrySource = CountrySource.NETWORK, ituRegion = ItuRegion.REGION_1)
    private val usa = UserLocation(countryCode = "US", countrySource = CountrySource.CHOSEN, ituRegion = ItuRegion.REGION_2)
    private val region1Only = UserLocation(ituRegion = ItuRegion.REGION_1)

    private fun entry(id: String, regions: List<Int> = emptyList(), countries: List<String> = emptyList()) =
        StationListEntry(id = id, name = id, mode = "FT8", frequency = 14_074_000, bandwidth = 3000, regions = regions, countries = countries)

    @Test
    fun theBundledListIsReadable() {
        // unit tests run in the module directory
        val list = DigitalStationList.parse(File("src/main/assets/${DigitalStationList.ASSET_PATH}").readBytes())
        assertNotNull(list)
        assertTrue(list!!.stations.size > 50)
        assertEquals(list.stations.size, list.stations.map { it.id }.toSet().size)
        assertTrue(list.stations.all { s -> DemodulationMode.entries.any { it.name == s.mode } })
    }

    @Test
    fun entriesApplyByCountryRegionOrToEveryone() {
        val world = entry("world")
        val europe = entry("europe", regions = listOf(1))
        val berlin = entry("berlin", countries = listOf("DE"))
        val anz = entry("anz", countries = listOf("AU", "NZ"))
        assertTrue(DigitalStationList.appliesTo(world, germany))
        assertTrue(DigitalStationList.appliesTo(europe, germany))
        assertTrue(DigitalStationList.appliesTo(berlin, germany))
        assertFalse(DigitalStationList.appliesTo(anz, germany))
        assertFalse(DigitalStationList.appliesTo(europe, usa))
        assertFalse(DigitalStationList.appliesTo(berlin, usa))
        // a region alone takes the countries in it
        assertTrue(DigitalStationList.appliesTo(berlin, region1Only))
        assertFalse(DigitalStationList.appliesTo(anz, region1Only))
        // nothing known: only what is for everyone
        assertTrue(DigitalStationList.appliesTo(world, UserLocation()))
        assertFalse(DigitalStationList.appliesTo(europe, UserLocation()))
        assertFalse(DigitalStationList.appliesTo(berlin, UserLocation()))
    }

    @Test
    fun rowsCarryParamsAndKeepFavorites() {
        val list = DigitalStationList.parse("""
            {"format":1,"version":5,"stations":[
              {"id":"dwd","name":"DWD","mode":"RTTY","frequency":10100800,"bandwidth":450,"regions":[1],
               "params":{"rtty":{"baudRate":50.0,"frequencyShift":450.0,"polarity":"REVERSED"}},"notes":"n"},
              {"id":"downlink","name":"Down","mode":"LORA","frequency":869525000,"bandwidth":125000,"regions":[1],
               "params":{"lora":{"iqMode":"INVERTED"}},"location":{"lat":49.4,"lon":8.7}},
              {"id":"future","name":"F","mode":"DMR","frequency":1,"bandwidth":1}
            ]}""".toByteArray())!!
        val first = DigitalStationList.toStations(list, germany, emptyList(), 1000)
        assertEquals(listOf("DWD", "Down"), first.map { it.name }) // the unknown mode is left out
        val dwd = first[0]
        assertEquals(SourceProvider.DIGITAL_LIST, dwd.source)
        assertEquals(DemodulationMode.RTTY, dwd.mode)
        assertEquals(RttyPolarity.REVERSED, dwd.demodulationParameters!!.rtty!!.polarity)
        assertEquals(50f, dwd.demodulationParameters!!.rtty!!.baudRate)
        assertNull(dwd.demodulationParameters!!.squelch)
        assertEquals(LoraIqMode.INVERTED, first[1].demodulationParameters!!.lora!!.iqMode)
        assertEquals(49.4, first[1].coordinates!!.latitude, 1e-9)

        val again = DigitalStationList.toStations(list, germany, listOf(dwd.copy(favorite = true)), 2000)
        assertTrue(again[0].favorite)
        assertFalse(again[1].favorite)
        assertEquals(dwd.remoteHash, again[0].remoteHash)
    }

    @Test
    fun aNewerFormatOrGarbageIsIgnored() {
        assertNull(DigitalStationList.parse("""{"format":2,"version":9,"stations":[]}""".toByteArray()))
        assertNull(DigitalStationList.parse("<html>".toByteArray()))
        assertNotNull(DigitalStationList.parse("""{"format":1,"version":9,"stations":[],"later":"field"}""".toByteArray()))
    }

    @Test
    fun areaKeyChangesWithCountryOrRegionOnly() {
        assertEquals("R1:DE", DigitalStationList.areaKey(germany))
        assertEquals(DigitalStationList.areaKey(germany), DigitalStationList.areaKey(germany.copy(countrySource = CountrySource.CHOSEN)))
        assertEquals("R1:", DigitalStationList.areaKey(region1Only))
        assertEquals("R0:", DigitalStationList.areaKey(UserLocation()))
    }

    @Test
    fun aDownloadInANewerFormatIsNoErrorButNoStationListIs() {
        // a newer format: keep the current list quietly
        assertNull(DigitalStationList.fromDownload("""{"format":2,"version":9,"stations":[]}""".toByteArray()))
        assertNotNull(DigitalStationList.fromDownload("""{"format":1,"version":9,"stations":[]}""".toByteArray()))
        assertThrows(java.io.IOException::class.java) { DigitalStationList.fromDownload("<html>".toByteArray()) }
    }
}
