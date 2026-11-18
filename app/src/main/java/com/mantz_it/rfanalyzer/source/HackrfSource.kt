package com.mantz_it.rfanalyzer.source

import com.mantz_it.libhackrf.HackrfDevice

/**
 * <h1>RF Analyzer - HackRF Source</h1>
 *
 * Module:      HackrfSource.kt
 * Description: The source for a HackRF on USB, on top of UsbSdrSource: frequency and sample rate
 *              ranges, LNA and VGA gain, the RF amplifier and the antenna power. The baseband
 *              filter is set to 75 % of the sample rate; the samples are signed 8-bit IQ.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class HackrfSource : UsbSdrSource<HackrfDevice>(UsbSdrType.HACKRF, TAG, Signed8BitIQConverter()) {

    companion object {
        private const val TAG = "HackrfSource"

        const val MIN_FREQUENCY = 1L
        const val MAX_FREQUENCY = 7250000000L

        const val MAX_SAMPLERATE = 20000000
        const val MIN_SAMPLERATE = 1000000

        const val MAX_VGA_RX_GAIN = 62
        const val MAX_LNA_GAIN = 40

        const val VGA_RX_GAIN_STEP_SIZE = 2
        const val LNA_GAIN_STEP_SIZE = 8

        val OPTIMAL_SAMPLE_RATES = intArrayOf(4000000, 6000000, 8000000, 10000000, 12500000, 16000000, 20000000)
    }

    var lnaGain: Int = 0
        set(value) {
            field = value.coerceIn(0, MAX_LNA_GAIN)
            device?.setLnaGain(field)
        }

    var vgaGain: Int = 0
        set(value) {
            field = value.coerceIn(0, MAX_VGA_RX_GAIN)
            device?.setVgaGain(field)
        }

    var ampEnabled: Boolean = false
        set(value) {
            field = value
            device?.setAmpEnable(value)
        }

    var antennaPowerEnabled: Boolean = false
        set(value) {
            field = value
            device?.setAntennaEnable(value)
        }

    override fun openDevice(fd: Int) = HackrfDevice.open(fd)

    override var sampleRate: Int
        get() = super.sampleRate
        set(value) {
            super.sampleRate = value.coerceIn(MIN_SAMPLERATE, MAX_SAMPLERATE)
        }

    override fun applySampleRate(device: HackrfDevice, sampleRate: Int): Boolean {
        if (!device.setSampleRate(sampleRate.toDouble())) return false
        device.setBasebandFilterBandwidth((sampleRate * 0.75).toInt())
        return true
    }

    override fun applyFrequency(device: HackrfDevice, frequency: Long) {
        device.setFrequency(frequency)
    }

    override fun applySettings(device: HackrfDevice) {
        device.setLnaGain(lnaGain)
        device.setVgaGain(vgaGain)
        device.setAmpEnable(ampEnabled)
        device.setAntennaEnable(antennaPowerEnabled)
    }

    override val name: String
        get() = "HackRF"

    override val maxFrequency: Long
        get() = MAX_FREQUENCY + frequencyOffset

    override val minFrequency: Long
        get() = MIN_FREQUENCY + frequencyOffset

    override val supportedSampleRates: IntArray
        get() = OPTIMAL_SAMPLE_RATES

    override val packetSize: Int
        get() = HackrfDevice.BUFFER_SIZE

    override val bytesPerSample: Int
        get() = 2
}
