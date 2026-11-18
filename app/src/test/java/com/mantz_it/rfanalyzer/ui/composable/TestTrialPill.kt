package com.mantz_it.rfanalyzer.ui.composable

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - Trial Pill Tests</h1>
 *
 * Module:      TestTrialPill.kt
 * Description: Tests for [trialPillText]: the time left of the trial and of the digital modes trial
 *              in short form, each left out when it does not apply.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestTrialPill {

    @Test
    fun bothTrialsInShortForm() {
        assertEquals("Trial 42m · Digital 1h 52m", trialPillText(42 * 60 + 59, 112 * 60))
        assertEquals("Trial <1m · Digital 2h 0m", trialPillText(59, 7200))
    }

    @Test
    fun aUsedUpTrialSaysSo() {
        assertEquals("Trial over · Digital 5m", trialPillText(0, 300))
        assertEquals("Trial over · Digital over", trialPillText(0, 0))
    }

    @Test
    fun withoutDigitalModesOnlyTheTrial() {
        assertEquals("Trial 1h 0m", trialPillText(3600, null))
    }

    @Test
    fun withTheBaseVersionOnlyTheDigitalTrial() {
        assertEquals("Digital 1h 52m", trialPillText(null, 112 * 60))
    }
}
