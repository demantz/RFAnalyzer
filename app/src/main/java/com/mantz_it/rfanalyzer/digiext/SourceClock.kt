package com.mantz_it.rfanalyzer.digiext

import com.mantz_it.rfanalyzer_ipc.SamplePacket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * <h1>RF Analyzer - Source Clock</h1>
 *
 * Module:      SourceClock.kt
 * Description: What time it is in the samples being demodulated - the wall clock for a live SDR,
 *              the recording's own time for a replayed file. The modes whose packets carry their
 *              samples' time (DigitalMode.stampedWithSampleTime: FT8, LoRa) are measured against
 *              it while received - ages, "this session", the Raw list's order - so a replayed
 *              recording reads like a live reception while its packets keep the time they were
 *              really received.
 *
 * Owned by CaptureSessionRepository, which resets it when a capture session starts; fed with every
 * sample packet handed to the digital extension.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class SourceClock {
    // Source time minus wall clock at the newest sample; 0 without samples (the wall clock).
    @Volatile private var offsetMs = 0L
    @Volatile private var newestMs = Long.MIN_VALUE
    @Volatile private var running = false

    private val _sessionStartMs = MutableStateFlow<Long?>(null)

    /** The source time of the session's earliest sample - for a looping file, the file's start.
     *  Null until the session's first samples arrived. */
    val sessionStartMs: StateFlow<Long?> = _sessionStartMs

    private val _sessions = MutableStateFlow(0)

    /** Counts the capture sessions started since the app started. */
    val sessions: StateFlow<Int> = _sessions

    fun startSession() {
        offsetMs = 0L
        newestMs = Long.MIN_VALUE
        running = true
        _sessionStartMs.value = null
        _sessions.value++
    }

    /** Back to the wall clock; the session's start stays for its stored data. */
    fun endSession() {
        offsetMs = 0L
        running = false
    }

    /** Called from the DSP thread for every packet the extension gets - cheap on purpose. */
    fun onSamples(samples: SamplePacket) {
        val startMs = samples.timestamp
        if (startMs <= 0L || samples.sampleRate <= 0) return
        val endMs = startMs + samples.size() * 1000L / samples.sampleRate
        offsetMs = endMs - System.currentTimeMillis()
        if (endMs > newestMs) newestMs = endMs
        val sessionStart = _sessionStartMs.value
        if (sessionStart == null || startMs < sessionStart) _sessionStartMs.value = startMs
    }

    /** Now, in the time of the samples. */
    fun nowMs(): Long = System.currentTimeMillis() + offsetMs

    /** The end of the session's window: its newest sample time (a packet is decoded from samples
     *  already fed, so it is never newer; the slack only covers rounding) - or [Long.MAX_VALUE]
     *  before its first samples, when nothing narrows the window yet. */
    fun sessionEndMs(): Long = newestMs.takeIf { it != Long.MIN_VALUE }?.plus(WINDOW_SLACK_MS) ?: Long.MAX_VALUE

    /** The running session's window while its samples are older than the wall clock - a replayed
     *  recording - else null: a live source's session holds the newest data anyway. */
    fun replayWindow(): LongRange? {
        if (!running) return null
        val start = _sessionStartMs.value ?: return null
        val newest = newestMs.takeIf { it != Long.MIN_VALUE } ?: return null
        if (System.currentTimeMillis() - newest < REPLAY_LAG_MS) return null
        return start..newest + WINDOW_SLACK_MS
    }

    private companion object {
        const val WINDOW_SLACK_MS = 1_000L
        /** Samples this far behind the wall clock are a recording, not a live source's latency. */
        const val REPLAY_LAG_MS = 60_000L
    }
}
