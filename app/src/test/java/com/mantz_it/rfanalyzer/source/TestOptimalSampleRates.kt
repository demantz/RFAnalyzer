package com.mantz_it.rfanalyzer.source

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - Optimal Sample Rate Tests</h1>
 *
 * Module:      TestOptimalSampleRates.kt
 * Description: IQSourceInterface's default next higher/lower optimal sample rate, from
 *              getSupportedSampleRates().
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestOptimalSampleRates {

    private val rtltcp = RtltcpSource("localhost", 1234) // 1, 1.4, 1.8, 2, 2.4, 2.5 MSps

    @Test
    fun higherIsTheNextSupportedRateAboveOrTheHighest() {
        assertEquals(1_000_000, rtltcp.getNextHigherOptimalSampleRate(500_000))
        assertEquals(1_400_000, rtltcp.getNextHigherOptimalSampleRate(1_000_000))
        assertEquals(2_400_000, rtltcp.getNextHigherOptimalSampleRate(2_200_000))
        assertEquals(2_500_000, rtltcp.getNextHigherOptimalSampleRate(2_500_000))
        assertEquals(2_500_000, rtltcp.getNextHigherOptimalSampleRate(9_000_000))
    }

    @Test
    fun lowerIsTheNextSupportedRateBelowOrTheLowest() {
        assertEquals(2_400_000, rtltcp.getNextLowerOptimalSampleRate(2_500_000))
        assertEquals(2_000_000, rtltcp.getNextLowerOptimalSampleRate(2_200_000))
        assertEquals(1_800_000, rtltcp.getNextLowerOptimalSampleRate(2_000_000))
        assertEquals(1_000_000, rtltcp.getNextLowerOptimalSampleRate(1_000_000))
        assertEquals(2_500_000, rtltcp.getNextLowerOptimalSampleRate(9_000_000))
    }

    @Test
    fun aSingleRateSourceAlwaysAnswersItsRate() {
        val file = FileIQSource() // 1 MSps until a file is opened
        for (rate in intArrayOf(1, 999_999, 1_000_000, 1_000_001, 20_000_000)) {
            assertEquals(1_000_000, file.getNextHigherOptimalSampleRate(rate))
            assertEquals(1_000_000, file.getNextLowerOptimalSampleRate(rate))
        }
    }
}
