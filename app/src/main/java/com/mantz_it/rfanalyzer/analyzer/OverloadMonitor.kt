package com.mantz_it.rfanalyzer.analyzer

import com.mantz_it.rfanalyzer_ipc.digiext.PipelineHealth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * <h1>RF Analyzer - Overload Monitor</h1>
 *
 * Module:      OverloadMonitor.kt
 * Description: Detects when the device can't keep up with the signal, for every demodulation mode.
 *
 * Every processing stage reports how long it was busy for how much signal ([reportBusy]) and every
 * place that throws samples away reports how much signal it dropped ([reportLoss]); the digital
 * extension adds its own DSP-thread load and missed deadlines ([reportExtensionHealth]). Once per
 * second, while the analyzer runs, [tick] turns that window into an [OverloadState]:
 *  - OVERLOAD: signal was dropped, a deadline was missed, or a stage needed >= 100% of real time.
 *    Analog modes stutter; digital modes silently stop decoding.
 *  - HIGH_LOAD: some stage needed >= 70% of real time (little headroom left).
 *  - NORMAL otherwise.
 * The UI shows a warning chip while [OverloadState.chipVisible] (OVERLOAD, held for a few seconds
 * so it doesn't flicker) and the full state in the debug overlay.
 *
 * Any new place in the DSP chain that drops samples must call [reportLoss].
 * Report methods are called from many threads (Scheduler, Resampler, Demodulator, USB callbacks,
 * Binder), so the window is guarded by a lock; they are cheap enough to call once per packet.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object OverloadMonitor {
    const val HIGH_LOAD_THRESHOLD = 0.7f
    const val OVERLOAD_THRESHOLD = 1.0f
    const val CHIP_HOLD_MS = 5_000L
    // Starting a source/demodulator legitimately stalls for a moment (buffer allocation, filter
    // design, JIT) - don't report that as an overload.
    const val START_GRACE_MS = 3_000L
    const val EXTENSION_STAGE = "Digital extension"

    enum class Level { NORMAL, HIGH_LOAD, OVERLOAD }

    data class OverloadState(
        val level: Level = Level.NORMAL,
        /** Highest busy/signal ratio of any stage this window, and which stage it was. */
        val maxLoad: Float = 0f,
        val maxLoadStage: String? = null,
        /** Share of the window's signal time that was dropped (0..1+), and the stage that dropped most. */
        val lostFraction: Float = 0f,
        val lossStage: String? = null,
        val missedDeadlines: Int = 0,
        val missedDeadlineSource: String? = null,
        val chipVisible: Boolean = false,
    )

    /** One finished window, as fed to [evaluate]. */
    internal data class Window(
        val windowNs: Long,
        val loads: Map<String, Float>,
        val lostNsByStage: Map<String, Long>,
        val missedDeadlines: Int,
        val missedDeadlineSource: String?,
    )

    private class StageLoad(var busyNs: Long = 0L, var signalNs: Long = 0L)

    private val lock = Any()
    private val stageLoads = HashMap<String, StageLoad>()
    private val lostNsByStage = HashMap<String, Long>()
    private var extensionLoad: Float? = null
    private var missedDeadlines = 0
    private var missedDeadlineSource: String? = null
    private var windowStartNs = System.nanoTime()
    @Volatile
    private var startedAtMs = 0L
    private var lastOverloadMs: Long? = null

    private val _state = MutableStateFlow(OverloadState())
    val state: StateFlow<OverloadState> = _state

    /** A stage spent [busyNs] processing [signalNs] worth of signal. */
    fun reportBusy(stage: String, busyNs: Long, signalNs: Long) {
        if (busyNs < 0 || signalNs <= 0) return
        synchronized(lock) {
            val s = stageLoads.getOrPut(stage) { StageLoad() }
            s.busyNs += busyNs
            s.signalNs += signalNs
        }
    }

    /** A stage dropped [lostSignalNs] worth of signal (samples / sampleRate). */
    fun reportLoss(stage: String, lostSignalNs: Long) {
        if (lostSignalNs <= 0) return
        synchronized(lock) { lostNsByStage[stage] = (lostNsByStage[stage] ?: 0L) + lostSignalNs }
    }

    /** Work that runs behind the DSP chain fell behind and dropped what it could not keep up with. */
    fun reportMissedDeadline(source: String) {
        synchronized(lock) {
            missedDeadlines++
            missedDeadlineSource = source
        }
    }

    /** Latest PipelineHealth from the digital extension (about one per second). */
    fun reportExtensionHealth(health: PipelineHealth) {
        synchronized(lock) {
            extensionLoad = maxOf(extensionLoad ?: 0f, health.dspLoad)
            if (health.missedDeadlines > 0) {
                missedDeadlines += health.missedDeadlines
                missedDeadlineSource = health.missedDeadlineSource ?: EXTENSION_STAGE
            }
        }
    }

    /** Closes the current window and publishes its state. Called once per second while the analyzer runs. */
    fun tick(nowMs: Long = System.currentTimeMillis()) {
        val window = synchronized(lock) {
            val nowNs = System.nanoTime()
            val loads = HashMap<String, Float>()
            stageLoads.forEach { (stage, s) -> if (s.signalNs > 0) loads[stage] = s.busyNs.toFloat() / s.signalNs }
            extensionLoad?.let { loads[EXTENSION_STAGE] = it }
            val w = Window(nowNs - windowStartNs, loads, HashMap(lostNsByStage), missedDeadlines, missedDeadlineSource)
            stageLoads.clear()
            lostNsByStage.clear()
            extensionLoad = null
            missedDeadlines = 0
            missedDeadlineSource = null
            windowStartNs = nowNs
            w
        }
        val inGrace = nowMs - startedAtMs < START_GRACE_MS
        val (newState, newLastOverloadMs) = evaluate(window, lastOverloadMs, nowMs, inGrace)
        lastOverloadMs = newLastOverloadMs
        _state.value = newState
    }

    /** Forget everything; called when the analyzer starts and stops. */
    fun reset(nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            stageLoads.clear()
            lostNsByStage.clear()
            extensionLoad = null
            missedDeadlines = 0
            missedDeadlineSource = null
            windowStartNs = System.nanoTime()
        }
        startedAtMs = nowMs
        lastOverloadMs = null
        _state.value = OverloadState()
    }

    /** A reconfiguration while running - the mode, its parameters, the sample rate - stalls and
     *  flushes for a moment like a start does (the extension rebuilds its demodulator, its input
     *  ring is overwritten): no overload for [START_GRACE_MS] from now. Unlike [reset] it keeps
     *  what is shown, so a chip from a real overload before stays up for its hold time. Real
     *  overloads within those seconds go unreported too. */
    fun restartGrace(nowMs: Long = System.currentTimeMillis()) {
        startedAtMs = nowMs
    }

    /** Pure state logic: one window plus the time of the last overload -> state and updated time. */
    internal fun evaluate(window: Window, lastOverloadMs: Long?, nowMs: Long, inGrace: Boolean): Pair<OverloadState, Long?> {
        val maxEntry = window.loads.maxByOrNull { it.value }
        val maxLoad = maxEntry?.value ?: 0f
        val totalLostNs = window.lostNsByStage.values.sum()
        val lossStage = window.lostNsByStage.maxByOrNull { it.value }?.key
        val lostFraction = if (window.windowNs > 0) totalLostNs.toFloat() / window.windowNs else 0f

        val overloaded = !inGrace && (totalLostNs > 0 || window.missedDeadlines > 0 || maxLoad >= OVERLOAD_THRESHOLD)
        val level = when {
            overloaded -> Level.OVERLOAD
            maxLoad >= HIGH_LOAD_THRESHOLD -> Level.HIGH_LOAD
            else -> Level.NORMAL
        }
        val lastOverload = if (overloaded) nowMs else lastOverloadMs
        val chipVisible = lastOverload != null && nowMs - lastOverload < CHIP_HOLD_MS
        return OverloadState(
            level = level,
            maxLoad = maxLoad,
            maxLoadStage = maxEntry?.key,
            lostFraction = lostFraction,
            lossStage = lossStage,
            missedDeadlines = window.missedDeadlines,
            missedDeadlineSource = window.missedDeadlineSource,
            chipVisible = chipVisible,
        ) to lastOverload
    }
}
