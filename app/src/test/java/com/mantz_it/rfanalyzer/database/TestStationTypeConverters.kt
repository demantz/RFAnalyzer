package com.mantz_it.rfanalyzer.database

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * <h1>RF Analyzer - Station Type Converters Tests</h1>
 *
 * Module:      TestStationTypeConverters.kt
 * Description: A bookmark written by a newer build (or restored from its backup) still reads.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestStationTypeConverters {

    private val converters = StationTypeConverters()

    @Test
    fun unknownFieldsAreSkipped() {
        val params = converters.toDemodulationParameters(
            """{"squelch":{"enabled":true,"thresholdDbfs":-80.0},"pocsag":{"baudRate":1200,"futureSetting":3},"futureMode":{"x":1}}"""
        )
        assertNotNull(params)
        assertEquals(1200, params!!.pocsag?.baudRate)
        assertEquals(52.5, converters.toCoordinates("""{"latitude":52.5,"longitude":13.4,"altitude":30}""")!!.latitude, 0.0)
    }
}
