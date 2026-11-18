package com.mantz_it.rfanalyzer.ui.tabs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - Port Field Tests</h1>
 *
 * Module:      TestPortField.kt
 * Description: The rtl_tcp and KiwiSDR port fields pass only a valid port on to the setting.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestPortField {
    @Test
    fun emptyAndOutOfRangeEntriesAreNoPort() {
        assertNull(parsePort(""))       // the field emptied before typing a new port
        assertNull(parsePort("0"))
        assertNull(parsePort("65536"))
    }

    @Test
    fun validPortsPass() {
        assertEquals(1, parsePort("1"))
        assertEquals(1234, parsePort("1234"))
        assertEquals(65535, parsePort("65535"))
    }
}
