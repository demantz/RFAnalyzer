package com.mantz_it.rfanalyzer.database

/**
 * <h1>RF Analyzer - Global Performance Data</h1>
 *
 * Module:      GlobalPerformanceData.kt
 * Description: A singleton object holding averaged performance data from various Threads.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


object GlobalPerformanceData {

    private const val alpha = 0.05f

    // exponential moving average (EMA)
    private data class EmaState(var value: Float, var initialized: Boolean = false)

    private val loads = mutableMapOf<String, EmaState>()
    // Cumulative event counters (queue drops, IPC poll misses, ring overwrites, ...): rare, bursty
    // events that an average would hide.
    private val counters = mutableMapOf<String, Long>()
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

    fun incrementCounter(id: String, by: Long = 1) {
        synchronized(lock) {
            counters[id] = (counters[id] ?: 0L) + by
        }
    }

    fun getAllCounters(): Map<String, Long> {
        synchronized(lock) {
            return counters.toMap()
        }
    }

    fun reset() {
        synchronized(lock) {
            loads.clear()
            counters.clear()
        }
    }
}

