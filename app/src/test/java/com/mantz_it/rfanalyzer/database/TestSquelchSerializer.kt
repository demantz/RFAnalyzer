package com.mantz_it.rfanalyzer.database

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Squelch Serializer Tests</h1>
 *
 * Module:      TestSquelchSerializer.kt
 * Description: Tests for the squelch in the stored DemodulationParameters JSON: an old absolute
 *              threshold is read as the default margin, the margin round-trips without a threshold
 *              field, and a missing squelch stays null.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestSquelchSerializer {

    /** Absolute thresholds (before 2026-11) aren't carried over: the default margin replaces them -
     *  read with a plain Json, which would fail on an unknown key. */
    @Test
    fun oldAbsoluteThresholdsGiveTheDefaultMargin() {
        for (old in listOf(""""thresholdDb":-50.0""", """"thresholdDbfs":-92.5""")) {
            val params = Json.decodeFromString<DemodulationParameters>("""{"squelch":{"enabled":true,$old}}""")
            assertEquals(AppStateRepository.DEFAULT_SQUELCH_MARGIN, params.squelch!!.marginDb, 0f)
            assertTrue(params.squelch.enabled)
        }
    }

    @Test
    fun marginRoundTrips() {
        val params = DemodulationParameters(squelch = DemodulationParameters.Squelch(enabled = true, marginDb = 6.5f))
        val json = Json.encodeToString(params)
        assertTrue(json, json.contains("\"marginDb\":6.5"))
        assertFalse(json, json.contains("threshold"))
        assertEquals(params, Json.decodeFromString<DemodulationParameters>(json))
    }

    @Test
    fun noSquelchStaysNull() {
        val params = Json.decodeFromString<DemodulationParameters>("""{"squelch":null,"pocsag":{"baudRate":1200}}""")
        assertEquals(null, params.squelch)
        assertEquals(1200, params.pocsag!!.baudRate)
    }
}
