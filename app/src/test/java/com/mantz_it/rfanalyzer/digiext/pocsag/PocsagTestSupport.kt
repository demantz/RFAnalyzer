package com.mantz_it.rfanalyzer.digiext.pocsag

import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPhy

/**
 * <h1>RF Analyzer - POCSAG Test Support</h1>
 *
 * Module:      PocsagTestSupport.kt
 * Description: Test helpers shared by the POCSAG tests: [pocsagPage] builds a [PocsagPage] from
 *              already-decoded readings, and `secondaryText` is a page's other reading - the one
 *              not shown as its primary text.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** A page as the pipeline builds it from already-decoded readings - for tests of what is done with
 *  a page, not of how it is parsed. */
internal fun pocsagPage(
    function: Int,
    alpha: String? = null,
    numeric: String? = null,
    encoding: PocsagAlphaEncoding = PocsagAlphaEncoding.UNKNOWN,
    skyper: String? = null,
    tag: String? = null,
    address: Long = 1L,
    timestampMs: Long = 0L,
    rowId: Long = 0L,
    frequencyHz: Long = 0L,
): PocsagPage = PocsagSpec.packet(
    timestampMs = timestampMs, frequencyHz = frequencyHz, raw = null,
    phy = PocsagPhy(address = address, function = function, baudRate = 1200, bitCount = 0),
    data = PocsagMessage(alphaMessage = alpha, numericMessage = numeric, alphaEncoding = encoding, skyperMessage = skyper, skyperTag = tag),
    rowId = rowId,
)

internal val PocsagPage.secondaryText: String? get() = data.secondaryText(phy.function)
