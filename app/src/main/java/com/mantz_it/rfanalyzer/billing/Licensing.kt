package com.mantz_it.rfanalyzer.billing

/**
 * <h1>RF Analyzer - Licensing</h1>
 *
 * Module:      Licensing.kt
 * Description: The products sold on Google Play, which license the owned ones give and which
 *              products may be bought on top of them - pure functions of what Google Play reports,
 *              so every combination is unit-tested (TestLicensing).
 *
 * Google Play enforces none of these rules: it sells any product to whoever reaches its purchase
 * sheet and only refuses buying the same product twice. A Full Version owner could buy the Base
 * Version, a Base Version owner the Full Version, anyone the upgrade - unless [purchasableProducts]
 * says so, which BillingRepository.purchase() checks against a fresh query right before Play's
 * sheet opens.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The one-time products in Google Play Console. Never change a product ID: purchases are stored by it. */
enum class Product(val productId: String) {
    /** Sold since 2.0, owned by every buyer up to 2.2.1, and still sold: everything in one purchase. */
    FULL_VERSION("rfanalyzer_full_version"),
    /** Everything except the extensions. */
    BASE_VERSION("rfanalyzer_base_version"),
    /** Base Version owners only: together with it the same as [FULL_VERSION]. */
    FULL_VERSION_UPGRADE("rfanalyzer_upgrade_to_full_version");

    companion object {
        fun ofId(productId: String): Product? = entries.firstOrNull { it.productId == productId }
    }
}

enum class License {
    /** Nothing bought: the trial limits apply. */
    TRIAL,
    /** No trial limits; the extensions (digital modes) keep their own trial. */
    BASE,
    /** Everything, including the extensions. */
    FULL,
}

/** The license of the products Google Play reports as purchased (pending ones grant nothing). */
fun licenseOf(owned: Set<Product>): License = when {
    Product.FULL_VERSION in owned -> License.FULL
    Product.BASE_VERSION in owned && Product.FULL_VERSION_UPGRADE in owned -> License.FULL
    Product.BASE_VERSION in owned -> License.BASE
    // also the upgrade alone (the Base Version refunded): buying the Base Version again completes it
    else -> License.TRIAL
}

/** What Google Play reported in one query: the products purchased and those whose payment is pending. */
data class PurchaseSnapshot(
    val owned: Set<Product> = emptySet(),
    val pending: Set<Product> = emptySet(),
) {
    val license: License get() = licenseOf(owned)

    /** [products] completed (by the purchase listener) after the query that made this snapshot started. */
    fun withGranted(products: Set<Product>) = PurchaseSnapshot(owned + products, pending - products)

    /** The purchase listener's report on top of the last query: it only ever adds (revoking is a
     *  query's job), a purchase completed leaves the pending ones. */
    fun mergedWith(records: List<PurchaseRecord>): PurchaseSnapshot {
        val update = snapshotOf(records)
        val owned = owned + update.owned
        return PurchaseSnapshot(owned, pending + update.pending - owned)
    }
}

/**
 * What may be bought on top of [snapshot] - never a product that would add nothing or that the
 * owned ones already include. While a payment is pending only that product again: Play's purchase
 * query can report a payment as pending long after Play cancelled it (25+ minutes in a test), so the
 * user can try again and Play decides - another product could be paid twice.
 *
 * [extensionsShown] (DigitalFeatures.shown): temporary, for RF Analyzer 2.3, whose digital features
 * are hidden unless the extension is installed - then a new buyer gets the Full Version alone, as
 * up to 2.2.1, because Base and upgrade differ only in what isn't shown. Goes away with
 * BuildConfig.DIGITAL_LAUNCHED (2.4).
 */
fun purchasableProducts(snapshot: PurchaseSnapshot, extensionsShown: Boolean): Set<Product> {
    val withoutPending = when (snapshot.license) {
        License.FULL -> emptySet()
        License.BASE -> setOf(Product.FULL_VERSION_UPGRADE)
        License.TRIAL ->
            if (Product.FULL_VERSION_UPGRADE in snapshot.owned) setOf(Product.BASE_VERSION)
            else setOf(Product.BASE_VERSION, Product.FULL_VERSION)
    }
    if (snapshot.pending.isNotEmpty()) return withoutPending intersect snapshot.pending
    return if (!extensionsShown && withoutPending == setOf(Product.BASE_VERSION, Product.FULL_VERSION)) setOf(Product.FULL_VERSION) else withoutPending
}

/** One Google Play purchase, as much of it as the rules need (BillingRepository maps Play's Purchase). */
data class PurchaseRecord(
    val productIds: List<String>,
    val state: State,
    val isAcknowledged: Boolean,
    val purchaseToken: String,
    /** "GPA.1234-…", also in Google Play's receipt email; null for a purchase redeemed with a promo code. */
    val orderId: String? = null,
    val purchaseTimeMs: Long = 0,
) {
    enum class State { PURCHASED, PENDING, OTHER }

    /** Our products in it; any other product ID grants nothing. */
    val products: Set<Product> get() = productIds.mapNotNull(Product::ofId).toSet()
}

fun snapshotOf(records: List<PurchaseRecord>): PurchaseSnapshot {
    val owned = records.filter { it.state == PurchaseRecord.State.PURCHASED }.flatMap { it.products }.toSet()
    val pending = records.filter { it.state == PurchaseRecord.State.PENDING }.flatMap { it.products }.toSet()
    return PurchaseSnapshot(owned, pending - owned)
}

/** A completed purchase as the About tab's purchase details show it - what a user quotes when
 *  asking about it. Never the purchase token (it identifies the purchase towards Google's servers). */
data class OwnedPurchase(val product: Product, val orderId: String?, val purchaseTimeMs: Long)

fun ownedPurchasesOf(records: List<PurchaseRecord>): List<OwnedPurchase> = records
    .filter { it.state == PurchaseRecord.State.PURCHASED }
    .flatMap { record -> record.products.map { OwnedPurchase(it, record.orderId, record.purchaseTimeMs) } }
    .sortedBy { it.purchaseTimeMs }

/** Purchases Google Play refunds and revokes unless they are acknowledged (within 3 days; test
 *  purchases: 3 minutes) - only completed ones, never pending ones. */
fun purchasesToAcknowledge(records: List<PurchaseRecord>): List<PurchaseRecord> = records.filter {
    it.state == PurchaseRecord.State.PURCHASED && !it.isAcknowledged && it.products.isNotEmpty()
}

/** The license the cache in AppStateRepository holds ([isFullVersion]: the key 2.2.1 wrote). */
fun licenseOfCache(isFullVersion: Boolean, isBaseVersion: Boolean): License = when {
    isFullVersion -> License.FULL
    isBaseVersion -> License.BASE
    else -> License.TRIAL
}
