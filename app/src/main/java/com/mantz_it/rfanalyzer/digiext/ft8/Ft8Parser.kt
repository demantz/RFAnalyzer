package com.mantz_it.rfanalyzer.digiext.ft8

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import java.math.BigInteger
import java.util.Locale

/**
 * <h1>RF Analyzer - FT8 Parser</h1>
 *
 * Module:      Ft8Parser.kt
 * Description: FT8's message parser: unpacks the 77 payload bits of an FT8 transmission ([Bytes] as
 *              the extension sends them, MSB first) into an [Ft8Message] - the FT4/FT8 message
 *              types identified by `i3` (last three bits) and, for i3 = 0, `n3` (the three before),
 *              as defined by the protocol (Franke, Somerville, Taylor: "The FT4 and FT8
 *              Communication Protocols", QEX July/August 2020).
 *
 * Some messages carry a callsign only as a 10-, 12- or 22-bit hash. The parser remembers the full
 * callsigns of the messages it parsed (the latest per hash value), so those are resolved from
 * earlier messages - packets must come in the order they were received, which both the live stream
 * and the start-up re-parse (by row id) do. Unresolved: "<...>", as in WSJT-X.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object Ft8Parser {
    /** Raise when [parse]'s output changes: stored packets are parsed again. */
    const val PARSER_VERSION = 1

    private val unpacker = Ft8Unpacker()

    fun parse(raw: Bytes): Ft8Message = synchronized(unpacker) { unpacker.unpack(raw) }
}

/** The unpacking itself, with its own callsign memory (see [Ft8Parser]). Not thread-safe. */
class Ft8Unpacker {
    companion object {
        const val PAYLOAD_BITS = 77

        // c28, the 28-bit callsign field: tokens, then 22-bit hashes, then standard callsigns
        private const val TOKENS = 2_063_592L
        private const val HASHES22 = 4_194_304L
        private const val FIRST_CQ_NUMBER = 3L          // "CQ 000" .. "CQ 999"
        private const val FIRST_CQ_LETTERS = 1_003L     // "CQ A" .. "CQ ZZZZ"
        private const val CQ_LETTER_CODES = 27L * 27 * 27 * 27
        private const val GRID4_CODES = 18 * 18 * 10 * 10   // the values above are reports
        private const val GRID6_CODES = 18 * 18 * 10 * 10 * 24 * 24

        // character sets of the packed fields
        private const val ALPHANUMERIC = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private const val LETTERS = " ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        /** Callsigns of the 58-bit field, and the alphabet callsigns are hashed in. */
        private const val CALL_CHARS = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ/"
        private const val TEXT_CHARS = " 0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ+-./?"
        private const val HASH_CALL_LENGTH = 11
        /** The protocol's multiplier for callsign hashes. */
        private const val HASH_MULTIPLIER = 47_055_833_459L

        /** ARRL/RAC sections of the Field Day exchange, by their code (1..86). Protocol data. */
        private val ARRL_SECTIONS = listOf(
            "AB", "AK", "AL", "AR", "AZ", "BC", "CO", "CT", "DE", "EB",
            "EMA", "ENY", "EPA", "EWA", "GA", "GH", "IA", "ID", "IL", "IN",
            "KS", "KY", "LA", "LAX", "NS", "MB", "MDC", "ME", "MI", "MN",
            "MO", "MS", "MT", "NC", "ND", "NE", "NFL", "NH", "NL", "NLI",
            "NM", "NNJ", "NNY", "TER", "NTX", "NV", "OH", "OK", "ONE", "ONN",
            "ONS", "OR", "ORG", "PAC", "PR", "QC", "RI", "SB", "SC", "SCV",
            "SD", "SDG", "SF", "SFL", "SJV", "SK", "SNJ", "STX", "SV", "TN",
            "UT", "VA", "VI", "VT", "WCF", "WI", "WMA", "WNY", "WPA", "WTX",
            "WV", "WWA", "WY", "DX", "PE", "NB",
        )
        /** States, provinces and other multipliers of the RTTY Roundup exchange, codes 8001... Protocol data. */
        private val RTTY_MULTIPLIERS = listOf(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DE", "FL", "GA",
            "HI", "ID", "IL", "IN", "IA", "KS", "KY", "LA", "ME", "MD",
            "MA", "MI", "MN", "MS", "MO", "MT", "NE", "NV", "NH", "NJ",
            "NM", "NY", "NC", "ND", "OH", "OK", "OR", "PA", "RI", "SC",
            "SD", "TN", "TX", "UT", "VT", "VA", "WA", "WV", "WI", "WY",
            "NB", "NS", "QC", "ON", "MB", "SK", "AB", "BC", "NWT", "NF",
            "LB", "NU", "YT", "PEI", "DC", "DR", "FR", "GD", "GR", "OV",
            "ZH", "ZL",
        ) + (1..99).map { "X%02d".format(Locale.US, it) }
        private const val FIRST_MULTIPLIER = 8001

        /** The [bits]-bit hash of [call] (up to 11 characters of [CALL_CHARS]). */
        fun hash(call: String, bits: Int): Int {
            var n = 0L
            for (i in 0 until HASH_CALL_LENGTH) n = 38 * n + CALL_CHARS.indexOf(call.getOrElse(i) { ' ' }).coerceAtLeast(0)
            return ((n * HASH_MULTIPLIER) ushr (64 - bits)).toInt()
        }
    }

    private val calls10 = HashMap<Int, String>()
    private val calls12 = HashMap<Int, String>()
    private val calls22 = HashMap<Int, String>()

    /** Makes [call] resolvable from its hashes. */
    fun remember(call: String) {
        if (call.isEmpty() || call.length > HASH_CALL_LENGTH || call.any { it !in CALL_CHARS }) return
        calls10[hash(call, 10)] = call
        calls12[hash(call, 12)] = call
        calls22[hash(call, 22)] = call
    }

    fun unpack(raw: Bytes): Ft8Message {
        require(raw.size * 8 >= PAYLOAD_BITS) { "an FT8 payload has $PAYLOAD_BITS bits" }
        val bits = Bits(raw)
        val i3 = bits.int(74, 3)
        return when (i3) {
            0 -> when (val n3 = bits.int(71, 3)) {
                0 -> TextMessage(text(bits))
                1 -> dxpedition(bits)
                3, 4 -> fieldDay(bits, n3)
                5 -> TelemetryMessage(bits.big(0, 71).toString(16).uppercase())
                else -> UnknownMessage(0, n3)
            }
            1, 2 -> standard(bits, i3)
            3 -> rtty(bits)
            4 -> nonstandard(bits)
            5 -> euVhfContest(bits)
            else -> UnknownMessage(i3)
        } ?: UnknownMessage(i3, if (i3 == 0) bits.int(71, 3) else null)
    }

    /** i3=1: c28 r1 c28 r1 R1 g15; i3=2: the same with p1 ("/P") for r1. */
    private fun standard(bits: Bits, i3: Int): Ft8Message? {
        val call1 = call28(bits.long(0, 28)) ?: return null
        val suffix1 = bits.flag(28)
        val call2 = call28(bits.long(29, 28)) ?: return null
        val suffix2 = bits.flag(57)
        val roger = bits.flag(58)
        val report = gridOrReport(bits.int(59, 15)) ?: return null
        return if (i3 == 1) StandardMessage(call1, suffix1, call2, suffix2, roger, report.value)
        else EUVHFMessage(call1, suffix1, call2, suffix2, roger, report.value)
    }

    /** A grid square, a report in dB, RRR/RR73/73 - or nothing (a wrapped null). Null if invalid. */
    private fun gridOrReport(g15: Int): Optional<Report>? {
        if (g15 < GRID4_CODES) {
            val grid = String(charArrayOf('A' + g15 / 1800, 'A' + g15 / 100 % 18, '0' + g15 / 10 % 10, '0' + g15 % 10))
            // some software sends the acknowledgement RR73 as that grid square (in the Arctic Ocean)
            return Optional(if (grid == "RR73") OtherReport("RR73") else LocationReport(grid))
        }
        return when (val code = g15 - GRID4_CODES) {
            0 -> null
            1 -> Optional(null)
            2 -> Optional(OtherReport("RRR"))
            3 -> Optional(OtherReport("RR73"))
            4 -> Optional(OtherReport("73"))
            else -> Optional(SignalReport((code - 35).let { if (it > 50) it - 101 else it }))
        }
    }

    private class Optional<T>(val value: T?)

    /** i3=3: t1 c28 c28 R1 r3 s13. */
    private fun rtty(bits: Bits): Ft8Message? {
        val call1 = call28(bits.long(1, 28)) ?: return null
        val call2 = call28(bits.long(29, 28)) ?: return null
        val exchange = bits.int(61, 13)
        val state = when {
            exchange >= FIRST_MULTIPLIER -> RTTY_MULTIPLIERS.getOrNull(exchange - FIRST_MULTIPLIER) ?: return null
            exchange < 8000 -> "%04d".format(Locale.US, exchange)
            else -> return null
        }
        return RTTYMessage(call1, call2, roger = bits.flag(57), RTTYSignal(529 + 10 * bits.int(58, 3)), RTTYState(state), thankYou = bits.flag(0))
    }

    /** i3=4: h12 c58 h1 r2 c1 - h1 says which of the two calls is the hash. */
    private fun nonstandard(bits: Bits): Ft8Message? {
        var n = bits.long(12, 58)
        val chars = CharArray(HASH_CALL_LENGTH)
        for (i in HASH_CALL_LENGTH - 1 downTo 0) {
            chars[i] = CALL_CHARS[(n % 38).toInt()]
            n /= 38
        }
        val call = String(chars).trim()
        if (call.isEmpty() || ' ' in call) return null
        remember(call)
        val full = Callsign(call)
        if (bits.flag(73)) return NonstandardCallMessage(Callsign("CQ", token = true), full, OtherReport(""))
        val hashed = hashed(calls12, bits.int(0, 12))
        val report = when (bits.int(71, 2)) {
            1 -> "RRR"
            2 -> "RR73"
            3 -> "73"
            else -> ""
        }
        return if (bits.flag(70)) NonstandardCallMessage(full, hashed, OtherReport(report))
        else NonstandardCallMessage(hashed, full, OtherReport(report))
    }

    /** i3=0 n3=1: c28 c28 h10 r5. */
    private fun dxpedition(bits: Bits): Ft8Message? {
        val call1 = call28(bits.long(0, 28))?.takeUnless { it.token } ?: return null
        val call2 = call28(bits.long(28, 28))?.takeUnless { it.token } ?: return null
        return DXpeditionMessage(call1, call2, hashed(calls10, bits.int(56, 10)), 2 * bits.int(66, 5) - 30)
    }

    /** i3=0 n3=3/4: c28 c28 R1 n4 k3 s7. */
    private fun fieldDay(bits: Bits, n3: Int): Ft8Message? {
        val call1 = call28(bits.long(0, 28)) ?: return null
        val call2 = call28(bits.long(28, 28)) ?: return null
        val section = ARRL_SECTIONS.getOrNull(bits.int(64, 7) - 1) ?: return null
        val transmitters = bits.int(57, 4) + 1 + if (n3 == 4) 16 else 0
        return FieldDayMessage(call1, call2, bits.flag(56), transmitters, ('A' + bits.int(61, 3)).toString(), section)
    }

    /** i3=5: h12 h22 R1 r3 s11 g25. */
    private fun euVhfContest(bits: Bits): Ft8Message? {
        var g = bits.int(49, 25)
        if (g >= GRID6_CODES) return null
        val grid = CharArray(6)
        val radices = intArrayOf(18, 18, 10, 10, 24, 24)
        val bases = charArrayOf('A', 'A', '0', '0', 'A', 'A')
        for (i in 5 downTo 0) {
            grid[i] = bases[i] + g % radices[i]
            g /= radices[i]
        }
        return EuVhfContestMessage(
            hashed(calls12, bits.int(0, 12)), hashed(calls22, bits.int(12, 22)),
            roger = bits.flag(34), report = 52 + bits.int(35, 3), serial = bits.int(38, 11), grid = String(grid),
        )
    }

    /** i3=0 n3=0: 71 bits = 13 characters base 42. */
    private fun text(bits: Bits): String {
        var n = bits.big(0, 71)
        val chars = CharArray(13)
        val radix = BigInteger.valueOf(TEXT_CHARS.length.toLong())
        for (i in 12 downTo 0) {
            val (q, r) = n.divideAndRemainder(radix)
            chars[i] = TEXT_CHARS[r.toInt()]
            n = q
        }
        return String(chars).trim()
    }

    /** The c28 field: a token (DE, QRZ, CQ, CQ nnn, CQ ABCD), a 22-bit hash or a standard callsign; null if invalid. */
    private fun call28(n: Long): Callsign? {
        when {
            n == 0L -> return Callsign("DE", n.toInt(), token = true)
            n == 1L -> return Callsign("QRZ", n.toInt(), token = true)
            n == 2L -> return Callsign("CQ", n.toInt(), token = true)
            n < FIRST_CQ_LETTERS -> return Callsign("CQ %03d".format(Locale.US, n - FIRST_CQ_NUMBER), n.toInt(), token = true)
            n < FIRST_CQ_LETTERS + CQ_LETTER_CODES -> {
                var m = n - FIRST_CQ_LETTERS
                val letters = CharArray(4)
                for (i in 3 downTo 0) {
                    letters[i] = LETTERS[(m % 27).toInt()]
                    m /= 27
                }
                val suffix = String(letters).trim()
                if (' ' in suffix) return null
                return Callsign("CQ $suffix", n.toInt(), token = true)
            }
            n < TOKENS -> return null
            n < TOKENS + HASHES22 -> return hashed(calls22, (n - TOKENS).toInt())
        }
        // standard callsign: 37 x 36 x 10 x 27 x 27 x 27 (alphanumeric or space, alphanumeric, digit, 3 letters or space)
        var m = n - TOKENS - HASHES22
        val c = CharArray(6)
        for (i in 5 downTo 3) {
            c[i] = LETTERS[(m % 27).toInt()]
            m /= 27
        }
        c[2] = '0' + (m % 10).toInt()
        m /= 10
        c[1] = ALPHANUMERIC[1 + (m % 36).toInt()]
        m /= 36
        if (m >= ALPHANUMERIC.length) return null
        c[0] = ALPHANUMERIC[m.toInt()]
        val call = String(c).trim()
        if (' ' in call) return null
        remember(call)
        return Callsign(call, n.toInt(), standard = true)
    }

    private fun hashed(table: Map<Int, String>, hash: Int) = Callsign(table[hash] ?: "...", fromHash = true)

    /** The payload's bits, MSB first. */
    private class Bits(raw: Bytes) {
        private val bytes = ByteArray(raw.size) { raw[it] }

        fun flag(at: Int) = (bytes[at / 8].toInt() shr (7 - at % 8)) and 1 == 1

        fun long(from: Int, width: Int): Long {
            var v = 0L
            for (i in from until from + width) v = (v shl 1) or if (flag(i)) 1L else 0L
            return v
        }

        fun int(from: Int, width: Int) = long(from, width).toInt()

        fun big(from: Int, width: Int): BigInteger {
            var v = BigInteger.ZERO
            for (i in from until from + width) v = v.shiftLeft(1).let { if (flag(i)) it.setBit(0) else it }
            return v
        }
    }
}
