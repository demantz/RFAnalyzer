package com.mantz_it.rfanalyzer.digiext.callsign

import com.mantz_it.rfanalyzer.util.CALLSIGN_CORE_REGEX

/**
 * <h1>RF Analyzer - K2K Callsign Parser</h1>
 *
 * Module:      K2kCallsignParser.kt
 * Description: Extracts the to/from callsigns from the text of a finished RTTY/PSK31 (K2K)
 *              transmission, using the two most common keyboard-to-keyboard conventions:
 *              "CALL1 DE CALL2" and a general call "CQ CQ CALL".
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

private val CALL = CALLSIGN_CORE_REGEX.pattern

// "CALL1 DE CALL2" - "DE" means "from": CALL1 is the called station, CALL2 the one transmitting.
private val DE_PATTERN = Regex("""\b($CALL)\b\s+DE\s+\b($CALL)\b""")

// "CQ CQ (CQ) (DE) CALL (CALL)" - a general call, not addressed to anyone in particular.
private val CQ_PATTERN = Regex("""\bCQ\s+CQ\s+(?:CQ\s+)?(?:DE\s+)?($CALL)\b(?:\s+\1\b)?""")

/**
 * Best-effort extraction of to/from callsigns from a finished K2K (RTTY/PSK31) message's
 * accumulated text, using the two most common ham keyboard-to-keyboard conventions. Returns
 * (fromCallsign, toCallsign), either or both null if no pattern matched.
 */
fun parseK2kCallsigns(text: String): Pair<String?, String?> {
    val upper = text.uppercase()
    DE_PATTERN.find(upper)?.let { match ->
        val (to, from) = match.destructured
        return from to to
    }
    CQ_PATTERN.find(upper)?.let { match ->
        return match.groupValues[1] to null
    }
    return null to null
}
