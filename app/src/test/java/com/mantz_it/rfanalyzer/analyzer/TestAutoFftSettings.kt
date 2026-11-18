package com.mantz_it.rfanalyzer.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Auto FFT Settings Tests</h1>
 *
 * Module:      TestAutoFftSettings.kt
 * Description: Tests for AutoFftSettings: the analysis window is one waterfall row above the
 *              low-rate floor, faster waterfalls overlap rows instead of shortening the window (up
 *              to the CPU cap), high sample rates are capped without padding, and every result is a
 *              power-of-two FFT size within the cap with a padding ratio between 0 and 0.75.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestAutoFftSettings {

    private fun window(sampleRate: Long, fps: Float = 30f, maxFftSize: Int = 131072, lowPerformanceMode: Boolean = false): Pair<Int, Int> {
        val (fftSize, padding) = AutoFftSettings.compute(sampleRate, fps, maxFftSize, lowPerformanceMode)
        return fftSize to FftProcessor.computeAnalysisWindowSize(fftSize, padding)
    }

    @Test
    fun windowIsOneWaterfallRowAboveTheFloor() {
        // 1 MSps at 30 fps: one row = 33333 samples
        assertEquals(65536 to 33333, window(1_000_000))
        assertEquals(131072 to 83333, window(2_500_000))
        // slower waterfall: longer window
        assertEquals(131072 to 76800, window(384_000, fps = 5f))
    }

    @Test
    fun fasterWaterfallsOverlapRowsInsteadOfShorteningTheWindow() {
        // RTTY in a 384 kSps recording at 91/232 fps: the window of 30 rows/s (52 Hz) stays
        assertEquals(32768 to 12800, window(384_000, fps = 91f))
        assertEquals(32768 to 12800, window(384_000, fps = 232f))
        assertEquals(65536 to 41667, window(1_250_000, fps = 120f))
        // the CPU cap (FFT points per second) limits it at high rates and speeds
        assertEquals(32768 to 32768, window(2_400_000, fps = 232f))
    }

    @Test
    fun lowSampleRatesKeepTheWindowFloor() {
        assertEquals(4096 to 2048, window(12_000))
        assertEquals(8192 to 4096, window(48_000))
        // the floor wins over a fast waterfall
        assertEquals(8192 to 4096, window(48_000, fps = 120f))
    }

    @Test
    fun highSampleRatesAreCappedWithoutPadding() {
        assertEquals(131072 to 131072, window(10_000_000))
        assertEquals(65536 to 65536, window(10_000_000, maxFftSize = 65536))
        assertEquals(16384 to 16384, window(10_000_000, lowPerformanceMode = true))
    }

    @Test
    fun resultIsAlwaysValid() {
        for (sr in listOf(1_000L, 12_000L, 48_000L, 192_000L, 768_000L, 2_000_000L, 20_000_000L, 61_440_000L))
            for (fps in listOf(1f, 5f, 30f, 60f, 163f))
                for (cap in listOf(32768, 65536, 131072)) {
                    val (fftSize, padding) = AutoFftSettings.compute(sr, fps, cap)
                    assertTrue("$sr $fps $cap -> $fftSize", fftSize in 4096..cap && Integer.bitCount(fftSize) == 1)
                    assertTrue("$sr $fps $cap -> $padding", padding in 0f..0.75f)
                }
    }
}
