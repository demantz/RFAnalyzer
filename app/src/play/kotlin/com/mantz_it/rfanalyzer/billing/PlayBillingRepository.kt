package com.mantz_it.rfanalyzer.billing

import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import com.mantz_it.rfanalyzer.database.AppStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * <h1>RF Analyzer - Google Play Billing Repository</h1>
 *
 * Module:      PlayBillingRepository.kt
 * Description: BillingRepository on the Google Play Billing Library, following Google's integration
 *              guide: one client with automatic reconnection, purchases queried on connection and
 *              whenever the app comes to the foreground, only PURCHASED grants, every completed
 *              purchase acknowledged (Play refunds unacknowledged ones after 3 days).
 *
 * Everything runs on the main thread - the library calls some listeners on its own threads, so they
 * hop over first - and queries run one at a time ([mutex]), so a query's answer and the purchase
 * listener never interleave.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025-2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class PlayBillingRepository(
    context: Context,
    private val appStateRepository: AppStateRepository,
) : BillingRepository, PurchasesUpdatedListener {

    companion object {
        private const val TAG = "BillingRepository"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutex = Mutex()
    private val _state = MutableStateFlow(BillingState())
    override val state: StateFlow<BillingState> = _state.asStateFlow()
    private val _events = MutableSharedFlow<BillingEvent>(extraBufferCapacity = 4)
    override val events: SharedFlow<BillingEvent> = _events.asSharedFlow()

    // Products the purchase listener reported completed, with the time (elapsedRealtime): a query
    // that started before may not know them yet and must not revoke them.
    private val listenerGrants = mutableMapOf<Product, Long>()
    private val acknowledging = mutableSetOf<String>()
    private var connectedOnce = false

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        connect()
    }

    private fun connect() {
        Log.d(TAG, "connect: starting the billing client connection")
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                scope.launch {
                    if (result.responseCode == BillingResponseCode.OK) {
                        Log.d(TAG, "connect: connected")
                        connectedOnce = true
                        refresh()
                    } else {
                        Log.w(TAG, "connect: failed: ${result.responseCode} (${result.debugMessage})")
                        _state.update { it.copy(playStatus = PlayStatus.UNAVAILABLE) }
                    }
                }
            }

            // With automatic reconnection the next call reconnects; Google: don't reconnect here.
            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "connect: disconnected")
            }
        })
    }

    override fun refresh(withPrices: Boolean) {
        scope.launch { query(withPrices) }
    }

    override suspend fun query(withPrices: Boolean): Boolean = mutex.withLock {
        val answered = queryPurchases() != null
        if (withPrices) queryPrices()
        answered
    }

    /** Play's purchases, published to [state] and the license cache; null without an answer. */
    private suspend fun queryPurchases(): PurchaseSnapshot? {
        when (client.connectionState) {
            BillingClient.ConnectionState.CONNECTING -> return null  // onBillingSetupFinished refreshes
            BillingClient.ConnectionState.DISCONNECTED -> if (!connectedOnce) {
                // the first connection failed (e.g. no Play Store, or it was updating) - try again;
                // after one success, automatic reconnection takes care of it
                connect()
                return null
            }
        }
        val startedAt = SystemClock.elapsedRealtime()
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
        )
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "queryPurchases: failed: ${result.billingResult.responseCode} (${result.billingResult.debugMessage})")
            _state.update { it.copy(playStatus = PlayStatus.UNAVAILABLE) }
            return null
        }
        val records = result.purchasesList.map(::recordOf)
        result.purchasesList.forEach { Log.d(TAG, "queryPurchases: ${describe(it)}") }
        val snapshot = snapshotOf(records).withGranted(listenerGrants.filterValues { it > startedAt }.keys)
        _state.update { it.copy(ownedPurchases = ownedPurchasesOf(records)) }
        publish(snapshot)
        acknowledge(records)
        return snapshot
    }

    private suspend fun publish(snapshot: PurchaseSnapshot) {
        Log.i(TAG, "publish: owned=${snapshot.owned} pending=${snapshot.pending} -> ${snapshot.license}")
        _state.update { it.copy(purchases = snapshot, playStatus = PlayStatus.READY) }
        // A cache value set before its stored value is loaded would be overwritten by it.
        appStateRepository.isFullVersion.awaitInitialized()
        appStateRepository.isBaseVersion.awaitInitialized()
        // In this order the license only steps directly from the old to the new one (no TRIAL in
        // between, e.g. Full Version → Base Version).
        val isFull = snapshot.license == License.FULL
        val isBase = Product.BASE_VERSION in snapshot.owned
        if (isFull) {
            appStateRepository.isFullVersion.set(true)
            appStateRepository.isBaseVersion.set(isBase)
        } else {
            appStateRepository.isBaseVersion.set(isBase)
            appStateRepository.isFullVersion.set(false)
        }
    }

    private fun acknowledge(records: List<PurchaseRecord>) {
        for (record in purchasesToAcknowledge(records)) {
            if (!acknowledging.add(record.purchaseToken)) continue
            scope.launch {
                try {
                    val result = client.acknowledgePurchase(
                        AcknowledgePurchaseParams.newBuilder().setPurchaseToken(record.purchaseToken).build()
                    )
                    if (result.responseCode == BillingResponseCode.OK)
                        Log.i(TAG, "acknowledge: ${record.products} acknowledged")
                    else  // tried again with the next query (connection, onResume)
                        Log.e(TAG, "acknowledge: ${record.products} failed: ${result.responseCode} (${result.debugMessage})")
                } finally {
                    acknowledging.remove(record.purchaseToken)
                }
            }
        }
    }

    private suspend fun queryProductDetails(products: Collection<Product>): List<ProductDetails>? {
        val result = client.queryProductDetails(
            QueryProductDetailsParams.newBuilder().setProductList(products.map {
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(it.productId)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            }).build()
        )
        if (result.billingResult.responseCode != BillingResponseCode.OK) {
            Log.w(TAG, "queryProductDetails: failed: ${result.billingResult.responseCode} (${result.billingResult.debugMessage})")
            return null
        }
        return result.productDetailsList.orEmpty()
    }

    private suspend fun queryPrices() {
        val details = queryProductDetails(Product.entries) ?: return
        // The backward-compatible buy option (the product's first) - the one purchase() charges.
        val prices = details.mapNotNull { productDetails ->
            val product = Product.ofId(productDetails.productId) ?: return@mapNotNull null
            productDetails.oneTimePurchaseOfferDetails?.formattedPrice?.let { product to it }
        }.toMap()
        Log.d(TAG, "queryPrices: $prices")
        _state.update { it.copy(prices = prices) }
    }

    override suspend fun purchase(activity: Activity, product: Product): PurchaseStart = mutex.withLock {
        _state.update { it.copy(purchaseStarting = product) }
        try {
            // Play's answer now, not the cache or an earlier answer: bought on another device,
            // refunded, a pending payment completed - everything since counts.
            val snapshot = queryPurchases() ?: return PurchaseStart.PLAY_UNAVAILABLE
            if (product !in purchasableProducts(snapshot, DigitalFeatures.shown)) {
                Log.w(TAG, "purchase: $product is not purchasable (owned=${snapshot.owned} pending=${snapshot.pending})")
                return PurchaseStart.NOT_PURCHASABLE
            }
            val productDetails = queryProductDetails(listOf(product))?.firstOrNull { it.productId == product.productId }
                ?: return PurchaseStart.PRODUCT_UNAVAILABLE
            val offer = productDetails.oneTimePurchaseOfferDetails ?: return PurchaseStart.PRODUCT_UNAVAILABLE
            if (activity.isFinishing || activity.isDestroyed) return PurchaseStart.CANCELLED
            val params = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(productDetails)
                        // the offer whose price was shown (without one, Play takes the same default)
                        .apply { offer.offerToken?.let(::setOfferToken) }
                        .build()
                ))
                .build()
            val result = client.launchBillingFlow(activity, params)
            Log.i(TAG, "purchase: $product (${offer.formattedPrice}): ${result.responseCode} (${result.debugMessage})")
            when (result.responseCode) {
                BillingResponseCode.OK -> PurchaseStart.LAUNCHED
                BillingResponseCode.USER_CANCELED -> PurchaseStart.CANCELLED
                BillingResponseCode.ITEM_ALREADY_OWNED -> {
                    refresh()  // after this lock: Play's next answer should include it
                    PurchaseStart.NOT_PURCHASABLE
                }
                else -> PurchaseStart.FAILED
            }
        } finally {
            _state.update { it.copy(purchaseStarting = null) }
        }
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        scope.launch { handlePurchasesUpdated(result, purchases.orEmpty()) }
    }

    private fun handlePurchasesUpdated(result: BillingResult, purchases: List<Purchase>) {
        Log.i(TAG, "onPurchasesUpdated: ${result.responseCode} (${result.debugMessage}) ${purchases.map(::describe)}")
        when (result.responseCode) {
            BillingResponseCode.OK -> {
                val records = purchases.map(::recordOf)
                val update = snapshotOf(records)
                val now = SystemClock.elapsedRealtime()
                update.owned.forEach { listenerGrants[it] = now }
                update.pending.forEach { _events.tryEmit(BillingEvent.PurchasePending(it)) }
                scope.launch {
                    mutex.withLock {
                        // At once on top of Play's last answer (it only adds), then Play's full
                        // answer - which doesn't revoke what was granted here after it started.
                        _state.value.purchases?.let { publish(it.mergedWith(records)) }
                        acknowledge(records)
                        queryPurchases()
                    }
                }
            }
            BillingResponseCode.USER_CANCELED -> {}
            BillingResponseCode.ITEM_ALREADY_OWNED -> {
                _events.tryEmit(BillingEvent.AlreadyOwned(purchases.firstOrNull()?.products?.firstNotNullOfOrNull(Product::ofId)))
                refresh()
            }
            else -> _events.tryEmit(BillingEvent.PurchaseFailed(result.responseCode, result.debugMessage))
        }
    }

    private fun recordOf(purchase: Purchase) = PurchaseRecord(
        productIds = purchase.products,
        state = when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> PurchaseRecord.State.PURCHASED
            Purchase.PurchaseState.PENDING -> PurchaseRecord.State.PENDING
            else -> PurchaseRecord.State.OTHER
        },
        isAcknowledged = purchase.isAcknowledged,
        purchaseToken = purchase.purchaseToken,
        orderId = purchase.orderId,
        purchaseTimeMs = purchase.purchaseTime,
    )

    // Without the purchase token and order ID: the log file can be shared, and both identify the
    // purchase (the order ID is shown in About → Purchase details instead).
    private fun describe(purchase: Purchase) =
        "${purchase.products} state=${purchase.purchaseState} acknowledged=${purchase.isAcknowledged} " +
            "time=${purchase.purchaseTime}"
}
