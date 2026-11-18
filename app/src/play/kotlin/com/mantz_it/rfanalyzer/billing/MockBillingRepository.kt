package com.mantz_it.rfanalyzer.billing

import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import android.app.Activity
import com.mantz_it.rfanalyzer.database.AppStateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * <h1>RF Analyzer - Mock Billing Repository</h1>
 *
 * Module:      MockBillingRepository.kt
 * Description: Debug builds (not signed for Google Play): a store whose truth is the license
 *              cache itself, so the debug receiver can set isFullVersion / isBaseVersion and every
 *              purchase completes at once - under the same purchase rules (purchasableProducts).
 *              The profile build uses the real Google Play billing (a license tester account gets
 *              test purchases in sideloaded builds too).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class MockBillingRepository(private val appStateRepository: AppStateRepository) : BillingRepository {

    companion object {
        private const val PLAY_DELAY_MS = 400L
        private val PRICES = mapOf(
            Product.BASE_VERSION to "€4.99",
            Product.FULL_VERSION to "€8.99",
            Product.FULL_VERSION_UPGRADE to "€4.49",
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(BillingState())
    override val state: StateFlow<BillingState> = _state.asStateFlow()
    override val events: SharedFlow<BillingEvent> = MutableSharedFlow<BillingEvent>().asSharedFlow()

    init {
        refresh()
    }

    // Base + Full in the cache stands for Base + Upgrade.
    private suspend fun owned(): Set<Product> {
        appStateRepository.isFullVersion.awaitInitialized()
        appStateRepository.isBaseVersion.awaitInitialized()
        val isFull = appStateRepository.isFullVersion.value
        val isBase = appStateRepository.isBaseVersion.value
        return buildSet {
            if (isBase) add(Product.BASE_VERSION)
            if (isFull) add(if (isBase) Product.FULL_VERSION_UPGRADE else Product.FULL_VERSION)
        }
    }

    override fun refresh(withPrices: Boolean) {
        scope.launch { query(withPrices) }
    }

    override suspend fun query(withPrices: Boolean): Boolean {
        delay(PLAY_DELAY_MS)
        val snapshot = PurchaseSnapshot(owned())
        _state.update { it.copy(purchases = snapshot, playStatus = PlayStatus.READY, prices = if (withPrices) PRICES else it.prices,
            // no Google Play order behind a mock purchase - and it says so in the purchase details
            ownedPurchases = snapshot.owned.map { product -> OwnedPurchase(product, "debug build, no Google Play order", System.currentTimeMillis()) }) }
        return true
    }

    override suspend fun purchase(activity: Activity, product: Product): PurchaseStart {
        _state.update { it.copy(purchaseStarting = product) }
        delay(PLAY_DELAY_MS)
        val snapshot = PurchaseSnapshot(owned())
        _state.update { it.copy(purchaseStarting = null, purchases = snapshot, playStatus = PlayStatus.READY) }
        if (product !in purchasableProducts(snapshot, DigitalFeatures.shown)) return PurchaseStart.NOT_PURCHASABLE
        when (product) {
            Product.BASE_VERSION -> appStateRepository.isBaseVersion.set(true)
            Product.FULL_VERSION, Product.FULL_VERSION_UPGRADE -> appStateRepository.isFullVersion.set(true)
        }
        _state.update { it.copy(purchases = PurchaseSnapshot(owned())) }
        return PurchaseStart.LAUNCHED
    }
}
