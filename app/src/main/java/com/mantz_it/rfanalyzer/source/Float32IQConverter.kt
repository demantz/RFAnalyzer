package com.mantz_it.rfanalyzer.source

import android.util.Log
import com.mantz_it.nativedsp.NativeIQMixerFloat
import com.mantz_it.rfanalyzer_ipc.SamplePacket

/**
 * <h1>RF Analyzer - Complex Float 32-Bit IQ Converter</h1>
 *
 * Module:      Float32IQConverter.kt
 * Description: Interleaved little-endian complex float (CF32) IQ samples, as the AirspyHF
 *              delivers them: copied to the FFT feed, or mixed to baseband into SamplePackets.
 *
 * Performance: mixPacketIntoSamplePacket()'s per-sample loop runs natively (NativeIQMixerFloat,
 * nativedsp/src/main/cpp/iq_float_mixer.c). The Kotlin loop below is the fallback when the native
 * library can't be loaded (a plain JVM unit test).
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class Float32IQConverter(
    // Tests only: the Kotlin loop even when the native mixer is available, to compare the two.
    forceKotlinFallback: Boolean = false
) : IQConverter() {
    companion object {
        private const val LOGTAG = "Float32IQConverter"
    }

    // Sin/Cos lookup tables
    private var cosLut: FloatArray? = null
    private var sinLut: FloatArray? = null

    private val nativeMixer: NativeIQMixerFloat? = if (forceKotlinFallback) {
        null
    } else {
        try {
            NativeIQMixerFloat()
        } catch (e: Throwable) {
            Log.w(LOGTAG, "Native IQMixerFloat unavailable, falling back to Kotlin implementation: ${e.message}")
            null
        }
    }

    /** Whether the native mixer runs (not the Kotlin fallback). For tests. */
    val isUsingNative: Boolean get() = nativeMixer != null

    override fun generateLookupTable() {
        // Not needed for float data
    }

    override fun generateMixerLookupTable(mixFrequencyIn: Int) {
        var mixFrequency = mixFrequencyIn
        // If mix frequency is too low, just add the sample rate (sampled spectrum is periodic):
        if (mixFrequency == 0 || (sampleRate / kotlin.math.abs(mixFrequency) > MAX_COSINE_LENGTH)) {
            mixFrequency += sampleRate
        }

        // Only regenerate if needed
        if (cosLut == null || mixFrequency != cosineFrequency) {
            cosineFrequency = mixFrequency
            val bestLength = calcOptimalCosineLength()

            val cosArr = FloatArray(bestLength)
            val sinArr = FloatArray(bestLength)
            val twoPiFOverFs = (2.0 * Math.PI * cosineFrequency) / sampleRate.toDouble()

            var t = 0
            while (t < bestLength) {
                val angle = twoPiFOverFs * t
                cosArr[t] = kotlin.math.cos(angle).toFloat()
                sinArr[t] = kotlin.math.sin(angle).toFloat()
                t++
            }

            cosLut = cosArr
            sinLut = sinArr
            cosineIndex = 0

            nativeMixer?.setNcoTable(cosArr, sinArr, bestLength)
        }
    }

    override fun fillPacketIntoInterleavedBuffer(packet: ByteArray, interleavedBuffer: FloatArray, validByteCount: Int): Boolean {
        val floatCount = validByteCount.coerceIn(0, packet.size) / 4
        if (interleavedBuffer.size < floatCount) return false
        // Interpret the ByteArray as FloatArray without copying, then copy into the output buffer
        java.nio.ByteBuffer.wrap(packet)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(interleavedBuffer, 0, floatCount)
        return true
    }

    override fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long, validByteCount: Int): Int {
        val length = validByteCount.coerceIn(0, packet.size)
        val mixFrequency = (frequency - channelFrequency).toInt()
        generateMixerLookupTable(mixFrequency)

        val count: Int
        if (nativeMixer != null) {
            val outOffset = samplePacket.size()
            val (_, produced) = nativeMixer.mix(
                packet, 0, length,
                samplePacket.re(), samplePacket.im(), outOffset, samplePacket.capacity()
            )
            count = produced
            samplePacket.setSize(outOffset + count)
        } else {
            count = mixPacketIntoSamplePacketKotlinFallback(packet, length, samplePacket)
        }
        samplePacket.sampleRate = sampleRate
        samplePacket.frequency = channelFrequency
        stampFromClock(samplePacket)
        return count
    }

    /** The Kotlin engine, used when native is unavailable (sample rate, frequency and timestamp
     * are set by mixPacketIntoSamplePacket()). */
    private fun mixPacketIntoSamplePacketKotlinFallback(packet: ByteArray, length: Int, samplePacket: SamplePacket): Int {
        val capacity = samplePacket.capacity()
        val startIndex = samplePacket.size()
        if (startIndex >= capacity) return 0

        val re = samplePacket.re()
        val im = samplePacket.im()
        val cosArr = cosLut!!
        val sinArr = sinLut!!
        val cLen = cosArr.size
        var cIdx = cosineIndex

        val floatBuf = java.nio.ByteBuffer.wrap(packet, 0, length)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()

        val samplesAvailable = floatBuf.remaining() / 2
        val samplesToCopy = minOf(samplesAvailable, capacity - startIndex)

        var i = 0
        var outIdx = startIndex

        // Mix: (I + jQ) * e^{jωt} = (I*cos - Q*sin) + j(Q*cos + I*sin), ω of frequency - channelFrequency
        while (i < samplesToCopy) {
            val I = floatBuf.get()
            val Q = floatBuf.get()

            val c = cosArr[cIdx]
            val s = sinArr[cIdx]

            re[outIdx] = I * c - Q * s
            im[outIdx] = Q * c + I * s

            cIdx++
            if (cIdx == cLen) cIdx = 0

            i++
            outIdx++
        }

        cosineIndex = cIdx
        samplePacket.setSize(startIndex + samplesToCopy)
        return samplesToCopy
    }

    /** Frees native resources. Safe to call even if the Kotlin fallback engine is in use. */
    override fun close() {
        nativeMixer?.close()
    }
}