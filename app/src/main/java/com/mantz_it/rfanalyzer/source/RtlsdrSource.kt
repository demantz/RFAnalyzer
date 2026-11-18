package com.mantz_it.rfanalyzer.source

import com.mantz_it.librtlsdr.RtlSdrDevice

/**
 * <h1>RF Analyzer - RTL-SDR Source</h1>
 *
 * Module:      RtlsdrSource.kt
 * Description: The source for an RTL-SDR dongle on USB, on top of UsbSdrSource: tuner gain (manual
 *              or automatic), the RTL2832U's AGC, frequency correction, bias tee and direct
 *              sampling. It names the tuner chip it finds (and the RTL-SDR Blog V4) and reports a
 *              tune outside that tuner's range once, without refusing it; the samples are unsigned
 *              8-bit IQ.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class RtlsdrSource : UsbSdrSource<RtlSdrDevice>(UsbSdrType.RTLSDR, TAG, Unsigned8BitIQConverter()) {

    companion object {
        private const val TAG = "RtlsdrSource"

        // The most common RTL2832U VID/PID (RTL-SDR Blog V3 and V4 among others; librtlsdr tells
        // the V4 apart by its USB product string, see isBlogV4). Used by the instrumented tests;
        // UsbSdrType.RTLSDR lists every accepted ID.
        const val USB_VENDOR_ID = 0x0bda
        const val USB_PRODUCT_ID = 0x2838

        // Deliberately no limit: dongles, tuner chips, modifications and direct sampling cover
        // very different ranges. A tune outside the tuner chip's known range is reported once
        // (onIQSourceInformation, see checkFrequencyBounds()), never refused.
        const val MIN_FREQUENCY = 0L
        const val MAX_FREQUENCY = 3000000000L   // 3 GHz

        // Real per-tuner-chip bounds, used only to decide when to fire onIQSourceInformation -
        // never to clamp/reject a tune request. Index = librtlsdr's rtlsdr_tuner enum (as
        // returned by RtlSdrDevice.getTunerType(): 0=unknown, 1=E4000, 2=FC0012, 3=FC0013,
        // 4=FC2580, 5=R820T, 6=R828D), the same numbering as RtltcpSource's RTLSDR_TUNER_*.
        private val TUNER_MIN_FREQUENCY = longArrayOf(0L, 52000000L, 22000000L, 22000000L, 146000000L, 24000000L, 24000000L)
        private val TUNER_MAX_FREQUENCY = longArrayOf(0L, 2200000000L, 948000000L, 1100000000L, 924000000L, 1766000000L, 1766000000L)
        private val TUNER_NAME = listOf("unknown", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D")

        // Blog V4's built-in upconverter/triplexer extends the R828D's HF floor down to 500 kHz.
        private const val MIN_FREQUENCY_BLOG_V4 = 500000L

        /** A tuner limit as people write it: "500 kHz", "24 MHz", "1766 MHz". */
        private fun formatMhz(hz: Long): String =
            if (hz < 1_000_000) "${hz / 1000} kHz" else "%.1f".format(java.util.Locale.US, hz / 1e6).removeSuffix(".0") + " MHz"

        // The RTL2832U takes any rate from 0.9 to 3.2 Msps (and 225-300 kSps): the steps between
        // let Automatic Sample Rate go lower when zooming in. Not below 1 Msps: one 256 KiB USB
        // transfer would hold ~0.5 s at 250 kSps (a bursty waterfall, that much audio latency).
        // The first entry is a fresh install's rate; rtl_tcp uses the first and last as its range.
        val OPTIMAL_SAMPLE_RATES = intArrayOf(1000000, 1400000, 1800000, 2000000, 2400000, 2500000)
    }

    // Queried from the opened device (onDeviceOpened).
    private var isBlogV4: Boolean = false
    private var tunerType: Int = -1

    // Fires onIQSourceInformation at most once per direction per open() - reset in close().
    private var reportedBelowMinFrequency = false
    private var reportedAboveMaxFrequency = false

    var frequencyCorrection: Int = 0
        set(value) {
            field = value
            device?.setFreqCorrection(value)
        }

    var isManualGain: Boolean = false
        set(value) {
            field = value
            device?.setTunerGainMode(value)
        }

    var gain: Int = 0
        set(value) {
            field = value
            if (isManualGain) device?.setTunerGain(value)
        }

    // Never applied: only the E4000 tuner has a separate IF gain stage, and this source doesn't
    // set it. Exists for AnalyzerService's shared RTL-SDR IF gain setting; possibleIFGainValues
    // (a single 0) hides the Source tab's IF gain slider.
    var ifGain: Int = 0

    var isAutomaticGainControl: Boolean = false
        set(value) {
            field = value
            device?.setAgcMode(value)
        }

    // RtltcpSource has no bias tee setting.
    var biasTeeEnabled: Boolean = false
        set(value) {
            field = value
            device?.setBiasTee(value)
        }

    val possibleGainValues: IntArray
        get() = device?.getTunerGains()?.toIntArray() ?: intArrayOf(0)

    val possibleIFGainValues: IntArray
        get() = intArrayOf(0)

    /** 0 off, 1 I branch, 2 Q branch - kept, so a mode chosen before the device opens (the stored
     *  setting at start) reaches it in [applySettings]. */
    private var directSamplingMode: Int = 0

    fun setDirectSamplingMode(mode: Int) {
        directSamplingMode = mode
        device?.setDirectSampling(mode)
    }

    override fun openDevice(fd: Int) = RtlSdrDevice.open(fd)

    override fun onDeviceOpened(device: RtlSdrDevice) {
        isBlogV4 = device.isBlogV4()
        tunerType = device.getTunerType()
    }

    override fun onDeviceClosed() {
        isBlogV4 = false
        tunerType = -1
        reportedBelowMinFrequency = false
        reportedAboveMaxFrequency = false
    }

    override fun applySampleRate(device: RtlSdrDevice, sampleRate: Int): Boolean = device.setSampleRate(sampleRate)

    override fun applyFrequency(device: RtlSdrDevice, frequency: Long) {
        device.setFrequency(frequency)
    }

    override fun applySettings(device: RtlSdrDevice) {
        checkFrequencyBounds() // report back if frequency is out of range
        device.setFreqCorrection(frequencyCorrection)
        device.setTunerGainMode(isManualGain)
        if (isManualGain) device.setTunerGain(gain)
        device.setAgcMode(isAutomaticGainControl)
        device.setBiasTee(biasTeeEnabled)
        // librtlsdr re-tunes the current frequency itself when the sampling path changes
        if (directSamplingMode != 0) device.setDirectSampling(directSamplingMode)
    }

    private fun checkFrequencyBounds() {
        // direct sampling bypasses the tuner chip: HF below its range is fine then
        val realMinFrequency = when {
            directSamplingMode != 0 -> 0L
            isBlogV4 -> MIN_FREQUENCY_BLOG_V4
            else -> TUNER_MIN_FREQUENCY.getOrElse(tunerType) { 0L }
        }
        val realMaxFrequency = TUNER_MAX_FREQUENCY.getOrElse(tunerType) { Long.MAX_VALUE }
        if (tunedFrequency < realMinFrequency && !reportedBelowMinFrequency) {
            reportedBelowMinFrequency = true
            callback?.onIQSourceInformation(this, "RTL-SDR dongle does not support frequencies below ${formatMhz(realMinFrequency)}")
        } else if (tunedFrequency > realMaxFrequency && !reportedAboveMaxFrequency) {
            reportedAboveMaxFrequency = true
            callback?.onIQSourceInformation(this, "RTL-SDR dongle does not support frequencies above ${formatMhz(realMaxFrequency)}")
        }
    }

    override var frequency: Long
        get() = super.frequency
        set(value) {
            super.frequency = value
            checkFrequencyBounds()
        }

    override val name: String
        get() = when {
            isBlogV4 -> "RTL-SDR (Blog V4)"
            tunerType < 0 -> "RTL-SDR" // not opened (yet)
            else -> "RTL-SDR ${TUNER_NAME.getOrElse(tunerType) { "unknown ($it)" }}"
        }

    override val maxFrequency: Long
        get() = MAX_FREQUENCY + frequencyOffset

    override val minFrequency: Long
        get() = MIN_FREQUENCY + frequencyOffset

    override val supportedSampleRates: IntArray
        get() = OPTIMAL_SAMPLE_RATES

    override val packetSize: Int
        get() = RtlSdrDevice.BUFFER_SIZE

    override val bytesPerSample: Int
        get() = 2
}
