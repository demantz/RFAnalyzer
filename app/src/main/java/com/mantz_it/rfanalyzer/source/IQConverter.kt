package com.mantz_it.rfanalyzer.source

import com.mantz_it.rfanalyzer_ipc.SamplePacket
import kotlin.math.abs

/**
 * <h1>RF Analyzer - IQ Converter</h1>
 *
 * Module:      IQConverter.kt
 * Description: This class implements methods to convert the raw input data of IQ sources (bytes)
 *              to SamplePackets. It has also methods to do converting and down-mixing at the same
 *              time.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
abstract class IQConverter {
    /** Baseband frequency of the converted samples (is put into the SamplePacket). */
    var frequency: Long = 0
    /** Sample rate of the converted samples (is put into the SamplePacket). */
    var sampleRate: Int = 0
        set(value) {
            if (field != value) {
                field = value
                cosineFrequency = -1 // invalid: generateMixerLookupTable() recalculates the table on the next packet
            }
        }
    protected var lookupTable: FloatArray? = null                  // IQ bytes -> floats
    protected var cosineRealLookupTable: Array<FloatArray>? = null  // IQ bytes -> frequency shifted floats
    protected var cosineImagLookupTable: Array<FloatArray>? = null  // IQ bytes -> frequency shifted floats
    protected var cosineFrequency = 0                               // frequency of the cosine that is mixed to the signal
    protected var cosineIndex = 0                                   // current index within the cosine

    init {
        generateLookupTable()
    }

    /**
     * Stamps samplePacket with the time of its first sample, taking its newest sample as received
     * just now (on the corrected wall clock). A source that knows when its samples were received
     * restamps the packet afterwards with stampFromSource().
     */
    protected fun stampFromClock(samplePacket: SamplePacket) {
        samplePacket.timestamp = ClockCorrection.now() - samplesToMillis(samplePacket.size().toLong(), sampleRate)
    }

    protected fun calcOptimalCosineLength(): Int {
        if (sampleRate <= 0 || cosineFrequency == 0) {
            return 1 // sampleRate or cosineFrequency == 0 is invalid; return 1 to not break anything
        }
        // look for the best fitting array size to hold one or more full cosine cycles:
        val cycleLength = sampleRate / abs(cosineFrequency.toDouble())
        var bestLength = cycleLength.toInt()
        var bestLengthError = abs(bestLength - cycleLength)
        var i = 1
        while (i * cycleLength < MAX_COSINE_LENGTH) {
            if (abs(i * cycleLength - (i * cycleLength).toInt()) < bestLengthError) {
                bestLength = (i * cycleLength).toInt()
                bestLengthError = abs(bestLength - i * cycleLength)
            }
            i++
        }
        return bestLength
    }

    fun fillPacketIntoInterleavedBuffer(packet: ByteArray, interleavedBuffer: FloatArray): Boolean =
        fillPacketIntoInterleavedBuffer(packet, interleavedBuffer, packet.size)

    /** Converts only the first validByteCount bytes of packet: a pooled USB buffer can hold fewer
     *  new samples than its size (see SampleBufferPool), the rest is a previous fill's content. */
    abstract fun fillPacketIntoInterleavedBuffer(packet: ByteArray, interleavedBuffer: FloatArray, validByteCount: Int): Boolean

    fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long): Int =
        mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency, packet.size)

    /** Mixes only the first validByteCount bytes of packet (see fillPacketIntoInterleavedBuffer). */
    abstract fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long, validByteCount: Int): Int

    protected abstract fun generateLookupTable()

    protected abstract fun generateMixerLookupTable(mixFrequency: Int)

    /** Frees the native mixer of subclasses that have one; safe to call in any case. */
    open fun close() {}

    companion object {
        /** Max length of the cosine lookup table. */
        const val MAX_COSINE_LENGTH = 500

        /**
         * Stamps samplePacket with the time of its first sample, given the time of the first of the
         * appended samples a converter just added to its end.
         */
        @JvmStatic
        fun stampFromSource(samplePacket: SamplePacket, appended: Int, appendedTimestamp: Long) {
            samplePacket.timestamp = appendedTimestamp - samplesToMillis((samplePacket.size() - appended).toLong(), samplePacket.sampleRate)
        }

        private fun samplesToMillis(samples: Long, sampleRate: Int): Long = if (sampleRate > 0) samples * 1000 / sampleRate else 0
    }
}
