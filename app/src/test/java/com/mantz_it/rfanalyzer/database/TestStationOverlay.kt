package com.mantz_it.rfanalyzer.database

import com.mantz_it.rfanalyzer.location.ItuRegion
import com.mantz_it.rfanalyzer.location.UserLocation
import com.mantz_it.rfanalyzer.network.DigitalStationList
import com.mantz_it.rfanalyzer.ui.toTuneTarget
import java.io.File
import com.mantz_it.rfanalyzer_ipc.LoraIqMode
import com.mantz_it.rfanalyzer_ipc.LoraSpreadingFactors
import com.mantz_it.rfanalyzer_ipc.RttyPolarity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Station Overlay Tests</h1>
 *
 * Module:      TestStationOverlay.kt
 * Description: What a tuned station lays over the settings (StationOverlay.kt). The overlay's life
 *              cycle lives in AppStateRepository and is not covered here.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestStationOverlay {

    @Test
    fun bookmarkParametersBecomeTheOverlayButNotTheAx25Variant() {
        val params = DemodulationParameters(
            squelch = DemodulationParameters.Squelch(enabled = true, marginDb = 6f),
            rtty = DemodulationParameters.Rtty(baudRate = 50f, frequencyShift = 450f, polarity = RttyPolarity.REVERSED),
            ax25 = DemodulationParameters.Ax25(baudRate = 300),
            lora = DemodulationParameters.Lora(spreadingFactors = LoraSpreadingFactors.of(8), iqMode = LoraIqMode.NORMAL),
        ).toTemporaryParameters()
        assertEquals(true, params.squelchEnabled)
        assertEquals(6f, params.squelchMarginDb)
        assertEquals(50f, params.rttyBaudRate)
        assertEquals(RttyPolarity.REVERSED, params.rttyPolarity)
        assertEquals(LoraSpreadingFactors.of(8), params.loraSpreadingFactors)
        assertEquals(LoraIqMode.NORMAL, params.loraIqMode)
        assertNull(params.pocsagBaudRate)
    }

    /** A disabled squelch switches the squelch off, but carries no threshold. */
    @Test
    fun aDisabledSquelchCarriesNoThreshold() {
        val params = DemodulationParameters(squelch = DemodulationParameters.Squelch(enabled = false, marginDb = 6f)).toTemporaryParameters()
        assertEquals(false, params.squelchEnabled)
        assertNull(params.squelchMarginDb)
    }

    @Test
    fun releasingEveryValueEmptiesTheOverlay() {
        val params = TemporaryParameters(rttyBaudRate = 50f)
        assertTrue(params.copy(rttyBaudRate = null).isEmpty())
    }

    @Test
    fun toleranceIsHalfTheBandwidthButAtLeast500Hz() {
        assertEquals(6_250L, StationOverlay.toleranceFor(12_500))
        assertEquals(500L, StationOverlay.toleranceFor(450))
    }

    /** The Digital Stations list's entries that fix parameters do so for their own channel only. */
    @Test
    fun listEntriesWithFixedParametersUseTheOverlay() {
        val list = DigitalStationList.parse(File("src/main/assets/${DigitalStationList.ASSET_PATH}").readBytes())!!
        val germany = UserLocation(countryCode = "DE", ituRegion = ItuRegion.REGION_1)
        val stations = DigitalStationList.toStations(list, germany, emptyList(), 0)
        val dwd = stations.first { it.name.startsWith("DWD") && it.frequency == 10_100_800L }.toTuneTarget()
        assertEquals(50f, dwd.overlay?.rttyBaudRate)
        assertEquals(RttyPolarity.REVERSED, dwd.overlay?.rttyPolarity)
        val downlink = stations.single { it.name.contains("EU868") && it.name.contains("Downlink") }.toTuneTarget()
        assertEquals(LoraIqMode.INVERTED, downlink.overlay?.loraIqMode)
    }
}
