package com.mantz_it.rfanalyzer.digiext.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Digital Format Tests</h1>
 *
 * Module:      TestDigitalFormat.kt
 * Description: The Digital tab's text formats.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestDigitalFormat {

    @Test
    fun ageUsesTheLargestFittingUnit() {
        val now = 100_000_000L
        assertEquals("0 s", formatAge(now, now))
        assertEquals("59 s", formatAge(now, now - 59_999))
        assertEquals("5 min", formatAge(now, now - 5 * 60_000))
        assertEquals("3 h", formatAge(now, now - 3 * 3_600_000))
        assertEquals("11 d", formatAge(now, now - 11 * 86_400_000L))
        // A timestamp from a skewed clock does not count down.
        assertEquals("0 s", formatAge(now, now + 5_000))
        // One well after now (heard after the recording being replayed) reads as a time.
        assertEquals(formatShortTime(now, now + 3_600_000), formatAge(now, now + 3_600_000))
    }

    /** Only the callsigns a text's object knows are coloured, and only as whole words. */
    @Test
    fun identitiesAreFoundAsWholeWords() {
        assertEquals(listOf(0..5, 9..13), identityRanges("DL1ABC → DK2XY RR73", listOf("DK2XY", "DL1ABC")))
        // An SSID makes another station; a portable suffix does not.
        assertEquals(emptyList<IntRange>(), identityRanges("DL1ABC-9 heard", listOf("DL1ABC")))
        assertEquals(listOf(0..5), identityRanges("DL1ABC/P 5W", listOf("DL1ABC")))
        assertEquals(emptyList<IntRange>(), identityRanges("XDL1ABC", listOf("DL1ABC")))
        assertEquals(listOf(12..16, 25..29), identityRanges("Relayed for W6AHM: hello W6AHM", listOf("W6AHM", "")))
    }

    @Test
    fun frequenciesReadAsPeopleWriteThem() {
        assertEquals("144.390 MHz", formatFrequency(144_390_000))
        assertEquals("869.618 MHz", formatFrequency(869_618_000))
        assertEquals("14.0715 MHz", formatFrequency(14_071_500))
        assertEquals("4.583 MHz", formatFrequency(4_583_000))
        assertEquals("439.9875 MHz", formatFrequency(439_987_500))
        assertEquals("1090 MHz", formatFrequency(1_090_000_000))
        assertEquals("147.3 kHz", formatFrequency(147_300))
        assertEquals("62.5 kHz", formatFrequency(62_500))
    }

    @Test
    fun durationsAndCounts() {
        assertEquals("49 s", formatDuration(49_500))
        assertEquals("2:31 min", formatDuration(151_000))
        assertEquals("1:05 h", formatDuration(3_900_000))
        assertEquals("14 751", formatCount(14_751))
        assertEquals("1 station", plural(1, "station", "stations"))
        assertEquals("2 stations", plural(2, "station", "stations"))
    }

    @Test
    fun signalsHaveARealMinus() {
        assertEquals("−13.9 dB", formatDb(-13.9))
        assertEquals("+4 dB", formatDb(4.0))
        assertEquals("0 dB", formatDb(0.0))
    }

    /** Times are in the phone's zone: today without the date, older with it. */
    @Test
    fun timesShowTheDateOnlyWhenItIsNotToday() {
        val now = System.currentTimeMillis()
        assertTrue(formatShortTime(now, now).matches(Regex("\\d\\d:\\d\\d")))
        assertTrue(formatShortTime(now, now - 3 * 86_400_000L).matches(Regex("\\d\\d\\.\\d\\d\\.")))
        assertTrue(formatPacketTime(now, now).matches(Regex("\\d\\d:\\d\\d:\\d\\d")))
        assertTrue(formatWhen(now, now - 3 * 86_400_000L).matches(Regex("\\d\\d\\.\\d\\d\\.\\d{4} \\d\\d:\\d\\d:\\d\\d")))
        assertTrue(formatFullDateTime(now).matches(Regex("\\d\\d\\.\\d\\d\\.\\d{4} \\d\\d:\\d\\d:\\d\\d\\.\\d{3}")))
    }

    @Test
    fun metaLinesSkipBlanksAndOneLineCollapsesBreaks() {
        assertEquals("a · c", metaLine("a", null, " ", "c"))
        assertEquals("V 68 S51CK", oneLine("V\r\n\r\n 68   S51CK\n"))
    }

    /** A header that is too narrow drops whole parts from the end. */
    @Test
    fun metaLinesShortenPartByPart() {
        assertEquals(listOf("85 msg/s", "85 msg/s · 12 with position"), metaLinePrefixes("85 msg/s · 12 with position"))
        assertEquals(emptyList<String>(), metaLinePrefixes(""))
    }
}
