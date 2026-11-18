package com.mantz_it.rfanalyzer.source

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random

/**
 * <h1>RF Analyzer - Native FFT Feed Conversion Tests</h1>
 *
 * Module:      NativeFftFeedConversionTest.kt
 * Description: fillPacketIntoInterleavedBuffer() (the spectrum FFT feed) of the native-backed IQ
 *              converters must be bit-identical to their Kotlin fallback: every byte value, odd
 *              lengths, partial samples, and a valid-byte count shorter than the packet. The output
 *              buffers start with the same sentinel, so writes beyond the converted range would
 *              show up too.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class NativeFftFeedConversionTest {

    private val lengths = intArrayOf(0, 1, 2, 3, 4, 5, 255, 256, 257, 262144, 262147)

    private fun packet(length: Int, seed: Long): ByteArray {
        val p = ByteArray(length)
        Random(seed).nextBytes(p)
        for (i in 0 until minOf(256, length)) p[i] = i.toByte() // every byte value
        return p
    }

    private fun assertBitExact(expected: FloatArray, actual: FloatArray, what: String) {
        for (i in expected.indices)
            assertEquals("$what differs at $i", java.lang.Float.floatToRawIntBits(expected[i]), java.lang.Float.floatToRawIntBits(actual[i]))
    }

    private fun compare(native: IQConverter, fallback: IQConverter, convert: (IQConverter, ByteArray, FloatArray) -> Boolean, name: String) {
        for (length in lengths) {
            val p = packet(length, length.toLong())
            val outNative = FloatArray(length + 8) { -7.5f }
            val outFallback = FloatArray(length + 8) { -7.5f }
            assertEquals(convert(fallback, p, outFallback), convert(native, p, outNative))
            assertBitExact(outFallback, outNative, "$name length=$length")
        }
    }

    // Only the first validByteCount bytes are samples (a pooled USB buffer, see SampleBufferPool)
    private fun compareValidByteCounts(native: IQConverter, fallback: IQConverter, name: String) {
        val p = packet(262144, 42)
        for (valid in intArrayOf(0, 3, 4, 65536, 65539, 262144)) {
            val outNative = FloatArray(262144) { -7.5f }
            val outFallback = FloatArray(262144) { -7.5f }
            fallback.fillPacketIntoInterleavedBuffer(p, outFallback, valid)
            native.fillPacketIntoInterleavedBuffer(p, outNative, valid)
            assertBitExact(outFallback, outNative, "$name valid=$valid")
        }
    }

    @Test
    fun signed8Bit() {
        val native = Signed8BitIQConverter()
        val fallback = Signed8BitIQConverter(true)
        assertTrue(native.isUsingNative && !fallback.isUsingNative)
        compare(native, fallback, { c, p, o -> c.fillPacketIntoInterleavedBuffer(p, o) }, "signed 8 bit")
        compareValidByteCounts(native, fallback, "signed 8 bit")
    }

    @Test
    fun unsigned8Bit() {
        val native = Unsigned8BitIQConverter()
        val fallback = Unsigned8BitIQConverter(true)
        assertTrue(native.isUsingNative && !fallback.isUsingNative)
        compare(native, fallback, { c, p, o -> c.fillPacketIntoInterleavedBuffer(p, o) }, "unsigned 8 bit")
        compareValidByteCounts(native, fallback, "unsigned 8 bit")
    }

    @Test
    fun signed16Bit() {
        val native = Signed16BitIQConverter()
        val fallback = Signed16BitIQConverter(forceKotlinFallback = true)
        assertTrue(native.isUsingNative && !fallback.isUsingNative)
        compare(native, fallback, { c, p, o -> c.fillPacketIntoInterleavedBuffer(p, o) }, "signed 16 bit")
        compareValidByteCounts(native, fallback, "signed 16 bit")
        native.close(); fallback.close()
    }
}
