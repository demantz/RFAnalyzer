package com.mantz_it.rfanalyzer.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * <h1>RF Analyzer - Native Unsigned 8-Bit IQ Mixer Edge Case Tests</h1>
 *
 * Module:      NativeIQMixerUnsignedEdgeCasesTest.kt
 * Description: Edge-case / fuzz coverage for the native IQMixerUnsigned (Unsigned8BitIQConverter,
 *              RTL-SDR format) - a companion to NativeIQMixerUnsignedGoldenTest.kt's "normal input"
 *              bit-exact sweep. See NativeIQMixerEdgeCasesTest.kt (the signed-8-bit sibling) for
 *              the rationale: every case compares native output against the Kotlin fallback rather
 *              than just checking "doesn't crash".
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class NativeIQMixerUnsignedEdgeCasesTest {

    companion object {
        private const val SAMPLE_RATE = 2_048_000
        private const val TUNE_FREQUENCY = 100_871_991L
        private const val CHANNEL_FREQUENCY = 100_800_000L
        // Real RTL-SDR USB transfer buffers are 262144 bytes (RtlSdrDevice.BUFFER_SIZE); this is
        // deliberately much larger to stress the native malloc path beyond any realistic single
        // packet, simulating a pathological/adversarial caller.
        private const val LARGE_PACKET_BYTES = 4 * 1024 * 1024
    }

    private fun newPair(): Pair<Unsigned8BitIQConverter, Unsigned8BitIQConverter> {
        val native = Unsigned8BitIQConverter()
        val fallback = Unsigned8BitIQConverter(true)
        assertTrue("expected native converter to actually use native backend", native.isUsingNative)
        assertTrue("expected forced-fallback converter to use Java backend", !fallback.isUsingNative)
        for (c in listOf(native, fallback)) {
            c.sampleRate = SAMPLE_RATE
            c.frequency = TUNE_FREQUENCY
        }
        return Pair(native, fallback)
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

    @Test
    fun zeroLengthPacket() {
        val (native, fallback) = newPair()
        val nativeOut = SamplePacket(16)
        val fallbackOut = SamplePacket(16)

        val consumedNative = native.mixPacketIntoSamplePacket(ByteArray(0), nativeOut, CHANNEL_FREQUENCY)
        val consumedFallback = fallback.mixPacketIntoSamplePacket(ByteArray(0), fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(0, consumedNative)
        assertEquals(0, consumedFallback)
        assertBitExact(nativeOut, fallbackOut)
        native.close(); fallback.close()
    }

    @Test
    fun truncatedTailPacket() {
        // 1 byte: below one full 2-byte (I,Q) sample - should produce nothing, not crash.
        val (native, fallback) = newPair()
        val nativeOut = SamplePacket(16)
        val fallbackOut = SamplePacket(16)
        val packet = byteArrayOf(0x2A)

        val consumedNative = native.mixPacketIntoSamplePacket(packet, nativeOut, CHANNEL_FREQUENCY)
        val consumedFallback = fallback.mixPacketIntoSamplePacket(packet, fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(consumedFallback, consumedNative)
        assertBitExact(nativeOut, fallbackOut)
        native.close(); fallback.close()
    }

    @Test
    fun largePacket() {
        val (native, fallback) = newPair()
        val rng = Random(0xEDDE2L)
        val packet = ByteArray(LARGE_PACKET_BYTES)
        rng.nextBytes(packet)
        val capacity = LARGE_PACKET_BYTES / 2 + 16
        val nativeOut = SamplePacket(capacity)
        val fallbackOut = SamplePacket(capacity)

        val consumedNative = native.mixPacketIntoSamplePacket(packet, nativeOut, CHANNEL_FREQUENCY)
        val consumedFallback = fallback.mixPacketIntoSamplePacket(packet, fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(consumedFallback, consumedNative)
        assertTrue("expected a large number of samples to be produced", consumedNative > 1_000_000)
        assertBitExact(nativeOut, fallbackOut)
        native.close(); fallback.close()
    }

    @Test
    fun boundarySampleValues() {
        // Unsigned 8-bit extremes: 0x00 and 0xFF, all four (I,Q) combinations.
        val (native, fallback) = newPair()
        val nativeOut = SamplePacket(16)
        val fallbackOut = SamplePacket(16)
        val packet = byteArrayOf(
            0x00, 0x00,
            0x00.toByte(), 0xFF.toByte(),
            0xFF.toByte(), 0x00,
            0xFF.toByte(), 0xFF.toByte(),
            0x80.toByte(), 0x80.toByte()
        )

        val consumedNative = native.mixPacketIntoSamplePacket(packet, nativeOut, CHANNEL_FREQUENCY)
        val consumedFallback = fallback.mixPacketIntoSamplePacket(packet, fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(5, consumedNative)
        assertEquals(consumedFallback, consumedNative)
        assertBitExact(nativeOut, fallbackOut)
        native.close(); fallback.close()
    }

    @Test
    fun repeatedCreateCloseCycling() {
        repeat(1000) {
            val c = Unsigned8BitIQConverter()
            assertTrue(c.isUsingNative)
            c.close()
        }
    }

    @Test
    fun mixAfterClose() {
        val native = Unsigned8BitIQConverter()
        native.sampleRate = SAMPLE_RATE
        native.frequency = TUNE_FREQUENCY
        val out = SamplePacket(16)
        val rng = Random(0x51058L)
        val packet = ByteArray(64)
        rng.nextBytes(packet)

        val producedBeforeClose = native.mixPacketIntoSamplePacket(packet, out, CHANNEL_FREQUENCY)
        assertTrue("expected the pre-close call to actually produce samples", producedBeforeClose > 0)

        native.close()

        val postCloseOut = SamplePacket(16)
        val producedAfterClose = native.mixPacketIntoSamplePacket(packet, postCloseOut, CHANNEL_FREQUENCY)
        assertEquals(0, producedAfterClose)
        assertEquals(0, postCloseOut.size())
    }
}
