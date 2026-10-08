package com.mantz_it.rfanalyzer.ui.composable

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.decoder.DecodedEvent
import com.mantz_it.rfanalyzer.decoder.FlexDecoder
import kotlinx.coroutines.delay

/**
 * <h1>RF Analyzer - Decoder Tab</h1>
 *
 * Module:      DecoderTab.kt
 * Description: Tab to enable and control the rtl_433 based decoding of the
 * received IQ stream and to inspect/log the decoded events.
 *
 * Copyright (C) 2026 RF Analyzer contributors
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Target decoder input rates offered in the UI (in Samples/s). */
val DECODER_TARGET_SAMPLE_RATES = listOf(250000, 500000, 1000000)

/** Unit conversion applied to decoded values (matches rtl_433's -C modes). */
enum class DecoderConversionMode(val displayName: String, val bridgeValue: Int) {
    NATIVE("Native (as decoded)", 0),
    SI("SI (metric)", 1),
    CUSTOMARY("US customary", 2),
}

data class DecoderTabActions(
    val onEnabledChanged: (Boolean) -> Unit,
    val onTargetSampleRateChanged: (Int) -> Unit,
    val onChannelizedChanged: (Boolean) -> Unit,
    val onConversionModeChanged: (DecoderConversionMode) -> Unit,
    val onAutoLevelChanged: (Boolean) -> Unit,
    val onMinSnrChanged: (Float) -> Unit,
    val onReportMetaChanged: (Boolean) -> Unit,
    val onLogToFileChanged: (Boolean) -> Unit,
    val onShowLogClicked: () -> Unit,
    val onSaveLogToFileClicked: (Uri) -> Unit,
    val onShareLogClicked: () -> Unit,
    /** Replaces the whole list of custom (flex) decoder specs. */
    val onFlexDecodersChanged: (List<String>) -> Unit,
)

@Composable
fun DecoderTabComposable(
    decoderEnabled: Boolean,
    decoderRunning: Boolean,
    analyzerRunning: Boolean,
    targetSampleRate: Int,
    channelized: Boolean,
    conversionMode: DecoderConversionMode,
    autoLevel: Boolean,
    minSnr: Float,
    reportMeta: Boolean,
    logToFile: Boolean,
    logFilePath: String,
    eventCount: Int,
    events: List<DecodedEvent>,
    flexDecoders: List<FlexDecoder>,
    decoderTabActions: DecoderTabActions,
) {
    val destinationFileChooser = rememberCreateFilePicker(
        suggestedFileName = "decoded_events.json",
        mimeType = "application/json",
        onAbort = { },
        onFileCreated = { destUri -> decoderTabActions.onSaveLogToFileClicked(destUri) })

    ScrollableColumnWithFadingEdge {
        OutlinedSwitch(
            label = "Decode with rtl_433",
            helpText = "Decode OOK/FSK sensor packets in the current band using rtl_433. " +
                    "Decoding only covers the bandwidth around the center frequency.",
            isChecked = decoderEnabled,
            onCheckedChange = decoderTabActions.onEnabledChanged,
            helpSubPath = "decoding.html"
        )

        if (decoderEnabled) {
            OutlinedListDropDown(
                label = "Decoder Input Rate",
                items = DECODER_TARGET_SAMPLE_RATES,
                selectedItem = targetSampleRate,
                getDisplayName = { "${it / 1000} kSps" },
                onSelectionChanged = decoderTabActions.onTargetSampleRateChanged,
                helpSubPath = "decoding.html"
            )

            OutlinedSwitch(
                label = "Channelized Decoding",
                helpText = "Filter to the demodulation channel before decoding so strong " +
                        "signals elsewhere in the band do not desensitise the decoder. Tune the " +
                        "demodulation channel to the signal and pick a demodulation mode whose " +
                        "channel width matches it (e.g. AM at 20 kHz).",
                isChecked = channelized,
                onCheckedChange = decoderTabActions.onChannelizedChanged,
                helpSubPath = "decoding.html"
            )

            OutlinedEnumDropDown(
                label = "Units",
                selectedEnum = conversionMode,
                enumClass = DecoderConversionMode::class,
                getDisplayName = { it.displayName },
                onSelectionChanged = decoderTabActions.onConversionModeChanged,
                helpSubPath = "decoding.html"
            )

            OutlinedSwitch(
                label = "Auto Level",
                helpText = "Automatically adapt the detection threshold to the noise floor. " +
                        "Recommended while roaming; disable for a fixed threshold.",
                isChecked = autoLevel,
                onCheckedChange = decoderTabActions.onAutoLevelChanged,
                helpSubPath = "decoding.html"
            )

            OutlinedSlider(
                label = "Minimum SNR",
                unit = "dB",
                unitInLabel = true,
                minValue = 3f,
                maxValue = 24f,
                decimalPlaces = 0,
                value = minSnr,
                onValueChanged = decoderTabActions.onMinSnrChanged,
                helpSubPath = "decoding.html"
            )

            OutlinedSwitch(
                label = "Report Signal Metadata",
                helpText = "Include modulation, frequency, RSSI, SNR and noise in each decoded event",
                isChecked = reportMeta,
                onCheckedChange = decoderTabActions.onReportMetaChanged,
                helpSubPath = "decoding.html"
            )

            FlexDecoderSection(
                decoders = flexDecoders,
                onChanged = decoderTabActions.onFlexDecodersChanged,
            )

            OutlinedBox(label = "Status") {
                Column(modifier = Modifier.padding(8.dp)) {
                    val status = when {
                        decoderRunning -> "Running"
                        analyzerRunning -> "Starting..."
                        else -> "Waiting for analyzer"
                    }
                    Text(status, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Text("Decoded events: $eventCount", fontSize = 12.sp)
                    if (events.isNotEmpty())
                        Text(
                            "Last: ${events.last().model}",
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                }
            }

            if (events.isNotEmpty()) {
                val latest = events.last()
                OutlinedBox(label = "Latest: ${latest.model}") {
                    Column(modifier = Modifier.padding(8.dp)) {
                        latest.fields.forEach { (key, value) ->
                            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
                                Text(
                                    key,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.width(96.dp)
                                )
                                Text(value, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            OutlinedSwitch(
                label = "Log Decoded Events",
                helpText = "Append every decoded event as a JSON line to a file in the app's storage",
                isChecked = logToFile,
                onCheckedChange = decoderTabActions.onLogToFileChanged,
                helpSubPath = "decoding.html"
            ) {
                Row(modifier = Modifier.fillMaxWidth().padding(4.dp)) {
                    val logAvailable = logFilePath.isNotEmpty()
                    Button(
                        onClick = decoderTabActions.onShowLogClicked,
                        enabled = logAvailable,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(end = 3.dp)
                    ) {
                        Icon(Icons.Default.Visibility, contentDescription = "View decoded log")
                    }
                    Button(
                        onClick = destinationFileChooser,
                        enabled = logAvailable,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.padding(horizontal = 3.dp).weight(1f)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = "Save decoded log")
                    }
                    Button(
                        onClick = decoderTabActions.onShareLogClicked,
                        enabled = logAvailable,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.weight(1f).padding(start = 3.dp)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Share decoded log")
                    }
                }
            }
        }
    }
}

/**
 * Small, unobtrusive status badge drawn over the spectrum view while decoding
 * is enabled. It briefly highlights when a packet was successfully decoded.
 */
@Composable
fun DecoderStatusBadge(
    enabled: Boolean,
    running: Boolean,
    lastModel: String,
    lastEventTimestamp: Long,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return

    var highlight by remember { mutableStateOf(false) }
    LaunchedEffect(lastEventTimestamp) {
        if (lastEventTimestamp > 0L) {
            highlight = true
            delay(2000)
            highlight = false
        }
    }

    val container = if (highlight) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)
    val content = if (highlight) MaterialTheme.colorScheme.onPrimary
                  else MaterialTheme.colorScheme.onSurfaceVariant

    Surface(
        color = container,
        contentColor = content,
        shape = RoundedCornerShape(50),
        modifier = modifier
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(if (running) Color(0xFF4CAF50) else Color(0xFFFFC107), CircleShape)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = if (highlight && lastModel.isNotEmpty()) lastModel else "rtl_433",
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One-click starting points for the flex editor. */
private val FLEX_PRESETS: List<Pair<String, FlexDecoder>> = listOf(
    // The 29-bit length is what keeps flex from emitting every noise pulse-train
    // as a 1-13 bit row; without it the decoder drowns in fragments.
    "OOK PWM 467/927 us (OOK remote)" to FlexDecoder(
        name = "OOK467", modulation = "OOK_PWM",
        shortWidth = 467f, longWidth = 927f, resetLimit = 2000f, gapLimit = 0f, tolerance = 0f,
        bits = 29,
    ),
    "OOK PWM 368/704 us (curtain/awning)" to FlexDecoder(
        name = "OOK368", modulation = "OOK_PWM",
        shortWidth = 368f, longWidth = 704f, resetLimit = 10000f, gapLimit = 10000f, syncWidth = 5628f,
    ),
    "OOK PPM 464/948 us" to FlexDecoder(
        name = "PPM", modulation = "OOK_PPM",
        shortWidth = 464f, longWidth = 948f, resetLimit = 2000f, gapLimit = 1200f,
    ),
    "OOK Manchester 467 us" to FlexDecoder(
        name = "MC", modulation = "OOK_MC_ZEROBIT",
        shortWidth = 467f, resetLimit = 942f,
    ),
)

/**
 * Section listing the user-defined flex decoders with add / edit / delete. The
 * list is stored as raw spec strings in [DecoderTabActions.onFlexDecodersChanged].
 */
@Composable
private fun FlexDecoderSection(
    decoders: List<FlexDecoder>,
    onChanged: (List<String>) -> Unit,
) {
    var showEditor by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf(-1) }
    var draft by remember { mutableStateOf(FlexDecoder()) }

    OutlinedBox(label = "Custom Decoders (flex)") {
        Column(modifier = Modifier.fillMaxWidth().padding(8.dp)) {
            Text(
                "Decode devices that have no built-in protocol by describing their " +
                    "modulation and pulse timings (rtl_433 -X). Several decoders can run at once.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp)
            )
            if (decoders.isEmpty()) {
                Text(
                    "No custom decoders.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            decoders.forEachIndexed { index, decoder ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = decoder.summary(),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = {
                        draft = decoder; editingIndex = index; showEditor = true
                    }) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit decoder")
                    }
                    IconButton(onClick = {
                        val list = decoders.toMutableList().also { it.removeAt(index) }
                        onChanged(list.map { it.toSpec() })
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete decoder")
                    }
                }
            }
            Button(
                onClick = {
                    draft = FLEX_PRESETS.first().second.copy(name = "flex${decoders.size + 1}")
                    editingIndex = -1
                    showEditor = true
                },
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add decoder")
            }
        }
    }

    if (showEditor) {
        FlexDecoderEditorDialog(
            initial = draft,
            onDismiss = { showEditor = false },
            onSave = { decoder ->
                val list = decoders.toMutableList()
                if (editingIndex in list.indices) list[editingIndex] = decoder else list.add(decoder)
                onChanged(list.map { it.toSpec() })
                showEditor = false
            },
        )
    }
}

@Composable
private fun FlexDecoderEditorDialog(
    initial: FlexDecoder,
    onDismiss: () -> Unit,
    onSave: (FlexDecoder) -> Unit,
) {
    var decoder by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Custom (flex) decoder") },
        text = {
            Column(modifier = Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                Text("Presets", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FLEX_PRESETS.forEach { (label, preset) ->
                    TextButton(onClick = {
                        decoder = preset.copy(name = decoder.name.ifBlank { preset.name })
                    }) {
                        Text(label, fontSize = 12.sp)
                    }
                }
                OutlinedTextField(
                    value = decoder.name,
                    onValueChange = { decoder = decoder.copy(name = it) },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                OutlinedListDropDown(
                    label = "Modulation",
                    items = FlexDecoder.MODULATIONS,
                    selectedItem = decoder.modulation.takeIf { it in FlexDecoder.MODULATIONS }
                        ?: FlexDecoder.MODULATIONS.first(),
                    getDisplayName = { it },
                    onSelectionChanged = { decoder = decoder.copy(modulation = it) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                FlexNumberField("Short width (us)", decoder.shortWidth) { decoder = decoder.copy(shortWidth = it) }
                FlexNumberField("Long width (us)", decoder.longWidth) { decoder = decoder.copy(longWidth = it) }
                FlexNumberField("Reset limit (us)", decoder.resetLimit) { decoder = decoder.copy(resetLimit = it) }
                FlexNumberField("Gap limit (us)", decoder.gapLimit) { decoder = decoder.copy(gapLimit = it) }
                FlexNumberField("Sync width (us)", decoder.syncWidth) { decoder = decoder.copy(syncWidth = it) }
                FlexNumberField("Tolerance (us)", decoder.tolerance) { decoder = decoder.copy(tolerance = it) }
                OutlinedTextField(
                    value = decoder.bits?.toString() ?: "",
                    onValueChange = { decoder = decoder.copy(bits = it.trim().toIntOrNull()) },
                    label = { Text("Bits (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                OutlinedTextField(
                    value = decoder.rawSpec,
                    onValueChange = { decoder = decoder.copy(rawSpec = it) },
                    label = { Text("Raw spec (advanced)") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
                Text(
                    "Spec: " + decoder.toSpec(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(decoder) }, enabled = decoder.isValid()) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun FlexNumberField(label: String, value: Float?, onValueChanged: (Float?) -> Unit) {
    OutlinedTextField(
        value = value?.let { FlexDecoder.formatNumber(it) } ?: "",
        onValueChange = { text ->
            val trimmed = text.trim()
            onValueChanged(if (trimmed.isEmpty()) null else trimmed.toFloatOrNull())
        },
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
    )
}
