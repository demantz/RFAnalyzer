package com.mantz_it.nativedsp

/**
 * <h1>RF Analyzer - Native Rational Resampler</h1>
 *
 * Module:      NativeRationalResampler.kt
 * Description: The native polyphase rational resampler behind the app's dsp/RationalResampler
 *              (rational_resampler.c). Each instance owns its native state: different threads may
 *              use different instances. Plain arrays; RationalResampler handles the SamplePackets.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class NativeRationalResampler(interpolation: Int, decimation: Int, flatFirTaps: FloatArray, nt: Int) {

    private var nativeHandle: Long = 0

    init {
        require(interpolation > 0) { "interpolation must be > 0" }
        require(decimation > 0) { "decimation must be > 0" }
        require(nt > 0) { "nt must be > 0" }
        require(flatFirTaps.size == interpolation * nt) { "flatFirTaps.size must equal interpolation*nt" }
        nativeHandle = nativeCreate(interpolation, decimation, flatFirTaps, nt)
        require(nativeHandle != 0L) { "Failed to create native RationalResampler instance" }
    }

    // =========================
    // Native bindings
    // =========================
    private external fun nativeCreate(interpolation: Int, decimation: Int, flatFirTaps: FloatArray, nt: Int): Long
    private external fun nativeDestroy(handle: Long)

    // Returns a packed result: (produced.toLong() shl 32) or (consumed.toLong() and 0xFFFFFFFFL)
    private external fun nativeResample(
        handle: Long,
        inRe: FloatArray, inIm: FloatArray, offset: Int, length: Int,
        outRe: FloatArray, outIm: FloatArray, outOffset: Int, outCapacity: Int
    ): Long

    companion object {
        init {
            System.loadLibrary("nativedsp")
        }
    }

    /**
     * Resamples [length] input samples starting at [offset] into [outRe]/[outIm] starting at
     * [outOffset], writing at most (outCapacity - outOffset) output samples.
     * @return Pair(consumed, produced)
     */
    fun resample(
        inRe: FloatArray, inIm: FloatArray, offset: Int, length: Int,
        outRe: FloatArray, outIm: FloatArray, outOffset: Int, outCapacity: Int
    ): Pair<Int, Int> {
        val packed = nativeResample(nativeHandle, inRe, inIm, offset, length, outRe, outIm, outOffset, outCapacity)
        val consumed = (packed and 0xFFFFFFFFL).toInt()
        val produced = (packed ushr 32).toInt()
        return Pair(consumed, produced)
    }

    /**
     * Free native resources. Must be called when done with this instance.
     */
    fun close() {
        if (nativeHandle != 0L) {
            nativeDestroy(nativeHandle)
            nativeHandle = 0
        }
    }

    @Suppress("deprecation")
    protected fun finalize() {
        close()
    }
}
