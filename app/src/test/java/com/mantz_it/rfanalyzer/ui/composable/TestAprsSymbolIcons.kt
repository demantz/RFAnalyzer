package com.mantz_it.rfanalyzer.ui.composable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - APRS Symbol Icon Tests</h1>
 *
 * Module:      TestAprsSymbolIcons.kt
 * Description: Tests for [AprsSymbolIcons]: the primary and alternate symbol tables, overlay
 *              characters, malformed symbols, and that every registered map key resolves back to
 *              its own image.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestAprsSymbolIcons {

    @Test
    fun primaryAndAlternateTablesResolveSeparately() {
        assertEquals("Car", AprsSymbolIcons.label('/', '>'))
        assertEquals("Red car", AprsSymbolIcons.label('\\', '>'))
        assertNotEquals(AprsSymbolIcons.resourceFor('/', '>'), AprsSymbolIcons.resourceFor('\\', '>'))
        assertEquals("aprs:/>", AprsSymbolIcons.iconKey('/', '>'))
        assertEquals("aprs:\\>", AprsSymbolIcons.iconKey('\\', '>'))
    }

    @Test
    fun anOverlayCharacterSelectsTheAlternateSymbolAndIsReported() {
        // "S#" is an overlaid digipeater: the alternate-table '#' with an 'S' drawn on top.
        assertEquals(AprsSymbolIcons.resourceFor('\\', '#'), AprsSymbolIcons.resourceFor('S', '#'))
        assertEquals('S', AprsSymbolIcons.overlayChar('S'))
        assertEquals('3', AprsSymbolIcons.overlayChar('3'))
        // The map has one image per symbol, so an overlaid symbol shares its plain one's key.
        assertEquals("aprs:\\#", AprsSymbolIcons.iconKey('S', '#'))
    }

    @Test
    fun plainTableSelectorsCarryNoOverlay() {
        assertNull(AprsSymbolIcons.overlayChar('/'))
        assertNull(AprsSymbolIcons.overlayChar('\\'))
        assertNull(AprsSymbolIcons.overlayChar(null))
    }

    @Test
    fun malformedSymbolsResolveToNothing() {
        assertNull(AprsSymbolIcons.resourceFor('/', null))
        assertNull(AprsSymbolIcons.resourceFor(null, '>'))
        assertNull(AprsSymbolIcons.resourceFor('!', '>'))
        assertNull(AprsSymbolIcons.iconKey('!', '>'))
        assertNull(AprsSymbolIcons.label('/', '\u0001'))
    }

    @Test
    fun everyRegisteredMapKeyResolvesBackToItsOwnImage() {
        assertEquals(184, AprsSymbolIcons.allIcons.size)
        AprsSymbolIcons.allIcons.forEach { (key, resId) ->
            val table = key[5]; val symbol = key[6]
            assertEquals(key, AprsSymbolIcons.iconKey(table, symbol))
            assertEquals(resId, AprsSymbolIcons.resourceFor(table, symbol))
            assertNotNull(resId)
        }
    }
}
