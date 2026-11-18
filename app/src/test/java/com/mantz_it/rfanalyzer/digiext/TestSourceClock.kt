package com.mantz_it.rfanalyzer.digiext

import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Source Clock Tests</h1>
 *
 * Module:      TestSourceClock.kt
 * Description: The samples' clock the Digital tab counts in while live - a replayed recording's own
 *              time.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestSourceClock {

    private fun samples(startMs: Long, rate: Int = 1_000, size: Int = 500) =
        SamplePacket(FloatArray(size), FloatArray(size), 0L, rate, size, startMs)

    @Test
    fun withoutSamplesItIsTheWallClock() {
        val clock = SourceClock()
        clock.startSession()
        assertTrue(kotlin.math.abs(clock.nowMs() - System.currentTimeMillis()) < 1_000)
        assertNull(clock.sessionStartMs.value)
        assertEquals(Long.MAX_VALUE, clock.sessionEndMs())
        assertNull(clock.replayWindow())
    }

    @Test
    fun aRecordingSetsTheClockAndTheSessionWindow() {
        val clock = SourceClock()
        clock.startSession()
        val recorded = 1_785_401_350_000L // 30.07.2026, months ago
        clock.onSamples(samples(recorded))
        clock.onSamples(samples(recorded + 500))
        // now = the end of the newest samples, give or take the time the test takes
        assertTrue(kotlin.math.abs(clock.nowMs() - (recorded + 1_000)) < 1_000)
        assertEquals(recorded, clock.sessionStartMs.value)
        val window = SessionWindow(recorded, clock)
        assertTrue(recorded + 800 in window)
        assertFalse(recorded - 1 in window)
        assertFalse(System.currentTimeMillis() in window) // heard later in real time
        assertEquals(recorded..recorded + 1_000 + 1_000, clock.replayWindow())
    }

    @Test
    fun aLoopingFileKeepsItsStartAndANewSessionStartsOver() {
        val clock = SourceClock()
        clock.startSession()
        val start = 1_785_401_350_000L
        clock.onSamples(samples(start + 60_000))
        clock.onSamples(samples(start)) // the file started over
        assertEquals(start, clock.sessionStartMs.value)
        val sessions = clock.sessions.value
        clock.startSession()
        assertNull(clock.sessionStartMs.value)
        assertEquals(sessions + 1, clock.sessions.value)
    }

    @Test
    fun aLiveSourceIsNoReplay() {
        val clock = SourceClock()
        clock.startSession()
        clock.onSamples(samples(System.currentTimeMillis() - 300))
        assertNotNull(clock.sessionStartMs.value)
        assertNull(clock.replayWindow())
        clock.endSession()
        assertNull(clock.replayWindow())
    }
}
