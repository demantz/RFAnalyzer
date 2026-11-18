package com.mantz_it.rfanalyzer.billing

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.mantz_it.rfanalyzer.database.AppStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * <h1>RF Analyzer - License Cache Tests</h1>
 *
 * Module:      TestLicenseCache.kt
 * Description: The license cache in a preferences file as RF Analyzer 2.2.1 left it: whoever bought
 *              the full version must have it from the first frame after the update, before Google
 *              Play answers.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestLicenseCache {

    @get:Rule val folder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private fun appStateRepositoryWith(vararg values: Pair<String, Boolean>): AppStateRepository = runBlocking {
        val dataStore = PreferenceDataStoreFactory.create(scope = scope) { folder.newFile("settings.preferences_pb").also { it.delete() } }
        dataStore.edit { prefs -> values.forEach { (key, value) -> prefs[booleanPreferencesKey(key)] = value } }
        AppStateRepository(dataStore).also {
            withTimeout(10_000) {
                it.isFullVersion.awaitInitialized()
                it.isBaseVersion.awaitInitialized()
            }
        }
    }

    @Test
    fun aFullVersionBoughtBefore22StaysTheFullVersion() {
        // 2.2.1 wrote these two keys (isPurchasePending is no longer read)
        val repository = appStateRepositoryWith("isFullVersion" to true, "isPurchasePending" to false)
        assertEquals(License.FULL, repository.license.value)
        assertTrue(repository.isLicensed.value)
        assertTrue(repository.digitalFeaturesUnlocked.value)
    }

    @Test
    fun aTrialStaysATrial() {
        val repository = appStateRepositoryWith("isFullVersion" to false)
        assertEquals(License.TRIAL, repository.license.value)
        assertFalse(repository.isLicensed.value)
    }

    @Test
    fun theBaseVersionLiftsTheTrialButNotTheDigitalOne() {
        val repository = appStateRepositoryWith("isBaseVersion" to true)
        assertEquals(License.BASE, repository.license.value)
        assertTrue(repository.isLicensed.value)
        assertTrue(repository.digitalTrialRunning.value)
    }
}
