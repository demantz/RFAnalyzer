package com.mantz_it.rfanalyzer.digiext.rds

import com.mantz_it.rfanalyzer_ipc.digiext.RdsEvent
import com.mantz_it.rfanalyzer_ipc.digiext.TmcRawOptionalField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - TMC Message Resolver Tests</h1>
 *
 * Module:      TestTmcMessageResolver.kt
 * Description: Tests for [TmcMessageResolver]: how an assembled single- or multi-group TMC message
 *              becomes text - the event text with its quantifier, the expiry, diversion advice from
 *              the message's bit or a multi-group control code, supplementary information, length
 *              of route, speed limit, explicit start time, chained additional events, and the
 *              fallback for an unknown event code.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestTmcMessageResolver {

    private fun singleGroup(
        eventCode: Int,
        extent: Int = 1,
        direction: Boolean = false,
        diversionRecommended: Boolean = false,
        locationCode: Int = 100,
        dp: Int = 0,
    ) = RdsEvent.TmcAssembledMessage(
        messageKey = "$eventCode:$locationCode:-1",
        continuityIndex = -1,
        singleGroup = true,
        complete = true,
        eventCode = eventCode,
        extent = extent,
        direction = direction,
        diversionRecommended = diversionRecommended,
        locationCode = locationCode,
        durationOrCi = dp,
        optionalFields = emptyList(),
    )

    private fun multiGroup(
        eventCode: Int,
        extent: Int = 1,
        direction: Boolean = false,
        locationCode: Int = 100,
        continuityIndex: Int = 1,
        optionalFields: List<TmcRawOptionalField> = emptyList(),
    ) = RdsEvent.TmcAssembledMessage(
        messageKey = "$eventCode:$locationCode:$continuityIndex",
        continuityIndex = continuityIndex,
        singleGroup = false,
        complete = true,
        eventCode = eventCode,
        extent = extent,
        direction = direction,
        diversionRecommended = false,
        locationCode = locationCode,
        durationOrCi = continuityIndex,
        optionalFields = optionalFields,
    )

    @Test
    fun testSimpleEventNoQuantifier() {
        // event 1 = "traffic problem", Info/Urgent/Dynamic, not bidirectional, no quantifier.
        val resolved = TmcMessageResolver.resolve(singleGroup(eventCode = 1, diversionRecommended = true, dp = 3))
        assertEquals("traffic problem", resolved.eventText)
        assertEquals("Info", resolved.nature)
        assertEquals("Urgent", resolved.urgency)
        assertNull("event 1 is not bidirectional, direction must not be surfaced", resolved.direction)
        assertTrue(resolved.diversionAdvised)
        assertEquals("+1 hour", resolved.expiryText) // DP=3, Dynamic -> +1 hour
        assertEquals(60, resolved.expiryMinutes)
    }

    @Test
    fun testQuantifierTemplateSubstitution() {
        // event 2 = "queuing traffic...", quantifier type 4 (speed): "of up to 5*q km/h".
        val fields = listOf(TmcRawOptionalField(label = 4, content = 10))
        val resolved = TmcMessageResolver.resolve(multiGroup(eventCode = 2, optionalFields = fields))
        assertEquals("of up to 50 km/h", resolved.quantifierText)
        assertEquals("queuing traffic with average speeds of up to 50 km/h. Danger of stationary traffic", resolved.eventText)
    }

    @Test
    fun testEvent701QuantifierTemplate() {
        // event 701 = "roadworks", template "$Q sets of roadworks", quantifier type 0 (count).
        val fields = listOf(TmcRawOptionalField(label = 4, content = 5))
        val resolved = TmcMessageResolver.resolve(multiGroup(eventCode = 701, optionalFields = fields))
        assertEquals("5 sets of roadworks", resolved.eventText)
        assertEquals("Longer-lasting", TmcEventTables.lookupEvent(701)!!.durationType)
    }

    @Test
    fun testMultiGroupDiversionResolvedFromControlCode() {
        // label=1 (control code), content=5 -> "set diversion advised flag".
        val fields = listOf(TmcRawOptionalField(label = 1, content = 5))
        val resolved = TmcMessageResolver.resolve(multiGroup(eventCode = 1, optionalFields = fields))
        assertTrue("diversion for multi-group messages must come from the control code, not a raw bit", resolved.diversionAdvised)
        assertEquals(listOf("set diversion advised"), resolved.controlCodes)
    }

    @Test
    fun testMultiGroupWithoutDiversionControlCodeIsNotAdvised() {
        val resolved = TmcMessageResolver.resolve(multiGroup(eventCode = 1, optionalFields = emptyList()))
        assertTrue(resolved.controlCodes.isEmpty())
        assertEquals(false, resolved.diversionAdvised)
    }

    @Test
    fun testSupplementaryInfoLookup() {
        val fields = listOf(TmcRawOptionalField(label = 6, content = 1))
        val resolved = TmcMessageResolver.resolve(multiGroup(eventCode = 1, optionalFields = fields))
        assertEquals(listOf("heavy lorries are recommended to avoid the area"), resolved.supplementaryInfo)
    }

    @Test
    fun testLengthOfRouteAndSpeedLimit() {
        val fields = listOf(TmcRawOptionalField(label = 2, content = 25), TmcRawOptionalField(label = 3, content = 10))
        val resolved = TmcMessageResolver.resolve(multiGroup(eventCode = 1, optionalFields = fields))
        assertEquals(70.0, resolved.lengthOfRouteKm) // 20 + (25-15)*5 = 70
        assertEquals(50, resolved.speedLimitKmh) // 5*10
    }

    @Test
    fun testExplicitStartTime() {
        // label=7, content=253: in 232-255 range, odd -> "end of month (253-230)/2 = 11".
        val fields = listOf(TmcRawOptionalField(label = 7, content = 253))
        val resolved = TmcMessageResolver.resolve(multiGroup(eventCode = 1, optionalFields = fields))
        assertEquals("end of month 11", resolved.explicitStartTime)
    }

    @Test
    fun testChainedAdditionalEvent() {
        // label=9, content=701 -> chains to event 701's plain text.
        val fields = listOf(TmcRawOptionalField(label = 9, content = 701))
        val resolved = TmcMessageResolver.resolve(multiGroup(eventCode = 1, optionalFields = fields))
        assertEquals(1, resolved.additionalEvents.size)
        assertEquals(701, resolved.additionalEvents[0].code)
        assertEquals("roadworks", resolved.additionalEvents[0].text)
    }

    @Test
    fun testUnknownEventCodeFallsBackGracefully() {
        val resolved = TmcMessageResolver.resolve(singleGroup(eventCode = 9999))
        assertEquals("Unknown event 9999", resolved.eventText)
        assertEquals("Info", resolved.nature)
        assertEquals("Normal", resolved.urgency)
    }
}
