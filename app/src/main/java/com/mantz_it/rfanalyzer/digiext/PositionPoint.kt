package com.mantz_it.rfanalyzer.digiext

import kotlinx.serialization.Serializable

/**
 * <h1>RF Analyzer - Position Point</h1>
 *
 * Module:      PositionPoint.kt
 * Description: One fix in a tracked object's position trail (the Map subtab draws these as trails).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Serializable
data class PositionPoint(
    val lat: Double,
    val lon: Double,
    val timestampMs: Long,
)

// Trails are live-only: they exist for objects heard in this process and are never persisted (see
// ObjectFamily.trail). The cap bounds memory per object.
private const val MAX_TRAIL_POINTS = 200

/** Newest first. */
internal fun ArrayDeque<PositionPoint>.addPosition(lat: Double, lon: Double, ts: Long) {
    addFirst(PositionPoint(lat, lon, ts))
    while (size > MAX_TRAIL_POINTS) removeLast()
}
