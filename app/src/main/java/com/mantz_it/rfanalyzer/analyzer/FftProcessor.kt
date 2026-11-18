package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.nativedsp.NativeDsp
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * <h1>RF Analyzer - FFT Processor</h1>
 *
 * Module:      FftProcessor.kt
 * Description: This Thread computes the spectrum: the Scheduler feeds the samples into a native
 *              ring buffer, the thread runs the windowed FFT at the waterfall's frame rate and
 *              writes each frame into FftProcessorData for the UI, and the channel's level to the
 *              squelch.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


// FFT Buffer
class FftProcessorData {
    val lock = ReentrantReadWriteLock()
    @Volatile
    var waterfallBuffer: Array<ByteArray>? = null // Circular buffer for fft samples (with history)
    @Volatile
    var waterfallBufferDirtyMap: Array<Boolean>? = null // Circular buffer which indicates for each row in waterfallBuffer if it must be recalculated
    @Volatile
    var frequencyOrSampleRateChanged = true
    @Volatile
    var writeIndex = 0 // Tracks where new FFT results go
    @Volatile
    var readIndex = 0  // Tracks where the latest FFT results are
    @Volatile
    var frequency: Long? = null
    @Volatile
    var sampleRate: Long? = null
    @Volatile
    var peaks: FloatArray? = null // peak hold values
    @Volatile
    var newestRowTimeNs = 0L // System.nanoTime() when the row at readIndex was added
    @Volatile
    var rowPeriodNs = 0L     // nominal time between two rows (1 / waterfall fps)
}

/**
 * FftProcessor feeds samples into the native ring buffer, runs the native FFT and writes the
 * results into fftProcessorData for the UI thread.
 *  - waterfallBufferSize: the waterfall history, fixed at construction
 *  - fftSize: set on this processor, independent of the incoming packet length
 *  - fftFramesPerSecond: the desired maximum frame rate. If the device can't keep up, the
 *    processor runs as fast as it can (actualFftFramesPerSecond).
 */
class FftProcessor(
    initialFftSize: Int,
    initialSampleRate: Long,
    initialFrequency: Long,
    initialFftFramesPerSecond: Float,
    initialChannelFrequencyRange: Pair<Long, Long>,
    initialFftPaddingRatio: Float,
    private val fftProcessorData: FftProcessorData,
    private val waterfallBufferSize: Int,                 // fixed size for waterfall history (cannot change at runtime)
    var fftPeakHold: Boolean,
    /** Decides the squelch from each frame's channel level and noise floor (Squelch.kt). */
    private val squelchGate: SquelchGate,
    /** Each frame's channel level (per-bin dB) for the UI; the floor and decision are in [squelchGate]. */
    private val onChannelLevelChanged: (Float) -> Unit,
) : Thread() {
    private var stopRequested = true // Will stop the thread when set to true
    private var nativeDsp: NativeDsp = NativeDsp()

    // User-settable desired maximum frames per second. Must be > 0. Volatile for visibility.
    @Volatile
    var fftFramesPerSecond: Float = initialFftFramesPerSecond

    // Measured effective frames per second (smoothed). Exposed via GlobalPerformanceData.
    @Volatile
    var actualFftFramesPerSecond: Float = 0.0f
        private set

    // current FFT size (complex bins); starts with initialFftSize and can be changed at runtime.
    @Volatile
    var fftSize: Int = initialFftSize

    // fraction of the FFT transform input that is zero-padding (0f..0.75f). The remaining
    // (1-fftPaddingRatio)*fftSize real samples are the "analysis window" (M) that gets windowed;
    // shrinking M reduces the real-time span each frame represents (fixes waterfall time-blur)
    // at the cost of true frequency resolution (mainlobe width), which is governed by M, not fftSize.
    @Volatile
    var fftPaddingRatio: Float = initialFftPaddingRatio

    @Volatile
    var sampleRate: Long = initialSampleRate

    @Volatile
    var frequency: Long = initialFrequency

    @Volatile
    var channelFrequencyRange: Pair<Long, Long> = initialChannelFrequencyRange

    companion object {
        private const val LOGTAG = "FftProcessor"

        // Constants for converting float -> byte in waterfallBuffer (dBFS per FFT bin, 0.63 dB steps)
        const val MIN_DB = -150f
        const val MAX_DB = 10f
        const val DB_RANGE = MAX_DB - MIN_DB // 160
        const val BYTE_MAX = 255f
        const val SCALE = BYTE_MAX / DB_RANGE
        const val INV_SCALE = DB_RANGE / BYTE_MAX

        // smoothing factor for actual FPS (EMA)
        private const val FPS_EMA_ALPHA = 0.10f

        // lead-time control loop for hop size
        private const val HOP_KP = 0.35f
        private const val HOP_KI = 0.08f
        private const val LEAD_JUMP_FACTOR = 3


        // size of the internal ring buffer for input samples:
        const val MAX_FFT_SIZE = 131072
        private const val FFT_BUFFER_CAPACITY = MAX_FFT_SIZE * 10 // 10x of the max fft size

        // Computes M (analysis window size, real samples windowed per frame) from the configured
        // FFT size (N, transform size / bin count) and padding ratio. N-M is zero-padded.
        fun computeAnalysisWindowSize(fftSize: Int, paddingRatio: Float): Int {
            val ratio = paddingRatio.coerceIn(0f, 0.75f)
            return (fftSize * (1f - ratio)).roundToInt().coerceIn(1, fftSize)
        }
    }

    private val availableSamples = AtomicInteger(0)

    private var nextFrameDeadlineNs = 0L
    private var lastFrameDoneNs = 0L
    private var fpsPeriodEmaNs = 0.0
    private var hopIntegral = 0.0

    init {
        // Initialize nativeDsp with initial fft size and analysis window size.
        if (!nativeDsp.init(fftSize, FFT_BUFFER_CAPACITY, computeAnalysisWindowSize(fftSize, fftPaddingRatio))) {
            Log.e(LOGTAG, "Failed to init native DSP.")
        }
    }

    /**
     * Will start the processing loop
     */
    override fun start() {
        this.stopRequested = false
        super.start()
    }

    /**
     * Will set the stopRequested flag so that the processing loop will terminate
     */
    fun stopLoop() {
        this.stopRequested = true
        LockSupport.unpark(this) // don't sit out the rest of a frame period (up to 1 s at low fps)
    }

    fun putNewFftSamples(samples: FloatArray, count: Int) {
        val ret = nativeDsp.addNewSamples(samples, count)
        if (ret < 0) {
            Log.e(LOGTAG, "putNewFftSamples: error=${availableSamples.get()}")
        } else {
            if (ret >= FFT_BUFFER_CAPACITY) GlobalPerformanceData.incrementCounter("fft.ringOverflows")
            availableSamples.set(ret)
        }
    }

    private fun updateActualFps(frameDoneNs: Long) {
        if (lastFrameDoneNs != 0L) {
            val periodNs = (frameDoneNs - lastFrameDoneNs).toDouble()
            fpsPeriodEmaNs = if (fpsPeriodEmaNs == 0.0) {
                periodNs
            } else {
                fpsPeriodEmaNs * (1.0 - FPS_EMA_ALPHA) + periodNs * FPS_EMA_ALPHA
            }
            actualFftFramesPerSecond = (1_000_000_000.0 / fpsPeriodEmaNs).toFloat()
        }
        lastFrameDoneNs = frameDoneNs
    }

    private fun computeHop(currentSampleRate: Long, currentFftSize: Int, analysisWindowSize: Int): Int {
        val sr = currentSampleRate.toDouble()
        if (sr <= 0.0) return 1

        val sustainableFps = if (actualFftFramesPerSecond > 1f) actualFftFramesPerSecond else fftFramesPerSecond
        val desiredFps = if (fftFramesPerSecond < sustainableFps) fftFramesPerSecond else sustainableFps
        val nominalHop = sr / desiredFps

        // lead-time target for the input buffer
        val FFT_LEAD_TARGET_SECONDS = if(sr<5_000_000) 0.15f else 0.05f // choose smaller lead time on high sample rates to not overfill the buffer
        val targetLeadSamples = max(
            analysisWindowSize*2, // hold at least samples for 2 frames (the zero padding needs none)
            (sr * FFT_LEAD_TARGET_SECONDS).roundToInt()
        ).coerceAtMost(FFT_BUFFER_CAPACITY / 2) // leave room for bursts and short stalls (capacity: 65 ms at 20 MSps)

        val available = availableSamples.get().coerceAtLeast(0)
        val leadErrorRatio = ((available - targetLeadSamples).toDouble() / targetLeadSamples.toDouble()).coerceIn(-0.5, 0.5)

        GlobalPerformanceData.updateLoad("FftProcessorBufferLead", available.toFloat() / targetLeadSamples.toFloat())
        GlobalPerformanceData.updateLoad("FftProcessorBufferFill", available.toFloat() / FFT_BUFFER_CAPACITY.toFloat())

        // Far too much buffered (smaller FFT window after a size change, a stall of this thread):
        // the controller below would take seconds to catch up, with the waterfall that much behind
        // and running fast - jump to the target lead instead.
        if (available > LEAD_JUMP_FACTOR * targetLeadSamples) {
            hopIntegral = 0.0
            return (available - targetLeadSamples).coerceIn(1, FFT_BUFFER_CAPACITY - currentFftSize)
        }

        hopIntegral = (hopIntegral + leadErrorRatio).coerceIn(-4.0, 4.0)

        val correction = 1.0 + HOP_KP * leadErrorRatio + HOP_KI * hopIntegral

        val res = (nominalHop * correction)
            .roundToInt()
            .coerceIn(1, FFT_BUFFER_CAPACITY - currentFftSize) // always leave at least samples for one fft in the buffer
        return res
    }

    override fun run() {
        this.setName("Thread-FftProcessor-" + System.currentTimeMillis())
        Log.i(LOGTAG, "Processing loop started. (Thread: " + this.name + ")")

        // Newest frame, quantized like the waterfall rows (MIN_DB + byte * INV_SCALE)
        var magBytes = ByteArray(fftSize)

        var lastFrequency: Long? = null
        var lastSampleRate: Long? = null
        var lastAppliedAnalysisWindowSize = computeAnalysisWindowSize(fftSize, fftPaddingRatio)

        // performance tracking
        var totalWaitTimeNs = 0L
        var totalWorkTimeNs = 0L

        while (!stopRequested) {
            val currentFftSize = fftSize
            val currentSampleRate = sampleRate
            val currentFrequency = frequency
            val currentAnalysisWindowSize = computeAnalysisWindowSize(currentFftSize, fftPaddingRatio)
            if (magBytes.size != currentFftSize || currentAnalysisWindowSize != lastAppliedAnalysisWindowSize) {
                if (magBytes.size != currentFftSize) magBytes = ByteArray(currentFftSize)
                nativeDsp.setFftSize(currentFftSize, currentAnalysisWindowSize)
                lastAppliedAnalysisWindowSize = currentAnalysisWindowSize
            }

            val measureStartNs = System.nanoTime()

            if (nextFrameDeadlineNs == 0L) {
                nextFrameDeadlineNs = measureStartNs
            }

            val sleepNs = nextFrameDeadlineNs - measureStartNs
            if (sleepNs > 0L) LockSupport.parkNanos(sleepNs)
            if (stopRequested) break
            val workStartNs = System.nanoTime()
            val waitDurationNs = workStartNs - measureStartNs

            val hop = computeHop(currentSampleRate, currentFftSize, currentAnalysisWindowSize)

            val actualConsumedSamples = nativeDsp.performWindowedFftAndReturnMag(magBytes, MIN_DB, SCALE, hop)
            val frameDoneNs = System.nanoTime()

            if (actualConsumedSamples == NativeDsp.NOT_ENOUGH_SAMPLES) {
                // Less than one analysis window buffered (start, retune, source gap): wait for the
                // missing samples instead of showing a partially filled (too weak) frame.
                GlobalPerformanceData.incrementCounter("fft.skippedFrames")
                val missingNs = (currentAnalysisWindowSize - availableSamples.get()).coerceAtLeast(1) * 1_000_000_000L / currentSampleRate.coerceAtLeast(1)
                LockSupport.parkNanos(missingNs.coerceIn(1_000_000L, 50_000_000L))
                continue
            } else if (actualConsumedSamples < 0) {
                Log.e(LOGTAG, "run: nativePerformWindowedFftAndReturnMag returned error code $actualConsumedSamples")
                LockSupport.parkNanos(10_000_000L) // don't spin on a persistent error
                continue
            } else if (actualConsumedSamples != hop) {
                Log.d(LOGTAG, "run: nativePerformWindowedFftAndReturnMag returned $actualConsumedSamples samples (hop=$hop)")
            }

            availableSamples.addAndGet(-actualConsumedSamples)

            updateActualFps(frameDoneNs)
            GlobalPerformanceData.updateLoad("FftProcessorFPS", actualFftFramesPerSecond / 100)

            // Frame deadlines on a fixed time base.
            val targetPeriodNs = (1_000_000_000.0 / fftFramesPerSecond.coerceAtLeast(1f)).toLong()
            if (nextFrameDeadlineNs == 0L) {
                nextFrameDeadlineNs = frameDoneNs + targetPeriodNs
            } else {
                nextFrameDeadlineNs += targetPeriodNs
                // If we fell behind by more than one period, snap back to real time.
                if (frameDoneNs > nextFrameDeadlineNs + targetPeriodNs) {
                    nextFrameDeadlineNs = frameDoneNs + targetPeriodNs
                }
            }

            // The channel's level and the noise floor beside it: the squelch's decision and the UI's level
            val samplesPerHz = magBytes.size / currentSampleRate.toFloat()
            val frequencyAtIndexZero = currentFrequency - currentSampleRate / 2
            val (channelStartFrequency, channelEndFrequency) = channelFrequencyRange
            val channelStartIndex =
                ((channelStartFrequency - frequencyAtIndexZero) * samplesPerHz).toInt()
                    .coerceIn(0, magBytes.size)
            val channelEndIndex =
                ((channelEndFrequency - frequencyAtIndexZero) * samplesPerHz).toInt()
                    .coerceIn(0, magBytes.size)
            // a channel narrower than a bin still has its bin
            val channelEnd = maxOf(channelEndIndex, minOf(channelStartIndex + 1, magBytes.size))
            val channelLevel = channelLevel(magBytes, channelStartIndex, channelEnd)
            if (!channelLevel.isNaN()) {
                squelchGate.update(channelLevel, noiseFloor(magBytes, channelStartIndex, channelEnd), System.currentTimeMillis())
                onChannelLevelChanged(channelLevel)
            }

            // --- Put the results into fftProcessorData
            try {
                fftProcessorData.lock.writeLock().lock()
                fftProcessorData.frequency = currentFrequency
                fftProcessorData.sampleRate = currentSampleRate

                val frequencyChanged = currentFrequency != lastFrequency
                val sampleRateChanged = currentSampleRate != lastSampleRate
                fftProcessorData.frequencyOrSampleRateChanged =
                    frequencyChanged || sampleRateChanged

                val frequencyDiff =
                    if (lastFrequency != null) lastFrequency - currentFrequency else 0L
                lastFrequency = currentFrequency
                lastSampleRate = currentSampleRate

                // update waterfallBuffer if fftSize changed
                if (fftProcessorData.waterfallBuffer == null || fftProcessorData.waterfallBuffer!![0].size != magBytes.size) {
                    fftProcessorData.waterfallBuffer =
                        Array(waterfallBufferSize) { ByteArray(magBytes.size) }
                    fftProcessorData.waterfallBufferDirtyMap =
                        Array(waterfallBufferSize) { true }
                    fftProcessorData.writeIndex = 0
                }

                if (frequencyDiff != 0L) {
                    // shift history samples because the source frequency changed
                    val shiftOffset = (frequencyDiff * samplesPerHz).toInt()
                    val shiftLeft = shiftOffset < 0
                    if ((shiftLeft && shiftOffset * -1 < magBytes.size) || (!shiftLeft && shiftOffset < magBytes.size)) {
                        fftProcessorData.waterfallBuffer!!.forEach {
                            if (shiftLeft) {
                                System.arraycopy(
                                    it,
                                    shiftOffset * -1,
                                    it,
                                    0,
                                    it.size + shiftOffset
                                ) // Shift left
                                it.fill(0, it.size + shiftOffset, it.size) // Fill right side
                            } else {
                                System.arraycopy(
                                    it,
                                    0,
                                    it,
                                    shiftOffset,
                                    it.size - shiftOffset
                                ) // Shift right
                                it.fill(0, 0, shiftOffset) // Fill left side
                            }
                        }
                    } else {
                        // clear entire history
                        fftProcessorData.waterfallBuffer!!.forEach { it.fill(0) }
                    }
                    fftProcessorData.waterfallBufferDirtyMap!!.fill(true)
                } else if (sampleRateChanged) {
                    // clear entire history
                    fftProcessorData.waterfallBuffer!!.forEach { it.fill(0) }
                    fftProcessorData.waterfallBufferDirtyMap!!.fill(true)
                }

                // copy newest samples into history
                System.arraycopy(magBytes, 0, fftProcessorData.waterfallBuffer!![fftProcessorData.writeIndex], 0, magBytes.size)
                fftProcessorData.waterfallBufferDirtyMap!![fftProcessorData.writeIndex] = true

                // update the read/write indices
                fftProcessorData.newestRowTimeNs = frameDoneNs
                // the measured period if the device can't keep up with the setting
                fftProcessorData.rowPeriodNs = if (actualFftFramesPerSecond > 1f) max(targetPeriodNs, (1e9 / actualFftFramesPerSecond).toLong()) else targetPeriodNs
                fftProcessorData.readIndex = fftProcessorData.writeIndex
                fftProcessorData.writeIndex =
                    if (fftProcessorData.writeIndex == 0) fftProcessorData.waterfallBuffer!!.size - 1 else fftProcessorData.writeIndex - 1

                // Update Peak Hold
                if (fftPeakHold) {
                    val arraySize = magBytes.size
                    if (fftProcessorData.peaks == null || fftProcessorData.peaks!!.size != arraySize) {
                        fftProcessorData.peaks = FloatArray(arraySize)
                        fftProcessorData.peaks!!.fill(-999999f)
                    }
                    val peaks = fftProcessorData.peaks!!
                    if (fftProcessorData.frequencyOrSampleRateChanged)
                        peaks.fill(-999999f)
                    for (i in 0 until arraySize)
                        peaks[i] = max(peaks[i], (magBytes[i].toInt() and 0xFF) * INV_SCALE + MIN_DB)
                } else {
                    fftProcessorData.peaks = null
                }
            } finally {
                fftProcessorData.lock.writeLock().unlock()
            }

            // Update timing metrics
            val fftDurationNs = System.nanoTime() - workStartNs
            totalWorkTimeNs += fftDurationNs
            totalWaitTimeNs += waitDurationNs

            if (totalWorkTimeNs + totalWaitTimeNs > 1_000_000_000L) { // update every second
                val totalCycleTime: Long = totalWaitTimeNs + totalWorkTimeNs
                val currentLoad = totalWorkTimeNs.toDouble() / totalCycleTime
                GlobalPerformanceData.updateLoad("FftProcessor", currentLoad.toFloat())
                totalWorkTimeNs = 0
                totalWaitTimeNs = 0
            }
        }

        // clean up native resources
        nativeDsp.release()

        this.stopRequested = true
        Log.i(LOGTAG, "Processing loop stopped. (Thread: " + this.name + ")")
    }
}