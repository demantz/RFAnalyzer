package com.mantz_it.rfanalyzer.ui.tabs

import com.mantz_it.rfanalyzer.digiext.rds.RdsPtyTable
import com.mantz_it.rfanalyzer.digiext.DigitalAction
import com.mantz_it.rfanalyzer.digiext.DigitalFeatures
import com.mantz_it.rfanalyzer.digiext.DigitalNotice
import com.mantz_it.rfanalyzer.ui.composable.DigitalStatusNotice
import android.content.Context
import androidx.compose.animation.*
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.mantz_it.rfanalyzer.ui.composable.OverloadChip
import com.mantz_it.rfanalyzer.analyzer.Demodulator
import com.mantz_it.rfanalyzer.database.Band
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreChannelsDialog
import com.mantz_it.rfanalyzer.database.Station
import com.mantz_it.rfanalyzer.digiext.toExportJson
import com.mantz_it.rfanalyzer.ui.ClockSyncState
import com.mantz_it.rfanalyzer_ipc.DigitalDemodParams
import com.mantz_it.rfanalyzer_ipc.Ft8TimingMode
import com.mantz_it.rfanalyzer_ipc.RttyPolarity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import com.mantz_it.rfanalyzer.ui.composable.bookmarks.BookmarkFavoriteDialog
import com.mantz_it.rfanalyzer.ui.composable.DEFAULT_MIN_BOX_HEIGHT
import com.mantz_it.rfanalyzer.ui.composable.LocalShowHelp
import com.mantz_it.rfanalyzer.ui.composable.FrequencyChooser
import com.mantz_it.rfanalyzer.ui.composable.OutlinedBox
import com.mantz_it.rfanalyzer.ui.composable.OutlinedEnumDropDown
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSlider
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSteppedSlider
import com.mantz_it.rfanalyzer.ui.composable.OutlinedSwitch
import com.mantz_it.rfanalyzer.ui.composable.ScrollableColumnWithFadingEdge
import com.mantz_it.rfanalyzer.ui.composable.reverseSaturationFunction
import com.mantz_it.rfanalyzer.ui.composable.saturationFunction
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketBadge
import com.mantz_it.rfanalyzer.ui.composable.OverlayIcon
import com.mantz_it.rfanalyzer_ipc.LoraIqMode
import com.mantz_it.rfanalyzer_ipc.LoraSpreadingFactors
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.FilterChip
import com.mantz_it.rfanalyzer.database.StationOverlay

/**
 * <h1>RF Analyzer - Demodulation Tab</h1>
 *
 * Module:      DemodulationTab.kt
 * Description: A Tab in the Control Drawer. Contains all settings related to signal demodulation.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

enum class DemodulationMode(
    val displayName: String,
    val minChannelWidth: Int,
    val maxChannelWidth: Int,
    val channelWidthSteps: List<Int>?,
    val defaultChannelWidth: Int,
    val tuneStepDistance: Int,
    val isDigitalExtension: Boolean,
    val basebandSamplerate: Int,
    val fullName: String,
    val description: String,
    val tuneStepOptions: List<Int>,
) {
    OFF("OFF", 0, 50000, null, 0, 0, false, Demodulator.AUDIO_RATE,  // dummy channel width values
        fullName = "",
        description = "Demodulation disabled — spectrum/waterfall display only",
        tuneStepOptions = listOf(1000)),
    AM("AM", 3000, 20000, null, 10000, 1000, false, 2*Demodulator.AUDIO_RATE,
        fullName = "Amplitude Modulation",
        description = "aviation, CB radio, shortwave broadcast",
        tuneStepOptions = listOf(100, 1000, 5000, 9000, 10000)),
    NFM("FM (narrow)", 5000, 25000, null, 12500, 1000, false, 2*Demodulator.AUDIO_RATE,
        fullName = "Frequency Modulation",
        description = "voice — PMR, ham repeaters",
        tuneStepOptions = listOf(100, 250, 500, 1000, 6250, 12500, 25000)),
    WFM("FM (wide)", 30000, 95000, null, 95000, 100000, false, 4*Demodulator.AUDIO_RATE,
        fullName = "Frequency Modulation",
        description = "commercial FM broadcast radio",
        tuneStepOptions = listOf(10000, 50000, 100000, 200000)),
    LSB("LSB", 1500, 5000, null, 2800, 100, false, 2*Demodulator.AUDIO_RATE,
        fullName = "Lower Sideband",
        description = "HF voice below ~10 MHz",
        tuneStepOptions = listOf(10, 50, 100, 500, 1000)),
    USB("USB", 1500, 5000, null, 2800, 100, false, 2*Demodulator.AUDIO_RATE,
        fullName = "Upper Sideband",
        description = "HF/VHF voice above ~10 MHz",
        tuneStepOptions = listOf(10, 50, 100, 500, 1000)),
    CW("CW", 100, 600, null, 200, 50, false, Demodulator.AUDIO_RATE,
        fullName = "Continuous Wave",
        description = "Morse code telegraphy",
        tuneStepOptions = listOf(10, 20, 50, 100, 500)),
    RTTY("RTTY", 85, 850, listOf(85, 170, 450, 850), 450, 50, true, Demodulator.AUDIO_RATE,
        fullName = "Radioteletype",
        description = "narrow-shift FSK text",
        tuneStepOptions = listOf(10, 50, 100, 170, 500)),
    // The channel is the receiver's capture range: the PSK signal nearest its center within it is decoded.
    PSK31("PSK31", 50, 200, listOf(50, 100, 200), 200, 50, true, Demodulator.AUDIO_RATE,
        fullName = "Phase-Shift Keying",
        description = "keyboard-to-keyboard chat",
        tuneStepOptions = listOf(1, 5, 10, 31, 50, 100)),
    // The channel is the receiver's capture range: R09 transmitters within ±10 kHz are decoded (rough tuning).
    R09("R09", 20000, 20000, listOf(20000), 20000, 200, true, Demodulator.AUDIO_RATE,
        fullName = "",
        description = "tram & bus priority at traffic lights",
        tuneStepOptions = listOf(100, 200, 1000, 5000, 12500)),
    FT8("FT8", 3000, 3000, listOf(3000), 3000, 50, true, Demodulator.AUDIO_RATE/4,
        fullName = "weak-signal digital mode",
        description = "long-distance ham contacts",
        tuneStepOptions = listOf(10, 50, 100, 500, 1000)),
    AX25("AX.25", 300, 25000, listOf(300, 12500, 25000), 12500, 500, true, Demodulator.AUDIO_RATE,
        fullName = "Amateur X.25 packet radio",
        description = "position beacons & messaging",
        tuneStepOptions = listOf(500, 1000, 5000, 12500, 25000)),
    ADSB("ADS-B", 1_000_000, 1_000_000, listOf(1_000_000), 1_000_000, 1000, true, 2_000_000,
        fullName = "Automatic Dependent Surveillance–Broadcast",
        description = "aircraft position broadcasts",
        tuneStepOptions = listOf(1000, 10000, 100000, 1000000)),
    POCSAG("POCSAG", 12500, 25000, listOf(12500, 12500, 12500, 25000), 12500, 500, true, Demodulator.AUDIO_RATE,
        fullName = "Post Office Code Standardisation Advisory Group",
        description = "numeric/alphanumeric paging",
        tuneStepOptions = listOf(500, 1000, 6250, 12500, 25000)),
    LORA("LoRa", 31250, 500000, listOf(31250, 62500, 125000, 250000, 500000), 250000, 1000, true, 500000,
        fullName = "Long Range",
        description = "MeshCore, Meshtastic, LoRaWAN, LoRa-APRS",
        tuneStepOptions = listOf(1000, 10000, 100000, 200000, 500000)),
}
fun DemodulationMode.normalizeChannelWidth(width: Int): Int {
    val snappedToStep = channelWidthSteps?.minByOrNull { step ->
        kotlin.math.abs(step - width)
    } ?: width
    val clamped = snappedToStep.coerceIn(minChannelWidth, maxChannelWidth)
    return clamped
}

// TODO: flip to false / remove once the "Digital" tab has shipped for one release cycle.
private const val DIGITAL_TAB_IS_NEW = true

data class DemodulationTabActions(
    val onDemodulationModeChanged: (DemodulationMode) -> Unit,
    val onDigitalTabClicked: (Context) -> Unit,
    val onWfmStereoAndRdsEnabledChanged: (Boolean) -> Unit,
    /** The button of a DigitalStatusNotice (install, update, retry). */
    val onDigitalAction: (DigitalAction) -> Unit,
    val onWfmRdsPtyTableChanged: (RdsPtyTable) -> Unit,
    val onChannelFrequencyChanged: (Long) -> Unit,
    val onTunerWheelDelta: (Float) -> Unit,
    val onChannelWidthChanged: (Int) -> Unit,
    val onTuneStepHzChanged: (Int) -> Unit,
    val onPocsagBaudRateChanged: (Int) -> Unit,
    val onMeshCoreCustomChannelsChanged: (List<String>) -> Unit,
    val onAx25VariantChanged: (Int) -> Unit,
    val onRttyBaudRateChanged: (Float) -> Unit,
    val onRttyFrequencyShiftChanged: (Float) -> Unit,
    val onRttyPolarityChanged: (RttyPolarity) -> Unit,
    val onPsk31BaudRateChanged: (Float) -> Unit,
    val onR09BaudRateChanged: (Int) -> Unit,
    val onLoraSpreadingFactorsChanged: (Int) -> Unit,
    val onLoraIqModeChanged: (LoraIqMode) -> Unit,
    /** ✕ on the station overlay's notice: back to the user's own settings. */
    val onStationOverlayDismissed: () -> Unit = {},
    val onFt8TimingModeChanged: (Ft8TimingMode) -> Unit,
    val onFt8ManualOffsetChanged: (Int) -> Unit,
    val onSyncClockClicked: () -> Unit,
    val onSquelchEnabledChanged: (Boolean) -> Unit,
    val onSquelchChanged: (Float) -> Unit,
    val onZoomChanged: (Float) -> Unit,
    val onAudioMuteClicked: () -> Unit,
    val onAudioVolumeLevelChanged: (Float) -> Unit,
    val onOpenBookmarksClicked: () -> Unit,
    val onAddStationBookmarkClicked: () -> Unit,
    val onAddBandBookmarkClicked: () -> Unit,
    val onTuneToStation: (Station) -> Unit,
    val onViewBand: (Band) -> Unit,
    // The digital-mode cards' menu actions (Share as JSON, Track on map, open a URL); this tab
    // shows no cards and doesn't call them.
    val onShareJsonExportClicked: (json: String, filenamePrefix: String) -> Unit,
    val onTrackOnMapClicked: (markerId: String, lat: Double, lon: Double) -> Unit,
    val onOpenExternalUrlClicked: (url: String) -> Unit,
    /** "?" on the overload chip - see OverloadChip. */
    val onOverloadHelpClicked: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DemodulationTabComposable(
    demodulationEnabled: Boolean,
    demodulationMode: DemodulationMode,
    /** Why the selected mode's digital part isn't received, if it isn't - see DigitalAvailability.kt. */
    digitalNotice: DigitalNotice?,
    wfmStereoAndRdsEnabled: Boolean,
    wfmRdsPtyTable: RdsPtyTable,
    /** What [RdsPtyTable.AUTO] picks for the user's country. */
    wfmRdsAutoIsRbds: Boolean,
    channelFrequency: Long,
    channelWidth: Int,
    tuneStepHz: Int,
    pocsagBaudRate: Int,
    meshCoreCustomChannels: List<String>,
    ax25Variant: Int,
    rttyBaudRate: Float,
    rttyFrequencyShift: Float,
    rttyPolarity: RttyPolarity,
    psk31BaudRate: Float,
    r09BaudRate: Int,
    loraSpreadingFactors: Int,
    loraIqMode: LoraIqMode,
    stationOverlay: StationOverlay? = null,
    ft8TimingMode: Ft8TimingMode,
    ft8ManualOffsetMs: Int,
    clockSyncState: ClockSyncState,
    squelchEnabled: Boolean,
    minSquelch: Float,
    maxSquelch: Float,
    squelch: Float,
    viewportZoom: Float,
    audioVolumeLevel: Float,
    audioMuted: Boolean,
    stationFavorites: List<Station>,
    bandFavorites: List<Band>,
    demodulationTabActions: DemodulationTabActions
) {
    var showVolumeSlider by remember { mutableStateOf(false) }
    var volumeSliderTouched by remember { mutableStateOf(false) }
    var showBookmarkDialog by remember { mutableStateOf(false) }
    var showStationFavorites by remember { mutableStateOf(true) }
    var showModePickerDialog by remember { mutableStateOf(false) }
    var modePickerShowDigitalTab by remember { mutableStateOf(demodulationMode.isDigitalExtension) }
    var lastActiveMode by remember { mutableStateOf(if (demodulationMode != DemodulationMode.OFF) demodulationMode else DemodulationMode.NFM) }
    LaunchedEffect(demodulationMode) {
        if (demodulationMode != DemodulationMode.OFF) lastActiveMode = demodulationMode
    }

    Column(modifier = Modifier.fillMaxSize()) {
        OverloadChip(
            onHelpClicked = demodulationTabActions.onOverloadHelpClicked,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        digitalNotice?.let {
            DigitalStatusNotice(it, demodulationTabActions.onDigitalAction, Modifier.fillMaxWidth().padding(bottom = 6.dp))
        }
        ScrollableColumnWithFadingEdge {
            Row {
                OutlinedBox(label = "Audio Volume",
                    helpSubPath = "demodulation.html#audio-controls",
                    modifier = Modifier.padding(end = 6.dp)
                ) {
                    Row(modifier = Modifier.align(Alignment.Center)) {
                        IconButton(onClick = demodulationTabActions.onAudioMuteClicked) {
                            Icon(
                                imageVector = if (audioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                                tint = MaterialTheme.colorScheme.primary,
                                contentDescription = "Mute toggle"
                            )
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable {
                                    showVolumeSlider = !showVolumeSlider
                                    volumeSliderTouched = false
                                }
                                .padding(6.dp)
                                .align(Alignment.CenterVertically)
                        ) {
                            Text(
                                text = "${(audioVolumeLevel * 100).toInt()}%",
                                fontSize = 20.sp,
                                textAlign = TextAlign.End,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier.width(50.dp)
                            )
                            Icon(
                                imageVector = Icons.Filled.ArrowDropDown,
                                contentDescription = "Show volume slider",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                        AnimatedVisibility(
                            visible = showVolumeSlider,
                            enter = fadeIn() + expandHorizontally(),
                            exit = fadeOut() + shrinkHorizontally()
                        ) {
                            Slider(
                                value = audioVolumeLevel,
                                onValueChange = {
                                    volumeSliderTouched = true  // user is interacting, prevent auto-dismiss
                                    demodulationTabActions.onAudioVolumeLevelChanged(it)
                                },
                                onValueChangeFinished = { showVolumeSlider = false },
                                valueRange = 0f..1f,
                                modifier = Modifier.padding(end = 10.dp).fillMaxWidth()
                            )
                        }
                        // hide volume slider after 3 seconds of not being used:
                        LaunchedEffect(showVolumeSlider) {
                            if (showVolumeSlider) {
                                delay(3000)
                                if (!volumeSliderTouched) {
                                    showVolumeSlider = false
                                }
                            }
                        }

                    }
                }
                AnimatedVisibility(
                    visible = !showVolumeSlider,
                    enter = fadeIn() + expandHorizontally(),
                    exit = fadeOut() + shrinkHorizontally(),
                    modifier = Modifier.weight(1f)
                ) {
                    Row {
                        OutlinedBox(
                            label = "Demodulation Mode",
                            helpSubPath = "demodulation.html#demodulation-mode",
                            onClick = {
                                modePickerShowDigitalTab = demodulationMode.isDigitalExtension
                                showModePickerDialog = true
                            },
                            modifier = Modifier
                                .height(DEFAULT_MIN_BOX_HEIGHT)
                                .weight(1f)
                        ) {
                            Text(
                                text = demodulationMode.displayName,
                                fontSize = 18.sp,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                }
            }
            if (demodulationEnabled) {
                Row {
                    FrequencyChooser(
                        label = "Channel Frequency",
                        unit = "Hz",
                        currentFrequency = channelFrequency,
                        onFrequencyChanged = demodulationTabActions.onChannelFrequencyChanged,
                        helpSubPath = "demodulation.html#channel-frequency",
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { showBookmarkDialog = true },
                        shape = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
                        modifier = Modifier
                            .padding(start = 4.dp, end = 2.dp, top = 12.dp)
                            .height(58.dp)
                            .align(Alignment.CenterVertically)
                    ) { OverlayIcon(Icons.Default.MenuBook, Icons.Default.Favorite, contentDescription = "Station Favorites", mainIconSize = 32.dp, badgeIconSize = 12.dp, badgePadding = 3.dp, badgeTint = Color.Red, badgeAlignment = Alignment.CenterStart) }
                }
                TunerWheel(
                    onRotationEvent = demodulationTabActions.onTunerWheelDelta,
                    sensitivity = if (tuneStepHz > 1000) 0.1f else 0.4f,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedBox(label = "Zoom", helpSubPath = "demodulation.html#zoom-and-bandwidth") {
                    Slider(
                        value = reverseSaturationFunction(y = viewportZoom, a = 3.5f, k = 0.15f),
                        onValueChange = { demodulationTabActions.onZoomChanged(saturationFunction(x = it, a = 3.5f, k = 0.15f)) },
                        valueRange = when (demodulationMode) {
                            DemodulationMode.OFF -> 0f..0.995f
                            DemodulationMode.AM, DemodulationMode.NFM, DemodulationMode.AX25, DemodulationMode.POCSAG -> 0f..0.7f
                            DemodulationMode.WFM, DemodulationMode.LORA -> 0f..0.45f
                            DemodulationMode.ADSB -> 0f..0.45f
                            DemodulationMode.LSB, DemodulationMode.USB -> 0f..0.95f
                            DemodulationMode.CW, DemodulationMode.RTTY, DemodulationMode.PSK31, DemodulationMode.R09, DemodulationMode.FT8 -> 0f..0.997f
                        },
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                }
                val tuneStepOptions = demodulationMode.tuneStepOptions
                OutlinedSteppedSlider(
                    label = "Tune Step",
                    unit = "Hz",
                    helpSubPath = "demodulation.html#channel-frequency",
                    steps = tuneStepOptions,
                    selectedStepIndex = tuneStepOptions.indexOf(tuneStepHz).let {
                        if (it >= 0) it else tuneStepOptions.indexOf(demodulationMode.tuneStepDistance).coerceAtLeast(0)
                    },
                    onSelectedStepIndexChanged = { value -> demodulationTabActions.onTuneStepHzChanged(tuneStepOptions[value]) },
                )

                if (stationOverlay != null && stationOverlay.mode == demodulationMode)
                    StationOverlayNotice(stationOverlay, demodulationTabActions.onStationOverlayDismissed)

                // Squelch and channel bandwidth of the analog modes (the app's own demodulators).
                val squelchAndBandwidth: @Composable () -> Unit = {
                    OutlinedSwitch(
                        label = "Squelch",
                        helpText = "Mutes the audio while no signal is in the channel. The squelch opens when the channel is the set level above the noise.",
                        isChecked = squelchEnabled,
                        onCheckedChange = demodulationTabActions.onSquelchEnabledChanged,
                        helpSubPath = "demodulation.html#squelch-control",
                    ) {
                        OutlinedSlider(
                            label = "Squelch (above the noise)",
                            unit = "dB",
                            unitInLabel = false,
                            minValue = minSquelch,
                            maxValue = maxSquelch,
                            value = squelch,
                            onValueChanged = { value -> demodulationTabActions.onSquelchChanged(value) },
                            showOutline = false
                        )
                    }
                    OutlinedSlider(
                        label = "Channel Bandwidth",
                        unit = "kHz",
                        minValue = demodulationMode.minChannelWidth.toFloat() / 1000,
                        maxValue = demodulationMode.maxChannelWidth.toFloat() / 1000,
                        value = channelWidth.toFloat() / 1000,
                        decimalPlaces = 1,
                        onValueChanged = { value -> demodulationTabActions.onChannelWidthChanged((value * 1000).roundToInt()) },
                        helpSubPath = "demodulation.html#zoom-and-bandwidth",
                    )
                }
                when (demodulationMode) {
                    DemodulationMode.FT8 -> {
                        OutlinedBox(label = "FT8 Timing", helpSubPath = "ft8.html#timing") {
                            Column {
                                val modes = listOf(
                                    Ft8TimingMode.AUTOMATIC to "Automatic",
                                    Ft8TimingMode.INTERNET_TIME to "Internet time",
                                    Ft8TimingMode.MANUAL to "Manual",
                                )
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    modes.forEachIndexed { index, (mode, label) ->
                                        SegmentedButton(
                                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                                            onClick = { demodulationTabActions.onFt8TimingModeChanged(mode) },
                                            selected = ft8TimingMode == mode,
                                        ) { Text(label, maxLines = 1) }
                                    }
                                }
                                when (ft8TimingMode) {
                                    Ft8TimingMode.AUTOMATIC -> Text(
                                        text = "Found from the signals themselves - works with any clock error. The overlay shows where transmissions start.",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                                    )
                                    Ft8TimingMode.INTERNET_TIME -> Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = when (clockSyncState) {
                                                is ClockSyncState.Success -> "Clock corrected by ${clockSyncState.offsetMs} ms (${clockSyncState.server})"
                                                is ClockSyncState.Error -> "Time sync failed: ${clockSyncState.message}"
                                                ClockSyncState.InProgress -> "Syncing the clock…"
                                                ClockSyncState.Idle -> "Clock not synced yet"
                                            },
                                            color = if (clockSyncState is ClockSyncState.Error) MaterialTheme.colorScheme.error else Color.Unspecified,
                                            style = MaterialTheme.typography.bodySmall,
                                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                                        )
                                        if (clockSyncState is ClockSyncState.InProgress) {
                                            CircularProgressIndicator(modifier = Modifier.padding(horizontal = 12.dp))
                                        } else {
                                            IconButton(onClick = demodulationTabActions.onSyncClockClicked) {
                                                Icon(Icons.Default.Autorenew, contentDescription = "Sync the clock with network time")
                                            }
                                        }
                                    }
                                    Ft8TimingMode.MANUAL -> OutlinedSlider(
                                        label = "Time Offset",
                                        unit = "ms",
                                        minValue = -7500f,
                                        maxValue = 7500f,
                                        value = ft8ManualOffsetMs.toFloat(),
                                        decimalPlaces = 0,
                                        showOutline = false,
                                        unitInLabel = false,   // no outline, so no label to hold it
                                        onValueChanged = { value -> demodulationTabActions.onFt8ManualOffsetChanged(value.roundToInt()) },
                                    )
                                }
                            }
                        }
                    }
                    DemodulationMode.AX25 -> {
                        val ax25VariantSteps = listOf(300, 1200, 9600)
                        OutlinedSteppedSlider(
                            label = "Baud Rate",
                            steps = ax25VariantSteps,
                            selectedStepIndex = ax25VariantSteps.indexOf(ax25Variant).let { if (it >= 0) it else 1 },
                            formatValue = { value -> when (value) {
                                300 -> "300 (HF)"
                                1200 -> "1200 (VHF)"
                                9600 -> "9600 (G3RUH)"
                                else -> "$value"
                            } },
                            unit = "baud",
                                onSelectedStepIndexChanged = { value -> demodulationTabActions.onAx25VariantChanged(ax25VariantSteps[value]) },
                            helpSubPath = "ax25.html#controls-in-the-demodulation-tab",
                        )
                    }
                    DemodulationMode.POCSAG -> {
                        // 0 is "Auto": all three rates are decoded in parallel (PocsagPhy.baudRate
                        // says which one a message came at). The persisted Setting<Int> and the step
                        // list need a concrete value; AnalyzerService maps 0 to the null of
                        // DigitalDemodParams.Pocsag.
                        val pocsagBaudRateSteps = listOf(0, 512, 1200, 2400)
                        OutlinedSteppedSlider(
                            label = "Baud Rate",
                            steps = pocsagBaudRateSteps,
                            selectedStepIndex = pocsagBaudRateSteps.indexOf(pocsagBaudRate).let { if (it >= 0) it else 0 },
                            formatValue = { value -> if (value == 0) "Auto" else "$value" },
                            unit = "bit/s",
                                onSelectedStepIndexChanged = { value -> demodulationTabActions.onPocsagBaudRateChanged(pocsagBaudRateSteps[value]) },
                            helpSubPath = "pocsag.html#controls-in-the-demodulation-tab",
                        )
                    }
                    DemodulationMode.LORA -> {
                        val loraChannelWidthSteps = DemodulationMode.LORA.channelWidthSteps!!
                        OutlinedSteppedSlider(
                            label = "Channel Bandwidth",
                            unit = "Hz",
                            steps = loraChannelWidthSteps,
                            selectedStepIndex = if (loraChannelWidthSteps.indexOf(channelWidth) >= 0) loraChannelWidthSteps.indexOf(channelWidth) else 0,
                                onSelectedStepIndexChanged = { value -> demodulationTabActions.onChannelWidthChanged(loraChannelWidthSteps[value]) },
                            helpSubPath = "lora.html#controls-in-the-demodulation-tab",
                        )
                        LoraSettings(loraSpreadingFactors, loraIqMode, demodulationTabActions)
                        var showMeshCoreChannelsDialog by remember { mutableStateOf(false) }
                        Button(
                            onClick = { showMeshCoreChannelsDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Edit MeshCore Hashtag Channels")
                        }
                        if (showMeshCoreChannelsDialog) {
                            MeshCoreChannelsDialog(
                                customChannels = meshCoreCustomChannels,
                                onChannelsChanged = demodulationTabActions.onMeshCoreCustomChannelsChanged,
                                onDismiss = { showMeshCoreChannelsDialog = false },
                            )
                        }
                    }
                    DemodulationMode.RTTY -> {
                        val auto = DigitalDemodParams.Rtty.AUTO
                        val rttyBaudRateSteps = listOf(auto, 45.45f, 50f, 75f, 100f)
                        OutlinedSteppedSlider(
                            label = "Baud Rate",
                            steps = rttyBaudRateSteps,
                            selectedStepIndex = rttyBaudRateSteps.indexOf(rttyBaudRate).let { if (it >= 0) it else 0 },
                            formatValue = { value -> if (value == auto) "Auto" else if (value == 45.45f) "45.45" else "${value.toInt()}" },
                            unit = "baud",
                            helpSubPath = "rtty.html#controls-in-the-demodulation-tab",
                            onSelectedStepIndexChanged = { value -> demodulationTabActions.onRttyBaudRateChanged(rttyBaudRateSteps[value]) },
                        )
                        val rttyFrequencyShiftSteps = listOf(auto, 85f, 170f, 450f, 850f)
                        OutlinedSteppedSlider(
                            label = "Frequency Shift",
                            steps = rttyFrequencyShiftSteps,
                            selectedStepIndex = rttyFrequencyShiftSteps.indexOf(rttyFrequencyShift).let { if (it >= 0) it else 0 },
                            formatValue = { value -> if (value == auto) "Auto" else "${value.toInt()}" },
                            unit = "Hz",
                            helpSubPath = "rtty.html#controls-in-the-demodulation-tab",
                            onSelectedStepIndexChanged = { value -> demodulationTabActions.onRttyFrequencyShiftChanged(rttyFrequencyShiftSteps[value]) },
                        )
                        OutlinedBox(label = "Polarity", helpSubPath = "rtty.html#polarity") {
                            Column {
                                val polarities = listOf(
                                    RttyPolarity.AUTO to "Auto",
                                    RttyPolarity.NORMAL to "Normal",
                                    RttyPolarity.REVERSED to "Reversed",
                                )
                                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                    polarities.forEachIndexed { index, (polarity, label) ->
                                        SegmentedButton(
                                            shape = SegmentedButtonDefaults.itemShape(index, polarities.size),
                                            onClick = { demodulationTabActions.onRttyPolarityChanged(polarity) },
                                            selected = rttyPolarity == polarity,
                                        ) { Text(label, maxLines = 1) }
                                    }
                                }
                                Text(
                                    text = when (rttyPolarity) {
                                        RttyPolarity.AUTO -> "Detected from the signal. Auto baud rate and shift are detected too; the tones are found within ±400 Hz of the channel."
                                        RttyPolarity.NORMAL -> "Mark is the higher frequency (amateur convention)."
                                        RttyPolarity.REVERSED -> "Mark is the lower frequency (e.g. DWD weather broadcasts)."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    DemodulationMode.WFM -> {
                        // Stereo and RDS come from the Digital Extension (DigitalFeatures).
                        if (DigitalFeatures.shown) {
                            OutlinedSwitch(
                                label = "Stereo & RDS",
                                helpText = "If enabled, use the digital extension to demodulate stereo audio and RDS data.",
                                isChecked = wfmStereoAndRdsEnabled,
                                onCheckedChange = demodulationTabActions.onWfmStereoAndRdsEnabledChanged,
                                helpSubPath = "demodulation.html#stereo-rds",
                            )
                            OutlinedEnumDropDown(
                                label = "RDS Programme Types",
                                enumClass = RdsPtyTable::class,
                                selectedEnum = wfmRdsPtyTable,
                                getDisplayName = {
                                    if (it == RdsPtyTable.AUTO) "Automatic (${if (wfmRdsAutoIsRbds) "North America" else "Europe"})" else it.displayName
                                },
                                onSelectionChanged = demodulationTabActions.onWfmRdsPtyTableChanged,
                                fontSize = 14.sp,
                                helpSubPath = "demodulation.html#stereo-rds",
                            )
                        }
                        squelchAndBandwidth()
                        if (wfmStereoAndRdsEnabled && DigitalFeatures.shown) Text(
                            // With Stereo & RDS the extension gets the unfiltered signal (Demodulator.kt).
                            text = "The bandwidth applies to mono audio only: with Stereo & RDS the Digital Extension receives the whole signal.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                    DemodulationMode.PSK31 -> {
                        val auto = DigitalDemodParams.Psk31.AUTO
                        val psk31BaudRateSteps = listOf(auto, 31.25f, 62.5f, 125f)
                        OutlinedSteppedSlider(
                            label = "Symbol Rate",
                            steps = psk31BaudRateSteps,
                            selectedStepIndex = psk31BaudRateSteps.indexOf(psk31BaudRate).let { if (it >= 0) it else 0 },
                            formatValue = { value -> when (value) { auto -> "Auto"; 31.25f -> "31 (PSK31)"; 62.5f -> "63 (PSK63)"; else -> "125 (PSK125)" } },
                            unit = "baud",
                            helpSubPath = "psk31.html#controls-in-the-demodulation-tab",
                            onSelectedStepIndexChanged = { value -> demodulationTabActions.onPsk31BaudRateChanged(psk31BaudRateSteps[value]) },
                        )
                        val psk31CaptureSteps = DemodulationMode.PSK31.channelWidthSteps!!
                        OutlinedSteppedSlider(
                            label = "Capture Width",
                            unit = "Hz",
                            steps = psk31CaptureSteps,
                            selectedStepIndex = psk31CaptureSteps.indexOf(channelWidth).let { if (it >= 0) it else psk31CaptureSteps.lastIndex },
                            onSelectedStepIndexChanged = { value -> demodulationTabActions.onChannelWidthChanged(psk31CaptureSteps[value]) },
                            helpSubPath = "psk31.html#controls-in-the-demodulation-tab",
                        )
                        Text(
                            text = "The PSK signal nearest the channel center within the capture width is decoded. Narrow it to keep a close neighbour out.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                    DemodulationMode.R09 -> {
                        val auto = DigitalDemodParams.R09.AUTO
                        val r09BaudRateSteps = listOf(auto, 2400, 1200)
                        OutlinedSteppedSlider(
                            label = "Bit Rate",
                            steps = r09BaudRateSteps,
                            selectedStepIndex = r09BaudRateSteps.indexOf(r09BaudRate).let { if (it >= 0) it else 0 },
                            formatValue = { value -> if (value == auto) "Auto" else "$value" },
                            unit = "bit/s",
                            helpSubPath = "r09.html#controls-in-the-demodulation-tab",
                            onSelectedStepIndexChanged = { value -> demodulationTabActions.onR09BaudRateChanged(r09BaudRateSteps[value]) },
                        )
                        Text(
                            text = "Transmitters within 10 kHz of the channel frequency are decoded. Auto decodes both bit rates.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                        )
                    }
                    DemodulationMode.ADSB -> { /* no extra mode-specific settings here */ }
                    else -> squelchAndBandwidth()
                }
            }
        }
    }

    if (showModePickerDialog) {
        ModePickerDialog(
            demodulationMode = demodulationMode,
            demodulationEnabled = demodulationEnabled,
            lastActiveMode = lastActiveMode,
            showDigitalTabInitially = modePickerShowDigitalTab,
            onTabChanged = { modePickerShowDigitalTab = it },
            onModeSelected = demodulationTabActions.onDemodulationModeChanged,
            onDigitalTabClicked = demodulationTabActions.onDigitalTabClicked,
            onDismiss = { showModePickerDialog = false }
        )
    }

    if(showBookmarkDialog) {
        BookmarkFavoriteDialog(
            stationFavorites = stationFavorites,
            bandFavorites = bandFavorites,
            showStationFavoritesInitially = showStationFavorites,
            onTabChanged = { showStationFavorites = it },
            onOpenBookmarksClicked = { showBookmarkDialog = false; demodulationTabActions.onOpenBookmarksClicked() },
            onAddStationBookmarkClicked = demodulationTabActions.onAddStationBookmarkClicked,
            onAddBandBookmarkClicked = demodulationTabActions.onAddBandBookmarkClicked,
            onTuneToStation = { showBookmarkDialog = false; demodulationTabActions.onTuneToStation(it) },
            onViewBand = { showBookmarkDialog = false; demodulationTabActions.onViewBand(it) },
            onDismiss = { showBookmarkDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModePickerDialog(
    demodulationMode: DemodulationMode,
    demodulationEnabled: Boolean,
    lastActiveMode: DemodulationMode,
    showDigitalTabInitially: Boolean,
    onTabChanged: (Boolean) -> Unit,
    onModeSelected: (DemodulationMode) -> Unit,
    onDigitalTabClicked: (Context) -> Unit,
    onDismiss: () -> Unit,
) {
    val showHelp = LocalShowHelp.current
    val context = LocalContext.current
    val analogModes = remember { DemodulationMode.entries.filter { !it.isDigitalExtension && it != DemodulationMode.OFF } }
    val digitalModes = remember { DemodulationMode.entries.filter { it.isDigitalExtension } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .fillMaxHeight(0.8f)
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Demodulation",
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(all = 10.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(
                            checked = demodulationEnabled,
                            onCheckedChange = { checked ->
                                if (checked) {
                                    onModeSelected(lastActiveMode)
                                } else {
                                    onModeSelected(DemodulationMode.OFF)
                                    onDismiss()
                                }
                            }
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                ) {
                    Text(
                        "Choose a demodulation mode",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    IconButton(
                        onClick = { showHelp("demodulation.html#demodulation-mode") },
                        modifier = Modifier.size(24.dp).padding(start = 2.dp)
                    ) {
                        Icon(Icons.Default.Help, contentDescription = "Help", modifier = Modifier.size(16.dp))
                    }
                }

                // While digital features are hidden, the list is the analog one alone.
                if (DigitalFeatures.shown) {
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            onClick = { onTabChanged(false) },
                            selected = !showDigitalTabInitially,
                        ) {
                            Text("Analog")
                        }
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            onClick = { onTabChanged(true); onDigitalTabClicked(context) },
                            selected = showDigitalTabInitially,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Digital", modifier = Modifier.padding(end = 6.dp))
                                if (DIGITAL_TAB_IS_NEW) {
                                    PacketBadge(label = "NEW", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }

                val listedModes = if (showDigitalTabInitially && DigitalFeatures.shown) digitalModes else analogModes
                LazyColumn(modifier = Modifier.weight(1f).alpha(if (demodulationEnabled) 1f else 0.5f)) {
                    items(listedModes) { mode ->
                        val selected = mode == demodulationMode
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onModeSelected(mode); onDismiss() }
                                .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.Bottom) {
                                    Text(mode.displayName, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                    if (mode.fullName.isNotEmpty()) {
                                        Text(
                                            " (${mode.fullName})",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(start = 4.dp)
                                        )
                                    }
                                }
                                Text(
                                    mode.description,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            if (selected) {
                                Icon(Icons.Default.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TunerWheel(
    modifier: Modifier = Modifier,
    onRotationEvent: (Float) -> Unit,
    height: Dp = 70.dp,
    sensitivity: Float = 0.5f,   // how aggressive/sensitive does the wheel react to input
    enableHapticFeedback: Boolean = true
) {
    val borderColor = MaterialTheme.colorScheme.secondary
    val animatedRotation = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val velocityTracker = remember { VelocityTracker() }
    var wheelWidthPx by remember { mutableStateOf(1f) } // avoid divide-by-zero

    val haptic = LocalHapticFeedback.current

    // Throttled rotation updates
    var lastUpdateTime by remember { mutableStateOf(0L) }
    var lastUpdateValue by remember { mutableStateOf(0f) }
    LaunchedEffect(animatedRotation.value) {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastUpdateTime > 25) {
            val delta = (animatedRotation.value - lastUpdateValue) * -1f
            val deltaWithSensitivity = delta * sensitivity
            if (abs(deltaWithSensitivity) >= 1f) {
                if (enableHapticFeedback)
                    haptic.performHapticFeedback(hapticFeedbackType = HapticFeedbackType.TextHandleMove)
                onRotationEvent(deltaWithSensitivity)
                lastUpdateTime = currentTime
                lastUpdateValue = animatedRotation.value
            }
        }
    }

    Box(modifier = modifier
            .height(height)
            .onSizeChanged { size ->
                wheelWidthPx = size.width.toFloat()
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    // stop fling when user touches the wheel
                    onPress = {
                        scope.launch {
                            animatedRotation.snapTo(animatedRotation.value)
                        }
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = {
                    },
                    onDrag = { change, dragAmount ->
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                        scope.launch {
                            animatedRotation.snapTo(animatedRotation.value + dragAmount.x * -0.2f)
                        }
                        change.consume()
                    },
                    onDragEnd = {
                        val velocity = velocityTracker.calculateVelocity().x
                        if (abs(velocity) > 1000f) {
                            scope.launch {
                                animatedRotation.animateTo(
                                    animatedRotation.value + velocity * -0.1f,
                                    animationSpec = tween(800, easing = LinearEasing)
                                )
                            }
                        }
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = size / 2f
            val wheelWidth = size.width * 0.9f

            // Draw outer frame
            drawRoundRect(
                color = Color.Black,
                cornerRadius = CornerRadius(20f, 20f),
                size = size
            )

            // Draw border
            drawRoundRect(
                color = borderColor,
                cornerRadius = CornerRadius(20f, 20f),
                size = size,
                style = Stroke(width = 4f)
            )

            // Draw wheel
            val resolution = 50
            val points = List(resolution) { i ->
                val angle = (i * Math.PI / resolution).toFloat()
                Pair(
                    center.width - cos(angle) * wheelWidth / 2,  // x coordinate
                    cos(angle).pow(2) * size.height * 0.05f      // y offset (wheel gets smaller at the edges)
                )
            }
            val path = Path().apply {
                moveTo(points.first().first, points.first().second)
                points.forEach { lineTo(it.first, center.height * 0.2f + it.second) }
                points.reversed().forEach { lineTo(it.first, center.height * 1.8f - it.second) }
                close()
            }
            drawPath(
                path = path,
                brush = Brush.horizontalGradient(
                    listOf(Color.Black, Color.LightGray, Color.Black),
                    startX = 0f,
                    endX = size.width
                )
            )

            // Draw ticks
            val numberOfTicks = 15
            repeat(numberOfTicks) { i ->
                val angle = (i * 2 * Math.PI / numberOfTicks).toFloat() + animatedRotation.value * 0.01f
                val angleNorm = (angle % Math.PI).toFloat().let { if (it < 0) it + Math.PI.toFloat() else it }
                val x = cos(angleNorm) * center.width * 0.9f         // x coordinate
                val y = cos(angleNorm).pow(2) * size.height * 0.05f  // y offset (ticks get smaller at the edges)
                val tickWidth = 1f + (1f-cos(angleNorm).pow(2))*5f   // ticks get thinner at the edges

                val lineStart = Offset(center.width + x, center.height * 0.2f + y)
                val lineEnd = Offset(center.width + x, center.height * 1.8f - y)

                drawLine(color = Color.LightGray, start = lineStart, end = lineEnd, strokeWidth = tickWidth)
                drawLine(color = Color.DarkGray, start = lineStart + Offset(tickWidth, 0f), end = lineEnd + Offset(tickWidth, 0f), strokeWidth = tickWidth*2)
            }
        }
    }
}

@Composable
@Preview
fun DemodulationTabPreview() {
    CompositionLocalProvider(LocalShowHelp provides {}) {
        DemodulationTabComposable(
            demodulationEnabled = true,
            demodulationMode = DemodulationMode.USB,
            digitalNotice = null,
            wfmStereoAndRdsEnabled = false,
            wfmRdsPtyTable = RdsPtyTable.AUTO,
            wfmRdsAutoIsRbds = false,
            channelFrequency = 14000000,
            channelWidth = 50000,
            tuneStepHz = 1000,
            pocsagBaudRate = 1200,
            meshCoreCustomChannels = emptyList(),
            ax25Variant = 1200,
            rttyBaudRate = 45.45f,
            rttyFrequencyShift = 170f,
            rttyPolarity = RttyPolarity.AUTO,
            psk31BaudRate = DigitalDemodParams.Psk31.AUTO,
            r09BaudRate = DigitalDemodParams.R09.AUTO,
            loraSpreadingFactors = LoraSpreadingFactors.AUTO,
            loraIqMode = LoraIqMode.AUTO,
            ft8TimingMode = Ft8TimingMode.AUTOMATIC,
            ft8ManualOffsetMs = 0,
            clockSyncState = ClockSyncState.Idle,
            squelchEnabled = true,
            minSquelch = 0f,
            maxSquelch = 40f,
            squelch = 10f,
            viewportZoom = 0.9f,
            audioVolumeLevel = 0.72f,
            audioMuted = false,
            stationFavorites = emptyList(),
            bandFavorites = emptyList(),
            demodulationTabActions = DemodulationTabActions(
                onDemodulationModeChanged = {},
                onDigitalTabClicked = { _ -> },
                onWfmStereoAndRdsEnabledChanged = {},
                onDigitalAction = {},
                onWfmRdsPtyTableChanged = {},
                onChannelFrequencyChanged = {},
                onTunerWheelDelta = {},
                onChannelWidthChanged = {},
                onTuneStepHzChanged = {},
                onPocsagBaudRateChanged = {},
                onMeshCoreCustomChannelsChanged = {},
                onAx25VariantChanged = {},
                onRttyBaudRateChanged = {},
                onRttyFrequencyShiftChanged = {},
                onRttyPolarityChanged = {},
                onPsk31BaudRateChanged = {},
                onR09BaudRateChanged = {},
                onLoraSpreadingFactorsChanged = {},
                onLoraIqModeChanged = {},
                onFt8TimingModeChanged = {},
                onFt8ManualOffsetChanged = {},
                onSyncClockClicked = {},
                onSquelchEnabledChanged = { },
                onSquelchChanged = { },
                onZoomChanged = { },
                onAudioMuteClicked = { },
                onAudioVolumeLevelChanged = { },
                onOpenBookmarksClicked = { },
                onOverloadHelpClicked = { },
                onAddStationBookmarkClicked = { },
                onAddBandBookmarkClicked = { },
                onTuneToStation = { },
                onViewBand = { },
                onShareJsonExportClicked = { _, _ -> },
                onTrackOnMapClicked = { _, _, _ -> },
                onOpenExternalUrlClicked = { },
            ),
        )
    }
}

/**
 * LoRa's receiver settings, the RTTY pattern: Auto by default, a fixed choice on request. Auto finds
 * every MeshCore, Meshtastic, LoRa-APRS and LoRaWAN signal; a fixed choice needs less CPU (A55 at
 * 250 kHz: Auto 45 %, one chirp direction 30 %, one SF 15 % of a core) and misses the rest.
 * Implicit header, sync words and extra channels stay hidden: easy to misconfigure, rarely needed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoraSettings(spreadingFactors: Int, iqMode: LoraIqMode, actions: DemodulationTabActions) {
    OutlinedBox(label = "Spreading Factor", helpSubPath = "lora.html#controls-in-the-demodulation-tab") {
        Column(modifier = Modifier.fillMaxWidth()) {
            val selected = LoraSpreadingFactors.toList(spreadingFactors)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = spreadingFactors == LoraSpreadingFactors.AUTO,
                    onClick = { actions.onLoraSpreadingFactorsChanged(LoraSpreadingFactors.AUTO) },
                    label = { Text("Auto") },
                )
                for (sf in 5..12) {
                    FilterChip(
                        selected = sf in selected,
                        onClick = {
                            val next = if (sf in selected) selected - sf else selected + sf
                            actions.onLoraSpreadingFactorsChanged(if (next.isEmpty()) LoraSpreadingFactors.AUTO else LoraSpreadingFactors.of(*next.toIntArray()))
                        },
                        label = { Text("$sf") },
                    )
                }
            }
            Text(
                text = if (spreadingFactors == LoraSpreadingFactors.AUTO) "SF7 to SF12 at once; SF5 and SF6 only when selected."
                    else "Only SF ${selected.joinToString(", ")}: less CPU, frames with other spreading factors are missed.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
    OutlinedBox(label = "Chirp Direction", helpSubPath = "lora.html#controls-in-the-demodulation-tab") {
        Column {
            val modes = listOf(LoraIqMode.AUTO to "Auto", LoraIqMode.NORMAL to "Normal", LoraIqMode.INVERTED to "Inverted")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (mode, label) ->
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        onClick = { actions.onLoraIqModeChanged(mode) },
                        selected = iqMode == mode,
                    ) { Text(label, maxLines = 1) }
                }
            }
            Text(
                text = when (iqMode) {
                    LoraIqMode.AUTO -> "Both directions: devices send normal chirps, LoRaWAN gateways inverted ones."
                    LoraIqMode.NORMAL -> "Normal chirps only: MeshCore, Meshtastic, LoRa-APRS and LoRaWAN uplinks."
                    LoraIqMode.INVERTED -> "Inverted chirps only: LoRaWAN downlinks from gateways."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
            )
        }
    }
}

/** The tuned station's parameters are in force (StationOverlay.kt): from where, until when, and ✕. */
@Composable
private fun StationOverlayNotice(overlay: StationOverlay, onDismiss: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(8.dp))
            .padding(start = 12.dp),
    ) {
        Text(
            text = "Settings from \u201C${overlay.stationName}\u201D until you tune away",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Back to your own settings", tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}
