package com.mantz_it.rfanalyzer.source

import com.mantz_it.rfanalyzer.analyzer.OverloadMonitor
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData

/**
 * <h1>RF Analyzer - Source Stall Tracker</h1>
 *
 * Module:      SourceStallTracker.kt
 * Description: Forwards a source's cumulative "had to wait for a free buffer" counters (USB
 *              drivers' emptyBufferPoolStalls/emptyBufferStallNs, rtl_tcp's receive wait) as deltas
 *              to the debug counters and to [OverloadMonitor]: while the receiving thread waits,
 *              the device or server keeps sending, and what doesn't fit into its own buffers is
 *              lost. Call from the source's getPacket() (Scheduler thread); restarts cleanly when
 *              the counters do (a new device instance).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class SourceStallTracker {
    private var lastStalls = 0L
    private var lastStallNs = 0L

    fun update(stalls: Long, stallNs: Long) {
        if (stalls < lastStalls || stallNs < lastStallNs) {
            lastStalls = 0L
            lastStallNs = 0L
        }
        val stallDelta = stalls - lastStalls
        if (stallDelta <= 0) return
        val stallNsDelta = stallNs - lastStallNs
        GlobalPerformanceData.incrementCounter("source.bufferStalls", stallDelta)
        GlobalPerformanceData.incrementCounter("source.bufferStallMs", stallNsDelta / 1_000_000)
        OverloadMonitor.reportLoss("Source", stallNsDelta)
        lastStalls = stalls
        lastStallNs = stallNs
    }
}
