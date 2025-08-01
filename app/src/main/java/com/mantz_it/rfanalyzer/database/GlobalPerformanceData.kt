package com.mantz_it.rfanalyzer.database

/**
 * <h1>RF Analyzer - Global Performance Data</h1>
 *
 * Module:      GlobalPerformanceData.kt
 * Description: A singleton object holding averaged performance data from various Threads.
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


object GlobalPerformanceData {

    private const val alpha = 0.05f

    // exponential moving average (EMA)
    private data class EmaState(var value: Float, var initialized: Boolean = false)

    private val loads = mutableMapOf<String, EmaState>()
    private val lock = Any()

    fun updateLoad(id: String, newSample: Float) {
        if (newSample.isNaN() || newSample.isInfinite())
            return
        synchronized(lock) {
            val state = loads.getOrPut(id) { EmaState(0f) }

            if (state.initialized) {
                state.value += alpha * (newSample - state.value)
            } else {
                state.value = newSample
                state.initialized = true
            }
        }
    }

    fun getLoad(id: String): Float {
        synchronized(lock) {
            return loads[id]?.value ?: 0f
        }
    }

    fun getAllLoads(): Map<String, Float> {
        synchronized(lock) {
            return loads.mapValues { it.value.value }
        }
    }

    fun reset() {
        synchronized(lock) {
            loads.clear()
        }
    }
}

