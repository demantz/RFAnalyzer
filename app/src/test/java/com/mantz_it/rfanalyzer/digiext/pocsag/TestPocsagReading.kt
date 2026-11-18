package com.mantz_it.rfanalyzer.digiext.pocsag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - POCSAG Reading Tests</h1>
 *
 * Module:      TestPocsagReading.kt
 * Description: Tests for [primaryReading] / [primaryText] / [secondaryText] - which of a POCSAG
 *              page's two always-produced readings actually carries its content.
 *
 * The byte-level classifier describes the *alpha reading* only, never the *page* ("encrypted"), and
 * pages where the two differ are what these tests are about. The fixtures are synthetic: texts
 * written for these tests, with the other reading as [PocsagParser] derives it from the same bits.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestPocsagReading {

    private fun message(
        function: Int,
        alpha: String? = null,
        numeric: String? = null,
        encoding: PocsagAlphaEncoding = PocsagAlphaEncoding.UNKNOWN,
        skyper: String? = null,
    ) = pocsagPage(function = function, alpha = alpha, numeric = numeric, encoding = encoding, skyper = skyper)

    /**
     * Capcode 2504 broadcasts the time and date every few minutes as an fn0 numeric page. Its
     * *alpha* reading is non-ASCII by construction, which must not make the page "[encrypted]" -
     * the numeric reading right next to it says `120500   150326`, i.e. 12:05:00 on 15.03.26.
     */
    @Test
    fun capcode2504_numericTimeBroadcast_isNotTreatedAsEncrypted() {
        val msg = message(
            function = 0,
            alpha = "! \u0001`L#A\u0011",
            numeric = "120500   150326",
            encoding = PocsagAlphaEncoding.BINARY,
        )
        assertEquals(PocsagReading.NUMERIC, msg.primaryReading)
        assertEquals("120500   150326", msg.primaryText)
        // The alpha reading is not hidden either - it is just demoted to secondary.
        assertEquals("! \u0001`L#A\u0011", msg.secondaryText)
    }

    /**
     * The other half, and the reason the classifier outranks the function code.
     *
     * Some senders transmit readable text - classified PLAIN - as **fn0**, the numeric service,
     * whose reading of it is `3467-5 027799]02.[U-...`. So the function bits mislead in both
     * directions; only the byte-level classification separates this from capcode 2504 above.
     */
    @Test
    fun textOnFunction0_staysAlpha() {
        val msg = message(
            function = 0,
            alpha = "Club net tonight 20:00 local",
            numeric = "3467-5 027799]02.[U--9]33.9]0291 47038188-[]1789-",
            encoding = PocsagAlphaEncoding.PLAIN,
        )
        assertEquals(PocsagReading.ALPHA, msg.primaryReading)
        assertEquals("Club net tonight 20:00 local", msg.primaryText)
    }

    /** fn0 only wins when the alpha reading is *not* demonstrably text (UNKNOWN here: too short). */
    @Test
    fun function0_prefersNumeric() {
        val msg = message(function = 0, alpha = "garbage", numeric = "12345")
        assertEquals(PocsagReading.NUMERIC, msg.primaryReading)
        assertEquals("12345", msg.primaryText)
    }

    /** A PLAIN alpha reading outranks fn0 even when the numeric reading is non-blank. */
    @Test
    fun plainAlphaOutranksFunction0() {
        val msg = message(function = 0, alpha = "readable text here", numeric = "12345", encoding = PocsagAlphaEncoding.PLAIN)
        assertEquals(PocsagReading.ALPHA, msg.primaryReading)
        assertEquals("readable text here", msg.primaryText)
        assertEquals("12345", msg.secondaryText)
    }

    /** BINARY must *not* be promoted - that is what keeps capcode 2504 on its numeric reading. */
    @Test
    fun binaryAlphaDoesNotOutrankFunction0() {
        val msg = message(function = 0, alpha = "not text", numeric = "12345", encoding = PocsagAlphaEncoding.BINARY)
        assertEquals(PocsagReading.NUMERIC, msg.primaryReading)
    }

    @Test
    fun function3_prefersAlpha() {
        val msg = message(function = 3, alpha = "7074.0 N0CALL de DM4NTZ", numeric = "738--86]2818 90U16099 4019U 0226396]4..6")
        assertEquals(PocsagReading.ALPHA, msg.primaryReading)
        assertEquals("7074.0 N0CALL de DM4NTZ", msg.primaryText)
        assertEquals("738--86]2818 90U16099 4019U 0226396]4..6", msg.secondaryText)
    }

    @Test
    fun function2_prefersAlpha() {
        assertEquals(PocsagReading.ALPHA, message(function = 2, alpha = "hello", numeric = "88").primaryReading)
    }

    @Test
    fun skyperOverridesFunction() {
        val msg = message(
            function = 3,
            alpha = "25185/1!O1DBMM!!!!!!ef!EN5OU[!bu!2311{",
            numeric = "junk",
            encoding = PocsagAlphaEncoding.SKYPER,
            skyper = "14074.0 N0CALL      de DM4NTZ at 1200z",
        )
        assertEquals(PocsagReading.SKYPER, msg.primaryReading)
        assertEquals("14074.0 N0CALL      de DM4NTZ at 1200z", msg.primaryText)
        // The raw shifted alpha adds nothing once un-shifted, so numeric is the useful secondary.
        assertEquals("junk", msg.secondaryText)
    }

    /**
     * Pages without payload come with every function code, not only fn1, so an address-only page
     * must be recognised from the absent payload, not from the function code.
     */
    @Test
    fun noPayload_isToneRegardlessOfFunction() {
        for (function in listOf(0, 1, 2, 3)) {
            val msg = message(function = function, alpha = null, numeric = null)
            assertEquals("fn=$function", PocsagReading.TONE, msg.primaryReading)
            assertNull(msg.primaryText)
        }
    }

    /** A payload that is present but decodes to blanks under both readings is NOT a tone page. */
    @Test
    fun blankPayload_isNoneNotTone() {
        val msg = message(function = 0, alpha = "   ", numeric = "     ")
        assertEquals(PocsagReading.NONE, msg.primaryReading)
        assertNull(msg.primaryText)
    }

    @Test
    fun function0_fallsBackToAlphaWhenNumericBlank() {
        val msg = message(function = 0, alpha = "real text", numeric = "   ")
        assertEquals(PocsagReading.ALPHA, msg.primaryReading)
        assertEquals("real text", msg.primaryText)
    }

    @Test
    fun function3_fallsBackToNumericWhenAlphaBlank() {
        val msg = message(function = 3, alpha = "  ", numeric = "1234")
        assertEquals(PocsagReading.NUMERIC, msg.primaryReading)
        assertEquals("1234", msg.primaryText)
    }

    /** POCSAG pads a short message's tail with NULs up to payloadBitCount/7 characters. */
    @Test
    fun trailingNulPaddingIsTrimmed() {
        val msg = message(function = 3, alpha = "hi" + "\u0000".repeat(4))
        assertEquals(PocsagReading.ALPHA, msg.primaryReading)
        assertEquals("hi", msg.primaryText)
    }

    @Test
    fun nulOnlyPayloadIsNone() {
        val msg = message(function = 3, alpha = "\u0000".repeat(3))
        assertEquals(PocsagReading.NONE, msg.primaryReading)
    }

    @Test
    fun toPrintableReplacesControlBytesOnly() {
        assertEquals("A·B·C", "A\u0000B\u001FC".toPrintable())
        assertEquals("plain text 123", "plain text 123".toPrintable())
        assertEquals("A#B", "A\u0080B".toPrintable('#'))
    }
}
