package com.mantz_it.rfanalyzer.analyzer

import com.mantz_it.rfanalyzer.analyzer.OverloadMonitor.Level
import com.mantz_it.rfanalyzer_ipc.digiext.PipelineHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Overload Monitor Tests</h1>
 *
 * Module:      TestOverloadMonitor.kt
 * Description: Tests for OverloadMonitor: the level follows the busiest stage, any dropped signal
 *              or missed deadline is an overload, the warning chip is held for a few seconds after
 *              recovery, nothing is reported during the start grace period, and a reconfiguration
 *              restarts the grace period but keeps a chip already shown. tick() combines the
 *              reports of a window and reset() clears them.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestOverloadMonitor {

    private val second = 1_000_000_000L

    private fun window(
        loads: Map<String, Float> = emptyMap(),
        lost: Map<String, Long> = emptyMap(),
        missed: Int = 0,
        missedSource: String? = null,
    ) = OverloadMonitor.Window(second, loads, lost, missed, missedSource)

    private fun eval(w: OverloadMonitor.Window, lastOverloadMs: Long? = null, nowMs: Long = 10_000L, inGrace: Boolean = false) =
        OverloadMonitor.evaluate(w, lastOverloadMs, nowMs, inGrace)

    @Test
    fun levelFollowsTheBusiestStage() {
        assertEquals(Level.NORMAL, eval(window(mapOf("Scheduler" to 0.69f))).first.level)
        assertEquals(Level.HIGH_LOAD, eval(window(mapOf("Scheduler" to 0.2f, "Resampler" to 0.7f))).first.level)
        val (state, _) = eval(window(mapOf("Scheduler" to 0.2f, "Demodulator" to 1.0f)))
        assertEquals(Level.OVERLOAD, state.level)
        assertEquals("Demodulator", state.maxLoadStage)
        assertTrue(state.chipVisible)
    }

    @Test
    fun anyDroppedSignalIsAnOverloadEvenAtLowLoad() {
        val (state, last) = eval(window(mapOf("Scheduler" to 0.3f), lost = mapOf("Resampler" to second / 100, "Scheduler" to second / 50)))
        assertEquals(Level.OVERLOAD, state.level)
        assertEquals(0.03f, state.lostFraction, 1e-6f)
        assertEquals("Scheduler", state.lossStage)
        assertTrue(state.chipVisible)
        assertEquals(10_000L, last)
    }

    @Test
    fun missedDeadlineIsAnOverload() {
        val (state, _) = eval(window(missed = 1, missedSource = "FT8 decode"))
        assertEquals(Level.OVERLOAD, state.level)
        assertEquals("FT8 decode", state.missedDeadlineSource)
    }

    @Test
    fun chipIsHeldForAFewSecondsAfterRecovery() {
        val clean = window(mapOf("Scheduler" to 0.2f))
        val overloadAt = 10_000L
        assertTrue(eval(clean, lastOverloadMs = overloadAt, nowMs = overloadAt + OverloadMonitor.CHIP_HOLD_MS - 1).first.chipVisible)
        val (later, last) = eval(clean, lastOverloadMs = overloadAt, nowMs = overloadAt + OverloadMonitor.CHIP_HOLD_MS)
        assertFalse(later.chipVisible)
        assertEquals(Level.NORMAL, later.level)
        assertEquals(overloadAt, last)
    }

    @Test
    fun nothingIsReportedDuringStartGrace() {
        val (state, last) = eval(window(lost = mapOf("Scheduler" to second)), inGrace = true)
        assertEquals(Level.NORMAL, state.level)
        assertFalse(state.chipVisible)
        assertNull(last)
    }

    @Test
    fun tickCombinesReportsAndResetClearsThem() {
        OverloadMonitor.reset(nowMs = 0L)
        OverloadMonitor.reportBusy("Demodulator", busyNs = 8, signalNs = 10)
        OverloadMonitor.reportExtensionHealth(PipelineHealth(windowMs = 1000, dspLoad = 0.5f, peakDspLoad = 0.9f, missedDeadlines = 0))
        OverloadMonitor.tick(nowMs = OverloadMonitor.START_GRACE_MS + 1)
        var state = OverloadMonitor.state.value
        assertEquals(Level.HIGH_LOAD, state.level)
        assertEquals("Demodulator", state.maxLoadStage)
        assertEquals(0.8f, state.maxLoad, 1e-6f)

        OverloadMonitor.reportExtensionHealth(PipelineHealth(windowMs = 1000, dspLoad = 0.1f, peakDspLoad = 0.1f, missedDeadlines = 2, missedDeadlineSource = "RDS hand-off"))
        OverloadMonitor.tick(nowMs = OverloadMonitor.START_GRACE_MS + 1001)
        state = OverloadMonitor.state.value
        assertEquals(Level.OVERLOAD, state.level)
        assertEquals(2, state.missedDeadlines)
        assertTrue(state.chipVisible)

        OverloadMonitor.reset()
        state = OverloadMonitor.state.value
        assertEquals(Level.NORMAL, state.level)
        assertFalse(state.chipVisible)
        assertNull(state.maxLoadStage)
    }

    /** A reconfiguration while running restarts the grace period but keeps a chip already shown. */
    @Test
    fun restartGraceHidesTheReconfigurationButKeepsTheChip() {
        val grace = OverloadMonitor.START_GRACE_MS
        OverloadMonitor.reset(nowMs = 0L)
        OverloadMonitor.reportLoss("Scheduler", lostSignalNs = 1_000)
        OverloadMonitor.tick(nowMs = grace + 1)
        assertTrue(OverloadMonitor.state.value.chipVisible)

        OverloadMonitor.restartGrace(nowMs = grace + 500)
        OverloadMonitor.reportLoss("Digital extension input", lostSignalNs = 1_000) // the rebuild's flush
        OverloadMonitor.tick(nowMs = grace + 1001)
        assertEquals(Level.NORMAL, OverloadMonitor.state.value.level)
        assertTrue("the earlier overload's chip stays for its hold time", OverloadMonitor.state.value.chipVisible)

        OverloadMonitor.reportLoss("Scheduler", lostSignalNs = 1_000)
        OverloadMonitor.tick(nowMs = 2 * grace + 600)
        assertEquals(Level.OVERLOAD, OverloadMonitor.state.value.level)
        OverloadMonitor.reset()
    }
}
