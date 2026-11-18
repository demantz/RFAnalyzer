package com.mantz_it.rfanalyzer.digiext.r09

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - R09 Parser Tests</h1>
 *
 * Module:      TestR09Parser.kt
 * Description: Tests for [R09Parser]: the telegram's 4-bit fields, packed LSB first as the
 *              extension sends them.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestR09Parser {

    /** Packs [nibbles] two per byte, the first in the low half. */
    private fun telegram(vararg nibbles: Int): Bytes =
        Bytes(ByteArray((nibbles.size + 1) / 2) { i -> (nibbles[2 * i] or (nibbles.getOrElse(2 * i + 1) { 0 } shl 4)).toByte() })

    @Test
    fun decodesAFullR09_16Telegram() {
        // R09.1, Länge 6, ZW -2, Meldepunkt 1165 with Tabelleneintrag 1 (HPTANM), PR 1 / HA 2,
        // Linie 413, Kurs 57, Ziel 869 (BCD, nibbles in the telegram's own order), Zuglänge 3.
        val mpRaw = 4 * 1165 + 1
        val paket = R09Parser.parse(telegram(
            1, 9, 6, 8 + 2,
            (mpRaw shr 8) and 0xF, (mpRaw shr 12) and 0xF, mpRaw and 0xF, (mpRaw shr 4) and 0xF,
            4, (1 shl 2) or 2, 3, 1,
            7, 5,
            6, 8, 3, 9,
        ))

        assertEquals(16, paket.typ)
        assertEquals(-2, paket.zw)
        assertEquals(1165, paket.mp)
        assertEquals(R09Paket.Tabelleneintrag.HPTANM, paket.tabelleneintrag)
        assertEquals(1, paket.prioritaet)
        assertEquals(2, paket.handanforderung)
        assertEquals(413, paket.liniennummer)
        assertEquals(57, paket.kursnummer)
        assertEquals(869, paket.zielnummer)
        assertEquals(3, paket.zuglaenge)
        assertEquals("L413/K57/Z869", r09ObjectKey(paket))
    }

    /** A short telegram carries no Kurs: it is stored but belongs to no bus. */
    @Test
    fun aShortTelegramHasNoBus() {
        val paket = R09Parser.parse(telegram(1, 9, 0, 0, 2, 3))
        assertEquals(10, paket.typ)
        assertEquals((3 * 16 + 2) / 4, paket.mp)
        assertEquals("", r09ObjectKey(paket))
    }

    /** A Länge longer than the telegram reads the missing fields as 0 rather than failing. */
    @Test
    fun aTruncatedTelegramDoesNotThrow() {
        val paket = R09Parser.parse(telegram(1, 9, 6, 0))
        assertEquals(16, paket.typ)
        assertEquals(0, paket.liniennummer)
    }
}
