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
 * <h1>RF Analyzer - Native Float IQ Mixer Edge Case Tests</h1>
 *
 * Module:      NativeIQMixerFloatEdgeCasesTest.kt
 * Description: Edge-case / fuzz coverage for the native IQMixerFloat (Float32IQConverter, AirspyHF
 *              CF32 format) - a companion to NativeIQMixerFloatGoldenTest.kt's "normal input"
 *              bit-exact sweep. See NativeIQMixerEdgeCasesTest.kt (the signed-8-bit sibling) for
 *              the general rationale. This converter is the one place NaN/Infinity inputs are
 *              actually reachable (the input is already raw IEEE-754 float, unlike the integer
 *              formats) - those get category-level (isNaN()/isInfinite()/sign) comparison rather
 *              than bit-exact, since NaN payload propagation through arithmetic is
 *              implementation-defined; everything else here stays bit-exact.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class NativeIQMixerFloatEdgeCasesTest {

    companion object {
        private const val SAMPLE_RATE = 768_000
        private const val TUNE_FREQUENCY = 14_074_000L
        private const val CHANNEL_FREQUENCY = 14_070_000L
        // Real AirspyHF+ transfer buffers are far smaller than this; deliberately large to
        // stress the native malloc path beyond any realistic single packet.
        private const val LARGE_PACKET_BYTES = 4 * 1024 * 1024
    }

    private fun newPair(): Pair<Float32IQConverter, Float32IQConverter> {
        val native = Float32IQConverter()
        val fallback = Float32IQConverter(forceKotlinFallback = true)
        assertTrue("expected native converter to actually use native backend", native.isUsingNative)
        assertTrue("expected forced-fallback converter to use Kotlin backend", !fallback.isUsingNative)
        for (c in listOf(native, fallback)) {
            c.sampleRate = SAMPLE_RATE
            c.frequency = TUNE_FREQUENCY
        }
        return Pair(native, fallback)
    }

    private fun cf32Bytes(vararg values: Float): ByteArray {
        val buf = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (v in values) buf.putFloat(v)
        return buf.array()
    }

    private fun assertBitExact(native: SamplePacket, fallback: SamplePacket) {
        assertEquals("size differs", fallback.size(), native.size())
        for (i in 0 until native.size()) {
            assertEquals("re differs at sample $i",
                java.lang.Float.floatToRawIntBits(fallback.re()[i]), java.lang.Float.floatToRawIntBits(native.re()[i]))
            assertEquals("im differs at sample $i",
                java.lang.Float.floatToRawIntBits(fallback.im()[i]), java.lang.Float.floatToRawIntBits(native.im()[i]))
        }
    }

    /** Category-level comparison for non-finite values: same NaN-ness, same infinite-ness and
     * sign - not bit-exact, since NaN payload propagation through arithmetic is
     * implementation-defined even for otherwise-identical IEEE-754 operation sequences. */
    private fun assertSameCategory(nativeVal: Float, fallbackVal: Float, label: String) {
        assertEquals("$label: NaN-ness differs (native=$nativeVal, fallback=$fallbackVal)",
            fallbackVal.isNaN(), nativeVal.isNaN())
        if (!fallbackVal.isNaN()) {
            assertEquals("$label: infinite-ness differs (native=$nativeVal, fallback=$fallbackVal)",
                fallbackVal.isInfinite(), nativeVal.isInfinite())
            if (fallbackVal.isInfinite()) {
                assertEquals("$label: sign differs (native=$nativeVal, fallback=$fallbackVal)",
                    fallbackVal > 0, nativeVal > 0)
            }
        }
    }

    @Test
    fun zeroLengthPacket() {
        val (native, fallback) = newPair()
        val nativeOut = SamplePacket(16)
        val fallbackOut = SamplePacket(16)

        val producedNative = native.mixPacketIntoSamplePacket(ByteArray(0), nativeOut, CHANNEL_FREQUENCY)
        val producedFallback = fallback.mixPacketIntoSamplePacket(ByteArray(0), fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(0, producedNative)
        assertEquals(0, producedFallback)
        assertBitExact(nativeOut, fallbackOut)
        native.close(); fallback.close()
    }

    @Test
    fun truncatedTailPacket() {
        // Below one full 8-byte (4-byte I + 4-byte Q) sample - should produce nothing, not crash.
        for (byteCount in intArrayOf(1, 4, 7)) {
            val (native, fallback) = newPair()
            val nativeOut = SamplePacket(16)
            val fallbackOut = SamplePacket(16)
            val packet = ByteArray(byteCount) { (it + 1).toByte() }

            val producedNative = native.mixPacketIntoSamplePacket(packet, nativeOut, CHANNEL_FREQUENCY)
            val producedFallback = fallback.mixPacketIntoSamplePacket(packet, fallbackOut, CHANNEL_FREQUENCY)

            assertEquals("byteCount=$byteCount", producedFallback, producedNative)
            assertBitExact(nativeOut, fallbackOut)
            native.close(); fallback.close()
        }
    }

    @Test
    fun largePacket() {
        val (native, fallback) = newPair()
        val rng = Random(0xEDDE4L)
        val buf = ByteBuffer.allocate(LARGE_PACKET_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 4) buf.putFloat((rng.nextFloat() - 0.5f) * 2f)
        val packet = buf.array()
        val capacity = LARGE_PACKET_BYTES / 8 + 16
        val nativeOut = SamplePacket(capacity)
        val fallbackOut = SamplePacket(capacity)

        val producedNative = native.mixPacketIntoSamplePacket(packet, nativeOut, CHANNEL_FREQUENCY)
        val producedFallback = fallback.mixPacketIntoSamplePacket(packet, fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(producedFallback, producedNative)
        assertTrue("expected a large number of samples to be produced", producedNative > 200_000)
        assertBitExact(nativeOut, fallbackOut)
        native.close(); fallback.close()
    }

    @Test
    fun finiteBoundaryValues() {
        val (native, fallback) = newPair()
        val nativeOut = SamplePacket(16)
        val fallbackOut = SamplePacket(16)
        val packet = cf32Bytes(
            0f, 0f,
            -0f, -0f,
            Float.MAX_VALUE, Float.MAX_VALUE,
            -Float.MAX_VALUE, -Float.MAX_VALUE,
            Float.MIN_VALUE, Float.MIN_VALUE, // smallest positive subnormal
            1.1754944E-38f, -1.1754944E-38f  // ~Float.MIN_NORMAL, one of each sign
        )

        val producedNative = native.mixPacketIntoSamplePacket(packet, nativeOut, CHANNEL_FREQUENCY)
        val producedFallback = fallback.mixPacketIntoSamplePacket(packet, fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(6, producedNative)
        assertEquals(producedFallback, producedNative)
        assertBitExact(nativeOut, fallbackOut)
        native.close(); fallback.close()
    }

    @Test
    fun nanAndInfinityValues() {
        val (native, fallback) = newPair()
        val nativeOut = SamplePacket(16)
        val fallbackOut = SamplePacket(16)
        val packet = cf32Bytes(
            Float.NaN, 0f,
            0f, Float.NaN,
            Float.POSITIVE_INFINITY, 1f,
            Float.NEGATIVE_INFINITY, 1f,
            1f, Float.POSITIVE_INFINITY,
            Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY
        )

        val producedNative = native.mixPacketIntoSamplePacket(packet, nativeOut, CHANNEL_FREQUENCY)
        val producedFallback = fallback.mixPacketIntoSamplePacket(packet, fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(6, producedNative)
        assertEquals(producedFallback, producedNative)
        for (i in 0 until nativeOut.size()) {
            assertSameCategory(nativeOut.re()[i], fallbackOut.re()[i], "re[$i]")
            assertSameCategory(nativeOut.im()[i], fallbackOut.im()[i], "im[$i]")
        }
        native.close(); fallback.close()
    }

    @Test
    fun repeatedCreateCloseCycling() {
        repeat(1000) {
            val c = Float32IQConverter()
            assertTrue(c.isUsingNative)
            c.close()
        }
    }

    @Test
    fun mixAfterClose() {
        val native = Float32IQConverter()
        native.sampleRate = SAMPLE_RATE
        native.frequency = TUNE_FREQUENCY
        val out = SamplePacket(16)
        val rng = Random(0x5105AL)
        val buf = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN)
        while (buf.remaining() >= 4) buf.putFloat((rng.nextFloat() - 0.5f) * 2f)
        val packet = buf.array()

        val producedBeforeClose = native.mixPacketIntoSamplePacket(packet, out, CHANNEL_FREQUENCY)
        assertTrue("expected the pre-close call to actually produce samples", producedBeforeClose > 0)

        native.close()

        val postCloseOut = SamplePacket(16)
        val producedAfterClose = native.mixPacketIntoSamplePacket(packet, postCloseOut, CHANNEL_FREQUENCY)
        assertEquals(0, producedAfterClose)
        assertEquals(0, postCloseOut.size())
    }
}
