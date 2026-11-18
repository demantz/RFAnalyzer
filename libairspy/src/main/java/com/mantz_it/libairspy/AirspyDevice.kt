package com.mantz_it.libairspy

import android.util.Log
import androidx.annotation.Keep
import com.mantz_it.libusb.SampleBufferPool
import com.mantz_it.libusb.SampleStreamDevice

/**
 * <h1>RF Analyzer - Airspy Device</h1>
 *
 * Module:      AirspyDevice.kt
 * Description: A Kotlin interface to the native libairspy driver
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class AirspyDevice private constructor(private var nativeDevicePtr: Long) : SampleStreamDevice {

    companion object {
        private const val TAG = "AirspyDevice"
        const val BUFFER_SIZE = 262144 // bytes per transfer (airspy.c: buffer_size); an INT16_IQ sample is 4 bytes

        @JvmStatic
        private external fun nativeOpenFd(fd: Int): Long

        @JvmStatic
        external fun getLibraryVersionString(): String?

        fun open(fd: Int): Pair<AirspyDevice?, AirspyError?> {
            val result = nativeOpenFd(fd)
            val error = AirspyError.fromCode(result.toInt())
            return if (error != null && error != AirspyError.AIRSPY_SUCCESS) {
                Log.e(TAG, "open: Error opening Airspy device: $result ($error)")
                Pair(null, error)
            } else {
                val devicePtr = result // on success, the native device pointer
                Pair(AirspyDevice(devicePtr), null)
            }
        }

        // Load the native library
        init {
            System.loadLibrary("libairspy")
            Log.i(TAG, "libairspy loaded: ${getLibraryVersionString()}")
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
    private external fun nativeGetSamplerates(nativePtr: Long, sampleRates: List<Int>): Int
    private external fun nativeStartRX(nativePtr: Long): Int
    private external fun nativeStopRX(nativePtr: Long): Int

    override val sampleBuffers = SampleBufferPool(BUFFER_SIZE)

    init {
        if (nativeDevicePtr == 0L) {
            throw IllegalArgumentException("Native device pointer cannot be null (0) for AirspyDevice.")
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
            if (result == AirspyError.AIRSPY_SUCCESS.code) {
                Log.i(TAG, "close: Airspy device closed successfully.")
                return true
            } else {
                Log.e(TAG, "close: Error closing Airspy device: ${AirspyError.fromCode(result)}")
            }
        }
        return false
    }

    // Fallback for a device that was never closed
    protected fun finalize() {
        if (nativeDevicePtr != 0L) {
            Log.w(TAG, "finalize: AirspyDevice was not explicitly closed. Closing in finalize().")
            close()
        }
    }

    fun setSampleRate(sampleRate: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setSampleRate: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetSampleRate(nativeDevicePtr, sampleRate) == AirspyError.AIRSPY_SUCCESS.code
    }

    fun setFrequency(frequency: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setFrequency: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetFrequency(nativeDevicePtr, frequency) == AirspyError.AIRSPY_SUCCESS.code
    }

    fun setLnaGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setLnaGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetLnaGain(nativeDevicePtr, gain) == AirspyError.AIRSPY_SUCCESS.code
    }

    fun setMixerGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setMixerGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetMixerGain(nativeDevicePtr, gain) == AirspyError.AIRSPY_SUCCESS.code
    }

    fun setVgaGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setVgaGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetVgaGain(nativeDevicePtr, gain) == AirspyError.AIRSPY_SUCCESS.code
    }

    fun setLinearityGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setLinearityGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetLinearityGain(nativeDevicePtr, gain) == AirspyError.AIRSPY_SUCCESS.code
    }

    fun setSensitivityGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setSensitivityGain: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetSensitivityGain(nativeDevicePtr, gain) == AirspyError.AIRSPY_SUCCESS.code
    }

    fun setRfBias(bias: Boolean): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setRfBias: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetRfBias(nativeDevicePtr, bias) == AirspyError.AIRSPY_SUCCESS.code
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

    override fun startRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "startRX: Device already closed or not opened.")
            return false
        }
        return nativeStartRX(nativeDevicePtr) == AirspyError.AIRSPY_SUCCESS.code
    }

    override fun stopRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "stopRX: Device already closed or not opened.")
            return false
        }
        return nativeStopRX(nativeDevicePtr) == AirspyError.AIRSPY_SUCCESS.code
    }

    // Called from JNI (sample_handoff.h) on the driver's RX thread
    @Keep
    private fun getEmptyBuffer(): ByteArray? = sampleBuffers.takeEmpty()

    @Keep
    private fun onSamplesReady(buffer: ByteArray, validByteCount: Int, droppedSamples: Long) =
        sampleBuffers.putFilled(buffer, validByteCount, droppedSamples)
}

enum class AirspyError(val code: Int) {
    AIRSPY_SUCCESS(0),
    AIRSPY_TRUE(1),
    AIRSPY_ERROR_INVALID_PARAM(-2),
    AIRSPY_ERROR_NOT_FOUND(-5),
    AIRSPY_ERROR_BUSY(-6),
    AIRSPY_ERROR_NO_MEM(-11),
    AIRSPY_ERROR_UNSUPPORTED(-12),
    AIRSPY_ERROR_LIBUSB(-1000),
    AIRSPY_ERROR_THREAD(-1001),
    AIRSPY_ERROR_STREAMING_THREAD_ERR(-1002),
    AIRSPY_ERROR_STREAMING_STOPPED(-1003),
    AIRSPY_ERROR_OTHER(-9999);

    companion object {
        private val map = entries.associateBy(AirspyError::code)
        fun fromCode(code: Int): AirspyError? = map[code]
    }

    override fun toString(): String {
        return when (this) {
            AIRSPY_SUCCESS -> "Success"
            AIRSPY_TRUE -> "True"
            AIRSPY_ERROR_INVALID_PARAM -> "Invalid Parameter"
            AIRSPY_ERROR_NOT_FOUND -> "Device Not Found"
            AIRSPY_ERROR_BUSY -> "Device Busy"
            AIRSPY_ERROR_NO_MEM -> "Out of Memory"
            AIRSPY_ERROR_UNSUPPORTED -> "Unsupported"
            AIRSPY_ERROR_LIBUSB -> "libusb error"
            AIRSPY_ERROR_THREAD -> "thread error"
            AIRSPY_ERROR_STREAMING_THREAD_ERR -> "streaming thread error"
            AIRSPY_ERROR_STREAMING_STOPPED -> "streaming thread stopped"
            AIRSPY_ERROR_OTHER -> "other error"
        }
    }
}