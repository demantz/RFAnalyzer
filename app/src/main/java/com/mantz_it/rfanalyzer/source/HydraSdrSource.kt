package com.mantz_it.rfanalyzer.source

import android.util.Log
import com.mantz_it.libhydrasdr.HydraSdrDevice
import com.mantz_it.libhydrasdr.HydraSdrDeviceInfo
import com.mantz_it.libhydrasdr.HydraSdrGainCategory
import com.mantz_it.libhydrasdr.HydraSdrGainType
import com.mantz_it.libhydrasdr.HydraSdrRfPortDescriptor

/**
 * <h1>RF Analyzer - HydraSdr Source</h1>
 *
 * Module:      HydraSdrSource.kt
 * Description: Source Class representing an HydraSdr Device in RF Analyzer
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
/** The RF ports before a device is queried (or if the query fails), see
 * [HydraSdrSource.availableRfPorts]; once connected, the device's [HydraSdrRfPortDescriptor]
 * list ([HydraSdrDeviceInfo]) counts. */
enum class HydraSdrRfPort(val value: Int, val displayName: String) {
    RX0(0, "AIR_IN/ANT (RX0)"),
    RX1(1, "CABLE1 (RX1)"),
    RX2(2, "CABLE2 (RX2)")
}

class HydraSdrSource : UsbSdrSource<HydraSdrDevice>(UsbSdrType.HYDRASDR, TAG, Signed16BitIQConverter()) {

    companion object {
        private const val TAG = "HydraSdrSource"

        const val USB_VENDOR_ID = 0x38af
        const val USB_PRODUCT_ID = 0x0001

        // Gain range before a device is queried (or for a gain type its HydraSdrDeviceInfo lacks);
        // once connected, the bounds come from HydraSdrDeviceInfo.getGainDescriptor().
        const val DEFAULT_MIN_GAIN = 0
        const val DEFAULT_MAX_GAIN = 21
    }

    // Read once at open: isOpen()/name are read from other threads (e.g. the scheduler's
    // error path) and a USB control transfer there can race with close() freeing the native device.
    private var versionString: String? = null

    /** Runtime-queried device capabilities (gain ranges, RF ports, frequency range, board
     * identity), populated once a device is open. Null before then, or if the query failed -
     * callers should fall back to [HydraSdrDeviceInfo.DEFAULT_MIN_FREQUENCY]/[HydraSdrDeviceInfo.DEFAULT_MAX_FREQUENCY]
     * and [DEFAULT_MIN_GAIN]/[DEFAULT_MAX_GAIN] in that case. */
    var deviceInfo: HydraSdrDeviceInfo? = null
        private set

    override var sampleRate: Int
        get() = super.sampleRate
        set(newSampleRate) {
            // hydrasdr_set_samplerate() swaps the decimation filter chain (update_ddc_decimation()
            // in hydrasdr_shared.c frees the old decimators) without synchronizing with the RX
            // threads still using it - although the driver documents it as callable while
            // streaming, a switch to a rate with more decimation can crash in the vendored
            // decimator (SIGSEGV in dec_hb33_int16_process). So the change is bracketed by
            // stop/start RX: hydrasdr_stop_rx() joins the RX threads first. getPacket() waits
            // through the gap.
            synchronized(streamLock) {
                device?.stopRX()
                super.sampleRate = newSampleRate
                if (samplingActive) device?.startRX()
            }
        }

    override fun applySampleRate(device: HydraSdrDevice, sampleRate: Int): Boolean = device.setSampleRate(sampleRate)

    override fun applyFrequency(device: HydraSdrDevice, frequency: Long) {
        device.setFrequency(frequency)
    }

    override fun applySettings(device: HydraSdrDevice) {
        applyActiveGains()
        device.setRfBias(rfBias)
        device.setRfPort(rfPortIndex)
    }

    // The value of every gain type ever set, active or not (unset ones: the device's default, see
    // getGain()), so toggling advancedGainEnabled re-applies each side's last values.
    private val gainValues = mutableMapOf<HydraSdrGainType, Int>()

    /** Current value for [type], from the local cache if set, else the device-reported default,
     * else [DEFAULT_MIN_GAIN]. */
    fun getGain(type: HydraSdrGainType): Int =
        gainValues[type] ?: deviceInfo?.getGainDescriptor(type)?.defaultValue ?: DEFAULT_MIN_GAIN

    /** Sets [type]'s value, coerced into the device-reported range if known (else
     * [DEFAULT_MIN_GAIN]/[DEFAULT_MAX_GAIN]). Applied to hardware immediately only if [type]'s
     * category is currently active per [advancedGainEnabled]; always cached regardless, so a
     * later mode switch re-applies it. Returns the actual (coerced) value stored. */
    fun setGain(type: HydraSdrGainType, value: Int): Int {
        val descriptor = deviceInfo?.getGainDescriptor(type)
        val coerced = value.coerceIn(descriptor?.minValue ?: DEFAULT_MIN_GAIN, descriptor?.maxValue ?: DEFAULT_MAX_GAIN)
        gainValues[type] = coerced
        if (isActiveGainType(type)) {
            device?.setGain(type, coerced)
        }
        return coerced
    }

    // Manual mode: every MANUAL gain. Preset mode: only the selected preset - each preset sets
    // LNA, mixer and VGA together, so applying more than one would let the last one win.
    private fun isActiveGainType(type: HydraSdrGainType): Boolean =
        if (advancedGainEnabled) type.category == HydraSdrGainCategory.MANUAL else type == presetGainType

    /** Writes every active gain (cached or device default) to the hardware - when
     * [advancedGainEnabled]/[presetGainType] change and before streaming starts ([applySettings]). */
    private fun applyActiveGains() {
        val activeTypes = deviceInfo?.gains?.map { it.type }?.filter { isActiveGainType(it) }
            ?: HydraSdrGainType.entries.filter { isActiveGainType(it) }
        activeTypes.forEach { type -> device?.setGain(type, getGain(type)) }
    }

    /** The composite preset (a PRESET-category gain type, e.g. LINEARITY or SENSITIVITY) applied
     * while [advancedGainEnabled] is off. */
    var presetGainType: HydraSdrGainType = HydraSdrGainType.LINEARITY
        set(value) {
            field = value
            if (!advancedGainEnabled) applyActiveGains()
        }

    /** Which gain category goes to the hardware ("Advanced Gain Control"): MANUAL (LNA/mixer/VGA-style
     * controls) when on, the PRESET [presetGainType] (linearity/sensitivity-style) when off - of
     * whatever gains the connected device reports. */
    var advancedGainEnabled: Boolean = false
        set(value) {
            field = value
            applyActiveGains()
        }

    var rfBias: Boolean = false
        set(value) {
            field = value
            device?.setRfBias(value)
        }

    /** RF port index (matches hydrasdr_rf_port_t - a plain firmware-defined ordinal, not a fixed
     * enum). See [availableRfPorts] for the list of ports this device actually reports. */
    var rfPortIndex: Int = 0
        set(value) {
            field = value
            device?.setRfPort(value)
        }

    /** RF ports this device reports, or [HydraSdrRfPort]'s before a device is queried. */
    val availableRfPorts: List<HydraSdrRfPortDescriptor>
        get() = deviceInfo?.rfPorts?.takeIf { it.isNotEmpty() }
            ?: HydraSdrRfPort.entries.map {
                HydraSdrRfPortDescriptor(it.value, it.displayName, HydraSdrDeviceInfo.DEFAULT_MIN_FREQUENCY, HydraSdrDeviceInfo.DEFAULT_MAX_FREQUENCY, false)
            }

    override fun openDevice(fd: Int) = HydraSdrDevice.open(fd)

    override fun onDeviceOpened(device: HydraSdrDevice) {
        versionString = device.getVersionString()
        Log.i(TAG, "open: HydraSDR Board Version String: ${versionString ?: "<unknown>"}")
        deviceInfo = device.getDeviceInfo()
        if (deviceInfo == null) {
            Log.w(TAG, "open: Failed to query device info - falling back to generic default bounds.")
        } else {
            Log.i(TAG, "open: deviceInfo=$deviceInfo")
        }
    }

    override val name: String
        get() = deviceInfo?.let { "${it.boardName} (FW: ${it.firmwareVersion})" } ?: versionString ?: "HydraSDR"

    override val maxFrequency: Long
        get() = (deviceInfo?.maxFrequency ?: HydraSdrDeviceInfo.DEFAULT_MAX_FREQUENCY) + frequencyOffset

    override val minFrequency: Long
        get() = (deviceInfo?.minFrequency ?: HydraSdrDeviceInfo.DEFAULT_MIN_FREQUENCY) + frequencyOffset

    override val supportedSampleRates: IntArray
        get() = device?.getSupportedSampleRates()?.takeIf { it.isNotEmpty() }?.sorted()?.toIntArray() ?: intArrayOf(0)

    override val packetSize: Int
        get() = HydraSdrDevice.BUFFER_SIZE

    override val bytesPerSample: Int
        get() = 4
}
