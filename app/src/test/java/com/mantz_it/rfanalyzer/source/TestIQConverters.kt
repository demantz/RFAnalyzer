package com.mantz_it.rfanalyzer.source

import com.mantz_it.rfanalyzer.testutil.Golden
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.random.Random

/**
 * <h1>RF Analyzer - IQ Converter Tests</h1>
 *
 * Module:      TestIQConverters.kt
 * Description: The four IQ converters (on the JVM their Kotlin engines - the native ones have their
 *              own golden tests on the device): conversion to floats and mixing to a channel keep
 *              their results bit for bit (Golden), including the oscillator's phase across packets
 *              and a full output packet.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestIQConverters {

    private fun bytes(n: Int, seed: Int) = Random(seed).nextBytes(n)

    private fun float32Bytes(samples: Int, seed: Int): ByteArray {
        val r = Random(seed)
        val b = ByteBuffer.allocate(samples * 8).order(ByteOrder.LITTLE_ENDIAN)
        repeat(samples * 2) { b.putFloat(r.nextFloat() * 2 - 1) }
        return b.array()
    }

    private fun check(name: String, converter: IQConverter, packet: (Int) -> ByteArray) {
        converter.sampleRate = 1_000_000
        converter.frequency = 100_000_000L
        val out = ArrayList<Float>()
        // floats for the FFT
        val first = packet(0)
        val interleaved = FloatArray(first.size)
        converter.fillPacketIntoInterleavedBuffer(first, interleaved)
        interleaved.forEach { out.add(it) }
        // mixing: the channel at the centre (mixes with the sample rate), above and below it, then a
        // run of packets into one output packet until it is full
        for ((i, channel) in listOf(100_000_000L, 100_100_000L, 99_763_000L, 99_763_000L).withIndex()) {
            val p = packet(i + 1)
            val samplePacket = SamplePacket(p.size)
            val count = converter.mixPacketIntoSamplePacket(p, samplePacket, channel)
            assertEquals(samplePacket.size(), count)
            assertEquals(1_000_000, samplePacket.sampleRate)
            assertEquals(channel, samplePacket.frequency)
            for (k in 0 until samplePacket.size()) { out.add(samplePacket.re(k)); out.add(samplePacket.im(k)) }
        }
        val full = SamplePacket(700)
        var total = 0
        repeat(3) { total += converter.mixPacketIntoSamplePacket(packet(10 + it), full, 100_050_000L) }
        assertEquals(700, full.size())
        assertEquals(700, total)
        for (k in 0 until full.size()) { out.add(full.re(k)); out.add(full.im(k)) }
        converter.close()
        Golden.check(name, out.toFloatArray())
    }

    @Test
    fun signed8Bit() {
        val c = Signed8BitIQConverter()
        assertFalse(c.isUsingNative)
        check("iq_signed8", c) { bytes(1000, it) }
    }

    @Test
    fun unsigned8Bit() {
        val c = Unsigned8BitIQConverter()
        assertFalse(c.isUsingNative)
        check("iq_unsigned8", c) { bytes(1000, it) }
    }

    @Test
    fun signed16Bit() {
        val c = Signed16BitIQConverter()
        assertFalse(c.isUsingNative)
        check("iq_signed16", c) { bytes(2000, it) }
    }

    @Test
    fun float32() {
        val c = Float32IQConverter()
        assertFalse(c.isUsingNative)
        check("iq_float32", c) { float32Bytes(500, it) }
    }

    @Test
    fun aTruncatedPacketsOddLastByteIsLeftOut() {
        val c = Unsigned8BitIQConverter().apply { sampleRate = 1_000_000; frequency = 0 }
        val p = SamplePacket(10)
        assertEquals(2, c.mixPacketIntoSamplePacket(bytes(10, 1), p, 0L, 5))
    }
}
