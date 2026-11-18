package com.mantz_it.rfanalyzer.source

import android.util.Log
import com.mantz_it.libairspy.AirspyDevice

/**
 * <h1>RF Analyzer - Airspy Source</h1>
 *
 * Module:      AirspySource.kt
 * Description: Source Class representing an Airspy Device in RF Analyzer
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
/** Composite gain presets of the Airspy (R820T tuner): one value sets LNA, mixer and VGA. */
enum class AirspyGainPreset { LINEARITY, SENSITIVITY }

class AirspySource : UsbSdrSource<AirspyDevice>(UsbSdrType.AIRSPY, TAG, Signed16BitIQConverter()) {

    companion object {
        private const val TAG = "AirspySource"

        const val MIN_FREQUENCY = 24000000L
        const val MAX_FREQUENCY = 1750000000L
        const val MIN_LNA_GAIN = 0
        const val MAX_LNA_GAIN = 15
        const val MIN_MIXER_GAIN = 0
        const val MAX_MIXER_GAIN = 15
        const val MIN_VGA_GAIN = 0
        const val MAX_VGA_GAIN = 15
        const val MIN_LINEARITY_GAIN = 0
        const val MAX_LINEARITY_GAIN = 21
        const val MIN_SENSITIVITY_GAIN = 0
        const val MAX_SENSITIVITY_GAIN = 21
    }

    // Read once at open: isOpen()/name are read from other threads (e.g. the scheduler's
    // error path) and a USB control transfer there can race with close() freeing the native device.
    private var versionString: String? = null

    var advancedGainEnabled: Boolean = false
        set(value) {
            field = value
            applyGains()
        }

    /** The composite preset applied while [advancedGainEnabled] is off. Each preset sets LNA, mixer
     * and VGA together, so only the selected one is written (writing both would let the last one win). */
    var gainPreset: AirspyGainPreset = AirspyGainPreset.LINEARITY
        set(value) {
            field = value
            if (!advancedGainEnabled) applyGains()
        }

    private fun applyGains() {
        if (advancedGainEnabled) {
            device?.setVgaGain(vgaGain)
            device?.setLnaGain(lnaGain)
            device?.setMixerGain(mixerGain)
        } else when (gainPreset) {
            AirspyGainPreset.LINEARITY -> device?.setLinearityGain(linearityGain)
            AirspyGainPreset.SENSITIVITY -> device?.setSensitivityGain(sensitivityGain)
        }
    }

    var lnaGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_LNA_GAIN, MAX_LNA_GAIN)
            if (advancedGainEnabled)
                device?.setLnaGain(value.coerceIn(MIN_LNA_GAIN, MAX_LNA_GAIN))
        }

    var mixerGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_MIXER_GAIN, MAX_MIXER_GAIN)
            if (advancedGainEnabled)
                device?.setMixerGain(value.coerceIn(MIN_MIXER_GAIN, MAX_MIXER_GAIN))
        }

    var vgaGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_VGA_GAIN, MAX_VGA_GAIN)
            if (advancedGainEnabled)
                device?.setVgaGain(value.coerceIn(MIN_VGA_GAIN, MAX_VGA_GAIN))
        }

    var linearityGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_LINEARITY_GAIN, MAX_LINEARITY_GAIN)
            if (!advancedGainEnabled && gainPreset == AirspyGainPreset.LINEARITY)
                device?.setLinearityGain(value.coerceIn(MIN_LINEARITY_GAIN, MAX_LINEARITY_GAIN))
        }

    var sensitivityGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_SENSITIVITY_GAIN, MAX_SENSITIVITY_GAIN)
            if (!advancedGainEnabled && gainPreset == AirspyGainPreset.SENSITIVITY)
                device?.setSensitivityGain(value.coerceIn(MIN_SENSITIVITY_GAIN, MAX_SENSITIVITY_GAIN))
        }

    var rfBias: Boolean = false
        set(value) {
            field = value
            device?.setRfBias(value)
        }

    override fun openDevice(fd: Int) = AirspyDevice.open(fd)

    override fun onDeviceOpened(device: AirspyDevice) {
        versionString = device.getVersionString()
        Log.i(TAG, "open: Airspy Board Version String: ${versionString ?: "<unknown>"}")
    }

    override fun applySampleRate(device: AirspyDevice, sampleRate: Int): Boolean = device.setSampleRate(sampleRate)

    override fun applyFrequency(device: AirspyDevice, frequency: Long) {
        device.setFrequency(frequency.toInt())
    }

    override fun applySettings(device: AirspyDevice) {
        applyGains()
        device.setRfBias(rfBias)
    }

    override val name: String
        get() = versionString ?: "Airspy"

    override val maxFrequency: Long
        get() = MAX_FREQUENCY + frequencyOffset

    override val minFrequency: Long
        get() = MIN_FREQUENCY + frequencyOffset

    override val supportedSampleRates: IntArray
        get() = device?.getSupportedSampleRates()?.takeIf { it.isNotEmpty() }?.sorted()?.toIntArray() ?: intArrayOf(0)

    override val packetSize: Int
        get() = AirspyDevice.BUFFER_SIZE

    override val bytesPerSample: Int
        get() = 4
}
