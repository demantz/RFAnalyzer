package com.mantz_it.rfanalyzer.location

import com.mantz_it.rfanalyzer.database.COUNTRY_CODE_TO_NAME
import com.mantz_it.rfanalyzer.digiext.rds.RdsPtyTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * <h1>RF Analyzer - User Location Tests</h1>
 *
 * Module:      TestUserLocation.kt
 * Description: Tests for the user location: the country guess from network, time zone, SIM and
 *              language, the chosen country or ITU region, the position from a grid locator or a
 *              device fix, locator validation and bounds, the ITU region of a country, the RDS
 *              programme type table on Auto, where the maps start, and the bundled country bounds.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestUserLocation {

    @Test
    fun guessPrefersTheNetworkThenTimeZoneThenSimThenLanguage() {
        assertEquals("FR" to CountrySource.NETWORK, guessCountry("fr", "DE", "de", "US"))
        assertEquals("DE" to CountrySource.TIME_ZONE, guessCountry("", "DE", "at", "US"))
        assertEquals("AT" to CountrySource.SIM, guessCountry(null, "001", "at", "US")) // "001": a zone without a country
        assertEquals("US" to CountrySource.LANGUAGE, guessCountry("", null, "", "US"))
        assertNull(guessCountry("", null, "", ""))
        assertNull(guessCountry("xx", "ZZ", null, null))
    }

    @Test
    fun theGuessAppliesUntilTheUserChooses() {
        val guess = "DE" to CountrySource.NETWORK
        val guessed = resolveUserLocation("", guess, PositionMode.NONE, "", "")
        assertEquals("DE", guessed.countryCode)
        assertEquals(CountrySource.NETWORK, guessed.countrySource)
        assertEquals(ItuRegion.REGION_1, guessed.ituRegion)

        val chosen = resolveUserLocation("US", guess, PositionMode.NONE, "", "")
        assertEquals("US", chosen.countryCode)
        assertEquals(CountrySource.CHOSEN, chosen.countrySource)
        assertEquals(ItuRegion.REGION_2, chosen.ituRegion)

        // a region alone hides the country
        val region = resolveUserLocation("R3", guess, PositionMode.NONE, "", "")
        assertNull(region.countryCode)
        assertEquals(ItuRegion.REGION_3, region.ituRegion)

        assertNull(resolveUserLocation("", null, PositionMode.NONE, "", "").ituRegion)
    }

    @Test
    fun positionComesFromTheChosenModeOnly() {
        val locator = resolveUserLocation("", null, PositionMode.LOCATOR, "jn49EH", "49.400,8.700,1000")
        assertEquals("JN49eh", locator.locator)
        assertEquals(49.3125, locator.position!!.latitude, 1e-6)
        assertEquals(8.375, locator.position!!.longitude, 1e-6)
        assertNull(locator.fixTimeMs)

        val device = resolveUserLocation("", null, PositionMode.DEVICE, "JN49", "49.400,8.700,1000")
        assertEquals("JN49ij", device.locator) // Heidelberg
        assertEquals(1000L, device.fixTimeMs)

        assertNull(resolveUserLocation("", null, PositionMode.NONE, "JN49", "49.400,8.700,1000").position)
        assertNull(resolveUserLocation("", null, PositionMode.LOCATOR, "JN4", "").position)
        assertNull(resolveUserLocation("", null, PositionMode.DEVICE, "", "").position)
    }

    @Test
    fun locatorsAreValidatedAndSpelledTheUsualWay() {
        assertEquals("JN49", normalizeLocator(" jn49 "))
        assertEquals("FN31pr", normalizeLocator("fn31PR"))
        assertNull(normalizeLocator("JN4"))
        assertNull(normalizeLocator("JN49e"))
        assertNull(normalizeLocator("ZZ99")) // fields go to R
        assertNull(normalizeLocator("JN49zz")) // subsquares go to x
        assertNull(normalizeLocator("JNA9"))
    }

    @Test
    fun deviceFixRoundTrips() {
        val fix = formatDeviceFix(49.41234, -8.69876, 1234567L)
        assertEquals("49.412,-8.699,1234567", fix)
        assertEquals(Triple(49.412, -8.699, 1234567L), parseDeviceFix(fix))
        assertNull(parseDeviceFix(""))
        assertNull(parseDeviceFix("1,2"))
    }

    @Test
    fun ituRegions() {
        assertEquals(ItuRegion.REGION_1, ituRegionOf("DE"))
        assertEquals(ItuRegion.REGION_1, ituRegionOf("RU")) // all of Russia
        assertEquals(ItuRegion.REGION_1, ituRegionOf("MN"))
        assertEquals(ItuRegion.REGION_1, ituRegionOf("SA"))
        assertEquals(ItuRegion.REGION_1, ituRegionOf("ZA"))
        assertEquals(ItuRegion.REGION_2, ituRegionOf("US"))
        assertEquals(ItuRegion.REGION_2, ituRegionOf("GL"))
        assertEquals(ItuRegion.REGION_2, ituRegionOf("BR"))
        assertEquals(ItuRegion.REGION_3, ituRegionOf("IR"))
        assertEquals(ItuRegion.REGION_3, ituRegionOf("AU"))
        assertEquals(ItuRegion.REGION_3, ituRegionOf("JP"))
        assertEquals(ItuRegion.REGION_3, ituRegionOf("GU"))
    }

    @Test
    fun rdsProgrammeTypesFollowTheCountryOnAuto() {
        assertTrue(RdsPtyTable.AUTO.isRbds("US"))
        assertTrue(RdsPtyTable.AUTO.isRbds("CA"))
        assertFalse(RdsPtyTable.AUTO.isRbds("DE"))
        assertFalse(RdsPtyTable.AUTO.isRbds(null))
        assertTrue(RdsPtyTable.RBDS.isRbds("DE"))
        assertFalse(RdsPtyTable.RDS.isRbds("US"))
    }

    @Test
    fun locatorBoundsAreTheSquare() {
        assertEquals(GeoBounds(49.0, 8.0, 50.0, 10.0), locatorBounds("JN49"))
        val sub = locatorBounds("jn49ij")!! // JN49 + i (8 × 5') + j (9 × 2.5')
        assertEquals(49.375, sub.south, 1e-9)
        assertEquals(8.0 + 8 * 2.0 / 24, sub.west, 1e-9)
        assertEquals(1.0 / 24, sub.north - sub.south, 1e-9)
        assertEquals(GeoBounds(-90.0, -180.0, -89.0, -178.0), locatorBounds("AA00"))
        assertNull(locatorBounds("JN4"))
    }

    @Test
    fun theMapStartsOnTheLocatorTheDeviceFixTheCountryOrTheRegion() {
        val germany = GeoBounds(47.28, 5.86, 54.90, 15.02)
        val bounds = { code: String -> germany.takeIf { code == "DE" } }
        val guess = "DE" to CountrySource.NETWORK

        val locator = resolveUserLocation("", guess, PositionMode.LOCATOR, "JN49ij", "").mapHome(bounds)!!
        assertEquals(locatorBounds("JN49ij"), locator.bounds)
        assertEquals(locator.bounds, locator.square)
        assertEquals("JN49ij", locator.label)

        val device = resolveUserLocation("", guess, PositionMode.DEVICE, "", "49.410,8.690,1000").mapHome(bounds)!!
        assertNull(device.square) // the live location dot is the map's own
        assertTrue(device.bounds.south < 49.41 && device.bounds.north > 49.41 && device.bounds.west < 8.69 && device.bounds.east > 8.69)

        assertEquals(MapHome(germany), resolveUserLocation("", guess, PositionMode.NONE, "", "").mapHome(bounds))
        // a country without a box, and a region alone
        assertEquals(ItuRegion.REGION_2, resolveUserLocation("CA", guess, PositionMode.NONE, "", "").ituRegion)
        assertTrue(resolveUserLocation("CA", guess, PositionMode.NONE, "", "").mapHome(bounds)!!.bounds.west < -100)
        assertTrue(resolveUserLocation("R3", guess, PositionMode.NONE, "", "").mapHome(bounds)!!.bounds.east > 100)
        assertNull(resolveUserLocation("", null, PositionMode.NONE, "", "").mapHome(bounds))
    }

    @Test
    fun theCountryBoundsCoverAlmostEveryCountry() {
        val bounds = parseCountryBounds(File("src/main/assets/location/country_bounds.csv").readLines().asSequence())
        assertEquals(GeoBounds(41.67, -141.0, 83.12, -52.65), bounds["CA"])
        bounds.values.forEach { assertTrue(it.toString(), it.south < it.north && it.west < it.east && it.east <= 360) }
        // Gibraltar, Bouvet Island and the US Minor Outlying Islands fall back to their region
        assertEquals(setOf("BV", "GI", "UM"), COUNTRY_CODE_TO_NAME.keys - bounds.keys)
    }
}
