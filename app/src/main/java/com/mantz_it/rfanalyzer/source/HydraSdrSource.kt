package com.mantz_it.rfanalyzer.source

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.mantz_it.libhydrasdr.HydraSdrDevice

/**
 * <h1>RF Analyzer - HydraSdr Source</h1>
 *
 * Module:      HydraSdrSource.kt
 * Description: Source Class representing an HydraSdr Device in RF Analyzer
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
enum class HydraSdrRfPort(val value: Int, val displayName: String) {
    RX0(0, "AIR_IN/ANT (RX0)"),
    RX1(1, "CABLE1 (RX1)"),
    RX2(2, "CABLE2 (RX2)")
}

class HydraSdrSource : IQSourceInterface {

    companion object {
        private const val TAG = "HydraSdrSource"
        private const val ACTION_USB_PERMISSION = "com.mantz_it.rfanalyzer.HYDRASDR_USB_PERMISSION"

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

    private var hydraSdrDevice: HydraSdrDevice? = null
    private val converter = Signed16BitIQConverter()
    private var iqSourceCallback: IQSourceInterface.Callback? = null
    private var sampleRate: Int = 0
    private var frequency: Long = 0

    override fun getSampleRate(): Int {
        return sampleRate
    }

    override fun setSampleRate(newSampleRate: Int) {
        sampleRate = newSampleRate
        hydraSdrDevice?.setSampleRate(newSampleRate)
        converter.setSampleRate(newSampleRate)
        hydraSdrDevice?.flushBufferQueue()
    }

    override fun getFrequency(): Long {
        return frequency + frequencyOffset
    }

    override fun setFrequency(newFrequency: Long) {
        frequency = newFrequency - frequencyOffset
        hydraSdrDevice?.setFrequency(frequency.toInt())
        converter.frequency = newFrequency
        hydraSdrDevice?.flushBufferQueue()
    }

    var advancedGainEnabled: Boolean = false
        set(value) {
            field = value
            if(value) {
                hydraSdrDevice?.setLnaGain(lnaGain)
                hydraSdrDevice?.setMixerGain(mixerGain)
                hydraSdrDevice?.setVgaGain(vgaGain)
            } else {
                hydraSdrDevice?.setLinearityGain(linearityGain)
                hydraSdrDevice?.setSensitivityGain(sensitivityGain)
            }
        }

    var lnaGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_LNA_GAIN, MAX_LNA_GAIN)
            if (advancedGainEnabled)
                hydraSdrDevice?.setLnaGain(value.coerceIn(MIN_LNA_GAIN, MAX_LNA_GAIN))
        }

    var mixerGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_MIXER_GAIN, MAX_MIXER_GAIN)
            if (advancedGainEnabled)
                hydraSdrDevice?.setMixerGain(value.coerceIn(MIN_MIXER_GAIN, MAX_MIXER_GAIN))
        }

    var vgaGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_VGA_GAIN, MAX_VGA_GAIN)
            if (advancedGainEnabled)
                hydraSdrDevice?.setVgaGain(value.coerceIn(MIN_VGA_GAIN, MAX_VGA_GAIN))
        }

    var linearityGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_LINEARITY_GAIN, MAX_LINEARITY_GAIN)
            if (!advancedGainEnabled)
                hydraSdrDevice?.setLinearityGain(value.coerceIn(MIN_LINEARITY_GAIN, MAX_LINEARITY_GAIN))
        }

    var sensitivityGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_SENSITIVITY_GAIN, MAX_SENSITIVITY_GAIN)
            if (!advancedGainEnabled)
                hydraSdrDevice?.setSensitivityGain(value.coerceIn(MIN_SENSITIVITY_GAIN, MAX_SENSITIVITY_GAIN))
        }

    var rfBias: Boolean = false
        set(value) {
            field = value
            hydraSdrDevice?.setRfBias(value)
        }

    var rfPort: HydraSdrRfPort = HydraSdrRfPort.RX0
        set(value) {
            field = value
            hydraSdrDevice?.setRfPort(value.value)
        }

    var frequencyOffset: Int = 0
        set(value) {
            field = value
            converter.frequency = frequency + value
        }

    override fun open(
        context: Context,
        callback: IQSourceInterface.Callback?
    ): Boolean {
        if (hydraSdrDevice != null) {
            Log.w(TAG, "open: hydraSdrDevice is already open (not null).")
            return false
        }
        if (callback == null) {
            Log.w(TAG, "open: callback is null.")
            return false
        }
        iqSourceCallback = callback

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == 0x38af && it.productId == 0x0001
        }
        if (device == null) {
            Log.i(TAG, "open: No HydraSDR device found.")
            usbManager.deviceList.values.forEach {
                Log.i(TAG, "open: Unknown USB device: ${it.deviceName} (VendorId: ${it.vendorId}, ProductId: ${it.productId})")
            }
            return false
        }
        Log.i(TAG, "open: device=$device (vendorid: ${device.vendorId} productId: ${device.productId})")
        if (usbManager.hasPermission(device)) {
            openHydraSdrDevice(device, context)
        } else {
            // Register broadcast receiver BEFORE requesting permission
            val usbPermissionReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (ACTION_USB_PERMISSION == intent.action) {
                        synchronized(this) {
                            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                            if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                                if (device != null) {
                                    Log.i(TAG, "USB permission granted for device: ${device.productName}")
                                    openHydraSdrDevice(device, context)
                                }
                            } else {
                                Log.w(TAG, "USB permission denied for device: ${device?.productName}")
                                Toast.makeText(context, "USB permission denied.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    context.unregisterReceiver(this)
                }
            }
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            ContextCompat.registerReceiver(context, usbPermissionReceiver, filter,ContextCompat.RECEIVER_NOT_EXPORTED)

            // Request permission
            // Note: setting the package name of the inner intent makes it explicit
            // From Android 14 it is required that mutable PendingIntents have explicit inner intents!
            val innerIntent = Intent(ACTION_USB_PERMISSION)
            innerIntent.setPackage(context.packageName)
            val permissionIntent = android.app.PendingIntent.getBroadcast(context, 0, innerIntent, android.app.PendingIntent.FLAG_MUTABLE)
            Log.i(TAG, "open: requesting permission for device: $device")
            usbManager.requestPermission(device, permissionIntent)
        }
        return true
    }

    private fun openHydraSdrDevice(device: UsbDevice, context: Context): Boolean {
        var errorMsg: String? = null
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            val connection = usbManager.openDevice(device)
            if (connection != null) {
                val (deviceHandle, hydrasdrError) = HydraSdrDevice.open(connection.fileDescriptor)
                if (deviceHandle != null) {
                    hydraSdrDevice = deviceHandle
                    Log.i(TAG,"openHydraSdrDevice: HydraSDR Board Version String: ${deviceHandle.getVersionString() ?: "<unknown>"}")
                    iqSourceCallback?.onIQSourceReady(this)
                    return true
                } else if (hydrasdrError != null) {
                    Log.w(TAG, "openHydraSdrDevice: Error on HydraSdrDevice.open: $hydrasdrError")
                    errorMsg = hydrasdrError.toString()
                }
            } else {
                Log.w(TAG, "openHydraSdrDevice: Error opening USB connection.")
                errorMsg = "Error opening USB connection."
            }
        } else {
            Log.w(TAG, "openHydraSdrDevice: No permission to open HydraSDR device.")
            errorMsg = "No permission"
        }
        iqSourceCallback?.onIQSourceError(this, "Failed to open HydraSDR device ($errorMsg)")
        Toast.makeText(context, "Failed to open HydraSDR device ($errorMsg).", Toast.LENGTH_SHORT).show()
        return false
    }

    override fun isOpen(): Boolean {
        hydraSdrDevice?.let {
            val versionString = it.getVersionString()
            if (versionString != null)
                return true
        }
        return false
    }

    override fun close(): Boolean {
        hydraSdrDevice?.let {
            return it.close()
        }
        return true
    }

    override fun getName(): String? {
        hydraSdrDevice?.let {
            val versionString = it.getVersionString()
            if (versionString != null)
                return versionString
        }
        return "HydraSDR"
    }

    override fun getMaxFrequency(): Long {
        return MAX_FREQUENCY + frequencyOffset
    }

    override fun getMinFrequency(): Long {
        return MIN_FREQUENCY + frequencyOffset
    }

    override fun getNextHigherOptimalSampleRate(sampleRate: Int): Int {
        val supportedRates = getSupportedSampleRates() ?: return sampleRate
        return supportedRates.firstOrNull { it > sampleRate } ?: supportedRates.lastOrNull() ?: sampleRate
    }

    override fun getNextLowerOptimalSampleRate(sampleRate: Int): Int {
        val supportedRates = getSupportedSampleRates() ?: return sampleRate
        return supportedRates.reversed().firstOrNull { it < sampleRate } ?: supportedRates.firstOrNull() ?: sampleRate
    }

    override fun getSupportedSampleRates(): IntArray? {
        val supportedSampleRates = hydraSdrDevice?.getSupportedSampleRates()
        return if (supportedSampleRates != null && supportedSampleRates.isNotEmpty()) {
            supportedSampleRates.sorted().toIntArray()
        } else {
            listOf(0).toIntArray()
        }
    }

    override fun getPacketSize(): Int {
        return HydraSdrDevice.BUFFER_SIZE
    }

    override fun getBytesPerSample(): Int {
        return 4
    }

    override fun getPacket(timeout: Int): ByteArray? {
        if (hydraSdrDevice == null)
            Log.w(TAG, "getPacket: hydrasdrDevice is null.")
        val packet = hydraSdrDevice?.getSampleBuffer()
        if (packet == null && !(hydraSdrDevice?.isStreaming() ?: false)) {
            Log.w(TAG, "getPacket: hydrasdrDevice did not return a packet and is not streaming. report source error..")
            iqSourceCallback?.onIQSourceError(this, "HydraSdr stopped streaming")
            return null
        }
        return packet
    }

    override fun returnPacket(buffer: ByteArray?) {
        if (buffer == null)
            return
        if (hydraSdrDevice == null)
            Log.w(TAG, "returnPacket: hydrasdrDevice is null.")
        hydraSdrDevice?.returnSampleBuffer(buffer)
    }

    override fun startSampling() {
        if (hydraSdrDevice == null)
            Log.w(TAG, "startSampling: hydrasdrDevice is null.")
        hydraSdrDevice?.setSampleRate(sampleRate)
        hydraSdrDevice?.setFrequency(frequency.toInt())
        if (advancedGainEnabled) {
            hydraSdrDevice?.setVgaGain(vgaGain)
            hydraSdrDevice?.setLnaGain(lnaGain)
            hydraSdrDevice?.setMixerGain(mixerGain)
        } else {
            hydraSdrDevice?.setLinearityGain(linearityGain)
            hydraSdrDevice?.setSensitivityGain(sensitivityGain)
        }
        hydraSdrDevice?.setRfBias(rfBias)
        hydraSdrDevice?.setRfPort(rfPort.value)
        hydraSdrDevice?.startRX()
    }

    override fun stopSampling() {
        if (hydraSdrDevice == null)
            Log.w(TAG, "stopSampling: hydrasdrDevice is null.")
        hydraSdrDevice?.stopRX()
    }

    override fun fillPacketIntoSamplePacket(
        packet: ByteArray?,
        samplePacket: SamplePacket?
    ): Int {
        if (packet == null || samplePacket == null) {
            Log.w(TAG, "fillPacketIntoSamplePacket: packet or samplePacket is null.")
            return 0
        }
        return converter.fillPacketIntoSamplePacket(packet, samplePacket)
    }

    override fun mixPacketIntoSamplePacket(
        packet: ByteArray?,
        samplePacket: SamplePacket?,
        channelFrequency: Long
    ): Int {
        if (packet == null || samplePacket == null) {
            Log.w(TAG, "mixPacketIntoSamplePacket: packet or samplePacket is null.")
            return 0
        }
        return converter.mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency)
    }

}
