package com.mantz_it.rfanalyzer.digiext.pocsag

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPhy

/**
 * <h1>RF Analyzer - POCSAG Parser</h1>
 *
 * Module:      PocsagParser.kt
 * Description: Turns the payload bits of a POCSAG page into a PocsagMessage. Runs in the app rather
 *              than in the digital extension because its heuristics keep changing: the stored raw
 *              bits are re-parsed whenever PARSER_VERSION is raised.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * The alphanumeric and numeric readings ([decodeAlpha], [decodeNumeric] and their bit helpers, the
 * BCD character table) follow multimon-ng's POCSAG decoder (pocsag.c, Copyright (C) 1996 Thomas
 * Sailer, multimon-ng contributors; GPL version 2 or later).
 */
object PocsagParser {

    /**
     * Raise whenever the output of [parse] changes for the same input - a new decode, a fixed bug,
     * retuned [SkyperDecoder] thresholds: stored rows of a lower version are re-parsed from their
     * raw bits.
     */
    const val PARSER_VERSION = 1

    /** Special-character table for the 4-bit BCD reading (multimon-ng print_msg_numeric). */
    private const val NUMERIC_TABLE = "084 2.6]195-3U7["

    /** Stands for a character with bits in a damaged codeword ([PocsagPhy.damagedCodewords]). */
    const val DAMAGED = '\uFFFD'

    private const val CODEWORD_BITS = 20

    fun parse(raw: Bytes, phy: PocsagPhy): PocsagMessage {
        val payload = raw.toByteArray()
        val alpha = decodeAlpha(payload, phy.bitCount, phy.damagedCodewords)
        val numeric = decodeNumeric(payload, phy.bitCount, phy.damagedCodewords)
        val skyper = SkyperDecoder.classify(alpha)
        return PocsagMessage(
            alphaMessage = alpha,
            numericMessage = numeric,
            alphaEncoding = skyper.encoding,
            skyperMessage = skyper.skyperMessage,
            skyperTag = skyper.skyperTag,
        )
    }

    /**
     * 7-bit ASCII, bit-reversed (multimon-ng print_msg_alpha). Both this and [decodeNumeric] are
     * always run: the same bitstream is valid under either reading, and the function bits are only
     * a convention, not a reliable discriminator - so the choice is left to the UI.
     */
    internal fun decodeAlpha(payload: ByteArray, bitCount: Int, damaged: List<Int> = emptyList()): String? {
        if (bitCount <= 0) return null
        val numChars = bitCount / 7
        val sb = StringBuilder(numChars)
        for (i in 0 until numChars) {
            sb.append(if (touchesDamage(damaged, 7 * i, 7)) DAMAGED else (rev7(get7(payload, i)).toInt() and 0x7F).toChar())
        }
        return sb.toString()
    }

    /** 4-bit BCD with special-character table (multimon-ng print_msg_numeric). */
    internal fun decodeNumeric(payload: ByteArray, bitCount: Int, damaged: List<Int> = emptyList()): String? {
        if (bitCount <= 0) return null
        val numDigits = minOf(bitCount / 4, payload.size * 2)
        val sb = StringBuilder(numDigits)
        for (i in 0 until numDigits) {
            val nibble = (payload[i / 2].toInt() ushr (if (i % 2 == 0) 4 else 0)) and 0xF
            sb.append(if (touchesDamage(damaged, 4 * i, 4)) DAMAGED else NUMERIC_TABLE[nibble])
        }
        return sb.toString()
    }

    /** Whether payload bits [start] until [start] + [length] overlap a damaged codeword. */
    private fun touchesDamage(damaged: List<Int>, start: Int, length: Int): Boolean =
        damaged.any { k -> start < (k + 1) * CODEWORD_BITS && k * CODEWORD_BITS < start + length }

    /** Extract the n-th 7-bit word from a packed byte array (big-endian nibbles). */
    private fun get7(buf: ByteArray, n: Int): Byte {
        val idx0 = (n * 7) / 8
        val idx1 = (n * 7 + 6) / 8
        val b0 = if (idx0 < buf.size) buf[idx0].toInt() and 0xFF else 0
        val b1 = if (idx1 < buf.size) buf[idx1].toInt() and 0xFF else 0
        return (((b0 shl 8) or b1) ushr ((n + 1) % 8)).toByte()
    }

    /** Reverse the 7 LSBs of a byte. */
    private fun rev7(b: Byte): Byte {
        val v = b.toInt()
        return (((v shl 6) and 0x40) or ((v ushr 6) and 0x01) or
                ((v shl 4) and 0x20) or ((v ushr 4) and 0x02) or
                ((v shl 2) and 0x10) or ((v ushr 2) and 0x04) or
                ((v)       and 0x08)).toByte()
    }
}

/**
 * Detects and decodes the "Skyper" ROT-1 byte-shift convention used by amateur-radio POCSAG
 * bulletin feeds, and distinguishes it from plain alpha text and from payloads whose alpha reading
 * simply isn't text at all.
 *
 * The shift only ever touches bytes 33-126 (space, 0x20, is below that range and passes
 * through unshifted), so on the wire every real word-boundary space shows up as '!' (0x21).
 * That means raw Skyper text has almost no literal spaces, while undoing the shift restores
 * normal word spacing - a signature that's directly checkable without any dictionary or
 * language model, and distinguishes it from both plain text (which has normal spaces
 * already) and non-text bytes (which stay space-free even after un-shifting).
 *
 * **This classifies the alpha reading only, and draws no conclusion about the payload.** A
 * [PocsagAlphaEncoding.BINARY] verdict means "these bytes aren't ASCII text", which for an fn0
 * numeric page is the expected result and says nothing about the page being obscured. Content
 * selection is [primaryReading]'s job, not this class's.
 *
 * The thresholds are heuristic. This runs app-side against the stored raw payload, so retuning
 * them improves captures already recorded; raise [PocsagParser.PARSER_VERSION] whenever the
 * classification changes.
 */
object SkyperDecoder {

    data class Result(
        val encoding: PocsagAlphaEncoding,
        val skyperMessage: String? = null,
        val skyperTag: String? = null,
    )

    private const val MIN_LEN = 8
    private const val BINARY_CONTROL_MAX = 0.10f
    private const val RAW_SPACE_MAX = 0.03f
    private const val DECODED_SPACE_MIN = 0.05f
    private const val WORD_CHAR_MIN = 0.70f

    // Optional single skipped lead char (covers e.g. "X!xOTA: ..." where 'X' is a bare
    // marker char preceding the real "!xOTA:" tag), then 1-2 punctuation chars, a short
    // alphanumeric word, and a colon+space delimiter.
    private val WORD_TAG_REGEX = Regex("^.?[\\p{Punct}]{1,2}([A-Za-z][A-Za-z0-9]{1,7}):\\s")

    fun classify(alphaMessage: String?): Result {
        if (alphaMessage == null) return Result(PocsagAlphaEncoding.UNKNOWN)
        // Trailing NULs, not spaces: the 7-bit unpacking always yields payloadBitCount/7
        // chars, so a short message's tail is whatever padding the transmitter sent -
        // usually 0x00. Trimming it stops the control-char ratio below reading padding
        // as binary content.
        val text = alphaMessage.trimEnd(Char(0))
        // the statistics leave out characters lost in damaged codewords: they say nothing
        // about the encoding
        val sample = text.filter { it != PocsagParser.DAMAGED }
        if (sample.length < MIN_LEN) return Result(PocsagAlphaEncoding.UNKNOWN)

        val controlRatio = sample.count { it.code !in 32..126 } / sample.length.toFloat()
        if (controlRatio > BINARY_CONTROL_MAX) return Result(PocsagAlphaEncoding.BINARY)

        val rawSpaceRatio = sample.count { it == ' ' } / sample.length.toFloat()
        val decodedSample = rot1Decode(sample)
        val decodedSpaceRatio = decodedSample.count { it == ' ' } / decodedSample.length.toFloat()
        val wordCharRatio = decodedSample.count { it.isLetterOrDigit() || it == ' ' } / decodedSample.length.toFloat()

        val isSkyper = rawSpaceRatio < RAW_SPACE_MAX &&
                decodedSpaceRatio >= DECODED_SPACE_MIN &&
                wordCharRatio >= WORD_CHAR_MIN
        if (!isSkyper) return Result(PocsagAlphaEncoding.PLAIN)

        val decoded = rot1Decode(text)   // the damaged marks stay where they are
        val (tag, body) = extractTag(decoded)
        return Result(PocsagAlphaEncoding.SKYPER, skyperMessage = body, skyperTag = tag)
    }

    private fun rot1Decode(text: String): String =
        text.map { c -> if (c.code in 33..126) (c.code - 1).toChar() else c }.joinToString("")

    // Best-effort only - see SkyperDecoder class doc. Two forms, and when either matches, the
    // matched prefix is stripped from skyperMessage (the tag is only ever surfaced separately):
    //  1. "word + colon" (e.g. "!PARA: ", "#UBA: ", "X!xOTA: ") -> captures just the word.
    //  2. bare 2-char marker fallback (e.g. "%%", "Q ", "y!") -> a doubled character, or a pair
    //     containing at least one alphanumeric. Two *different* punctuation characters ('"%',
    //     "[ ") are rejected: that is mostly ordinary content beginning with punctuation, whose
    //     first two characters would be eaten. A rejected tag leaves the message un-stripped.
    private fun extractTag(decoded: String): Pair<String?, String> {
        WORD_TAG_REGEX.find(decoded)?.let { m ->
            return m.groupValues[1] to decoded.substring(m.range.last + 1).trimStart()
        }
        if (decoded.length < 2) return null to decoded
        val lead = decoded.substring(0, 2)
        if (lead.all { it.isLetterOrDigit() }) return null to decoded
        if (lead[0] != lead[1] && lead.none { it.isLetterOrDigit() }) return null to decoded
        return lead to decoded.substring(2).trimStart()
    }
}
