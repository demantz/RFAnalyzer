package com.mantz_it.libhackrf

import android.util.Log
import androidx.annotation.Keep
import com.mantz_it.libusb.SampleBufferPool
import com.mantz_it.libusb.SampleStreamDevice

/**
 * <h1>RF Analyzer - Hackrf Device</h1>
 *
 * Module:      HackrfDevice.kt
 * Description: A Kotlin interface to the native libhackrf driver
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class HackrfDevice private constructor(private var nativeDevicePtr: Long) : SampleStreamDevice {

    companion object {
        private const val TAG = "HackrfDevice"

        // libhackrf's TRANSFER_BUFFER_SIZE: one RX transfer of interleaved signed 8-bit I/Q
        // samples (2 bytes per sample).
        const val BUFFER_SIZE = 262144 // bytes

        @JvmStatic
        private external fun nativeOpenFd(fd: Int): Long

        @JvmStatic
        external fun getLibraryVersionString(): String?

        fun open(fd: Int): Pair<HackrfDevice?, Int?> {
            val result = nativeOpenFd(fd)
            // A negative hackrf_error on failure, else the device pointer - which can be negative
            // as a signed long, so only -10000..0 means an error
            if (result <= 0 && result >= -10000) {
                Log.e(TAG, "open: Error opening HackRF device: $result")
                return Pair(null, result.toInt())
            } else {
                return Pair(HackrfDevice(result), null)
            }
        }

        init {
            System.loadLibrary("libhackrf")
            Log.i(TAG, "libhackrf loaded: ${getLibraryVersionString()}")
        }
    }

    // native functions
    private external fun nativeVersionStringRead(nativePtr: Long): String?
    private external fun nativeIsStreaming(nativePtr: Long): Boolean
    private external fun nativeClose(nativePtr: Long): Int
    private external fun nativeSetLnaGain(nativePtr: Long, gain: Int): Int
    private external fun nativeSetVgaGain(nativePtr: Long, gain: Int): Int
    private external fun nativeSetAmpEnable(nativePtr: Long, enable: Boolean): Int
    private external fun nativeSetAntennaEnable(nativePtr: Long, enable: Boolean): Int
    private external fun nativeSetBasebandFilterBandwidth(nativePtr: Long, bandwidth: Int): Int
    private external fun nativeSetSampleRate(nativePtr: Long, sampleRate: Double): Int
    private external fun nativeSetFrequency(nativePtr: Long, frequency: Long): Int
    private external fun nativeStartRX(nativePtr: Long): Int
    private external fun nativeStopRX(nativePtr: Long): Int

    override val sampleBuffers = SampleBufferPool(BUFFER_SIZE)

    init {
        if (nativeDevicePtr == 0L) {
            throw IllegalArgumentException("Native device pointer cannot be null (0) for HackrfDevice.")
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
            nativeDevicePtr = 0L
            if (result == 0) {
                Log.i(TAG, "close: HackRF device closed successfully.")
                return true
            } else {
                Log.e(TAG, "close: Error closing HackRF device: $result")
            }
        }
        return false
    }

    protected fun finalize() {
        if (nativeDevicePtr != 0L) {
            Log.w(TAG, "finalize: HackrfDevice was not explicitly closed. Closing in finalize().")
            close()
        }
    }

    fun setBasebandFilterBandwidth(bandwidth: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setBasebandFilterBandwidth: nativeDevicePtr is null.")
            return false
        }
        return nativeSetBasebandFilterBandwidth(nativeDevicePtr, bandwidth) == 0
    }

    fun setSampleRate(sampleRate: Double): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setSampleRate: nativeDevicePtr is null.")
            return false
        }
        return nativeSetSampleRate(nativeDevicePtr, sampleRate) == 0
    }

    fun setFrequency(frequencyHz: Long): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setFrequency: nativeDevicePtr is null.")
            return false
        }
        return nativeSetFrequency(nativeDevicePtr, frequencyHz) == 0
    }

    fun setLnaGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setLnaGain: nativeDevicePtr is null.")
            return false
        }
        return nativeSetLnaGain(nativeDevicePtr, gain) == 0
    }

    fun setVgaGain(gain: Int): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setVgaGain: nativeDevicePtr is null.")
            return false
        }
        return nativeSetVgaGain(nativeDevicePtr, gain) == 0
    }

    fun setAmpEnable(enable: Boolean): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setAmpEnable: nativeDevicePtr is null.")
            return false
        }
        return nativeSetAmpEnable(nativeDevicePtr, enable) == 0
    }

    fun setAntennaEnable(enable: Boolean): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "setAntennaEnable: nativeDevicePtr is null.")
            return false
        }
        return nativeSetAntennaEnable(nativeDevicePtr, enable) == 0
    }

    override fun startRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "startRX: Device already closed or not opened.")
            return false
        }
        return nativeStartRX(nativeDevicePtr) == 0
    }

    override fun stopRX(): Boolean {
        if (nativeDevicePtr == 0L) {
            Log.e(TAG, "stopRX: Device already closed or not opened.")
            return false
        }
        return nativeStopRX(nativeDevicePtr) == 0
    }

    // Called from JNI (sample_handoff.h) on the driver's RX thread
    @Keep
    private fun getEmptyBuffer(): ByteArray? = sampleBuffers.takeEmpty()

    @Keep
    private fun onSamplesReady(buffer: ByteArray, validByteCount: Int, droppedSamples: Long) =
        sampleBuffers.putFilled(buffer, validByteCount, droppedSamples)
}
