package com.mantz_it.rfanalyzer.dsp

/**
 * <h1>RF Analyzer - DSP Window Function</h1>
 *
 * Module:      WindowFunctions.kt
 *
 * Description: This class implements Window Functions (Hamming, Kaiser, ...)
 * for DSP operations. Mostly copied and inspired from GNU Radio.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
interface WindowFunction {
    /**
     * @param n index of tap
     * @param N total number of taps
     * @return window coefficient at index n
     */
    fun value(n: Int, N: Int): Float

    /**
     * @return human-readable name for logging/debugging
     */
    fun name(): String
}

class BlackmanWindow : WindowFunction {
    override fun value(n: Int, N: Int): Float {
        return (0.42f
                - 0.5f * kotlin.math.cos(2.0 * Math.PI * n / (N - 1)).toFloat()
                + 0.08f * kotlin.math.cos(4.0 * Math.PI * n / (N - 1)).toFloat())
    }

    override fun name() = "Blackman"
}


class HammingWindow : WindowFunction {
    override fun value(n: Int, N: Int): Float {
        return (0.54f - 0.46f * kotlin.math.cos(2.0 * Math.PI * n / (N - 1)).toFloat())
    }

    override fun name() = "Hamming"
}

class KaiserWindow(private val beta: Double) : WindowFunction {

    override fun value(n: Int, N: Int): Float {
        if (beta < 0.0) throw IllegalArgumentException("Kaiser beta must be >= 0")

        // Precompute inverse of I0(beta) once
        val iBeta = 1.0 / izero(beta)

        // Special case for edges (numerical stability)
        if (n == 0 || n == N - 1) {
            return iBeta.toFloat()
        }

        val inm1 = 1.0 / (N - 1).toDouble()
        val temp = 2.0 * n * inm1 - 1.0
        val valN = izero(beta * kotlin.math.sqrt(1.0 - temp * temp)) * iBeta
        return valN.toFloat()
    }

    override fun name() = "Kaiser(β=$beta)"

    /** Modified Bessel function of order 0 (approximation like GNU Radio) */
    private fun izero(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val halfX = x / 2.0
        var k = 1
        while (true) {
            val tmp = halfX / k
            term *= tmp * tmp
            val delta = term
            sum += delta
            if (delta < 1e-12) break
            k++
        }
        return sum
    }
}

