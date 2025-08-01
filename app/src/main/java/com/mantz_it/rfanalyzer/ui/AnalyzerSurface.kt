package com.mantz_it.rfanalyzer.ui

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
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.core.content.ContextCompat
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.VERTICAL_SCALE_LOWER_BOUNDARY
import com.mantz_it.rfanalyzer.database.AppStateRepository.Companion.VERTICAL_SCALE_UPPER_BOUNDARY
import com.mantz_it.rfanalyzer.database.collectAppState
import com.mantz_it.rfanalyzer.source.SamplePacket
import com.mantz_it.rfanalyzer.ui.composable.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.FftColorMap
import com.mantz_it.rfanalyzer.ui.composable.FftDrawingType
import com.mantz_it.rfanalyzer.ui.composable.FftWaterfallSpeed
import com.mantz_it.rfanalyzer.ui.composable.FontSize
import com.mantz_it.rfanalyzer.ui.composable.asSizeInBytesToString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ArrayBlockingQueue
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.analyzer.FftProcessorData
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData

/**
 * <h1>RF Analyzer - Analyzer Surface (FFT/Waterfall View)</h1>
 *
 * Module:      AnalyzerSurface.kt
 * Description: The custom SurfaceView which draws the FFT and the Waterfall plots.
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


data class AnalyzerSurfaceActions(
    val onViewportFrequencyChanged: (Long) -> Unit,
    val onViewportSampleRateChanged: (Long) -> Unit,
    val onViewportVerticalScaleChanged: (Pair<Float, Float>) -> Unit,  // former minDB, maxDB
    val onChannelFrequencyChanged: (newFrequency: Long) -> Unit,
    val onChannelWidthChanged: (newWidth: Int) -> Unit,
    val onSquelchChanged: (newSquelch: Float) -> Unit
)

class AnalyzerSurface(context: Context,
                      private val sourceName: AppStateRepository.State<String>,
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
                      private val squelch: AppStateRepository.State<Float>,
                      private val recordingRunning: AppStateRepository.State<Boolean>,
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
                      private val isFullVersion: AppStateRepository.State<Boolean>,
                      private val fftProcessorData: FftProcessorData,
                      private val analyzerSurfaceActions: AnalyzerSurfaceActions) : SurfaceView(context){

    companion object {
        private const val LOGTAG = "AnalyzerSurface"
        private const val MIN_VIRTUAL_SAMPLERATE = 64L // Smallest virtual sample rate

        const val STROKE_WIDTH_CHANNELWIDTHSELECTOR = 1f
        const val STROKE_WIDTH_SQUELCH = 3f
        const val STROKE_WIDTH_CHANNELSELECTOR = 3f
        const val STROKE_WIDTH_ACTIVE = 6f
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
    private val channelSelectorDragHandlePosition: Pair<Float, Float> // vertical position of the drag handles (in dB; first: channel handle; second: channelWidth handle)
        get() {
            // handles should be at 1/3 distance from the top if squelch is below half and vice versa
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value
            val viewportHeightInDb = maxDB - minDB
            val centerHandlePosition: Float
            val outerHandlePosition: Float
            if(squelch.value < minDB + viewportHeightInDb/2) {
                centerHandlePosition = maxDB - viewportHeightInDb * 0.35f
                outerHandlePosition = maxDB - viewportHeightInDb * 0.2f
            } else {
                centerHandlePosition = minDB + viewportHeightInDb * 0.35f
                outerHandlePosition = minDB + viewportHeightInDb * 0.2f
            }
            return Pair(centerHandlePosition, outerHandlePosition)
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

    private final val scaleGestureListener = object: ScaleGestureDetector.OnScaleGestureListener {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean { return true }
        override fun onScaleEnd(detector: ScaleGestureDetector) { }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            when (scrollType) {
                ScrollType.NORMAL -> {
                    val xScale = detector.currentSpanX / detector.previousSpanX
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
                drawingThread?.channelWidthSelectorPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
            } else if (demodulationEnabled && showUpperBand
                    && touchedDB in outerDragHandlePositionDb - dbVariation .. outerDragHandlePositionDb + dbVariation
                    && touchedFrequency < channelEndFrequency + channelFrequencyVariation
                    && touchedFrequency > channelEndFrequency - channelFrequencyVariation
            ) {
                scrollType = ScrollType.CHANNEL_WIDTH_RIGHT
                drawingThread?.channelWidthSelectorPaint?.strokeWidth = STROKE_WIDTH_ACTIVE
            } else if (e.x > gridSize * 1.5 || e.y > fftHeight - gridSize)
                scrollType = ScrollType.NORMAL
            else
                scrollType = ScrollType.NORMAL_VERTICAL


            return true
        }

        override fun onShowPress(e: MotionEvent) { }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
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

            // scroll horizontally or adjust channel selector (scroll type was selected in onDown() event routine:
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
                    val tmpChannelWidth = if (scrollType == ScrollType.CHANNEL_WIDTH_LEFT) (channelWidth.value + distanceX * hzPerPx).toInt() else (channelWidth.value - distanceX * hzPerPx).toInt()
                    analyzerSurfaceActions.onChannelWidthChanged(tmpChannelWidth.coerceIn(demodulationMode.value.minChannelWidth, demodulationMode.value.maxChannelWidth))
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
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Log.d(LOGTAG, "surfaceDestroyed")
                if(drawingThread != null) {
                    drawingThread?.running = false
                    drawingThread?.join()
                    Log.d(LOGTAG, "surfaceDestroyed: drawingThread ${drawingThread?.name} joined.")
                    drawingThread = null
                }
            }
        })

        // Instantiate the (scale-) gesture detectors:
        this.scaleGestureDetector = ScaleGestureDetector(context, scaleGestureListener)
        this.gestureDetector = GestureDetector(context, gestureListener)

        // react to changes of the view model:
        observeAppState()
    }

    private fun observeAppState() {
        fun updateViewport() {
            // if viewport is out of range of the source's new frequency and samplerate, we try to fix it
            // if the virtual sample rate is larger than the source sample rate we reset it:
            if (viewportSampleRate.value > sourceSampleRate.value) {
                //Log.d(LOGTAG, "observeAppState: calling onViewportSampleRateChanged(${sourceSampleRate.value}) [one]")
                analyzerSurfaceActions.onViewportSampleRateChanged(sourceSampleRate.value)
            }
            // if the viewport is completely outside of the actual signal, we reset freq and sample rate:
            if (viewportStartFrequency.value > sourceSignalEndFrequency.value || viewportEndFrequency.value < sourceSignalStartFrequency.value) {
                //Log.d(LOGTAG, "observeAppState: calling onViewportFrequencyChanged(${sourceFrequency.value})")
                analyzerSurfaceActions.onViewportFrequencyChanged(sourceFrequency.value)
                //Log.d(LOGTAG, "observeAppState: calling onViewportSampleRateChanged(${sourceSampleRate.value}) [two]")
                analyzerSurfaceActions.onViewportSampleRateChanged(sourceSampleRate.value)
            }
            // if the viewport is a bit to much on the right we shift it left:
            if (viewportEndFrequency.value > sourceSignalEndFrequency.value) {
                val sef = sourceSignalEndFrequency.value
                val sf  = sourceFrequency.value
                val newViewportFrequency = sef - viewportSampleRate.value / 2
                //Log.d(LOGTAG, "observeAppState: calling onViewportFrequencyChanged($newViewportFrequency)  [sf=$sf   sef=$sef]")
                analyzerSurfaceActions.onViewportFrequencyChanged(newViewportFrequency)
            }
            // if the viewport is a bit to much on the left we shift it right:
            if (viewportStartFrequency.value < sourceSignalStartFrequency.value) {
                val newViewportFrequency = sourceSignalStartFrequency.value + viewportSampleRate.value / 2
                //Log.d(LOGTAG, "observeAppState: calling onViewportFrequencyChanged($newViewportFrequency) [four]")
                analyzerSurfaceActions.onViewportFrequencyChanged(newViewportFrequency)
            }
        }
        coroutineScope.collectAppState(sourceFrequency) { updateViewport() }
        coroutineScope.collectAppState(sourceSampleRate) { updateViewport() }
        coroutineScope.collectAppState(fftWaterfallRatio) { drawingThread?.apply { updateFftPaint() } }
        coroutineScope.collectAppState(fontSize) { drawingThread?.apply { updateTextPaint() } }
        coroutineScope.collectAppState(waterfallColorMap) { drawingThread?.createWaterfallColorMap(it) }
        coroutineScope.collectAppState(fftDrawingType) { drawingThread?.apply { updateFftPaint() } }
        coroutineScope.collectAppState(squelchSatisfied) { drawingThread?.squelchPaint?.color = if(it) Color.GREEN else Color.RED }
        coroutineScope.collectAppState(isFullVersion) { drawingThread?.drawWatermark() }  // redraw the watermark
    }

    /**
     * Will cause the surface to automatically adjust the dB scale at the
     * next call of draw() so that it fits the incoming fft samples perfectly
     */
    fun autoscale() {
        this.doAutoscaleInNextDraw = true
    }


    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Reset the stroke width of the channel controls if the user lifts his finger:
        if (event.action == MotionEvent.ACTION_UP) {
            drawingThread?.squelchPaint?.strokeWidth = STROKE_WIDTH_SQUELCH
            drawingThread?.channelSelectorPaint?.strokeWidth = STROKE_WIDTH_CHANNELSELECTOR
            drawingThread?.channelWidthSelectorPaint?.strokeWidth = STROKE_WIDTH_CHANNELWIDTHSELECTOR
            scrollType = ScrollType.NONE
        }

        var retVal = scaleGestureDetector.onTouchEvent(event)
        retVal = gestureDetector.onTouchEvent(event) || retVal
        return retVal
    }

    //
    //  ---- DRAWING -----
    //

    private inner class DrawThread() : Thread() {
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
        private val leftRightArrowDrawable = ContextCompat.getDrawable(context, R.drawable.left_right_arrow_icon)
        private val topBottomArrowDrawable = ContextCompat.getDrawable(context, R.drawable.top_bottom_arrow_icon)
        private var fftPath: Path = Path()                  // Path object to draw the fft lines
        private lateinit var waterfallColorMapArray: IntArray    // Colors used to draw the waterfall plot.
        private var fftGridBitmap: Bitmap? = null           // bitmap caching the power and frequency grid of the fft
        private var fftGridCanvas: Canvas? = null           // canvas for drawing onto the fftGridBitmap
        private val fftGridBitmapState: MutableMap<String, Any> = HashMap() // Hashmap to track all relevant variables for the grid
                                                                            // (stores all variables which influence the grid at the time the bitmap was drawn)
        private var waterfallBitmap: Bitmap? = null
        private var peaks: FloatArray? = null // peak hold values
        private var timeAverageSamples: FloatArray? = null // Used to calculate the average of multiple values in the past 'averagingLength' fft sample rows
        private var peaksYCoordinates: FloatArray? = null // peak hold y-coordinates
        private var colorBuffer: IntArray? = null // Preallocated color array
        private var lastMinDB: Float = 0f
        private var lastMaxDB: Float = 0f
        private var lastViewportFrequency: Long = 0
        private var lastViewportSampleRate: Long = 0

        init {
            blackPaint.color = Color.BLACK
            fftPaint.color = Color.BLUE
            peakHoldPaint.color = Color.YELLOW
            peakHoldPaint.style = Paint.Style.FILL
            peakHoldPaint.strokeWidth = 2f
            textPaint.color = Color.WHITE
            textPaint.isAntiAlias = true
            textSmallPaint.color = Color.WHITE
            textSmallPaint.isAntiAlias = true
            channelSelectorPaint.color = Color.YELLOW
            channelWidthSelectorPaint.color = Color.WHITE
            squelchPaint.color = if (squelchSatisfied.value) Color.GREEN else Color.RED

            // Create the color map for the waterfall plot
            this.createWaterfallColorMap(waterfallColorMap.value)
            updateFftPaint()
            updateTextPaint()
        }

        override fun run() {
            running = true
            this.setName("Thread-DrawThread-" + System.currentTimeMillis())
            Log.i(LOGTAG, "DrawThread started. (Thread: " + this.name + ")")

            // Draw watermark
            drawWatermark()

            var frameRateTimestamp = System.currentTimeMillis()
            var frameRateFrameCounter = 0
            var frameRate = 0

            while (running) {
                val startTimestamp = System.currentTimeMillis()
                frameRateFrameCounter++
                if(startTimestamp-frameRateTimestamp > 1000) { // only measure every second
                    frameRate = (frameRateFrameCounter * 1000f / (startTimestamp-frameRateTimestamp)).toInt()
                    frameRateFrameCounter = 0
                    frameRateTimestamp = startTimestamp
                }

                // Local current state (better performance, and safer because width/height could change at any time)
                val width = width
                val fftHeight = fftHeight

                if(timeAverageSamples == null || timeAverageSamples!!.size != width)
                    timeAverageSamples = FloatArray(width)
                if(fftPeakHold.value && (peaksYCoordinates == null || peaksYCoordinates!!.size != width))
                    peaksYCoordinates = FloatArray(width)

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
                        frequency != null &&
                        sampleRate != null
                    ) {
                        doDraw = true
                        // Update Peak Hold
                        if (fftPeakHold.value) {
                            // First verify that the array is initialized correctly:
                            if (peaks == null || peaks!!.size != waterfallBuffer[0].size) {
                                peaks = FloatArray(waterfallBuffer[0].size)
                                for (i in peaks!!.indices) peaks!![i] = -999999f // == no peak
                            }
                            // Check if the frequency or sample rate of the incoming signals is different from the ones before:
                            if (fftProcessorData.frequencyOrSampleRateChanged)
                                for (i in peaks!!.indices) peaks!![i] = -999999f // reset peaks. We could also shift and scale. But for now they are simply reset.
                            // Update the peaks:
                            for (i in waterfallBuffer[readIndex].indices) peaks!![i] = max(peaks!![i], waterfallBuffer[readIndex][i])
                        } else {
                            peaks = null
                            peaksYCoordinates = null
                        }

                        // preprocessing of waterfall data:
                        drawPreprocessing(
                            waterfallBuffer,
                            waterfallBufferDirtyMap,
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
                if (doDraw)
                    draw(frameRate)

                // measure current frametime to derive how long we need to sleep to meet the user's preferred framerate
                val frameDrawingTime = System.currentTimeMillis()-startTimestamp
                val desiredFrameTimeMs = 1000/maxFrameRate.value
                GlobalPerformanceData.updateLoad("Renderer", frameDrawingTime.toFloat()/desiredFrameTimeMs)
                val sleepTime = desiredFrameTimeMs - frameDrawingTime
                sleep(sleepTime.coerceAtLeast(0))
            }
            Log.i(LOGTAG, "DrawThread stopped. (Thread: " + this.name + ")")
        }

        private fun drawPreprocessing(waterfallBuffer: Array<FloatArray>,
                                      waterfallBufferDirtyMap: Array<Boolean>,
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
            val peaks = peaks
            val calcPeaks = peaks != null
            val fftPath = fftPath
            val peaksYCoordinates = peaksYCoordinates
            val fftSize = waterfallBuffer[0].size
            val doAutoscale = doAutoscaleInNextDraw

            if(waterfallBitmap == null || waterfallBitmap!!.width != width || waterfallBitmap!!.height != waterfallBuffer.size) {
                waterfallBitmap = Bitmap.createBitmap(width, waterfallBuffer.size, Bitmap.Config.ARGB_8888)
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
            var avg: Float              // Used to calculate the average of multiple values in mag (horizontal average)
            var peakAvg: Float          // Used to calculate the average of multiple values in peaks (horizontal average)
            var counter: Int            // Used to calculate the average of multiple values in mag and peaks

            // measure the signal min and max for autoscale:
            var minMeasuredSignal = VERTICAL_SCALE_UPPER_BOUNDARY
            var maxMeasuredSignal = VERTICAL_SCALE_LOWER_BOUNDARY
            val black = Color.rgb(0, 0, 0)

            // The start position to draw is either 0 or greater 0, if start is negative:
            val firstPixel = if (start >= 0) 0 else ((start * -1) / samplesPerPx).toInt()

            // We will only draw to the end of mag, not beyond:
            val lastPixel = if (end >= fftSize) ((fftSize - start) / samplesPerPx).toInt() else ((end - start) / samplesPerPx).toInt()

            // Reset fft path and start at the first pixel:
            fftPath.reset()
            fftPath.moveTo(firstPixel.toFloat(), fftHeight.toFloat()) // start graph at the bottom left

            //Log.d(LOGTAG, "SURFACE - before outer loop: " + (System.currentTimeMillis()-startTimestamp));
            var rowsProcessed = 0
            for(rowNumber in 0 until waterfallBuffer.size) {
                val bufferIndex = (currentRowIdx + rowNumber) % waterfallBuffer.size // from newest sample to oldest (ringbuffer is ordered in reverse)

                // only process dirty rows and only up to 5 rows each run (to keep the interface snappy and responsive)
                if(!waterfallBufferDirtyMap[bufferIndex] && rowNumber > timeAveragingLength)
                    continue  // current row was already processed and is up to date!
                if(rowsProcessed > timeAveragingLength + 5) // only process 5 additional dirty rows per run
                    break

                val fftRow = waterfallBuffer[bufferIndex]

                // Draw pixel by pixel:
                // We start at firstPixel+1 because of integer round off error
                //for (i in firstPixel + 1 until lastPixel) {
                for (i in 0 until width) {
                    if(i in (firstPixel + 1)..<lastPixel-1) {
                        // Calculate the average value for this pixel (horizontal average - not the time domain average):
                        avg = 0f
                        peakAvg = 0f
                        counter = 0
                        var j = (i * samplesPerPx).toInt()
                        while (j < (i + 1) * samplesPerPx) {
                            avg += fftRow[j + start]
                            if (rowNumber == 0 && calcPeaks) peakAvg += peaks!![j + start]
                            counter++
                            j++
                        }
                        avg /= counter
                        if (rowNumber == 0 && calcPeaks) peaksYCoordinates!![i] = fftHeight - (peakAvg/counter - minDB) * dbWidth

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
                        if(calcPeaks) peaksYCoordinates!![i] = -1f // outside of the frame
                    }
                }
                rowsProcessed += 1
                waterfallBufferDirtyMap[bufferIndex] = false
                //bitmap!!.setPixels(colorBuffer!!, bufferIndex*width, width, 0, rowNumber, width, 1)
            }
            waterfallBitmap!!.setPixels(colorBuffer, currentRowIdx*width, width, 0, 0, width, waterfallBuffer.size-currentRowIdx)
            waterfallBitmap!!.setPixels(colorBuffer, 0, width, 0, waterfallBuffer.size-currentRowIdx, width, currentRowIdx)
            fftPath.lineTo(lastPixel.toFloat(), fftHeight.toFloat()) // end at the bottom right
            if (doAutoscale && minMeasuredSignal < maxMeasuredSignal) {
                minMeasuredSignal -= 5f // leave a bit room
                maxMeasuredSignal += 5f
                analyzerSurfaceActions.onViewportVerticalScaleChanged(Pair(minMeasuredSignal.coerceAtLeast(VERTICAL_SCALE_LOWER_BOUNDARY), maxMeasuredSignal.coerceAtMost(VERTICAL_SCALE_UPPER_BOUNDARY)))
                doAutoscaleInNextDraw = false
            }
            //Log.d(LOGTAG, "SURFACE - after outer loop: " + (System.currentTimeMillis()-startTimestamp));
        }

        /**
         * Will (re-)draw the surface.
         *
         * @param frameRate    current frame rate (FPS)
         */
        fun draw(frameRate: Int) {
            // Redraw/recreate the fft grid bitmap if necessary
            if (hasFftGridChanged()) {
                //Log.d(LOGTAG, "SURFACE - before redraw grid: " + (System.currentTimeMillis()-startTime));
                if (fftGridBitmap == null || fftGridBitmap!!.height != fftHeight || fftGridBitmap!!.width != width) {
                    // fft height has changed!
                    fftGridBitmap = Bitmap.createBitmap(width, fftHeight, Bitmap.Config.ARGB_8888)
                    fftGridCanvas = Canvas(fftGridBitmap!!)
                }
                // Clear the bitmap with transparency
                fftGridCanvas!!.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                drawFrequencyGrid(fftGridCanvas!!)
                drawPowerGrid(fftGridCanvas!!)
                // save the current state:
                fftGridBitmapState["fftHeight"] = fftHeight
                fftGridBitmapState["fontSize"] = fontSize
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
                fftGridBitmapState["showUpperBand"] = demodulationMode.value != DemodulationMode.USB
                fftGridBitmapState["showLowerBand"] = demodulationMode.value != DemodulationMode.LSB
            }

            // Draw:
            var c: Canvas? = null
            try {
                c = holder.lockHardwareCanvas()
                synchronized(holder) {
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
                        c.drawBitmap(waterfallBitmap!!, Rect(0, 0, waterfallBitmap!!.width, waterfallBitmap!!.height), Rect(0, fftHeight, width, height), null)
                        // Draw Grid (with channel selector)
                        c.drawBitmap(fftGridBitmap!!, 0f, 0f, null)

                        // draw channel frequency line into waterfall:
                        if (demodulationEnabled.value) {
                            val pxPerHz = width / viewportSampleRate.value.toFloat()
                            val channelPosition = width / 2 + pxPerHz * (channelFrequency.value - viewportFrequency.value)
                            c.drawLine(channelPosition, fftHeight.toFloat(), channelPosition, height.toFloat(), channelSelectorPaint)
                        }

                        drawPerformanceInfo(c, frameRate, averageSignalStrength.value)
                    } else Log.d(LOGTAG, "draw: Canvas is null.")
                }
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
         * checks current state vs old state (fftGridBitmapState)
         * @return true if the fft grid bitmap must be redrawn
         */
        private fun hasFftGridChanged(): Boolean {
            if (!fftGridBitmapState.containsKey("fftHeight"))  // test if hashmap was initialized with values already
                return true
            return !(fftGridBitmapState["fftHeight"] == fftHeight &&
                    fftGridBitmapState["fontSize"] == fontSize &&
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
                    fftGridBitmapState["showUpperBand"] == (demodulationMode.value != DemodulationMode.USB) &&
                    fftGridBitmapState["showLowerBand"] == (demodulationMode.value != DemodulationMode.LSB))
        }

        /**
         * This method will draw the frequency grid into the canvas
         *
         * @param c                canvas of the surface view
         */
        private fun drawFrequencyGrid(c: Canvas) {
            val showLowerBand = demodulationMode.value != DemodulationMode.USB
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

            // Calculate span of a minor tick (must be a power of 10KHz)
            var tickSize = 10 // we start with 10KHz
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

            // Calculate the frequency and position of the first Tick (ticks are every <tickSize> KHz)
            var tickFreq = (ceil(startFrequency.toDouble() / tickSize.toFloat()) * tickSize).toLong()
            var tickPos = pixelPerMinorTick / tickSize.toFloat() * (tickFreq - startFrequency)

            // Draw the ticks
            var i = 0
            while (i < virtualSampleRate / tickSize.toFloat()) {
                var tickHeight: Float
                if (tickFreq % (tickSize * 10) == 0L) {
                    // Major Tick (10x <tickSize> KHz)
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
                    // Half major tick (5x <tickSize> KHz)
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
                    // Minor tick (<tickSize> KHz)
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

            // If demodulation is activated: draw channel selector:
            if (demodulationEnabled.value) {
                val squelchEnabled = squelchEnabled.value
                val pxPerHz = width / virtualSampleRate.toFloat()
                val channelPosition = width / 2 + pxPerHz * (channelFrequency.value - virtualFrequency)
                val leftBorder = width / 2 + pxPerHz * (channelStartFrequency.value - virtualFrequency)
                val rightBorder = width / 2 + pxPerHz * (channelEndFrequency.value - virtualFrequency)
                //val leftBorder = channelPosition - pxPerHz * channelWidth.value
                //val rightBorder = channelPosition + pxPerHz * channelWidth.value
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
                    textStr = String.format("%2.1f dB", squelch.value)
                    textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                    c.drawText(textStr, channelPosition - bounds.width() / 2f, squelchYPosition - bounds.height() * 0.1f, textSmallPaint)

                    // draw drag handle on squelch
                    val squelchHandleBounds = RectF(squelchXPosition - 15f, squelchYPosition - 30f, squelchXPosition + 15f, squelchYPosition + 30f)
                    c.drawOval(squelchHandleBounds, squelchPaint)
                    topBottomArrowDrawable?.bounds = Rect(squelchHandleBounds.left.toInt() + 5, squelchHandleBounds.top.toInt() + 5, squelchHandleBounds.right.toInt() - 5, squelchHandleBounds.bottom.toInt() - 5)
                    topBottomArrowDrawable?.draw(c)

                    // draw channel width text below the squelch selector:
                    var shownChannelWidth = 0
                    if (showLowerBand) shownChannelWidth += channelWidth.value
                    if (showUpperBand) shownChannelWidth += channelWidth.value
                    textStr = String.format("%d kHz", shownChannelWidth / 1000)
                    textSmallPaint.getTextBounds(textStr, 0, textStr.length, bounds)
                    c.drawText(textStr, channelPosition - bounds.width() / 2f, squelchYPosition + bounds.height() * 1.1f, textSmallPaint)
                }
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
                    // Draw Frequency Text:
                    c.drawText("" + tickDB, (gridSize / 2.9).toFloat(), tickPos, textPaint)
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

        /**
         * This method will draw the performance information into the canvas
         *
         * @param c                canvas of the surface view
         * @param frameRate    current frame rate (FPS)
         * @param load            current load (percentage [0..1])
         * @param averageSignalStrength        average magnitude of the signal in the selected channel
         */
        private fun drawPerformanceInfo(c: Canvas, frameRate: Int, averageSignalStrength: Float) {
            var text: String
            var yPos = height * 0.01f
            val rightBorder = width * 0.99f
            val minDB = viewportVerticalScaleMin.value
            val maxDB = viewportVerticalScaleMax.value

            // Name
            text = sourceName.value
            textSmallPaint.getTextBounds(text, 0, text.length, bounds)
            c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint);
            yPos += bounds.height() * 1.1f;

            if (demodulationEnabled.value) {
                // Draw the channel frequency if demodulation is enabled:
                text = String.format("demod at %4.6f MHz", channelFrequency.value / 1000000f)
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)

                // increase yPos:
                yPos += bounds.height() * 1.1f

                // Draw the average signal strength indicator if demodulation is enabled
                text = String.format("%2.1f dB", averageSignalStrength)
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)

                val indicatorWidth = (width / 10).toFloat()
                val indicatorPosX = rightBorder - indicatorWidth
                val indicatorPosY = yPos + bounds.height()
                val squelchTickPos = (squelch.value - minDB) / (maxDB - minDB) * indicatorWidth
                var signalWidth = (averageSignalStrength - minDB) / (maxDB - minDB) * indicatorWidth
                if (signalWidth < 0) signalWidth = 0f
                if (signalWidth > indicatorWidth) signalWidth = indicatorWidth

                // draw signal rectangle:
                c.drawRect(
                    indicatorPosX,
                    yPos + bounds.height() * 0.1f,
                    indicatorPosX + signalWidth,
                    indicatorPosY,
                    squelchPaint
                )

                // draw left border, right border, bottom line and squelch tick:
                c.drawLine(indicatorPosX, indicatorPosY, indicatorPosX, yPos, textPaint)
                c.drawLine(rightBorder, indicatorPosY, rightBorder, yPos, textPaint)
                c.drawLine(indicatorPosX, indicatorPosY, rightBorder, indicatorPosY, textPaint)
                c.drawLine(
                    indicatorPosX + squelchTickPos,
                    indicatorPosY + 2,
                    indicatorPosX + squelchTickPos,
                    yPos + bounds.height() * 0.5f,
                    textPaint
                )

                // draw text:
                c.drawText(text, indicatorPosX - bounds.width() * 1.1f, indicatorPosY, textSmallPaint)

                // increase yPos:
                yPos += bounds.height() * 1.1f
            }

            // Draw recording information
            if (recordingRunning.value) {
                if (squelchSatisfied.value || !recordOnlyWhenSquelchIsSatisfied.value) {
                    text = String.format("%4.6f MHz @ %2.3f MSps (%s)", sourceFrequency.value / 1000000f, sourceSampleRate.value / 1000000f, recordingCurrentFileSize.value.asSizeInBytesToString())
                    textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                    c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
                    defaultPaint.color = Color.RED
                    c.drawCircle(
                        rightBorder - bounds.width() - (bounds.height() / 2) * 1.3f,
                        yPos + bounds.height() / 2,
                        (bounds.height() / 2).toFloat(),
                        defaultPaint
                    )
                    // increase yPos:
                    yPos += bounds.height() * 1.1f
                }
            }

            if (showDebugInformation.value) {
                // Draw the FFT/s rate
                text = "$frameRate FPS"
                textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
                yPos += bounds.height() * 1.1f

                // Draw the load
                GlobalPerformanceData.getAllLoads().forEach { (metricId, value) ->
                    text = String.format("$metricId: %03.1f %%", value * 100)
                    textSmallPaint.getTextBounds(text, 0, text.length, bounds)
                    c.drawText(text, rightBorder - bounds.width(), yPos + bounds.height(), textSmallPaint)
                    yPos += bounds.height() * 1.1f
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
            val normalTextSize: Float
            val smallTextSize: Float
            when (fontSize.value) {
                FontSize.SMALL -> {
                    normalTextSize = gridSize * 0.2f
                    smallTextSize = gridSize * 0.13f
                }
                FontSize.NORMAL -> {
                    normalTextSize = gridSize * 0.3f
                    smallTextSize = gridSize * 0.2f
                }
                FontSize.LARGE -> {
                    normalTextSize = gridSize * 0.476f
                    smallTextSize = gridSize * 0.25f
                }
            }
            textPaint.textSize = normalTextSize
            textSmallPaint.textSize = smallTextSize
            Log.i(
                LOGTAG, "setFontSize: X-dpi=${resources.displayMetrics.xdpi} X-width=${resources.displayMetrics.widthPixels}" +
                        "  fontSize=${fontSize}  normalTextSize=${normalTextSize} smallTextSize=${smallTextSize}"
            )
        }

        fun drawWatermark() {
            var c: Canvas? = null
            val bitmap: Bitmap = BitmapFactory.decodeResource(resources, R.drawable.rfanalyzer2)
            var text = if(isFullVersion.value) "FULL VERSION" else "TRIAL VERSION"
            val paint = Paint()
            val textPaint = Paint()
            textPaint.color = Color.DKGRAY
            textPaint.textSize = 50f
            val matrix = ColorMatrix()
            matrix.setSaturation(0f) // 0 = grayscale
            paint.colorFilter = ColorMatrixColorFilter(matrix)
            paint.alpha = 100
            try {
                c = holder.lockHardwareCanvas()
                synchronized(holder) {
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
                }
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

