package com.mantz_it.rfanalyzer.digiext.adsb

import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - ADS-B Identity Sort Tests</h1>
 *
 * Module:      TestAdsbIdentitySort.kt
 * Description: Tests for [adsbIdentitySortKey], the sort order of the ADS-B identity column:
 *              aircraft with a callsign first, sorted by it, then those known by their address
 *              only.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestAdsbIdentitySort {

    private fun aircraft(icaoHex: String, callsign: String?) = TrackedObject(
        kind = ObjectKind.ADSB_AIRCRAFT, key = icaoHex, displayName = callsign ?: icaoHex, firstSeenMs = 0, lastSeenMs = 0,
        packetCount = 1, frequencyHz = null, lat = null, lon = null, state = AdsbAircraft(callsign = callsign),
    )

    /** The column shows the callsign where there is one, so that is what it sorts by; aircraft
     *  known by address only follow, instead of being scattered between the callsigns. */
    @Test
    fun callsignsComeFirstThenAddresses() {
        val sorted = listOf(
            aircraft("3C6444", null), aircraft("000ABC", "DLH7XP"), aircraft("0A0001", null), aircraft("FFFFFF", "AFR123"),
        ).sortedBy(::adsbIdentitySortKey)

        assertEquals(listOf("AFR123", "DLH7XP", "0A0001", "3C6444"), sorted.map { it.displayName })
    }
}
