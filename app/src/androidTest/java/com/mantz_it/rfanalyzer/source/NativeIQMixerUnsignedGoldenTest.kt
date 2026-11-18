package com.mantz_it.rfanalyzer.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * <h1>RF Analyzer - Native Unsigned 8-Bit IQ Mixer Golden Tests</h1>
 *
 * Module:      NativeIQMixerUnsignedGoldenTest.kt
 * Description: On-device bit-exact gate for the native IQMixerUnsigned used by
 *              Unsigned8BitIQConverter's mixPacketIntoSamplePacket() (RTL-SDR format) - same
 *              methodology as NativeIQMixerGoldenTest.kt (the HackRF/signed-8-bit sibling):
 *              constructs a native-backed and a Kotlin-fallback-forced Unsigned8BitIQConverter ON
 *              THE SAME DEVICE, feeds them identical raw IQ bytes across several channel-frequency
 *              offsets (to exercise multiple mixer-table regenerations, not just one fixed table),
 *              and compares every output sample's raw bits.
 *
 * Run: ./gradlew connectedPlayDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mantz_it.rfanalyzer.source.NativeIQMixerUnsignedGoldenTest
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class NativeIQMixerUnsignedGoldenTest {

    companion object {
        private const val SAMPLE_RATE = 2_048_000 // typical RTL-SDR rate
        private const val TUNE_FREQUENCY = 100_871_991L
        // Several channel offsets, forcing generateMixerLookupTable() to regenerate its table
        // more than once per converter instance - exercises the native table-reload path
        // (nativeSetMixerTable), not just a single fixed-table steady-state case.
        private val CHANNEL_OFFSETS_HZ = listOf(-500_000L, -125_000L, 0L, 187_500L, 471_991L)

        private val PACKET_BYTE_SIZE_CYCLE = listOf(2048, 1998, 4096, 262144, 2, 1024, 512)
        private val PACKET_BYTE_SIZES = List(60) { PACKET_BYTE_SIZE_CYCLE[it % PACKET_BYTE_SIZE_CYCLE.size] }
    }

    @Test
    fun nativeMatchesJava() {
        val nativeConverter = Unsigned8BitIQConverter()
        val javaConverter = Unsigned8BitIQConverter(true)

        assertTrue("expected native converter to actually use native backend",
            nativeConverter.isUsingNative)
        assertTrue("expected forced-fallback converter to use Java backend",
            !javaConverter.isUsingNative)

        nativeConverter.sampleRate = SAMPLE_RATE
        nativeConverter.frequency = TUNE_FREQUENCY
        javaConverter.sampleRate = SAMPLE_RATE
        javaConverter.frequency = TUNE_FREQUENCY

        val rng = Random(0x9002L)
        val outCapacity = PACKET_BYTE_SIZES.sum() / 2 + 64
        val nativeOut = SamplePacket(outCapacity)
        val javaOut = SamplePacket(outCapacity)

        for ((idx, byteSize) in PACKET_BYTE_SIZES.withIndex()) {
            val channelFrequency = TUNE_FREQUENCY + CHANNEL_OFFSETS_HZ[idx % CHANNEL_OFFSETS_HZ.size]
            val packet = ByteArray(byteSize)
            rng.nextBytes(packet)

            val consumedNative = nativeConverter.mixPacketIntoSamplePacket(packet, nativeOut, channelFrequency)
            val consumedJava = javaConverter.mixPacketIntoSamplePacket(packet, javaOut, channelFrequency)
            assertEquals("call $idx: consumed differs between native and Java", consumedJava, consumedNative)
        }

        assertEquals("total produced differs between native and Java", javaOut.size(), nativeOut.size())

        for (i in 0 until nativeOut.size()) {
            assertEquals("re differs at sample $i",
                java.lang.Float.floatToRawIntBits(javaOut.re()[i]), java.lang.Float.floatToRawIntBits(nativeOut.re()[i]))
            assertEquals("im differs at sample $i",
                java.lang.Float.floatToRawIntBits(javaOut.im()[i]), java.lang.Float.floatToRawIntBits(nativeOut.im()[i]))
        }

        nativeConverter.close()
        javaConverter.close()
    }
}
