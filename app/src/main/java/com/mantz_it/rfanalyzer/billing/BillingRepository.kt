package com.mantz_it.rfanalyzer.billing

import android.app.Activity
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * <h1>RF Analyzer - Billing Repository</h1>
 *
 * Module:      BillingRepository.kt
 * Description: The app's view of Google Play Billing: what Play reports as purchased and pending,
 *              the prices, and starting a purchase. Implemented per flavor and build type
 *              (createBillingRepository): Google Play (play release/profile), a mock (play debug),
 *              nothing (foss).
 *
 * The license itself lives in AppStateRepository (isFullVersion, isBaseVersion): a cache of
 * Play's last answer, so the app starts with the right license offline and before Play answers.
 * Only an answer from Play changes it; an error never does.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
interface BillingRepository {
    val state: StateFlow<BillingState>
    /** What happened to a purchase after Play's sheet closed, for a message (the license itself
     *  follows from [state]). */
    val events: SharedFlow<BillingEvent>

    /** Asks Play for the purchases again (connection, onResume, the license sheet), with
     *  [withPrices] also for the prices. Without an answer nothing changes. */
    fun refresh(withPrices: Boolean = false)

    /** [refresh], waiting for Play: whether it answered. */
    suspend fun query(withPrices: Boolean = false): Boolean

    /** Opens Play's purchase sheet for [product] - after asking Play for the purchases once more and
     *  only if [purchasableProducts] allows it then, so nothing can be bought twice or out of order. */
    suspend fun purchase(activity: Activity, product: Product): PurchaseStart
}

data class BillingState(
    /** Google Play's last answer in this process; null until it answered once (until then the
     *  cached license applies and nothing can be bought). */
    val purchases: PurchaseSnapshot? = null,
    val playStatus: PlayStatus = PlayStatus.CONNECTING,
    /** Play's localized price per product ("€9.99"), as far as it reported them. */
    val prices: Map<Product, String> = emptyMap(),
    /** A purchase being prepared: the queries before Play's sheet opens. */
    val purchaseStarting: Product? = null,
    /** The completed purchases of Play's last answer, for the purchase details. */
    val ownedPurchases: List<OwnedPurchase> = emptyList(),
) {
    /** What may be bought now - only from an answer of Play, never from the cache. [extensionsShown]: DigitalFeatures.shown. */
    fun purchasable(extensionsShown: Boolean): Set<Product> =
        if (playStatus == PlayStatus.READY && purchases != null) purchasableProducts(purchases, extensionsShown) else emptySet()
}

enum class PlayStatus {
    CONNECTING,
    /** The last query was answered. */
    READY,
    /** The last query failed: no connection, no Play Store, Play Store blocked or not signed in. */
    UNAVAILABLE,
}

/** Why [BillingRepository.purchase] did or didn't open Play's sheet. */
enum class PurchaseStart {
    /** Play's sheet is open; the result arrives as [BillingEvent] and in [BillingRepository.state]. */
    LAUNCHED,
    /** Play's answer just now rules it out (owned, included in an owned product, a payment pending). */
    NOT_PURCHASABLE,
    PLAY_UNAVAILABLE,
    /** Play didn't return the product (not active in Play Console, not offered in this country). */
    PRODUCT_UNAVAILABLE,
    /** Play refused to open its sheet. */
    FAILED,
    /** The user or the activity went away before Play's sheet opened. */
    CANCELLED,
}

/** Google Play's BillingClient.BillingResponseCode values the app tells apart - mirrored here because
 *  the main source set has no Billing Library (the FOSS build doesn't include it). */
object BillingResponse {
    const val SERVICE_TIMEOUT = -3
    const val SERVICE_DISCONNECTED = -1
    const val SERVICE_UNAVAILABLE = 2
    const val BILLING_UNAVAILABLE = 3
    const val ITEM_UNAVAILABLE = 4
    const val NETWORK_ERROR = 12
}

sealed interface BillingEvent {
    /** Paid with a method that completes later (cash, bank transfer): nothing is unlocked until then. */
    data class PurchasePending(val product: Product) : BillingEvent
    /** Play reported [product] as owned although its last answer said otherwise; asked again. */
    data class AlreadyOwned(val product: Product?) : BillingEvent
    data class PurchaseFailed(val responseCode: Int, val debugMessage: String) : BillingEvent
}
