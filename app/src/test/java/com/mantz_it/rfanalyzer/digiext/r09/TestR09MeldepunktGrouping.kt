package com.mantz_it.rfanalyzer.digiext.r09

import com.mantz_it.rfanalyzer_ipc.digiext.R09Phy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - R09 Meldepunkt Grouping Tests</h1>
 *
 * Module:      TestR09MeldepunktGrouping.kt
 * Description: Tests for [groupR09ByMeldepunktVisit]: a user re-tracking the same bus (same
 *              Linie/Kurs/Ziel) with old Meldepunkt data still in the database must see a fresh
 *              visit-row, not one that already carries every checkmark from the previous run.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestR09MeldepunktGrouping {

    private fun message(mp: Int, tabelleneintrag: R09Paket.Tabelleneintrag, timestamp: Long, id: Long = timestamp): R09Telegram = R09Spec.packet(
        timestampMs = timestamp, frequencyHz = 0L, raw = null, phy = R09Phy(), rowId = id,
        data = R09Paket(
            typ = 9, zw = 0, laenge = 10, mp = mp, tabelleneintrag = tabelleneintrag,
            prioritaet = 0, handanforderung = 0, liniennummer = 1, kursnummer = 2, zielnummer = 3, zuglaenge = 1,
        ),
    )

    @Test
    fun messagesForTheSameMeldepunktWithinFiveMinutesMergeIntoOneGroup() {
        val messages = listOf(
            // newest first, as the detail query returns
            message(5, R09Paket.Tabelleneintrag.ABMELD, timestamp = 4 * 60_000L),
            message(5, R09Paket.Tabelleneintrag.HPTANM, timestamp = 2 * 60_000L),
            message(5, R09Paket.Tabelleneintrag.VORANM, timestamp = 0L),
        )

        val groups = groupR09ByMeldepunktVisit(messages)

        assertEquals(1, groups.size)
        assertEquals(5, groups.single().meldepunkt)
        assertEquals(3, groups.single().messages.size)
    }

    @Test
    fun aGapOverFiveMinutesSplitsIntoTwoGroups() {
        val messages = listOf(
            // A second visit to MP 5, 10 minutes after the first one's last message.
            message(5, R09Paket.Tabelleneintrag.VORANM, timestamp = 10 * 60_000L + 4 * 60_000L),
            message(5, R09Paket.Tabelleneintrag.ABFBER, timestamp = 4 * 60_000L),
            message(5, R09Paket.Tabelleneintrag.VORANM, timestamp = 0L),
        )

        val groups = groupR09ByMeldepunktVisit(messages)

        assertEquals("a >5 minute gap for the same meldepunkt must start a new group", 2, groups.size)
        // Newest-group-first.
        assertEquals(1, groups[0].messages.size)
        assertEquals(R09Paket.Tabelleneintrag.VORANM, groups[0].messages.single().data.tabelleneintrag)
        assertEquals(2, groups[1].messages.size)
    }

    /** Re-tracking the same bus must not merge with stale old data, even when the old visit's
     *  Meldepunkt already has every phase checked off. */
    @Test
    fun reTrackingTheSameBusAfterAGapDoesNotInheritThePreviousVisitsCheckmarks() {
        val oldVisit = listOf(
            message(5, R09Paket.Tabelleneintrag.VORANM, timestamp = 0L),
            message(5, R09Paket.Tabelleneintrag.HPTANM, timestamp = 60_000L),
            message(5, R09Paket.Tabelleneintrag.ABMELD, timestamp = 120_000L),
            message(5, R09Paket.Tabelleneintrag.ABFBER, timestamp = 180_000L),
        )
        val muchLater = 2 * 60 * 60_000L // 2 hours later - a completely separate capture session
        val newVisit = listOf(message(5, R09Paket.Tabelleneintrag.VORANM, timestamp = muchLater))

        val groups = groupR09ByMeldepunktVisit((newVisit + oldVisit).sortedByDescending { it.timestampMs })

        assertEquals(2, groups.size)
        val newest = groups.first()
        assertEquals(muchLater, newest.messages.single().timestampMs)
        assertEquals(
            "the fresh visit must show only VORANM, not the old visit's ABFBER too",
            setOf(R09Paket.Tabelleneintrag.VORANM),
            newest.toStatus().receivedTabelleneintraege,
        )
    }

    /** Interleaved meldepunkte (a real route visits several stops in sequence, sometimes
     *  revisiting one) must not let one meldepunkt's gap affect another's windowing. */
    @Test
    fun interleavedMeldepunkteDoNotAffectEachOthersWindowing() {
        val messages = listOf(
            message(12, R09Paket.Tabelleneintrag.VORANM, timestamp = 6 * 60_000L, id = 4),
            message(5, R09Paket.Tabelleneintrag.HPTANM, timestamp = 4 * 60_000L, id = 3),
            message(12, R09Paket.Tabelleneintrag.ABFBER, timestamp = 2 * 60_000L, id = 2),
            message(5, R09Paket.Tabelleneintrag.VORANM, timestamp = 0L, id = 1),
        )

        val groups = groupR09ByMeldepunktVisit(messages)

        assertEquals(2, groups.size)
        val mp5 = groups.single { it.meldepunkt == 5 }
        val mp12 = groups.single { it.meldepunkt == 12 }
        assertEquals(2, mp5.messages.size)
        assertEquals(2, mp12.messages.size)
    }

    @Test
    fun groupsAreOrderedNewestFirst() {
        val messages = listOf(
            message(5, R09Paket.Tabelleneintrag.VORANM, timestamp = 20 * 60_000L),
            message(12, R09Paket.Tabelleneintrag.VORANM, timestamp = 10 * 60_000L),
            message(20, R09Paket.Tabelleneintrag.VORANM, timestamp = 0L),
        )

        val groups = groupR09ByMeldepunktVisit(messages)

        assertEquals(listOf(5, 12, 20), groups.map { it.meldepunkt })
    }

    @Test
    fun toStatusDerivesTheReceivedSetAndNewestPhaseFromTheGroup() {
        val messages = listOf(
            message(5, R09Paket.Tabelleneintrag.ABMELD, timestamp = 120_000L),
            message(5, R09Paket.Tabelleneintrag.VORANM, timestamp = 0L),
        )
        val group = groupR09ByMeldepunktVisit(messages).single()

        val status = group.toStatus()

        assertEquals(5, status.meldepunkt)
        assertEquals(setOf(R09Paket.Tabelleneintrag.ABMELD, R09Paket.Tabelleneintrag.VORANM), status.receivedTabelleneintraege)
        assertEquals("newest-first messages -> first() is the most recently received phase",
            R09Paket.Tabelleneintrag.ABMELD, status.lastReceivedTabelleneintrag)
        assertEquals(120_000L, status.lastUpdateMs)
    }

    @Test
    fun emptyInputProducesNoGroups() {
        assertTrue(groupR09ByMeldepunktVisit(emptyList()).isEmpty())
    }
}
