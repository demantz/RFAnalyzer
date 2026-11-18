package com.mantz_it.rfanalyzer.network

import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - SSB Mode and Spot Tuning Tests</h1>
 *
 * Module:      SsbModeForFrequencyTest.kt
 * Description: Tests for [ssbModeForFrequency] (LSB below 10 MHz, USB above it and on 60 m) and
 *              [spotTuning]: the mode and frequency a spot is tuned to - the digital modes that
 *              RF Analyzer demodulates get their mode, an FT8 spot moves to its band's dial
 *              frequency, other digital modes get USB, an unknown mode keeps the current one.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class SsbModeForFrequencyTest {
    @Test
    fun lowerSidebandBelowTenMegahertz() {
        assertEquals(DemodulationMode.LSB, ssbModeForFrequency(3_760_000.0))
        assertEquals(DemodulationMode.LSB, ssbModeForFrequency(7_150_000.0))
    }

    @Test
    fun upperSidebandAboveTenMegahertzAndOnSixtyMetres() {
        assertEquals(DemodulationMode.USB, ssbModeForFrequency(14_285_000.0))
        assertEquals(DemodulationMode.USB, ssbModeForFrequency(5_357_000.0))
    }
}

class SpotTuningTest {
    @Test
    fun digitalModesRfAnalyzerDemodulatesGetTheirMode() {
        assertEquals(DemodulationMode.RTTY to 14_085_000.0, spotTuning("RTTY", 14_085_000.0))
        assertEquals(DemodulationMode.PSK31 to 14_070_500.0, spotTuning("PSK31", 14_070_500.0))
    }

    /** The FT8 channel starts at the dial: a spot with the signal's own frequency is moved there. */
    @Test
    fun ft8SpotsAreTunedToTheirBandsDial() {
        assertEquals(DemodulationMode.FT8 to 14_074_000.0, spotTuning("FT8", 14_075_350.0))
        assertEquals(DemodulationMode.FT8 to 7_074_000.0, spotTuning("FT8", 7_074_000.0))
        assertEquals(DemodulationMode.FT8 to 14_090_000.0, spotTuning("FT8", 14_090_000.0)) // off the dial: as spotted
    }

    @Test
    fun otherDigitalModesGetUsbAndUnknownOnesKeepTheMode() {
        assertEquals(DemodulationMode.USB, spotTuning("FT4", 14_080_000.0).first)
        assertEquals(DemodulationMode.USB, spotTuning("data", 7_078_000.0).first)
        assertEquals(DemodulationMode.OFF, spotTuning("", 7_078_000.0).first)
    }
}
