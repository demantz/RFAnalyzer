package com.mantz_it.rfanalyzer.source

import android.util.Log
import com.mantz_it.nativedsp.NativeByteConverter
import com.mantz_it.nativedsp.NativeIQMixer16
import com.mantz_it.rfanalyzer_ipc.SamplePacket

/**
 * <h1>RF Analyzer - Signed 16-Bit IQ Converter</h1>
 *
 * Module:      Signed16BitIQConverter.kt
 * Description: Converts interleaved I/Q samples where each component is a
 *              16-bit *signed* little-endian value (Airspy, HydraSDR, KiwiSDR after its byte swap).
 *              - Input format: ... I(lo), I(hi), Q(lo), Q(hi), I(lo), ...
 *              - Normalization: short [-32768, 32767] -> float [-1.0, 1.0)
 *              - To keep memory small and GC quiet:
 *                  * One 65,536-entry value LUT for short→float mapping
 *                  * Compact sin/cos LUTs of length bestLength for the NCO
 *                  * No per-(t, value) 2D tables (would be huge for 16-bit)
 *
 * Performance: mixPacketIntoSamplePacket()'s per-sample loop runs natively (NativeIQMixer16,
 * nativedsp/src/main/cpp/iq_word_mixer.c). The Kotlin loop below is the fallback when the native
 * library can't be loaded (a plain JVM unit test).
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class Signed16BitIQConverter(
    // Tests only: the Kotlin loop even when the native mixer is available, to compare the two.
    forceKotlinFallback: Boolean = false
) : IQConverter() {
    companion object {
        private const val LOGTAG = "Signed16BitIQConverter"
    }

    // 16-bit normalization table: index is the unsigned short (0..65535),
    // value is mapped float in ~[-1.0, 1.0)
    private lateinit var lookupTable16: FloatArray

    // Per-time-step oscillator lookup (compact)
    private var cosLut: FloatArray? = null
    private var sinLut: FloatArray? = null

    private val nativeMixer: NativeIQMixer16? = if (forceKotlinFallback) {
        null
    } else {
        try {
            NativeIQMixer16()
        } catch (e: Throwable) {
            Log.w(LOGTAG, "Native IQMixer16 unavailable, falling back to Kotlin implementation: ${e.message}")
            null
        }
    }

    init {
        // lookupTable16 is set: the IQConverter constructor ran generateLookupTable() before
        // this class' initializers.
        nativeMixer?.setValueTable(lookupTable16)
    }

    /** Whether the native mixer runs (not the Kotlin fallback). For tests. */
    val isUsingNative: Boolean get() = nativeMixer != null

    override fun generateLookupTable() {
        // Build once; ~256 KB, fine for Android and avoids per-sample math
        val lut = FloatArray(65536)
        // Map u16 -> s16 -> float; dividing by 32768f matches typical SDR scaling
        //   -32768 / 32768f = -1.0f
        //    32767 / 32768f ≈  0.99997f
        for (u in 0 until 65536) {
            val s = (u.toShort()).toInt() // sign-extend to 32-bit int
            lut[u] = s / 32768.0f
        }
        lookupTable16 = lut
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
        if (interleavedBuffer.size < packet.size / 2) return false
        if (nativeMixer != null) {
            NativeByteConverter.s16ToFloats(packet, validByteCount.coerceIn(0, packet.size) / 4 * 2, interleavedBuffer)
            return true
        }
        val lut = lookupTable16
        var i = 0
        val pktEnd = validByteCount.coerceIn(0, packet.size)
        var outIdx = 0

        // Each complex sample: 4 bytes (Ilo, Ihi, Qlo, Qhi)
        while (i + 3 < pktEnd) {
            // little-endian 16-bit -> unsigned index 0..65535
            val iU = (packet[i].toInt() and 0xFF) or (packet[i + 1].toInt() shl 8)
            val qU = (packet[i + 2].toInt() and 0xFF) or (packet[i + 3].toInt() shl 8)

            interleavedBuffer[outIdx] = lut[iU and 0xFFFF]
            interleavedBuffer[outIdx+1] = lut[qU and 0xFFFF]

            i += 4
            outIdx += 2
        }
        return true
    }

    override fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long, validByteCount: Int): Int {
        val mixFrequency = (frequency - channelFrequency).toInt()
        generateMixerLookupTable(mixFrequency) // only regenerates if needed
        val pktEnd = validByteCount.coerceIn(0, packet.size)

        val count: Int
        if (nativeMixer != null) {
            val outOffset = samplePacket.size()
            val (_, produced) = nativeMixer.mix(
                packet, 0, pktEnd,
                samplePacket.re(), samplePacket.im(), outOffset, samplePacket.capacity()
            )
            count = produced
            if (count == 0) return 0
            samplePacket.setSize(outOffset + count)
        } else {
            count = mixPacketIntoSamplePacketKotlinFallback(packet, samplePacket, pktEnd)
            if (count == 0) return 0
        }
        samplePacket.sampleRate = sampleRate
        samplePacket.frequency = channelFrequency // downmixed center
        stampFromClock(samplePacket)
        return count
    }

    /** The Kotlin engine, used when native is unavailable (sample rate, frequency and timestamp
     * are set by mixPacketIntoSamplePacket()). [pktEnd] is the clamped valid byte count. */
    private fun mixPacketIntoSamplePacketKotlinFallback(packet: ByteArray, samplePacket: SamplePacket, pktEnd: Int): Int {
        val capacity = samplePacket.capacity()
        val startIndex = samplePacket.size()
        if (startIndex >= capacity) return 0

        val cosArr = cosLut!!    // guaranteed non-null after generateMixerLookupTable
        val sinArr = sinLut!!
        val lut = lookupTable16
        val pkt = packet

        var count = 0
        var i = 0
        var outIdx = startIndex
        var cIdx = cosineIndex
        val cLen = cosArr.size

        if (cLen == 0) // Lookup Table is empty/invalid
            return 0
        if (cIdx >= cLen) cIdx = 0

        // Mix: (I + jQ)(cos + j sin), the NCO at frequency - channelFrequency
        // re = I*cos - Q*sin
        // im = Q*cos + I*sin
        while (i + 3 < pktEnd && outIdx < capacity) {
            val iU = (pkt[i].toInt() and 0xFF) or (pkt[i + 1].toInt() shl 8)
            val qU = (pkt[i + 2].toInt() and 0xFF) or (pkt[i + 3].toInt() shl 8)

            val iF = lut[iU and 0xFFFF]
            val qF = lut[qU and 0xFFFF]

            val c = cosArr[cIdx]
            val s = sinArr[cIdx]

            samplePacket.re()[outIdx] = iF * c - qF * s
            samplePacket.im()[outIdx] = qF * c + iF * s

            cIdx++
            if (cIdx == cLen) cIdx = 0

            i += 4
            outIdx++
            count++
        }

        if (count == 0) return 0

        cosineIndex = cIdx
        samplePacket.setSize(startIndex + count)
        return count
    }

    /** Frees native resources. Safe to call even if the Kotlin fallback engine is in use. */
    override fun close() {
        nativeMixer?.close()
    }
}
