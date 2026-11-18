package com.mantz_it.rfanalyzer.digiext.adsb

import com.mantz_it.rfanalyzer_ipc.digiext.AirbornePositionPayload
import com.mantz_it.rfanalyzer_ipc.digiext.AirborneVelocityPayload
import com.mantz_it.rfanalyzer_ipc.digiext.AircraftIdPayload
import com.mantz_it.rfanalyzer_ipc.digiext.SurfacePositionPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - ADS-B Payload Grouping Tests</h1>
 *
 * Module:      TestAdsbPayloadGrouping.kt
 * Description: Tests for [groupAdsbFramesByPayloadType], the per-type rows of an aircraft's frame
 *              list.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestAdsbPayloadGrouping {

    private fun frame(id: Long, ts: Long, payload: com.mantz_it.rfanalyzer_ipc.digiext.AdsbPayload): AdsbFrame =
        AdsbSpec.packet(timestampMs = ts, frequencyHz = 1_090_000_000L, raw = null, phy = Unit, data = payload, rowId = id)

    @Test
    fun emptyInputProducesNoGroups() {
        assertEquals(emptyList<AdsbPayloadTypeSummary>(), groupAdsbFramesByPayloadType(emptyList()))
    }

    @Test
    fun oneFramePerTypeProducesOneGroupEach() {
        val frames = listOf(
            frame(1, 100, AircraftIdPayload(4, 0, "SWISS123")),
            frame(2, 200, AirbornePositionPayload(11, 35000, "BARO", cprOddFrame = false, cprLat = 1, cprLon = 2)),
        )
        val groups = groupAdsbFramesByPayloadType(frames)
        assertEquals(setOf("ID", "Pos (air)"), groups.map { it.typeLabel }.toSet())
    }

    /** Airborne and surface position are distinct message types with different fields, and an
     *  aircraft rarely transmits both - they must stay separate rows, not merge into one "Pos". */
    @Test
    fun airborneAndSurfacePositionStaySeparateGroups() {
        val frames = listOf(
            frame(1, 100, AirbornePositionPayload(11, 35000, "BARO", cprOddFrame = false, cprLat = 1, cprLon = 2)),
            frame(2, 200, SurfacePositionPayload(6, 12.0, 90.0, cprOddFrame = true, cprLat = 3, cprLon = 4)),
        )
        val groups = groupAdsbFramesByPayloadType(frames)
        assertEquals(2, groups.size)
        assertEquals(setOf("Pos (air)", "Pos (surface)"), groups.map { it.typeLabel }.toSet())
    }

    /** Multiple frames of the same type collapse into one group, newest first within it. */
    @Test
    fun multipleFramesOfTheSameTypeCollapseIntoOneGroup() {
        val frames = listOf(
            frame(1, 300, AirborneVelocityPayload(1, 450, 90.0, 0, "BARO")),
            frame(2, 200, AirborneVelocityPayload(1, 440, 91.0, 0, "BARO")),
            frame(3, 100, AirborneVelocityPayload(1, 430, 92.0, 0, "BARO")),
        )
        val groups = groupAdsbFramesByPayloadType(frames)
        assertEquals(1, groups.size)
        val velGroup = groups.single()
        assertEquals("Vel", velGroup.typeLabel)
        assertEquals(3, velGroup.frames.size)
        // "Newest first" is the caller's contract (the detail query's order) - the group must
        // preserve it, not re-sort.
        assertEquals(listOf(1L, 2L, 3L), velGroup.frames.map { it.rowId })
    }

    /** The group's latestFrame must be the *first* frame of that type in iteration order (i.e. the
     *  newest, given the newest-first input contract), not an arbitrary member. */
    @Test
    fun latestFrameIsTheFirstOccurrenceOfItsType() {
        val newest = frame(1, 300, AircraftIdPayload(4, 0, "NEWCALL"))
        val older = frame(2, 100, AircraftIdPayload(4, 0, "OLDCALL"))
        val groups = groupAdsbFramesByPayloadType(listOf(newest, older))
        assertEquals(newest, groups.single().latestFrame)
    }

    /** Group order follows the fixed TYPE_PRIORITY list, not which type was most recently
     *  active - "ID" sorts before "Vel" here even though the newest frame is a Vel one. Each
     *  type's latestFrame is still its first occurrence in the newest-first input, unaffected by
     *  group order. */
    @Test
    fun groupOrderFollowsFixedPriorityNotArrivalOrder() {
        val frames = listOf(
            frame(1, 300, AirborneVelocityPayload(1, 450, 90.0, 0, "BARO")), // Vel most recent
            frame(2, 200, AircraftIdPayload(4, 0, "SWISS123")),               // ID next
            frame(3, 100, AirborneVelocityPayload(1, 430, 92.0, 0, "BARO")), // older Vel
        )
        val groups = groupAdsbFramesByPayloadType(frames)
        assertEquals(listOf("ID", "Vel"), groups.map { it.typeLabel })
    }

    /** Two aircraft with the same message types show them in the same row order, whichever type
     *  arrived first. */
    @Test
    fun groupOrderIsIdenticalRegardlessOfArrivalOrder() {
        val idPayload = AircraftIdPayload(4, 0, "SWISS123")
        val posPayload = AirbornePositionPayload(11, 35000, "BARO", cprOddFrame = false, cprLat = 1, cprLon = 2)
        val velPayload = AirborneVelocityPayload(1, 450, 90.0, 0, "BARO")

        val posFirst = groupAdsbFramesByPayloadType(
            listOf(frame(1, 300, posPayload), frame(2, 200, velPayload), frame(3, 100, idPayload)),
        )
        val velFirst = groupAdsbFramesByPayloadType(
            listOf(frame(1, 300, velPayload), frame(2, 200, idPayload), frame(3, 100, posPayload)),
        )

        assertEquals(listOf("ID", "Pos (air)", "Vel"), posFirst.map { it.typeLabel })
        assertEquals(posFirst.map { it.typeLabel }, velFirst.map { it.typeLabel })
    }

    /** "Unknown" (an undecoded type code) is last in TYPE_PRIORITY - and any label not in the list
     *  at all (a hypothetical future payload subtype) falls back to that same "sorts last, doesn't
     *  crash" behavior via typePriorityOf's `Int.MAX_VALUE` default. */
    @Test
    fun unknownPayloadTypeSortsLast() {
        val frames = listOf(
            frame(1, 100, com.mantz_it.rfanalyzer_ipc.digiext.UnknownAdsbPayload(0)),
            frame(2, 200, AircraftIdPayload(4, 0, "SWISS123")),
        )
        val groups = groupAdsbFramesByPayloadType(frames)
        assertEquals(listOf("ID", "Unknown"), groups.map { it.typeLabel })
    }

    @Test
    fun adsbPayloadTypeLabelCoversEveryKnownSubtype() {
        assertTrue(adsbPayloadTypeLabel(AircraftIdPayload(4, 0, "X")).isNotBlank())
        assertTrue(adsbPayloadTypeLabel(AirbornePositionPayload(11, null, "BARO", false, 0, 0)).isNotBlank())
        assertTrue(adsbPayloadTypeLabel(SurfacePositionPayload(6, null, null, false, 0, 0)).isNotBlank())
        assertTrue(adsbPayloadTypeLabel(AirborneVelocityPayload(1, null, null, null, "BARO")).isNotBlank())
    }
}
