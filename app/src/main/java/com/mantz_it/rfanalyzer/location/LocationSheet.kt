package com.mantz_it.rfanalyzer.location

import android.Manifest
import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.mantz_it.rfanalyzer.database.COUNTRY_CODE_TO_NAME
import com.mantz_it.rfanalyzer.digiext.ui.common.formatAge
import com.mantz_it.rfanalyzer.ui.composable.OutlinedBox
import com.mantz_it.rfanalyzer.ui.composable.countryCodeToFlagEmoji
import kotlinx.coroutines.launch

/**
 * <h1>RF Analyzer - Location Sheet</h1>
 *
 * Module:      LocationSheet.kt
 * Description: Where the user tells the app where they listen from (UserLocation.kt): a country or
 *              just an ITU region - guessed until they pick one - and optionally a position, as a
 *              grid locator or this device's location. The location permission is asked for only
 *              when the user picks the device's location here (the map's own button asks by
 *              itself). Opened from the Settings tab, and by a feature that needs more than is
 *              known.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

data class LocationSheetActions(
    val onChooseAutomatic: () -> Unit,
    val onChooseCountry: (String) -> Unit,
    val onChooseRegion: (ItuRegion) -> Unit,
    val onChoosePositionMode: (PositionMode) -> Unit,
    val onLocatorChanged: (String) -> Unit,
    val hasLocationPermission: () -> Boolean,
    /** Takes a new device fix; false if none could be had. */
    val onUpdateDeviceFix: suspend () -> Boolean,
)

/** One line for a summary row: "🇩🇪 Germany · from your mobile network" / "ITU Region 1" / "Not set". */
fun UserLocation.summary(): String = when {
    countryCode != null -> "${countryCodeToFlagEmoji(countryCode)} $countryName" + (countrySource?.let { " · ${it.description}" } ?: "")
    ituRegion != null -> "ITU ${ituRegion.label} (${ituRegion.description})"
    else -> "Not set"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSheet(
    location: UserLocation,
    /** The phone's own suggestion, for the "Automatic" entry. */
    guessedCountry: Pair<String, CountrySource>?,
    /** Whether the country is the guess (nothing chosen). */
    automatic: Boolean,
    /** The locator as stored - kept while another position mode is chosen. */
    locatorInput: String,
    actions: LocationSheetActions,
    onDismiss: () -> Unit,
    /** Why a feature opened the sheet; null from Settings. */
    reason: String? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickerOpen by remember { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }
    var fixFailed by remember { mutableStateOf(false) }
    var fixRunning by remember { mutableStateOf(false) }
    val nowMs = System.currentTimeMillis()

    fun updateFix() {
        fixRunning = true
        scope.launch {
            fixFailed = !actions.onUpdateDeviceFix()
            fixRunning = false
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) {
            actions.onChoosePositionMode(PositionMode.DEVICE)
            updateFix()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            Text("Location", style = MaterialTheme.typography.titleLarge)
            Text(
                reason ?: (if (DigitalFeatures.shown) "Presets, band plans, the maps and the RDS programme types follow your area. It stays on this device."
                           else "Band plans and the maps follow your area. It stays on this device."),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedBox(
                label = "Country or region",
                onClick = { pickerOpen = true },
                background = BottomSheetDefaults.ContainerColor,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) {
                Column(modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 8.dp)) {
                    Text(
                        location.countryCode?.let { "${countryCodeToFlagEmoji(it)} ${location.countryName}" }
                            ?: location.ituRegion?.let { "ITU ${it.label}" } ?: "Not set - tap to choose",
                        fontSize = 18.sp,
                    )
                    val detail = when {
                        location.countryCode != null -> location.countrySource?.description
                        location.ituRegion != null -> location.ituRegion.description
                        else -> null
                    }
                    detail?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }

            Text("Your position (optional)", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
            Text(
                if (DigitalFeatures.shown) "Puts the stations nearest to you first, and the maps start there. Presets by country don't need it."
                else "The maps start there instead of on the whole country.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PositionMode.entries.forEach { mode ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        when {
                            mode != PositionMode.DEVICE -> actions.onChoosePositionMode(mode)
                            actions.hasLocationPermission() -> { actions.onChoosePositionMode(mode); updateFix() }
                            else -> permissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
                        }
                    },
                ) {
                    RadioButton(selected = location.positionMode == mode, onClick = null, modifier = Modifier.padding(end = 8.dp))
                    Text(if (mode == PositionMode.DEVICE) "This device's location (approximate)" else mode.displayName)
                }
                if (mode == PositionMode.LOCATOR && location.positionMode == PositionMode.LOCATOR) {
                    OutlinedTextField(
                        value = locatorInput,
                        onValueChange = { actions.onLocatorChanged(it.take(6)) },
                        label = { Text("Maidenhead locator, e.g. JN49 or JN49eh") },
                        singleLine = true,
                        isError = locatorInput.isNotEmpty() && normalizeLocator(locatorInput) == null,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                        modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
                    )
                }
                if (mode == PositionMode.DEVICE && location.positionMode == PositionMode.DEVICE) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 40.dp)) {
                        Text(
                            when {
                                fixRunning -> "Getting a location fix…"
                                location.locator != null -> "${location.locator} · ${formatAge(nowMs, location.fixTimeMs ?: nowMs)} ago"
                                fixFailed -> "No location available - is location turned on?"
                                else -> "No location fix yet"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { updateFix() }, enabled = !fixRunning) { Text("Update") }
                    }
                }
            }
            if (permissionDenied) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Location permission denied. Allow it in the app's settings, or enter a grid locator.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { openAppSettings(context) }) { Text("App settings") }
                }
            }
            Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("Done") }
        }
    }

    if (pickerOpen) {
        AreaPickerDialog(
            guessedCountry = guessedCountry,
            automatic = automatic,
            selectedCountry = location.countryCode.takeIf { !automatic },
            selectedRegion = location.ituRegion.takeIf { location.countryCode == null },
            onAutomatic = { actions.onChooseAutomatic(); pickerOpen = false },
            onCountry = { actions.onChooseCountry(it); pickerOpen = false },
            onRegion = { actions.onChooseRegion(it); pickerOpen = false },
            onDismiss = { pickerOpen = false },
        )
    }
}

/** Automatic (the phone's guess), a region alone, or a country - searchable like CountryPickerDialog. */
@Composable
private fun AreaPickerDialog(
    guessedCountry: Pair<String, CountrySource>?,
    automatic: Boolean,
    selectedCountry: String?,
    selectedRegion: ItuRegion?,
    onAutomatic: () -> Unit,
    onCountry: (String) -> Unit,
    onRegion: (ItuRegion) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val countries = COUNTRY_CODE_TO_NAME.entries.filter { (code, name) -> "$name ($code)".contains(query, ignoreCase = true) }
        .sortedBy { it.value }
    val regions = ItuRegion.entries.filter { "ITU ${it.label} ${it.description}".contains(query, ignoreCase = true) }

    @Composable
    fun Entry(headline: String, supporting: String?, selected: Boolean, onClick: () -> Unit) = ListItem(
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = if (selected) { { Icon(Icons.Default.Check, contentDescription = "Selected", modifier = Modifier.size(20.dp)) } } else null,
        modifier = Modifier.clickable(onClick = onClick),
    )

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 8.dp, modifier = Modifier.fillMaxSize(0.9f)) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search country") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (query.isEmpty()) {
                        item {
                            Entry(
                                "Automatic",
                                guessedCountry?.let { (code, source) -> "${countryCodeToFlagEmoji(code)} ${COUNTRY_CODE_TO_NAME[code]}, ${source.description}" }
                                    ?: "Nothing to go by on this device",
                                automatic,
                                onAutomatic,
                            )
                        }
                    }
                    items(regions) { region -> Entry("ITU ${region.label} only", region.description, region == selectedRegion, { onRegion(region) }) }
                    item { HorizontalDivider() }
                    items(countries, key = { it.key }) { (code, name) ->
                        Entry("${countryCodeToFlagEmoji(code)} $name", null, code == selectedCountry, { onCountry(code) })
                    }
                }
            }
        }
    }
}
