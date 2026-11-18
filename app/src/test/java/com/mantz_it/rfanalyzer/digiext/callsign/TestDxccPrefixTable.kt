package com.mantz_it.rfanalyzer.digiext.callsign

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - DXCC Prefix Table Tests</h1>
 *
 * Module:      TestDxccPrefixTable.kt
 * Description: Tests for the DXCC prefix table: callsigns resolve to their country, continent and
 *              flag by the longest matching prefix (whole ITU blocks and split series included),
 *              calling tokens such as "CQ" get no flag, no prefix is listed for two countries, and
 *              [normalizeCallsignForCountryLookup] strips modifier suffixes.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestDxccPrefixTable {

    @Test
    fun resolvesKnownCallsignsToTheirCountry() {
        assertEquals("US", dxccCountryOf("W1AW")?.isoCode)
        assertEquals("DE", dxccCountryOf("DL1ABC")?.isoCode)
        assertEquals("JP", dxccCountryOf("JA1XYZ")?.isoCode)
        assertEquals("AU", dxccCountryOf("VK2ABC")?.isoCode)
        assertEquals("BR", dxccCountryOf("PY2XYZ")?.isoCode)
        assertEquals("ZA", dxccCountryOf("ZS6ABC")?.isoCode)
    }

    @Test
    fun resolvesTheFullDxccEntry() {
        val entry = dxccCountryOf("DL1ABC")
        assertEquals("Germany", entry?.countryName)
        assertEquals("DE", entry?.isoCode)
        assertEquals("EU", entry?.continent)
    }

    /** The longest matching prefix decides. */
    @Test
    fun longestPrefixWins() {
        assertEquals("SE", dxccCountryOf("SM0ABC")?.isoCode)
        // "SP" (Poland) vs "S5" (Slovenia) vs "SV" (Greece) must not cross-match each other.
        assertEquals("PL", dxccCountryOf("SP5ABC")?.isoCode)
        assertEquals("SI", dxccCountryOf("S51ABC")?.isoCode)
        assertEquals("GR", dxccCountryOf("SV1ABC")?.isoCode)
    }

    @Test
    fun unknownPrefixReturnsNull() {
        // "Q" is not an ITU-allocated first letter for any amateur callsign, so nothing in the
        // table can match it - unlike "Z", which collides with Brazil's "ZZ" prefix.
        assertNull(dxccCountryOf("QQQQQQ99"))
    }

    @Test
    fun isCaseInsensitive() {
        assertEquals(dxccCountryOf("dl1abc"), dxccCountryOf("DL1ABC"))
    }

    // ------------------------------------------------------------------
    // normalizeCallsignForCountryLookup
    // ------------------------------------------------------------------

    @Test
    fun normalizeStripsAModifierSuffix() {
        assertEquals("DL1ABC", normalizeCallsignForCountryLookup("DL1ABC/P"))
        assertEquals("DL1ABC", normalizeCallsignForCountryLookup("DL1ABC/QRP"))
    }

    @Test
    fun normalizeUppercasesAndTrims() {
        assertEquals("DL1ABC", normalizeCallsignForCountryLookup("  dl1abc  "))
    }

    @Test
    fun normalizeHandlesAPlainCallsignUnchanged() {
        assertEquals("W1AW", normalizeCallsignForCountryLookup("W1AW"))
    }

    /** A callsign with a slash still resolves to *a* country (the segment before the slash) -
     *  see normalizeCallsignForCountryLookup's doc for the known limitation on which segment
     *  that should be for the reverse ("location-prefix/homecall") compound form. */
    @Test
    fun compoundCallStillResolvesToSomeCountry() {
        assertEquals("DE", dxccCountryOf("DL1ABC/P")?.isoCode)
    }

    @Test
    fun aCallsignGetsItsCountrysFlag() {
        assertEquals("\uD83C\uDDE9\uD83C\uDDEA", callsignFlag("DL1ABC"))
        assertEquals("\uD83C\uDDE9\uD83C\uDDEA DL1ABC", withFlag("DL1ABC"))
    }

    /** FT8 puts "CQ" and friends in the callsign slot, and "CQ" is also a Portuguese prefix block. */
    @Test
    fun aCallingTokenIsNotACallsignAndGetsNoFlag() {
        for (token in listOf("CQ", "CQ DX", "QRZ", "DE")) {
            assertNull(token, callsignFlag(token))
            assertEquals(token, withFlag(token))
        }
    }

    /** Each ITU block is listed whole, not just its most common prefix. */
    @Test
    fun aWholePrefixBlockResolves() {
        for (call in listOf("UA3ABC", "UI6O", "RV3XYZ", "R1AB")) assertEquals(call, "RU", dxccCountryOf(call)?.isoCode)
        for (call in listOf("DB1AA", "DG2BB", "DM3CC", "DR4DD")) assertEquals(call, "DE", dxccCountryOf(call)?.isoCode)
        assertEquals("US", dxccCountryOf("AE5XY")?.isoCode)
        assertEquals("JP", dxccCountryOf("7K1ABC")?.isoCode)
    }

    /** Where a series is split between entities, the longer prefix decides. */
    @Test
    fun aSplitSeriesResolvesToTheRightEntity() {
        assertEquals("CN", dxccCountryOf("BG7ABC")?.isoCode)
        assertEquals("TW", dxccCountryOf("BV2AB")?.isoCode)
        assertEquals("CH", dxccCountryOf("HB9ABC")?.isoCode)
        assertEquals("LI", dxccCountryOf("HB0AB")?.isoCode)
        assertEquals("FR", dxccCountryOf("F4ABC")?.isoCode)
        assertEquals("RE", dxccCountryOf("FR4AB")?.isoCode)
        assertEquals("GB", dxccCountryOf("GM0ABC")?.isoCode)
        assertEquals("IM", dxccCountryOf("GD4AB")?.isoCode)
        assertEquals("PR", dxccCountryOf("KP4AB")?.isoCode)
        assertEquals("KZ", dxccCountryOf("UN7AB")?.isoCode)
        assertEquals("UA", dxccCountryOf("UR5AB")?.isoCode)
    }

    @Test
    fun russiaIsSplitIntoContinentsByCallArea() {
        assertEquals("EU", dxccCountryOf("UA3ABC")?.continent)
        assertEquals("EU", dxccCountryOf("RA2FA")?.continent)
        assertEquals("AS", dxccCountryOf("UA9ABC")?.continent)
        assertEquals("AS", dxccCountryOf("RZ0AB")?.continent)
    }

    /** DXCC puts these on a continent other than the one a map suggests. */
    @Test
    fun continentsFollowTheDxccList() {
        assertEquals("AS", dxccCountryOf("TA2AB")?.continent)
        assertEquals("OC", dxccCountryOf("YB1AB")?.continent)
        assertEquals("OC", dxccCountryOf("DU1AB")?.continent)
    }

    /** One prefix must not be claimed by two countries - the lookup would pick either. */
    @Test
    fun noPrefixIsListedForTwoCountries() {
        val clashes = DXCC_TABLE.groupBy { it.prefix }.filterValues { entries -> entries.map { it.isoCode }.distinct().size > 1 }
        assertEquals(emptyMap<String, List<DxccEntry>>(), clashes)
    }

    @Test
    fun everyEntryHasATwoLetterCountryCodeAndAKnownContinent() {
        for (entry in DXCC_TABLE) {
            assertEquals(entry.prefix, 2, entry.isoCode.length)
            assertEquals(entry.prefix, true, entry.continent in setOf("NA", "SA", "EU", "AS", "AF", "OC"))
        }
    }
}
