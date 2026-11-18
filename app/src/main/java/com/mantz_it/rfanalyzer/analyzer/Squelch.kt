package com.mantz_it.rfanalyzer.analyzer

import kotlin.math.log10
import kotlin.math.pow

/**
 * <h1>RF Analyzer - Squelch</h1>
 *
 * Module:      Squelch.kt
 * Description: The squelch decision, made like a radio's: the threshold is a margin above the noise
 *              floor (so it means the same with every SDR, gain and FFT size), the squelch opens as
 *              soon as the channel is above it, and closes only after the channel stayed a few dB
 *              below it for [SquelchGate.HANG_MS] - no flapping around the threshold, and a burst's
 *              tail isn't cut.
 *
 *              The levels come from the FFT (FftProcessor), per frame: [channelLevel] (the mean
 *              power of the channel's bins, in the per-bin dB of the plot) and [noiseFloor] (a low
 *              percentile of the bins beside the channel). The Scheduler reads [SquelchGate.open]
 *              for every packet and keeps what arrives while the squelch is closed, so the start of
 *              a burst (from before the FFT noticed it) still reaches the demodulator and the
 *              recordings.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class SquelchGate {
    companion object {
        /** How long the channel must stay below the threshold before the squelch closes. */
        const val HANG_MS = 300L
        /** Closing needs the level this much below the opening threshold. */
        const val HYSTERESIS_DB = 3f
        /** The noise floor's smoothing: it follows changes within about a second at 30 frames/s. */
        private const val FLOOR_ALPHA = 0.05f
    }

    @Volatile var enabled: Boolean = false
        set(value) { field = value; if (!value) open = true }
    /** dB above the noise floor. */
    @Volatile var marginDb: Float = 10f

    /** Whether the channel passes - always while the squelch is disabled. */
    @Volatile var open: Boolean = true
        private set
    /** The smoothed noise floor (per-bin dB); NaN before the first frame. */
    @Volatile var floorDb: Float = Float.NaN
        private set
    /** The opening threshold now (per-bin dB) - where the FFT draws the squelch line. */
    val thresholdDb: Float get() = floorDb + marginDb

    private var belowSinceMs = -1L

    /** One FFT frame's levels at [nowMs]. Returns whether the squelch is open after it. */
    fun update(channelLevelDb: Float, noiseFloorDb: Float, nowMs: Long): Boolean {
        floorDb = if (floorDb.isNaN()) noiseFloorDb else floorDb + FLOOR_ALPHA * (noiseFloorDb - floorDb)
        if (!enabled) {
            open = true
            belowSinceMs = -1L
            return true
        }
        val threshold = thresholdDb
        when {
            channelLevelDb >= threshold -> { open = true; belowSinceMs = -1L }
            !open -> Unit
            channelLevelDb >= threshold - HYSTERESIS_DB -> belowSinceMs = -1L // in the hysteresis band: stays open
            belowSinceMs < 0 -> belowSinceMs = nowMs
            nowMs - belowSinceMs >= HANG_MS -> open = false
        }
        return open
    }
}

/** dB values of the FFT's byte scale (FftProcessor.MIN_DB + byte * INV_SCALE) as linear power. */
private val BYTE_TO_POWER = FloatArray(256) { 10f.pow((FftProcessor.MIN_DB + it * FftProcessor.INV_SCALE) / 10f) }

/**
 * The mean power of the bins [from] until [to] of [magBytes] (the FFT's byte scale), in per-bin dB:
 * a channel full of noise reads the noise level, a strong signal filling it its level - matching the
 * plot, unlike the mean of the dB values, which reads a signal in a few bins of a wide channel far
 * too low and noise ~2.5 dB low. NaN for an empty range.
 */
fun channelLevel(magBytes: ByteArray, from: Int, to: Int): Float {
    if (to <= from) return Float.NaN
    var sum = 0.0
    for (i in from until to) sum += BYTE_TO_POWER[magBytes[i].toInt() and 0xFF]
    return (10 * log10(sum / (to - from))).toFloat()
}

/**
 * The noise floor at the channel [from] until [to] of [magBytes] (the FFT's byte scale): the 20th
 * percentile of the bins beside it - [SIDE_CHANNEL_WIDTHS] channel widths on each side, at least
 * [MIN_SIDE_BINS] bins - from the side where it is higher. Signals rarely fill four fifths of the
 * neighbourhood, so a low percentile stays on the noise. The higher side, because the other one may
 * lie outside the receiver's passband: a band edge's roll-off, or a recording that holds only part
 * of its band, reads far below the noise the channel has (a whole-band percentile would take the
 * squelch line down there, where no margin closes it). The whole band's estimate if neither side has room.
 * Histograms - no sorting per frame.
 */
fun noiseFloor(magBytes: ByteArray, from: Int, to: Int): Float {
    val width = (to - from).coerceAtLeast(1)
    val side = maxOf(SIDE_CHANNEL_WIDTHS * width, MIN_SIDE_BINS)
    // a strong signal's sidebands and the window's leakage lift the bins right next to the channel
    val guard = width / 4 + 2
    val left = lowPercentile(magBytes, from - guard - side, from - guard)
    val right = lowPercentile(magBytes, to + guard, to + guard + side)
    return when {
        !left.isNaN() && !right.isNaN() -> maxOf(left, right)
        !left.isNaN() -> left
        !right.isNaN() -> right
        else -> lowPercentile(magBytes, magBytes.size / 10, magBytes.size - magBytes.size / 10) // the band minus its edges
    }
}

private const val SIDE_CHANNEL_WIDTHS = 4
private const val MIN_SIDE_BINS = 64
/** A side narrower than this (cut off by the band's edge) says too little to count. */
private const val MIN_USABLE_BINS = 16

/** The 20th percentile of [magBytes] from [from] until [to] (clipped to the array), in dB; NaN
 *  for fewer than [MIN_USABLE_BINS] bins. */
private fun lowPercentile(magBytes: ByteArray, from: Int, to: Int): Float {
    val start = from.coerceIn(0, magBytes.size)
    val end = to.coerceIn(0, magBytes.size)
    if (end - start < MIN_USABLE_BINS) return Float.NaN
    val histogram = IntArray(256)
    for (i in start until end) histogram[magBytes[i].toInt() and 0xFF]++
    val target = (end - start) / 5
    var count = 0
    for (value in 0 until 256) {
        count += histogram[value]
        if (count > target) return FftProcessor.MIN_DB + value * FftProcessor.INV_SCALE
    }
    return FftProcessor.MAX_DB
}
