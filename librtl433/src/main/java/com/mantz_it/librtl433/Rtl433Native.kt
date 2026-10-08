package com.mantz_it.librtl433

import androidx.annotation.Keep

/**
 * <h1>RF Analyzer - rtl_433 native decoder</h1>
 *
 * Thin wrapper around the `rfax_bridge` C API (see src/main/cpp). It drives the
 * rtl_433 decoder in "push" mode: callers feed interleaved, normalised IQ
 * samples (the same buffer layout RF Analyzer already builds for the FFT) and
 * receive decoded events as JSON strings through [onDecoded].
 *
 * The native handle is not thread-safe. Feed samples from a single thread, and
 * only call [destroy] once that thread has stopped.
 *
 * Copyright (C) 2026 RF Analyzer contributors
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class Rtl433Native {

    /** Called on the feeding thread for every decoded event (a JSON object). */
    var onDecoded: ((json: String) -> Unit)? = null

    @Volatile
    private var handle: Long = 0L

    /** Loads the decoder and registers [onDecoded]. Returns false on failure. */
    fun create(): Boolean {
        if (handle != 0L) return true
        handle = nativeCreate(this)
        return handle != 0L
    }

    /**
     * Configures the decoder. [sampleRate] and [centerFrequency] describe the
     * IQ stream passed to [feed]; [decimation] is the integer factor applied
     * before decoding (use 1 to disable). Returns false on failure.
     */
    fun configure(sampleRate: Int, centerFrequency: Long, decimation: Int): Boolean {
        if (handle == 0L) return false
        return nativeConfigure(handle, sampleRate, centerFrequency, decimation.coerceAtLeast(1)) == 0
    }

    /** Updates the center frequency reported in decoded events. */
    fun setCenterFrequency(centerFrequency: Long) {
        if (handle != 0L) nativeSetCenterFrequency(handle, centerFrequency)
    }

    /**
     * Optionally filters to a channel before decoding. [offsetHz] is the channel
     * offset from the SDR center, [bandwidthHz] the channel width. Pass
     * bandwidth 0 to disable (full-band decoding).
     */
    fun setChannel(offsetHz: Int, bandwidthHz: Int) {
        if (handle != 0L) nativeSetChannel(handle, offsetHz, bandwidthHz)
    }

    /**
     * Adjusts decoder behaviour.
     * @param conversionMode unit conversion (0 = native, 1 = SI, 2 = US customary)
     * @param autoLevel adapt the detection threshold to the noise floor
     * @param reportMeta include rssi/snr/freq/mod in decoded events
     * @param minSnr minimum signal-to-noise ratio in dB (<= 0 keeps the current)
     */
    fun setOptions(conversionMode: Int, autoLevel: Boolean, reportMeta: Boolean, minSnr: Float) {
        if (handle != 0L)
            nativeSetOptions(handle, conversionMode, if (autoLevel) 1 else 0, if (reportMeta) 1 else 0, minSnr)
    }

    /**
     * Registers an extra "flex" decoder from an rtl_433 `-X` spec string, e.g.
     * `n=ook,m=OOK_PWM,s=467,l=927,r=2000,g=0,t=0,y=0`. Call it right after
     * [create] and before feeding. Returns false if the spec was rejected.
     */
    fun addFlex(spec: String): Boolean {
        if (handle == 0L) return false
        return nativeAddFlex(handle, spec) == 0
    }

    /** Feeds interleaved IQ samples: [i0, q0, i1, q1, ...] in roughly [-1, 1]. */
    fun feed(samples: FloatArray) {
        if (handle != 0L) nativeFeed(handle, samples)
    }

    /** Flushes buffered samples so a pending frame can be completed. */
    fun flush() {
        if (handle != 0L) nativeFlush(handle)
    }

    /** Releases the decoder. Safe to call multiple times. */
    fun destroy() {
        if (handle != 0L) {
            nativeDestroy(handle)
            handle = 0L
        }
    }

    /** rtl_433 version string. */
    fun version(): String = if (handle != 0L) nativeVersion() else ""

    @Keep
    private fun onDecodedJson(json: String) {
        onDecoded?.invoke(json)
    }

    private external fun nativeCreate(callback: Any): Long
    private external fun nativeConfigure(handle: Long, sampleRate: Int, centerFrequency: Long, decimation: Int): Int
    private external fun nativeSetCenterFrequency(handle: Long, centerFrequency: Long)
    private external fun nativeSetChannel(handle: Long, offsetHz: Int, bandwidthHz: Int)
    private external fun nativeSetOptions(handle: Long, conversionMode: Int, autoLevel: Int, reportMeta: Int, minSnr: Float)
    private external fun nativeAddFlex(handle: Long, spec: String): Int
    private external fun nativeFeed(handle: Long, samples: FloatArray)
    private external fun nativeFlush(handle: Long)
    private external fun nativeDestroy(handle: Long)
    private external fun nativeVersion(): String

    companion object {
        init {
            System.loadLibrary("rfax_rtl433")
        }
    }
}
