package com.mantz_it.rfanalyzer.database

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

/**
 * <h1>RF Analyzer - Private Settings</h1>
 *
 * Module:      PrivateSettings.kt
 * Description: The settings that tell where the user is or open a receiver - the location (country
 *              or region, grid locator, device fix), the KiwiSDR password and where the KiwiSDR map
 *              was looked at. They live in their own file, which Android's cloud backup leaves out
 *              (res/xml/backup_rules.xml, data_extraction_rules.xml), so they don't leave the
 *              device. AppStateRepository keeps exactly the settings named in
 *              [PrivateSettings.KEYS] there.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object PrivateSettings {
    /** The DataStore's name: files/datastore/rf_analyzer_private.preferences_pb */
    const val FILE_NAME = "rf_analyzer_private"

    /** The keys as stored (an enum setting's name carries the suffix "Enum"; the plain name is its legacy int key, see AppStateRepository.Setting). */
    val KEYS = setOf(
        "locationArea", "locationPositionModeEnum", "locationPositionMode", "locationLocator", "locationDeviceFix",
        "kiwiSdrPassword",
        "kiwiSdrDialog.mapCameraLatitude", "kiwiSdrDialog.mapCameraLongitude", "kiwiSdrDialog.mapCameraZoom",
    )
}

/**
 * Moves the private settings out of the main preferences, where pre-release builds of 2.3 kept them
 * (and the cloud backup included them; no released version had them). DataStore runs it before the
 * private store is first read; the keys leave the main preferences only after the copy is stored
 * ([cleanUp]).
 */
class PrivateSettingsMigration(private val from: DataStore<Preferences>) : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences) =
        from.data.first().asMap().keys.any { it.name in PrivateSettings.KEYS }

    override suspend fun migrate(currentData: Preferences): Preferences {
        val prefs = currentData.toMutablePreferences()
        from.data.first().asMap().forEach { (key, value) ->
            @Suppress("UNCHECKED_CAST")
            if (key.name in PrivateSettings.KEYS && !prefs.contains(key)) prefs[key as Preferences.Key<Any>] = value
        }
        return prefs.toPreferences()
    }

    override suspend fun cleanUp() {
        from.edit { prefs -> prefs.asMap().keys.filter { it.name in PrivateSettings.KEYS }.forEach { prefs.remove(it) } }
    }
}
