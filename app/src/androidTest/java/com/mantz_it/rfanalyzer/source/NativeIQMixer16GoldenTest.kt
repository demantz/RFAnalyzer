package com.mantz_it.rfanalyzer.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * <h1>RF Analyzer - Native 16-Bit IQ Mixer Golden Tests</h1>
 *
 * Module:      NativeIQMixer16GoldenTest.kt
 * Description: On-device bit-exact gate for the native IQMixer16 used by Signed16BitIQConverter's
 *              mixPacketIntoSamplePacket() (Airspy/HydraSDR/KiwiSDR format) - same methodology as
 *              NativeIQMixerGoldenTest.kt (the HackRF/signed-8-bit sibling), adapted for the
 *              different kernel shape (compact NCO + 65536-entry value LUT, direct per-sample
 *              multiply instead of a precombined table): constructs a native-backed and a
 *              Kotlin-fallback-forced Signed16BitIQConverter ON THE SAME DEVICE, feeds them
 *              identical raw IQ bytes across several channel-frequency offsets (to exercise
 *              multiple NCO-table regenerations, not just one fixed table), and compares every
 *              output sample's raw bits.
 *
 * Run: ./gradlew connectedPlayDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mantz_it.rfanalyzer.source.NativeIQMixer16GoldenTest
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class NativeIQMixer16GoldenTest {

    companion object {
        private const val SAMPLE_RATE = 10_000_000 // typical Airspy rate
        private const val TUNE_FREQUENCY = 145_871_991L
        // Several channel offsets, forcing generateMixerLookupTable() to regenerate its NCO table
        // more than once per converter instance - exercises the native table-reload path
        // (nativeSetNcoTable), not just a single fixed-table steady-state case.
        private val CHANNEL_OFFSETS_HZ = listOf(-2_000_000L, -500_000L, 0L, 750_000L, 1_971_991L)

        // Byte counts must be multiples of 4 (I-lo, I-hi, Q-lo, Q-hi) to be fully consumed - mix
        // in a few non-multiples-of-4 to exercise the truncated-tail path too.
        private val PACKET_BYTE_SIZE_CYCLE = listOf(4096, 3996, 8192, 262144, 4, 2048, 1024, 6, 1999)
        private val PACKET_BYTE_SIZES = List(60) { PACKET_BYTE_SIZE_CYCLE[it % PACKET_BYTE_SIZE_CYCLE.size] }
    }

    @Test
    fun nativeMatchesKotlin() {
        val nativeConverter = Signed16BitIQConverter()
        val kotlinConverter = Signed16BitIQConverter(forceKotlinFallback = true)

        assertTrue("expected native converter to actually use native backend",
            nativeConverter.isUsingNative)
        assertTrue("expected forced-fallback converter to use Kotlin backend",
            !kotlinConverter.isUsingNative)

        nativeConverter.sampleRate = SAMPLE_RATE
        nativeConverter.frequency = TUNE_FREQUENCY
        kotlinConverter.sampleRate = SAMPLE_RATE
        kotlinConverter.frequency = TUNE_FREQUENCY

        val rng = Random(0x9003L)
        val outCapacity = PACKET_BYTE_SIZES.sum() / 4 + 64
        val nativeOut = SamplePacket(outCapacity)
        val kotlinOut = SamplePacket(outCapacity)

        for ((idx, byteSize) in PACKET_BYTE_SIZES.withIndex()) {
            val channelFrequency = TUNE_FREQUENCY + CHANNEL_OFFSETS_HZ[idx % CHANNEL_OFFSETS_HZ.size]
            val packet = ByteArray(byteSize)
            rng.nextBytes(packet)

            val consumedNative = nativeConverter.mixPacketIntoSamplePacket(packet, nativeOut, channelFrequency)
            val consumedKotlin = kotlinConverter.mixPacketIntoSamplePacket(packet, kotlinOut, channelFrequency)
            assertEquals("call $idx: produced differs between native and Kotlin", consumedKotlin, consumedNative)
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
