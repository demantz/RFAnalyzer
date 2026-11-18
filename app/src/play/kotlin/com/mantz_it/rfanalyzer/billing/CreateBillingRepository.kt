package com.mantz_it.rfanalyzer.billing

import android.content.Context
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.database.AppStateRepository

/**
 * <h1>RF Analyzer - Billing Repository (Play)</h1>
 *
 * Module:      CreateBillingRepository.kt
 * Description: Google Play billing in the release and profile builds, the mock in debug builds
 *              (whose license gate still applies - see MockBillingRepository).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
fun createBillingRepository(context: Context, appStateRepository: AppStateRepository): BillingRepository =
    if (BuildConfig.BUILD_TYPE == "debug") MockBillingRepository(appStateRepository)
    else PlayBillingRepository(context, appStateRepository)
