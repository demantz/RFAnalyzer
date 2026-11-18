package com.mantz_it.rfanalyzer.digiext.r09

import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.R09Phy
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * <h1>RF Analyzer - R09 Telegram</h1>
 *
 * Module:      R09Telegram.kt
 * Description: The fields of an R09.1x telegram (German public-transport priority request, VDV 420)
 *              and their decoder. The field names follow the German specification.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@Serializable
data class R09Paket(
    val typ: Int,                 // R09.x
    val zw: Int,                  // Fahrplanabweichung
    val laenge: Int,              // Länge
    val mp: Int,                  // Meldepunkt
    val tabelleneintrag: Tabelleneintrag,
    val prioritaet: Int,
    val handanforderung: Int,
    val liniennummer: Int,
    val kursnummer: Int,
    val zielnummer: Int,
    val zuglaenge: Int,
) {
    override fun toString(): String {
        return "[R09.$typ] " +
                "ZW:%+d  MP:%5d %-7s  PR:%d  HA:%d  LN:%3d  KN:%2d  ZN:%3d  ZL:%d"
                    .format(Locale.US, zw, mp, tabelleneintrag, prioritaet, handanforderung, liniennummer, kursnummer, zielnummer, zuglaenge)
    }

    enum class Tabelleneintrag {
        VORANM, HPTANM, ABMELD, ABFBER
    }
}

/** A received R09 telegram. */
typealias R09Telegram = StoredPacket<R09Phy, R09Paket>

/**
 * Decodes a telegram the extension found valid (CRC and the R09.1 type nibbles checked there). Its
 * bits arrive packed LSB first within each byte; the fields are 4-bit nibbles, also LSB first.
 */
object R09Parser {
    /** Bump whenever [parse]'s output changes for the same telegram; stored telegrams are re-parsed. */
    const val PARSER_VERSION = 1

    fun parse(raw: Bytes): R09Paket {
        // A field the telegram is too short to carry reads as 0, like the fields its Länge leaves out.
        fun nibble(n: Int): Int = if (n / 2 < raw.size) (raw[n / 2].toInt() shr (4 * (n % 2))) and 0xF else 0

        val laenge = nibble(2)
        // Fahrplanabweichung: sign and magnitude.
        val zw = nibble(3).let { if (it > 7) -(it - 8) else it }

        val mpRaw = if (laenge > 0) nibble(5) * 4096 + nibble(4) * 256 + nibble(7) * 16 + nibble(6)
        else nibble(5) * 16 + nibble(4)
        val tabelleneintrag = R09Paket.Tabelleneintrag.entries[mpRaw % 4]

        return R09Paket(
            typ = 10 + laenge,
            zw = zw,
            laenge = laenge,
            mp = mpRaw / 4,
            tabelleneintrag = tabelleneintrag,
            prioritaet = if (laenge > 1) nibble(9) shr 2 else 0,
            handanforderung = if (laenge > 1) nibble(9) and 0x3 else 0,
            // Linie, Kurs and Ziel are BCD.
            liniennummer = if (laenge > 2) nibble(8) * 100 + nibble(11) * 10 + nibble(10) else 0,
            kursnummer = if (laenge > 3) nibble(13) * 10 + nibble(12) else 0,
            zielnummer = if (laenge > 5) nibble(15) * 100 + nibble(14) * 10 + nibble(17) else 0,
            zuglaenge = if (laenge > 5) nibble(16) and 0x7 else 0,
        )
    }
}
