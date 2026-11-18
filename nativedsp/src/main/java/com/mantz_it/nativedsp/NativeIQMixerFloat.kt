package com.mantz_it.nativedsp

/**
 * <h1>RF Analyzer - Native Float IQ Mixer</h1>
 *
 * Module:      NativeIQMixerFloat.kt
 * Description: The native mixing loop of Float32IQConverter (CF32 IQ -> complex floats,
 *              iq_float_mixer.c). Each instance owns its native state: different threads may use
 *              different instances (same threading model as NativeIQMixer).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class NativeIQMixerFloat {

    private var nativeHandle: Long = 0

    init {
        nativeHandle = nativeCreate()
        require(nativeHandle != 0L) { "Failed to create native IQMixerFloat instance" }
    }

    // =========================
    // Native bindings
    // =========================
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetNcoTable(handle: Long, cosArr: FloatArray, sinArr: FloatArray, ncoLength: Int)

    // Returns a packed result: (produced.toLong() shl 32) or (consumed.toLong() and 0xFFFFFFFFL)
    private external fun nativeMix(
        handle: Long,
        packet: ByteArray, offset: Int, length: Int,
        outRe: FloatArray, outIm: FloatArray, outOffset: Int, outCapacity: Int
    ): Long

    companion object {
        init {
            System.loadLibrary("nativedsp")
        }
    }

    /** Loads a new per-time-step NCO cos/sin table and resets the cosine index to 0 - called by
     * Float32IQConverter.generateMixerLookupTable() whenever it regenerates cosLut/sinLut. */
    fun setNcoTable(cosArr: FloatArray, sinArr: FloatArray, ncoLength: Int) {
        nativeSetNcoTable(nativeHandle, cosArr, sinArr, ncoLength)
    }

    /** @return Pair(consumed, produced) - both counted in complex-sample pairs. */
    fun mix(
        packet: ByteArray, offset: Int, length: Int,
        outRe: FloatArray, outIm: FloatArray, outOffset: Int, outCapacity: Int
    ): Pair<Int, Int> {
        val packed = nativeMix(nativeHandle, packet, offset, length, outRe, outIm, outOffset, outCapacity)
        return Pair((packed and 0xFFFFFFFFL).toInt(), (packed ushr 32).toInt())
    }

    /** Free native resources. Must be called when done with this instance. */
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
