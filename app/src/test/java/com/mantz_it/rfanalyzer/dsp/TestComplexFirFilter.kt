package com.mantz_it.rfanalyzer.dsp

import com.mantz_it.rfanalyzer.testutil.Golden
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.random.Random

/**
 * <h1>RF Analyzer - Complex FIR Filter Tests</h1>
 *
 * Module:      TestComplexFirFilter.kt
 * Description: The band-pass filter of SSB and CW: its outputs stay bit for bit what they were
 *              (Golden).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestComplexFirFilter {

    private fun noise(n: Int, seed: Int) = Random(seed).let { r -> FloatArray(n) { r.nextFloat() * 2 - 1 } }

    /** Filters [packets] input packets of [size] samples, collecting every output sample (re, im interleaved). */
    private fun run(filter: ComplexFirFilter, packets: Int, size: Int, outCapacity: Int): FloatArray {
        val out = ArrayList<Float>()
        for (p in 0 until packets) {
            val input = SamplePacket(noise(size, 2 * p), noise(size, 2 * p + 1), 7_100_000L, 48_000, size, 1000L + p)
            var offset = 0
            while (offset < input.size()) {
                val output = SamplePacket(outCapacity)
                offset += filter.filter(input, output, offset, input.size() - offset)
                assertEquals(48_000 / filter.decimation, output.sampleRate)
                assertEquals(7_100_000L, output.frequency)
                assertEquals(1000L + p, output.timestamp)
                for (i in 0 until output.size()) { out.add(output.re(i)); out.add(output.im(i)) }
            }
        }
        return out.toFloatArray()
    }

    @Test
    fun ssbBandPassKeepsItsOutput() {
        val filter = ComplexFirFilter.createBandPass(1, 1f, 48_000f, 300f, 3_000f, 300f, 60f, 0)!!
        assertEquals(1, filter.decimation)
        assertEquals(437, filter.numberOfTaps) // 60 dB · 48 kHz / (22 · 300 Hz), made odd
        Golden.check("complex_fir_ssb", run(filter, packets = 4, size = 1000, outCapacity = 1000))
    }

    @Test
    fun decimatingFilterWithASmallOutputKeepsItsOutput() {
        // decimation 3, an output packet smaller than what the input gives: the early return
        val filter = ComplexFirFilter.createBandPass(3, 2f, 48_000f, -2_000f, -200f, 400f, 50f, 101)!!
        assertEquals(101, filter.numberOfTaps)
        assertEquals(-2_000f, filter.lowCutOffFrequency)
        assertEquals(-200f, filter.highCutOffFrequency)
        Golden.check("complex_fir_decimating", run(filter, packets = 3, size = 999, outCapacity = 70))
    }

    @Test
    fun invalidParametersGiveNoFilter() {
        assertNull(ComplexFirFilter.createBandPass(1, 1f, 0f, 0f, 100f, 10f, 60f))
        assertNull(ComplexFirFilter.createBandPass(1, 1f, 1000f, -600f, 100f, 10f, 60f))
        assertNull(ComplexFirFilter.createBandPass(1, 1f, 1000f, 100f, 100f, 10f, 60f))
        assertNull(ComplexFirFilter.createBandPass(1, 1f, 1000f, 0f, 100f, 0f, 60f))
    }
}
