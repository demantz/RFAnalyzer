package com.mantz_it.rfanalyzer.source

/**
 * <h1>RF Analyzer - Clock Correction</h1>
 *
 * Module:      ClockCorrection.kt
 * Description: The correction from this device's wall clock to UTC, measured with
 *              [com.mantz_it.rfanalyzer.network.SntpClient]. Android lets the system clock drift by
 *              up to 2 s before it steps it, too much for FT8's 15 s periods. Every sample
 *              timestamp taken from the wall clock goes through [now]; sources that know when their
 *              samples were received (KiwiSDR's GPS stamps, a file's position) don't need it.
 *
 * 0 until a measurement was made, and kept in memory only: Android steps the clock without
 * notice, which would silently invalidate a stored correction.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object ClockCorrection {
    /** UTC minus [System.currentTimeMillis], in ms. */
    @Volatile
    var offsetMs: Long = 0L

    /** The current UTC time in ms (the uncorrected wall clock until a correction was measured). */
    @JvmStatic
    fun now(): Long = System.currentTimeMillis() + offsetMs
}
