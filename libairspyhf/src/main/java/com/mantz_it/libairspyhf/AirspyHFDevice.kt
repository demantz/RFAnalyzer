package com.mantz_it.libairspyhf

import android.util.Log
import androidx.annotation.Keep
import com.mantz_it.libusb.SampleBufferPool
import com.mantz_it.libusb.SampleStreamDevice

/**
 * <h1>RF Analyzer - AirspyHF Device</h1>
 *
 * Module:      AirspyHFDevice.kt
 * Description: A Kotlin interface to the native libairspyhf driver
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class AirspyHFDevice private constructor(private var nativeDevicePtr: Long) : SampleStreamDevice {

    companion object {
        private const val TAG = "AirspyHFDevice"

        private const val SAMPLES_TO_TRANSFER = 1024 * 4 // airspyhf.c's SAMPLES_TO_TRANSFER
        const val BUFFER_SIZE = SAMPLES_TO_TRANSFER * 4 * 2  // one sample is stored as complex float (2x4 byte)

        @JvmStatic
        private external fun nativeOpenFd(fd: Int): Long

        @JvmStatic
        external fun getLibraryVersionString(): String?

        fun open(fd: Int): Pair<AirspyHFDevice?, AirspyHFError?> {
            val result = nativeOpenFd(fd)
            val error = AirspyHFError.fromCode(result.toInt())
            return if (error != null && error != AirspyHFError.AIRSPYHF_SUCCESS) {
                Log.e(TAG, "open: Error opening AirspyHF device: $result ($error)")
                Pair(null, error)
            } else {
                val devicePtr = result // on success, the native device pointer
                Pair(AirspyHFDevice(devicePtr), null)
            }
        }

        // Load the native library
        init {
            System.loadLibrary("libairspyhf")
            Log.i(TAG, "libairspyhf loaded: ${getLibraryVersionString()}")
        }
    }

    // native functions:
    private external fun nativeVersionStringRead(nativePtr: Long): String?
    private external fun nativeIsStreaming(nativePtr: Long): Boolean
    private external fun nativeClose(nativePtr: Long): Int
    private external fun nativeSetSampleRate(nativePtr: Long, sampleRate: Int): Int
    private external fun nativeSetFrequency(nativePtr: Long, frequency: Int): Int
    private external fun nativeGetSamplerates(nativePtr: Long, sampleRates: List<Int>): Int
    private external fun nativeSetHfAgc(nativePtr: Long, enable: Boolean): Int
    private external fun nativeSetHfAgcThreshold(nativePtr: Long, high: Boolean): Int  // true = high, false = low
    private external fun nativeSetHfAtt(nativePtr: Long, step: Int): Int               // Possible values: 0..8 Range: 0..48 dB Attenuation with 6 dB steps
    private external fun nativeSetHfLna(nativePtr: Long, enable: Boolean): Int
    private external fun nativeStartRX(nativePtr: Long): Int
    private external fun nativeStopRX(nativePtr: Long): Int

    override val sampleBuffers = SampleBufferPool(BUFFER_SIZE)

    init {
        if (nativeDevicePtr == 0L) {
            throw IllegalArgumentException("Native device pointer cannot be null (0) for AirspyHFDevice.")
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
            if (result == AirspyHFError.AIRSPYHF_SUCCESS.code) {
                Log.i(TAG, "close: AirspyHF device closed successfully.")
                return true
            } else {
                Log.e(TAG, "close: Error closing AirspyHF device: ${AirspyHFError.fromCode(result)}")
            }
        }
        return false
    }

    // Fallback for a device that was never closed
    protected fun finalize() {
        if (nativeDevicePtr != 0L) {
            Log.w(TAG, "finalize: AirspyHFDevice was not explicitly closed. Closing in finalize().")
            close()
        }
    }

    fun setSampleRate(sampleRate: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setSampleRate: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetSampleRate(nativeDevicePtr, sampleRate) == AirspyHFError.AIRSPYHF_SUCCESS.code
    }

    fun setFrequency(frequency: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setFrequency: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetFrequency(nativeDevicePtr, frequency) == AirspyHFError.AIRSPYHF_SUCCESS.code
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

    fun setHfAgcEnabled(enabled: Boolean): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setHfAgcEnabled: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetHfAgc(nativeDevicePtr, enabled) == AirspyHFError.AIRSPYHF_SUCCESS.code
    }

    fun setHfAgcThreshold(high: Boolean): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setHfAgcThreshold: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetHfAgcThreshold(nativeDevicePtr, high) == AirspyHFError.AIRSPYHF_SUCCESS.code
    }

    fun setHfAttenuation(step: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setHfAttenuation: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetHfAtt(nativeDevicePtr, step) == AirspyHFError.AIRSPYHF_SUCCESS.code
    }

    fun setHfLnaEnabled(enabled: Boolean): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setHfLnaEnabled: nativeDevicePtr is null. Device might be closed or not initialized.")
            return false
        }
        return nativeSetHfLna(nativeDevicePtr, enabled) == AirspyHFError.AIRSPYHF_SUCCESS.code
    }

    override fun startRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "startRX: Device already closed or not opened.")
            return false
        }
        return nativeStartRX(nativeDevicePtr) == AirspyHFError.AIRSPYHF_SUCCESS.code
    }

    override fun stopRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "stopRX: Device already closed or not opened.")
            return false
        }
        return nativeStopRX(nativeDevicePtr) == AirspyHFError.AIRSPYHF_SUCCESS.code
    }

    // Called from JNI (sample_handoff.h) on the driver's RX thread
    @Keep
    private fun getEmptyBuffer(): ByteArray? = sampleBuffers.takeEmpty()

    @Keep
    private fun onSamplesReady(buffer: ByteArray, validByteCount: Int, droppedSamples: Long) =
        sampleBuffers.putFilled(buffer, validByteCount, droppedSamples)
}

enum class AirspyHFError(val code: Int) {
    AIRSPYHF_SUCCESS(0),
    AIRSPYHF_ERROR(-1),
    AIRSPYHF_UNSUPPORTED(-2);

    companion object {
        private val map = entries.associateBy(AirspyHFError::code)
        fun fromCode(code: Int): AirspyHFError? = map[code]
    }

    override fun toString(): String {
        return when (this) {
            AIRSPYHF_SUCCESS -> "SUCCESS"
            AIRSPYHF_ERROR -> "ERROR"
            AIRSPYHF_UNSUPPORTED -> "UNSUPPORTED"
        }
    }
}
