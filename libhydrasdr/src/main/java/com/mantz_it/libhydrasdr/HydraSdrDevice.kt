package com.mantz_it.libhydrasdr

import android.util.Log
import androidx.annotation.Keep
import com.mantz_it.libusb.SampleBufferPool
import com.mantz_it.libusb.SampleStreamDevice

/**
 * <h1>RF Analyzer - HydraSDR Device</h1>
 *
 * Module:      HydraSdrDevice.kt
 * Description: A Kotlin interface to the native libhydrasdr driver
 *
 * @author Dennis Mantz (with contributions by Benjamin Vernoux <bvernoux@hydrasdr.com>)
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class HydraSdrDevice private constructor(private var nativeDevicePtr: Long) : SampleStreamDevice {

    companion object {
        private const val TAG = "HydraSdrDevice"
        const val BUFFER_SIZE = 262144 // bytes per transfer (DEFAULT_BUFFER_SIZE, hydrasdr_shared.h); an INT16_IQ sample is 4 bytes

        @JvmStatic
        private external fun nativeOpenFd(fd: Int): Long

        @JvmStatic
        external fun getLibraryVersionString(): String?

        fun open(fd: Int): Pair<HydraSdrDevice?, HydraSdrError?> {
            val result = nativeOpenFd(fd)
            val error = HydraSdrError.fromCode(result.toInt())
            return if (error != null && error != HydraSdrError.HYDRASDR_SUCCESS) {
                Log.e(TAG, "open: Error opening HydraSDR device: $result ($error)")
                Pair(null, error)
            } else {
                val devicePtr = result // on success, the native device pointer
                Pair(HydraSdrDevice(devicePtr), null)
            }
        }

        // Load the native library
        init {
            System.loadLibrary("libhydrasdr")
            Log.i(TAG, "libhydrasdr loaded: ${getLibraryVersionString()}")
        }
    }

    // native functions:
    private external fun nativeVersionStringRead(nativePtr: Long): String?
    private external fun nativeIsStreaming(nativePtr: Long): Boolean
    private external fun nativeClose(nativePtr: Long): Int
    private external fun nativeSetGain(nativePtr: Long, gainType: Int, value: Int): Int
    private external fun nativeSetSampleRate(nativePtr: Long, sampleRate: Int): Int
    private external fun nativeSetFrequency(nativePtr: Long, frequency: Long): Int
    private external fun nativeSetRfBias(nativePtr: Long, bias: Boolean): Int
    private external fun nativeSetRfPort(nativePtr: Long, port: Int): Int
    private external fun nativeGetSamplerates(nativePtr: Long, sampleRates: List<Int>): Int
    private external fun nativeGetDeviceInfo(nativePtr: Long): String?
    private external fun nativeStartRX(nativePtr: Long): Int
    private external fun nativeStopRX(nativePtr: Long): Int

    override val sampleBuffers = SampleBufferPool(BUFFER_SIZE)

    init {
        if (nativeDevicePtr == 0L) {
            throw IllegalArgumentException("Native device pointer cannot be null (0) for HydraSdrDevice.")
        }
    }

    fun getVersionString(): String? {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "getVersionString: Device already closed or not opened.")
            return null
        }
        return nativeVersionStringRead(nativeDevicePtr)
    }

    override fun isStreaming(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "isStreaming: Device already closed or not opened.")
            return false
        }
        return nativeIsStreaming(nativeDevicePtr)
    }

    override fun close(): Boolean {
        if (nativeDevicePtr != 0L) {
            val result = nativeClose(nativeDevicePtr)
            nativeDevicePtr = 0L // Mark as closed, prevent further use
            if (result == HydraSdrError.HYDRASDR_SUCCESS.code) {
                Log.i(TAG, "close: HydraSdr device closed successfully.")
                return true
            } else {
                Log.e(TAG, "close: Error closing HydraSDR device: ${HydraSdrError.fromCode(result)}")
            }
        }
        return false
    }

    // Fallback for a device that was never closed
    protected fun finalize() {
        if (nativeDevicePtr != 0L) {
            Log.w(TAG, "finalize: HydraSdrDevice was not explicitly closed. Closing in finalize().")
            close()
        }
    }

    fun setSampleRate(sampleRate: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setSampleRate: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetSampleRate(nativeDevicePtr, sampleRate) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    fun setFrequency(frequency: Long): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setFrequency: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetFrequency(nativeDevicePtr, frequency) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    // One setter for every gain type (libhydrasdr's hardware-agnostic API, v1.1)
    fun setGain(type: HydraSdrGainType, value: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetGain(nativeDevicePtr, type.nativeValue, value) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    fun setRfBias(bias: Boolean): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setRfBias: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetRfBias(nativeDevicePtr, bias) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    fun setRfPort(port: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setRfPort: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetRfPort(nativeDevicePtr, port) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    fun getSupportedSampleRates(): List<Int>? {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "getSupportedSampleRates: Device already closed or not opened.")
            return null
        }
        val sampleRates = mutableListOf<Int>()
        nativeGetSamplerates(nativeDevicePtr, sampleRates)
        return sampleRates.filter { it != 0 }
    }

    // The device's capabilities (gain ranges, RF ports, frequency range, board identity), from
    // the v1.1 hardware-agnostic API. Null (logged) if the device doesn't support the query or the
    // answer can't be parsed: callers then use HydraSdrDeviceInfo's DEFAULT_MIN/MAX_FREQUENCY.
    fun getDeviceInfo(): HydraSdrDeviceInfo? {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "getDeviceInfo: Device already closed or not opened.")
            return null
        }
        val json = nativeGetDeviceInfo(nativeDevicePtr)
        if (json == null) {
            Log.w(TAG, "getDeviceInfo: native query failed or returned null.")
            return null
        }
        return HydraSdrDeviceInfo.parse(json)
    }

    override fun startRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "startRX: Device already closed or not opened.")
            return false
        }
        return nativeStartRX(nativeDevicePtr) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    override fun stopRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "stopRX: Device already closed or not opened.")
            return false
        }
        return nativeStopRX(nativeDevicePtr) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    // Called from JNI (sample_handoff.h) on the driver's RX thread
    @Keep
    private fun getEmptyBuffer(): ByteArray? = sampleBuffers.takeEmpty()

    @Keep
    private fun onSamplesReady(buffer: ByteArray, validByteCount: Int, droppedSamples: Long) =
        sampleBuffers.putFilled(buffer, validByteCount, droppedSamples)
}

enum class HydraSdrError(val code: Int) {
    HYDRASDR_SUCCESS(0),
    HYDRASDR_TRUE(1),
    HYDRASDR_ERROR_INVALID_PARAM(-2),
    HYDRASDR_ERROR_NOT_FOUND(-5),
    HYDRASDR_ERROR_BUSY(-6),
    HYDRASDR_ERROR_NO_MEM(-11),
    HYDRASDR_ERROR_UNSUPPORTED(-12),
    HYDRASDR_ERROR_LIBUSB(-1000),
    HYDRASDR_ERROR_THREAD(-1001),
    HYDRASDR_ERROR_STREAMING_THREAD_ERR(-1002),
    HYDRASDR_ERROR_STREAMING_STOPPED(-1003),
    HYDRASDR_ERROR_OTHER(-9999);

    companion object {
        private val map = entries.associateBy(HydraSdrError::code)
        fun fromCode(code: Int): HydraSdrError? = map[code]
    }

    override fun toString(): String {
        return when (this) {
            HYDRASDR_SUCCESS -> "Success"
            HYDRASDR_TRUE -> "True"
            HYDRASDR_ERROR_INVALID_PARAM -> "Invalid Parameter"
            HYDRASDR_ERROR_NOT_FOUND -> "Device Not Found"
            HYDRASDR_ERROR_BUSY -> "Device Busy"
            HYDRASDR_ERROR_NO_MEM -> "Out of Memory"
            HYDRASDR_ERROR_UNSUPPORTED -> "Unsupported"
            HYDRASDR_ERROR_LIBUSB -> "libusb error"
            HYDRASDR_ERROR_THREAD -> "thread error"
            HYDRASDR_ERROR_STREAMING_THREAD_ERR -> "streaming thread error"
            HYDRASDR_ERROR_STREAMING_STOPPED -> "streaming thread stopped"
            HYDRASDR_ERROR_OTHER -> "other error"
        }
    }
}
