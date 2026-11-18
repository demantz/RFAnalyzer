package com.mantz_it.rfanalyzer.digiext.ft8

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - FT8 Formatting Tests</h1>
 *
 * Module:      TestFt8Formatting.kt
 * Description: Tests for [formatFt8Snr]: the SNR is rounded before its sign is chosen, so a value
 *              that rounds to zero reads "+0", never "-0".
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestFt8Formatting {

    /** A value that rounds to zero must not keep its sign: "-0 dB" is not a level. */
    @Test
    fun snrRoundsBeforeTheSignIsChosen() {
        assertEquals("+0", formatFt8Snr(-0.4f))
        assertEquals("+0", formatFt8Snr(0.4f))
        assertEquals("-1", formatFt8Snr(-0.6f))
        assertEquals("+8", formatFt8Snr(7.5f))
        assertEquals("-13", formatFt8Snr(-13f))
    }
}
