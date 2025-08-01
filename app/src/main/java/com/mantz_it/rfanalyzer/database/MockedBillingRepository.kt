package com.mantz_it.rfanalyzer.database

import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * <h1>RF Analyzer - Mockup for Billing Repository</h1>
 *
 * Module:      MockedBillingRepository.kt
 * Description: A mockup for the BillingRepository. Only for development.
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


class MockedBillingRepository(val context: Context, val appStateRepository: AppStateRepository) : BillingRepositoryInterface {

    private val _remainingTrialPeriodDays = MutableStateFlow(calculateRemainingDays())
    override val remainingTrialPeriodDays: StateFlow<Int> = _remainingTrialPeriodDays.asStateFlow()

    init {
        CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                _remainingTrialPeriodDays.value = calculateRemainingDays()
                delay(TimeUnit.HOURS.toMillis(1)) // update every hour
            }
        }
    }

    override fun queryPurchases() {
        // do nothing in mock
    }

    override fun purchaseFullVersion(activity: Activity) {
        //Log.d("MockedBillingRepository", "purchaseFullVersion: DISABLED")
        appStateRepository.isFullVersion.set(true)
    }

    private fun calculateRemainingDays(): Int {
        val installTimestamp = getInstallTimestamp(context) // todo: this should be 'purchase time'
        val currentTime = System.currentTimeMillis()
        val installedDays = TimeUnit.MILLISECONDS.toDays(currentTime - installTimestamp).toInt()
        val trialPeriod= 7 // 7-day trial period
        Log.d("MockedBillingRepository", "Install: $installTimestamp ; Now: $currentTime  ;  Diff: ${currentTime-installTimestamp}  ; InstalledDays: $installedDays")
        return (trialPeriod - installedDays).coerceAtLeast(0)
    }

    override fun isTrialPeriodExpired(): Boolean {
        return remainingTrialPeriodDays.value <= 0
    }

    private fun getInstallTimestamp(context: Context): Long {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.firstInstallTime // Returns install time in milliseconds
        } catch (e: PackageManager.NameNotFoundException) {
            0L
        }
    }
}
