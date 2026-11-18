package com.mantz_it.rfanalyzer.ui.composable

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - Frequency Range String Tests</h1>
 *
 * Module:      TestFrequencyRangeString.kt
 * Description: Tests for [frequencyRangeString]: both ends of a frequency range are written in the
 *              unit of the larger one.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestFrequencyRangeString {
    @Test
    fun bothEndsUseTheUnitOfTheLargerOne() {
        assertEquals("446.0125 - 446.025 MHz", frequencyRangeString(446_012_500, 446_025_000))
        assertEquals("446 - 446.2 MHz", frequencyRangeString(446_000_000, 446_200_000))
        assertEquals("26.96 - 27.41 MHz", frequencyRangeString(26_960_000, 27_410_000))
        assertEquals("135.7 - 137.8 kHz", frequencyRangeString(135_700, 137_800))
        assertEquals("0.9995 - 1.0005 MHz", frequencyRangeString(999_500, 1_000_500))
        assertEquals("10 - 10.5 GHz", frequencyRangeString(10_000_000_000, 10_500_000_000))
        assertEquals("0 - 500 Hz", frequencyRangeString(0, 500))
    }
}
