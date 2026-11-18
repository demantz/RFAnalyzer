package com.mantz_it.rfanalyzer.source

import android.util.Log
import com.mantz_it.nativedsp.NativeIQMixer

/**
 * <h1>RF Analyzer - signed 8-bit IQ Converter</h1>
 *
 * Module:      Signed8BitIQConverter.kt
 * Description: Signed 8-bit IQ samples, as the HackRF delivers them (ByteIQConverter).
 *
 * Performance: mixPacketIntoSamplePacket()'s per-sample loop runs natively (NativeIQMixer,
 * nativedsp/src/main/cpp/iq_byte_mixer.c) - at HackRF rates it is the Scheduler thread's largest
 * cost. The Kotlin loop of ByteIQConverter is the fallback when the native library can't be
 * loaded (a plain JVM unit test).
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class Signed8BitIQConverter(
    // Tests only: the Kotlin loop even when the native mixer is available, to compare the two.
    forceKotlinFallback: Boolean = false,
) : ByteIQConverter(unsigned = false) {

    private val nativeMixer: NativeIQMixer? = if (forceKotlinFallback) null else try {
        NativeIQMixer()
    } catch (e: Throwable) {
        Log.w(LOGTAG, "Native IQMixer unavailable, falling back to the Kotlin implementation: ${e.message}")
        null
    }

    /** Whether the native mixer runs (not the Kotlin fallback). For tests. */
    val isUsingNative: Boolean get() = nativeMixer != null

    override val hasNativeMixer: Boolean get() = nativeMixer != null

    override fun setNativeMixerTable(realTableFlat: FloatArray, imagTableFlat: FloatArray, tableLength: Int) {
        nativeMixer?.setMixerTable(realTableFlat, imagTableFlat, tableLength)
    }

    override fun nativeMix(packet: ByteArray, length: Int, re: FloatArray, im: FloatArray, offset: Int, capacity: Int): Int =
        nativeMixer!!.mix(packet, 0, length, re, im, offset, capacity).second

    /** Frees native resources. Safe to call even if the Kotlin fallback engine is in use. */
    override fun close() {
        nativeMixer?.close()
    }

    private companion object {
        const val LOGTAG = "Signed8BitIQConverter"
    }
}
