package com.mantz_it.rfanalyzer.database

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import kotlinx.coroutines.flow.MutableStateFlow
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/**
 * <h1>RF Analyzer - Billing Repository</h1>
 *
 * Module:      Billing Repository.kt
 * Description: Interface to the Google Play Billing API
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */


interface BillingRepositoryInterface {
    fun queryPurchases()
    fun purchaseFullVersion(activity: Activity)
    fun isTrialPeriodExpired(): Boolean
    val remainingTrialPeriodDays: StateFlow<Int>
}

class BillingRepository(val context: Context, val appStateRepository: AppStateRepository) : PurchasesUpdatedListener, BillingRepositoryInterface {

    companion object {
        private const val TAG = "BillingRepository"
    }

    private val _remainingTrialPeriodDays = MutableStateFlow(calculateRemainingDays())
    override val remainingTrialPeriodDays: StateFlow<Int> = _remainingTrialPeriodDays.asStateFlow()

    private val billingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    init {
        startBillingConnection()

        CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                _remainingTrialPeriodDays.value = calculateRemainingDays()
                delay(TimeUnit.HOURS.toMillis(1)) // update every hour
            }
        }
    }

    private fun startBillingConnection() {
        Log.d(TAG, "startBillingConnection: start connection with billing client ...")
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "startBillingConnection: billing client connection successfull. query purchases...")
                    queryPurchases()
                } else {
                    Log.w(TAG, "startBillingConnection: billing client connection failed. Response Code: ${result.responseCode} (${result.debugMessage})")
                }
            }

            override fun onBillingServiceDisconnected() {
                // TODO: Maybe implement retry logic in the future..
                Log.w(TAG, "startBillingConnection: billing client connection disconnected!")
            }
        })
    }

    override fun queryPurchases() {
        Log.d(TAG, "queryPurchases: querying INAPP purchases...")
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                Log.d(TAG, "queryPurchases: query successful.")
                purchases.forEach { Log.d(TAG, "queryPurchases: purchase: ${it.packageName} (state=${it.purchaseState}). time=${it.purchaseTime} token=${it.purchaseToken} orderId=${it.orderId} acknowledged=${it.isAcknowledged}") }
                val purchasedPurchase = purchases.find { it.purchaseState == Purchase.PurchaseState.PURCHASED }
                val isPending = purchases.any { it.purchaseState == Purchase.PurchaseState.PENDING }

                if (purchasedPurchase != null) {
                    Log.d(TAG, "queryPurchases: set isFullVersion = true!")
                    appStateRepository.isFullVersion.set(true)
                    appStateRepository.isPurchasePending.set(false)

                    if (!purchasedPurchase.isAcknowledged) {
                        Log.i(TAG, "queryPurchases: Purchase is not acknowledged yet. Acknowledging the purchase..")
                        billingClient.acknowledgePurchase(
                            AcknowledgePurchaseParams.newBuilder()
                                .setPurchaseToken(purchasedPurchase.purchaseToken)
                                .build()
                        ) { result ->
                            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                                Log.d(TAG, "queryPurchases: Purchase acknowledged successfully")
                            } else {
                                Log.e(TAG, "queryPurchases: Failed to acknowledge purchase: ${result.debugMessage}")
                            }
                        }
                    }
                } else if (isPending) {
                    Log.d(TAG, "queryPurchases: purchase is pending. set isPurchasePending = true!")
                    appStateRepository.isPurchasePending.set(true)
                } else {
                    Log.d(TAG, "queryPurchases: no purchases found. set isFullVersion and isPurchasePending to false!")
                    appStateRepository.isFullVersion.set(false)
                    appStateRepository.isPurchasePending.set(false)
                }
            } else {
                Log.w(TAG, "queryPurchases: query failed. Response Code: ${billingResult.responseCode} (${billingResult.debugMessage})")
            }
        }
    }

    override fun purchaseFullVersion(activity: Activity) {
        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId("rfanalyzer_full_version")    // SKU from Google Play Console
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        )

        val queryParams = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        Log.d(TAG, "purchaseFullApp: Starting purchase flow for product: ${productList[0].zza()}, ${productList[0].zzb()}")

        billingClient.queryProductDetailsAsync(queryParams) { billingResult, productDetailsResult ->
            val productDetailsList = productDetailsResult.productDetailsList
            Log.d(TAG, "purchaseFullApp: querySkuDetailsAsync responseCode: ${billingResult.responseCode}, productDetailsList.size: ${productDetailsList.size}")

            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                if (productDetailsList.isEmpty()) {
                    Log.w(TAG, "purchaseFullApp: Product list is empty or null. Cannot start purchase flow.")
                    return@queryProductDetailsAsync
                }

                val productDetails = productDetailsList[0]
                Log.i(TAG, "purchaseFullApp: Found product details: ${productDetails}")
                val billingParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(
                        listOf(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                .setProductDetails(productDetails)
                                .build()
                        )
                    )
                    .build()

                val flowResult = billingClient.launchBillingFlow(activity, billingParams)
                Log.d(TAG, "purchaseFullApp: launchBillingFlow responseCode: ${flowResult.responseCode}")

            } else {
                Log.e(TAG, "purchaseFullApp: Failed to query product details. Response code: ${billingResult.responseCode}")
            }
        }
    }



    override fun onPurchasesUpdated(
        billingResult: BillingResult,
        purchases: MutableList<Purchase>?
    ) {
        Log.d(TAG, "onPurchasesUpdated: called with responseCode: ${billingResult.responseCode}")

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (purchases.isNullOrEmpty()) {
                    Log.w(TAG, "onPurchasesUpdated: Purchase successful but no purchases list returned.")
                    return
                }

                for (purchase in purchases) {
                    Log.d(TAG, "onPurchasesUpdated: Purchase found: $purchase")

                    when (purchase.purchaseState) {
                        Purchase.PurchaseState.PURCHASED -> {
                            Log.i(TAG, "onPurchasesUpdated: Purchase is in PURCHASED state.")
                            appStateRepository.isFullVersion.set(true)
                            appStateRepository.isPurchasePending.set(false)

                            if (!purchase.isAcknowledged) {
                                Log.i(TAG, "onPurchasesUpdated: Purchase is not acknowledged yet. Acknowledging the purchase..")
                                billingClient.acknowledgePurchase(
                                    AcknowledgePurchaseParams.newBuilder()
                                        .setPurchaseToken(purchase.purchaseToken)
                                        .build()
                                ) { result ->
                                    if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                                        Log.d(TAG, "Purchase acknowledged successfully")
                                    } else {
                                        Log.e(TAG, "Failed to acknowledge purchase: ${result.debugMessage}")
                                    }
                                }
                            }
                        }

                        Purchase.PurchaseState.PENDING -> {
                            if(!appStateRepository.isFullVersion.value) {
                                Log.i(TAG, "onPurchasesUpdated: Purchase is pending.")
                                appStateRepository.isPurchasePending.set(true)
                            } else {
                                Log.i(TAG, "onPurchasesUpdated: A purchase is pending, but app is already full version.")
                            }
                        }

                        Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                            Log.i(TAG, "onPurchasesUpdated: Purchase is in unspecified state.")
                        }

                        else -> {
                            Log.w(TAG, "onPurchasesUpdated: Purchase is in unknown state: ${purchase.purchaseState}")
                        }
                    }
                }
            }

            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.i(TAG, "onPurchasesUpdated: User canceled the purchase flow.")
            }

            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                Log.i(TAG, "onPurchasesUpdated: Item already owned.")
            }

            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> {
                Log.w(TAG, "onPurchasesUpdated: Item unavailable.")
            }

            BillingClient.BillingResponseCode.DEVELOPER_ERROR -> {
                Log.w(TAG, "onPurchasesUpdated: Developer Error (incorrect API usage).")
            }

            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
                Log.w(TAG, "onPurchasesUpdated: Billing Unavailable.")
            }

            else -> {
                Log.e(TAG, "onPurchasesUpdated: Unhandled billing response code: ${billingResult.responseCode}")
            }
        }
    }

    private fun getInstallTimestamp(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.firstInstallTime // Returns install time in milliseconds
        } catch (e: PackageManager.NameNotFoundException) {
            0L
        }
    }

    private fun calculateRemainingDays(): Int {
        val installTimestamp = getInstallTimestamp(context)
        val installedDays = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - installTimestamp).toInt()
        val trialPeriod= 7 // 7-day trial period
        return if (installedDays > 60) // workaround for people who had the old app version installed already (prior to 2.0 launch)
            trialPeriod                // these people just get 7 days and the usageTime is the limiting factor
        else
            (trialPeriod - installedDays).coerceAtLeast(0)
    }

    override fun isTrialPeriodExpired(): Boolean {
        return remainingTrialPeriodDays.value <= 0
    }
}
