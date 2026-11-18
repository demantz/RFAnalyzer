package com.mantz_it.rfanalyzer.digiext.k2k

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.K2kSymbol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - K2K Aggregator Tests</h1>
 *
 * Module:      TestK2kAggregator.kt
 * Description: Pins when a keyboard-to-keyboard transmission is stored: once, when it is finalized,
 *              and only if it is long enough to be a real transmission. While it is still being
 *              received it exists only as [K2kAggregator.current].
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestK2kAggregator {

    private val persisted = mutableListOf<K2kTransmission>()
    private val aggregator = K2kAggregator(RttySpec) { persisted += it }

    /** Types [text] one character per [intervalMs], the first at [startMs]. */
    private fun type(text: String, startMs: Long, frequency: Long = 14_080_000L, baudRate: Float? = 45.45f, intervalMs: Long = 1) {
        text.forEachIndexed { i, letter ->
            aggregator.onSymbol(K2kSymbol(DigitalMode.RTTY, startMs + i * intervalMs, frequency, letter, baudRate, frequencyShift = 170f), frequency)
        }
    }

    @Test
    fun nothingIsStoredWhileATransmissionIsStillBeingReceived() {
        type("CQ CQ DE DF4IAE", startMs = 1_000)

        assertTrue(persisted.isEmpty())
        assertEquals("CQ CQ DE DF4IAE", aggregator.current.value?.data?.text)
        assertTrue(aggregator.current.value!!.ongoing)
    }

    @Test
    fun aTransmissionIsStoredOnceWhenItGoesSilent() {
        type("CQ CQ DE DF4IAE", startMs = 1_000)

        aggregator.sweep(now = 1_000 + 60_000)
        aggregator.sweep(now = 1_000 + 120_000)

        val stored = persisted.single()
        assertEquals("CQ CQ DE DF4IAE", stored.data.text)
        assertFalse(stored.ongoing)
        assertEquals(1_000L, stored.timestampMs)
        assertEquals(1_000L + 14, stored.endTimestampMs)
        assertEquals("DF4IAE", stored.data.fromCallsign)
        assertNull(aggregator.current.value)
    }

    @Test
    fun aLostLockShortensTheWaitButDoesNotFinalizeByItself() {
        type("CQ CQ DE DF4IAE", startMs = 1_000)

        aggregator.onLockLost()
        assertTrue(persisted.isEmpty())
        aggregator.sweep(now = 1_000 + 3_000) // past the unlock grace, far short of the silence timeout
        assertEquals(1, persisted.size)
    }

    /** A false squelch lock or a fragment of a callsign is noise, not a transmission. */
    @Test
    fun aTooShortTransmissionIsNeverStored() {
        type("RYRY", startMs = 1_000)

        aggregator.sweep(now = 1_000 + 60_000)

        assertTrue(persisted.isEmpty())
        assertNull(aggregator.current.value)
    }

    /** Two parameter regimes are never spliced into one message. */
    @Test
    fun aChangeOfDemodSettingsEndsTheTransmission() {
        type("FIRST PART", startMs = 1_000, baudRate = 45.45f)
        type("SECOND", startMs = 2_000, baudRate = 50f)

        assertEquals(listOf("FIRST PART"), persisted.map { it.data.text })
        assertEquals("SECOND", aggregator.current.value?.data?.text)
    }

    // ------------------------------------------------ a transmission that does not end is stored in parts

    /** Every part a transmission of its own: consecutive numbers from 1, no gaps or overlaps in time,
     *  nothing lost or duplicated at the cuts. */
    private fun assertParts(text: String, parts: List<K2kTransmission>) {
        assertEquals((1..parts.size).toList(), parts.map { it.data.part })
        assertEquals(text, parts.joinToString("") { it.data.text })
        parts.zipWithNext { a, b -> assertTrue(a.endTimestampMs!! < b.timestampMs) }
        parts.forEach { assertFalse(it.ongoing) }
    }

    private val line = "X".repeat(60) + "\r\n"

    @Test
    fun aLongTransmissionIsCutAfterTheFirstLineBreakPast1500Characters() {
        val text = line.repeat(30)
        type(text, startMs = 1_000)

        // 1500 characters are reached in line 25: the part ends with that line
        val first = persisted.single()
        assertEquals(1, first.data.part)
        assertEquals(line.repeat(25), first.data.text)
        assertEquals(1_000L, first.timestampMs)
        assertEquals(1_000L + 25 * line.length - 1, first.endTimestampMs)

        // the next part starts with the next character, at its time, and is live as part 2
        val current = aggregator.current.value!!
        assertEquals(2, current.data.part)
        assertEquals(1_000L + 25 * line.length, current.timestampMs)
        assertEquals(line.repeat(5), current.data.text)

        // silence after a cut ends the last part like any transmission
        aggregator.sweep(now = 1_000 + 60_000)
        assertParts(text, persisted)
        assertEquals(2, persisted.size)
        assertNull(aggregator.current.value)
    }

    /** The threshold is crossed at the CR: its LF, and any further CR or LF, stay in the same part. */
    @Test
    fun aCrLfPairIsNeverSplit() {
        listOf("\r\n", "\r\r\n", "\n\r").forEach { lineBreak ->
            persisted.clear()
            val head = "A".repeat(K2K_TEST_PART_CHARS - 1) + lineBreak
            type(head + "NEXT LINE", startMs = 1_000_000)
            assertEquals(lineBreak.escaped(), head, persisted.single().data.text)
            assertEquals("NEXT LINE", aggregator.current.value!!.data.text)
            aggregator.sweep(now = 2_000_000)
        }
    }

    /** After a cut the ticker still shows the end of the stored part above the new one. */
    @Test
    fun earlierTextKeepsTheStoredPartsEnd() {
        val head = "A".repeat(K2K_TEST_PART_CHARS - 1) + "\n"
        type(head + "NEXT", startMs = 1_000_000)
        assertEquals("NEXT", aggregator.current.value!!.data.text)
        assertEquals(head, aggregator.currentEarlierText.value)
        aggregator.sweep(now = 2_000_000)
        assertNull(aggregator.current.value)
        assertEquals("", aggregator.currentEarlierText.value)
    }

    /** At 50 Bd a line break comes long before 1500 characters do: the parts are about a minute long. */
    @Test
    fun aWeatherBroadcastIsCutEveryMinuteAtALineBreak() {
        val text = DWD_SEA_AREA_REPORT.replace("\n", "\r\n").repeat(4)
        type(text, startMs = 1_000, intervalMs = 150, baudRate = 50f)
        aggregator.sweep(now = 1_000 + 150L * text.length + 60_000)

        assertParts(text, persisted)
        assertTrue(persisted.size >= 4)
        persisted.dropLast(1).forEach { part ->
            assertTrue(part.data.text.endsWith("\r\n"))
            assertTrue(part.endTimestampMs!! - part.timestampMs in 60_000L..80_000L)
        }
        // the callsign guess runs per part
        assertTrue(persisted.all { it.data.fromCallsign == null })
    }

    @Test
    fun aTextWithoutLineBreaksIsCutAfterItsLastSpaceAt4000Characters() {
        val text = "ABCDEF ".repeat(700) // 4900 characters, a space every 7th
        type(text, startMs = 1_000)

        // 4000 characters reached: cut after the last space before them, at index 3996
        val first = persisted.single()
        assertEquals(text.substring(0, 3997), first.data.text)
        assertEquals(1_000L + 3996, first.endTimestampMs)
        assertEquals(1_000L + 3997, aggregator.current.value!!.timestampMs)

        aggregator.sweep(now = 1_000_000)
        assertParts(text, persisted)
    }

    @Test
    fun aTextWithoutSpacesIsCutWhereItIs() {
        val text = "X".repeat(4_500)
        type(text, startMs = 1_000)
        aggregator.sweep(now = 1_000_000)

        assertParts(text, persisted)
        assertEquals(listOf(4_000, 500), persisted.map { it.data.text.length })
    }

    @Test
    fun aTextWithoutLineBreaksIsCutAfterThreeMinutes() {
        val text = "SLOW TEXT ".repeat(20) // one character a second: 200 s
        type(text, startMs = 1_000, intervalMs = 1_000)
        aggregator.sweep(now = 1_000_000)

        assertParts(text, persisted)
        // the 181st character arrives 180 s after the first; the last space before it ends the part
        assertEquals("SLOW TEXT ".repeat(18), persisted.first().data.text)

        // without a space the part ends where it is
        persisted.clear()
        type("X".repeat(200), startMs = 10_000_000, intervalMs = 1_000)
        aggregator.sweep(now = 20_000_000)
        assertEquals(listOf(180, 20), persisted.map { it.data.text.length })
        assertEquals(listOf(1, 2), persisted.map { it.data.part })
    }

    /** Only a transmission that has been cut is numbered. */
    @Test
    fun aTransmissionThatEndsBeforeItIsCutStaysWhole() {
        // long enough to be cut, but it ends (silence) before the next line break
        val text = "A".repeat(2_000) + "\r\n"
        type(text, startMs = 1_000)
        assertEquals(0, aggregator.current.value!!.data.part)
        aggregator.sweep(now = 1_000_000)

        assertEquals(0, persisted.single().data.part)
        assertEquals(text, persisted.single().data.text)
    }

    private fun String.escaped() = replace("\r", "\\r").replace("\n", "\\n")

    private companion object {
        const val K2K_TEST_PART_CHARS = 1500

        /** The start of DWD's sea-area report on 4583 kHz (samples/rtty, 2026-10-20). */
        val DWD_SEA_AREA_REPORT = """
            SVINOY (62.3N   4.4E) SST: 11 C
            WE 21. 00Z: NE-E     4           M        //
            WE 21. 06Z: NE-E   0-2        2.5 M        //
            WE 21. 12Z: E-SE   0-2         2  M        //
            WE 21. 18Z: E-SE   2-3         2  M        //
            TH 22. 00Z: SE-S     3         2  M        //
            TH 22. 06Z: E      3-4         2  M        //
            TH 22. 12Z: E      2-3         2  M RAIN   //

            FAEROES (60.4N   5.6W) SST: 11 C
            WE 21. 00Z: SE-S     6   7-8  4.5 M        //
            WE 21. 06Z: SE     5-6   6-7   4  M        //
            WE 21. 12Z: SE-S   4-5        3.5 M RAIN   //
            WE 21. 18Z: SE       3        3.5 M RAIN   //
            TH 22. 00Z: W        5   6-7  3.5 M        //
            TH 22. 06Z: W-NW     5        3.5 M        //
            TH 22. 12Z: W-NW     4        2.5 M        //

            PENTLAND FIRTH (59.7N   3.1W) SST: 12 C
            WE 21. 00Z: SE       6   7-8   4  M        //

        """.trimIndent()
    }
}
