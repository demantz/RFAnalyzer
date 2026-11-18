package com.mantz_it.rfanalyzer.database

/**
 * <h1>RF Analyzer - MapCameraPosition</h1>
 *
 * Module:      MapCameraPosition.kt
 * Description: Center coordinates + zoom level of a MaplibreView camera. See
 *              AppStateRepository.MapCameraPositionSettings for the DataStore-backed
 *              persistence of this state.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

data class MapCameraPosition(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
) {
    companion object {
        // Matches MapLibre-Compose's CameraPosition() default (world view)
        val DEFAULT = MapCameraPosition(latitude = 0.0, longitude = 0.0, zoom = 1.0)
    }
}
