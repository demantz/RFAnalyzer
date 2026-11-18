package com.mantz_it.rfanalyzer.analyzer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * <h1>RF Analyzer - Squelch Tests</h1>
 *
 * Module:      TestSquelch.kt
 * Description: Tests for SquelchGate: it opens at once above the margin, closes only after the hang
 *              time, stays open within the hysteresis, is always open while disabled and smooths
 *              the noise floor. Also channelLevel() (the mean power of the channel's bins, not the
 *              mean of their dB values) and noiseFloor() (the noise beside the channel, unaffected
 *              by other signals there, by the part of the band a recording doesn't hold and by the
 *              band's roll-off).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestSquelch {

    private fun gate(margin: Float = 10f) = SquelchGate().apply { enabled = true; marginDb = margin }

    @Test
    fun opensAtOnceAboveTheMarginAndClosesOnlyAfterTheHangTime() {
        val g = gate()
        // below the threshold for longer than the hang time: closed
        g.update(-100f, -100f, 0); g.update(-100f, -100f, 400)
        assertFalse(g.open)
        assertTrue(g.update(-89f, -100f, 500)) // 11 dB above the floor
        assertTrue(g.update(-100f, -100f, 533)) // dropped: still open (hang)
        assertTrue(g.update(-100f, -100f, 800))
        assertFalse(g.update(-100f, -100f, 833)) // 300 ms below
    }

    @Test
    fun hysteresisKeepsItOpenJustBelowTheThreshold() {
        val g = gate()
        g.update(-89f, -100f, 0)
        // 8 dB above the floor: below the 10 dB threshold but within the 3 dB hysteresis
        for (t in 33L..2000L step 33) assertTrue(g.update(-92f, -100f, t))
        // 6 dB above: below threshold - hysteresis, closes after the hang time
        g.update(-94f, -100f, 2033)
        assertTrue(g.update(-94f, -100f, 2300))
        assertFalse(g.update(-94f, -100f, 2340))
    }

    @Test
    fun aShortDipDoesNotClose() {
        val g = gate()
        g.update(-80f, -100f, 0)
        g.update(-100f, -100f, 33)
        g.update(-100f, -100f, 200)
        g.update(-80f, -100f, 233) // back before the hang time ran out
        assertTrue(g.update(-100f, -100f, 500))
        assertTrue(g.open)
    }

    @Test
    fun disabledIsAlwaysOpen() {
        val g = SquelchGate()
        assertTrue(g.update(-150f, -100f, 0))
        g.enabled = true
        g.update(-150f, -100f, 33); g.update(-150f, -100f, 400)
        assertFalse(g.open)
        g.enabled = false
        assertTrue(g.open)
    }

    @Test
    fun theFloorIsSmoothed() {
        val g = gate()
        g.update(-100f, -100f, 0)
        assertEquals(-100f, g.floorDb, 0.01f)
        g.update(-100f, -80f, 33) // one noisy frame moves it only a little
        assertTrue(g.floorDb < -98f)
        assertEquals(-88f, g.thresholdDb, 2f)
    }

    private fun byteOf(db: Float) = ((db - FftProcessor.MIN_DB) / FftProcessor.INV_SCALE).roundToInt().toByte()

    @Test
    fun channelLevelIsTheMeanPowerNotTheMeanOfDb() {
        // 100 bins of noise at -100 dB, 10 of them carrying a signal 30 dB higher
        val bins = ByteArray(100) { byteOf(-100f) }
        for (i in 45 until 55) bins[i] = byteOf(-70f)
        // mean power: (90 * 1e-10 + 10 * 1e-7) / 100 ~ 1.0e-8 -> about -80 dB (the mean of dB would be -97)
        assertEquals(-80f, channelLevel(bins, 0, 100), 0.5f)
        assertEquals(-100f, channelLevel(bins, 0, 40), 0.5f)
        assertTrue(channelLevel(bins, 5, 5).isNaN())
    }

    @Test
    fun noiseFloorIgnoresSignalsBesideTheChannel() {
        val bins = ByteArray(1000) { byteOf(-100f) }
        for (i in 300 until 400) bins[i] = byteOf(-60f) // half of the left side busy
        for (i in 480 until 520) bins[i] = byteOf(-50f) // the channel itself
        assertEquals(-100f, noiseFloor(bins, 480, 520), 1f)
    }

    @Test
    fun noiseFloorIsTheChannelsNoiseInARecordingThatHoldsOnlyPartOfItsBand() {
        // a quarter of the band received at -100 dB, the rest 36 dB lower (the db0zh recording)
        val bins = ByteArray(4096) { byteOf(-136f) }
        for (i in 1500 until 2500) bins[i] = byteOf(-100f)
        assertEquals(-100f, noiseFloor(bins, 1900, 1960), 1f)
        // at the passband's edge: the side outside it is ignored
        assertEquals(-100f, noiseFloor(bins, 2440, 2480), 1f)
        assertEquals(-100f, noiseFloor(bins, 1500, 1540), 1f)
    }

    @Test
    fun noiseFloorAtTheBandsEdgeAndForAChannelAsWideAsTheBand() {
        val bins = ByteArray(1000) { byteOf(-100f) }
        for (i in 0 until 20) bins[i] = byteOf(-140f) // roll-off
        assertEquals(-100f, noiseFloor(bins, 25, 35), 1f) // no room on the left
        assertEquals(-100f, noiseFloor(bins, 0, 1000), 1f) // no room on either side: the band
    }
}
