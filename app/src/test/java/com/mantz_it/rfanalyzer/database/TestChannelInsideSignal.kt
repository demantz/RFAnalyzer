package com.mantz_it.rfanalyzer.database

import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.channelFrequencyInsideSignal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - Channel Inside Signal Tests</h1>
 *
 * Module:      TestChannelInsideSignal.kt
 * Description: A channel left outside the received signal (another SDR, a band far away) comes into
 *              the view.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestChannelInsideSignal {

    // HackRF at 100 MHz, 4 Msps
    private val start = 98_000_000L
    private val end = 102_000_000L

    @Test
    fun aChannelInsideStays() {
        assertNull(channelFrequencyInsideSignal(99_500_000L, start, end, 100_000_000L, 1_000_000L))
        assertNull(channelFrequencyInsideSignal(end, start, end, 100_000_000L, 1_000_000L))
    }

    @Test
    fun outsideItGoesToTheViewportCentre() {
        // LoRa left at 869.5 MHz, the view on 101 MHz
        assertEquals(101_000_000L, channelFrequencyInsideSignal(869_500_000L, start, end, 101_000_000L, 1_000_000L))
    }

    @Test
    fun aViewportPartlyOutsideIsPulledInFirst() {
        // the view 101.5 MHz +- 1 MHz sticks out on the right: the surface shifts it to 101 MHz
        assertEquals(101_000_000L, channelFrequencyInsideSignal(3_573_000L, start, end, 101_500_000L, 2_000_000L))
        // wider than the signal: the surface narrows it to the signal
        assertEquals(100_000_000L, channelFrequencyInsideSignal(3_573_000L, start, end, 101_000_000L, 10_000_000L))
    }

    @Test
    fun aViewportCompletelyOutsideMeansTheSignalCentre() {
        // FT8 on 3.573 MHz, the view still on 80 m, the RTL-SDR on the 70 cm band
        assertEquals(431_000_000L, channelFrequencyInsideSignal(3_573_000L, 430_000_000L, 432_000_000L, 3_573_000L, 100_000L))
    }
}
