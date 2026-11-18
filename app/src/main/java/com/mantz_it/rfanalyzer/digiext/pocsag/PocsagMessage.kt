package com.mantz_it.rfanalyzer.digiext.pocsag

import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPhy
import kotlinx.serialization.Serializable

/**
 * <h1>RF Analyzer - POCSAG Interpretation</h1>
 *
 * Module:      PocsagMessage.kt
 * Description: App-side interpretation of a POCSAG page's payload bits - the decoded text plus a
 *              best-effort classification of what kind of content it is.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * What the **7-bit alpha reading** of a payload looks like, guessed from its byte-level statistics
 * (see [SkyperDecoder]). Not a formally documented protocol - just a practical distinction between
 * human-readable text and the ROT-1-obfuscated "Skyper" convention used by amateur-radio bulletin
 * feeds.
 *
 * This says nothing about the payload as a whole - only about one of the two readings of it. In
 * particular [BINARY] is **not** a claim that the content is encrypted: an fn0 (numeric service)
 * page decodes to garbage under the alpha reading by construction, while its numeric reading is
 * perfectly good plain text. Use [primaryReading] to pick what to show; this enum only decides
 * whether the *alpha* string is worth rendering as text or as hex.
 */
@Serializable
enum class PocsagAlphaEncoding {
    /** Printable text, shown as-is. */
    PLAIN,

    /** ROT-1 shifted; [PocsagMessage.skyperMessage] holds the un-shifted text. */
    SKYPER,

    /** The alpha reading is mostly non-printable - it is bytes, not text. Render it as hex rather
     *  than as mojibake. Says nothing about *why*: usually it just means the page wasn't alpha. */
    BINARY,

    /** Too short to classify (under [SkyperDecoder]'s minimum length), or no payload at all. */
    UNKNOWN,
}

/**
 * Which reading of a page actually carries its content.
 *
 * POCSAG sends the same bits under two readings and never says which is meant, so something has to
 * choose. The function bits are the transmitter's own statement of intent and the only signal
 * available: fn0 is the numeric/BCD service, fn1 a bare tone page with no payload, fn2/fn3 the
 * alphanumeric services. That is a far better discriminator than the byte-level classifier, which
 * only ever describes the *alpha* reading and must never be allowed to veto a good numeric one.
 */
enum class PocsagReading {
    NUMERIC,
    ALPHA,
    SKYPER,

    /**
     * An address-only page: no payload at all, so the address *is* the message.
     *
     * Detected from the absence of payload bits, not from `function == 1`: most empty pages carry
     * fn0/fn2/fn3.
     */
    TONE,

    /** Payload bits present, but both readings decode to nothing but blanks. */
    NONE,
}

/**
 * The content of one POCSAG page: the two readings of its payload bits and the classification of
 * the alpha one. Parsed app-side from the page's raw bits ([PocsagParser]) - the address, function
 * and baud rate are the page's [PocsagPhy].
 *
 * Which reading carries the content depends on the page's function bits too, so the precedence
 * functions below take them: [primaryReading].
 */
@Serializable
data class PocsagMessage(
    /** 7-bit reversed-ASCII reading of the payload bits; null when there was no payload. */
    val alphaMessage: String?,
    /** 4-bit BCD reading of the same payload bits; null when there was no payload. Both readings
     *  are always produced - which one is meaningful is a judgement the UI makes, not the decoder. */
    val numericMessage: String?,
    val alphaEncoding: PocsagAlphaEncoding,
    /** Populated only when [alphaEncoding] is SKYPER: [alphaMessage] with the ROT-1 shift undone. */
    val skyperMessage: String? = null,
    /** Best-effort leading source tag extracted from [skyperMessage] (e.g. "PARA", "UBA", "y!"). */
    val skyperTag: String? = null,
)

/** A received POCSAG page. */
typealias PocsagPage = StoredPacket<PocsagPhy, PocsagMessage>

val PocsagPage.primaryReading: PocsagReading get() = data.primaryReading(phy.function)
val PocsagPage.primaryText: String? get() = data.primaryText(phy.function)

/** POCSAG pads a short message's tail with NULs up to `payloadBitCount / 7` characters. */
private fun String?.trimPadding(): String? = this?.trimEnd(Char(0))?.takeIf { it.isNotBlank() }

/**
 * Which reading of this page carries its content - see [PocsagReading].
 *
 * Order matters:
 *  1. **No payload at all -> TONE.** Both decoders return null exactly when there were no payload
 *     bits, so this is a fact about the page rather than a guess. It is checked before the function
 *     code because most empty pages carry fn0/fn2/fn3, not fn1.
 *  2. **Direct evidence about the bytes wins** over the function code, which is only a convention:
 *     a detected ROT-1 shift, or an alpha reading the classifier found to be printable text
 *     ([PocsagAlphaEncoding.PLAIN]).
 *  3. **Otherwise the function code chooses**, since it is the transmitter's own statement of which
 *     service it used, and each choice falls back to the other reading when its own is blank - so a
 *     mislabelled page still shows whatever content it has rather than nothing.
 *
 * Neither the function bits nor the alpha reading alone can be trusted, which is why rule 2 exists:
 * an fn0 page can be a numeric time-and-date broadcast whose alpha reading is not ASCII, or
 * alerting text whose alpha reading is plain. The classifier separates them: the first is BINARY,
 * the second PLAIN.
 *
 * How digit-like the numeric reading looks is no discriminator: the POCSAG numeric alphabet yields
 * digit-heavy strings from any bits.
 */
fun PocsagMessage.primaryReading(function: Int): PocsagReading {
    // Untrimmed null-ness, not blankness: both decoders return null iff payloadBitCount <= 0.
    if (alphaMessage == null && numericMessage == null) return PocsagReading.TONE

    val alpha = alphaMessage.trimPadding()
    val numeric = numericMessage.trimPadding()
    val skyper = skyperMessage.trimPadding()
    return when {
        alphaEncoding == PocsagAlphaEncoding.SKYPER && skyper != null -> PocsagReading.SKYPER
        // PLAIN means the alpha reading passed the printability test, so it demonstrably *is*
        // text - evidence about the bytes, which outranks the function code for the same reason
        // the SKYPER check above does.
        alphaEncoding == PocsagAlphaEncoding.PLAIN && alpha != null -> PocsagReading.ALPHA
        function == 0 -> if (numeric != null) PocsagReading.NUMERIC
                         else if (alpha != null) PocsagReading.ALPHA else PocsagReading.NONE
        alpha != null -> PocsagReading.ALPHA
        numeric != null -> PocsagReading.NUMERIC
        else -> PocsagReading.NONE
    }
}

/** The text of [primaryReading]; null for a tone page or an empty payload. */
fun PocsagMessage.primaryText(function: Int): String? = when (primaryReading(function)) {
    PocsagReading.SKYPER -> skyperMessage.trimPadding()
    PocsagReading.NUMERIC -> numericMessage.trimPadding()
    PocsagReading.ALPHA -> alphaMessage.trimPadding()
    PocsagReading.TONE, PocsagReading.NONE -> null
}

/**
 * The *other* reading, for the detail card - never hidden, because which reading was meant is a
 * guess and the user is better placed than we are to spot when the guess is wrong.
 */
fun PocsagMessage.secondaryText(function: Int): String? = when (primaryReading(function)) {
    // The raw shifted alpha adds nothing once the shift is undone; the numeric reading might.
    PocsagReading.SKYPER -> numericMessage.trimPadding()
    PocsagReading.NUMERIC -> alphaMessage.trimPadding()
    PocsagReading.ALPHA -> numericMessage.trimPadding()
    PocsagReading.TONE, PocsagReading.NONE -> null
}

/**
 * Replaces characters outside printable ASCII so a byte-ish string can share a dense single-line
 * row without control codes wrecking the layout ([PocsagParser.DAMAGED] stays). Display only -
 * never feed a hex dump from this.
 */
fun String.toPrintable(placeholder: Char = '·'): String =
    map { if (it.code in 32..126 || it == PocsagParser.DAMAGED) it else placeholder }.joinToString("")
