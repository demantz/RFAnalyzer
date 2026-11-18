package com.mantz_it.rfanalyzer.ui.tabs

import com.mantz_it.rfanalyzer.ui.startExternalActivity
import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import com.mantz_it.rfanalyzer.billing.BillingState
import com.mantz_it.rfanalyzer.billing.License
import com.mantz_it.rfanalyzer.billing.OwnedPurchase
import com.mantz_it.rfanalyzer.billing.PlayStatus
import com.mantz_it.rfanalyzer.billing.Product
import com.mantz_it.rfanalyzer.billing.PurchaseSnapshot
import com.mantz_it.rfanalyzer.ui.composable.ownedProductsText
import com.mantz_it.rfanalyzer.ui.composable.productName
import com.mantz_it.rfanalyzer.digiext.formatTrialTimeLeft
import com.mantz_it.rfanalyzer.database.AppStateRepository
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import java.text.DateFormat
import java.util.Date
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.ui.AppScreen
import com.mantz_it.rfanalyzer.ui.RFAnalyzerTheme
import com.mantz_it.rfanalyzer.ui.composable.FossDonationOptionDialogContent
import com.mantz_it.rfanalyzer.ui.composable.LocalSnackbarHostState
import com.mantz_it.rfanalyzer.ui.composable.ScrollableColumnWithFadingEdge
import com.mantz_it.rfanalyzer.ui.composable.toTimeSpanString

/**
 * <h1>RF Analyzer - About Tab</h1>
 *
 * Module:      AboutTab.kt
 * Description: A Tab in the Control Drawer. The license (or, in the FOSS build, donations), the
 *              manual, tutorials and the legal pages.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


data class AboutTabActions(
    val onAboutClicked: () -> Unit,
    val onManualClicked: () -> Unit,
    val onTutorialClicked: (AppScreen) -> Unit,
    /** Opens the license sheet. */
    val onLicenseOptionsClicked: () -> Unit,
    val onCheckPurchasesClicked: () -> Unit,
    /** The license section came into view (prices for its upgrade button). */
    val onLicenseSectionShown: () -> Unit,
    /** A page of the manual's Legal section ("legal/terms.html", …). */
    val onLegalPageClicked: (String) -> Unit,
)

@Composable
fun AboutTabComposable(
    appUsageTime: Int,
    /** Operating time left in the trial (AppStateRepository.trialSecondsLeft). */
    trialSecondsLeft: Int,
    license: License,
    /** Google Play's last answer: what exactly was bought, pending payments, prices. */
    billing: BillingState,
    /** Decoding time left in the separate digital modes trial (AppStateRepository.digitalTrialSecondsLeft). */
    digitalTrialSecondsLeft: Int,
    appVersion: String,
    appBuildType: String,
    aboutTabActions: AboutTabActions,
) {
    val context = LocalContext.current
    val buttonHeight = 60.dp
    var showTutorialDialog by remember { mutableStateOf(false) }

    ScrollableColumnWithFadingEdge {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp)
                .border(2.dp, MaterialTheme.colorScheme.tertiary, shape = MaterialTheme.shapes.medium)
        ) {
            val usageTimeStr = (appUsageTime*1000L).toTimeSpanString()

            if (BuildConfig.IS_FOSS) {
                Text(
                    "Open-Source Version (FOSS)",
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(all = 10.dp)
                        .align(Alignment.CenterHorizontally)
                )
                // The one mention of digital modes in this build (DigitalFeatures) - no more.
                if (!DigitalFeatures.inBuild) Text(
                    "Digital modes (FT8, APRS, ADS-B, mesh networks and more) are not part of the open-source version.",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp).align(Alignment.CenterHorizontally)
                )
                var donationButtonClicked by remember { mutableStateOf(false) }
                FossDonationOptionDialogContent(
                    usageTimeStr = usageTimeStr,
                    postDonationAction = { donationButtonClicked = true },
                )
                if (donationButtonClicked) {
                    Text(
                        "Thank you! 💛",
                        fontSize = 18.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                            .align(Alignment.CenterHorizontally)
                    )
                }
            } else {
                LicenseSection(license, billing, trialSecondsLeft, digitalTrialSecondsLeft, aboutTabActions)
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
                    context.startExternalActivity(intent)
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
            Button(onClick = { showTutorialDialog = true },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .height(buttonHeight)
                    .padding(start = 3.dp)
                    .weight(1f)
            ) { Text("Tutorials") }
        }

        Text("RF Analyzer - Version $appVersion ($appBuildType)",
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(top=16.dp))
        Row(horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
            // The Terms cover the Google Play version only (they say so)
            if (!BuildConfig.IS_FOSS) TextButton(onClick = { aboutTabActions.onLegalPageClicked("legal/terms.html") }) { Text("Terms of Use") }
            TextButton(onClick = { aboutTabActions.onLegalPageClicked("legal/privacy.html") }) { Text("Privacy Policy") }
            TextButton(onClick = { aboutTabActions.onLegalPageClicked("legal/imprint.html") }) { Text("Imprint") }
        }
    }

    if (showTutorialDialog) {
        AlertDialog(
            onDismissRequest = { showTutorialDialog = false },
            title = { Text("Select Tutorial") },
            text = {
                Column {
                    TextButton(onClick = { showTutorialDialog = false; aboutTabActions.onTutorialClicked(AppScreen.WelcomeScreen) }) { Text("RF Analyzer Quick Start") }
                    TextButton(onClick = { showTutorialDialog = false; aboutTabActions.onTutorialClicked(AppScreen.BookmarksTutorial) }) { Text("Bookmark Manager Tutorial") }
                    TextButton(onClick = { showTutorialDialog = false; aboutTabActions.onTutorialClicked(AppScreen.WhatsNewScreen) }) { Text("What's New") }
                }
            },
            confirmButton = {}
        )
    }

}

@Preview
@Composable
fun AboutTabPreview() {
    val snackbarHostState = remember { SnackbarHostState() }
    CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
        RFAnalyzerTheme {
            AboutTabComposable(
                appUsageTime = 60*60+60*3+13,
                trialSecondsLeft = 2520,
                license = License.TRIAL,
                digitalTrialSecondsLeft = 6720,
                billing = BillingState(purchases = PurchaseSnapshot(), playStatus = PlayStatus.READY),
                appVersion = "2.0test1",
                appBuildType = "debug",
                aboutTabActions = AboutTabActions(
                    onAboutClicked = { },
                    onManualClicked = { },
                    onLegalPageClicked = { },
                    onTutorialClicked = { },
                    onLicenseOptionsClicked = { },
                    onCheckPurchasesClicked = { },
                    onLicenseSectionShown = { },
                )
            )
        }
    }
}
/**
 * The license as Google Play reports it, the trial time left, and a button to the license sheet
 * unless the Full Version is owned. Purchases happen in the license sheet only.
 */
@Composable
private fun LicenseSection(
    license: License,
    billing: BillingState,
    trialSecondsLeft: Int,
    digitalTrialSecondsLeft: Int,
    actions: AboutTabActions,
) {
    LaunchedEffect(Unit) { actions.onLicenseSectionShown() }
    val purchases = billing.purchases
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(
            when (license) {
                License.FULL -> "License: Full Version"
                License.BASE -> "License: Base Version"
                License.TRIAL -> "License: Trial Version"
            },
            fontSize = 22.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp),
        )
        // Exactly what Play reports, when it is more than the license's name says.
        purchases?.owned?.takeIf { license == License.FULL && Product.BASE_VERSION in it }?.let {
            Text(ownedProductsText(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 6.dp))
        }
        when (license) {
            License.FULL -> Text(
                "Thank you for contributing to the development of this app :)",
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            )
            License.BASE -> if (DigitalFeatures.shown) {
                Text("Thank you for contributing to the development of this app :)\n" +
                     "Digital modes are part of the Full Version; with the Base Version they have a trial of " +
                     "up to ${AppStateRepository.DIGITAL_TRIAL_SECONDS / 3600} hours of decoding.")
                TrialTimeLeft("Digital modes", digitalTrialSecondsLeft)
            } else {
                Text("Thank you for contributing to the development of this app :)", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp))
            }
            License.TRIAL -> {
                Text(
                    "This is a trial version of the app, created to let you explore and enjoy all its features before deciding to purchase it.\n" +
                    "You can use the app for up to a total of 60 minutes of actual operating time."
                )
                // Both trials say what is left, like the trial pill above the drawer.
                TrialTimeLeft("Operating time", trialSecondsLeft)
                if (DigitalFeatures.shown) {
                    Text("Digital modes have their own trial: up to ${AppStateRepository.DIGITAL_TRIAL_SECONDS / 3600} hours of decoding, counted only while a digital mode is received.")
                    TrialTimeLeft("Digital modes", digitalTrialSecondsLeft)
                }
                Text("Thanks for trying the app! 73 DM4NTZ")
            }
        }
        purchases?.pending?.takeIf { it.isNotEmpty() }?.let { pending ->
            Text(
                "Purchase pending: ${pending.joinToString(" and ", transform = ::productName)} - unlocks as soon as Google Play confirms the payment.",
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        if (license != License.FULL) {
            val upgradePrice = billing.prices[Product.FULL_VERSION_UPGRADE]
            Button(
                onClick = actions.onLicenseOptionsClicked,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(top = 10.dp).fillMaxWidth(),
            ) {
                Text(
                    if (license == License.BASE) "Upgrade to Full Version" + (upgradePrice?.let { " · $it" } ?: "") else "Purchase options",
                    modifier = Modifier.padding(end = 10.dp),
                )
                Icon(Icons.Default.ShoppingCart, contentDescription = null)
            }
        }
        // Where a paying user sees the trial: another Google account active in the Play Store.
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text(
                "Purchases belong to the Google account active in the Play Store.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.onCheckPurchasesClicked) { Text("Check again") }
        }
        if (billing.ownedPurchases.isNotEmpty()) {
            var showDetails by remember { mutableStateOf(false) }
            TextButton(onClick = { showDetails = true }, modifier = Modifier.align(Alignment.End)) { Text("Purchase details") }
            if (showDetails) PurchaseDetailsDialog(billing.ownedPurchases, onDismiss = { showDetails = false })
        }
    }
}

/** What was bought, when, and its order number - what a user quotes when asking about a purchase. */
@Composable
private fun PurchaseDetailsDialog(purchases: List<OwnedPurchase>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val text = purchaseDetailsText(purchases)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Purchase details") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                purchases.forEach { purchase ->
                    Column {
                        Text(productName(purchase.product), style = MaterialTheme.typography.titleSmall)
                        Text(purchaseDateAndOrder(purchase), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        dismissButton = {
            TextButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("RF Analyzer purchase details", text))
            }) { Text("Copy") }
        },
    )
}

/**
 * "12 Nov 2026 · GPA.1234-5678-9012-34567". A completed purchase without an order number was
 * redeemed with a promo code (test purchases have an order number too; pending ones aren't listed
 * here).
 */
private fun purchaseDateAndOrder(purchase: OwnedPurchase): String =
    "${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(purchase.purchaseTimeMs))} · ${purchase.orderId ?: "promo code (no order number)"}"

/** What Copy puts on the clipboard: "Base Version · 12 Nov 2026 · GPA.1234-5678-9012-34567", one line per purchase. */
internal fun purchaseDetailsText(purchases: List<OwnedPurchase>): String =
    purchases.joinToString("\n") { "${productName(it.product)} · ${purchaseDateAndOrder(it)}" }

@Composable
private fun TrialTimeLeft(label: String, secondsLeft: Int) {
    Text(
        if (secondsLeft > 0) "$label: ${formatTrialTimeLeft(secondsLeft)} left" else "$label: trial used up",
        fontWeight = FontWeight.SemiBold,
        color = if (secondsLeft > 0) Color.Green else Color.Red,
        modifier = Modifier.padding(vertical = 6.dp),
    )
}
