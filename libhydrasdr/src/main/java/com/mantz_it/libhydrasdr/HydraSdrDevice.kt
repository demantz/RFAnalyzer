package com.mantz_it.libhydrasdr

import android.util.Log
import androidx.annotation.Keep
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * <h1>RF Analyzer - HydraSDR Device</h1>
 *
 * Module:      HydraSdrDevice.kt
 * Description: A Kotlin interface to the native libhydrasdr driver
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */

class HydraSdrDevice private constructor(private var nativeDevicePtr: Long) {

    companion object {
        private const val TAG = "HydraSdrDevice"
        const val BUFFER_SIZE = 262144 // from hydrasdr.c:949 (1024*256)  - value is in bytes. one IQ sample is 4 bytes!

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
                val devicePtr = result // Assuming result is the device pointer on success
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
    private external fun nativeSetLnaGain(nativePtr: Long, gain: Int): Int
    private external fun nativeSetMixerGain(nativePtr: Long, gain: Int): Int
    private external fun nativeSetVgaGain(nativePtr: Long, gain: Int): Int
    private external fun nativeSetLinearityGain(nativePtr: Long, gain: Int): Int
    private external fun nativeSetSensitivityGain(nativePtr: Long, gain: Int): Int
    private external fun nativeSetSampleRate(nativePtr: Long, sampleRate: Int): Int
    private external fun nativeSetFrequency(nativePtr: Long, frequency: Int): Int
    private external fun nativeSetRfBias(nativePtr: Long, bias: Boolean): Int
    private external fun nativeSetRfPort(nativePtr: Long, port: Int): Int
    private external fun nativeGetSamplerates(nativePtr: Long, sampleRates: List<Int>): Int
    private external fun nativeStartRX(nativePtr: Long): Int
    private external fun nativeStopRX(nativePtr: Long): Int

    private val bufferPoolSize: Int = 70 // ~ 0.5sec of samples @10MSps; adjust if necessary!
    private val availableBuffers = ArrayBlockingQueue<ByteArray>(bufferPoolSize)
    private val filledBuffers = ArrayBlockingQueue<ByteArray>(bufferPoolSize)

    init {
        if (nativeDevicePtr == 0L) {
            throw IllegalArgumentException("Native device pointer cannot be null (0) for HydraSdrDevice.")
        }
        // Create Buffers:
        repeat(bufferPoolSize) {
            availableBuffers.put(ByteArray(BUFFER_SIZE))
        }
    }

    fun flushBufferQueue() {
        var buffer = filledBuffers.poll()
        while (buffer != null) {
            availableBuffers.offer(buffer)
            buffer = filledBuffers.poll()
        }
    }

    fun getVersionString(): String? {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "getVersionString: Device already closed or not opened.")
            return null
        }
        return nativeVersionStringRead(nativeDevicePtr)
    }

    fun isStreaming(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "isStreaming: Device already closed or not opened.")
            return false
        }
        return nativeIsStreaming(nativeDevicePtr)
    }

    // Call this to release the native resources
    fun close(): Boolean {
        if (nativeDevicePtr != 0L) {
            val result = nativeClose(nativeDevicePtr)
            nativeDevicePtr = 0L // Mark as closed, prevent further use
            if (result == HydraSdrError.HYDRASDR_SUCCESS.code) { // Assuming you map errors
                Log.i(TAG, "close: HydraSdr device closed successfully.")
                return true
            } else {
                Log.e(TAG, "close: Error closing HydraSDR device: ${HydraSdrError.fromCode(result)}")
            }
        }
        return false
    }

    // Ensure resources are released if the object is GC'd without explicit close
    // This is a fallback, explicit close() is always better.
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

    fun setFrequency(frequency: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setFrequency: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetFrequency(nativeDevicePtr, frequency) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    fun setLnaGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setLnaGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetLnaGain(nativeDevicePtr, gain) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    fun setMixerGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setMixerGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetMixerGain(nativeDevicePtr, gain) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    fun setVgaGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setVgaGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetVgaGain(nativeDevicePtr, gain) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    fun setLinearityGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setLinearityGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetLinearityGain(nativeDevicePtr, gain) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    fun setSensitivityGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setSensitivityGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetSensitivityGain(nativeDevicePtr, gain) == HydraSdrError.HYDRASDR_SUCCESS.code
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
        return sampleRates.filter { it != 0 } // Filter out any trailing zeros if the native side pads
    }

    fun startRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "startRX: Device already closed or not opened.")
            return false
        }
        return nativeStartRX(nativeDevicePtr) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    fun stopRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "stopRX: Device already closed or not opened.")
            return false
        }
        return nativeStopRX(nativeDevicePtr) == HydraSdrError.HYDRASDR_SUCCESS.code
    }

    // Get a buffer with samples from the HydraSdr
    fun getSampleBuffer(): ByteArray? {
        return filledBuffers.poll(100, TimeUnit.MILLISECONDS) // returns null if no buffer is available after 100ms
    }

    // Return a buffer to the pool
    fun returnSampleBuffer(buffer: ByteArray) {
        availableBuffers.put(buffer) // return buffer to pool
    }

    // Called from JNI when native side has samples ready
    @Keep
    private fun onSamplesReady(buffer: ByteArray) {
        filledBuffers.put(buffer) // blocks if user isn't consuming fast enough
    }

    // Called from JNI to fetch an empty buffer for filling
    @Keep
    private fun getEmptyBuffer(): ByteArray {
        return availableBuffers.take() // blocks until a buffer is free
    }
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
