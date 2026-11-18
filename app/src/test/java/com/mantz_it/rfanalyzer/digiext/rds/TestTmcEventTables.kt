package com.mantz_it.rfanalyzer.digiext.rds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - TMC Event Table Tests</h1>
 *
 * Module:      TestTmcEventTables.kt
 * Description: Tests for [TmcEventTables]: the bundled TMC event list and supplementary information
 *              table load completely, and known events carry their text, quantifier template,
 *              nature, duration type, direction and urgency; an unknown event code gives null.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestTmcEventTables {

    @Test
    fun testEventListLoadsAndHasExpectedSize() {
        // Spot-checking size confirms the classpath resource actually loaded (not silently empty).
        assertTrue(TmcEventTables.lookupEvent(1) != null)
        var count = 0
        for (code in 0..2047) if (TmcEventTables.lookupEvent(code) != null) count++
        assertEquals(1552, count)
    }

    @Test
    fun testSupplementaryInfoLoadsAndHasExpectedSize() {
        assertEquals("heavy lorries are recommended to avoid the area", TmcEventTables.lookupSupplementaryInfo(1))
        var count = 0
        for (code in 0..300) if (TmcEventTables.lookupSupplementaryInfo(code) != null) count++
        assertEquals(233, count)
    }

    @Test
    fun testKnownEventWithoutQuantifier() {
        val event = TmcEventTables.lookupEvent(1)!!
        assertEquals("traffic problem", event.text)
        assertNull(event.textTemplate)
        assertEquals("Info", event.nature)
        assertNull(event.quantifierType)
        assertEquals("Dynamic", event.durationType)
        assertFalse(event.bidirectional)
        assertEquals("Urgent", event.urgency)
    }

    @Test
    fun testKnownEventWithQuantifierTemplate() {
        val event = TmcEventTables.lookupEvent(2)!!
        assertEquals("queuing traffic with average speeds \$Q. Danger of stationary traffic", event.textTemplate)
        assertEquals(4, event.quantifierType)
    }

    @Test
    fun testEvent701MatchesReferenceCorpus() {
        // The event list says "roadworks" - diversion is the message's own D bit, not this event.
        val event = TmcEventTables.lookupEvent(701)!!
        assertEquals("roadworks", event.text)
        assertEquals("\$Q sets of roadworks", event.textTemplate)
        assertEquals(0, event.quantifierType)
        assertEquals("Longer-lasting", event.durationType)
    }

    @Test
    fun testUnknownEventReturnsNull() {
        assertNull(TmcEventTables.lookupEvent(9999))
    }
}
