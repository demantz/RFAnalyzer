package com.mantz_it.rfanalyzer.database

import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.DEFAULT_VERTICAL_SCALE_MAX
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.DEFAULT_VERTICAL_SCALE_MIN
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.VERTICAL_SCALE_LOWER_BOUNDARY
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.VERTICAL_SCALE_UPPER_BOUNDARY
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.sanitizeVerticalScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Sanitize Vertical Scale Tests</h1>
 *
 * Module:      TestSanitizeVerticalScale.kt
 * Description: Tests for AppStateRepository.sanitizeVerticalScale(): a valid scale stays unchanged,
 *              a non-finite one falls back to the defaults, and any input gives a slider range with
 *              min < max inside the scale boundaries.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestSanitizeVerticalScale {

    private val defaults = Pair(DEFAULT_VERTICAL_SCALE_MIN, DEFAULT_VERTICAL_SCALE_MAX)

    @Test
    fun validScaleIsUnchanged() {
        assertEquals(Pair(-60f, 0f), sanitizeVerticalScale(-60f, 0f))
        assertEquals(Pair(VERTICAL_SCALE_LOWER_BOUNDARY, VERTICAL_SCALE_UPPER_BOUNDARY),
            sanitizeVerticalScale(VERTICAL_SCALE_LOWER_BOUNDARY, VERTICAL_SCALE_UPPER_BOUNDARY))
    }

    @Test
    fun nonFiniteScaleFallsBackToDefaults() {
        assertEquals(defaults, sanitizeVerticalScale(Float.NaN, Float.NaN))
        assertEquals(defaults, sanitizeVerticalScale(-60f, Float.NaN))
        assertEquals(defaults, sanitizeVerticalScale(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY))
        assertEquals(defaults, sanitizeVerticalScale(Float.NEGATIVE_INFINITY, 0f))
    }

    @Test
    fun resultIsAlwaysAUsableSliderRange() {
        val values = listOf(-1000f, -101f, -100f, -50f, -0.5f, 0f, 9.5f, 10f, 11f, 1000f)
        for (min in values) for (max in values) {
            val (sMin, sMax) = sanitizeVerticalScale(min, max)
            assertTrue("($min, $max) -> ($sMin, $sMax)", sMin < sMax)
            assertTrue(sMin >= VERTICAL_SCALE_LOWER_BOUNDARY && sMax <= VERTICAL_SCALE_UPPER_BOUNDARY)
        }
    }
}
