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

    override fun queryPurchases() {
        // do nothing in mock
    }

    override fun purchaseFullVersion(activity: Activity) {
        //Log.d("MockedBillingRepository", "purchaseFullVersion: DISABLED")
        appStateRepository.isFullVersion.set(true)
    }
}
