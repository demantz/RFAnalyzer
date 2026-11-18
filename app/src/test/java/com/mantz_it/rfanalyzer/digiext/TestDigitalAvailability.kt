package com.mantz_it.rfanalyzer.digiext

import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Digital Availability Tests</h1>
 *
 * Module:      TestDigitalAvailability.kt
 * Description: The one state every digital entry point reads - nothing may start that silently
 *              receives nothing.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestDigitalAvailability {

    @Test
    fun thePackageCheckDecidesUnlessConnectingFoundOutMore() {
        assertEquals(DigitalAvailability.UPDATE_APP, availabilityOf(unlocked = true, DigitalAvailability.UPDATE_APP, ExtensionConnectionState.CONNECTED))
        assertEquals(DigitalAvailability.READY, availabilityOf(unlocked = true, DigitalAvailability.READY, ExtensionConnectionState.DISCONNECTED))
        // an extension too old to name its protocol in its manifest: only connecting tells
        assertEquals(DigitalAvailability.UPDATE_EXTENSION, availabilityOf(unlocked = true, DigitalAvailability.READY, ExtensionConnectionState.VERSION_MISMATCH_UPDATE_EXTENSION))
        assertEquals(DigitalAvailability.UPDATE_APP, availabilityOf(unlocked = true, DigitalAvailability.READY, ExtensionConnectionState.VERSION_MISMATCH_UPDATE_APP))
        assertEquals(DigitalAvailability.FOREIGN_SIGNATURE, availabilityOf(unlocked = true, DigitalAvailability.READY, ExtensionConnectionState.NOT_PERMITTED))
        // uninstalled while running
        assertEquals(DigitalAvailability.NOT_INSTALLED, availabilityOf(unlocked = true, DigitalAvailability.READY, ExtensionConnectionState.NOT_INSTALLED))
    }

    @Test
    fun installingComesBeforeBuying() {
        assertEquals(DigitalAvailability.NOT_INSTALLED, availabilityOf(unlocked = false, DigitalAvailability.NOT_INSTALLED, ExtensionConnectionState.DISCONNECTED))
        assertEquals(DigitalAvailability.UPDATE_EXTENSION, availabilityOf(unlocked = false, DigitalAvailability.READY, ExtensionConnectionState.VERSION_MISMATCH_UPDATE_EXTENSION))
        assertEquals(DigitalAvailability.LOCKED, availabilityOf(unlocked = false, DigitalAvailability.READY, ExtensionConnectionState.CONNECTED))
        assertEquals(DigitalAction.UNLOCK_DIGITAL_MODES, DigitalStatus(DigitalAvailability.LOCKED).notice()!!.action)
    }

    @Test
    fun aUsedUpTrialStillDecodesForTheGracePeriod() {
        assertTrue(DigitalAvailability.LOCKED.decodes)
        assertTrue(DigitalAvailability.READY.decodes)
        assertFalse(DigitalAvailability.NOT_INSTALLED.decodes)
        assertFalse(DigitalAvailability.UPDATE_APP.decodes)
    }

    @Test
    fun trialTimeReadsInHoursAndMinutes() {
        assertEquals("2 h 0 min", formatTrialTimeLeft(7200))
        assertEquals("1 h 52 min", formatTrialTimeLeft(6720 + 59))
        assertEquals("5 min", formatTrialTimeLeft(300))
        assertEquals("under a minute", formatTrialTimeLeft(59))
    }

    @Test
    fun theLinkCountsOnlyWhileTheExtensionIsNeeded() {
        assertEquals(DigitalLink.IDLE, linkOf(needed = false, ExtensionConnectionState.UNAVAILABLE))
        assertEquals(DigitalLink.CONNECTING, linkOf(needed = true, ExtensionConnectionState.DISCONNECTED))
        assertEquals(DigitalLink.CONNECTED, linkOf(needed = true, ExtensionConnectionState.CONNECTED))
        assertEquals(DigitalLink.INTERRUPTED, linkOf(needed = true, ExtensionConnectionState.RECONNECTING))
        assertEquals(DigitalLink.FAILED, linkOf(needed = true, ExtensionConnectionState.UNAVAILABLE))
    }

    @Test
    fun everyStateThatReceivesNothingSaysSoWithAWayOut() {
        // NOT_IN_BUILD shows nothing at all (DigitalFeatures)
        assertNull(DigitalStatus(DigitalAvailability.NOT_IN_BUILD).notice())
        for (availability in DigitalAvailability.entries.filter { it != DigitalAvailability.READY && it != DigitalAvailability.NOT_IN_BUILD }) {
            val notice = DigitalStatus(availability).notice()
            assertNotNull(availability.name, notice)
            assertNotNull(availability.name, notice!!.action)
        }
        assertNull(DigitalStatus(DigitalAvailability.READY, DigitalLink.CONNECTED).notice())
        assertNull(DigitalStatus(DigitalAvailability.READY, DigitalLink.IDLE).notice())
        assertTrue(DigitalStatus(DigitalAvailability.READY, DigitalLink.INTERRUPTED).notice()!!.isError)
        assertEquals(DigitalAction.RETRY, DigitalStatus(DigitalAvailability.READY, DigitalLink.FAILED).notice()!!.action)
    }

    @Test
    fun eachUpdateOpensTheAppThatIsTooOld() {
        assertEquals("com.mantz_it.rfanalyzer_digital", DigitalStatus(DigitalAvailability.UPDATE_EXTENSION).notice()!!.action!!.playStorePackage())
        assertEquals("com.mantz_it.rfanalyzer", DigitalStatus(DigitalAvailability.UPDATE_APP).notice()!!.action!!.playStorePackage())
        assertEquals("com.mantz_it.rfanalyzer_digital", DigitalStatus(DigitalAvailability.NOT_INSTALLED).notice()!!.action!!.playStorePackage())
    }

    @Test
    fun digitalFeaturesAreShownInPlayBuildsOnceLaunchedOrWithTheExtension() {
        // F-Droid: never
        assertFalse(digitalFeaturesShown(inBuild = false, launched = true, extensionInstalled = true))
        // RF Analyzer 2.3: only where the extension is installed
        assertFalse(digitalFeaturesShown(inBuild = true, launched = false, extensionInstalled = false))
        assertTrue(digitalFeaturesShown(inBuild = true, launched = false, extensionInstalled = true))
        // from 2.4 on: always
        assertTrue(digitalFeaturesShown(inBuild = true, launched = true, extensionInstalled = false))
    }

    @Test
    fun whileHiddenDigitalStationsAreHeardInAnalogModes() {
        assertEquals(DigitalAvailability.NOT_IN_BUILD, availabilityOf(unlocked = false, DigitalAvailability.NOT_IN_BUILD, ExtensionConnectionState.DISCONNECTED))
        assertEquals(DemodulationMode.FT8, DemodulationMode.FT8.shownOrAnalog(digitalShown = true))
        assertEquals(DemodulationMode.USB, DemodulationMode.FT8.shownOrAnalog(digitalShown = false))
        assertEquals(DemodulationMode.NFM, DemodulationMode.POCSAG.shownOrAnalog(digitalShown = false))
        assertNull(DemodulationMode.LORA.shownOrAnalog(digitalShown = false)) // no analog equivalent: keep the current mode
        assertEquals(DemodulationMode.NFM, DemodulationMode.NFM.shownOrAnalog(digitalShown = false))
        assertFalse(DemodulationMode.RTTY.shown(digitalShown = false))
        assertTrue(DemodulationMode.AM.shown(digitalShown = false))
    }

    @Test
    fun wfmNeedsTheExtensionOnlyForStereoAndRds() {
        assertTrue(usesDigitalExtension(DemodulationMode.POCSAG, wfmStereoAndRds = false))
        assertTrue(usesDigitalExtension(DemodulationMode.WFM, wfmStereoAndRds = true))
        assertFalse(usesDigitalExtension(DemodulationMode.WFM, wfmStereoAndRds = false))
        assertFalse(usesDigitalExtension(DemodulationMode.NFM, wfmStereoAndRds = true))
    }
}
