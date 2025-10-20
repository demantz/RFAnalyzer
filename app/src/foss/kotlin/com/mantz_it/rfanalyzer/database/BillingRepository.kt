package com.mantz_it.rfanalyzer.database

import android.app.Activity
import android.content.Context

/**
 * <h1>RF Analyzer - Billing Repository (FOSS)</h1>
 *
 * Module:      BillingRepository.kt
 * Description: Mock Interface (without Google Play Billing API) for FOSS version
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
}

class BillingRepository(val context: Context, val appStateRepository: AppStateRepository) : BillingRepositoryInterface {
    override fun queryPurchases() {
        // do nothing in foss
    }
    override fun purchaseFullVersion(activity: Activity) {
        // do nothing in foss
    }
}
