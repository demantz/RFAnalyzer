package com.mantz_it.rfanalyzer.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random

/**
 * <h1>RF Analyzer - Native Float IQ Mixer Golden Tests</h1>
 *
 * Module:      NativeIQMixerFloatGoldenTest.kt
 * Description: On-device bit-exact gate for the native IQMixerFloat used by Float32IQConverter's
 *              mixPacketIntoSamplePacket() (AirspyHF CF32 format) - same methodology as
 *              NativeIQMixerGoldenTest.kt (the HackRF/signed-8-bit sibling), adapted for the
 *              simplest kernel shape (no value LUT at all - the input is already IEEE-754 float,
 *              just a little-endian read plus the NCO multiply): constructs a native-backed and a
 *              Kotlin-fallback-forced Float32IQConverter ON THE SAME DEVICE, feeds them identical
 *              raw CF32 bytes (generated from random but finite/normal float values - NaN/Infinity
 *              are covered separately by the edge-case suite, not this bit-exact correctness sweep)
 *              across several channel-frequency offsets, and compares every output sample's raw
 *              bits.
 *
 * Run: ./gradlew connectedPlayDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mantz_it.rfanalyzer.source.NativeIQMixerFloatGoldenTest
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class NativeIQMixerFloatGoldenTest {

    companion object {
        private const val SAMPLE_RATE = 768_000 // typical AirspyHF+ rate
        private const val TUNE_FREQUENCY = 14_074_000L
        // Several channel offsets, forcing generateMixerLookupTable() to regenerate its NCO table
        // more than once per converter instance - exercises the native table-reload path
        // (nativeSetNcoTable), not just a single fixed-table steady-state case.
        private val CHANNEL_OFFSETS_HZ = listOf(-100_000L, -25_000L, 0L, 37_500L, 94_371L)

        // Byte counts must be multiples of 8 (4-byte I float + 4-byte Q float) to be fully
        // consumed - mix in a few non-multiples-of-8 to exercise the truncated-tail path too.
        private val PACKET_BYTE_SIZE_CYCLE = listOf(8192, 8184, 16384, 262144, 8, 4096, 2048, 12, 3999)
        private val PACKET_BYTE_SIZES = List(60) { PACKET_BYTE_SIZE_CYCLE[it % PACKET_BYTE_SIZE_CYCLE.size] }
    }

    /** Random finite (non-NaN/non-Infinite) CF32 bytes, little-endian. */
    private fun randomFiniteCf32Bytes(byteCount: Int, rng: Random): ByteArray {
        val buf = ByteBuffer.allocate(byteCount).order(ByteOrder.LITTLE_ENDIAN)
        var written = 0
        while (written + 4 <= byteCount) {
            // Typical SDR sample magnitude range, avoids accidentally hitting NaN/Infinity bit
            // patterns while still exercising a wide, realistic value range.
            buf.putFloat((rng.nextFloat() - 0.5f) * 2f)
            written += 4
        }
        while (written < byteCount) {
            buf.put(0)
            written++
        }
        return buf.array()
    }

    @Test
    fun nativeMatchesKotlin() {
        val nativeConverter = Float32IQConverter()
        val kotlinConverter = Float32IQConverter(forceKotlinFallback = true)

        assertTrue("expected native converter to actually use native backend",
            nativeConverter.isUsingNative)
        assertTrue("expected forced-fallback converter to use Kotlin backend",
            !kotlinConverter.isUsingNative)

        nativeConverter.sampleRate = SAMPLE_RATE
        nativeConverter.frequency = TUNE_FREQUENCY
        kotlinConverter.sampleRate = SAMPLE_RATE
        kotlinConverter.frequency = TUNE_FREQUENCY

        val rng = Random(0x9004L)
        val outCapacity = PACKET_BYTE_SIZES.sum() / 8 + 64
        val nativeOut = SamplePacket(outCapacity)
        val kotlinOut = SamplePacket(outCapacity)

        for ((idx, byteSize) in PACKET_BYTE_SIZES.withIndex()) {
            val channelFrequency = TUNE_FREQUENCY + CHANNEL_OFFSETS_HZ[idx % CHANNEL_OFFSETS_HZ.size]
            val packet = randomFiniteCf32Bytes(byteSize, rng)

            val producedNative = nativeConverter.mixPacketIntoSamplePacket(packet, nativeOut, channelFrequency)
            val producedKotlin = kotlinConverter.mixPacketIntoSamplePacket(packet, kotlinOut, channelFrequency)
            assertEquals("call $idx: produced differs between native and Kotlin", producedKotlin, producedNative)
        }

        assertEquals("total produced differs between native and Kotlin", kotlinOut.size(), nativeOut.size())

        for (i in 0 until nativeOut.size()) {
            assertEquals("re differs at sample $i",
                java.lang.Float.floatToRawIntBits(kotlinOut.re()[i]), java.lang.Float.floatToRawIntBits(nativeOut.re()[i]))
            assertEquals("im differs at sample $i",
                java.lang.Float.floatToRawIntBits(kotlinOut.im()[i]), java.lang.Float.floatToRawIntBits(nativeOut.im()[i]))
        }

        nativeConverter.close()
        kotlinConverter.close()
    }
}
