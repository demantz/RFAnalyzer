package com.mantz_it.nativedsp

/**
 * <h1>RF Analyzer - Native Byte Converter</h1>
 *
 * Module:      NativeByteConverter.kt
 * Description: 8/16-bit IQ samples -> floats, natively (the spectrum FFT feed of the 8/16-bit IQ
 *              converters: a JVM table lookup per value costs ~6 cycles, at HackRF 20 MSps that is
 *              40 M values/s).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object NativeByteConverter {
    init {
        System.loadLibrary("nativedsp")
    }

    /**
     * out[i] = packet[i] / 128 (signed bytes) or (packet[i] - 127.4) / 128 (unsigned bytes, RTL-SDR)
     * for i < count - the same values as the converters' lookup tables.
     */
    fun toFloats(packet: ByteArray, count: Int, isUnsigned: Boolean, out: FloatArray) {
        require(count <= packet.size && count <= out.size)
        nativeToFloats(packet, count, isUnsigned, out)
    }

    /** out[i] = (little-endian int16 number i of packet) / 32768 for i < count. */
    fun s16ToFloats(packet: ByteArray, count: Int, out: FloatArray) {
        require(2 * count <= packet.size && count <= out.size)
        nativeS16ToFloats(packet, count, out)
    }

    @JvmStatic
    private external fun nativeS16ToFloats(packet: ByteArray, count: Int, out: FloatArray)

    @JvmStatic
    private external fun nativeToFloats(packet: ByteArray, count: Int, isUnsigned: Boolean, out: FloatArray)
}
