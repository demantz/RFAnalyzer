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
import com.mantz_it.libairspy.AirspyDevice

/**
 * <h1>RF Analyzer - Airspy Source</h1>
 *
 * Module:      AirspySource.kt
 * Description: Source Class representing an Airspy Device in RF Analyzer
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
class AirspySource : IQSourceInterface {

    companion object {
        private const val TAG = "AirspySource"
        private const val ACTION_USB_PERMISSION = "com.mantz_it.rfanalyzer.AIRSPY_USB_PERMISSION"

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

    private var airspyDevice: AirspyDevice? = null
    private val converter = Signed16BitIQConverter()
    private var iqSourceCallback: IQSourceInterface.Callback? = null
    private var sampleRate: Int = 0
    private var frequency: Long = 0

    override fun getSampleRate(): Int {
        return sampleRate
    }

    override fun setSampleRate(newSampleRate: Int) {
        sampleRate = newSampleRate
        airspyDevice?.setSampleRate(newSampleRate)
        converter.setSampleRate(newSampleRate)
        airspyDevice?.flushBufferQueue()
    }

    override fun getFrequency(): Long {
        return frequency + frequencyOffset
    }

    override fun setFrequency(newFrequency: Long) {
        frequency = newFrequency - frequencyOffset
        airspyDevice?.setFrequency(frequency.toInt())
        converter.frequency = newFrequency
        airspyDevice?.flushBufferQueue()
    }

    var advancedGainEnabled: Boolean = false
        set(value) {
            field = value
            if(value) {
                airspyDevice?.setLnaGain(lnaGain)
                airspyDevice?.setMixerGain(mixerGain)
                airspyDevice?.setVgaGain(vgaGain)
            } else {
                airspyDevice?.setLinearityGain(linearityGain)
                airspyDevice?.setSensitivityGain(sensitivityGain)
            }
        }

    var lnaGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_LNA_GAIN, MAX_LNA_GAIN)
            if (advancedGainEnabled)
                airspyDevice?.setLnaGain(value.coerceIn(MIN_LNA_GAIN, MAX_LNA_GAIN))
        }

    var mixerGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_MIXER_GAIN, MAX_MIXER_GAIN)
            if (advancedGainEnabled)
                airspyDevice?.setMixerGain(value.coerceIn(MIN_MIXER_GAIN, MAX_MIXER_GAIN))
        }

    var vgaGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_VGA_GAIN, MAX_VGA_GAIN)
            if (advancedGainEnabled)
                airspyDevice?.setVgaGain(value.coerceIn(MIN_VGA_GAIN, MAX_VGA_GAIN))
        }

    var linearityGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_LINEARITY_GAIN, MAX_LINEARITY_GAIN)
            if (!advancedGainEnabled)
                airspyDevice?.setLinearityGain(value.coerceIn(MIN_LINEARITY_GAIN, MAX_LINEARITY_GAIN))
        }

    var sensitivityGain: Int = 0
        set(value) {
            field = value.coerceIn(MIN_SENSITIVITY_GAIN, MAX_SENSITIVITY_GAIN)
            if (!advancedGainEnabled)
                airspyDevice?.setSensitivityGain(value.coerceIn(MIN_SENSITIVITY_GAIN, MAX_SENSITIVITY_GAIN))
        }

    var rfBias: Boolean = false
        set(value) {
            field = value
            airspyDevice?.setRfBias(value)
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
        if (airspyDevice != null) {
            Log.w(TAG, "open: airspyDevice is already open (not null).")
            return false
        }
        if (callback == null) {
            Log.w(TAG, "open: callback is null.")
            return false
        }
        iqSourceCallback = callback

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == 0x1d50 && it.productId == 0x60a1
        }
        if (device == null) {
            Log.i(TAG, "open: No Airspy device found.")
            usbManager.deviceList.values.forEach {
                Log.i(TAG, "open: Unknown USB device: ${it.deviceName} (VendorId: ${it.vendorId}, ProductId: ${it.productId})")
            }
            return false
        }
        Log.i(TAG, "open: device=$device (vendorid: ${device.vendorId} productId: ${device.productId})")
        if (usbManager.hasPermission(device)) {
            openAirspyDevice(device, context)
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
                                    openAirspyDevice(device, context)
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

    private fun openAirspyDevice(device: UsbDevice, context: Context): Boolean {
        var errorMsg: String? = null
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(device)) {
            val connection = usbManager.openDevice(device)
            if (connection != null) {
                val (deviceHandle, airspyError) = AirspyDevice.open(connection.fileDescriptor)
                if (deviceHandle != null) {
                    airspyDevice = deviceHandle
                    Log.i(TAG,"openAirspyDevice: Airspy Board Version String: ${deviceHandle.getVersionString() ?: "<unknown>"}")
                    iqSourceCallback?.onIQSourceReady(this)
                    return true
                } else if (airspyError != null) {
                    Log.w(TAG, "openAirspyDevice: Error on AirspyDevice.open: $airspyError")
                    errorMsg = airspyError.toString()
                }
            } else {
                Log.w(TAG, "openAirspyDevice: Error opening USB connection.")
                errorMsg = "Error opening USB connection."
            }
        } else {
            Log.w(TAG, "openAirspyDevice: No permission to open Airspy device.")
            errorMsg = "No permission"
        }
        iqSourceCallback?.onIQSourceError(this, "Failed to open Airspy device ($errorMsg)")
        Toast.makeText(context, "Failed to open Airspy device ($errorMsg).", Toast.LENGTH_SHORT).show()
        return false
    }

    override fun isOpen(): Boolean {
        airspyDevice?.let {
            val versionString = it.getVersionString()
            if (versionString != null)
                return true
        }
        return false
    }

    override fun close(): Boolean {
        airspyDevice?.let {
            return it.close()
        }
        return true
    }

    override fun getName(): String? {
        airspyDevice?.let {
            val versionString = it.getVersionString()
            if (versionString != null)
                return versionString
        }
        return "Airspy"
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
        val supportedSampleRates = airspyDevice?.getSupportedSampleRates()
        return if (supportedSampleRates != null && supportedSampleRates.isNotEmpty()) {
            supportedSampleRates.sorted().toIntArray()
        } else {
            listOf(0).toIntArray()
        }
    }

    override fun getPacketSize(): Int {
        return AirspyDevice.BUFFER_SIZE
    }

    override fun getBytesPerSample(): Int {
        return 4
    }

    override fun getPacket(timeout: Int): ByteArray? {
        if (airspyDevice == null)
            Log.w(TAG, "getPacket: airspyDevice is null.")
        val packet = airspyDevice?.getSampleBuffer()
        if (packet == null && !(airspyDevice?.isStreaming() ?: false)) {
            Log.w(TAG, "getPacket: airspyDevice did not return a packet and is not streaming. report source error..")
            iqSourceCallback?.onIQSourceError(this, "Airspy stopped streaming")
            return null
        }
        return packet
    }

    override fun returnPacket(buffer: ByteArray?) {
        if (buffer == null)
            return
        if (airspyDevice == null)
            Log.w(TAG, "returnPacket: airspyDevice is null.")
        airspyDevice?.returnSampleBuffer(buffer)
    }

    override fun startSampling() {
        if (airspyDevice == null)
            Log.w(TAG, "startSampling: airspyDevice is null.")
        airspyDevice?.setSampleRate(sampleRate)
        airspyDevice?.setFrequency(frequency.toInt())
        if (advancedGainEnabled) {
            airspyDevice?.setVgaGain(vgaGain)
            airspyDevice?.setLnaGain(lnaGain)
            airspyDevice?.setMixerGain(mixerGain)
        } else {
            airspyDevice?.setLinearityGain(linearityGain)
            airspyDevice?.setSensitivityGain(sensitivityGain)
        }
        airspyDevice?.setRfBias(rfBias)
        airspyDevice?.startRX()
    }

    override fun stopSampling() {
        if (airspyDevice == null)
            Log.w(TAG, "stopSampling: airspyDevice is null.")
        airspyDevice?.stopRX()
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




