package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.billing.BillingState
import com.mantz_it.rfanalyzer.billing.License
import com.mantz_it.rfanalyzer.billing.PlayStatus
import com.mantz_it.rfanalyzer.billing.Product
import com.mantz_it.rfanalyzer.billing.PurchaseSnapshot
import com.mantz_it.rfanalyzer.digiext.formatTrialTimeLeft

/**
 * <h1>RF Analyzer - License Sheet</h1>
 *
 * Module:      LicenseSheet.kt
 * Description: The one place where RF Analyzer is bought - opened by every "buy" or "upgrade"
 *              button (trial pill, trial dialogs, Digital tab, About tab). It says what each option
 *              includes and costs, Google Play's prices next to each other, and offers only what
 *              Google Play's answer allows (BillingState.purchasable): a Full Version owner sees no
 *              button, a Base Version owner only the upgrade.
 *
 * It states the facts and leaves the choice to the user: no countdown, no "best value" badge.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** What the sheet shows, from Google Play's answer. */
sealed interface LicenseSheetContent {
    /** Whether the sheet offers something to buy (then the Terms notice goes with it). */
    val offersPurchase: Boolean
        get() = this is Pending || this == Upgrade || this == CompleteWithBaseVersion || this == Choose || this == FullVersionAlone

    /** No answer from Play in this process yet. */
    data object AskingPlay : LicenseSheetContent
    /** Play didn't answer at all in this process. */
    data object PlayUnavailable : LicenseSheetContent
    data class Pending(val products: Set<Product>) : LicenseSheetContent
    data class FullVersionOwned(val owned: Set<Product>) : LicenseSheetContent
    /** Base Version owned: the upgrade. */
    data object Upgrade : LicenseSheetContent
    /** The upgrade owned without the Base Version (refunded): the Base Version completes it. */
    data object CompleteWithBaseVersion : LicenseSheetContent
    /** Nothing bought: Base Version or Full Version. */
    data object Choose : LicenseSheetContent
    /** Nothing bought while digital features are hidden: the Full Version alone. TEMPORARY for
     *  RF Analyzer 2.3 (purchasableProducts) - goes with DIGITAL_LAUNCHED in 2.4. */
    data object FullVersionAlone : LicenseSheetContent
}

fun licenseSheetContent(state: BillingState, extensionsShown: Boolean = true): LicenseSheetContent {
    val purchases = state.purchases ?: return if (state.playStatus == PlayStatus.UNAVAILABLE) LicenseSheetContent.PlayUnavailable else LicenseSheetContent.AskingPlay
    return when {
        purchases.pending.isNotEmpty() -> LicenseSheetContent.Pending(purchases.pending)
        purchases.license == License.FULL -> LicenseSheetContent.FullVersionOwned(purchases.owned)
        purchases.license == License.BASE -> LicenseSheetContent.Upgrade
        Product.FULL_VERSION_UPGRADE in purchases.owned -> LicenseSheetContent.CompleteWithBaseVersion
        !extensionsShown -> LicenseSheetContent.FullVersionAlone
        else -> LicenseSheetContent.Choose
    }
}

fun productName(product: Product): String = when (product) {
    Product.FULL_VERSION -> "Full Version"
    Product.BASE_VERSION -> "Base Version"
    Product.FULL_VERSION_UPGRADE -> "Full Version Upgrade"
}

/** "Full Version", "Base Version + Full Version Upgrade". */
fun ownedProductsText(owned: Set<Product>): String =
    Product.entries.filter { it in owned }.sortedBy { it != Product.BASE_VERSION }.joinToString(" + ", transform = ::productName)

// The modes are what is bought here; the RFA Digital app that decodes them costs nothing extra.
private const val DIGITAL_MODES = "Digital modes: FT8, APRS, ADS-B, POCSAG, LoRa mesh networks, RDS, RTTY, PSK31 and more (decoded by the RFA Digital app, a free download)"
private const val EXTENSIONS = "All extensions, also those still to come"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicenseSheet(
    billing: BillingState,
    /** The cached license (AppStateRepository.license): which trial counts. */
    license: License,
    trialSecondsLeft: Int,
    /** Null while digital features are hidden (DigitalFeatures). */
    digitalTrialSecondsLeft: Int?,
    /** DigitalFeatures.shown - false: the Full Version alone (FullVersionAlone). */
    extensionsShown: Boolean,
    /** What went wrong with the last purchase attempt. */
    message: String?,
    onBuy: (Product) -> Unit,
    onRetry: () -> Unit,
    /** Opens the Terms of Use (the sheet closes). */
    onTermsClicked: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
        ) {
            Text("RF Analyzer License", style = MaterialTheme.typography.titleLarge)
            trialLine(license, trialSecondsLeft, digitalTrialSecondsLeft)?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.HourglassBottom, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            fun option(product: Product) = OptionButton(
                label = "Buy ${productName(product)}",
                enabled = product in billing.purchasable(extensionsShown) && billing.purchaseStarting == null,
                busy = billing.purchaseStarting == product,
                onClick = { onBuy(product) },
            )
            val prices = billing.prices
            val content = licenseSheetContent(billing, extensionsShown)
            when (content) {
                LicenseSheetContent.AskingPlay -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Asking Google Play…")
                }
                LicenseSheetContent.PlayUnavailable -> PlayUnavailable(onRetry)
                is LicenseSheetContent.Pending -> {
                    StatusCard(
                        title = "Purchase pending",
                        text = "Your ${content.products.joinToString(" and ", transform = ::productName)} unlocks as soon as Google Play " +
                               "confirms the payment (for example a payment in cash or by bank transfer).\n" +
                               "Was the payment declined or cancelled? Google Play can take a while to tell the app - you can buy it again.",
                        icon = Icons.Default.HourglassBottom,
                    )
                    // Play decides: it refuses while the payment is still pending (purchasableProducts)
                    content.products.filter { it in billing.purchasable(extensionsShown) || billing.purchaseStarting == it }.forEach { product ->
                        val button = option(product)
                        OutlinedButton(onClick = button.onClick, enabled = button.enabled, modifier = Modifier.fillMaxWidth()) {
                            if (button.busy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                            }
                            Text("Buy ${productName(product)} again")
                        }
                    }
                }
                is LicenseSheetContent.FullVersionOwned -> StatusCard(
                    title = "You own the Full Version",
                    text = "Google Play reports: ${ownedProductsText(content.owned)}. Everything is unlocked - thank you for supporting RF Analyzer!",
                    icon = Icons.Default.CheckCircle,
                )
                LicenseSheetContent.Upgrade -> {
                    Text("You own the Base Version. The upgrade adds the rest:", style = MaterialTheme.typography.bodyMedium)
                    OptionCard(
                        title = productName(Product.FULL_VERSION_UPGRADE),
                        price = prices[Product.FULL_VERSION_UPGRADE],
                        lines = listOf(OptionLine(DIGITAL_MODES), OptionLine(EXTENSIONS)),
                        note = "Together with your Base Version, the same as the Full Version.",
                        button = option(Product.FULL_VERSION_UPGRADE),
                    )
                }
                LicenseSheetContent.CompleteWithBaseVersion -> {
                    Text(
                        "Google Play reports the Full Version Upgrade, but not the Base Version it builds on (refunded?). " +
                        "Buying the Base Version again completes the Full Version.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OptionCard(
                        title = productName(Product.BASE_VERSION),
                        price = prices[Product.BASE_VERSION],
                        lines = listOf(OptionLine("Completes the Full Version with your upgrade")),
                        button = option(Product.BASE_VERSION),
                    )
                }
                LicenseSheetContent.Choose -> {
                    OptionCard(
                        title = productName(Product.BASE_VERSION),
                        price = prices[Product.BASE_VERSION],
                        lines = listOf(
                            OptionLine("All of RF Analyzer without the time limit: spectrum, waterfall, demodulation, recording, bookmarks"),
                            OptionLine("Digital modes: the two-hour trial only", included = false),
                        ),
                        note = prices[Product.FULL_VERSION_UPGRADE]?.let { "Upgrade to the Full Version later: $it" }
                            ?: "Upgrade to the Full Version later at any time.",
                        button = option(Product.BASE_VERSION),
                    )
                    OptionCard(
                        title = productName(Product.FULL_VERSION),
                        price = prices[Product.FULL_VERSION],
                        lines = listOf(OptionLine("Everything in the Base Version"), OptionLine(DIGITAL_MODES), OptionLine(EXTENSIONS)),
                        button = option(Product.FULL_VERSION),
                    )
                }
                LicenseSheetContent.FullVersionAlone -> OptionCard(
                    title = productName(Product.FULL_VERSION),
                    price = prices[Product.FULL_VERSION],
                    lines = listOf(
                        OptionLine("All of RF Analyzer without the time limit: spectrum, waterfall, demodulation, recording, bookmarks"),
                        OptionLine(EXTENSIONS),
                    ),
                    button = option(Product.FULL_VERSION),
                )
            }
            if (content.offersPurchase) TermsNotice(onTermsClicked)
            message?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            // An earlier answer is shown, but nothing can be bought while Play doesn't answer.
            if (billing.playStatus == PlayStatus.UNAVAILABLE && billing.purchases != null) PlayUnavailable(onRetry)

            Text(
                "One-time purchases through Google Play - no subscription, no ads. They belong to your Google account " +
                "and come back after reinstalling or on a new phone.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Where buying concludes the contract: the Terms apply (they say so for Google Play purchases). */
@Composable
private fun TermsNotice(onTermsClicked: () -> Unit) {
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    Text(
        buildAnnotatedString {
            append("By purchasing, you agree to the ")
            withLink(LinkAnnotation.Clickable("terms", link) { onTermsClicked() }) { append("Terms of Use") }
            append(". Consumers have a statutory right of withdrawal (see the Terms).")
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** "Trial: 42 min left · Digital modes trial: 1 h 52 min left"; null with the Full Version. */
internal fun trialLine(license: License, trialSecondsLeft: Int, digitalTrialSecondsLeft: Int?): String? {
    fun left(seconds: Int) = if (seconds > 0) "${formatTrialTimeLeft(seconds)} left" else "used up"
    val digital = digitalTrialSecondsLeft?.let { "Digital modes trial: ${left(it)}" }
    return when (license) {
        License.FULL -> null
        License.BASE -> digital
        License.TRIAL -> listOfNotNull("Trial: ${left(trialSecondsLeft)}", digital).joinToString(" · ")
    }
}

private data class OptionLine(val text: String, val included: Boolean = true)

private data class OptionButton(val label: String, val enabled: Boolean, val busy: Boolean, val onClick: () -> Unit)

@Composable
private fun OptionCard(title: String, price: String?, lines: List<OptionLine>, button: OptionButton, note: String? = null) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                price?.let { Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
            }
            lines.forEach { line ->
                Row {
                    Icon(
                        if (line.included) Icons.Default.Check else Icons.Default.Remove,
                        contentDescription = if (line.included) "Included" else "Not included",
                        tint = if (line.included) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        line.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (line.included) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Button(onClick = button.onClick, enabled = button.enabled, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                if (button.busy) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(button.label)
            }
        }
    }
}

@Composable
private fun StatusCard(title: String, text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(16.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun PlayUnavailable(onRetry: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Google Play isn't reachable. Purchases need the Play Store app, signed in, and an internet connection.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = onRetry) { Text("Try again") }
    }
}
