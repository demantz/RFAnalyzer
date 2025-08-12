package com.mantz_it.rfanalyzer.ui.screens

import android.content.res.Configuration
import android.util.Log
import android.view.ViewGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.viewinterop.AndroidView
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.BillingRepositoryInterface
import com.mantz_it.rfanalyzer.ui.AnalyzerSurface
import com.mantz_it.rfanalyzer.ui.MainViewModel
import com.mantz_it.rfanalyzer.ui.composable.AnalyzerTabsComposable
import com.mantz_it.rfanalyzer.ui.composable.ControlDrawerSide
import com.mantz_it.rfanalyzer.ui.composable.CustomSideDrawerOverlay
import com.mantz_it.rfanalyzer.ui.composable.DrawerSide

/**
 * <h1>RF Analyzer - Main Screen</h1>
 *
 * Module:      MainScreen.kt
 * Description: The main screen of the application which contains the AnalyzerSurface (FFT/Waterfall)
 * and the Control Drawer which holds the AnalyzerTabs
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


@Composable
fun MainScreen(analyzerSurface: AnalyzerSurface, viewModel: MainViewModel, appStateRepository: AppStateRepository, billingRepository: BillingRepositoryInterface) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isDrawerOpen = rememberSaveable { mutableStateOf(true) }
    val controlDrawerSide by appStateRepository.controlDrawerSide.stateFlow.collectAsState()

    CustomSideDrawerOverlay(
        drawerSide = if(isLandscape) {
            if (controlDrawerSide == ControlDrawerSide.RIGHT) DrawerSide.RIGHT
            else DrawerSide.LEFT
        } else DrawerSide.BOTTOM,
        content = {
            Box(modifier = Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { _ ->
                        Log.d("MainScreen", "AndroidView.factory: reusing surface: ${System.identityHashCode(analyzerSurface)} (parent: ${analyzerSurface.parent})")
                        // Detach from previous parent if it exists
                        (analyzerSurface.parent as? ViewGroup)?.removeView(analyzerSurface)
                        // Return analyzer surface:
                        analyzerSurface
                    },
                    modifier = Modifier
                        .fillMaxSize()
                )
            }
        },
        drawerContent = { AnalyzerTabsComposable(
            mainViewModel = viewModel,
            appStateRepository = appStateRepository,
            billingRepository = billingRepository,
            sourceTabActions = viewModel.sourceTabActions,
            displayTabActions = viewModel.displayTabActions,
            demodulationTabActions = viewModel.demodulationTabActions,
            recordingTabActions = viewModel.recordingTabActions,
            settingsTabActions = viewModel.settingsTabActions,
            aboutTabActions = viewModel.aboutTabActions,
        ) },
        isDrawerOpen = isDrawerOpen.value,
        onDismiss = { isDrawerOpen.value = false },
        fabOnClick = { isDrawerOpen.value = true }
    )
}