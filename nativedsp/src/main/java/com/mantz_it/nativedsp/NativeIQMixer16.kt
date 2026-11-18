package com.mantz_it.nativedsp

/**
 * <h1>RF Analyzer - Native 16-Bit IQ Mixer</h1>
 *
 * Module:      NativeIQMixer16.kt
 * Description: The native mixing loop of Signed16BitIQConverter (signed 16-bit IQ -> complex
 *              floats, iq_word_mixer.c). Each instance owns its native state: different threads may
 *              use different instances (same threading model as NativeIQMixer).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class NativeIQMixer16 {

    private var nativeHandle: Long = 0

    init {
        nativeHandle = nativeCreate()
        require(nativeHandle != 0L) { "Failed to create native IQMixer16 instance" }
    }

    // =========================
    // Native bindings
    // =========================
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetValueTable(handle: Long, valueTable: FloatArray)
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

    /** Loads the 65536-entry value-conversion LUT (raw unsigned 16-bit sample -> float). Call
     * once, right after construction - Signed16BitIQConverter only ever builds this table once. */
    fun setValueTable(valueTable: FloatArray) {
        nativeSetValueTable(nativeHandle, valueTable)
    }

    /** Loads a new per-time-step NCO cos/sin table and resets the cosine index to 0 - called by
     * Signed16BitIQConverter.generateMixerLookupTable() whenever it regenerates cosLut/sinLut. */
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
