package com.mantz_it.rfanalyzer.source

/**
 * RF Analyzer - signed 16-bit IQ Converter
 *
 * Module:      Signed16BitIQConverter.kt
 * Description: Converts interleaved I/Q samples where each component is a
 *              16-bit *signed* little-endian value. Compatible with IQConverter.
 *              - Input format: ... I(lo), I(hi), Q(lo), Q(hi), I(lo), ...
 *              - Normalization: short [-32768, 32767] -> float [-1.0, 1.0)
 *              - To keep memory small and GC quiet:
 *                  * One 65,536-entry value LUT for short→float mapping
 *                  * Compact sin/cos LUTs of length bestLength for the NCO
 *                  * No per-(t, value) 2D tables (would be huge for 16-bit)
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

class Signed16BitIQConverter : IQConverter() {

    // 16-bit normalization table: index is the unsigned short (0..65535),
    // value is mapped float in ~[-1.0, 1.0)
    private lateinit var lookupTable16: FloatArray

    // Per-time-step oscillator lookup (compact)
    private var cosLut: FloatArray? = null
    private var sinLut: FloatArray? = null

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
        }
    }

    override fun fillPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket): Int {
        val capacity = samplePacket.capacity()
        val startIndex = samplePacket.size()
        if (startIndex >= capacity) return 0

        val re = samplePacket.re()
        val im = samplePacket.im()
        val lut = lookupTable16
        val pkt = packet

        var count = 0
        var i = 0
        val pktEnd = pkt.size
        var outIdx = startIndex

        // Each complex sample: 4 bytes (Ilo, Ihi, Qlo, Qhi)
        while (i + 3 < pktEnd && outIdx < capacity) {
            // little-endian 16-bit -> unsigned index 0..65535
            val iU = (pkt[i].toInt() and 0xFF) or (pkt[i + 1].toInt() shl 8)
            val qU = (pkt[i + 2].toInt() and 0xFF) or (pkt[i + 3].toInt() shl 8)

            re[outIdx] = lut[iU and 0xFFFF]
            im[outIdx] = lut[qU and 0xFFFF]

            i += 4
            outIdx++
            count++
        }

        if (count == 0) return 0

        samplePacket.setSize(startIndex + count)
        samplePacket.sampleRate = sampleRate
        samplePacket.frequency = frequency
        return count
    }

    override fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long): Int {
        val mixFrequency = (frequency - channelFrequency).toInt()
        generateMixerLookupTable(mixFrequency) // only regenerates if needed

        val capacity = samplePacket.capacity()
        val startIndex = samplePacket.size()
        if (startIndex >= capacity) return 0

        val cosArr = cosLut!!    // guaranteed non-null after generateMixerLookupTable
        val sinArr = sinLut!!
        val lut = lookupTable16
        val pkt = packet

        var count = 0
        var i = 0
        val pktEnd = pkt.size
        var outIdx = startIndex
        var cIdx = cosineIndex
        val cLen = cosArr.size

        if (cLen == 0) // Lookup Table is empty/invalid
            return 0
        if (cIdx >= cLen) cIdx = 0

        // Mix: (I + jQ) * (cos + j sin)^*  = (I + jQ)(cos - j sin)
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
        samplePacket.sampleRate = sampleRate
        samplePacket.frequency = channelFrequency // downmixed center
        return count
    }
}
