package com.mantz_it.librtlsdr

import android.util.Log
import androidx.annotation.Keep
import com.mantz_it.libusb.SampleBufferPool
import com.mantz_it.libusb.SampleStreamDevice

/**
 * <h1>RF Analyzer - RtlSdr Device</h1>
 *
 * Module:      RtlSdrDevice.kt
 * Description: A Kotlin interface to the native librtlsdr driver
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class RtlSdrDevice private constructor(private var nativeDevicePtr: Long) : SampleStreamDevice {

    companion object {
        private const val TAG = "RtlSdrDevice"

        // This matches librtlsdr's own DEFAULT_BUF_LENGTH (16 * 32 * 512 = 262144)
        // one RX transfer contains interleaved unsigned int8 I/Q samples (2 bytes per sample).
        const val BUFFER_SIZE = 262144 // bytes

        @JvmStatic
        private external fun nativeOpenFd(fd: Int): Long

        fun open(fd: Int): Pair<RtlSdrDevice?, Int?> {
            val result = nativeOpenFd(fd)
            // A negative error code on failure, else the device pointer - which can be negative
            // as a signed long, so only -10000..0 means an error
            if (result <= 0 && result >= -10000) {
                Log.e(TAG, "open: Error opening RTL-SDR device: $result")
                return Pair(null, result.toInt())
            } else {
                return Pair(RtlSdrDevice(result), null)
            }
        }

        init {
            System.loadLibrary("librtlsdr")
        }
    }

    // native functions
    private external fun nativeClose(nativePtr: Long): Int
    private external fun nativeSetCenterFreq(nativePtr: Long, freqHz: Long): Int
    private external fun nativeSetSampleRate(nativePtr: Long, sampleRate: Int): Int
    private external fun nativeSetFreqCorrection(nativePtr: Long, ppm: Int): Int
    private external fun nativeSetTunerGainMode(nativePtr: Long, manual: Boolean): Int
    private external fun nativeSetTunerGain(nativePtr: Long, gainTenthsDb: Int): Int
    private external fun nativeSetAgcMode(nativePtr: Long, on: Boolean): Int
    private external fun nativeSetDirectSampling(nativePtr: Long, mode: Int): Int
    private external fun nativeSetBiasTee(nativePtr: Long, on: Boolean): Int
    private external fun nativeGetTunerType(nativePtr: Long): Int
    private external fun nativeIsBlogV4(nativePtr: Long): Boolean
    private external fun nativeGetTunerGains(nativePtr: Long, gains: MutableList<Int>): Int
    private external fun nativeStartRX(nativePtr: Long): Int
    private external fun nativeStopRX(nativePtr: Long): Int

    override val sampleBuffers = SampleBufferPool(BUFFER_SIZE)

    // librtlsdr has no streaming state of its own: set by startRX(), cleared by stopRX()/close()
    // and when the RX thread reports a lost device (onStreamingError)
    @Volatile
    private var streaming = false

    init {
        if (nativeDevicePtr == 0L) {
            throw IllegalArgumentException("Native device pointer cannot be null (0) for RtlSdrDevice.")
        }
    }

    override fun close(): Boolean {
        streaming = false
        if (nativeDevicePtr != 0L) {
            val result = nativeClose(nativeDevicePtr)
            nativeDevicePtr = 0L
            if (result == 0) {
                Log.i(TAG, "close: RTL-SDR device closed successfully.")
                return true
            } else {
                Log.e(TAG, "close: Error closing RTL-SDR device: $result")
            }
        }
        return false
    }

    protected fun finalize() {
        if (nativeDevicePtr != 0L) {
            Log.w(TAG, "finalize: RtlSdrDevice was not explicitly closed. Closing in finalize().")
            close()
        }
    }

    fun setFrequency(freqHz: Long): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setFrequency: nativeDevicePtr is null.")
            return false
        }
        return nativeSetCenterFreq(nativeDevicePtr, freqHz) == 0
    }

    fun setSampleRate(sampleRate: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setSampleRate: nativeDevicePtr is null.")
            return false
        }
        return nativeSetSampleRate(nativeDevicePtr, sampleRate) == 0
    }

    fun setFreqCorrection(ppm: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setFreqCorrection: nativeDevicePtr is null.")
            return false
        }
        return nativeSetFreqCorrection(nativeDevicePtr, ppm) == 0
    }

    fun setTunerGainMode(manual: Boolean): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setTunerGainMode: nativeDevicePtr is null.")
            return false
        }
        return nativeSetTunerGainMode(nativeDevicePtr, manual) == 0
    }

    fun setTunerGain(gainTenthsDb: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setTunerGain: nativeDevicePtr is null.")
            return false
        }
        return nativeSetTunerGain(nativeDevicePtr, gainTenthsDb) == 0
    }

    fun setAgcMode(on: Boolean): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setAgcMode: nativeDevicePtr is null.")
            return false
        }
        return nativeSetAgcMode(nativeDevicePtr, on) == 0
    }

    fun setDirectSampling(mode: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setDirectSampling: nativeDevicePtr is null.")
            return false
        }
        return nativeSetDirectSampling(nativeDevicePtr, mode) == 0
    }

    fun setBiasTee(on: Boolean): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setBiasTee: nativeDevicePtr is null.")
            return false
        }
        return nativeSetBiasTee(nativeDevicePtr, on) == 0
    }

    fun getTunerType(): Int {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "getTunerType: nativeDevicePtr is null.")
            return -1
        }
        return nativeGetTunerType(nativeDevicePtr)
    }

    // Whether the opened device is an RTL-SDR Blog V4 (librtlsdr checks its USB manufacturer
    // and product strings).
    fun isBlogV4(): Boolean {
        if (nativeDevicePtr == 0L) {
            return false
        }
        return nativeIsBlogV4(nativeDevicePtr)
    }

    fun getTunerGains(): List<Int> {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "getTunerGains: nativeDevicePtr is null.")
            return emptyList()
        }
        val gains = mutableListOf<Int>()
        nativeGetTunerGains(nativeDevicePtr, gains)
        return gains // in tenths of a dB, per librtlsdr's own convention
    }

    override fun startRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "startRX: Device already closed or not opened.")
            return false
        }
        streaming = nativeStartRX(nativeDevicePtr) == 0
        return streaming
    }

    override fun stopRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "stopRX: Device already closed or not opened.")
            return false
        }
        streaming = false
        return nativeStopRX(nativeDevicePtr) == 0
    }

    override fun isStreaming(): Boolean = streaming

    // Called from JNI (sample_handoff.h) on the RX thread
    @Keep
    private fun getEmptyBuffer(): ByteArray? = sampleBuffers.takeEmpty()

    @Keep
    private fun onSamplesReady(buffer: ByteArray, validByteCount: Int, droppedSamples: Long) =
        sampleBuffers.putFilled(buffer, validByteCount, droppedSamples)

    // Called from JNI (RX thread) when rtlsdr_read_async() returned due to a lost device, not a
    // normal stopRX()/close()
    @Keep
    private fun onStreamingError() {
        Log.e(TAG, "onStreamingError: RTL-SDR device lost (USB transfer errors).")
        streaming = false
    }
}
