package com.mantz_it.rfanalyzer.ui.composable

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.ui.RFAnalyzerTheme

/**
 * <h1>RF Analyzer - About Tab</h1>
 *
 * Module:      AboutTab.kt
 * Description: A Tab in the Control Drawer. Gives access to the manual and the purchase button.
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


data class AboutTabActions(
    val onAboutClicked: () -> Unit,
    val onManualClicked: () -> Unit,
    val onTutorialClicked: () -> Unit,
    val onBuyFullVersionClicked: () -> Unit
)

@Composable
fun AboutTabComposable(
    appUsageTime: Int,
    isAppUsageTimeUsedUp: Boolean,
    remainingTrialDays: Int,
    isFullVersion: Boolean,
    isPurchasePending: Boolean,
    appVersion: String,
    appBuildType: String,
    aboutTabActions: AboutTabActions,
) {
    val context = LocalContext.current
    val buttonHeight = 60.dp

    ScrollableColumnWithFadingEdge {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .border(2.dp, MaterialTheme.colorScheme.tertiary, shape = MaterialTheme.shapes.medium) // Border
        ) {
            if (isFullVersion) {
                Column {
                    Text(
                        "License: Full Version",
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(all = 10.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                    Text("Thank you for contributing to the development of this app :)",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 10.dp))
                }
            } else {
                Text(
                    "License: Trial Version",
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(all = 6.dp)
                        .align(Alignment.CenterHorizontally)
                )
                Column(modifier = Modifier.padding(start = 10.dp)) {
                    Text("This is a trial version of the app, created to let you explore and enjoy all its features before deciding to purchase the full version.\n" +
                            "You can use the app for up to 7 days after installation, with a total of 60 minutes of actual operating time.")
                    Text("- Time used: ${(appUsageTime/60)} minutes and ${appUsageTime%60} seconds",
                        fontWeight = FontWeight.SemiBold,
                        color = if (isAppUsageTimeUsedUp) Color.Red else Color.Green,
                        modifier = Modifier.padding(top = 6.dp))
                    Text("- Days remaining: ${remainingTrialDays}",
                        fontWeight = FontWeight.SemiBold,
                        color = if (remainingTrialDays <= 0) Color.Red else Color.Green,
                        modifier = Modifier.padding(bottom = 6.dp))
                    Text("Once either limit is reached, you’ll be invited to unlock the full version with a one-time in-app purchase.\n" +
                            "Thanks for trying the app! 73 DM4NTZ")
                }
                Button(
                    onClick = aboutTabActions.onBuyFullVersionClicked,
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 8.dp).fillMaxWidth()
                )
                {
                    if (isPurchasePending) {
                        Text("Purchase pending..", modifier = Modifier.padding(end = 10.dp))
                        CircularProgressIndicator(color = Color.White)
                    } else {
                        Text("Unlock Full Version", modifier = Modifier.padding(end = 10.dp))
                        Icon(Icons.Default.ShoppingCart, "Unlock Full Version")
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = aboutTabActions.onAboutClicked,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .height(buttonHeight)
                    .padding(end = 3.dp)
                    .weight(1f)
            ) { Text("About the App") }
            Button(onClick = aboutTabActions.onManualClicked,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .height(buttonHeight)
                    .padding(start = 3.dp)
                    .weight(1f)
            ) { Text("Manual") }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://www.youtube.com/watch?v=sui54fqbImw".toUri())
                    context.startActivity(intent)
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .weight(1f)
                    .height(buttonHeight)
                    .padding(end = 3.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.youtube),
                    contentDescription = "YouTube Channel",
                    modifier = Modifier.size(75.dp)
                )
            }
            Button(onClick = aboutTabActions.onTutorialClicked,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .height(buttonHeight)
                    .padding(start = 3.dp)
                    .weight(1f)
            ) { Text("Tutorial") }
        }

        Text("RF Analyzer - Version $appVersion ($appBuildType)",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top=16.dp))
    }
}

@Preview
@Composable
fun AboutTabPreview() {
    val snackbarHostState = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        RFAnalyzerTheme {
            AboutTabComposable(
                appUsageTime = 130,
                isAppUsageTimeUsedUp = false,
                remainingTrialDays = 3,
                isFullVersion = false,
                isPurchasePending = true,
                appVersion = "2.0test1",
                appBuildType = "debug",
                aboutTabActions = AboutTabActions(
                    onAboutClicked = { },
                    onManualClicked = { },
                    onTutorialClicked = { },
                    onBuyFullVersionClicked = { },
                )
            )
        }
    }
}