package com.mantz_it.rfanalyzer.database

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * <h1>RF Analyzer - Private Settings Tests</h1>
 *
 * Module:      TestPrivateSettings.kt
 * Description: The location and the KiwiSDR password move out of the backed-up preferences.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestPrivateSettings {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun privateSettingsMoveToTheirOwnStoreOnce() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val main = PreferenceDataStoreFactory.create(scope = scope) { File(tmp.root, "main.preferences_pb") }
        val locator = stringPreferencesKey("locationLocator")
        val password = stringPreferencesKey("kiwiSdrPassword")
        val positionMode = stringPreferencesKey("locationPositionModeEnum")
        val fps = intPreferencesKey("maxFrameRate")
        main.edit { it[locator] = "JN49hl"; it[password] = "secret"; it[positionMode] = "LOCATOR"; it[fps] = 45 }

        val private = PreferenceDataStoreFactory.create(scope = scope, migrations = listOf(PrivateSettingsMigration(main))) {
            File(tmp.root, "private.preferences_pb")
        }
        val moved = private.data.first()
        assertEquals("JN49hl", moved[locator])
        assertEquals("secret", moved[password])
        assertEquals("LOCATOR", moved[positionMode])
        assertNull(moved[fps])                       // not private: stays where it was

        val left = main.data.first()
        assertNull(left[locator])
        assertNull(left[password])
        assertEquals(45, left[fps])
        scope.cancel()
    }
}
