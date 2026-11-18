package com.mantz_it.rfanalyzer.billing

import com.mantz_it.rfanalyzer.billing.Product.BASE_VERSION
import com.mantz_it.rfanalyzer.billing.Product.FULL_VERSION
import com.mantz_it.rfanalyzer.billing.Product.FULL_VERSION_UPGRADE
import com.mantz_it.rfanalyzer.billing.PurchaseRecord.State.OTHER
import com.mantz_it.rfanalyzer.billing.PurchaseRecord.State.PENDING
import com.mantz_it.rfanalyzer.billing.PurchaseRecord.State.PURCHASED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Licensing Tests</h1>
 *
 * Module:      TestLicensing.kt
 * Description: Tests for the license rules: the product IDs, the license of every set of owned
 *              products, what may be bought on top of each (with the digital features shown or
 *              hidden, with a pending payment), which purchases grant a license and which get
 *              acknowledged, how a snapshot takes in later purchases, the list of completed
 *              purchases and the license read from the cache.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestLicensing {

    /** Every set of owned products. */
    private val allOwnedSets: List<Set<Product>> = (0 until (1 shl Product.entries.size)).map { bits ->
        Product.entries.filterIndexed { i, _ -> bits and (1 shl i) != 0 }.toSet()
    }

    @Test
    fun productIdsAreTheOnesInPlayConsole() {
        // Purchases are stored by these IDs: changing one loses every purchase of it.
        assertEquals("rfanalyzer_full_version", FULL_VERSION.productId)
        assertEquals("rfanalyzer_base_version", BASE_VERSION.productId)
        assertEquals("rfanalyzer_upgrade_to_full_version", FULL_VERSION_UPGRADE.productId)
        assertEquals(BASE_VERSION, Product.ofId("rfanalyzer_base_version"))
        assertNull(Product.ofId("full_version"))
    }

    @Test
    fun licenseOfEveryOwnedSet() {
        for (owned in allOwnedSets) {
            val expected = when {
                FULL_VERSION in owned -> License.FULL
                BASE_VERSION in owned && FULL_VERSION_UPGRADE in owned -> License.FULL
                BASE_VERSION in owned -> License.BASE
                else -> License.TRIAL
            }
            assertEquals("owned=$owned", expected, licenseOf(owned))
        }
        // spelled out: the cases that matter
        assertEquals(License.FULL, licenseOf(setOf(FULL_VERSION)))
        assertEquals(License.FULL, licenseOf(setOf(BASE_VERSION, FULL_VERSION_UPGRADE)))
        assertEquals(License.BASE, licenseOf(setOf(BASE_VERSION)))
        assertEquals(License.TRIAL, licenseOf(setOf(FULL_VERSION_UPGRADE)))  // Base refunded
        assertEquals(License.TRIAL, licenseOf(emptySet()))
    }

    @Test
    fun whatMayBeBoughtOnTopOfEveryOwnedSet() {
        assertEquals(setOf(BASE_VERSION, FULL_VERSION), purchasableProducts(PurchaseSnapshot(), extensionsShown = true))
        assertEquals(setOf(FULL_VERSION_UPGRADE), purchasableProducts(PurchaseSnapshot(setOf(BASE_VERSION)), extensionsShown = true))
        assertEquals(setOf(BASE_VERSION), purchasableProducts(PurchaseSnapshot(setOf(FULL_VERSION_UPGRADE)), extensionsShown = true))
        for (owned in allOwnedSets) {
            val purchasable = purchasableProducts(PurchaseSnapshot(owned), extensionsShown = true)
            // never something owned, never anything on top of the full version
            assertTrue("owned=$owned", purchasable.none { it in owned })
            if (licenseOf(owned) == License.FULL) assertTrue("owned=$owned", purchasable.isEmpty())
            // the upgrade only for Base Version owners, the Full Version only for those without Base
            if (FULL_VERSION_UPGRADE in purchasable) assertTrue("owned=$owned", BASE_VERSION in owned)
            if (FULL_VERSION in purchasable) assertTrue("owned=$owned", BASE_VERSION !in owned && FULL_VERSION_UPGRADE !in owned)
            // every purchase raises the license
            for (product in purchasable) assertTrue("owned=$owned + $product", licenseOf(owned + product) > licenseOf(owned))
        }
    }

    @Test
    fun withDigitalFeaturesHiddenANewBuyerGetsTheFullVersionAlone() {
        // RF Analyzer 2.3 without the extension (DigitalFeatures): as up to 2.2.1
        assertEquals(setOf(FULL_VERSION), purchasableProducts(PurchaseSnapshot(), extensionsShown = false))
        // owners of Base or the upgrade (testers of the extension who removed it) keep their way to Full
        assertEquals(setOf(FULL_VERSION_UPGRADE), purchasableProducts(PurchaseSnapshot(setOf(BASE_VERSION)), extensionsShown = false))
        assertEquals(setOf(BASE_VERSION), purchasableProducts(PurchaseSnapshot(setOf(FULL_VERSION_UPGRADE)), extensionsShown = false))
        assertEquals(emptySet<Product>(), purchasableProducts(PurchaseSnapshot(setOf(FULL_VERSION)), extensionsShown = false))
        // a pending payment can still be retried, whatever it was for
        assertEquals(setOf(BASE_VERSION), purchasableProducts(PurchaseSnapshot(pending = setOf(BASE_VERSION)), extensionsShown = false))
        assertEquals(setOf(FULL_VERSION), purchasableProducts(PurchaseSnapshot(pending = setOf(FULL_VERSION)), extensionsShown = false))
    }

    @Test
    fun aPendingPaymentAllowsOnlyThatProductAgain() {
        // Play may still report a cancelled payment as pending: that product can be tried again
        // (Play decides), nothing else - it could be paid twice
        for (owned in allOwnedSets) for (pending in Product.entries) {
            if (pending in owned) continue
            val purchasable = purchasableProducts(PurchaseSnapshot(owned, setOf(pending)), extensionsShown = true)
            val expected = if (pending in purchasableProducts(PurchaseSnapshot(owned), extensionsShown = true)) setOf(pending) else emptySet()
            assertEquals("owned=$owned pending=$pending", expected, purchasable)
        }
        assertEquals(setOf(FULL_VERSION), purchasableProducts(PurchaseSnapshot(pending = setOf(FULL_VERSION)), extensionsShown = true))
        assertEquals(setOf(FULL_VERSION_UPGRADE), purchasableProducts(PurchaseSnapshot(setOf(BASE_VERSION), setOf(FULL_VERSION_UPGRADE)), extensionsShown = true))
        assertEquals(emptySet<Product>(), purchasableProducts(PurchaseSnapshot(setOf(FULL_VERSION), setOf(BASE_VERSION)), extensionsShown = true))
    }

    private fun record(vararg ids: String, state: PurchaseRecord.State = PURCHASED, acknowledged: Boolean = true, token: String = ids.joinToString()) =
        PurchaseRecord(ids.toList(), state, acknowledged, token)

    @Test
    fun onlyCompletedPurchasesOfOurProductsGrant() {
        val snapshot = snapshotOf(listOf(
            record("rfanalyzer_base_version"),
            record("rfanalyzer_upgrade_to_full_version", state = PENDING),
            record("rfanalyzer_full_version", state = OTHER),
            record("some_other_product"),
        ))
        assertEquals(setOf(BASE_VERSION), snapshot.owned)
        assertEquals(setOf(FULL_VERSION_UPGRADE), snapshot.pending)
        assertEquals(License.BASE, snapshot.license)
    }

    @Test
    fun anUnknownProductGrantsNothing() {
        // Only our three product IDs count (2.2.1 counted any purchase as the full version)
        assertEquals(License.TRIAL, snapshotOf(listOf(record("full_version"))).license)
    }

    @Test
    fun everyCompletedUnacknowledgedPurchaseIsAcknowledged() {
        val records = listOf(
            record("rfanalyzer_base_version", acknowledged = false),
            record("rfanalyzer_upgrade_to_full_version", acknowledged = false),
            record("rfanalyzer_full_version", acknowledged = true),
            record("rfanalyzer_full_version", state = PENDING, acknowledged = false, token = "pending"),
            record("some_other_product", acknowledged = false),
        )
        assertEquals(listOf("rfanalyzer_base_version", "rfanalyzer_upgrade_to_full_version"), purchasesToAcknowledge(records).map { it.purchaseToken })
    }

    @Test
    fun theListenerOnlyAdds() {
        val last = PurchaseSnapshot(setOf(BASE_VERSION), setOf(FULL_VERSION_UPGRADE))
        // the pending upgrade completed
        val merged = last.mergedWith(listOf(record("rfanalyzer_upgrade_to_full_version")))
        assertEquals(setOf(BASE_VERSION, FULL_VERSION_UPGRADE), merged.owned)
        assertEquals(emptySet<Product>(), merged.pending)
        assertEquals(License.FULL, merged.license)
        // a report without the Base Version doesn't revoke it
        assertEquals(setOf(BASE_VERSION), PurchaseSnapshot(setOf(BASE_VERSION)).mergedWith(emptyList()).owned)
    }

    @Test
    fun aQueryThatStartedBeforeAPurchaseCompletedKeepsIt() {
        val query = snapshotOf(listOf(record("rfanalyzer_full_version", state = PENDING)))
        val withGrant = query.withGranted(setOf(FULL_VERSION))
        assertEquals(setOf(FULL_VERSION), withGrant.owned)
        assertEquals(emptySet<Product>(), withGrant.pending)
    }

    @Test
    fun purchaseDetailsListCompletedPurchasesOnly() {
        val records = listOf(
            PurchaseRecord(listOf("rfanalyzer_upgrade_to_full_version"), PURCHASED, true, "t2", orderId = "GPA.2", purchaseTimeMs = 2000),
            PurchaseRecord(listOf("rfanalyzer_base_version"), PURCHASED, true, "t1", orderId = null, purchaseTimeMs = 1000),
            PurchaseRecord(listOf("rfanalyzer_full_version"), PENDING, false, "t3", orderId = null, purchaseTimeMs = 3000),
            PurchaseRecord(listOf("some_other_product"), PURCHASED, true, "t4", orderId = "GPA.4", purchaseTimeMs = 500),
        )
        assertEquals(
            listOf(OwnedPurchase(BASE_VERSION, null, 1000), OwnedPurchase(FULL_VERSION_UPGRADE, "GPA.2", 2000)),
            ownedPurchasesOf(records),
        )
    }

    @Test
    fun theCachedLicense() {
        assertEquals(License.FULL, licenseOfCache(isFullVersion = true, isBaseVersion = false))   // 2.2.1 buyers
        assertEquals(License.FULL, licenseOfCache(isFullVersion = true, isBaseVersion = true))    // Base + Upgrade
        assertEquals(License.BASE, licenseOfCache(isFullVersion = false, isBaseVersion = true))
        assertEquals(License.TRIAL, licenseOfCache(isFullVersion = false, isBaseVersion = false))
    }
}
