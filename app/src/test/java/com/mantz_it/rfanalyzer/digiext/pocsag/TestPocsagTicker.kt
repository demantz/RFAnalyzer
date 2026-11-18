package com.mantz_it.rfanalyzer.digiext.pocsag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - POCSAG Ticker Tests</h1>
 *
 * Module:      TestPocsagTicker.kt
 * Description: Tests for the pure pacing/queueing logic behind [PocsagLiveTicker].
 *
 * The dwell rule and the drop policy are the two things that decide whether the ticker stays in
 * step with a real burst or drifts minutes behind it, and neither needs a device to check.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestPocsagTicker {

    private fun message(
        id: Long = 1L,
        address: Long = 2504L,
        function: Int = 3,
        alpha: String? = null,
        numeric: String? = null,
    ) = pocsagPage(function = function, alpha = alpha, numeric = numeric, address = address, rowId = id)

    // ------------------------------------------------------------------
    // tickerDwellMs
    // ------------------------------------------------------------------

    /** Address-only pages are common. Holding those for the full dwell would mean the ticker spent
     *  much of its time showing nothing while pages with text waited behind them. */
    @Test
    fun emptyAndVeryShortTextGetTheShortDwell() {
        assertEquals(1_000L, tickerDwellMs(null))
        assertEquals(1_000L, tickerDwellMs(""))
        assertEquals(1_000L, tickerDwellMs("1234"))
    }

    @Test
    fun realTextGetsTheFullDwell() {
        assertEquals(5_000L, tickerDwellMs("12345"))
        assertEquals(5_000L, tickerDwellMs("Club net tonight at 20:00 local time"))
    }

    @Test
    fun dwellBoundariesAreConfigurable() {
        assertEquals(50L, tickerDwellMs("ab", minDwellMs = 900, shortDwellMs = 50, shortTextMaxChars = 3))
        assertEquals(900L, tickerDwellMs("abc", minDwellMs = 900, shortDwellMs = 50, shortTextMaxChars = 3))
    }

    // ------------------------------------------------------------------
    // TickerQueue
    // ------------------------------------------------------------------

    @Test
    fun queueIsFifoWhileUnderCapacity() {
        val queue = TickerQueue(maxSize = 5)
        (1L..3L).forEach { queue.offer(message(id = it)) }

        assertEquals(3, queue.size)
        assertEquals(listOf(1L, 2L, 3L), listOf(queue.poll(), queue.poll(), queue.poll()).map { it!!.rowId })
        assertNull(queue.poll())
    }

    /**
     * The defining property: a burst leaves the ticker at most `maxSize` dwells behind live, not
     * `burstSize` dwells. Dropping the *newest* instead would leave it permanently behind.
     */
    @Test
    fun burstDropsOldestAndStaysBoundedNearLive() {
        val queue = TickerQueue(maxSize = 5)
        (1L..20L).forEach { queue.offer(message(id = it)) }

        assertEquals(5, queue.size)
        assertEquals(listOf(16L, 17L, 18L, 19L, 20L), generateSequence { queue.poll() }.map { it.rowId }.toList())
    }

    @Test
    fun emptyQueuePollsNull() {
        assertNull(TickerQueue(maxSize = 5).poll())
    }

    // ------------------------------------------------------------------
    // tickerText
    // ------------------------------------------------------------------

    /** Tone and empty pages have no text; the composable labels them rather than showing blank. */
    @Test
    fun tonePageHasNoText() {
        assertNull(tickerText(message(function = 1)))
        assertNull(tickerText(message(function = 3, alpha = null, numeric = null)))
    }

    @Test
    fun fn0PageShowsItsNumericReading() {
        val msg = message(function = 0, alpha = "garbage", numeric = "120500   150326")
        assertEquals("120500   150326", tickerText(msg))
    }

    @Test
    fun controlBytesAreMadePrintable() {
        val msg = message(function = 3, alpha = "A\u0000B")
        assertEquals("A·B", tickerText(msg))
    }
}
