package com.mantz_it.rfanalyzer.database

import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Derived States Tests</h1>
 *
 * Module:      TestDerivedStates.kt
 * Description: Tests for AppStateRepository's DerivedEnumState and DerivedState: a state derived
 *              from the setting an enum selects follows that setting when its stored value loads
 *              later and when the enum changes, and set() writes the selected setting, notifying
 *              listeners once per change.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestDerivedStates {

    private val mode = AppStateRepository.MutableState(DemodulationMode.NFM)
    private val perMode = mapOf(DemodulationMode.NFM to AppStateRepository.MutableState(false), DemodulationMode.AM to AppStateRepository.MutableState(true))
    private val selected = AppStateRepository.DerivedEnumState(mode, perMode)
    private val derived = AppStateRepository.DerivedState(selected) { selected.value }

    @Test
    fun aStateOnTopFollowsTheSelectedSettingLoadingLater() {
        assertFalse(derived.stateFlow.value)
        perMode.getValue(DemodulationMode.NFM).set(true) // the stored value arrives from the DataStore
        assertTrue(selected.stateFlow.value)
        assertTrue(derived.stateFlow.value)
    }

    @Test
    fun aStateOnTopFollowsTheEnumAndSet() {
        val heard = mutableListOf<Boolean>()
        selected.addOnChangedListener { heard += it }
        mode.set(DemodulationMode.AM)
        assertTrue(derived.stateFlow.value)
        selected.set(false)
        assertFalse(perMode.getValue(DemodulationMode.AM).value)
        assertFalse(derived.stateFlow.value)
        assertEquals(listOf(true, false), heard) // once per change
    }
}
