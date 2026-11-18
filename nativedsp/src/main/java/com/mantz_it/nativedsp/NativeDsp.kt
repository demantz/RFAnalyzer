package com.mantz_it.nativedsp

/**
 * <h1>RF Analyzer - Native DSP</h1>
 *
 * Module:      NativeDsp.kt
 * Description: Kotlin wrapper for the native DSP FFT ringbuffer implementation.
 *
 * Usage:
 *  - call init(fftSize, bufferCapacity) once (or call init with a small fftSize then call
 *    setFftSize later)
 *  - continuously call addNewSamples(interleavedSamples, floatCount) as SDR samples arrive
 *  - call performWindowedFftAndReturnMag(magOut, minDb, bytesPerDb, hop) on the owning (FFT)
 *    thread to get FFT frames
 *  - call setFftSize(N) on the owning thread to change the FFT size at runtime without losing
 *    samples
 *  - call release() when finished
 *
 * Notes:
 *  - All sizes are in complex samples unless noted.
 *  - The returned magOut uses centered frequency layout (0 frequency in the middle) and contains
 *    the power per bin in dBFS, quantized to unsigned bytes ((dB - minDb) * bytesPerDb): a
 *    full-scale complex tone reads 0 dB, independent of FFT size and padding (the window's
 *    coherent gain is compensated).
 *  - Every instance has its own native state.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class NativeDsp {

    companion object {
        /** performWindowedFftAndReturnMag(): fewer than one analysis window buffered. */
        const val NOT_ENOUGH_SAMPLES = -6

        init {
            System.loadLibrary("nativedsp")
        }
    }

    // native FftState*, 0 = not initialized. init/release/addNewSamples hold [lock]: samples arrive
    // on the source thread while the owning (FFT) thread may release the native state.
    private var handle = 0L
    private val lock = Any()
    private val initialized get() = handle != 0L
    private var fftSize = 0
    private var bufferCapacity = 0

    /**
     * Initialize native resources.
     * @param fftSizeComplex number of complex samples for FFT (N) -- the transform size / bin count
     * @param bufferCapacityComplex capacity of ring buffer in complex samples (>= fftSizeComplex,
     *                            else init fails); it never grows, so it also bounds setFftSize().
     * @param analysisWindowSizeComplex number of buffered samples windowed per frame (M <= N); the
     *                            remaining N-M samples of the transform input are zero-padded
     *                            (FFT padding). Defaults to N (no padding).
     */
    fun init(fftSizeComplex: Int, bufferCapacityComplex: Int = fftSizeComplex * 4, analysisWindowSizeComplex: Int = fftSizeComplex): Boolean {
        if (fftSizeComplex <= 0 || bufferCapacityComplex <= 0 || analysisWindowSizeComplex <= 0 || analysisWindowSizeComplex > fftSizeComplex) return false
        release()
        synchronized(lock) {
            handle = nativeInit(fftSizeComplex, bufferCapacityComplex, analysisWindowSizeComplex)
            if (initialized) {
                fftSize = fftSizeComplex
                bufferCapacity = bufferCapacityComplex
            }
            return initialized
        }
    }

    /**
     * Release native resources.
     */
    fun release() {
        synchronized(lock) {
            if (initialized) {
                nativeRelease(handle)
                handle = 0L
                fftSize = 0
                bufferCapacity = 0
            }
        }
    }

    /**
     * Change the FFT size (and/or analysis window size) at runtime, on the owning thread. Does
     * not discard the ring buffer contents; fails if newFftSize exceeds the ring buffer's capacity.
     *
     * @param newAnalysisWindowSize number of buffered samples windowed per frame (M <= newFftSize);
     *                               defaults to newFftSize (no padding).
     *
     * Returns true on success.
     */
    fun setFftSize(newFftSize: Int, newAnalysisWindowSize: Int = newFftSize): Boolean {
        if (!initialized) return false
        if (newFftSize <= 0 || newAnalysisWindowSize <= 0 || newAnalysisWindowSize > newFftSize) return false
        val ok = nativeSetFftSize(handle, newFftSize, newAnalysisWindowSize)
        if (ok) {
            fftSize = newFftSize
        }
        return ok
    }

    /**
     * Append the first [floatCount] floats (interleaved I/Q, 2 per complex sample) of
     * [interleavedSamples] to the native circular buffer.
     *
     * Overwrites oldest samples when buffer is full.
     */
    fun addNewSamples(interleavedSamples: FloatArray, floatCount: Int = interleavedSamples.size): Int {
        synchronized(lock) {
            if (!initialized) return -1
            return nativeAddNewSamples(handle, interleavedSamples, floatCount.coerceIn(0, interleavedSamples.size))
        }
    }

    /**
     * Perform windowed FFT using the current read position of the ring buffer.
     *
     * - magOut must have length == current fftSize (number of bins).
     * - numberOfConsumedSamples is the *upper limit* on how many complex samples to advance.
     *   The native method will advance at most this many samples; if fewer samples are available
     *   it will advance to the end of available samples and return that actual number.
     *
     * Returns:
     *  - >= 0 : actual number of consumed (advanced) complex samples
     *  - [NOT_ENOUGH_SAMPLES]: fewer than one analysis window buffered, no frame computed
     *  - < 0  : other error code (see nativedsp.cpp error codes)
     */
    fun performWindowedFftAndReturnMag(magOut: ByteArray, minDb: Float, bytesPerDb: Float, numberOfConsumedSamples: Int): Int {
        if (!initialized) return -1
        if (magOut.size != fftSize) return -2
        if (numberOfConsumedSamples <= 0) return -3
        return nativePerformWindowedFftAndReturnMag(handle, magOut, minDb, bytesPerDb, numberOfConsumedSamples)
    }

    // --- native methods ---
    private external fun nativeInit(fftSize: Int, bufferCapacity: Int, analysisWindowSize: Int): Long
    private external fun nativeRelease(handle: Long)
    private external fun nativeSetFftSize(handle: Long, newFftSize: Int, newAnalysisWindowSize: Int): Boolean
    private external fun nativeAddNewSamples(handle: Long, interleavedSamples: FloatArray, floatCount: Int): Int
    private external fun nativePerformWindowedFftAndReturnMag(handle: Long, magOut: ByteArray, minDb: Float, bytesPerDb: Float, numberOfConsumedSamples: Int): Int
}