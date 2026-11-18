package com.mantz_it.rfanalyzer.util

/**
 * <h1>RF Analyzer - Callsigns</h1>
 *
 * Module:      Callsigns.kt
 * Description: Callsign matching for the K2K "X DE Y" / "CQ CQ DE X" patterns and the bookmarks'
 *              QRZ.com lookup: the regex for the core of a callsign and a helper that strips its
 *              portable/suffix decoration.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Matches the "core" of a callsign, e.g. "DL1ABC" inside "DL1ABC/P": an ITU prefix of letters,
 *  letter + digit (S5, E7) or digit + letter(s) (9A, 2E, 3DA), a digit and a suffix of 1-4 letters -
 *  or a suffix-less utility call of three letters and one or two digits (DWD's DDK2, DDH47). Used by
 *  the K2K "X DE Y" / "CQ CQ DE X" patterns and the bookmarks' QRZ.com lookup. Match against
 *  [String.uppercase] input. */
val CALLSIGN_CORE_REGEX = Regex("""\b(?:(?:[A-Z]{1,3}|[A-Z]\d|\d[A-Z]{1,2})\d[A-Z]{1,4}|[A-Z]{3}\d{1,2})\b""")

/** Strips portable/suffix decoration (e.g. "/P", "/MM") from a callsign-like string by returning
 *  just its regex-matched core, or null if nothing matches. */
fun extractCoreCallsign(raw: String): String? = CALLSIGN_CORE_REGEX.find(raw.uppercase())?.value
