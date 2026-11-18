package com.mantz_it.rfanalyzer.digiext.adsb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - ADS-B Map Icon Tests</h1>
 *
 * Module:      TestAdsbMapIcon.kt
 * Description: Tests for the ADS-B map icons: a silhouette for every emitter category, the category
 *              of each type code and CA value, the tintable map icons, the altitude colors (orange
 *              to magenta, clamped, never decreasing; ground and unknown altitude off the scale)
 *              and the icon size per category.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestAdsbMapIcon {

    /** Every category the identification message can carry has a silhouette of its own, except
     *  the one with nothing recognizable to draw. */
    @Test
    fun everyCategoryHasItsOwnSilhouette() {
        val all = (2..4).flatMap { tc -> (1..7).mapNotNull { ca -> AdsbCategory.of(tc, ca) } }
        assertEquals(AdsbCategory.entries.toList(), all.sortedBy { it.ordinal })
        all.filter { it != AdsbCategory.B7 }.forEach { category ->
            assertNotEquals("no silhouette for $category", AdsbIconShape.GENERIC, category.shape)
        }
    }

    @Test
    fun categoryCodesFollowTheTypeCode() {
        assertEquals(AdsbCategory.A1, AdsbCategory.of(4, 1))
        assertEquals(AdsbCategory.A5, AdsbCategory.of(4, 5))
        assertEquals(AdsbCategory.B2, AdsbCategory.of(3, 2))
        assertEquals(AdsbCategory.C3, AdsbCategory.of(2, 3))
        assertEquals(null, AdsbCategory.of(4, 0))
        assertEquals(null, AdsbCategory.of(3, 5))
        assertEquals(null, AdsbCategory.of(1, 1))
        assertEquals(AdsbIconShape.GENERIC, AdsbAircraft().iconShape)
    }

    @Test
    fun everySilhouetteIsRegisteredAsATintableMapIcon() {
        assertEquals(AdsbIconShape.entries.map { it.iconKey }, adsbMapIcons.map { it.key })
        assertTrue(adsbMapIcons.all { it.sdf })
    }

    @Test
    fun altitudeHueRunsFromOrangeToMagentaAndClampsAtBothEnds() {
        assertEquals(20f, adsbAltitudeHue(-500), 0.01f)
        assertEquals(20f, adsbAltitudeHue(2_000), 0.01f)
        assertEquals(80f, adsbAltitudeHue(6_000), 0.01f)
        assertEquals(140f, adsbAltitudeHue(10_000), 0.01f)
        assertEquals(220f, adsbAltitudeHue(25_000), 0.01f)
        assertEquals(300f, adsbAltitudeHue(40_000), 0.01f)
        assertEquals(300f, adsbAltitudeHue(51_000), 0.01f)
    }

    @Test
    fun altitudeHueNeverDecreasesWithAltitude() {
        val hues = (0..45_000 step 250).map { adsbAltitudeHue(it) }
        assertTrue(hues.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test
    fun groundAndUnknownAltitudeAreNotOnTheAltitudeScale() {
        val ground = adsbAltitudeColor(altitudeFt = 35_000, onGround = true)
        val unknown = adsbAltitudeColor(altitudeFt = null, onGround = false)
        assertEquals(ground, adsbAltitudeColor(altitudeFt = null, onGround = true))
        assertNotEquals(ground, unknown)
        assertNotEquals(ground, adsbAltitudeColor(35_000, onGround = false))
        assertNotEquals(unknown, adsbAltitudeColor(35_000, onGround = null))
    }

    @Test
    fun aBalloonIsNotSizedAsALightAircraft() {
        assertEquals(1.1f, AdsbAircraft(category = AdsbCategory.B2).mapIconSize(), 0f)
        assertEquals(1.3f, AdsbAircraft(category = AdsbCategory.A1).mapIconSize(), 0f)
        assertEquals(2.4f, AdsbAircraft(category = AdsbCategory.A5).mapIconSize(), 0f)
    }
}
