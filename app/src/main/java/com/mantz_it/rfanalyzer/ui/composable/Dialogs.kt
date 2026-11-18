package com.mantz_it.rfanalyzer.ui.composable

import com.mantz_it.rfanalyzer.ui.startExternalActivity
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.net.toUri
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.database.COUNTRY_CODE_TO_NAME
import com.mantz_it.rfanalyzer.database.MapCameraPosition
import com.mantz_it.rfanalyzer.location.MapHome
import com.mantz_it.rfanalyzer.network.KiwiSdrPublicEntry
import com.mantz_it.rfanalyzer.ui.RFAnalyzerTheme
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketBadge

/**
 * <h1>RF Analyzer - Dialogs</h1>
 *
 * Module:      Dialogs.kt
 * Description: Dialogs and sheets used across the app: the donation dialog of the FOSS build, a
 *              dialog that edits one string, the base bottom sheet of the edit forms (with a prompt
 *              for unsaved changes), the country picker, a row of preset colors and the picker for
 *              a public KiwiSDR receiver, as a searchable list and a map.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@Preview
@Composable
fun donationDialogPreview() {
    RFAnalyzerTheme {
        FossDonationDialog(
            usageTimeStr = "1 hour and 3 minutes",
            dismissAction = {}
        )
    }
}

@Composable
fun FossDonationOptionDialogContent(
    usageTimeStr: String,
    postDonationAction: () -> Unit,
    modifier: Modifier = Modifier,
    scrollableText: Boolean = false
) {
    val context = LocalContext.current
    val columnWrapper: @Composable ColumnScope.(@Composable ColumnScope.() -> Unit) -> Unit = if (scrollableText) {
        { content -> ScrollableColumnWithFadingEdge(modifier = Modifier.weight(1f, fill = false), content = content) }
    } else {
        { content -> Column(content = content) }
    }
    Column(modifier = modifier) {
        columnWrapper {
            Text("You've used RF Analyzer for $usageTimeStr - thank you! Every minute of that has been powered by late nights, debugging, and a lot of care. \uD83D\uDC9B",
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
            Text("This app is open-source so anyone can explore SDR without barriers and let the community benefit from a Google-free, privacy-respecting version.",
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
            Text("If you enjoy using it, please support the project - your donation helps keep it alive and accessible to everyone. \uD83D\uDE4F",
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
            Text("73, Dennis Mantz (DM4NTZ)",
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            )
        }
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp)) {
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://ko-fi.com/rfanalyzer".toUri())
                    context.startExternalActivity(intent)
                    postDonationAction()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF202020)),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .weight(1f)
                    .requiredHeight(40.dp)
                    .padding(horizontal = 2.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.kofi),
                    contentDescription = "Ko-fi Logo",
                    modifier = Modifier.size(70.dp)
                )
            }
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://liberapay.com/DM4NTZ/donate".toUri())
                    context.startExternalActivity(intent)
                    postDonationAction()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF6C915)),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .weight(1.3f)
                    .requiredHeight(40.dp)
                    .padding(horizontal = 2.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.liberapay),
                    contentDescription = "Liberapay Logo",
                    modifier = Modifier.size(85.dp)
                )
            }
            Button(
                onClick = {
                    val intent = Intent(Intent.ACTION_VIEW, "https://paypal.me/dennismantz".toUri())
                    context.startExternalActivity(intent)
                    postDonationAction()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier
                    .weight(1f)
                    .requiredHeight(40.dp)
                    .padding(horizontal = 2.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.paypal),
                    contentDescription = "PayPal Logo",
                    modifier = Modifier.size(78.dp)
                )
            }
        }
    }
}

@Composable
fun FossDonationDialog(
    usageTimeStr: String,
    dismissAction: () -> Unit,
) {
    Dialog(
        onDismissRequest = dismissAction,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        var donationButtonClicked by remember { mutableStateOf(false) }
        Box(
            modifier = Modifier
                .padding(10.dp)
                .background(MaterialTheme.colorScheme.background)
                .border(2.dp, MaterialTheme.colorScheme.tertiary, shape = MaterialTheme.shapes.medium)
        ) {
            IconButton(
                onClick = dismissAction,
                modifier = Modifier
                    .padding(16.dp)
                    .size(25.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f), shape = CircleShape)
                    .clip(CircleShape)
                    .align(Alignment.TopEnd)
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
            }
            Column(modifier = Modifier.padding(vertical = 16.dp, horizontal = 8.dp)) {
                Text(
                    "Support RF Analyzer",
                    fontSize = 22.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(all = 10.dp)
                        .align(Alignment.CenterHorizontally)
                )
                FossDonationOptionDialogContent(
                    usageTimeStr = usageTimeStr,
                    postDonationAction = { donationButtonClicked = true },
                    modifier = Modifier.weight(1f, fill = false),  // this shrinks the content so that the thank you button has space
                    scrollableText = true
                )
                if (donationButtonClicked) {
                    Button(
                        onClick = dismissAction,
                        shape = MaterialTheme.shapes.extraLarge,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Thank you! \uD83D\uDC9B  (click to dismiss)")
                    }
                }
            }
        }
    }
}

@Composable
fun EditStringDialog(
    title: String,
    label: String,
    initialValue: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initialValue) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text(label) }
            )
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BaseEditSheet(
    title: String,
    hasUnsavedChanges: (() -> Boolean)?,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    disableSaveButton: Boolean = false,
    overwriteSaveTitle: String? = null,
    showSaveButton: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    var showSheet by remember { mutableStateOf(true) }
    var showUnsavedDialog by remember { mutableStateOf(false) }

    fun onDissmissRequested() {
        if (hasUnsavedChanges != null && hasUnsavedChanges()) {
            showSheet = false
            showUnsavedDialog = true
        }
        else {
            showSheet = false
            onDismiss()
        }
    }

    if (showSheet) {
        ModalBottomSheet(onDismissRequest = ::onDissmissRequested) {
            Column(
                modifier = Modifier.fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    IconButton(onClick = ::onDissmissRequested) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                    Text(title,
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .padding(horizontal = 8.dp)
                            .weight(1f)
                            .align(Alignment.CenterVertically)
                    )
                    if (showSaveButton) {
                        Button(onClick = {
                                onSave()
                                onDismiss()
                            },
                            enabled = if (disableSaveButton) false else if (hasUnsavedChanges != null) hasUnsavedChanges() else true
                        ) { Text(overwriteSaveTitle ?: "Save") }
                    }
                }
                content()
            }
        }
    }

    if (showUnsavedDialog) {
        AlertDialog(
            onDismissRequest = {
                showUnsavedDialog = false
                showSheet = true
            },
            title = { Text("Unsaved Changes") },
            text = { Text("Discard your changes?") },
            confirmButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    showSheet = false
                    onDismiss()
                }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showUnsavedDialog = false
                    showSheet = true
                }) { Text("Cancel") }
            }
        )
    }
}

@Preview
@Composable
fun PreviewCountryPicker() {
    RFAnalyzerTheme {
        CountryPicker(selectedCountryCode = "US", onCountryCodeSelected = {})
    }
}

@Composable
fun CountryPicker(
    selectedCountryCode: String?,
    onCountryCodeSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var dialogOpen by remember { mutableStateOf(false) }
    val buttonText = if(selectedCountryCode==null || selectedCountryCode.isEmpty())
            "<select country>"
        else
            "${countryCodeToFlagEmoji(selectedCountryCode)} ${COUNTRY_CODE_TO_NAME[selectedCountryCode]} ($selectedCountryCode)"

    Row(modifier = modifier) {
        Button(onClick = { dialogOpen = true }) {
            Row {
                Text(buttonText)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        if (selectedCountryCode != null && selectedCountryCode.isNotEmpty()) {
            IconButton(onClick = { onCountryCodeSelected("") }) {
                Icon(Icons.Default.Clear, contentDescription = "Clear")
            }
        }
    }

    if (dialogOpen) {
        CountryPickerDialog(
            onDismiss = { dialogOpen = false },
            onSelect = {
                onCountryCodeSelected(it)
                dialogOpen = false
            }
        )
    }
}

@Composable
fun CountryPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }

    val filtered = COUNTRY_CODE_TO_NAME.keys.filter {
        val display = "${COUNTRY_CODE_TO_NAME[it]} ($it)"
        display.contains(query, ignoreCase = true)
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier.fillMaxSize(0.9f)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {

                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search Country") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Spacer(Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(filtered) { code ->
                        val display = "${countryCodeToFlagEmoji(code)} ${COUNTRY_CODE_TO_NAME[code]} ($code)"
                        ListItem(
                            headlineContent = { Text(display) },
                            modifier = Modifier
                                .clickable { onSelect(code) }
                        )
                    }
                }
            }
        }
    }
}


@Composable
fun ColorPickerRow(
    selectedColor: Color,
    onColorSelected: (Color) -> Unit,
    modifier: Modifier = Modifier
) {
    val presetColors = listOf(
        Color(0xFFEF5350), // red
        Color(0xFFAB47BC), // purple
        Color(0xFF5C6BC0), // indigo
        Color(0xFF29B6F6), // light blue
        Color(0xFF26A69A), // teal
        Color(0xFF66BB6A), // green
        Color(0xFFFFCA28), // amber
        Color(0xFFFFA726), // orange
        Color(0xFFAF4706), // dark orange
        Color(0xFF8D6E63), // brown
        Color(0xFF9E9E9E), // gray
        Color(0xFF000000), // black
        Color(0xFFFFFFFF), // white
    )

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Color",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(bottom = 6.dp)
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            presetColors.forEach { color ->
                val isSelected = color == selectedColor
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.onBackground else Color.Black.copy(alpha = 0.3f),
                            shape = CircleShape
                        )
                        .clickable { onColorSelected(color) }
                )
            }
        }
    }
}

/**
 * Dialog for choosing a receiver from the public KiwiSDR directory (kiwisdr.com/public/),
 * offering the same [entries] as a searchable List and a Map. Selecting an entry (List: tap a
 * card; Map: tap a marker, then confirm in the popup) invokes [onSelect] and the caller closes
 * the dialog and applies the choice.
 */
@Composable
fun KiwiSdrPickerDialog(
    entries: List<KiwiSdrPublicEntry>,
    isLoading: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit,
    onSelect: (KiwiSdrPublicEntry) -> Unit,
    cameraPosition: MapCameraPosition,
    onCameraPositionChanged: (MapCameraPosition) -> Unit,
    /** Where the map starts while [cameraPosition] was never moved, and the locator square. */
    home: MapHome? = null,
) {
    var query by remember { mutableStateOf("") }
    var selectedTabIndex by rememberSaveable { mutableStateOf(0) } // 0 = List, 1 = Map
    var pendingMapSelection by remember { mutableStateOf<KiwiSdrPublicEntry?>(null) }

    val filtered = remember(entries, query) {
        if (query.isBlank()) entries
        else entries.filter { entry ->
            entry.name.contains(query, ignoreCase = true) ||
                entry.host.contains(query, ignoreCase = true) ||
                (entry.loc?.contains(query, ignoreCase = true) == true)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(shape = RoundedCornerShape(16.dp), tonalElevation = 8.dp, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "Choose a Public KiwiSDR Receiver",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onRefresh, enabled = !isLoading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Now")
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                SecondaryTabRow(selectedTabIndex = selectedTabIndex) {
                    Tab(selected = selectedTabIndex == 0, onClick = { selectedTabIndex = 0 }, text = { Text("List") })
                    Tab(
                        selected = selectedTabIndex == 1,
                        onClick = { selectedTabIndex = 1; pendingMapSelection = null },
                        text = { Text("Map") }
                    )
                }

                if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                errorMessage?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(4.dp)
                    )
                }

                when (selectedTabIndex) {
                    0 -> KiwiSdrPublicListTab(
                        entries = filtered,
                        query = query,
                        onQueryChange = { query = it },
                        onSelect = onSelect,
                        modifier = Modifier.weight(1f),
                    )
                    1 -> KiwiSdrPublicMapTab(
                        entries = filtered,
                        pendingSelection = pendingMapSelection,
                        onMarkerTap = { pendingMapSelection = it },
                        onDeselect = { pendingMapSelection = null },
                        onConfirmSelection = { entry -> onSelect(entry); pendingMapSelection = null },
                        cameraPosition = cameraPosition,
                        onCameraPositionChanged = onCameraPositionChanged,
                        home = home,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun KiwiSdrPublicListTab(
    entries: List<KiwiSdrPublicEntry>,
    query: String,
    onQueryChange: (String) -> Unit,
    onSelect: (KiwiSdrPublicEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Search name, location, host") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "Clear")
                    }
                }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        LazyColumn(Modifier.fillMaxSize()) {
            if (entries.isEmpty()) {
                item { Text("No receivers found", modifier = Modifier.padding(16.dp)) }
            }
            items(entries, key = { it.id }) { entry ->
                KiwiSdrPublicEntryCard(entry, onClick = { onSelect(entry) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun KiwiSdrPublicEntryCard(entry: KiwiSdrPublicEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (entry.offline) PacketBadge(label = "OFFLINE", color = MaterialTheme.colorScheme.error)
            }
            Text("${entry.host}:${entry.port}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            entry.loc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.outline) }
            Row {
                Text("Users: ${entry.usersCurrent}/${entry.usersMax}", style = MaterialTheme.typography.labelSmall)
                entry.snrHf?.let {
                    Text("   SNR (HF): $it dB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun KiwiSdrPublicMapTab(
    entries: List<KiwiSdrPublicEntry>,
    pendingSelection: KiwiSdrPublicEntry?,
    onMarkerTap: (KiwiSdrPublicEntry) -> Unit,
    onDeselect: () -> Unit,
    onConfirmSelection: (KiwiSdrPublicEntry) -> Unit,
    cameraPosition: MapCameraPosition,
    onCameraPositionChanged: (MapCameraPosition) -> Unit,
    home: MapHome?,
    modifier: Modifier = Modifier,
) {
    val markers = remember(entries) {
        entries.mapNotNull { entry ->
            val lat = entry.lat
            val lon = entry.lon
            if (lat == null || lon == null) null
            else MapMarker(id = entry.id, lat = lat, lon = lon, title = entry.name, description = entry.loc ?: "")
        }
    }
    val selectedMarker = markers.find { it.id == pendingSelection?.id }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        MaplibreView(
            modifier = Modifier.fillMaxSize(),
            markers = markers,
            onMarkerClick = { marker -> entries.find { it.id == marker.id }?.let(onMarkerTap) },
            onDeselect = onDeselect,
            selectedMarker = selectedMarker,
            // the user's area until the map was moved once
            initialCameraPosition = cameraPosition.takeIf { it != MapCameraPosition.DEFAULT } ?: homeCamera(home, maxWidth.value, maxHeight.value),
            onCameraIdle = { lat, lon, zoom -> onCameraPositionChanged(MapCameraPosition(lat, lon, zoom)) },
            home = home,
        ) {
            pendingSelection?.let { entry ->
                ElevatedCard {
                    Column(Modifier.padding(12.dp).widthIn(max = 240.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.name, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            if (entry.offline) PacketBadge(label = "OFFLINE", color = MaterialTheme.colorScheme.error)
                        }
                        Text("${entry.host}:${entry.port}", style = MaterialTheme.typography.bodySmall)
                        entry.loc?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        Button(onClick = { onConfirmSelection(entry) }, modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
                            Icon(Icons.Default.Public, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Select this Receiver")
                        }
                    }
                }
            }
        }
    }
}
