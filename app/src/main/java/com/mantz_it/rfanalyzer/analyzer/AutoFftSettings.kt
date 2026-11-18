package com.mantz_it.rfanalyzer.analyzer

import android.app.ActivityManager
import android.content.Context
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * <h1>RF Analyzer - Auto FFT Settings</h1>
 *
 * Module:      AutoFftSettings.kt
 * Description: Computes the (FFT size, FFT padding ratio) pair used when the user hasn't enabled
 *              Manual FFT Size, from the sample rate and the waterfall speed.
 *
 * The analysis window M (samples windowed per frame) is the time slot of one waterfall row,
 * sampleRate / fps: every row then shows exactly its own stretch of signal - no gaps in which
 * a burst could be missed, no smear across rows - with the finest frequency resolution that
 * row rate allows (Blackman: 1.73 * fps Hz). Above MAX_ROW_WINDOW_FPS the window stays at
 * sampleRate / 30 and the rows overlap: a fast waterfall is mostly used to stretch the time
 * axis of a zoomed-in signal (RTTY bits), and a window of one 230 fps row (~400 Hz resolution)
 * would merge its tones. So the resolution never gets coarser than 52 Hz before the cap.
 * At low sample rates M also has a floor (2048 at 12 kSps, 4096 from 48 kSps up) for the
 * narrowband signals found there. The FFT size N zero-pads M by at least 1.5x, which keeps
 * zoomed-in views smooth (interpolated bins, no extra resolution). N is capped by memory (the
 * waterfall history holds N bytes per row) and by CPU (FFT points per second): at the cap
 * M = N.
 *
 * The cost: FFT 131072 at 30 fps takes ~9 % of a big core of a Pixel 4a 5G; on a 4 MSps HF
 * recording it shows weak CW/FT8 lines at 64x zoom that a 32768-point FFT doesn't.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object AutoFftSettings {
    private const val MIN_FFT_SIZE = 4096            // >= ~4 bins per pixel on a phone screen
    private const val MIN_WINDOW_SQRT_FACTOR = 18.7  // window floor = 18.7 * sqrt(sampleRate): 2048 at 12 kSps
    private const val MAX_WINDOW_FLOOR = 4096        // ... up to 4096 (48 kSps)
    private const val MIN_PADDING_FACTOR = 1.5       // N >= 1.5 * M
    private const val MAX_ROW_WINDOW_FPS = 30f       // faster waterfalls overlap rows instead of shortening the window
    private const val REFERENCE_FPS = 60f            // the memory cap is affordable (CPU) up to this waterfall speed
    private const val LOW_PERFORMANCE_MODE_MAX_FFT_SIZE = 16384

    /**
     * Largest FFT size the automatic choice uses on this device, by its heap size (also a proxy
     * for the device class): the waterfall history takes 400 rows x N bytes (52 MB at 131072).
     */
    fun deviceMaxFftSize(context: Context): Int {
        val largeHeapMb = (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).largeMemoryClass
        return when {
            largeHeapMb >= 512 -> 131072
            largeHeapMb >= 256 -> 65536
            else -> 32768
        }.coerceAtMost(FftProcessor.MAX_FFT_SIZE)
    }

    /**
     * Computes (fftSize, paddingRatio) for the given sample rate and waterfall speed.
     *
     * @param maxFftSize the device's cap (see [deviceMaxFftSize])
     * @param lowPerformanceMode when true, caps the FFT size at [LOW_PERFORMANCE_MODE_MAX_FFT_SIZE]
     *        for less capable devices.
     */
    fun compute(sampleRate: Long, fps: Float, maxFftSize: Int, lowPerformanceMode: Boolean = false): Pair<Int, Float> {
        val sr = sampleRate.coerceAtLeast(1).toDouble()
        val framesPerSecond = fps.coerceAtLeast(1f)
        val memoryCap = if (lowPerformanceMode) min(maxFftSize, LOW_PERFORMANCE_MODE_MAX_FFT_SIZE) else maxFftSize
        // CPU: FFT points per second, faster waterfalls get proportionally smaller FFTs
        val cap = max(MIN_FFT_SIZE, min(memoryCap, powerOfTwoAtMost(memoryCap * REFERENCE_FPS / framesPerSecond)))

        val windowFloor = min(MIN_WINDOW_SQRT_FACTOR * sqrt(sr), MAX_WINDOW_FLOOR.toDouble())
        val window = min(max(windowFloor, sr / min(framesPerSecond, MAX_ROW_WINDOW_FPS)), cap.toDouble())
        val fftSize = max(MIN_FFT_SIZE, powerOfTwoAtLeast(window * MIN_PADDING_FACTOR)).coerceAtMost(cap)
        val paddingRatio = (1.0 - window / fftSize).toFloat().coerceIn(0f, 0.75f)
        return fftSize to paddingRatio
    }

    private fun powerOfTwoAtLeast(x: Double): Int {
        var n = 1
        while (n < ceil(x) && n < FftProcessor.MAX_FFT_SIZE) n = n shl 1
        return n
    }

    private fun powerOfTwoAtMost(x: Float): Int {
        var n = 1
        while (n * 2 <= x) n = n shl 1
        return n
    }
}
