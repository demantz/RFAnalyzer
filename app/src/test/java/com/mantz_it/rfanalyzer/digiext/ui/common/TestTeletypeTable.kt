package com.mantz_it.rfanalyzer.digiext.ui.common

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Teletype Table Tests</h1>
 *
 * Module:      TestTeletypeTable.kt
 * Description: Tests for [isTeletypeTable]: a teletype text of several short lines (a weather
 *              report) is laid out as a table, a chat with long lines and a text of one or two
 *              lines are not.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestTeletypeTable {

    @Test
    fun weatherReportIsATable() {
        val report = """
            ZCZC 123
            SXDL31 DWAV 091200
            STATION      WIND   SICHT  WETTER   TEMP
            HELGOLAND    W 5    20 KM  BEDECKT  12
            ARKONA       SW 4   15 KM  REGEN    10
            NNNN
        """.trimIndent()
        assertTrue(isTeletypeTable(report))
    }

    @Test
    fun chatWithLongLinesWraps() {
        val chat = "CQ CQ DE DL1ABC " + "TNX FER THE NICE QSO ".repeat(10) + "\nNAME IS JOE\nQTH NR MUNICH"
        assertFalse(isTeletypeTable(chat))
    }

    @Test
    fun oneOrTwoLinesAreNoTable() {
        assertFalse(isTeletypeTable("CQ CQ DE DL1ABC\nPSE K"))
    }
}
