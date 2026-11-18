package com.mantz_it.rfanalyzer.ui.composable

import com.mantz_it.rfanalyzer.billing.BillingState
import com.mantz_it.rfanalyzer.billing.License
import com.mantz_it.rfanalyzer.billing.PlayStatus
import com.mantz_it.rfanalyzer.billing.Product.BASE_VERSION
import com.mantz_it.rfanalyzer.billing.Product.FULL_VERSION
import com.mantz_it.rfanalyzer.billing.Product.FULL_VERSION_UPGRADE
import com.mantz_it.rfanalyzer.billing.PurchaseSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * <h1>RF Analyzer - License Sheet Tests</h1>
 *
 * Module:      TestLicenseSheet.kt
 * Description: Tests for the license sheet's logic: which content it shows for what Google Play
 *              reports, which products it offers, with the digital features shown or hidden, the
 *              owned products in words, and the trial line.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestLicenseSheet {

    private fun ready(snapshot: PurchaseSnapshot) = BillingState(purchases = snapshot, playStatus = PlayStatus.READY)

    @Test
    fun withoutAnAnswerFromPlayNothingIsOffered() {
        assertEquals(LicenseSheetContent.AskingPlay, licenseSheetContent(BillingState()))
        assertEquals(LicenseSheetContent.PlayUnavailable, licenseSheetContent(BillingState(playStatus = PlayStatus.UNAVAILABLE)))
        assertEquals(emptySet<Any>(), BillingState().purchasable(extensionsShown = true))
        // an earlier answer, but Play doesn't answer now: shown, nothing purchasable
        val stale = BillingState(purchases = PurchaseSnapshot(), playStatus = PlayStatus.UNAVAILABLE)
        assertEquals(LicenseSheetContent.Choose, licenseSheetContent(stale))
        assertEquals(emptySet<Any>(), stale.purchasable(extensionsShown = true))
    }

    @Test
    fun theContentFollowsWhatPlayReports() {
        assertEquals(LicenseSheetContent.Choose, licenseSheetContent(ready(PurchaseSnapshot())))
        assertEquals(LicenseSheetContent.Upgrade, licenseSheetContent(ready(PurchaseSnapshot(setOf(BASE_VERSION)))))
        assertEquals(LicenseSheetContent.CompleteWithBaseVersion, licenseSheetContent(ready(PurchaseSnapshot(setOf(FULL_VERSION_UPGRADE)))))
        assertEquals(LicenseSheetContent.FullVersionOwned(setOf(FULL_VERSION)), licenseSheetContent(ready(PurchaseSnapshot(setOf(FULL_VERSION)))))
        assertEquals(
            LicenseSheetContent.FullVersionOwned(setOf(BASE_VERSION, FULL_VERSION_UPGRADE)),
            licenseSheetContent(ready(PurchaseSnapshot(setOf(BASE_VERSION, FULL_VERSION_UPGRADE)))),
        )
        assertEquals(LicenseSheetContent.Pending(setOf(BASE_VERSION)), licenseSheetContent(ready(PurchaseSnapshot(pending = setOf(BASE_VERSION)))))
    }

    @Test
    fun theButtonsOfEachContent() {
        assertEquals(setOf(BASE_VERSION, FULL_VERSION), ready(PurchaseSnapshot()).purchasable(extensionsShown = true))
        assertEquals(setOf(FULL_VERSION_UPGRADE), ready(PurchaseSnapshot(setOf(BASE_VERSION))).purchasable(extensionsShown = true))
        assertEquals(setOf(BASE_VERSION), ready(PurchaseSnapshot(setOf(FULL_VERSION_UPGRADE))).purchasable(extensionsShown = true))
        assertEquals(emptySet<Any>(), ready(PurchaseSnapshot(setOf(FULL_VERSION))).purchasable(extensionsShown = true))
        assertEquals(setOf(FULL_VERSION), ready(PurchaseSnapshot(pending = setOf(FULL_VERSION))).purchasable(extensionsShown = true))  // "buy again"
    }

    @Test
    fun withDigitalFeaturesHiddenTheFullVersionAlone() {
        assertEquals(LicenseSheetContent.FullVersionAlone, licenseSheetContent(ready(PurchaseSnapshot()), extensionsShown = false))
        assertEquals(setOf(FULL_VERSION), ready(PurchaseSnapshot()).purchasable(extensionsShown = false))
        // what someone owns is answered as always
        assertEquals(LicenseSheetContent.Upgrade, licenseSheetContent(ready(PurchaseSnapshot(setOf(BASE_VERSION))), extensionsShown = false))
        assertEquals(LicenseSheetContent.FullVersionOwned(setOf(FULL_VERSION)), licenseSheetContent(ready(PurchaseSnapshot(setOf(FULL_VERSION))), extensionsShown = false))
    }

    @Test
    fun ownedProductsInWords() {
        assertEquals("Full Version", ownedProductsText(setOf(FULL_VERSION)))
        assertEquals("Base Version + Full Version Upgrade", ownedProductsText(setOf(FULL_VERSION_UPGRADE, BASE_VERSION)))
    }

    @Test
    fun theTrialLine() {
        assertEquals("Trial: 42 min left · Digital modes trial: 1 h 52 min left", trialLine(License.TRIAL, 42 * 60, 112 * 60))
        assertEquals("Trial: used up · Digital modes trial: used up", trialLine(License.TRIAL, 0, 0))
        assertEquals("Trial: 42 min left", trialLine(License.TRIAL, 42 * 60, null))
        assertEquals("Digital modes trial: 1 h 52 min left", trialLine(License.BASE, 0, 112 * 60))
        assertNull(trialLine(License.FULL, 0, 0))
    }
}
