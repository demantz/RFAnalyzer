package com.mantz_it.rfanalyzer.digiext.ft8

import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer.digiext.callsign.dxccCountryOf
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine

/**
 * <h1>RF Analyzer - FT8 Summary</h1>
 *
 * Module:      Ft8Summary.kt
 * Description: The figures next to the FT8 table's count: where the listed stations are and the
 *              best signal - of this session while it runs, so it says something about now.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Continent code to station count, largest first. A callsign the DXCC table does not resolve
 *  is left out rather than counted as unknown: the table's coverage is partial on purpose. */
fun continentsOf(stations: List<TrackedObject<Ft8Station>>): List<Pair<String, Int>> =
    stations.mapNotNull { dxccCountryOf(it.key)?.continent }
        .groupingBy { it }
        .eachCount()
        .entries.sortedByDescending { it.value }
        .map { it.key to it.value }

/** "EU 64 · NA 16 · AS 5 · best +4 dB this session". */
fun ft8Summary(stations: List<TrackedObject<Ft8Station>>, session: SessionWindow?): String {
    val best = stations.filter { session == null || it.lastSeenMs in session }.maxOfOrNull { it.state.bestSnr }
    return metaLine(
        continentsOf(stations).joinToString(" · ") { (continent, count) -> "$continent $count" },
        best?.let { "best ${formatFt8Snr(it)} dB" + if (session != null) " this session" else "" },
    )
}
