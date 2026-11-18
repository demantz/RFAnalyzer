package com.mantz_it.rfanalyzer.dsp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * <h1>RF Analyzer - Native Resampler Golden Tests</h1>
 *
 * Module:      NativeResamplerGoldenTest.kt
 * Description: On-device bit-exact gate for the native RationalResampler.
 *
 * Constructs a native-backed and a Kotlin-fallback-forced (forceKotlinFallback = true)
 * RationalResampler ON THE SAME DEVICE, feeds them identical input, and compares every output
 * sample's raw bits (Float.floatToRawIntBits) - not an epsilon comparison, which would hide
 * exactly the FMA/fast-math bugs this test exists to catch.
 *
 * Run: ./gradlew connectedPlayDebugAndroidTest \
 *   -Pandroid.testInstrumentationRunnerArguments.class=com.mantz_it.rfanalyzer.dsp.NativeResamplerGoldenTest
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class NativeResamplerGoldenTest {

    companion object {
        private const val MAX_TAPS = 500

        private data class RatioCase(val label: String, val interpolation: Int, val decimation: Int, val seed: Long)

        private val CASES = listOf(
            RatioCase("19_192", 19, 192, 0x1001L),
            RatioCase("1_1", 1, 1, 0x1002L),
            RatioCase("5_1", 5, 1, 0x1003L),
            RatioCase("1_10", 1, 10, 0x1004L),
            RatioCase("4_8", 4, 8, 0x1005L),
        )

        private val PACKET_SIZE_CYCLE = listOf(1000, 999, 1024, 777, 1, 512, 333, 4096, 7, 256)
        private val PACKET_SIZES = List(80) { PACKET_SIZE_CYCLE[it % PACKET_SIZE_CYCLE.size] }
    }

    @Test
    fun nativeMatchesKotlin() {
        for (case in CASES) {
            verifyOne(case)
        }
    }

    private fun verifyOne(case: RatioCase) {
        val nativeResampler = RationalResampler(case.interpolation, case.decimation, maxTaps = MAX_TAPS)
        val kotlinResampler = RationalResampler(
            case.interpolation, case.decimation, maxTaps = MAX_TAPS, forceKotlinFallback = true
        )
        // Guard against a vacuous pass: if native silently failed to load and both instances
        // ended up on the Kotlin fallback, the comparison below would trivially match without
        // ever exercising the native code at all.
        assertTrue("${case.label}: expected native resampler to actually use native backend",
            nativeResampler.isUsingNative)
        assertTrue("${case.label}: expected forced-fallback resampler to use Kotlin backend",
            !kotlinResampler.isUsingNative)

        val rng = Random(case.seed)
        val maxPacketSize = PACKET_SIZES.max()
        val inPacket = SamplePacket(maxPacketSize)
        val outCapacity = (PACKET_SIZES.sum().toLong() * case.interpolation / case.decimation + 64).toInt()
        val nativeOut = SamplePacket(outCapacity)
        val kotlinOut = SamplePacket(outCapacity)

        var phase = 0.0
        for (size in PACKET_SIZES) {
            for (i in 0 until size) {
                val re = (kotlin.math.cos(phase) + rng.nextGaussian() * 0.05).toFloat()
                val im = (kotlin.math.sin(phase) + rng.nextGaussian() * 0.05).toFloat()
                inPacket.re()[i] = re
                inPacket.im()[i] = im
                phase += 0.037
            }
            inPacket.setSize(size)
            inPacket.sampleRate = 2_000_000
            inPacket.frequency = 97_000_000L

            val consumedNative = nativeResampler.resample(inPacket, nativeOut, 0, size)
            val consumedKotlin = kotlinResampler.resample(inPacket, kotlinOut, 0, size)
            assertEquals("${case.label}: consumed differs between native and Kotlin", consumedKotlin, consumedNative)
        }

        assertEquals("${case.label}: total produced differs between native and Kotlin",
            kotlinOut.size(), nativeOut.size())

        for (i in 0 until nativeOut.size()) {
            assertEquals("${case.label}: re differs at sample $i",
                java.lang.Float.floatToRawIntBits(kotlinOut.re()[i]), java.lang.Float.floatToRawIntBits(nativeOut.re()[i]))
            assertEquals("${case.label}: im differs at sample $i",
                java.lang.Float.floatToRawIntBits(kotlinOut.im()[i]), java.lang.Float.floatToRawIntBits(nativeOut.im()[i]))
        }

        nativeResampler.close()
        kotlinResampler.close()
    }
}
