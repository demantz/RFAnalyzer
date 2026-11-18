package com.mantz_it.rfanalyzer.source

import android.util.Log
import com.mantz_it.libairspyhf.AirspyHFDevice

/**
 * <h1>RF Analyzer - AirspyHF Source</h1>
 *
 * Module:      AirspyHfSource.kt
 * Description: Source Class representing an AirspyHF Device in RF Analyzer
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class AirspyHfSource : UsbSdrSource<AirspyHFDevice>(UsbSdrType.AIRSPYHF, TAG, Float32IQConverter()) {

    companion object {
        private const val TAG = "AirspyHFSource"

        const val MIN_FREQUENCY = 500000L
        const val MAX_FREQUENCY = 260000000L
        const val MIN_ATTENUATION = 0
        const val MAX_ATTENUATION = 8
        const val ATTENUATION_STEP = 6 // dB
    }

    // Read once at open: isOpen()/name are read from other threads (e.g. the scheduler's
    // error path) and a USB control transfer there can race with close() freeing the native device.
    private var versionString: String? = null

    var agcEnabled: Boolean = false
        set(value) {
            field = value
            device?.setHfAgcEnabled(value)
            if(value) {
                device?.setHfAgcThreshold(agcThreshold)
            } else {
                device?.setHfAttenuation(attenuation)
            }
        }

    var agcThreshold: Boolean = false
        set(value) {
            field = value
            if (agcEnabled)
                device?.setHfAgcThreshold(value)
        }

    var attenuation: Int = 0
        set(value) {
            field = value.coerceIn(MIN_ATTENUATION, MAX_ATTENUATION)
            if (!agcEnabled)
                device?.setHfAttenuation(value.coerceIn(MIN_ATTENUATION, MAX_ATTENUATION))
        }

    var lnaEnabled: Boolean = false
        set(value) {
            field = value
            device?.setHfLnaEnabled(value)
        }

    override fun openDevice(fd: Int) = AirspyHFDevice.open(fd)

    override fun onDeviceOpened(device: AirspyHFDevice) {
        versionString = device.getVersionString()
        Log.i(TAG, "open: AirspyHF Board Version String: ${versionString ?: "<unknown>"}")
    }

    override fun applySampleRate(device: AirspyHFDevice, sampleRate: Int): Boolean = device.setSampleRate(sampleRate)

    override fun applyFrequency(device: AirspyHFDevice, frequency: Long) {
        device.setFrequency(frequency.toInt())
    }

    override fun applySettings(device: AirspyHFDevice) {
        device.setHfAgcEnabled(agcEnabled)
        if (agcEnabled) {
            device.setHfAgcThreshold(agcThreshold)
        } else {
            device.setHfAttenuation(attenuation)
        }
        device.setHfLnaEnabled(lnaEnabled)
    }

    override fun onStreamStarted(device: AirspyHFDevice) {
        device.setFrequency(tunedFrequency.toInt())
        Thread.sleep(1000) // the AirspyHF takes about a second to deliver its first samples
    }

    override val name: String
        get() = versionString?.let { "AirspyHF ($it)" } ?: "AirspyHF"

    override val maxFrequency: Long
        get() = MAX_FREQUENCY + frequencyOffset

    override val minFrequency: Long
        get() = MIN_FREQUENCY + frequencyOffset

    override val supportedSampleRates: IntArray
        get() = device?.getSupportedSampleRates()?.takeIf { it.isNotEmpty() }?.sorted()?.toIntArray() ?: intArrayOf(0)

    override val packetSize: Int
        get() = AirspyHFDevice.BUFFER_SIZE

    override val bytesPerSample: Int
        get() = 8
}
