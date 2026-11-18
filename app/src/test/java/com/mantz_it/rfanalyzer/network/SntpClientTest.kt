package com.mantz_it.rfanalyzer.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/**
 * <h1>RF Analyzer - SNTP Client Tests</h1>
 *
 * Module:      SntpClientTest.kt
 * Description: Tests for [SntpClient]: NTP timestamps round-trip (also in the era after 2036), the
 *              clock offset and round trip come from the four timestamps, and unsynchronised or
 *              invalid answers are rejected.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class SntpClientTest {

    private fun response(t2: Long, t3: Long, mode: Int = 4, stratum: Int = 2, leap: Int = 0): ByteArray {
        val r = ByteArray(48)
        r[0] = ((leap shl 6) or (4 shl 3) or mode).toByte()
        r[1] = stratum.toByte()
        SntpClient.writeTimestamp(r, 32, t2)
        SntpClient.writeTimestamp(r, 40, t3)
        return r
    }

    @Test
    fun timestampsRoundTripIncludingTheNextEra() {
        val buffer = ByteArray(8)
        for (ms in longArrayOf(0L, 1792396717233L, 2085978495999L /* 2036-02-07 06:28:15.999 */, 2085978496000L, 2200000000123L)) {
            SntpClient.writeTimestamp(buffer, 0, ms)
            assertEquals(ms, SntpClient.readTimestamp(buffer, 0))
        }
    }

    @Test
    fun offsetAndRoundTripFromTheFourTimestamps() {
        // local clock 1863 ms behind; 40 ms out, 60 ms back, 5 ms in the server
        val t1 = 1_000_000L
        val t2 = t1 + 1863 + 40
        val t3 = t2 + 5
        val t4 = t3 - 1863 + 60
        val m = SntpClient.evaluate(response(t2, t3), t1, t4, "test")
        assertEquals(1863 - 10L, m.offsetMs) // half the 20 ms asymmetry
        assertEquals(100L, m.roundTripMs)
    }

    @Test
    fun unsynchronisedOrInvalidAnswersAreRejected() {
        assertThrows(IOException::class.java) { SntpClient.evaluate(response(1, 1, stratum = 0), 0, 0, "kiss-o'-death") }
        assertThrows(IOException::class.java) { SntpClient.evaluate(response(1, 1, leap = 3), 0, 0, "alarm") }
        assertThrows(IOException::class.java) { SntpClient.evaluate(response(1, 1, mode = 3), 0, 0, "client packet") }
    }
}
