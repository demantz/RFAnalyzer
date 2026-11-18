package com.mantz_it.rfanalyzer.digiext.adsb

import com.mantz_it.rfanalyzer_ipc.digiext.AirbornePositionPayload
import com.mantz_it.rfanalyzer_ipc.digiext.AirborneVelocityPayload
import com.mantz_it.rfanalyzer_ipc.digiext.AircraftIdPayload
import com.mantz_it.rfanalyzer_ipc.digiext.UnknownAdsbPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - ADS-B Payload Formatting Tests</h1>
 *
 * Module:      TestAdsbPayloadFormatting.kt
 * Description: Tests for [compactAdsbPayloadText] and [flightLevel] - a frame's values in words.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestAdsbPayloadFormatting {

    @Test
    fun aircraftIdIncludesTheCallsign() {
        assertTrue(compactAdsbPayloadText(AircraftIdPayload(4, 0, "SWISS123 ")).startsWith("SWISS123"))
    }

    @Test
    fun airbornePositionShowsFlightLevelAndMeters() {
        val text = compactAdsbPayloadText(AirbornePositionPayload(11, 35000, "BARO", false, 0, 0))
        assertTrue(text, text.contains("FL350"))
        assertTrue(text, text.contains("10668 m"))
    }

    @Test
    fun airbornePositionHandlesMissingAltitude() {
        assertEquals("Altitude unknown", compactAdsbPayloadText(AirbornePositionPayload(11, null, "BARO", false, 0, 0)))
    }

    @Test
    fun airborneVelocityShowsSpeedTrackAndVerticalRate() {
        val text = compactAdsbPayloadText(AirborneVelocityPayload(1, 450, 90.0, 1200, "BARO"))
        assertEquals("450 kt · 90° · +1200 ft/min", text)
    }

    @Test
    fun unknownPayloadNamesTheTypeCode() {
        assertEquals("Type code 0", compactAdsbPayloadText(UnknownAdsbPayload(0)))
        assertEquals("Not an extended squitter", compactAdsbPayloadText(UnknownAdsbPayload(-1)))
    }

    @Test
    fun flightLevelRoundsToHundredsOfFeet() {
        assertEquals("FL316", flightLevel(31_600))
        assertEquals("FL010", flightLevel(1_000))
        assertEquals("800 ft", flightLevel(800))
    }
}
