package com.mantz_it.rfanalyzer.digiext

import com.mantz_it.rfanalyzer.database.Coordinates
import com.mantz_it.rfanalyzer.database.SourceProvider
import com.mantz_it.rfanalyzer.database.Station
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Mode Presets Tests</h1>
 *
 * Module:      TestModePresets.kt
 * Description: Tests for buildModePresets(): a mode's presets list the nearest station first when
 *              the position is known and go by frequency otherwise, put the last started one first
 *              (it is what Start starts) and the favorites next, start a lone station without a
 *              last start, and offer RDS the FM band plus the favorite FM stations.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestModePresets {

    private fun listed(id: String, mode: DemodulationMode, frequency: Long, lat: Double? = null, lon: Double? = null) = Station(
        remoteHash = "rfa:$id", name = id, mode = mode, frequency = frequency, bandwidth = 20000, source = SourceProvider.DIGITAL_LIST,
        coordinates = if (lat != null && lon != null) Coordinates(lat, lon) else null,
    )

    private val berlin = listed("r09-berlin", DemodulationMode.R09, 170_450_000, 52.52, 13.40)
    private val heidelberg = listed("r09-heidelberg", DemodulationMode.R09, 152_909_800, 49.40, 8.69)
    private val dresden = listed("r09-dresden", DemodulationMode.R09, 170_810_000, 51.05, 13.74)
    private val myR09 = Station(id = 7, name = "Mein R09", mode = DemodulationMode.R09, frequency = 150_000_000, bandwidth = 20000, favorite = true)

    @Test
    fun nearestFirstWithAPosition_frequencyOrderWithout() {
        val stations = listOf(berlin, heidelberg, dresden)
        val near = buildModePresets(stations, emptyMap(), Coordinates(49.41, 8.69)).getValue(DigitalMode.R09)
        assertEquals(listOf("r09-heidelberg", "r09-dresden", "r09-berlin"), near.items.map { it.target.label })
        assertEquals(1, near.items.first().distanceKm)
        val noPosition = buildModePresets(stations, emptyMap(), null).getValue(DigitalMode.R09)
        assertEquals(listOf("r09-heidelberg", "r09-berlin", "r09-dresden"), noPosition.items.map { it.target.label })
        assertNull(noPosition.items.first().distanceKm)
    }

    @Test
    fun lastStartedComesFirstAndIsWhatStartStarts_thenFavorites() {
        val presets = buildModePresets(listOf(berlin, heidelberg, myR09), mapOf(DigitalMode.R09 to "rfa:r09-berlin"), null).getValue(DigitalMode.R09)
        assertEquals(listOf("r09-berlin", "Mein R09", "r09-heidelberg"), presets.items.map { it.target.label })
        assertEquals("rfa:r09-berlin", presets.startTarget!!.target.key)
        assertTrue(presets.items[1].favorite)
    }

    @Test
    fun withoutALastStartStartStartsTheOnlyOne_elseNothing() {
        assertEquals("r09-berlin", buildModePresets(listOf(berlin), emptyMap(), null).getValue(DigitalMode.R09).startTarget!!.target.label)
        assertNull(buildModePresets(listOf(berlin, dresden), emptyMap(), null).getValue(DigitalMode.R09).startTarget)
        // a last start that isn't offered any more (another area) is ignored
        assertNull(buildModePresets(listOf(berlin, dresden), mapOf(DigitalMode.R09 to "rfa:gone"), null).getValue(DigitalMode.R09).startTarget)
    }

    @Test
    fun rdsOffersTheFmBandAndFavoriteFmStationsWithRdsOn() {
        val fm = Station(id = 3, name = "SWR3", mode = DemodulationMode.WFM, frequency = 96_400_000, bandwidth = 95000, favorite = true)
        val rds = buildModePresets(listOf(fm), emptyMap(), null).getValue(DigitalMode.RDS)
        assertEquals(listOf(FM_BAND_TARGET.label, "SWR3"), rds.items.map { it.target.label })
        assertNull(rds.startTarget) // two choices
        assertEquals(FM_BAND_TARGET, buildModePresets(emptyList(), emptyMap(), null).getValue(DigitalMode.RDS).startTarget!!.target)
    }
}
