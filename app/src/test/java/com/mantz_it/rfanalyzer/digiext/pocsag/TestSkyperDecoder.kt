package com.mantz_it.rfanalyzer.digiext.pocsag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - Skyper Decoder Tests</h1>
 *
 * Module:      TestSkyperDecoder.kt
 * Description: Pure string-level tests for [SkyperDecoder] - no IQ streaming needed since
 *              classification and decoding operate purely on already-decoded alphanumeric text.
 *
 * The positive samples are synthetic: messages written for these tests in the forms amateur-radio
 * Skyper bulletins use (a word tag, a two-character marker, a lead marker before the tag), shifted
 * as they are sent.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestSkyperDecoder {

    @Test
    fun testNewsBulletin_wordTag() {
        val alpha = ";\"OFXT;!Dmvc!ofu!upojhiu!po!256/611-!bmm!tubujpot!xfmdpnf\""
        val result = SkyperDecoder.classify(alpha)
        assertEquals(PocsagAlphaEncoding.SKYPER, result.encoding)
        assertEquals("Club net tonight on 145.500, all stations welcome!", result.skyperMessage)
        assertEquals("NEWS", result.skyperTag)
    }

    @Test
    fun testIssMaximum_fallbackTag() {
        val alpha = "z\"Nbyjnvn!pg!JTT!bu!21;26!VUD/"
        val result = SkyperDecoder.classify(alpha)
        assertEquals(PocsagAlphaEncoding.SKYPER, result.encoding)
        assertEquals("Maximum of ISS at 10:15 UTC.", result.skyperMessage)
        assertEquals("y!", result.skyperTag)
    }

    @Test
    fun testClubBulletin_wordTag() {
        val alpha = ";\$DMVC;!Nffujoh!npwfe!up!uif!dpnnvojuz!ibmm"
        val result = SkyperDecoder.classify(alpha)
        assertEquals(PocsagAlphaEncoding.SKYPER, result.encoding)
        assertEquals("Meeting moved to the community hall", result.skyperMessage)
        assertEquals("CLUB", result.skyperTag)
    }

    @Test
    fun testIssRise_fallbackTag() {
        val alpha = "R!Sjtf!pg!JTT!bu!21;1:!VUD/"
        val result = SkyperDecoder.classify(alpha)
        assertEquals(PocsagAlphaEncoding.SKYPER, result.encoding)
        assertEquals("Rise of ISS at 10:09 UTC.", result.skyperMessage)
        assertEquals("Q ", result.skyperTag)
    }

    @Test
    fun testBeaconSpot_fallbackTag() {
        val alpha = "&&2555:2/1!O1DBMM0C!!!ef!EN5OU[!bu!2311{"
        val result = SkyperDecoder.classify(alpha)
        assertEquals(PocsagAlphaEncoding.SKYPER, result.encoding)
        assertEquals("144491.0 N0CALL/B   de DM4NTZ at 1200z", result.skyperMessage)
        assertEquals("%%", result.skyperTag)
    }

    @Test
    fun testOtaBulletin_wordTagWithLeadMarker() {
        val alpha = "Y\"yPUB;!Tqput!gps!tvnnju-!dbtumf!boe!qbsl!bdujwbujpot!po!3!n"
        val result = SkyperDecoder.classify(alpha)
        assertEquals(PocsagAlphaEncoding.SKYPER, result.encoding)
        assertEquals("Spots for summit, castle and park activations on 2 m", result.skyperMessage)
        assertEquals("xOTA", result.skyperTag)
    }

    // --- Negative cases: plain text must never be misclassified as SKYPER ---

    @Test
    fun testPlainSentenceWithNormalSpacing_notSkyper() {
        val result = SkyperDecoder.classify("Hello this is a plain test message with normal spacing")
        assertEquals(PocsagAlphaEncoding.PLAIN, result.encoding)
        assertNull(result.skyperMessage)
        assertNull(result.skyperTag)
    }

    @Test
    fun testGt512Content_notSkyper() {
        // The text of the extension's synthetic POCSAG test signal - real spaces.
        val result = SkyperDecoder.classify("512 B SIDE ZZZZZZ")
        assertEquals(PocsagAlphaEncoding.PLAIN, result.encoding)
    }

    @Test
    fun testGt1200Content_notSkyper() {
        // Synthetic fixture ground truth - no spaces at all, and its rot-1 shift doesn't produce
        // plausible word spacing either.
        val result = SkyperDecoder.classify("+++TIME=0008300324+++TIME=0008300324")
        assertEquals(PocsagAlphaEncoding.PLAIN, result.encoding)
    }

    @Test
    fun testTimestampText_notSkyper() {
        val result = SkyperDecoder.classify("YYYYMMDDHHMMSS20260315120500")
        assertEquals(PocsagAlphaEncoding.PLAIN, result.encoding)
    }

    // --- Negative case: an ordinary alphanumeric start must not get a fallback tag ---

    @Test
    fun testFallbackTagRejectsOrdinaryWordStart() {
        // Fabricated: same rot-1 signature as a real Skyper message, but starting with two
        // plain alphanumeric characters ("Hi") rather than a punctuation-containing marker.
        val plainStart = "Hi there, this looks like real Skyper content but starts with a word."
        val shifted = plainStart.map { c -> if (c.code in 32..125) (c.code + 1).toChar() else c }.joinToString("")
        val result = SkyperDecoder.classify(shifted)
        assertEquals(PocsagAlphaEncoding.SKYPER, result.encoding)
        assertNull(result.skyperTag)
    }

    /**
     * Two *different* punctuation characters are content, not a marker.
     *
     * Leads like `"%`, `$"`, `&'` and `[ ` begin ordinary messages: taken as a tag, their first two
     * characters would be eaten and shown as a "tag" badge. The doubled-character and
     * contains-an-alphanumeric forms above are the markers.
     */
    @Test
    fun testFallbackTagRejectsTwoDifferentPunctuationChars() {
        for (lead in listOf("\"%", "$\"", "&'", "[ ")) {
            val plain = lead + "14074.0 N0CALL      de DM4NTZ at 1200z"
            val shifted = plain.map { c -> if (c.code in 32..125) (c.code + 1).toChar() else c }.joinToString("")
            val result = SkyperDecoder.classify(shifted)
            assertEquals("lead=$lead", PocsagAlphaEncoding.SKYPER, result.encoding)
            assertNull("lead=$lead", result.skyperTag)
            // Rejecting the tag must leave the message intact rather than silently truncating it.
            assertEquals("lead=$lead", plain, result.skyperMessage)
        }
    }

    // --- Edge cases ---

    @Test
    fun testNullAlpha_unknown() {
        assertEquals(PocsagAlphaEncoding.UNKNOWN, SkyperDecoder.classify(null).encoding)
    }

    @Test
    fun testEmptyAlpha_unknown() {
        assertEquals(PocsagAlphaEncoding.UNKNOWN, SkyperDecoder.classify("").encoding)
    }

    @Test
    fun testShortAlpha_unknown() {
        // Below the minimum length needed to trust the statistics - must not guess.
        assertEquals(PocsagAlphaEncoding.UNKNOWN, SkyperDecoder.classify("XTIME=").encoding)
    }

    @Test
    fun testHeavyControlCharContent_binary() {
        // Simulates an encrypted payload riding the 7-bit alpha channel:
        // mostly non-printable bytes rather than human-composed text.
        val binaryish = (0..39).map { i -> ((i * 7 + 3) % 32).toChar() }.joinToString("")
        val result = SkyperDecoder.classify(binaryish)
        assertEquals(PocsagAlphaEncoding.BINARY, result.encoding)
        assertNull(result.skyperMessage)
    }

    /** Trailing NUL padding is an artifact of unpacking bitCount/7 characters, not content - it
     *  must not push a short plain message over the control-character ratio into BINARY. */
    @Test
    fun testTrailingNulPaddingIsTrimmedNotCountedAsBinary() {
        val padded = "512 B SIDE ZZZZZZ" + Char(0).toString().repeat(10)
        assertEquals(PocsagAlphaEncoding.PLAIN, SkyperDecoder.classify(padded).encoding)
    }
}
