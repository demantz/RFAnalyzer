package com.mantz_it.rfanalyzer.digiext.ft8

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - FT8 Parser Tests</h1>
 *
 * Module:      Ft8ParserTest.kt
 * Description: Tests for [Ft8Unpacker]: every message of the jt9 fixture (`ft8/jt9_messages.txt`)
 *              reads as jt9 prints it; free text, telemetry, standard messages, RR73 sent as a grid
 *              square, hashed callsigns resolved from earlier messages, contest exchanges and
 *              reserved message types.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class Ft8ParserTest {

    private fun payload(bits: String): Bytes {
        val bytes = ByteArray(12)
        for (i in bits.indices) if (bits[i] == '1') bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        return Bytes(bytes)
    }

    private fun hexPayload(hex: String) = Bytes(ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() })

    /** Bits of the given (value, width) fields, MSB first. */
    private fun fields(vararg f: Pair<Long, Int>): String = f.joinToString("") { (v, w) -> java.lang.Long.toBinaryString(v).padStart(w, '0').takeLast(w) }

    /** c28 of a standard callsign (its digit is the 3rd character, padded in front: " K1ABC"). */
    private fun c28(call: String): Long {
        val padded = (if (call[1].isDigit()) " $call" else call).padEnd(6)
        val alnum = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        val letters = " ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        var n = alnum.indexOf(padded[0]).toLong()
        n = n * 36 + alnum.indexOf(padded[1]) - 1
        n = n * 10 + (padded[2] - '0')
        for (i in 3..5) n = n * 27 + letters.indexOf(padded[i])
        return n + 2_063_592 + 4_194_304
    }

    /** jt9's text and ours in one form: hashed calls as "<...>" (jt9 decodes each period on its own). */
    private fun normalize(text: String) = text.replace(Regex("<[^>]*>"), "<...>").trim().split(Regex("\\s+")).joinToString(" ")

    @Test
    fun everyMessageJt9DecodedReadsAsJt9PrintsIt() {
        // decodes of the samples-repo recordings that jt9 decoded too, with jt9's text (see the file's header)
        val lines = javaClass.classLoader!!.getResource("ft8/jt9_messages.txt")!!.readText().lines().filter { it.isNotBlank() && !it.startsWith("#") }
        assertTrue(lines.size > 2000)
        val unpacker = Ft8Unpacker()
        val wrong = lines.mapNotNull { line ->
            val (hex, jt9) = line.split(" ", limit = 2)
            val ours = unpacker.unpack(hexPayload(hex)).text
            if (normalize(ours) == normalize(jt9)) null else "$jt9 -> $ours"
        }
        assertEquals("${wrong.size} of ${lines.size} differ: ${wrong.take(10)}", 0, wrong.size)
    }

    @Test
    fun freeTextAndTelemetry() {
        val u = Ft8Unpacker()
        assertEquals(TextMessage("TNX FER QSO"), u.unpack(payload("00000000000011101000000010001101100001001000000111110010011110000000111000000")))
        assertEquals(TextMessage("ABCDEFGHIJKLM"), u.unpack(payload("00100100111001000010000000001011111011100010010110011110110001100110111000000")))
        assertEquals(TelemetryMessage("1A2B"), u.unpack(payload("00000000000000000000000000000000000000000000000000000000001101000101011101000")))
    }

    @Test
    fun standardMessages() {
        val u = Ft8Unpacker()
        assertEquals("VK1ABC VK3JPK QF22", u.unpack(payload("11100001111111000101001101010111000100000011110100001111000111001010001010001")).text)
        assertEquals("DL1ABC K1ABC -30", u.unpack(payload("01101000100010010100001000110000010011011110111100011010100111111010010101001")).text)
        assertEquals("DL1ABC K1ABC +00", u.unpack(payload("01101000100010010100001000110000010011011110111100011010100111111010110011001")).text)
        assertEquals("DL1ABC K1ABC RR73", u.unpack(payload("01101000100010010100001000110000010011011110111100011010100111111010010011001")).text)
        assertEquals("DL1ABC/P K1ABC +05", u.unpack(payload("01101000100010010100001000111000010011011110111100011010100111111010111000010")).text)
        val cq = u.unpack(payload(fields(2L to 28, 0L to 1, c28("K1ABC") to 28, 0L to 1, 0L to 1, (5L * 1800 + 13 * 100 + 42) to 15, 1L to 3)))
        assertEquals("CQ K1ABC FN42", cq.text)
        // a CQ calls no station
        assertEquals("K1ABC" to null, ft8Transmitter(cq)!!.let { it.first to it.second })
    }

    @Test
    fun rr73SentAsAGridSquareIsTheAcknowledgement() {
        // "KQ4SUB LZ2II RR73" heard on 2026-10-19 with g15 = 32373, the grid square RR73
        val grid = ('R' - 'A') * 1800 + ('R' - 'A') * 100 + 7 * 10 + 3
        val m = Ft8Unpacker().unpack(payload(fields(c28("KQ4SUB") to 28, 0L to 1, c28("LZ2II") to 28, 0L to 1, 0L to 1, grid.toLong() to 15, 1L to 3)))
        assertEquals("KQ4SUB LZ2II RR73", m.text)
        assertEquals(OtherReport("RR73"), (m as StandardMessage).report)
    }

    @Test
    fun hashedCallsignsResolveFromEarlierMessages() {
        val u = Ft8Unpacker()
        // nothing heard yet
        assertEquals("<...> PJ4/K1ABC RRR", u.unpack(payload("10110010001100000000000110100011101000110001000111001010101000000000010010100")).text)
        // K1ABC in a standard message, then its 12-bit hash
        u.unpack(payload("01101000100010010100001000110000010011011110111100011010100111111010010101001"))
        assertEquals("PJ4/K1ABC <K1ABC> RR73", u.unpack(payload("10110010001100000000000110100011101000110001000111001010101000000000011100100")).text)
        assertEquals("CQ PJ4/K1ABC", u.unpack(payload("01010110101100000000000110100011101000110001000111001010101000000000010001100")).text)
        // PJ4/K1ABC (heard above) as a DXpedition's 10-bit hash
        val dx = u.unpack(payload(fields(c28("W9XYZ") to 28, c28("G4ABC") to 28, Ft8Unpacker.hash("PJ4/K1ABC", 10).toLong() to 10, 11L to 5, 1L to 3, 0L to 3)))
        assertEquals("W9XYZ RR73; G4ABC <PJ4/K1ABC> -08", dx.text)
        // and as a 22-bit hash in a standard message
        val std = u.unpack(payload(fields(c28("W9XYZ") to 28, 0L to 1, (2_063_592L + Ft8Unpacker.hash("PJ4/K1ABC", 22)) to 28, 0L to 1, 1L to 1, (32_400L + 35 - 12) to 15, 1L to 3)))
        assertEquals("W9XYZ <PJ4/K1ABC> R-12", std.text)
    }

    @Test
    fun contestExchanges() {
        val u = Ft8Unpacker()
        assertEquals("K1ABC DL1ABC R 549 CA", u.unpack(payload("00000100110111101111000110101011010001000100101000010001110101111101000101011")).text)
        val rttySerial = u.unpack(payload(fields(1L to 1, c28("W9XYZ") to 28, c28("K1ABC") to 28, 0L to 1, 7L to 3, 13L to 13, 3L to 3)))
        assertEquals("TU; W9XYZ K1ABC 599 0013", rttySerial.text)
        // ARRL Field Day: 16A EMA (section 11), and 18 transmitters (n3 = 4)
        val fd = u.unpack(payload(fields(c28("WA9XYZ") to 28, c28("KA1ABC") to 28, 1L to 1, 15L to 4, 0L to 3, 11L to 7, 3L to 3, 0L to 3)))
        assertEquals("WA9XYZ KA1ABC R 16A EMA", fd.text)
        assertEquals("KA1ABC", ft8Transmitter(fd)!!.first)
        val fd4 = u.unpack(payload(fields(c28("WA9XYZ") to 28, c28("KA1ABC") to 28, 0L to 1, 1L to 4, 1L to 3, 86L to 7, 4L to 3, 0L to 3)))
        assertEquals("WA9XYZ KA1ABC 18B NB", fd4.text)
        // EU VHF contest: hashes, 59 + serial 3, grid IO91NP
        val grid = ((((('I' - 'A') * 18 + ('O' - 'A')) * 10 + 9) * 10 + 1) * 24 + ('N' - 'A')) * 24 + ('P' - 'A')
        val vhf = u.unpack(payload(fields(0L to 12, 0L to 22, 1L to 1, 7L to 3, 3L to 11, grid.toLong() to 25, 5L to 3)))
        assertEquals("<...> <...> R 590003 IO91NP", vhf.text)
        assertEquals("IO91NP", (vhf as EuVhfContestMessage).grid)
    }

    @Test
    fun reservedTypesAreKeptAsUnknown() {
        val u = Ft8Unpacker()
        assertEquals(UnknownMessage(6), u.unpack(payload(fields(0L to 74, 6L to 3))))
        assertEquals(UnknownMessage(0, 2), u.unpack(payload(fields(0L to 71, 2L to 3, 0L to 3))))
    }
}
