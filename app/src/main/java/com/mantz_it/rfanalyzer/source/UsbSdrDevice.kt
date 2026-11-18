package com.mantz_it.rfanalyzer.source

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.mantz_it.rfanalyzer.ui.tabs.SourceType

/**
 * <h1>RF Analyzer - USB SDR Devices</h1>
 *
 * Module:      UsbSdrDevice.kt
 * Description: The USB SDRs the app can open: UsbSdrType, the one VID/PID table for opening a
 *              device and for auto-selecting the source type when one is attached, and
 *              UsbSdrOpener, which finds a device, requests the USB permission and opens it
 *              natively on a background thread on behalf of a source.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * Every USB SDR the app can open, identified by VID/PID. The single table used both for
 * opening a device (UsbSdrOpener) and for auto-selecting the source type when one is attached
 * (MainActivity).
 */
enum class UsbSdrType(val displayName: String, val ids: Set<Pair<Int, Int>>) {
    // HackRF One, Jawbreaker, rad1o - libhackrf's hackrf_open_fd() accepts all three.
    HACKRF("HackRF", setOf(Pair(0x1d50, 0x6089), Pair(0x1d50, 0x604b), Pair(0x1d50, 0xcc15))),
    // from https://github.com/osmocom/rtl-sdr/blob/master/rtl-sdr.rules
    RTLSDR("RTL-SDR", setOf(
        Pair(0x0bda, 0x2832), Pair(0x0bda, 0x2838), Pair(0x0413, 0x6680), Pair(0x0413, 0x6f0f), Pair(0x0458, 0x707f), Pair(0x0ccd, 0x00a9),
        Pair(0x0ccd, 0x00b3), Pair(0x0ccd, 0x00b4), Pair(0x0ccd, 0x00b5), Pair(0x0ccd, 0x00b7), Pair(0x0ccd, 0x00b8), Pair(0x0ccd, 0x00b9),
        Pair(0x0ccd, 0x00c0), Pair(0x0ccd, 0x00c6), Pair(0x0ccd, 0x00d3), Pair(0x0ccd, 0x00d7), Pair(0x0ccd, 0x00e0), Pair(0x1554, 0x5020),
        Pair(0x15f4, 0x0131), Pair(0x15f4, 0x0133), Pair(0x185b, 0x0620), Pair(0x185b, 0x0650), Pair(0x185b, 0x0680), Pair(0x1b80, 0xd393),
        Pair(0x1b80, 0xd394), Pair(0x1b80, 0xd395), Pair(0x1b80, 0xd397), Pair(0x1b80, 0xd398), Pair(0x1b80, 0xd39d), Pair(0x1b80, 0xd3a4),
        Pair(0x1b80, 0xd3a8), Pair(0x1b80, 0xd3af), Pair(0x1b80, 0xd3b0), Pair(0x1d19, 0x1101), Pair(0x1d19, 0x1102), Pair(0x1d19, 0x1103),
        Pair(0x1d19, 0x1104), Pair(0x1f4d, 0xa803), Pair(0x1f4d, 0xb803), Pair(0x1f4d, 0xc803), Pair(0x1f4d, 0xd286), Pair(0x1f4d, 0xd803)
    )),
    AIRSPY("Airspy", setOf(Pair(0x1d50, 0x60a1))),
    AIRSPYHF("AirspyHF", setOf(Pair(0x03eb, 0x800c))),
    HYDRASDR("HydraSDR", setOf(Pair(0x38af, 0x0001)));

    val permissionAction: String get() = "com.mantz_it.rfanalyzer.${name}_USB_PERMISSION"

    val sourceType: SourceType get() = when (this) {
        HACKRF -> SourceType.HACKRF
        RTLSDR -> SourceType.RTLSDR
        AIRSPY -> SourceType.AIRSPY
        AIRSPYHF -> SourceType.AIRSPYHF
        HYDRASDR -> SourceType.HYDRASDR
    }

    companion object {
        fun of(device: UsbDevice): UsbSdrType? =
            entries.firstOrNull { Pair(device.vendorId, device.productId) in it.ids }

        fun findAttached(usbManager: UsbManager): List<Pair<UsbDevice, UsbSdrType>> =
            usbManager.deviceList.values.mapNotNull { device -> of(device)?.let { Pair(device, it) } }

        fun deviceFromIntent(intent: Intent): UsbDevice? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                // else branch necessary as long as minSdk is < 33 (tiramisu)
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
    }
}

/**
 * Finds, requests permission for and opens one USB SDR of [type] on behalf of a source.
 * One instance per source instance.
 *
 * The native open does blocking USB control transfers (about 1 s for an RTL-SDR), so it always
 * runs on a background thread; completion is reported through onOpened/onError only.
 *
 * AnalyzerService creates a new source per Play press and drops the old one on Stop, so an
 * open still waiting for the permission dialog or running natively must never complete into a
 * source that was already closed: [cancel] bumps a generation that every late completion checks,
 * a superseded open closes what it just opened, and [cancel] waits for the open thread so the
 * USB claim is free before the next source tries to open the same device.
 */
internal class UsbSdrOpener(private val type: UsbSdrType, private val tag: String) {

    private var generation = 0
    private var openThread: Thread? = null
    private var permissionReceiver: BroadcastReceiver? = null
    private var receiverContext: Context? = null

    /**
     * Returns false if no matching device is attached. Otherwise returns true and later calls
     * exactly one of [onOpened] / [onError] (on the open thread), unless [cancel] came first.
     * [openNative] gets the USB file descriptor and returns (handle, error).
     */
    fun <D> open(
        context: Context,
        openNative: (fd: Int) -> Pair<D?, Any?>,
        closeNative: (D) -> Unit,
        onOpened: (D) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        val gen = ++generation
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = UsbSdrType.findAttached(usbManager).firstOrNull { it.second == type }?.first
        if (device == null) {
            Log.i(tag, "open: No ${type.displayName} device found.")
            usbManager.deviceList.values.forEach {
                Log.i(tag, "open: Unknown USB device: ${it.deviceName} (VendorId: ${it.vendorId}, ProductId: ${it.productId})")
            }
            return false
        }
        Log.i(tag, "open: device=${device.deviceName} (vendorId: ${device.vendorId} productId: ${device.productId})")

        if (usbManager.hasPermission(device)) {
            startOpenThread(device, context, gen, openNative, closeNative, onOpened, onError)
            return true
        }

        // Register the receiver BEFORE requesting permission
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                if (intent.action != type.permissionAction) return
                unregisterPermissionReceiver()
                val grantedDevice = UsbSdrType.deviceFromIntent(intent)
                if (gen != generation) {
                    Log.i(tag, "open: permission result after this attempt was superseded by close()/reopen - ignoring.")
                } else if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && grantedDevice != null) {
                    Log.i(tag, "USB permission granted for device: ${grantedDevice.productName}")
                    startOpenThread(grantedDevice, context, gen, openNative, closeNative, onOpened, onError)
                } else {
                    Log.w(tag, "USB permission denied for device: ${grantedDevice?.productName}")
                    // Reported as an error (not just a toast) so the analyzer leaves its
                    // start-pending state instead of waiting for an open that never comes.
                    onError("USB permission denied.")
                }
            }
        }
        unregisterPermissionReceiver()
        permissionReceiver = receiver
        receiverContext = context
        ContextCompat.registerReceiver(context, receiver, IntentFilter(type.permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Setting the package name makes the inner intent explicit, which Android 14+ requires
        // for mutable PendingIntents.
        val innerIntent = Intent(type.permissionAction).setPackage(context.packageName)
        val permissionIntent = PendingIntent.getBroadcast(context, 0, innerIntent, PendingIntent.FLAG_MUTABLE)
        Log.i(tag, "open: requesting permission for device: ${device.deviceName}")
        usbManager.requestPermission(device, permissionIntent)
        return true
    }

    /** Invalidates any open still in flight and waits for the open thread to finish. */
    fun cancel() {
        generation++
        unregisterPermissionReceiver()
        openThread?.let {
            if (it.isAlive && Thread.currentThread() != it) {
                try {
                    it.join(3000)
                } catch (_: InterruptedException) {
                }
            }
        }
        openThread = null
    }

    private fun unregisterPermissionReceiver() {
        val receiver = permissionReceiver ?: return
        permissionReceiver = null
        try {
            receiverContext?.unregisterReceiver(receiver)
        } catch (_: IllegalArgumentException) {
            // already unregistered
        }
        receiverContext = null
    }

    private fun <D> startOpenThread(
        device: UsbDevice,
        context: Context,
        gen: Int,
        openNative: (fd: Int) -> Pair<D?, Any?>,
        closeNative: (D) -> Unit,
        onOpened: (D) -> Unit,
        onError: (String) -> Unit,
    ) {
        openThread = Thread({
            openDevice(device, context, gen, openNative, closeNative, onOpened, onError)
        }, "$tag-Open-$gen").apply { start() }
    }

    private fun <D> openDevice(
        device: UsbDevice,
        context: Context,
        gen: Int,
        openNative: (fd: Int) -> Pair<D?, Any?>,
        closeNative: (D) -> Unit,
        onOpened: (D) -> Unit,
        onError: (String) -> Unit,
    ) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) {
            Log.w(tag, "openDevice: No permission to open ${type.displayName}.")
            if (gen == generation) onError("No permission to open ${type.displayName}")
            return
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.w(tag, "openDevice: Error opening USB connection.")
            if (gen == generation) onError("USB connection failed")
            return
        }

        val (handle, error) = openNative(connection.fileDescriptor)
        if (handle != null) {
            if (gen != generation) {
                Log.i(tag, "openDevice: open completed after being superseded - closing immediately.")
                closeNative(handle)
                connection.close() // the native side wraps the fd without owning it
                return
            }
            onOpened(handle)
            return
        }

        Log.w(tag, "openDevice: Failed to open ${type.displayName} ($error)")
        connection.close() // else the fd stays open until the process ends
        if (gen == generation) {
            onError("Failed to open ${type.displayName} ($error)")
            Handler(context.mainLooper).post {
                Toast.makeText(context, "Failed to open ${type.displayName} ($error)", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
