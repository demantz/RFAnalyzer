package com.mantz_it.rfanalyzer.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * <h1>RF Analyzer - Native 16-Bit IQ Mixer Edge Case Tests</h1>
 *
 * Module:      NativeIQMixer16EdgeCasesTest.kt
 * Description: Edge-case / fuzz coverage for the native IQMixer16 (Signed16BitIQConverter,
 *              Airspy/HydraSDR/KiwiSDR format) - a companion to NativeIQMixer16GoldenTest.kt's
 *              "normal input" bit-exact sweep. See NativeIQMixerEdgeCasesTest.kt (the signed-8-bit
 *              sibling) for the rationale: every case compares native output against the Kotlin
 *              fallback rather than just checking "doesn't crash".
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class NativeIQMixer16EdgeCasesTest {

    companion object {
        private const val SAMPLE_RATE = 10_000_000
        private const val TUNE_FREQUENCY = 145_871_991L
        private const val CHANNEL_FREQUENCY = 145_800_000L
        // Real Airspy/HydraSDR USB transfer buffers are 262144 bytes; this is deliberately much
        // larger to stress the native malloc path beyond any realistic single packet, simulating
        // a pathological/adversarial caller.
        private const val LARGE_PACKET_BYTES = 4 * 1024 * 1024
    }

    private fun newPair(): Pair<Signed16BitIQConverter, Signed16BitIQConverter> {
        val native = Signed16BitIQConverter()
        val fallback = Signed16BitIQConverter(forceKotlinFallback = true)
        assertTrue("expected native converter to actually use native backend", native.isUsingNative)
        assertTrue("expected forced-fallback converter to use Kotlin backend", !fallback.isUsingNative)
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

        val producedNative = native.mixPacketIntoSamplePacket(ByteArray(0), nativeOut, CHANNEL_FREQUENCY)
        val producedFallback = fallback.mixPacketIntoSamplePacket(ByteArray(0), fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(0, producedNative)
        assertEquals(0, producedFallback)
        assertBitExact(nativeOut, fallbackOut)
        native.close(); fallback.close()
    }

    @Test
    fun truncatedTailPacket() {
        // 3 bytes: below one full 4-byte (I-lo, I-hi, Q-lo, Q-hi) sample - should produce
        // nothing, not crash. Also try 1 and 2 bytes for good measure.
        for (byteCount in intArrayOf(1, 2, 3)) {
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
        val rng = Random(0xEDDE3L)
        val packet = ByteArray(LARGE_PACKET_BYTES)
        rng.nextBytes(packet)
        val capacity = LARGE_PACKET_BYTES / 4 + 16
        val nativeOut = SamplePacket(capacity)
        val fallbackOut = SamplePacket(capacity)

        val producedNative = native.mixPacketIntoSamplePacket(packet, nativeOut, CHANNEL_FREQUENCY)
        val producedFallback = fallback.mixPacketIntoSamplePacket(packet, fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(producedFallback, producedNative)
        assertTrue("expected a large number of samples to be produced", producedNative > 500_000)
        assertBitExact(nativeOut, fallbackOut)
        native.close(); fallback.close()
    }

    @Test
    fun boundarySampleValues() {
        // Signed 16-bit extremes: Short.MIN_VALUE (0x8000) and Short.MAX_VALUE (0x7FFF),
        // little-endian, all four (I,Q) combinations.
        val (native, fallback) = newPair()
        val nativeOut = SamplePacket(16)
        val fallbackOut = SamplePacket(16)
        // little-endian: MIN_VALUE = 00 80, MAX_VALUE = FF 7F
        val min = byteArrayOf(0x00, 0x80.toByte())
        val max = byteArrayOf(0xFF.toByte(), 0x7F)
        val packet = min + min + min + max + max + min + max + max + byteArrayOf(0, 0, 0, 0)

        val producedNative = native.mixPacketIntoSamplePacket(packet, nativeOut, CHANNEL_FREQUENCY)
        val producedFallback = fallback.mixPacketIntoSamplePacket(packet, fallbackOut, CHANNEL_FREQUENCY)

        assertEquals(5, producedNative)
        assertEquals(producedFallback, producedNative)
        assertBitExact(nativeOut, fallbackOut)
        native.close(); fallback.close()
    }

    @Test
    fun repeatedCreateCloseCycling() {
        repeat(1000) {
            val c = Signed16BitIQConverter()
            assertTrue(c.isUsingNative)
            c.close()
        }
    }

    @Test
    fun mixAfterClose() {
        val native = Signed16BitIQConverter()
        native.sampleRate = SAMPLE_RATE
        native.frequency = TUNE_FREQUENCY
        val out = SamplePacket(16)
        val rng = Random(0x51059L)
        val packet = ByteArray(128)
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
