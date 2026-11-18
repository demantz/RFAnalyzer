package com.mantz_it.rfanalyzer.digiext.pocsag

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPacket
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPhy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - POCSAG Parser Tests</h1>
 *
 * Module:      TestPocsagParser.kt
 * Description: Tests the observation -> interpretation step: that the raw payload bits carried over
 *              IPC decode to the page's text, and that re-parsing a stored observation works (the
 *              property the whole observation/interpretation split exists for).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestPocsagParser {

    /** Packs [text] the way the demodulator does - 7-bit reversed ASCII, MSB first - so a test can
     *  start from readable text. Inverse of [PocsagParser.decodeAlpha]. */
    private fun packAlpha(text: String): Pair<ByteArray, Int> {
        val bits = StringBuilder()
        for (c in text) {
            val v = c.code and 0x7F
            // Reversed: the demodulator's rev7 flips the 7 LSBs, so emit them LSB-first.
            for (i in 0 until 7) bits.append((v ushr i) and 1)
        }
        val bitCount = bits.length
        val bytes = ByteArray((bitCount + 7) / 8)
        for (i in 0 until bitCount) {
            if (bits[i] == '1') {
                bytes[i / 8] = (bytes[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            }
        }
        return bytes to bitCount
    }

    private fun packetOf(text: String, address: Long = 1234567L, function: Int = 3): PocsagPacket {
        val (bytes, bitCount) = packAlpha(text)
        return PocsagPacket(raw = Bytes(bytes), phy = PocsagPhy(address = address, function = function, baudRate = 1200, bitCount = bitCount))
    }

    private fun parse(packet: PocsagPacket) = PocsagParser.parse(packet.raw, packet.phy)

    @Test
    fun alphaTextRoundTripsThroughTheObservation() {
        val original = "512 B SIDE ZZZZZZ"
        val parsed = parse(packetOf(original))

        assertEquals(original, parsed.alphaMessage)
        assertEquals(PocsagAlphaEncoding.PLAIN, parsed.alphaEncoding)
    }

    @Test
    fun skyperContentIsClassifiedAndDecoded() {
        val onAir = "z\"Nbyjnvn!pg!JTT!bu!21;26!VUD/"
        val parsed = parse(packetOf(onAir))

        assertEquals(PocsagAlphaEncoding.SKYPER, parsed.alphaEncoding)
        assertEquals("Maximum of ISS at 10:15 UTC.", parsed.skyperMessage)
        assertEquals("y!", parsed.skyperTag)
    }

    @Test
    fun addressFunctionAndBaudRateCarryThroughUninterpreted() {
        val page = PocsagSpec.interpret(packetOf("some page text", address = 4520L, function = 1), frequencyHz = 0L).packet

        assertEquals(PocsagPhy(address = 4520L, function = 1, baudRate = 1200, bitCount = 14 * 7), page.phy)
        assertEquals("4520", page.objectKey)
    }

    @Test
    fun emptyPayloadYieldsNoText() {
        val parsed = PocsagParser.parse(Bytes.EMPTY, PocsagPhy(address = 1L, function = 0, baudRate = 1200, bitCount = 0))

        assertNull(parsed.alphaMessage)
        assertNull(parsed.numericMessage)
        assertEquals(PocsagAlphaEncoding.UNKNOWN, parsed.alphaEncoding)
    }

    /** Both readings are always produced - the function bits are only a convention, so the UI, not
     *  the parser, decides which one to show. */
    @Test
    fun bothAlphaAndNumericReadingsAreAlwaysProduced() {
        val parsed = parse(packetOf("12345"))

        assertTrue(parsed.alphaMessage!!.isNotEmpty())
        assertTrue(parsed.numericMessage!!.isNotEmpty())
    }

    @Test
    fun interpretingStampsTheCurrentParserVersion() {
        val page = PocsagSpec.interpret(packetOf("512 B SIDE ZZZZZZ"), frequencyHz = 0L).packet
        assertEquals(PocsagParser.PARSER_VERSION, page.parserVersion)
    }

    /**
     * The point of storing the raw bits: a row parsed by an older version is re-parsed later
     * without the original signal. Simulates a stale row and asserts re-parsing refreshes it.
     */
    @Test
    fun aStaleStoredRowIsReParsed() {
        val fresh = PocsagSpec.interpret(packetOf("z\"Nbyjnvn!pg!JTT!bu!21;26!VUD/"), frequencyHz = 0L).packet
        val stale = fresh.copy(
            rowId = 7L,
            parserVersion = 0,
            data = fresh.data.copy(alphaEncoding = PocsagAlphaEncoding.UNKNOWN, skyperMessage = null, skyperTag = null),
        )

        val refreshed = PocsagSpec.reparsed(PocsagSpec.toRow(stale))!!.interpreted.packet

        assertEquals(PocsagParser.PARSER_VERSION, refreshed.parserVersion)
        assertEquals(PocsagAlphaEncoding.SKYPER, refreshed.data.alphaEncoding)
        assertEquals("Maximum of ISS at 10:15 UTC.", refreshed.data.skyperMessage)
        assertEquals(7L, refreshed.rowId) // rewritten in place
        assertNull(PocsagSpec.reparsed(PocsagSpec.toRow(refreshed))) // and then up to date
    }

    private fun damagedPacketOf(text: String, damaged: List<Int>): PocsagPacket =
        packetOf(text).let { it.copy(phy = it.phy.copy(damagedCodewords = damaged)) }

    /** Codeword 1 is payload bits 20..39: alpha characters 2 (bits 14..20) to 5 (35..41), "e qu", touch it. */
    @Test
    fun damagedCodewordMarksTheCharactersItTouches() {
        val original = "The quick brown fox jumps over the lazy dog"
        val alpha = parse(damagedPacketOf(original, listOf(1))).alphaMessage!!
        val d = PocsagParser.DAMAGED
        assertEquals("Th$d$d$d${d}ick brown fox jumps over the lazy dog", alpha)
        assertEquals(PocsagAlphaEncoding.PLAIN, parse(damagedPacketOf(original, listOf(1))).alphaEncoding)
    }

    /** Four bits per digit: codeword 1 is digits 5..9. */
    @Test
    fun damagedCodewordMarksItsFiveDigits() {
        val numeric = PocsagParser.decodeNumeric(ByteArray(8), 60, damaged = listOf(1))!!
        val d = PocsagParser.DAMAGED
        assertEquals("00000$d$d$d$d${d}00000", numeric)
    }

    /** The Skyper statistics skip damaged characters; the decoded text keeps their marks. */
    @Test
    fun skyperSurvivesADamagedCodeword() {
        val parsed = parse(damagedPacketOf("z\"Nbyjnvn!pg!JTT!bu!21;26!VUD/", listOf(2)))
        assertEquals(PocsagAlphaEncoding.SKYPER, parsed.alphaEncoding)
        val d = PocsagParser.DAMAGED
        // codeword 2 = bits 40..59: characters 5 (35..41) to 8 (56..62), "imum" of "y!Maximum"
        assertEquals("Max$d$d$d${d} of ISS at 10:15 UTC.", parsed.skyperMessage)
    }

    @Test
    fun damageMarkStaysPrintable() {
        assertEquals("a${PocsagParser.DAMAGED}·b", "a${PocsagParser.DAMAGED}\u0001b".toPrintable())
    }
}
