package com.mantz_it.rfanalyzer.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * <h1>RF Analyzer - Native Signed 8-Bit IQ Mixer Golden Tests</h1>
 *
 * Module:      NativeIQMixerGoldenTest.kt
 * Description: On-device bit-exact gate for the native IQMixer used by Signed8BitIQConverter's
 *              mixPacketIntoSamplePacket() (HackRF format; a CPU hotspot at high sample rates).
 *              Same methodology as NativeResamplerGoldenTest.kt: constructs a native-backed and a
 *              Kotlin-fallback-forced Signed8BitIQConverter ON THE SAME DEVICE, feeds them
 *              identical raw IQ bytes across several channel-frequency offsets (to exercise
 *              multiple mixer-table regenerations, not just one fixed table), and compares every
 *              output sample's raw bits.
 *
 * Run: ./gradlew connectedPlayDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mantz_it.rfanalyzer.source.NativeIQMixerGoldenTest
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class NativeIQMixerGoldenTest {

    companion object {
        private const val SAMPLE_RATE = 16_000_000
        private const val TUNE_FREQUENCY = 91_871_991L
        // Several channel offsets, forcing generateMixerLookupTable() to regenerate its table
        // more than once per converter instance - exercises the native table-reload path
        // (nativeSetMixerTable), not just a single fixed-table steady-state case.
        private val CHANNEL_OFFSETS_HZ = listOf(-2_000_000L, -500_000L, 0L, 750_000L, 1_971_991L)

        private val PACKET_BYTE_SIZE_CYCLE = listOf(2048, 1998, 4096, 262144, 2, 1024, 512)
        private val PACKET_BYTE_SIZES = List(60) { PACKET_BYTE_SIZE_CYCLE[it % PACKET_BYTE_SIZE_CYCLE.size] }
    }

    @Test
    fun nativeMatchesJava() {
        val nativeConverter = Signed8BitIQConverter()
        val javaConverter = Signed8BitIQConverter(true)

        assertTrue("expected native converter to actually use native backend",
            nativeConverter.isUsingNative)
        assertTrue("expected forced-fallback converter to use Java backend",
            !javaConverter.isUsingNative)

        nativeConverter.sampleRate = SAMPLE_RATE
        nativeConverter.frequency = TUNE_FREQUENCY
        javaConverter.sampleRate = SAMPLE_RATE
        javaConverter.frequency = TUNE_FREQUENCY

        val rng = Random(0x9001L)
        val maxPacketBytes = PACKET_BYTE_SIZES.max()
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
