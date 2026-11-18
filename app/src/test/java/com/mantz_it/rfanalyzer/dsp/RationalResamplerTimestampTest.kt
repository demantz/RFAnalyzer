package com.mantz_it.rfanalyzer.dsp

import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - Rational Resampler Timestamp Tests</h1>
 *
 * Module:      RationalResamplerTimestampTest.kt
 * Description: Tests for [RationalResampler]'s timestamps: an input packet resampled into several
 *              output packets gives each output packet the time of its first input sample.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class RationalResamplerTimestampTest {

    @Test
    fun outputPacketsCarryTheTimeOfTheirFirstInputSample() {
        val resampler = RationalResampler(1, 4, forceKotlinFallback = true)
        try {
            val input = SamplePacket(FloatArray(48000), FloatArray(48000), 0L, 48000, 48000, 1_000_000L)
            // the Resampler thread: one input packet into several output packets
            var offset = 0
            val outputs = mutableListOf<Pair<Int, Long>>()
            while (offset < input.size()) {
                val output = SamplePacket(3000)
                val consumed = resampler.resample(input, output, offset, input.size() - offset)
                outputs.add(offset to output.timestamp)
                offset += consumed
            }
            assertEquals(4, outputs.size)
            for ((inputOffset, timestamp) in outputs) assertEquals(1_000_000L + inputOffset / 48, timestamp)
        } finally {
            resampler.close()
        }
    }
}
