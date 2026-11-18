package com.mantz_it.nativedsp

/**
 * <h1>RF Analyzer - Native IQ Mixer</h1>
 *
 * Module:      NativeIQMixer.kt
 * Description: The native mixing loop of Signed8BitIQConverter (signed 8-bit IQ -> complex floats,
 *              iq_byte_mixer.c). Each instance owns its native state: different threads may use
 *              different instances (same threading model as NativeRationalResampler).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class NativeIQMixer {

    private var nativeHandle: Long = 0

    init {
        nativeHandle = nativeCreate()
        require(nativeHandle != 0L) { "Failed to create native IQMixer instance" }
    }

    // =========================
    // Native bindings
    // =========================
    private external fun nativeCreate(): Long
    private external fun nativeDestroy(handle: Long)
    private external fun nativeSetMixerTable(handle: Long, realTableFlat: FloatArray, imagTableFlat: FloatArray, tableLength: Int)

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

    /**
     * Loads a new [tableLength]x256 mixer lookup table (flattened row-major: index = row*256 +
     * col) and resets the cosine index to 0 - called by ByteIQConverter.generateMixerLookupTable()
     * whenever it regenerates cosineRealLookupTable/cosineImagLookupTable.
     */
    fun setMixerTable(realTableFlat: FloatArray, imagTableFlat: FloatArray, tableLength: Int) {
        nativeSetMixerTable(nativeHandle, realTableFlat, imagTableFlat, tableLength)
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
