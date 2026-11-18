package com.mantz_it.rfanalyzer.digiext.callsign

import com.mantz_it.rfanalyzer.util.extractCoreCallsign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - K2K Callsign Parser Tests</h1>
 *
 * Module:      TestK2kCallsignParser.kt
 * Description: Tests for [parseK2kCallsigns], which finds the sending and the called station in
 *              keyboard-to-keyboard text ("DE" exchanges, CQ calls, prefixes with a digit, utility
 *              calls; signal reports are no callsigns), and for [extractCoreCallsign].
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestK2kCallsignParser {

    @Test
    fun testDePattern() {
        val (from, to) = parseK2kCallsigns("W1AW DE K2ABC K2ABC KN")
        assertEquals("K2ABC", from)
        assertEquals("W1AW", to)
    }

    @Test
    fun testDePatternLowercase() {
        val (from, to) = parseK2kCallsigns("w1aw de k2abc kn")
        assertEquals("K2ABC", from)
        assertEquals("W1AW", to)
    }

    @Test
    fun testDePatternEmbeddedInLongerExchange() {
        val (from, to) = parseK2kCallsigns("RST 599 599 BTU W1AW DE K2ABC K2ABC AR")
        assertEquals("K2ABC", from)
        assertEquals("W1AW", to)
    }

    @Test
    fun testCqPatternWithDoubledCallsign() {
        val (from, to) = parseK2kCallsigns("CQ CQ CQ DE K2ABC K2ABC K")
        assertEquals("K2ABC", from)
        assertNull(to)
    }

    @Test
    fun testCqPatternWithoutDe() {
        val (from, to) = parseK2kCallsigns("CQ CQ K2ABC K")
        assertEquals("K2ABC", from)
        assertNull(to)
    }

    @Test
    fun testNoCallsignPresent() {
        val (from, to) = parseK2kCallsigns("THE QUICK BROWN FOX JUMPS OVER THE LAZY DOG")
        assertNull(from)
        assertNull(to)
    }

    @Test
    fun testEmptyText() {
        val (from, to) = parseK2kCallsigns("")
        assertNull(from)
        assertNull(to)
    }

    @Test
    fun testPrefixWithDigit() {
        assertEquals("S51ABC" to "9A1AA", parseK2kCallsigns("9A1AA DE S51ABC S51ABC KN"))
        assertEquals("E73XYZ" to "2E0ABC", parseK2kCallsigns("2E0ABC DE E73XYZ K"))
        assertEquals("3DA0AB" to null, parseK2kCallsigns("CQ CQ DE 3DA0AB 3DA0AB K"))
    }

    @Test
    fun testUtilityCallWithoutSuffix() {
        assertEquals("DDK2" to null, parseK2kCallsigns("CQ CQ CQ DE DDK2 DDK2"))
    }

    @Test
    fun testSignalReportsAreNoCallsigns() {
        assertEquals(null to null, parseK2kCallsigns("UR 5NN 599 DE TU"))
    }

    @Test
    fun testExtractCoreCallsign() {
        assertEquals("S51ABC", extractCoreCallsign("s51abc/p"))
        assertEquals("DL1ABC", extractCoreCallsign("OE/DL1ABC/MM"))
        assertNull(extractCoreCallsign("599"))
    }
}
