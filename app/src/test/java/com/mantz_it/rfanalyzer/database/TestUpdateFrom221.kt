package com.mantz_it.rfanalyzer.database

import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.tabs.normalizeChannelWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - Update From 2.2.1 Tests</h1>
 *
 * Module:      TestUpdateFrom221.kt
 * Description: Values a 2.2.1 user brings along that 2.3 reads differently.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestUpdateFrom221 {

    @Test
    fun the221VerticalScaleIsConvertedToDbfs() {
        // 2.2.1 showed 10*log10(|X|/N); its default scale -60..-10 lands near today's -110..-20
        assertEquals(-112.5f, AppStateRepository.legacyDbToDbfs(-60f), 0.001f)
        assertEquals(-12.5f, AppStateRepository.legacyDbToDbfs(-10f), 0.001f)
        // and stays a usable scale
        val (min, max) = AppStateRepository.sanitizeVerticalScale(AppStateRepository.legacyDbToDbfs(-60f), AppStateRepository.legacyDbToDbfs(-10f))
        assertEquals(-112.5f, min, 0.001f)
        assertEquals(-12.5f, max, 0.001f)
    }

    @Test
    fun channelWidthsOutsideTodaysRangesAreNormalized() {
        // 2.2.1: WFM up to 150 kHz (default 100), NFM down to 3 kHz (default 10)
        assertEquals(DemodulationMode.WFM.maxChannelWidth, DemodulationMode.WFM.normalizeChannelWidth(150_000))
        assertEquals(DemodulationMode.WFM.maxChannelWidth, DemodulationMode.WFM.normalizeChannelWidth(100_000))
        assertEquals(DemodulationMode.NFM.minChannelWidth, DemodulationMode.NFM.normalizeChannelWidth(3_000))
        assertEquals(10_000, DemodulationMode.NFM.normalizeChannelWidth(10_000)) // inside: kept
    }

    @Test
    fun maidenheadLocators() {
        val (lat4, lon4) = Coordinates.maidenheadToLatLon("JN49")!!
        assertEquals(49.5, lat4, 1e-9); assertEquals(9.0, lon4, 1e-9)
        val (lat6, lon6) = Coordinates.maidenheadToLatLon("jn49HL")!!
        assertEquals(49.0 + 11 / 24.0 + 1 / 48.0, lat6, 1e-9)
        assertEquals(8.0 + 7 * 2 / 24.0 + 1 / 24.0, lon6, 1e-9)
        val (lat8, lon8) = Coordinates.maidenheadToLatLon("PM95vq52")!!
        assertEquals(35.0 + 16 / 24.0 + 2 / 240.0 + 1 / 480.0, lat8, 1e-9)
        assertEquals(138.0 + 21 * 2 / 24.0 + 5 * 2 / 240.0 + 1 / 240.0, lon8, 1e-9)
        // half-typed or invalid input: null, never an exception (the bookmark editor checks as you type)
        for (bad in listOf("", "JN4", "JNAB", "JN4!", "JN49h", "ZZ99", "JN49yy", "1234", "JN49hl3"))
            assertNull(bad, Coordinates.maidenheadToLatLon(bad))
    }
}
