package com.mantz_it.rfanalyzer.ui

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.mantz_it.rfanalyzer.BuildConfig
import com.mantz_it.rfanalyzer.billing.License
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.VERTICAL_SCALE_LOWER_BOUNDARY
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.VERTICAL_SCALE_UPPER_BOUNDARY
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.ui.composable.reverseSaturationFunction
import com.mantz_it.rfanalyzer.ui.composable.saturationFunction
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.tabs.FftColorMap
import com.mantz_it.rfanalyzer.ui.tabs.FftDrawingType
import com.mantz_it.rfanalyzer.ui.tabs.FontSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.analyzer.FftProcessor
import com.mantz_it.rfanalyzer.analyzer.FftProcessorData
import com.mantz_it.rfanalyzer.database.BandWithBookmarkList
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer.database.SourceProvider
import com.mantz_it.rfanalyzer.database.StationWithBookmarkList
import com.mantz_it.rfanalyzer.digiext.ExtensionConnectionState
import com.mantz_it.rfanalyzer_ipc.digiext.DemodStatus
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * <h1>RF Analyzer - Analyzer Surface (FFT/Waterfall View)</h1>
 *
 * Module:      AnalyzerSurface.kt
 * Description: The custom SurfaceView which draws the FFT and the Waterfall plots.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


data class AnalyzerSurfaceActions(
    val onViewportFrequencyChanged: (Long) -> Unit,
    val onViewportSampleRateChanged: (Long) -> Unit,
    val onViewportVerticalScaleChanged: (Pair<Float, Float>) -> Unit,  // (minDB, maxDB)
    val onChannelFrequencyChanged: (newFrequency: Long) -> Unit,
    val onChannelWidthChanged: (newWidth: Int) -> Unit,
    val onSquelchChanged: (newSquelch: Float) -> Unit,
    val onStationsClicked: (stationIds: Set<Long>, longPress: Boolean) -> Unit,
)

// helper classes for drawing the station/band labels
data class StationLabel(
    val stationIds: Set<Long>,
    val name: String,
    val frequency: Long,
    val x: Float,
    val widthPx: Float,
    val layer: Int,
    val mergedCount: Int = 1,
    val color: Int = Color.DKGRAY
)
data class BandLabel(
    val id: Long,
    val name: String,
    val freqStart: Long,
    val freqEnd: Long,
    val xStart: Float,
    val xEnd: Float,
    val layer: Int,
    val color: Int,
    val subBands: List<SubBandLabel>
)

data class SubBandLabel(
    val name: String,
    val freqStart: Long,
    val freqEnd: Long,
    val xStart: Float,
    val xEnd: Float
)

class AnalyzerSurface(context: Context,
                      private val sourceName: AppStateRepository.State<String>,
                      private val analyzerRunning: AppStateRepository.State<Boolean>,
                      private val sourceOptimalSampleRates: AppStateRepository.State<List<Long>>,
                      private val sourceFrequency: AppStateRepository.State<Long>,
                      private val sourceSampleRate: AppStateRepository.State<Long>,
                      private val sourceSignalStartFrequency: AppStateRepository.State<Long>,
                      private val sourceSignalEndFrequency: AppStateRepository.State<Long>,
                      private val fftAverageLength: AppStateRepository.State<Int>,
                      private val fftPeakHold: AppStateRepository.State<Boolean>,
                      private val maxFrameRate: AppStateRepository.State<Int>,
                      private val waterfallColorMap: AppStateRepository.State<FftColorMap>,
                      private val fftDrawingType: AppStateRepository.State<FftDrawingType>,
                      private val fftRelativeFrequency: AppStateRepository.State<Boolean>,
                      private val fftWaterfallRatio: AppStateRepository.State<Float>,
                      private val demodulationMode: AppStateRepository.State<DemodulationMode>,
                      private val demodulationEnabled: AppStateRepository.State<Boolean>,
                      private val channelFrequency: AppStateRepository.State<Long>,
                      private val channelWidth: AppStateRepository.State<Int>,
                      private val channelStartFrequency: AppStateRepository.State<Long>,
                      private val channelEndFrequency: AppStateRepository.State<Long>,
                      private val squelchEnabled: AppStateRepository.State<Boolean>,
                      /** Where the squelch opens now (noise floor + margin), in the plot's dB. */
                      private val squelch: AppStateRepository.State<Float>,
                      /** The squelch's margin above the noise floor - its label. */
                      private val squelchMargin: AppStateRepository.State<Float>,
                      private val recordingRunning: AppStateRepository.State<Boolean>,
                      private val basebandRecordingRunning: AppStateRepository.State<Boolean>,
                      private val recordOnlyWhenSquelchIsSatisfied: AppStateRepository.State<Boolean>,
                      private val recordingCurrentFileSize: AppStateRepository.State<Long>,
                      private val fontSize: AppStateRepository.State<FontSize>,
                      private val showDebugInformation: AppStateRepository.State<Boolean>,
                      private val viewportFrequency: AppStateRepository.State<Long>,
                      private val viewportSampleRate: AppStateRepository.State<Long>,
                      private val viewportVerticalScaleMin: AppStateRepository.State<Float>,
                      private val viewportVerticalScaleMax: AppStateRepository.State<Float>,
                      private val viewportStartFrequency: AppStateRepository.State<Long>,
                      private val viewportEndFrequency: AppStateRepository.State<Long>,
                      private val averageSignalStrength: AppStateRepository.State<Float>,
                      private val squelchSatisfied: AppStateRepository.State<Boolean>,
                      private val license: AppStateRepository.State<License>,
                      private val fftProcessorData: FftProcessorData,
                      private val stationListFlow: StateFlow<List<StationWithBookmarkList>>,
                      private val bandListFlow: StateFlow<List<BandWithBookmarkList>>,
                      private val displayStationsInFft: AppStateRepository.State<Boolean>,
                      private val displayBandsInFft: AppStateRepository.State<Boolean>,
                      private val demodStatusFlow: StateFlow<DemodStatus?>,
                      private val demodPacketCountsFlow: StateFlow<Map<DigitalMode, Int>>,
                      private val extensionConnectionStateFlow: StateFlow<ExtensionConnectionState>,
                      private val demodulationWfmStereoAndRdsEnabled: AppStateRepository.State<Boolean>,
                      private val analyzerSurfaceActions: AnalyzerSurfaceActions) : SurfaceView(context){

    companion object {
        private const val LOGTAG = "AnalyzerSurface"
        private const val WATERFALL_STRIP_ROWS = 25 // rows per waterfall bitmap strip
        private const val MIN_VIRTUAL_SAMPLERATE = 64L // Smallest virtual sample rate

        const val STROKE_WIDTH_CHANNELWIDTHSELECTOR = 1f
        const val STROKE_WIDTH_SQUELCH = 3f
        const val STROKE_WIDTH_CHANNELSELECTOR = 3f
        const val STROKE_WIDTH_ACTIVE = 6f

        // Quick tune/zoom gesture (long-press on empty space)
        const val QUICK_TUNE_CENTER_DOT_RADIUS_DP = 30f
        const val QUICK_TUNE_RING_RADIUS_DP = 30f // circular dead zone; leaving it commits (locks in) one axis for the rest of the gesture
        const val QUICK_TUNE_ZOOM_TOTAL_RANGE_DP = 240f // total vertical travel spanning the entire 0..max zoom range
        const val QUICK_TUNE_SATURATION_A = 3.5f // identical saturation curve to the zoom slider (DemodulationTab.kt)
        const val QUICK_TUNE_SATURATION_K = 0.15f
        const val QUICK_TUNE_FINE_TUNE_STEP_COUNT = 20 // number of discrete tune steps offered in each direction
        const val QUICK_TUNE_FINE_TUNE_TICK_HALF_HEIGHT_DP = 55f // ticks extend well above/below the touch point so they stay visible under the finger
        const val QUICK_TUNE_FINE_TUNE_MARKER_HALF_HEIGHT_DP = 75f // the selected-step marker is taller still, to stand out
        const val QUICK_TUNE_FINE_TUNE_STEP_SPACING_DP = 28f // pixel spacing per discrete tune step
        const val QUICK_TUNE_LABEL_MARGIN_DP = 8f // gap between an axis's end and its label
        const val QUICK_TUNE_HINT_LABEL_MARGIN_DP = 40f // gap between the ring and the direction hints, clear of the fingers

        // Label rows in the FFT (px): a station label box is its text size + STATION_LABEL_BOX_PADDING
        // high with STATION_LABEL_ROW_GAP between rows; a band box holds the band and sub-band names.
        const val STATION_LABEL_BOX_PADDING = 10f
        // While the analyzer is stopped: how often the draw thread checks for a change, and the
        // longest it goes without drawing.
        const val IDLE_POLL_MS = 40L
        const val IDLE_REDRAW_MS = 1000L
        const val STATION_LABEL_ROW_GAP = 6f
        const val BAND_LABEL_ROW_GAP = 12f
        const val MAX_LABEL_ROWS = 7
    }

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var drawingThread: DrawThread? = null

    // Gesture detectors to detect scaling, scrolling ...
    private var scaleGestureDetector: ScaleGestureDetector
    private var gestureDetector: GestureDetector
    private var surfaceHolder: SurfaceHolder? = null

    // internal state
    private var width = 0 // current width (in pixels) of the SurfaceView
    private var height = 0 // current height (in pixels) of the SurfaceView
    private var doAutoscaleInNextDraw = false // will cause draw() to adjust minDB and maxDB according to the samples

    // derived (calculated) properties
    private val fftHeight: Int  // Returns the height of the fft plot in px (y coordinate of the bottom line of the fft spectrum)
        get() = (height * fftWaterfallRatio.value).toInt()
    private val waterfallHeight: Int // Returns the height of the waterfall plot in px
        get() = (height * (1 - fftWaterfallRatio.value)).toInt()
    private val gridSize: Int // Returns the height/width of the frequency/power grid in px
        get() {
            val xdpi = resources.displayMetrics.xdpi
            val xpixel = resources.displayMetrics.widthPixels.toFloat()
            val xinch = xpixel / xdpi

            return if (xinch < 30) (75 * xdpi / 200).toInt() // Smartphone / Tablet / Computer screen
            else (400 * xdpi / 200).toInt() // TV screen
        }
    // The drag handles sit high while the squelch line is low. They change sides only once the line
    // is well past the middle (hysteresis): the line follows the noise floor, and the handles would
    // jump up and down with it near the switch point.
    @Volatile private var dragHandlesHigh = false
    private val channelSelectorDragHandlePosition: Pair<Float, Float> // vertical position of the drag handles (in dB; first: channel handle; second: channelWidth handle)
        get() {
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            val viewportHeightInDb = maxDB - minDB
            val centerHandlePosition: Float
            val outerHandlePosition: Float
            val squelchHeight = (squelch.value - minDB) / viewportHeightInDb
            if (squelchHeight < 0.32f) dragHandlesHigh = true
            else if (squelchHeight > 0.38f) dragHandlesHigh = false
            if(dragHandlesHigh) {
                centerHandlePosition = minDB + viewportHeightInDb * 0.55f
                outerHandlePosition = minDB + viewportHeightInDb * 0.45f
            } else {
                centerHandlePosition = minDB + viewportHeightInDb * 0.3f
                outerHandlePosition = minDB + viewportHeightInDb * 0.2f
            }
            return Pair(centerHandlePosition, outerHandlePosition)
        }

    // Get X coordinate (Px) from frequency
    fun freqToX(freq: Long): Float = ((freq - viewportStartFrequency.value).toFloat() / viewportSampleRate.value.toFloat()) * width

    private var stationList: List<StationWithBookmarkList>? = null
    @Volatile
    private var touchEventCount = 0L

    /**
     * Everything a frame shows while the analyzer is stopped - no new FFT rows then (except the
     * last ones arriving just after the stop), so the frame changes only with the view (pan, zoom,
     * the drawer resizing it), a setting, a label or a touch. The draw thread draws when this
     * changes (and once a second for anything it misses) instead of at Max Frame Rate.
     */
    private fun idleFrameSignature(): List<Any?> = listOf(
        width, height, fftHeight, touchEventCount, stationLabels, bandLabels, fftProcessorData.newestRowTimeNs,
        viewportFrequency.value, viewportSampleRate.value, viewportVerticalScaleMin.value, viewportVerticalScaleMax.value,
        channelFrequency.value, channelWidth.value, squelchEnabled.value, squelch.value, demodulationMode.value,
        demodulationEnabled.value, waterfallColorMap.value, fftDrawingType.value, fftRelativeFrequency.value,
        fftWaterfallRatio.value, fftPeakHold.value, fontSize.value, showDebugInformation.value, sourceName.value,
        recordingRunning.value, basebandRecordingRunning.value, license.value, displayStationsInFft.value,
        displayBandsInFft.value, demodulationWfmStereoAndRdsEnabled.value, extensionConnectionStateFlow.value,
    )

    private var stationLabels: List<StationLabel>? = null
    private var bandList: List<BandWithBookmarkList>? = null
    private var bandLabels: List<BandLabel>? = null
    @Volatile private var demodStatus: DemodStatus? = null
    @Volatile private var demodPacketCounts: Map<DigitalMode, Int> = emptyMap()
    @Volatile private var extensionConnectionState: ExtensionConnectionState = ExtensionConnectionState.DISCONNECTED

    // Handle Station Label Clicks (long press or simple click)
    private val LONG_PRESS_THRESHOLD = 300L // ms
    private var pressedLabel: StationLabel? = null
    private var longPressTriggered = false
    private val longPressHandler = Handler(Looper.getMainLooper())
    private val longPressRunnable = Runnable {
        pressedLabel?.let { label ->
            longPressTriggered = true
            this@AnalyzerSurface.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS) // Trigger haptic feedback
            analyzerSurfaceActions.onStationsClicked(label.stationIds, true)
            pressedLabel = null
        }
    }
    private fun cancelLongPressRunnable() {
        longPressHandler.removeCallbacks(longPressRunnable)
        pressedLabel = null
    }
    private val labelDebouncedUpdateHandler = Handler(Looper.getMainLooper())
    private val labelDebouncedUpdateRunnable = Runnable {
        updateStationLabels()
        updateBandLabels()
    }
    private fun debouncedLabelUpdate() {
        labelDebouncedUpdateHandler.removeCallbacks(labelDebouncedUpdateRunnable)

        labelDebouncedUpdateHandler.postDelayed(labelDebouncedUpdateRunnable, 250) // 250ms debounce
    }

    // Quick tune/zoom: long-press on empty space engages a joystick-style gesture. The two axes are
    // mutually exclusive - whichever axis the finger first leaves the center ring on stays engaged
    // for the rest of the gesture, the other is ignored until the finger lifts. With demodulation
    // off there is no channel to tune, so only the zoom axis exists.
    private enum class QuickTuneAxis { VERTICAL, HORIZONTAL }

    @Volatile private var quickTuneActive = false
    @Volatile private var quickTuneCenterX = 0f
    @Volatile private var quickTuneCenterY = 0f
    @Volatile private var quickTuneCurrentX = 0f // live finger position, for indicator drawing only
    @Volatile private var quickTuneCurrentY = 0f
    @Volatile private var quickTuneEngagedAxis: QuickTuneAxis? = null // null while still inside the ring
    @Volatile private var quickTuneTuneAxisAvailable = false // demodulation enabled, frozen at activation
    private var quickTuneArmed = false
    private var quickTuneDownX = 0f
    private var quickTuneDownY = 0f
    private var quickTuneCenterChannelFrequency = 0L
    private var quickTuneZoomAnchorFrequency = 0L // frequency that keeps its screen position while zooming
    private var quickTuneFineTuneStepHz = 0L // mode's smallest tune-step setting, frozen at activation
    private var quickTuneZoomTopLengthPx = 0f // distance above center to reach max zoom
    private var quickTuneZoomBottomLengthPx = 0f // distance below center to reach no zoom
    private var quickTuneZoomPhysicalMax = 0f // per-mode max, same post-saturation domain as the zoom slider's value
    private val quickTuneHandler = Handler(Looper.getMainLooper())
    private val quickTuneArmRunnable = Runnable { activateQuickTune() }

    private fun activateQuickTune() {
        if (!quickTuneArmed) return // cancelled in the meantime (e.g. touch-slop move)
        quickTuneArmed = false
        quickTuneActive = true
        quickTuneEngagedAxis = null
        quickTuneTuneAxisAvailable = demodulationEnabled.value
        quickTuneCenterX = quickTuneDownX
        quickTuneCenterY = quickTuneDownY
        quickTuneCurrentX = quickTuneDownX
        quickTuneCurrentY = quickTuneDownY
        quickTuneCenterChannelFrequency = channelFrequency.value
        quickTuneFineTuneStepHz = demodulationMode.value.tuneStepOptions.min().toLong()
        // Zoom around the channel while it is on screen, otherwise around the pressed point. Frozen for
        // the whole gesture: a channel that zooms out into view does not take over the anchor mid-gesture.
        val channelOnScreen = quickTuneTuneAxisAvailable &&
                channelFrequency.value in viewportStartFrequency.value..viewportEndFrequency.value
        quickTuneZoomAnchorFrequency = if (channelOnScreen) channelFrequency.value
            else viewportStartFrequency.value + (quickTuneDownX / width * viewportSampleRate.value).toLong()

        // Where the current zoom sits along the zoom slider's physical (post-reverse-saturation)
        // domain determines how much of the fixed on-screen range is left above vs. below center.
        val currentZoomFraction = (1f - viewportSampleRate.value.toFloat() / sourceSampleRate.value.toFloat()).coerceIn(0f, 1f)
        val physicalCurrent = reverseSaturationFunction(currentZoomFraction, QUICK_TUNE_SATURATION_A, QUICK_TUNE_SATURATION_K)
        quickTuneZoomPhysicalMax = quickTuneMaxPhysicalZoomValue()
        val s = (physicalCurrent / quickTuneZoomPhysicalMax).coerceIn(0f, 1f)
        val totalRangePx = QUICK_TUNE_ZOOM_TOTAL_RANGE_DP * resources.displayMetrics.density
        quickTuneZoomTopLengthPx = (1f - s) * totalRangePx
        quickTuneZoomBottomLengthPx = s * totalRangePx

        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    private fun deactivateQuickTune() {
        quickTuneHandler.removeCallbacks(quickTuneArmRunnable)
        quickTuneActive = false
        quickTuneArmed = false
        quickTuneEngagedAxis = null
    }

    /** Per-mode max value of the zoom slider's `valueRange` (DemodulationTab.kt) - already in the post-saturation domain. */
    private fun quickTuneMaxPhysicalZoomValue(): Float = when (demodulationMode.value) {
        DemodulationMode.OFF -> 0.995f
        DemodulationMode.AM, DemodulationMode.NFM, DemodulationMode.AX25, DemodulationMode.POCSAG -> 0.7f
        DemodulationMode.WFM, DemodulationMode.LORA -> 0.45f
        DemodulationMode.ADSB -> 0.45f
        DemodulationMode.LSB, DemodulationMode.USB -> 0.95f
        DemodulationMode.CW, DemodulationMode.RTTY, DemodulationMode.PSK31, DemodulationMode.R09, DemodulationMode.FT8 -> 0.997f
    }

    /** Called on every touch-move while quick-tune is active; picks an axis once, then updates only that axis. */
    private fun updateQuickTune(x: Float, y: Float) {
        quickTuneCurrentX = x
        quickTuneCurrentY = y
        if (quickTuneEngagedAxis == null) {
            val ringRadiusPx = QUICK_TUNE_RING_RADIUS_DP * resources.displayMetrics.density
            val dx = x - quickTuneCenterX
            val dy = y - quickTuneCenterY
            if (dx * dx + dy * dy > ringRadiusPx * ringRadiusPx) {
                quickTuneEngagedAxis = if (!quickTuneTuneAxisAvailable || abs(dy) >= abs(dx)) QuickTuneAxis.VERTICAL else QuickTuneAxis.HORIZONTAL
            }
        }
        when (quickTuneEngagedAxis) {
            QuickTuneAxis.VERTICAL -> updateQuickTuneZoom(y)
            QuickTuneAxis.HORIZONTAL -> updateQuickTuneTune(x)
            null -> { }
        }
    }

    /** Maps the finger position onto the frozen physical zoom track, exactly like dragging the zoom slider. */
    private fun updateQuickTuneZoom(y: Float) {
        val dy = (y - quickTuneCenterY).coerceIn(-quickTuneZoomTopLengthPx, quickTuneZoomBottomLengthPx)
        val totalRangePx = quickTuneZoomTopLengthPx + quickTuneZoomBottomLengthPx
        val s = if (totalRangePx > 0f) (quickTuneZoomBottomLengthPx - dy) / totalRangePx else 0f
        val physicalValue = s.coerceIn(0f, 1f) * quickTuneZoomPhysicalMax
        applyZoom(saturationFunction(physicalValue, QUICK_TUNE_SATURATION_A, QUICK_TUNE_SATURATION_K))
    }

    /** Snaps the finger position to the nearest of a fixed number of fine-tune steps in each direction. */
    private fun updateQuickTuneTune(x: Float) {
        val stepPx = QUICK_TUNE_FINE_TUNE_STEP_SPACING_DP * resources.displayMetrics.density
        val stepIndex = ((x - quickTuneCenterX) / stepPx).roundToInt().coerceIn(-QUICK_TUNE_FINE_TUNE_STEP_COUNT, QUICK_TUNE_FINE_TUNE_STEP_COUNT)
        applyTune(quickTuneCenterChannelFrequency + stepIndex.toLong() * quickTuneFineTuneStepHz)
    }

    /** Identical formula to the zoom slider's onZoomChanged (MainViewModel.kt), anchored at [quickTuneZoomAnchorFrequency]. */
    private fun applyZoom(zoom: Float) {
        val newVirtualSampleRate = ((1f - zoom) * sourceSampleRate.value).toLong().coerceAtLeast(MIN_VIRTUAL_SAMPLERATE)
        val sampleRateDiff = newVirtualSampleRate - viewportSampleRate.value
        val anchorToCenterOffset = viewportFrequency.value - quickTuneZoomAnchorFrequency
        val offsetRatio = anchorToCenterOffset / (viewportSampleRate.value.toFloat() / 2)
        val newVirtualFrequency = viewportFrequency.value + (sampleRateDiff / 2 * offsetRatio).toLong()
        analyzerSurfaceActions.onViewportSampleRateChanged(newVirtualSampleRate)
        analyzerSurfaceActions.onViewportFrequencyChanged(newVirtualFrequency)
    }

    private fun applyTune(newFrequency: Long) {
        analyzerSurfaceActions.onChannelFrequencyChanged(newFrequency.coerceIn(sourceSignalStartFrequency.value, sourceSignalEndFrequency.value))
    }

    // scroll type stores the intention of the user on a pointer down event:
    enum class ScrollType() {
        NONE,
        NORMAL,
        NORMAL_VERTICAL,
        CHANNEL_FREQUENCY,
        CHANNEL_WIDTH_LEFT,
        CHANNEL_WIDTH_RIGHT,
        SQUELCH
    }
    private var scrollType = ScrollType.NORMAL
    private var channelWidthDragStartValue = channelWidth.value  // reference channel width during scrolling/draging the width

    private final val scaleGestureListener = object: ScaleGestureDetector.OnScaleGestureListener {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) { }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            when (scrollType) {
                ScrollType.NORMAL -> {
                    val xScale = detector.currentSpanX / detector.previousSpanX
                    // 0/0 when both fingers share an x coordinate - that would snap to maximum zoom
                    if (!xScale.isFinite() || xScale <= 0f) return true
                    val sourceMaxSampleRate = sourceOptimalSampleRates.value.last().coerceAtLeast(MIN_VIRTUAL_SAMPLERATE)
                    val newVirtualSampleRate = (viewportSampleRate.value / xScale).toLong().coerceIn(MIN_VIRTUAL_SAMPLERATE, sourceMaxSampleRate)
                    val frequencyFocus = viewportFrequency.value + ((detector.focusX / width - 0.5) * newVirtualSampleRate).toInt()
                    val newVirtualFrequency = frequencyFocus + ((viewportFrequency.value - frequencyFocus) / xScale).toLong()
                    analyzerSurfaceActions.onViewportSampleRateChanged(newVirtualSampleRate)
                    analyzerSurfaceActions.onViewportFrequencyChanged(newVirtualFrequency)
                }
                ScrollType.NORMAL_VERTICAL -> {
                    val minDB = viewportVerticalScaleMin.value
                    val maxDB = viewportVerticalScaleMax.value
                    val yScale = detector.currentSpanY / detector.previousSpanY
                    // 0/0 when both fingers share a y coordinate - a NaN scale would get persisted
                    if (!yScale.isFinite() || yScale <= 0f || fftHeight <= 0) return true
                    val dBFocus = maxDB - (maxDB - minDB) * (detector.focusY / fftHeight)
                    val newMinDB = (dBFocus - (dBFocus - minDB) / yScale).coerceIn(VERTICAL_SCALE_LOWER_BOUNDARY, VERTICAL_SCALE_UPPER_BOUNDARY - 10)
                    val newMaxDB = (dBFocus - (dBFocus - maxDB) / yScale).coerceIn(newMinDB + 10, VERTICAL_SCALE_UPPER_BOUNDARY)
                    analyzerSurfaceActions.onViewportVerticalScaleChanged(Pair(newMinDB, newMaxDB))
                }
                else -> { } // if the user tapped on a drag handle we don't scale but handle it in onScroll
            }
            return true
        }
    }

    private val gestureListener = object: GestureDetector.OnGestureListener {
        override fun onDown(e: MotionEvent): Boolean {
            // Find out which type of scrolling is requested:
            val demodulationEnabled = demodulationEnabled.value
            val demodulationMode = demodulationMode.value
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            val channelFrequency = channelFrequency.value
            val channelStartFrequency = channelStartFrequency.value
            val channelEndFrequency = channelEndFrequency.value
            val squelchEnabled = squelchEnabled.value
            val squelch = squelch.value
            val hzPerPx = viewportSampleRate.value / width.toFloat()
            val dbPerPx = (maxDB - minDB) / fftHeight.toFloat()
            val channelFrequencyVariation = viewportSampleRate.value * 0.05f
            val dbVariation = (maxDB - minDB) * 0.1f
            val touchedFrequency = viewportStartFrequency.value + (e.x * hzPerPx).toLong()
            val touchedDB = maxDB - e.y * dbPerPx
            val showUpperBand = demodulationMode != DemodulationMode.LSB
            val (centerDragHandlePositionDb, outerDragHandlePositionDb) = channelSelectorDragHandlePosition

            // if the user touched the squelch indicator the user wants to adjust the squelch threshold:
            val squelchHandlePositionInHz = if (showUpperBand) channelEndFrequency else channelStartFrequency
            if (demodulationEnabled && squelchEnabled
                    && touchedFrequency < squelchHandlePositionInHz + channelFrequencyVariation
                    && touchedFrequency > squelchHandlePositionInHz - channelFrequencyVariation
                    && touchedDB < squelch + dbVariation
                    && touchedDB > squelch - dbVariation) {
                scrollType = ScrollType.SQUELCH
                drawingThread?.squelchPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
            } else if (demodulationEnabled
                    && touchedDB in centerDragHandlePositionDb - dbVariation .. centerDragHandlePositionDb + dbVariation
                    && touchedFrequency < channelFrequency + channelFrequencyVariation
                    && touchedFrequency > channelFrequency - channelFrequencyVariation) {
                scrollType = ScrollType.CHANNEL_FREQUENCY
                drawingThread?.channelSelectorPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
            } else if (demodulationEnabled && !showUpperBand
                    && touchedDB in outerDragHandlePositionDb - dbVariation .. outerDragHandlePositionDb + dbVariation
                    && touchedFrequency < channelStartFrequency + channelFrequencyVariation
                    && touchedFrequency > channelStartFrequency - channelFrequencyVariation) {
                scrollType = ScrollType.CHANNEL_WIDTH_LEFT
                channelWidthDragStartValue = channelWidth.value
                drawingThread?.channelWidthSelectorPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
            } else if (demodulationEnabled && showUpperBand
                    && touchedDB in outerDragHandlePositionDb - dbVariation .. outerDragHandlePositionDb + dbVariation
                    && touchedFrequency < channelEndFrequency + channelFrequencyVariation
                    && touchedFrequency > channelEndFrequency - channelFrequencyVariation
            ) {
                scrollType = ScrollType.CHANNEL_WIDTH_RIGHT
                channelWidthDragStartValue = channelWidth.value
                drawingThread?.channelWidthSelectorPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
            } else if (e.x > gridSize * 1.5 || e.y > fftHeight - gridSize)
                scrollType = ScrollType.NORMAL
            else
                scrollType = ScrollType.NORMAL_VERTICAL


            return true
        }

        override fun onShowPress(e: MotionEvent) { }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Check if the last down-tap was already handled as a long press:
            if (longPressTriggered) return true
            cancelLongPressRunnable() // cancel any ongoing long press timers

            // check if the user tapped on a station label:
            val stationLabel = findStationLabelAt(e.x, e.y)
            if (stationLabel != null) {
                Log.d(LOGTAG, "onSingleTapUp: tapped on ${stationLabel.name} @ ${stationLabel.frequency} (IDs: ${stationLabel.stationIds})")
                analyzerSurfaceActions.onStationsClicked(stationLabel.stationIds, false)
                return true
            }

            // Set the channel frequency to the tapped position
            if (demodulationEnabled.value) {
                val hzPerPx = viewportSampleRate.value / width.toFloat()
                val newChannelFrequency = viewportStartFrequency.value + (hzPerPx * e.x).toLong()
                analyzerSurfaceActions.onChannelFrequencyChanged(newChannelFrequency.coerceIn(sourceSignalStartFrequency.value, sourceSignalEndFrequency.value))
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2:  MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if(e1 == null)
                return true
            val hzPerPx = viewportSampleRate.value / width.toFloat()

            // scroll horizontally or adjust the channel selector (scrollType was chosen in onDown()):
            when (scrollType) {
                ScrollType.NONE -> Log.w(LOGTAG, "onScroll: No ScrollType specified!")
                // Scroll horizontal if touch point in the main area
                ScrollType.NORMAL -> {
                    // Scroll viewport frequency (horizontal)
                    val oldViewportFrequency = viewportFrequency.value
                    val newViewportFrequency = (oldViewportFrequency + (hzPerPx * distanceX).toLong())
                    analyzerSurfaceActions.onViewportFrequencyChanged(newViewportFrequency)
                }
                ScrollType.NORMAL_VERTICAL -> {
                    // Scroll viewport vertical scale
                    if (fftHeight <= 0) return true
                    val minDB = viewportVerticalScaleMin.value
                    val maxDB = viewportVerticalScaleMax.value
                    var yDiff = (maxDB - minDB) * (distanceY / fftHeight.toFloat())
                    // Make sure we stay in the boundaries:
                    if (maxDB - yDiff > VERTICAL_SCALE_UPPER_BOUNDARY) yDiff = VERTICAL_SCALE_UPPER_BOUNDARY - maxDB
                    if (minDB - yDiff < VERTICAL_SCALE_LOWER_BOUNDARY) yDiff = VERTICAL_SCALE_LOWER_BOUNDARY - minDB
                    analyzerSurfaceActions.onViewportVerticalScaleChanged(Pair(minDB - yDiff, maxDB - yDiff))
                }

                ScrollType.CHANNEL_FREQUENCY -> {
                    val newChannelFrequency = (channelFrequency.value - distanceX * hzPerPx).toLong()
                    analyzerSurfaceActions.onChannelFrequencyChanged(newChannelFrequency.coerceIn(sourceSignalStartFrequency.value, sourceSignalEndFrequency.value))
                }

                ScrollType.CHANNEL_WIDTH_LEFT, ScrollType.CHANNEL_WIDTH_RIGHT -> {
                    channelWidthDragStartValue = if (scrollType == ScrollType.CHANNEL_WIDTH_LEFT) (channelWidthDragStartValue + distanceX * hzPerPx).toInt() else (channelWidthDragStartValue - distanceX * hzPerPx).toInt()
                    analyzerSurfaceActions.onChannelWidthChanged(channelWidthDragStartValue)
                }

                ScrollType.SQUELCH -> {
                    val minDB = viewportVerticalScaleMin.value
                    val maxDB = viewportVerticalScaleMax.value
                    val dbPerPx = (maxDB - minDB) / fftHeight.toFloat()
                    val newSquelch = squelch.value + distanceY * dbPerPx
                    analyzerSurfaceActions.onSquelchChanged(newSquelch.coerceIn(minDB, maxDB))
                }
            }
            return true
        }

        override fun onLongPress(e: MotionEvent) { }
        override fun onFling( e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float ): Boolean { return true }
    }


    init {
        // Add a Callback to get informed when the dimensions of the SurfaceView changes:
        this.holder.addCallback(object: SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                this@AnalyzerSurface.surfaceHolder = holder
                Log.d(LOGTAG, "surfaceCreated")
                // Start drawing thread
                if(drawingThread != null) {
                    Log.w(LOGTAG, "surfaceCreated: drawingThread not null (${drawingThread?.name}). state: ${drawingThread?.running}. join()..")
                    drawingThread?.running = false
                    drawingThread?.join()
                }
                drawingThread = DrawThread()
                drawingThread?.start()
            }

            /**
             * SurfaceHolder.Callback function. This is called every time the dimension changes
             * (and after the SurfaceView is created).
             *
             * @param holder    reference to the surface holder
             * @param format
             * @param width        current width of the surface view
             * @param height    current height of the surface view
             */
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                this@AnalyzerSurface.surfaceHolder = holder
                if (this@AnalyzerSurface.width != width || this@AnalyzerSurface.height != height) {
                    this@AnalyzerSurface.width = width
                    this@AnalyzerSurface.height = height
                    drawingThread?.apply { updateFftPaint() }
                    drawingThread?.apply { updateTextPaint() }
                    debouncedLabelUpdate()
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(LOGTAG, "surfaceDestroyed")
                if(drawingThread != null) {
                    drawingThread?.running = false
                    drawingThread?.interrupt()
                    drawingThread?.join()
                    Log.d(LOGTAG, "surfaceDestroyed: drawingThread ${drawingThread?.name} joined.")
                    drawingThread = null
                }
            }
        })

        // Instantiate the (scale-) gesture detectors:
        this.scaleGestureDetector = ScaleGestureDetector(context, scaleGestureListener)
        this.gestureDetector = GestureDetector(context, gestureListener)
        this.gestureDetector.setIsLongpressEnabled(false)

        // react to changes of the view model:
        observeAppState()
    }

    private fun observeAppState() {
        fun updateViewport() {
            // if viewport is out of range of the source's new frequency and samplerate, we try to fix it
            // a file source has no sample rate until a file is opened: nothing to fit the viewport to
            if (sourceSampleRate.value <= 0) return
            // if the virtual sample rate is larger than the source sample rate, or 0 (stored while
            // such a file source was selected), we reset it:
            if (viewportSampleRate.value <= 0 || viewportSampleRate.value > sourceSampleRate.value) {
                analyzerSurfaceActions.onViewportSampleRateChanged(sourceSampleRate.value)
            }
            // if the viewport is completely outside of the actual signal, we reset freq and sample rate:
            if (viewportStartFrequency.value > sourceSignalEndFrequency.value || viewportEndFrequency.value < sourceSignalStartFrequency.value) {
                analyzerSurfaceActions.onViewportFrequencyChanged(sourceFrequency.value)
                if (viewportSampleRate.value > sourceSampleRate.value)
                    analyzerSurfaceActions.onViewportSampleRateChanged(sourceSampleRate.value)
            }
            // if the viewport reaches past the right end of the signal we shift it left:
            if (viewportEndFrequency.value > sourceSignalEndFrequency.value) {
                val sef = sourceSignalEndFrequency.value
                val sf  = sourceFrequency.value
                val newViewportFrequency = sef - viewportSampleRate.value / 2
                analyzerSurfaceActions.onViewportFrequencyChanged(newViewportFrequency)
            }
            // if the viewport reaches past the left end of the signal we shift it right:
            if (viewportStartFrequency.value < sourceSignalStartFrequency.value) {
                val newViewportFrequency = sourceSignalStartFrequency.value + viewportSampleRate.value / 2
                analyzerSurfaceActions.onViewportFrequencyChanged(newViewportFrequency)
            }
        }
        coroutineScope.collectAppState(sourceFrequency) { updateViewport() }
        coroutineScope.collectAppState(sourceSampleRate) { updateViewport() }
        coroutineScope.collectAppState(fftWaterfallRatio) {
            drawingThread?.apply { updateFftPaint() }
            debouncedLabelUpdate()
        }
        coroutineScope.collectAppState(fontSize) {
            drawingThread?.apply { updateTextPaint() }
            debouncedLabelUpdate()
        }
        coroutineScope.collectAppState(demodulationEnabled) { debouncedLabelUpdate() }
        coroutineScope.collectAppState(sourceName) { debouncedLabelUpdate() }
        coroutineScope.collectAppState(waterfallColorMap) { drawingThread?.createWaterfallColorMap(it) }
        coroutineScope.collectAppState(fftDrawingType) { drawingThread?.apply { updateFftPaint() } }
        coroutineScope.collectAppState(squelchSatisfied) { drawingThread?.squelchPaint?.color = if(it) Color.GREEN else Color.RED }
        coroutineScope.launch {
            stationListFlow.collect { newStationList ->
                stationList = newStationList
                debouncedLabelUpdate()
            }
        }
        coroutineScope.launch {
            bandListFlow.collect { newBandList ->
                bandList = newBandList
                debouncedLabelUpdate()
            }
        }
        coroutineScope.launch {
            demodStatusFlow.collect { newDemodStatus ->
                demodStatus = newDemodStatus
            }
        }
        coroutineScope.launch {
            demodPacketCountsFlow.collect { newCounts ->
                demodPacketCounts = newCounts
            }
        }
        coroutineScope.launch {
            extensionConnectionStateFlow.collect { newState ->
                extensionConnectionState = newState
            }
        }
        coroutineScope.collectAppState(displayStationsInFft) { debouncedLabelUpdate() }
        coroutineScope.collectAppState(displayBandsInFft) { debouncedLabelUpdate() }
        coroutineScope.collectAppState(viewportFrequency) { debouncedLabelUpdate() }
        coroutineScope.collectAppState(viewportSampleRate) { debouncedLabelUpdate() }
        coroutineScope.collectAppState(channelWidth) { newChannelWidth ->
            // need to update in case the channelwidth is changed externally during drag
            channelWidthDragStartValue = newChannelWidth
        }
    }

    private val smallTextSize: Float // the top-right overlay's and the frequency grid's text size
        get() = gridSize * when (fontSize.value) {
            FontSize.SMALL -> 0.13f
            FontSize.NORMAL -> 0.2f
            FontSize.LARGE -> 0.25f
        }

    // Station labels stack upwards from stationLabelBaseY to the top of the FFT, band labels upwards
    // from bandLabelBaseY (above the frequency grid) towards stationLabelBaseY. Each gets as many rows
    // as fit into its part of the FFT, at least one.
    private val stationLabelBaseY: Float
        get() = fftHeight * 0.45f
    private val stationLabelTopY: Float
        get() = height * 0.01f
    private val bandLabelBaseY: Float
        get() = fftHeight - gridSize * 0.7f

    private fun stationLabelRowHeight(textSize: Float) = textSize + STATION_LABEL_BOX_PADDING + STATION_LABEL_ROW_GAP
    private fun bandLabelBoxHeight(size: FontSize) = 4f + size.bandLabelFontSize + 12f + size.subBandLabelFontSize

    private fun labelRows(space: Float, rowHeight: Float) = (space / rowHeight).toInt().coerceIn(1, MAX_LABEL_ROWS)

    // The overlay's right-aligned lines at the top (TopRightOverlayRenderer): the source name, and while
    // demodulating the channel frequency and the signal meter below it. A station label row reaching
    // into them ends left of them.
    private fun stationLabelRightLimit(rowTop: Float, rowBottom: Float): Float {
        val lineHeight = smallTextSize * 1.1f
        val paint = Paint().apply { textSize = smallTextSize }
        var widest = 0f
        if (rowTop < stationLabelTopY + lineHeight)
            widest = paint.measureText(sourceName.value)
        if (demodulationEnabled.value && rowTop < stationLabelTopY + 3 * lineHeight && rowBottom > stationLabelTopY + lineHeight)
            widest = maxOf(widest, paint.measureText("demod at 0000.000000 MHz"))
        return if (widest == 0f) Float.MAX_VALUE else width * 0.99f - widest - 10f
    }

    private fun updateStationLabels() {
        val drawingThread = drawingThread
        val stationList = stationList
        if (stationList != null && displayStationsInFft.value) {
            val textSize = fontSize.value.stationLabelFontSize
            val newStationLabels = layoutStationsForFft(
                stations = stationList,
                layerCount = labelRows(stationLabelBaseY - stationLabelTopY, stationLabelRowHeight(textSize)),
                textSize = textSize
            )
            stationLabels = newStationLabels
            drawingThread?.stationListHasChanged = true
        }
    }
    private fun updateBandLabels() {
        val drawingThread = drawingThread
        val bands = bandList

        if (bands != null && displayBandsInFft.value) {
            val newLabels = layoutBandsForFft(
                bands = bands,
                layerCount = labelRows(bandLabelBaseY - stationLabelBaseY, bandLabelBoxHeight(fontSize.value) + BAND_LABEL_ROW_GAP),
                textSize = fontSize.value.bandLabelFontSize
            )
            bandLabels = newLabels
            drawingThread?.bandListHasChanged = true
        }
    }


    /**
     * Will cause the surface to automatically adjust the dB scale at the
     * next call of draw() so that it fits the incoming fft samples perfectly
     */
    fun autoscale() {
        this.doAutoscaleInNextDraw = true
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        touchEventCount++ // the idle draw loop redraws on interaction (see idleFrameSignature)
        // A second finger touching down while quick-tune is active falls back to pinch-zoom:
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && quickTuneActive) {
            deactivateQuickTune()
        }

        // Start timer for long press on down press:
        if (event.action == MotionEvent.ACTION_DOWN) {
            pressedLabel = findStationLabelAt(event.x, event.y)
            longPressTriggered = false
            pressedLabel?.let {
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_THRESHOLD) // Schedule long press if down on label
            }
            quickTuneDownX = event.x
            quickTuneDownY = event.y
        }

        // If finger moved off label -> cancel long press
        if (event.action == MotionEvent.ACTION_MOVE) {
            if (pressedLabel != null && !isInsideStationLabel(event.x, event.y, pressedLabel!!)) {
                pressedLabel = null
                cancelLongPressRunnable()
            }
            if (quickTuneArmed) {
                // Cancel the pending long-press if the finger moved too far before it fired:
                val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
                if (abs(event.x - quickTuneDownX) > touchSlop || abs(event.y - quickTuneDownY) > touchSlop) {
                    quickTuneHandler.removeCallbacks(quickTuneArmRunnable)
                    quickTuneArmed = false
                }
            }
            if (quickTuneActive) updateQuickTune(event.x, event.y)
        }

        // Captured before deactivation below: the final ACTION_UP/CANCEL of a quick-tune gesture must
        // not reach gestureDetector either, or it reads as a stray single-tap (it never saw the
        // intervening moves, which quick-tune was suppressing below).
        val endingQuickTuneGesture = (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) && quickTuneActive

        // Reset the stroke width of the channel controls if the user lifts his finger:
        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            drawingThread?.squelchPaint?.strokeWidth = STROKE_WIDTH_SQUELCH
            drawingThread?.channelSelectorPaint?.strokeWidth = STROKE_WIDTH_CHANNELSELECTOR
            drawingThread?.channelWidthSelectorPaint?.strokeWidth = STROKE_WIDTH_CHANNELWIDTHSELECTOR
            scrollType = ScrollType.NONE
            deactivateQuickTune()
        }

        var retVal = true
        if (!quickTuneActive && !endingQuickTuneGesture) {
            retVal = scaleGestureDetector.onTouchEvent(event)
            retVal = gestureDetector.onTouchEvent(event) || retVal
        }

        // Arm quick-tune only on empty space: not a station label, not a channel/squelch drag handle
        // (scrollType reflects the hit-test gestureListener.onDown() just did above).
        if (event.action == MotionEvent.ACTION_DOWN && pressedLabel == null && scrollType == ScrollType.NORMAL) {
            quickTuneArmed = true
            quickTuneHandler.postDelayed(quickTuneArmRunnable, LONG_PRESS_THRESHOLD)
        }

        return retVal
    }

    // Helper for station/band label rendering
    fun abbreviateTextToFit(
        text: String,
        paint: Paint,
        maxWidth: Float
    ): String {
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        if (paint.measureText(ellipsis) > maxWidth) return "" // cannot show anything

        // binary search for max chars
        var low = 0
        var high = text.length
        var best = ellipsis

        while (low <= high) {
            val mid = (low + high) / 2
            val candidate = text.take(mid) + ellipsis
            if (paint.measureText(candidate) <= maxWidth) {
                best = candidate
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return best
    }

    /**
     * Places station labels in layers within a frequency viewport.
     * If a label doesn't fit in any layer, truncation is attempted.
     * If still not possible → merge.
     */
    private fun layoutStationsForFft(
        stations: List<StationWithBookmarkList>,
        layerCount: Int,
        textSize: Float,
        minTextLength: Int = 4
    ): List<StationLabel> {
        if (stations.isEmpty()) return emptyList()
        val sorted = stations.sortedByDescending { it.station.frequency } // right → left
        val layerLeftEdges = FloatArray(layerCount) { layer ->
            val rowTop = stationLabelBaseY - (layer + 1) * stationLabelRowHeight(textSize)
            stationLabelRightLimit(rowTop, rowBottom = rowTop + textSize + STATION_LABEL_BOX_PADDING)
        }
        val results = mutableListOf<StationLabel>()
        val paint = Paint().apply{ this.textSize = textSize }

        for ((station, bookmarkList) in sorted) {
            // check if station with same name & frequency is already placed; if yes: merge:
            val duplicateStationIdx = results.indexOfFirst { it.name == station.name && it.frequency == station.frequency }
            if (duplicateStationIdx >= 0) {
                val duplicateStation = results[duplicateStationIdx]
                results[duplicateStationIdx] = duplicateStation.copy(
                    stationIds = duplicateStation.stationIds + station.id,
                    mergedCount = duplicateStation.mergedCount + 1
                )
                continue
            }
            val color = when(station.source) {
                SourceProvider.BOOKMARK -> bookmarkList?.color ?: SourceProvider.BOOKMARK.color
                else -> station.source.color
            }
            val xLeft = freqToX(station.frequency)
            val nameFull = station.name.ifBlank { "?" }
            val boxWidth = paint.measureText(nameFull) + 20f
            val xRight = xLeft + boxWidth

            // Try fitting in any layer
            var placed = false
            var bestLayer = -1
            var maxFreeSpace = Float.NEGATIVE_INFINITY

            for (layer in 0 until layerCount) {
                val freeSpace = layerLeftEdges[layer] - xLeft
                if (xRight < layerLeftEdges[layer]) {
                    // fits fully
                    results.add(StationLabel(
                        stationIds = setOf(station.id),
                        name = nameFull,
                        frequency = station.frequency,
                        x = xLeft,
                        widthPx = boxWidth,
                        layer = layer,
                        color = color)
                    )
                    layerLeftEdges[layer] = xLeft - 10f
                    placed = true
                    break
                } else if (freeSpace > maxFreeSpace) {
                    // track layer with most room for possible truncation
                    bestLayer = layer
                    maxFreeSpace = freeSpace
                }
            }

            // Try truncation only in the most spacious layer
            if (!placed && bestLayer >= 0 && maxFreeSpace > 30f) {
                var truncated = nameFull
                var fit = false
                while (truncated.length > minTextLength) {
                    truncated = truncated.dropLast(1)
                    val truncatedName = "$truncated…"
                    val truncatedWidth = paint.measureText(truncatedName) + 20f
                    val xRightTruncated = xLeft + truncatedWidth
                    if (xRightTruncated < layerLeftEdges[bestLayer]) {
                        results.add(
                            StationLabel(
                                stationIds = setOf(station.id),
                                name = truncatedName,
                                frequency = station.frequency,
                                x = xLeft,
                                widthPx = boxWidth,
                                layer = bestLayer,
                                color = color
                            )
                        )
                        layerLeftEdges[bestLayer] = xLeft - 10f
                        fit = true
                        placed = true
                        break
                    }
                }
                if (!fit) placed = false
            }

            // If still not placed → merge
            if (!placed) {
                val last = results.lastOrNull()
                if (last != null) {
                    results[results.lastIndex] = last.copy(
                        stationIds = last.stationIds + station.id,
                        name = "<${last.mergedCount + 1}>…",
                        mergedCount = last.mergedCount + 1
                    )
                }
            }
        }
        return results
    }

    fun layoutBandsForFft(
        bands: List<BandWithBookmarkList>,
        layerCount: Int,
        textSize: Float
    ): List<BandLabel> {
        val raw = bands.map { band ->
            val xStart = freqToX(band.band.startFrequency)
            val xEnd = freqToX(band.band.endFrequency)
            Triple(band, xStart, xEnd)
        }.sortedBy { it.second } // sort by xStart
        val layers = Array(layerCount) { mutableListOf<BandLabel>() }
        val results = mutableListOf<BandLabel>()
        val paint = Paint().apply{ this.textSize = textSize }

        for ((band, xStart, xEnd) in raw) {
            // visible width (clipped)
            val boxWidth = (xEnd - xStart).coerceAtLeast(0f)

            val nameFitted = abbreviateTextToFit(
                text = band.band.name,
                paint = paint,
                maxWidth = boxWidth - 20f   // left/right padding
            )

            // choose smallest free layer
            var layerIndex = 0
            while (layerIndex < layerCount) {
                val layer = layers[layerIndex]
                val collision = layer.any { other ->
                    // interval overlap?
                    val a1 = xStart
                    val a2 = xEnd
                    val b1 = other.xStart
                    val b2 = other.xEnd
                    a1 < b2 && b1 < a2
                }
                if (!collision) break
                layerIndex++
            }
            if (layerIndex == layerCount) continue // skip label if no layer free

            val color = band.bookmarkList?.color ?: Color.DKGRAY

            // subbands:
            val subLabels = band.band.subBands.map { sb ->
                val sx = freqToX(sb.startFrequency)
                val ex = freqToX(sb.endFrequency)
                SubBandLabel(
                    name = sb.name,
                    freqStart = sb.startFrequency,
                    freqEnd = sb.endFrequency,
                    xStart = sx,
                    xEnd = ex
                )
            }


            val label = BandLabel(
                id = band.band.id,
                name = nameFitted,
                freqStart = band.band.startFrequency,
                freqEnd = band.band.endFrequency,
                xStart = xStart,
                xEnd = xEnd,
                layer = layerIndex,
                color = color,
                subBands = subLabels
            )
            layers[layerIndex].add(label)
            results.add(label)
        }

        return results
    }

    private fun isInsideStationLabel(x: Float, y: Float, label: StationLabel): Boolean {
        val textSize = drawingThread?.stationPaint?.textSize ?: fontSize.value.stationLabelFontSize
        val boxHeight = textSize + STATION_LABEL_BOX_PADDING
        val boxTop = stationLabelBaseY - (label.layer + 1) * stationLabelRowHeight(textSize)
        val boxBottom = boxTop + boxHeight
        val boxLeft = label.x
        val boxRight = label.x + label.widthPx
        return x in boxLeft..boxRight && y in boxTop..boxBottom
    }
    private fun findStationLabelAt(x: Float, y: Float): StationLabel? {
        return stationLabels?.firstOrNull { label -> isInsideStationLabel(x, y, label)}
    }

    //
    //  ---- DRAWING -----
    //

    private inner class DrawThread() : Thread() {
        @Volatile
        var running = false

        // Objects for Drawing
        private val bounds = Rect()                             // A bounds object to calculate the box size of text
        private val defaultPaint: Paint = Paint()               // Paint object to draw bitmaps on the canvas
        private val blackPaint: Paint = Paint()                 // Paint object to draw black (erase)
        private val fftPaint: Paint = Paint()                   // Paint object to draw the fft lines
        private val peakHoldPaint: Paint = Paint()              // Paint object to draw the fft peak hold points
        private val textPaint: Paint = Paint()                  // Paint object to draw text on the canvas
        private val textSmallPaint: Paint = Paint()             // Paint object to draw small text on the canvas
        val channelSelectorPaint: Paint = Paint()       // Paint object to draw the area of the channel
        val channelWidthSelectorPaint: Paint = Paint()  // Paint object to draw the borders of the channel
        val squelchPaint: Paint = Paint()               // Paint object to draw the squelch selector
        val stationPaint: Paint = Paint()               // Paint object to draw the station labels
        val bandPaint: Paint = Paint()                  // Paint object to draw the bands
        val subBandPaint: Paint = Paint()               // Paint object to draw the subbands
        val bandBorderPaint: Paint = Paint()            // Paint object to draw the band borders
        val bandPinPaint: Paint = Paint()               // Paint object to draw the band marker pins
        val labelLinePaint = Paint()
        private val quickTuneIndicatorPaint = Paint()   // Paint object to draw the quick tune/zoom gesture circle
        private val topRightOverlayRenderer = TopRightOverlayRenderer(
            textPaint = textPaint,
            textSmallPaint = textSmallPaint,
            defaultPaint = defaultPaint,
            squelchPaint = squelchPaint,
            sourceName = sourceName,
            analyzerRunning = analyzerRunning,
            demodulationEnabled = demodulationEnabled,
            channelFrequency = channelFrequency,
            squelch = squelch,
            viewportVerticalScaleMin = viewportVerticalScaleMin,
            viewportVerticalScaleMax = viewportVerticalScaleMax,
            recordingRunning = recordingRunning,
            squelchSatisfied = squelchSatisfied,
            recordOnlyWhenSquelchIsSatisfied = recordOnlyWhenSquelchIsSatisfied,
            basebandRecordingRunning = basebandRecordingRunning,
            sourceFrequency = sourceFrequency,
            demodulationMode = demodulationMode,
            sourceSampleRate = sourceSampleRate,
            recordingCurrentFileSize = recordingCurrentFileSize,
            showDebugInformation = showDebugInformation,
            demodulationWfmStereoAndRdsEnabled = demodulationWfmStereoAndRdsEnabled,
        )
        private val leftRightArrowDrawable = ContextCompat.getDrawable(context, R.drawable.left_right_arrow_icon)
        private val topBottomArrowDrawable = ContextCompat.getDrawable(context, R.drawable.top_bottom_arrow_icon)
        private var fftPath: Path = Path()                  // Path object to draw the fft lines
        private lateinit var waterfallColorMapArray: IntArray    // Colors used to draw the waterfall plot.
        private var fftGridBitmap: Bitmap? = null           // bitmap caching the power and frequency grid of the fft
        private var fftGridCanvas: Canvas? = null           // canvas for drawing onto the fftGridBitmap
        private val fftGridBitmapState: MutableMap<String, Any> = HashMap() // Hashmap to track all relevant variables for the grid
                                                                            // (stores all variables which influence the grid at the time the bitmap was drawn)
        // The waterfall as a ring of row strips in the order of waterfallBuffer (not on screen):
        // only strips with recomputed rows are converted and re-uploaded to the GPU each frame
        // (one bitmap for all rows would be 1-2 MB per frame), draw() places them scrolled.
        private var waterfallStrips: Array<Bitmap>? = null
        private var waterfallStripDirty: BooleanArray? = null
        private var waterfallRows = 0                // rows in the waterfall ring (waterfallBuffer.size)
        private var waterfallTopRow = 0              // ring index of the newest row (shown at the top)
        private var waterfallTopRowTimeNs = 0L       // when that row arrived, and the row period:
        private var waterfallRowPeriodNs = 0L        // draw() scrolls by the elapsed part of a row
        // drawPreprocessing recomputes only a few rows per frame: rows still waiting after a full
        // invalidation (new surface, pan, zoom, scale, color map) keep the stopped draw loop going
        private var waterfallRowsPending = false
        private var timeAverageSamples: FloatArray? = null // Used to calculate the average of multiple values in the past 'averagingLength' fft sample rows
        private var peaksYCoordinates: FloatArray? = null // peak hold y-coordinates
        private var colorBuffer: IntArray? = null // Preallocated color array
        private var lastMinDB: Float = 0f
        private var lastMaxDB: Float = 0f
        private var lastViewportFrequency: Long = 0
        private var lastViewportSampleRate: Long = 0
        var stationListHasChanged = true   // indicates whether the station labels in the AnalyzerSurface have been changed recently and need to be redrawn
        var bandListHasChanged = true

        init {
            blackPaint.apply { color = Color.BLACK }
            fftPaint.apply { color = Color.BLUE }
            peakHoldPaint.apply {
                color = Color.YELLOW
                style = Paint.Style.FILL
                strokeWidth = 2f
            }
            textPaint.apply {
                color = Color.WHITE
                isAntiAlias = true
            }
            textSmallPaint.apply {
                color = Color.WHITE
                isAntiAlias = true
            }
            channelSelectorPaint.apply { color = Color.YELLOW }
            channelWidthSelectorPaint.apply { color = Color.WHITE }
            squelchPaint.apply { color = if (squelchSatisfied.value) Color.GREEN else Color.RED }
            stationPaint.apply {
                color = Color.WHITE
                isAntiAlias = true
            }
            bandPaint.apply {
                color = Color.WHITE
                isAntiAlias = true
            }
            subBandPaint.apply {
                color = Color.WHITE
                isAntiAlias = true
            }
            bandBorderPaint.apply {
                color = Color.BLACK
                style = Paint.Style.STROKE
                strokeWidth = 1.5f
                isAntiAlias = true
            }
            bandPinPaint.apply {
                color = Color.WHITE
                strokeWidth = 2f
                isAntiAlias = true
            }
            labelLinePaint.apply {
                style = Paint.Style.STROKE
                color = Color.GRAY
                strokeWidth = 2f
            }
            quickTuneIndicatorPaint.apply {
                style = Paint.Style.STROKE
                color = Color.WHITE
                strokeWidth = 3f
                isAntiAlias = true
                alpha = 0xA0
            }

            // Create the color map for the waterfall plot
            this.createWaterfallColorMap(waterfallColorMap.value)
            updateFftPaint()
            updateTextPaint()
        }

        override fun run() {
            running = true
            this.setName("Thread-DrawThread-" + System.currentTimeMillis())
            Log.i(LOGTAG, "DrawThread started. (Thread: " + this.name + ")")

            try {

                var frameRateTimestamp = System.currentTimeMillis()
                var frameRateFrameCounter = 0
                var frameRate = 0
                var didDrawWatermarkOnce = false
                var oldLicense = license.value
                var lastIdleSignature: List<Any?>? = null
                var lastIdleDrawMs = 0L

                while (running) {
                    val startTimestamp = System.currentTimeMillis()
                    // Stopped: draw only on a change (idleFrameSignature) - redrawing a still picture
                    // at Max Frame Rate would keep the draw and render threads busy.
                    if (!analyzerRunning.value) {
                        val signature = idleFrameSignature()
                        if (signature == lastIdleSignature && !waterfallRowsPending && !doAutoscaleInNextDraw &&
                            startTimestamp - lastIdleDrawMs < IDLE_REDRAW_MS) {
                            sleep(IDLE_POLL_MS)
                            continue
                        }
                        lastIdleSignature = signature
                        lastIdleDrawMs = startTimestamp
                    } else {
                        lastIdleSignature = null
                    }
                    frameRateFrameCounter++
                    if (startTimestamp - frameRateTimestamp > 1000) { // only measure every second
                        frameRate =
                            (frameRateFrameCounter * 1000f / (startTimestamp - frameRateTimestamp)).toInt()
                        frameRateFrameCounter = 0
                        frameRateTimestamp = startTimestamp
                    }

                    // Local current state (better performance, and safer because width/height could change at any time)
                    val width = width
                    val fftHeight = fftHeight

                    if (timeAverageSamples == null || timeAverageSamples!!.size != width)
                        timeAverageSamples = FloatArray(width)

                    if (fftPeakHold.value) {
                        if (peaksYCoordinates == null || peaksYCoordinates!!.size != width)
                            peaksYCoordinates = FloatArray(width)
                    } else {
                        peaksYCoordinates = null
                    }

                    var doDraw = false
                    try {
                        // Write Lock necessary because we write to dirty map!
                        fftProcessorData.lock.writeLock().lock()

                        val waterfallBuffer = fftProcessorData.waterfallBuffer
                        val waterfallBufferDirtyMap = fftProcessorData.waterfallBufferDirtyMap
                        val frequency = fftProcessorData.frequency
                        val sampleRate = fftProcessorData.sampleRate
                        val readIndex = fftProcessorData.readIndex
                        if (waterfallBuffer != null &&
                            waterfallBufferDirtyMap != null &&
                            width > 0 &&
                            frequency != null &&
                            sampleRate != null &&
                            sampleRate != 0L
                        ) {
                            doDraw = true

                            // preprocessing of waterfall data:
                            drawPreprocessing(
                                waterfallBuffer,
                                waterfallBufferDirtyMap,
                                fftProcessorData.peaks,
                                frequency,
                                sampleRate,
                                readIndex,
                                width,
                                fftHeight
                            )
                        }
                    } finally {
                        fftProcessorData.lock.writeLock().unlock()
                    }
                    if (doDraw) {
                        draw(frameRate)
                    } else {
                        val currentLicense = license.value
                        if (!didDrawWatermarkOnce || oldLicense != currentLicense)
                            drawWatermark() // Draw watermark
                        didDrawWatermarkOnce = true
                        oldLicense = currentLicense
                    }

                    // measure current frametime to derive how long we need to sleep to meet the user's preferred framerate
                    val frameDrawingTime = System.currentTimeMillis() - startTimestamp
                    val desiredFrameTimeMs = 1000 / maxFrameRate.value
                    GlobalPerformanceData.updateLoad(
                        "Renderer",
                        frameDrawingTime.toFloat() / desiredFrameTimeMs
                    )
                    val sleepTime = desiredFrameTimeMs - frameDrawingTime
                    sleep(sleepTime.coerceAtLeast(0))
                }
            } catch (_: InterruptedException) {
                Log.d(LOGTAG, "DrawThread interrupted.")
            }
            Log.i(LOGTAG, "DrawThread stopped. (Thread: " + this.name + ")")
        }

        private fun drawPreprocessing(waterfallBuffer: Array<ByteArray>,
                                      waterfallBufferDirtyMap: Array<Boolean>,
                                      peaks: FloatArray?,
                                      frequency: Long,          // center frequency of the fft samples
                                      sampleRate: Long,         // sample rate of the fft samples
                                      currentRowIdx: Int,       // Index of the most recent row in waterfallBuffer
                                      width: Int,
                                      fftHeight: Int
        ) {
            val startTimestamp = System.currentTimeMillis()

            // performance optimization:
            val timeAverageSamples = timeAverageSamples!!
            val waterfallColorMapArray = waterfallColorMapArray
            val colorMapSize = waterfallColorMapArray.size
            val peaksYCoordinates = peaksYCoordinates
            val calcPeaks = peaks != null && peaksYCoordinates != null
            val fftPath = fftPath
            val fftSize = waterfallBuffer[0].size
            val doAutoscale = doAutoscaleInNextDraw

            if(waterfallStrips == null || waterfallStrips!![0].width != width || waterfallRows != waterfallBuffer.size) {
                waterfallRows = waterfallBuffer.size
                val stripCount = (waterfallRows + WATERFALL_STRIP_ROWS - 1) / WATERFALL_STRIP_ROWS
                waterfallStrips = Array(stripCount) { k ->
                    Bitmap.createBitmap(width, min(WATERFALL_STRIP_ROWS, waterfallRows - k * WATERFALL_STRIP_ROWS), Bitmap.Config.ARGB_8888)
                }
                waterfallStripDirty = BooleanArray(stripCount)
                waterfallBufferDirtyMap.fill(true)
            }
            if(colorBuffer == null || colorBuffer!!.size != width * waterfallBuffer.size) {
                colorBuffer = IntArray(width * waterfallBuffer.size)
                waterfallBufferDirtyMap.fill(true)
            }
            for(i in timeAverageSamples.indices) timeAverageSamples[i] = 0f

            // performance optimization:
            val colorBuffer = colorBuffer!!

            val viewportFrequency = viewportFrequency.value
            val viewportSampleRate = viewportSampleRate.value
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            if(viewportFrequency != lastViewportFrequency || viewportSampleRate != lastViewportSampleRate || minDB != lastMinDB || maxDB != lastMaxDB) {
                waterfallBufferDirtyMap.fill(true)
                lastViewportFrequency = viewportFrequency
                lastViewportSampleRate = viewportSampleRate
                lastMinDB = minDB
                lastMaxDB = maxDB
            }

            // Calculate the start and end index to draw mag according to frequency and sample rate and
            // the viewport frequency and sample rate:
            val samplesPerHz = fftSize.toFloat() / sampleRate.toFloat() // indicates how many samples in mag cover 1 Hz
            val frequencyDiff = viewportFrequency - frequency // difference between center frequencies
            val sampleRateDiff = viewportSampleRate - sampleRate // difference between sample rates
            val start = ((frequencyDiff - sampleRateDiff / 2.0) * samplesPerHz).toInt()
            val end = fftSize + ((frequencyDiff + sampleRateDiff / 2.0) * samplesPerHz).toInt()
            val samplesPerPx = (end - start).toFloat() / width.toFloat() // number of fft samples per one pixel
            val dbDiff = maxDB - minDB
            val dbWidth = fftHeight / dbDiff // Size (in pixel) per 1dB in the fft
            val scale = waterfallColorMapArray.size / dbDiff // scale for the color mapping of the waterfall

            val timeAveragingLength = fftAverageLength.value
            var avg: Float              // value of one pixel in mag (maximum of the bins it covers)
            var peakAvg: Float          // value of one pixel in peaks (maximum of the bins it covers)

            // measure the signal min and max for autoscale:
            var minMeasuredSignal = VERTICAL_SCALE_UPPER_BOUNDARY
            var maxMeasuredSignal = VERTICAL_SCALE_LOWER_BOUNDARY
            val black = Color.rgb(0, 0, 0)

            // The start position to draw is either 0 or greater 0, if start is negative.
            // Both pixel bounds are clamped to the screen: if the samples lie far outside a deeply
            // zoomed viewport (e.g. right after a large retune) the raw values overflow Int and the
            // pixel range check below would let fftRow be indexed out of bounds.
            val firstPixel = if (start >= 0) 0 else (-start.toDouble() / samplesPerPx).coerceIn(0.0, width.toDouble()).toInt()

            // We will only draw to the end of mag, not beyond:
            val lastPixel = (if (end >= fftSize) (fftSize.toDouble() - start) / samplesPerPx else (end.toDouble() - start) / samplesPerPx)
                .coerceIn(0.0, width.toDouble()).toInt()

            // Reset fft path and start at the first pixel:
            fftPath.reset()
            fftPath.moveTo(firstPixel.toFloat(), fftHeight.toFloat()) // start graph at the bottom left

            var rowsProcessed = 0
            waterfallRowsPending = false
            for(rowNumber in 0 until waterfallBuffer.size) {
                val bufferIndex = (currentRowIdx + rowNumber) % waterfallBuffer.size // from newest sample to oldest (ringbuffer is ordered in reverse)

                // only process dirty rows and only up to 5 rows each run (to keep the interface snappy and responsive)
                if(!waterfallBufferDirtyMap[bufferIndex] && rowNumber > timeAveragingLength)
                    continue  // current row was already processed and is up to date!
                if(rowsProcessed > timeAveragingLength + 5) { // only process 5 additional dirty rows per run
                    waterfallRowsPending = true
                    break
                }

                val fftRow = waterfallBuffer[bufferIndex]

                // Draw pixel by pixel; the pixels outside firstPixel+1 until lastPixel-1 (integer
                // round-off at the edges) are black:
                for (i in 0 until width) {
                    if(i in (firstPixel + 1)..<lastPixel-1) {
                        // Value for this pixel: the maximum of all bins it covers (like gqrx/SDR++) -
                        // an average would dilute a narrowband signal into the noise when zoomed out.
                        var maxByteVal = 0
                        var peakMax = -Float.MAX_VALUE
                        var j = (i * samplesPerPx).toInt()
                        while (j < (i + 1) * samplesPerPx && (j+start)<fftSize) {
                            val unsignedByteVal = fftRow[j + start].toInt() and 0xFF
                            if (unsignedByteVal > maxByteVal) maxByteVal = unsignedByteVal
                            if (rowNumber == 0 && calcPeaks && peaks[j + start] > peakMax) peakMax = peaks[j + start]
                            j++
                        }
                        avg = maxByteVal * FftProcessor.INV_SCALE + FftProcessor.MIN_DB
                        peakAvg = peakMax
                        if (rowNumber == 0 && calcPeaks) peaksYCoordinates[i] = fftHeight - (peakAvg - minDB) * dbWidth

                        // FFT Path:
                        if(rowNumber <= timeAveragingLength)
                            timeAverageSamples[i] += avg
                        if(rowNumber == timeAveragingLength) {
                            val timeAverage = timeAverageSamples[i] / (timeAveragingLength + 1)
                            fftPath.lineTo( i.toFloat(), (fftHeight - (timeAverage - minDB) * dbWidth))
                            if(doAutoscale) {
                                minMeasuredSignal = min(timeAverage, minMeasuredSignal)
                                maxMeasuredSignal = max(timeAverage, maxMeasuredSignal)
                            }
                        }

                        // Waterfall Color Buffer:
                        val waterfallColorMapIndex = ((avg - minDB) * scale).toInt()
                        colorBuffer[bufferIndex * width + i] = waterfallColorMapArray[if(waterfallColorMapIndex<0) 0 else if(waterfallColorMapIndex>=colorMapSize) colorMapSize-1 else waterfallColorMapIndex]
                    } else {
                        colorBuffer[bufferIndex * width + i] = black
                        if(calcPeaks) peaksYCoordinates[i] = -1f // outside of the frame
                    }
                }
                rowsProcessed += 1
                waterfallBufferDirtyMap[bufferIndex] = false
                waterfallStripDirty!![bufferIndex / WATERFALL_STRIP_ROWS] = true
            }
            val strips = waterfallStrips!!
            val stripDirty = waterfallStripDirty!!
            for (k in strips.indices) {
                if (!stripDirty[k]) continue
                strips[k].setPixels(colorBuffer, k * WATERFALL_STRIP_ROWS * width, width, 0, 0, width, strips[k].height)
                stripDirty[k] = false
            }
            waterfallTopRow = currentRowIdx
            waterfallTopRowTimeNs = fftProcessorData.newestRowTimeNs
            waterfallRowPeriodNs = fftProcessorData.rowPeriodNs
            fftPath.lineTo(lastPixel.toFloat(), fftHeight.toFloat()) // end at the bottom right
            if (doAutoscale && minMeasuredSignal < maxMeasuredSignal) {
                minMeasuredSignal -= 5f // leave a bit room
                maxMeasuredSignal += 5f
                analyzerSurfaceActions.onViewportVerticalScaleChanged(Pair(minMeasuredSignal.coerceAtLeast(VERTICAL_SCALE_LOWER_BOUNDARY), maxMeasuredSignal.coerceAtMost(VERTICAL_SCALE_UPPER_BOUNDARY)))
                doAutoscaleInNextDraw = false
            }
        }

        /**
         * Will (re-)draw the surface.
         *
         * @param frameRate    current frame rate (FPS)
         */
        fun draw(frameRate: Int) {
            if (width <= 0 || fftHeight <= 0) return  // surface too small to draw (createBitmap() would throw)
            // Redraw/recreate the fft grid bitmap if necessary
            if (hasFftGridChanged()) {
                if (fftGridBitmap == null || fftGridBitmap!!.height != fftHeight || fftGridBitmap!!.width != width) {
                    // fft height has changed!
                    fftGridBitmap = Bitmap.createBitmap(width, fftHeight, Bitmap.Config.ARGB_8888)
                    fftGridCanvas = Canvas(fftGridBitmap!!)
                }
                // Clear the bitmap with transparency
                fftGridCanvas!!.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawFrequencyGrid(fftGridCanvas!!)
                drawPowerGrid(fftGridCanvas!!)
                // draw the station/band labels:
                // (the change flags are cleared even when labels are hidden or not computed yet -
                // otherwise the grid is redrawn and re-uploaded on every frame)
                stationListHasChanged = false
                bandListHasChanged = false
                val stationLabels = stationLabels
                if(stationLabels != null && displayStationsInFft.value) {
                    drawStationLabels(fftGridCanvas!!, stationLabels, baseY = fftHeight.toFloat(), stationLayerY = stationLabelBaseY)
                }
                val bandLabels = bandLabels
                if(bandLabels != null && displayBandsInFft.value) {
                    drawBandLabels(fftGridCanvas!!, bandLabels, bandLayerY = bandLabelBaseY)
                }
                if (demodulationEnabled.value) {
                    drawDemodulationChannel(fftGridCanvas!!)
                }
                // save the current state:
                fftGridBitmapState["fftHeight"] = fftHeight
                fftGridBitmapState["fontSize"] = fontSize.value
                fftGridBitmapState["scrollType"] = scrollType
                fftGridBitmapState["virtualSampleRate"] = viewportSampleRate.value
                fftGridBitmapState["virtualFrequency"] = viewportFrequency.value
                fftGridBitmapState["width"] = width
                fftGridBitmapState["displayRelativeFrequencies"] = fftRelativeFrequency.value
                fftGridBitmapState["demodulationEnabled"] = demodulationEnabled.value
                fftGridBitmapState["channelWidth"] = channelWidth.value
                fftGridBitmapState["channelFrequency"] = channelFrequency.value
                fftGridBitmapState["squelchEnabled"] = squelchEnabled.value
                fftGridBitmapState["squelch"] = squelch.value
                fftGridBitmapState["minDB"] = viewportVerticalScaleMin.value
                fftGridBitmapState["maxDB"] = viewportVerticalScaleMax.value
                fftGridBitmapState["squelchPaintStrokeWidth"] = squelchPaint.strokeWidth
                fftGridBitmapState["squelchPaintColor"] = squelchPaint.color
                fftGridBitmapState["channelSelectorStrokeWidth"] = channelSelectorPaint.strokeWidth
                fftGridBitmapState["channelWidthSelectorStrokeWidth"] = channelWidthSelectorPaint.strokeWidth
                fftGridBitmapState["showUpperBand"] = demodulationMode.value != DemodulationMode.USB && demodulationMode.value != DemodulationMode.FT8
                fftGridBitmapState["showLowerBand"] = demodulationMode.value != DemodulationMode.LSB
                fftGridBitmapState["displayStationsInFft"] = displayStationsInFft.value
                fftGridBitmapState["displayBandsInFft"] = displayBandsInFft.value
            }

            // Draw:
            var c: Canvas? = null
            try {
                if (!running) return  // make sure not to try to lock the canvas if we should exit..
                if (!holder.surface.isValid) return
                c = holder.lockHardwareCanvas()
                if (c != null) {
                    // Clear the canvas:
                    c.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
                    // Draw FFT:
                    c.drawPath(fftPath, fftPaint)
                    // Draw peaks
                    if (peaksYCoordinates != null)
                        for(i in 0 until width)
                            c.drawPoint(i.toFloat(), peaksYCoordinates!![i], peakHoldPaint)
                    // Draw waterfall (scale to fit waterfallHeight)
                    drawWaterfall(c)
                    // Draw Grid (with channel selector)
                    c.drawBitmap(fftGridBitmap!!, 0f, 0f, null)

                    // draw channel frequency line into waterfall:
                    if (demodulationEnabled.value) {
                        val pxPerHz = width / viewportSampleRate.value.toFloat()
                        val channelPosition = width / 2 + pxPerHz * (channelFrequency.value - viewportFrequency.value)
                        c.drawLine(channelPosition, fftHeight.toFloat(), channelPosition, height.toFloat(), channelSelectorPaint)
                    }

                    topRightOverlayRenderer.draw(c, width, height, frameRate, averageSignalStrength.value, demodStatus, demodPacketCounts, extensionConnectionState)

                    drawQuickTuneIndicator(c)
                } else Log.d(LOGTAG, "draw: Canvas is null.")
            } catch (e: Exception) {
                Log.e(LOGTAG, "draw: Error while drawing on the canvas. Stop!")
                e.printStackTrace()
            } finally {
                if (c != null) {
                    holder.unlockCanvasAndPost(c)
                }
            }
        }

        /**
         * Draws the waterfall strips scaled into the area below the FFT, newest row at the top.
         * The rows move down continuously by the elapsed fraction of a row period since the newest
         * row arrived (it slides in from above): FFT rows and drawn frames come from two clocks,
         * so whole-row steps alone give frames without movement followed by double steps.
         */
        private fun drawWaterfall(c: Canvas) {
            val strips = waterfallStrips ?: return
            val rows = waterfallRows
            val rowHeight = (height - fftHeight).toFloat() / rows
            val elapsedRows = if (waterfallRowPeriodNs > 0)
                ((System.nanoTime() - waterfallTopRowTimeNs).toFloat() / waterfallRowPeriodNs).coerceIn(0f, 1f) else 1f
            waterfallScrollOffset = (elapsedRows - 1f) * rowHeight
            c.save()
            c.clipRect(0, fftHeight, width, height)
            for (k in strips.indices) {
                val strip = strips[k]
                val firstRow = k * WATERFALL_STRIP_ROWS
                // display position of the strip's first row; a strip holding the newest row wraps
                val displayRow = (firstRow - waterfallTopRow + rows) % rows
                val rowsBeforeWrap = min(strip.height, rows - displayRow)
                drawWaterfallRows(c, strip, 0, rowsBeforeWrap, displayRow, rowHeight)
                if (rowsBeforeWrap < strip.height)
                    drawWaterfallRows(c, strip, rowsBeforeWrap, strip.height, 0, rowHeight)
            }
            c.restore()
        }

        private var waterfallScrollOffset = 0f

        private val waterfallSrcRect = Rect()
        private val waterfallDstRect = RectF()
        private fun drawWaterfallRows(c: Canvas, strip: Bitmap, fromRow: Int, toRow: Int, displayRow: Int, rowHeight: Float) {
            waterfallSrcRect.set(0, fromRow, strip.width, toRow)
            val top = fftHeight + displayRow * rowHeight + waterfallScrollOffset
            waterfallDstRect.set(0f, top, width.toFloat(), top + (toRow - fromRow) * rowHeight)
            c.drawBitmap(strip, waterfallSrcRect, waterfallDstRect, null)
        }

        /**
         * checks current state vs old state (fftGridBitmapState)
         * @return true if the fft grid bitmap must be redrawn
         */
        private fun hasFftGridChanged(): Boolean {
            if (!fftGridBitmapState.containsKey("fftHeight"))  // test if hashmap was initialized with values already
                return true
            if (stationListHasChanged)
                return true
            return !(fftGridBitmapState["fftHeight"] == fftHeight &&
                    fftGridBitmapState["fontSize"] == fontSize.value &&
                    fftGridBitmapState["scrollType"] == scrollType &&
                    fftGridBitmapState["virtualSampleRate"] ==viewportSampleRate.value &&
                    fftGridBitmapState["virtualFrequency"] == viewportFrequency.value &&
                    fftGridBitmapState["width"] == width &&
                    fftGridBitmapState["displayRelativeFrequencies"] == fftRelativeFrequency.value &&
                    fftGridBitmapState["demodulationEnabled"] == demodulationEnabled.value &&
                    fftGridBitmapState["channelWidth"] == channelWidth.value &&
                    fftGridBitmapState["channelFrequency"] == channelFrequency.value &&
                    fftGridBitmapState["squelchEnabled"] == squelchEnabled.value &&
                    fftGridBitmapState["squelch"] == squelch.value &&
                    fftGridBitmapState["minDB"] == viewportVerticalScaleMin.value &&
                    fftGridBitmapState["maxDB"] == viewportVerticalScaleMax.value &&
                    fftGridBitmapState["squelchPaintStrokeWidth"] == squelchPaint.strokeWidth &&
                    fftGridBitmapState["squelchPaintColor"] == squelchPaint.color &&
                    fftGridBitmapState["channelSelectorStrokeWidth"] == channelSelectorPaint.strokeWidth &&
                    fftGridBitmapState["channelWidthSelectorStrokeWidth"] == channelWidthSelectorPaint.strokeWidth &&
                    fftGridBitmapState["showUpperBand"] == (demodulationMode.value != DemodulationMode.USB && demodulationMode.value != DemodulationMode.FT8) &&
                    fftGridBitmapState["showLowerBand"] == (demodulationMode.value != DemodulationMode.LSB) &&
                    fftGridBitmapState["displayStationsInFft"] == displayStationsInFft.value &&
                    fftGridBitmapState["displayBandsInFft"] == displayBandsInFft.value
                )
        }

        /**
         * This method will draw the frequency grid into the canvas
         *
         * @param c                canvas of the surface view
         */
        private fun drawFrequencyGrid(c: Canvas) {
            val showUpperBand = demodulationMode.value != DemodulationMode.LSB
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            val virtualFrequency = viewportFrequency.value
            val virtualSampleRate = viewportSampleRate.value
            var textStr: String
            val MHZ = 1000000.0
            var tickFreqMHz: Double
            var lastTextEndPos = -99999f // will indicate the horizontal pixel pos where the last text ended
            var textPos: Float

            // Calculate the min space (in px) between text if we want it separated by at least
            // the same space as two dashes would consume.
            textPaint.getTextBounds("--", 0, 2, bounds)
            val minFreeSpaceBetweenText = bounds.width().toFloat()

            // Calculate span of a minor tick (a power of 10, in Hz)
            var tickSize = 10 // we start with 10 Hz
            var helperVar = virtualSampleRate / 20f
            while (helperVar > 100) {
                helperVar = helperVar / 10f
                tickSize = tickSize * 10
            }

            // Calculate pixel width of a minor tick
            val pixelPerMinorTick = width / (virtualSampleRate / tickSize.toFloat())

            // Calculate the frequency at the left most point of the fft:
            val startFrequency =
                if (fftRelativeFrequency.value) ((virtualFrequency-sourceFrequency.value) - (virtualSampleRate / 2.0)).toLong()
                else (virtualFrequency - (virtualSampleRate / 2.0)).toLong()

            // Calculate the frequency and position of the first Tick (ticks are every <tickSize> Hz)
            var tickFreq = (ceil(startFrequency.toDouble() / tickSize.toFloat()) * tickSize).toLong()
            var tickPos = pixelPerMinorTick / tickSize.toFloat() * (tickFreq - startFrequency)

            // Draw the ticks
            var i = 0
            while (i < virtualSampleRate / tickSize.toFloat()) {
                var tickHeight: Float
                if (tickFreq % (tickSize * 10) == 0L) {
                    // Major Tick (10x <tickSize> Hz)
                    tickHeight = (gridSize / 2.0).toFloat()

                    // Draw Frequency Text (always in MHz)
                    tickFreqMHz = tickFreq / MHZ
                    textStr = if (tickFreqMHz == tickFreqMHz.toInt().toDouble()) String.format("%d", tickFreqMHz.toInt()) else String.format("%s", tickFreqMHz)
                    textPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                    textPos = tickPos - bounds.width() / 2

                    // ...only if not overlapping with the last text:
                    if (lastTextEndPos + minFreeSpaceBetweenText < textPos) {
                        c.drawText(textStr, textPos, fftHeight - tickHeight, textPaint)
                        lastTextEndPos = textPos + bounds.width()
                    }
                } else if (tickFreq % (tickSize * 5) == 0L) {
                    // Half major tick (5x <tickSize> Hz)
                    tickHeight = (gridSize / 3.0).toFloat()

                    // Draw Frequency Text (always in MHz)...
                    tickFreqMHz = tickFreq / MHZ
                    textStr = if (tickFreqMHz == tickFreqMHz.toInt().toDouble()) String.format("%d", tickFreqMHz.toInt()) else String.format("%s", tickFreqMHz)
                    textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                    textPos = tickPos - bounds.width() / 2

                    // ...only if not overlapping with the last text:
                    if (lastTextEndPos + minFreeSpaceBetweenText < textPos) {
                        // ... if enough space between the major ticks:
                        if (bounds.width() < pixelPerMinorTick * 3) {
                            c.drawText(textStr, textPos, fftHeight - tickHeight, textSmallPaint)
                            lastTextEndPos = textPos + bounds.width()
                        }
                    }
                } else {
                    // Minor tick (<tickSize> Hz)
                    tickHeight = (gridSize / 4.0).toFloat()
                }

                // Draw the tick line:
                c.drawLine(
                    tickPos, fftHeight.toFloat(), tickPos, fftHeight - tickHeight,
                    textPaint
                )
                tickFreq += tickSize.toLong()
                tickPos += pixelPerMinorTick
                i++
            }
        }

        /**
         * Draws the quick tune/zoom indicator while quick-tune is active. Before an axis is
         * engaged, shows just the ring plus a direction hint for each axis. Once the finger leaves
         * the ring, the ring and the other axis's hints disappear and the engaged axis's full
         * indicator (with a marker for the current position) takes their place.
         */
        private fun drawQuickTuneIndicator(c: Canvas) {
            if (!quickTuneActive) return
            val cx = quickTuneCenterX
            val cy = quickTuneCenterY
            val density = resources.displayMetrics.density
            val dotRadiusPx = QUICK_TUNE_CENTER_DOT_RADIUS_DP * density

            when (quickTuneEngagedAxis) {
                null -> {
                    val ringRadiusPx = QUICK_TUNE_RING_RADIUS_DP * density
                    val hintDistancePx = ringRadiusPx + QUICK_TUNE_HINT_LABEL_MARGIN_DP * density
                    drawCenteredText(c, "Zoom In", cx, cy - hintDistancePx, alignBottom = true)
                    drawCenteredText(c, "Zoom Out", cx, cy + hintDistancePx, alignBottom = false)
                    if (quickTuneTuneAxisAvailable) {
                        drawCenteredText(c, "◄ Tune", cx - hintDistancePx, cy, alignRight = true)
                        drawCenteredText(c, "Tune ►", cx + hintDistancePx, cy, alignRight = false)
                    }
                    c.drawCircle(cx, cy, ringRadiusPx, quickTuneIndicatorPaint)
                }
                QuickTuneAxis.VERTICAL -> {
                    val labelMarginPx = QUICK_TUNE_LABEL_MARGIN_DP * density
                    c.drawLine(cx, cy - quickTuneZoomTopLengthPx, cx, cy + quickTuneZoomBottomLengthPx, quickTuneIndicatorPaint)
                    drawCenteredText(c, "Zoom In", cx, cy - quickTuneZoomTopLengthPx - labelMarginPx, alignBottom = true)
                    drawCenteredText(c, "Zoom Out", cx, cy + quickTuneZoomBottomLengthPx + labelMarginPx, alignBottom = false)
                    val markerY = cy + (quickTuneCurrentY - cy).coerceIn(-quickTuneZoomTopLengthPx, quickTuneZoomBottomLengthPx)
                    c.drawCircle(cx, markerY, dotRadiusPx / 2f, quickTuneIndicatorPaint)
                }
                QuickTuneAxis.HORIZONTAL -> {
                    val labelMarginPx = QUICK_TUNE_LABEL_MARGIN_DP * density
                    val tuneRangePx = QUICK_TUNE_FINE_TUNE_STEP_COUNT * QUICK_TUNE_FINE_TUNE_STEP_SPACING_DP * density
                    drawFineTuneTicks(c, cx, cy, density)
                    drawCenteredText(c, "◄ Tune", cx - tuneRangePx - labelMarginPx, cy, alignRight = true)
                    drawCenteredText(c, "Tune ►", cx + tuneRangePx + labelMarginPx, cy, alignRight = false)
                    drawFineTuneMarker(c, cx, cy, density)
                }
            }
            c.drawCircle(cx, cy, dotRadiusPx, quickTuneIndicatorPaint)
        }

        /** Tick mark at each discrete fine-tune step, tall enough to peek out from under the fingertip. */
        private fun drawFineTuneTicks(c: Canvas, cx: Float, cy: Float, density: Float) {
            val stepPx = QUICK_TUNE_FINE_TUNE_STEP_SPACING_DP * density
            val tickHalfHeight = QUICK_TUNE_FINE_TUNE_TICK_HALF_HEIGHT_DP * density
            for (i in -QUICK_TUNE_FINE_TUNE_STEP_COUNT..QUICK_TUNE_FINE_TUNE_STEP_COUNT) {
                if (i == 0) continue
                val tx = cx + i * stepPx
                c.drawLine(tx, cy - tickHalfHeight, tx, cy + tickHalfHeight, quickTuneIndicatorPaint)
            }
        }

        /** Taller, thicker tick marking the currently selected fine-tune step, so it stands out from the regular ticks. */
        private fun drawFineTuneMarker(c: Canvas, cx: Float, cy: Float, density: Float) {
            val stepPx = QUICK_TUNE_FINE_TUNE_STEP_SPACING_DP * density
            val stepIndex = ((quickTuneCurrentX - cx) / stepPx).roundToInt().coerceIn(-QUICK_TUNE_FINE_TUNE_STEP_COUNT, QUICK_TUNE_FINE_TUNE_STEP_COUNT)
            val markerX = cx + stepIndex * stepPx
            val markerHalfHeight = QUICK_TUNE_FINE_TUNE_MARKER_HALF_HEIGHT_DP * density
            val originalStrokeWidth = quickTuneIndicatorPaint.strokeWidth
            quickTuneIndicatorPaint.strokeWidth = originalStrokeWidth * 2.5f
            c.drawLine(markerX, cy - markerHalfHeight, markerX, cy + markerHalfHeight, quickTuneIndicatorPaint)
            quickTuneIndicatorPaint.strokeWidth = originalStrokeWidth
        }

        /**
         * Draws [textStr] horizontally centered on [x], and vertically anchored above/below or
         * left/right of ([x], [y]) depending on [alignBottom]/[alignRight].
         */
        private fun drawCenteredText(c: Canvas, textStr: String, x: Float, y: Float, alignBottom: Boolean? = null, alignRight: Boolean? = null) {
            textPaint.getTextBounds(textStr, 0, textStr.length, bounds)
            val drawX = (if (alignRight == true) x - bounds.width() else if (alignRight == false) x else x - bounds.width() / 2f)
                .coerceIn(0f, (width - bounds.width()).toFloat().coerceAtLeast(0f)) // whole on screen, also at its edges
            val drawY = if (alignBottom == true) y else if (alignBottom == false) y + bounds.height() else y + bounds.height() / 2f
            c.drawText(textStr, drawX, drawY, textPaint)
        }

        private fun drawDemodulationChannel(c: Canvas) {
            val showUpperBand = demodulationMode.value != DemodulationMode.LSB
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            val virtualFrequency = viewportFrequency.value
            val virtualSampleRate = viewportSampleRate.value
            var textStr: String
            val squelchEnabled = squelchEnabled.value
            val pxPerHz = width / virtualSampleRate.toFloat()
            val channelPosition = width / 2 + pxPerHz * (channelFrequency.value - virtualFrequency)
            val leftBorder = width / 2 + pxPerHz * (channelStartFrequency.value - virtualFrequency)
            val rightBorder = width / 2 + pxPerHz * (channelEndFrequency.value - virtualFrequency)
            val dbWidth = fftHeight / (maxDB - minDB)
            val squelchYPosition = if(squelchEnabled) fftHeight - (squelch.value - minDB) * dbWidth else fftHeight.toFloat()
            val squelchXPosition = if(showUpperBand) rightBorder else leftBorder
            val (centerDragHandlePositionDb, outerDragHandlePositionDb) = channelSelectorDragHandlePosition
            val centerDragHandleYPosition = fftHeight - (centerDragHandlePositionDb-minDB)*dbWidth
            val outerDragHandleYPosition = fftHeight - (outerDragHandlePositionDb-minDB)*dbWidth

            // draw half transparent channel area:
            channelWidthSelectorPaint.alpha = 0x40
            c.drawRect(leftBorder, 0f, rightBorder, squelchYPosition, channelWidthSelectorPaint)
            channelWidthSelectorPaint.alpha = 0xff

            // draw outer borders
            c.drawLine(leftBorder, fftHeight.toFloat(), leftBorder, 0f, channelWidthSelectorPaint)
            c.drawLine(rightBorder, fftHeight.toFloat(), rightBorder, 0f, channelWidthSelectorPaint)
            // draw center
            c.drawLine(channelPosition, fftHeight.toFloat(), channelPosition, 0f,channelSelectorPaint)

            // draw channel drag handle
            val dragHandleBounds = RectF(channelPosition - 30f, centerDragHandleYPosition - 15f, channelPosition + 30f, centerDragHandleYPosition + 15f)
            c.drawOval(dragHandleBounds, channelSelectorPaint)
            leftRightArrowDrawable?.bounds = Rect(dragHandleBounds.left.toInt() + 5, dragHandleBounds.top.toInt() + 5, dragHandleBounds.right.toInt() - 5, dragHandleBounds.bottom.toInt() - 5)
            leftRightArrowDrawable?.draw(c)

            // draw channel width drag handle
            val channelWidthHandleBounds = RectF(squelchXPosition - 30f, outerDragHandleYPosition - 15f, squelchXPosition + 30f, outerDragHandleYPosition + 15f)
            c.drawOval(channelWidthHandleBounds, channelWidthSelectorPaint)
            leftRightArrowDrawable?.bounds = Rect(channelWidthHandleBounds.left.toInt() + 5, channelWidthHandleBounds.top.toInt() + 5, channelWidthHandleBounds.right.toInt() - 5, channelWidthHandleBounds.bottom.toInt() - 5)
            leftRightArrowDrawable?.draw(c)

            // draw squelch
            if (squelchEnabled) {
                c.drawLine(leftBorder, squelchYPosition, rightBorder, squelchYPosition, squelchPaint)

                // draw squelch text above the squelch selector:
                textStr = String.format("+%.0f dB", squelchMargin.value)
                textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                c.drawText(textStr, channelPosition - bounds.width() / 2f, squelchYPosition - bounds.height() * 0.1f, textSmallPaint)

                // draw drag handle on squelch
                val squelchHandleBounds = RectF(squelchXPosition - 15f, squelchYPosition - 30f, squelchXPosition + 15f, squelchYPosition + 30f)
                c.drawOval(squelchHandleBounds, squelchPaint)
                topBottomArrowDrawable?.bounds = Rect(squelchHandleBounds.left.toInt() + 5, squelchHandleBounds.top.toInt() + 5, squelchHandleBounds.right.toInt() - 5, squelchHandleBounds.bottom.toInt() - 5)
                topBottomArrowDrawable?.draw(c)

                // draw channel width text below the squelch selector:
                val channelWidthValue = channelWidth.value
                textStr = if (channelWidthValue >= 10000) {
                    String.format("%d kHz", channelWidthValue / 1000)
                } else {
                    String.format("%.1f kHz", channelWidthValue / 1000.0)
                }
                textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                c.drawText(textStr, channelPosition - bounds.width() / 2f, squelchYPosition + bounds.height() * 1.1f, textSmallPaint)
            }
        }

        /**
         * This method will draw the power grid into the canvas
         *
         * @param c                canvas of the surface view
         */
        private fun drawPowerGrid(c: Canvas) {
            // Calculate pixel height of a minor tick (1dB)
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            val pixelPerMinorTick = (fftHeight / (maxDB - minDB))

            // During vertical scroll/scale we highlight the left axis:
            if (scrollType == ScrollType.NORMAL_VERTICAL) {
                channelWidthSelectorPaint.alpha = 0x5f
                c.drawRect(0f, 0f, gridSize.toFloat(), fftHeight.toFloat() - gridSize, channelWidthSelectorPaint)
                channelWidthSelectorPaint.alpha = 0xff
            }

            // Draw the ticks from the top to the bottom. Stop as soon as we interfere with the frequency scale
            var tickDB = maxDB.toInt()
            var tickPos = (maxDB - tickDB) * pixelPerMinorTick
            while (tickDB > minDB) {
                var tickWidth: Float
                if (tickDB % 10 == 0) {
                    // Major Tick (10dB)
                    tickWidth = (gridSize / 3.0).toFloat()
                    // Draw the dB value:
                    c.drawText("" + tickDB, (gridSize / 2.9).toFloat(), tickPos, textPaint)
                    // Draw grid line across the entire FFT plot:
                    val savedColor = textPaint.color
                    textPaint.color = (savedColor and 0x00ffffff) or 0x44000000
                    c.drawLine(0f, tickPos, width.toFloat(), tickPos, textPaint)
                    textPaint.color = savedColor
                } else if (tickDB % 5 == 0) {
                    // 5 dB tick
                    tickWidth = (gridSize / 3.5).toFloat()
                } else {
                    // Minor tick
                    tickWidth = (gridSize / 5.0).toFloat()
                }
                c.drawLine(0f, tickPos, tickWidth, tickPos, textPaint)
                tickPos += pixelPerMinorTick

                // stop if we interfere with the frequency grid:
                if (tickPos > fftHeight - gridSize) break
                tickDB--
            }
        }

        private fun drawStationLabels(
            canvas: Canvas,
            labels: List<StationLabel>,
            baseY: Float,
            stationLayerY: Float
        ) {
            val boxHeight = stationPaint.textSize + STATION_LABEL_BOX_PADDING

            for (label in labels) {
                val x = freqToX(label.frequency)
                val boxTop = stationLayerY - (label.layer + 1) * stationLabelRowHeight(stationPaint.textSize)
                val boxBottom = boxTop + boxHeight
                val textX = x + 10f
                val textY = boxBottom - 13f
                val boxWidth = stationPaint.measureText(label.name) + 20f

                // Draw line to frequency grid
                canvas.drawLine(x, baseY, x, boxTop + boxHeight/2, labelLinePaint)

                // Draw box
                val boxRect = RectF(x, boxTop, x + boxWidth, boxBottom)
                stationPaint.color = ((label.color.toLong() and 0x00FFFFFFL) or 0xCC000000L).toInt()  // set alpha to 0xCC
                canvas.drawRoundRect(boxRect, 8f, 8f, stationPaint)

                // Draw border
                if (pressedLabel == label) {
                    val origColor = labelLinePaint.color
                    val origStrokeWidth = labelLinePaint.strokeWidth
                    labelLinePaint.apply { color = Color.WHITE; strokeWidth = 8f }
                    canvas.drawRoundRect(boxRect, 8f, 8f, labelLinePaint)
                    labelLinePaint.apply { color = origColor; strokeWidth = origStrokeWidth }
                } else {
                    canvas.drawRoundRect(boxRect, 8f, 8f, labelLinePaint)
                }

                // Draw text
                stationPaint.color = Color.WHITE
                canvas.drawText(label.name, textX, textY, stationPaint)
            }
        }

        private fun drawBandLabels(
            canvas: Canvas,
            labels: List<BandLabel>,
            bandLayerY: Float
        ) {
            fun visibleSegment(start: Float, end: Float, leftBound: Float = 0f, rightBound: Float): Pair<Float, Float> {
                val s = start.coerceAtLeast(leftBound)
                val e = end.coerceAtMost(rightBound)
                return s to e
            }

            val widthPx = canvas.width.toFloat()

            val bandNamePaddingTop = 4f
            val bandNameHeight = bandPaint.textSize
            val subBandHeight = subBandPaint.textSize
            val subBandSpacing = 12f

            val boxHeight = bandNamePaddingTop + bandNameHeight + subBandSpacing + subBandHeight

            for (label in labels) {

                val boxTop = bandLayerY - (label.layer + 1) * (boxHeight + BAND_LABEL_ROW_GAP)
                val boxBottom = boxTop + boxHeight

                val bandXStart = freqToX(label.freqStart)
                val bandXEnd = freqToX(label.freqEnd)

                val bandRect = RectF(bandXStart, boxTop, bandXEnd, boxBottom)

                // Visible (clipped) segment for correct text positioning
                val (visibleStart, visibleEnd) =
                    visibleSegment(bandXStart, bandXEnd, rightBound = widthPx)

                // Draw band background
                bandPaint.color = ((label.color.toLong() and 0x00FFFFFF) or 0x55000000).toInt()
                canvas.drawRoundRect(bandRect, 10f, 10f, bandPaint)

                // --- Draw Band Name (centered in visible region) ---
                val fullName = label.name
                val visWidth = (visibleEnd - visibleStart).coerceAtLeast(0f)

                val bandNameFitted = if (bandPaint.measureText(fullName) <= visWidth - 20f) fullName
                                     else abbreviateTextToFit(fullName, bandPaint, visWidth - 20f)

                val bandTextWidth = bandPaint.measureText(bandNameFitted)
                val textX = visibleStart + (visWidth - bandTextWidth) / 2f
                val textY = boxTop + bandNamePaddingTop + bandNameHeight

                bandPaint.color = Color.WHITE
                canvas.drawText(bandNameFitted, textX, textY, bandPaint)

                // --- Subband vertical region ---
                val sbTop = boxBottom - subBandHeight
                val sbBottom = boxBottom

                for (sb in label.subBands) {
                    val sbFullStart = freqToX(sb.freqStart)
                    val sbFullEnd = freqToX(sb.freqEnd)
                    val (sbVisibleStart, sbVisibleEnd) = visibleSegment(sbFullStart, sbFullEnd, rightBound = widthPx)
                    val fullWidth = sbFullEnd - sbFullStart
                    val visWidthSb = sbVisibleEnd - sbVisibleStart

                    // ---- Case #1: normal subband (> minimum width) ----
                    if (fullWidth >= 6f) {

                        val sbRect = RectF(sbFullStart, sbTop, sbFullEnd, sbBottom)

                        val sbColor = ((label.color.toLong() and 0x00FFFFFF) or 0x66000000).toInt()
                        bandPaint.color = sbColor
                        canvas.drawRoundRect(sbRect, 4f, 4f, bandPaint)

                        // Draw border for separation
                        canvas.drawRoundRect(sbRect, 4f, 4f, bandBorderPaint)

                        // Draw subband name inside visible area
                        val fittedName =
                            abbreviateTextToFit(sb.name, subBandPaint, visWidthSb - 6f)

                        val tWidth = subBandPaint.measureText(fittedName)
                        val tx = sbVisibleStart + (visWidthSb - tWidth) / 2f
                        val ty = sbBottom - 4f

                        subBandPaint.color = Color.WHITE
                        canvas.drawText(fittedName, tx, ty, subBandPaint)
                    }

                    // ---- Case #2: marker subband (zero-width or tiny width) ----
                    else if (visWidth > width/3) { // draw markers only if band is zoomed in
                        val pinX = sbFullStart   // same as sbFullEnd when degenerate
                        canvas.drawLine(pinX, sbTop, pinX, sbBottom, bandPinPaint) // Draw vertical pin as marker

                        // Pill label for marker
                        val pillText = abbreviateTextToFit(sb.name, subBandPaint, 150f)
                        val textW = subBandPaint.measureText(pillText)
                        val pillWidth = max(textW + 12f, 24f)
                        val pillLeft = (pinX - pillWidth / 2f).coerceAtLeast(0f)
                        val pillTextX = pillLeft + (pillWidth - textW) / 2f
                        val pillTextY = sbBottom - subBandHeight - 2f
                        subBandPaint.color = Color.WHITE
                        canvas.drawText(pillText, pillTextX, pillTextY, subBandPaint)
                    }
                }
            }
        }

        /**
         * Will populate the waterfallColorMap array with color instances
         */
        fun createWaterfallColorMap(colorMap: FftColorMap) {
            this.waterfallColorMapArray = createColorMap(colorMap)
        }

        fun updateFftPaint() {
            when(fftDrawingType.value) {
                FftDrawingType.BAR -> fftPaint.style = Paint.Style.FILL
                FftDrawingType.LINE ->  fftPaint.style = Paint.Style.STROKE
            }
            // Recreate the shaders:
            fftPaint.apply { setShader( LinearGradient( 0f, 0f, 0f, fftHeight.toFloat(), Color.WHITE, Color.BLUE, Shader.TileMode.MIRROR ) ) }
        }

        fun updateTextPaint() {
            val normalTextSize = when (fontSize.value) {
                FontSize.SMALL -> gridSize * 0.2f
                FontSize.NORMAL -> gridSize * 0.3f
                FontSize.LARGE -> gridSize * 0.476f
            }
            textPaint.textSize = normalTextSize
            textSmallPaint.textSize = smallTextSize
            stationPaint.textSize = fontSize.value.stationLabelFontSize
            bandPaint.textSize = fontSize.value.bandLabelFontSize
            subBandPaint.textSize = fontSize.value.subBandLabelFontSize
            Log.i(
                LOGTAG, "setFontSize: X-dpi=${resources.displayMetrics.xdpi} X-width=${resources.displayMetrics.widthPixels}" +
                        "  fontSize=${fontSize}  normalTextSize=${normalTextSize} smallTextSize=${smallTextSize}"
            )
        }

        fun drawWatermark() {
            var c: Canvas? = null
            val bitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.rfanalyzer2)
            var text = if(BuildConfig.IS_FOSS) {
                "FOSS VERSION"
            } else {
                when (license.value) {
                    License.FULL -> "FULL VERSION"
                    License.BASE -> "BASE VERSION"
                    License.TRIAL -> "TRIAL VERSION"
                }
            }
            val paint = Paint()
            val textPaint = Paint()
            textPaint.color = Color.DKGRAY
            textPaint.textSize = 50f
            val matrix = ColorMatrix()
            matrix.setSaturation(0f) // 0 = grayscale
            paint.colorFilter = ColorMatrixColorFilter(matrix)
            paint.alpha = 100
            try {
                if (!running) return  // make sure not to try to lock the canvas if we should exit..
                if (!holder.surface.isValid) return
                c = holder.lockHardwareCanvas()
                if (c != null) {
                    // Clear the canvas:
                    c.drawColor(Color.BLACK, PorterDuff.Mode.CLEAR)
                    val dstRect = Rect(width/2 - 512/2, height/8, width/2 + 512/2, height/8 + 512)
                    val srcRect = Rect(0, 0, bitmap.width, bitmap.height)
                    c.drawBitmap(bitmap, srcRect, dstRect, paint)
                    textPaint.getTextBounds(text, 0, text.length, bounds)
                    c.drawText(text, width/2f - bounds.width()/2, height/8f + 512 + bounds.height() + 20, textPaint)
                    text = "RF ANALYZER"
                    textPaint.getTextBounds(text, 0, text.length, bounds)
                    c.drawText(text, width/2f - bounds.width()/2, height/8f - bounds.height() - 20, textPaint)
                } else Log.d(LOGTAG, "drawWatermark: Canvas is null.")
            } catch (e: Exception) {
                Log.e(LOGTAG, "drawWatermark: Error while drawing on the canvas. Stop!")
                e.printStackTrace()
            } finally {
                if (c != null) {
                    holder.unlockCanvasAndPost(c)
                }
            }
        }
    }
}

