package com.mantz_it.rfanalyzer.source

import com.mantz_it.nativedsp.NativeByteConverter
import com.mantz_it.rfanalyzer_ipc.SamplePacket

/**
 * <h1>RF Analyzer - 8-bit IQ Converter</h1>
 *
 * Module:      ByteIQConverter.kt
 * Description: Interleaved 8-bit IQ samples (in-phase component first, followed by the quadrature
 *              component), signed (HackRF) or unsigned (RTL-SDR): one table lookup per byte, and
 *              per byte and oscillator step when mixing. The per-sample loops run natively when
 *              the library is available (Signed8BitIQConverter, Unsigned8BitIQConverter); the
 *              Kotlin loops here are the fallback (e.g. on a plain JVM in unit tests).
 *
 *  [--------- first sample ----------]   [-------- second sample --------]
 *         I                  Q                  I                Q ...
 *  receivedBytes[0]   receivedBytes[1]   receivedBytes[2]       ...
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
abstract class ByteIQConverter(private val unsigned: Boolean) : IQConverter() {

    /** The native mixer is there; the three functions below are only called then. */
    protected abstract val hasNativeMixer: Boolean
    protected abstract fun setNativeMixerTable(realTableFlat: FloatArray, imagTableFlat: FloatArray, tableLength: Int)
    /** Mixes [length] bytes of [packet] into re/im from [offset] on (at most up to [capacity]); returns the samples written. */
    protected abstract fun nativeMix(packet: ByteArray, length: Int, re: FloatArray, im: FloatArray, offset: Int, capacity: Int): Int

    /** The float value of table index [index] (0..255). */
    private fun valueOf(index: Int): Float = if (unsigned) (index - 127.4f) / 128.0f else (index - 128) / 128.0f

    /** The table index of byte [b]. */
    private fun indexOf(b: Byte): Int = if (unsigned) b.toInt() and 0xff else b + 128

    // IQConverter's constructor calls generateLookupTable() before [unsigned] is set: the table is
    // built in init instead
    override fun generateLookupTable() {}

    init {
        lookupTable = FloatArray(256) { valueOf(it) }
    }

    override fun generateMixerLookupTable(mixFrequency: Int) {
        var mixFreq = mixFrequency
        // If mix frequency is too low, just add the sample rate (sampled spectrum is periodic):
        if (mixFreq == 0 || sampleRate / abs(mixFreq) > MAX_COSINE_LENGTH)
            mixFreq += sampleRate

        // Only generate lookupTable if null or invalid:
        if (cosineRealLookupTable == null || mixFreq != cosineFrequency) {
            cosineFrequency = mixFreq
            val bestLength = calcOptimalCosineLength()
            val real = Array(bestLength) { FloatArray(256) }
            val imag = Array(bestLength) { FloatArray(256) }
            for (t in 0 until bestLength) {
                val cosineAtT = Math.cos(2 * Math.PI * cosineFrequency * t / sampleRate.toFloat()).toFloat()
                val sineAtT = Math.sin(2 * Math.PI * cosineFrequency * t / sampleRate.toFloat()).toFloat()
                for (i in 0 until 256) {
                    real[t][i] = valueOf(i) * cosineAtT
                    imag[t][i] = valueOf(i) * sineAtT
                }
            }
            cosineRealLookupTable = real
            cosineImagLookupTable = imag
            cosineIndex = 0

            if (hasNativeMixer) {
                val realFlat = FloatArray(bestLength * 256)
                val imagFlat = FloatArray(bestLength * 256)
                for (t in 0 until bestLength) {
                    System.arraycopy(real[t], 0, realFlat, t * 256, 256)
                    System.arraycopy(imag[t], 0, imagFlat, t * 256, 256)
                }
                setNativeMixerTable(realFlat, imagFlat, bestLength)
            }
        }
    }

    override fun fillPacketIntoInterleavedBuffer(packet: ByteArray, interleavedBuffer: FloatArray, validByteCount: Int): Boolean {
        val length = validByteCount.coerceAtMost(packet.size).coerceAtLeast(0)
        if (interleavedBuffer.size < length) return false
        if (hasNativeMixer) {
            NativeByteConverter.toFloats(packet, length, unsigned, interleavedBuffer)
            return true
        }
        val table = lookupTable!!
        for (i in 0 until length) interleavedBuffer[i] = table[indexOf(packet[i])]
        return true
    }

    override fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long, validByteCount: Int): Int {
        val length = validByteCount.coerceAtMost(packet.size).coerceAtLeast(0)
        val mixFrequency = (frequency - channelFrequency).toInt()

        generateMixerLookupTable(mixFrequency) // will only generate table if really necessary

        val count: Int
        if (hasNativeMixer) {
            val outOffset = samplePacket.size()
            count = nativeMix(packet, length, samplePacket.re(), samplePacket.im(), outOffset, samplePacket.capacity())
            samplePacket.setSize(outOffset + count)
        } else {
            count = mixFallback(packet, length, samplePacket)
        }
        samplePacket.sampleRate = sampleRate
        samplePacket.frequency = channelFrequency
        stampFromClock(samplePacket)
        return count
    }

    /** The Kotlin engine, used when native is unavailable (sample rate, frequency and timestamp
     *  are set by mixPacketIntoSamplePacket()). */
    private fun mixFallback(packet: ByteArray, length: Int, samplePacket: SamplePacket): Int {
        val capacity = samplePacket.capacity()
        val startIndex = samplePacket.size()
        if (startIndex >= capacity) return 0 // SamplePacket is already full
        val real = cosineRealLookupTable!!
        val imag = cosineImagLookupTable!!
        if (real.isEmpty()) return 0 // Lookup Table is empty/invalid
        if (cosineIndex >= real.size) cosineIndex = 0 // the table was altered and now is smaller
        val re = samplePacket.re()
        val im = samplePacket.im()
        var count = 0
        // i + 1 < length: a truncated packet's odd last byte has no partner (NativeIQMixerEdgeCasesTest)
        var i = 0
        while (i + 1 < length) {
            re[startIndex + count] = real[cosineIndex][indexOf(packet[i])] - imag[cosineIndex][indexOf(packet[i + 1])]
            im[startIndex + count] = real[cosineIndex][indexOf(packet[i + 1])] + imag[cosineIndex][indexOf(packet[i])]
            cosineIndex = (cosineIndex + 1) % real.size
            count++
            if (startIndex + count >= capacity) break
            i += 2
        }
        samplePacket.setSize(samplePacket.size() + count)
        return count
    }

    private fun abs(value: Int) = kotlin.math.abs(value)
}
