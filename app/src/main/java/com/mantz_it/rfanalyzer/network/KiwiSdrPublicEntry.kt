package com.mantz_it.rfanalyzer.network

import kotlinx.serialization.Serializable

/**
 * <h1>RF Analyzer - KiwiSDR Public Entry</h1>
 *
 * Module:      KiwiSdrPublicEntry.kt
 * Description: A single receiver entry from the public KiwiSDR directory (kiwisdr.com/public/).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Serializable
data class KiwiSdrPublicEntry(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val lat: Double? = null,
    val lon: Double? = null,
    val grid: String? = null,
    val loc: String? = null,
    val usersCurrent: Int = 0,
    val usersMax: Int = 0,
    val offline: Boolean = false,
    val swVersion: String? = null,
    val antenna: String? = null,
    val snrAll: Double? = null,
    val snrHf: Double? = null,
    val bands: String? = null,
)
