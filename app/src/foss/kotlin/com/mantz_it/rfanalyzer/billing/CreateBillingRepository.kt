package com.mantz_it.rfanalyzer.billing

import android.app.Activity
import android.content.Context
import com.mantz_it.rfanalyzer.database.AppStateRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * <h1>RF Analyzer - Billing Repository (FOSS)</h1>
 *
 * Module:      CreateBillingRepository.kt
 * Description: The open-source build sells nothing: no billing, no license, no trial
 *              (BuildConfig.IS_FOSS).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
fun createBillingRepository(context: Context, appStateRepository: AppStateRepository): BillingRepository = NoBillingRepository

private object NoBillingRepository : BillingRepository {
    override val state: StateFlow<BillingState> = MutableStateFlow(BillingState(playStatus = PlayStatus.UNAVAILABLE))
    override val events: SharedFlow<BillingEvent> = MutableSharedFlow()
    override fun refresh(withPrices: Boolean) {}
    override suspend fun query(withPrices: Boolean) = false
    override suspend fun purchase(activity: Activity, product: Product) = PurchaseStart.PLAY_UNAVAILABLE
}
