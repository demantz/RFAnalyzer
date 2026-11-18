package com.mantz_it.rfanalyzer.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * <h1>RF Analyzer - Native Signed 8-Bit IQ Mixer Edge Case Tests</h1>
 *
 * Module:      NativeIQMixerEdgeCasesTest.kt
 * Description: Edge-case / fuzz coverage for the native IQMixer (Signed8BitIQConverter, HackRF
 *              format) - a companion to NativeIQMixerGoldenTest.kt's "normal input" bit-exact
 *              sweep. Native crashes are hard to reproduce from a Play Console stack trace, so they
 *              are caught here: every case compares the native output against the Kotlin fallback
 *              (the trusted reference), so a wrong-but-not-crashing result fails too.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class NativeIQMixerEdgeCasesTest {

    companion object {
        private const val SAMPLE_RATE = 16_000_000
        private const val TUNE_FREQUENCY = 91_871_991L
        private const val CHANNEL_FREQUENCY = 91_800_000L
        // Real HackRF USB transfer buffers are 262144 bytes (HackrfDevice.BUFFER_SIZE); this is
        // deliberately much larger to stress the native malloc path beyond any realistic single
        // packet, simulating a pathological/adversarial caller.
        private const val LARGE_PACKET_BYTES = 4 * 1024 * 1024
    }

    private fun newPair(): Pair<Signed8BitIQConverter, Signed8BitIQConverter> {
        val native = Signed8BitIQConverter()
        val fallback = Signed8BitIQConverter(true)
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
        val rng = Random(0xEDDE1L)
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
        // Signed 8-bit extremes: -128 (0x80) and 127 (0x7F), all four (I,Q) combinations.
        val (native, fallback) = newPair()
        val nativeOut = SamplePacket(16)
        val fallbackOut = SamplePacket(16)
        val packet = byteArrayOf(
            -128, -128,
            -128, 127,
            127, -128,
            127, 127,
            0, 0
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
        // Coarse but real check against native leaks/use-after-free/double-free in
        // nativeCreate/nativeDestroy - no mixing, just construct-and-tear-down under churn.
        repeat(1000) {
            val c = Signed8BitIQConverter()
            assertTrue(c.isUsingNative)
            c.close()
        }
    }

    @Test
    fun mixAfterClose() {
        val native = Signed8BitIQConverter()
        native.sampleRate = SAMPLE_RATE
        native.frequency = TUNE_FREQUENCY
        val out = SamplePacket(16)
        val rng = Random(0x51057L)
        val packet = ByteArray(64)
        rng.nextBytes(packet)

        // Establish a real mixer table first, so this exercises "was actively used, then closed"
        // rather than "never used at all".
        val producedBeforeClose = native.mixPacketIntoSamplePacket(packet, out, CHANNEL_FREQUENCY)
        assertTrue("expected the pre-close call to actually produce samples", producedBeforeClose > 0)

        native.close()

        // Must not crash and must not silently produce garbage - the native code's handle==0
        // guard (see iq_byte_mixer.c) should make this a clean no-op.
        val postCloseOut = SamplePacket(16)
        val producedAfterClose = native.mixPacketIntoSamplePacket(packet, postCloseOut, CHANNEL_FREQUENCY)
        assertEquals(0, producedAfterClose)
        assertEquals(0, postCloseOut.size())
    }
}
