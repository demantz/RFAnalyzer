package com.mantz_it.rfanalyzer.source

import android.content.Context
import android.util.Log
import com.mantz_it.libusb.SampleBufferPool
import com.mantz_it.libusb.SampleStreamDevice
import com.mantz_it.rfanalyzer.analyzer.OverloadMonitor
import com.mantz_it.rfanalyzer.database.GlobalPerformanceData
import com.mantz_it.rfanalyzer_ipc.SamplePacket

/**
 * <h1>RF Analyzer - USB SDR Source</h1>
 *
 * Module:      UsbSdrSource.kt
 * Description: What the USB SDR sources (RTL-SDR, HackRF, Airspy, Airspy HF+, HydraSDR) have in
 *              common: opening the device (UsbSdrOpener), tuning, the packet stream from the
 *              driver's SampleBufferPool with its loss reporting, start/stop and the sample
 *              conversion. Subclasses add their device settings.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
abstract class UsbSdrSource<D : SampleStreamDevice>(
    usbType: UsbSdrType,
    protected val tag: String,
    protected val converter: IQConverter,
) : IQSourceInterface {

    companion object {
        // getPacket() checks this often whether a stream without packets has stopped
        private const val PACKET_POLL_MS = 100L
    }

    private val opener = UsbSdrOpener(usbType, tag)
    private val stallTracker = SourceStallTracker()
    private var lastDroppedSamples = 0L

    protected var device: D? = null
        private set
    protected var callback: IQSourceInterface.Callback? = null
        private set

    private var deviceSampleRate = 0
    // frequency the device is tuned to (without frequencyOffset)
    protected var tunedFrequency = 0L
        private set

    /** Shifts the displayed frequencies against the tuned one (frequency converters). */
    var frequencyOffset: Int = 0
        set(value) {
            field = value
            converter.frequency = tunedFrequency + value
        }

    // Serializes stream state changes (start/stop, a subclass' restart for a sample rate change)
    // with getPacket()'s check whether the stream stopped by itself.
    protected val streamLock = Any()

    // Between startSampling() and stopSampling(): a stream that isn't running then has failed.
    @Volatile
    protected var samplingActive = false
        private set

    /** Opens the driver on the USB file descriptor: (device, error). */
    protected abstract fun openDevice(fd: Int): Pair<D?, Any?>

    /** On the open thread, after the device opened and before onIQSourceReady(). */
    protected open fun onDeviceOpened(device: D) {}

    /** After close() released the device. */
    protected open fun onDeviceClosed() {}

    /** Sets the rate on the device; false when the driver rejects it (the device keeps its rate). */
    protected abstract fun applySampleRate(device: D, sampleRate: Int): Boolean

    protected abstract fun applyFrequency(device: D, frequency: Long)

    /** Writes the settings that aren't sample rate or frequency (gains, bias, ports, ...) before streaming starts. */
    protected abstract fun applySettings(device: D)

    /** After the stream started, outside [streamLock]. */
    protected open fun onStreamStarted(device: D) {}

    override fun open(context: Context, callback: IQSourceInterface.Callback): Boolean {
        if (device != null) {
            Log.w(tag, "open: device is already open.")
            return false
        }
        this.callback = callback
        return opener.open(
            context,
            openNative = { openDevice(it) },
            closeNative = { it.close() },
            onOpened = { handle ->
                device = handle
                onDeviceOpened(handle)
                callback.onIQSourceReady(this)
            },
            onError = { callback.onIQSourceError(this, it) },
        )
    }

    override fun isOpen(): Boolean = device != null

    override fun close(): Boolean {
        converter.close()
        opener.cancel() // invalidate and wait for any open still in flight (see UsbSdrOpener)
        val d = device ?: return true
        device = null
        onDeviceClosed()
        return d.close()
    }

    override var sampleRate: Int
        get() = deviceSampleRate
        set(newSampleRate) {
            val d = device
            if (d != null && !applySampleRate(d, newSampleRate)) {
                // The device goes on at the old rate: keep reporting that one, so the app (which writes
                // the sample rate back, AnalyzerService) doesn't show and process a rate it isn't getting.
                Log.w(tag, "setSampleRate: the device rejected $newSampleRate Sps - staying at $deviceSampleRate Sps")
                callback?.onIQSourceInformation(this, "$name doesn't support $newSampleRate Sps - staying at $deviceSampleRate Sps")
                return
            }
            deviceSampleRate = newSampleRate
            converter.sampleRate = newSampleRate
            d?.sampleBuffers?.flush()
        }

    override var frequency: Long
        get() = tunedFrequency + frequencyOffset
        set(newFrequency) {
            tunedFrequency = newFrequency - frequencyOffset
            device?.let { applyFrequency(it, tunedFrequency) }
            converter.frequency = newFrequency
            device?.sampleBuffers?.flush()
        }

    override fun startSampling() {
        val d = device
        if (d == null) {
            Log.w(tag, "startSampling: device is not open.")
            return
        }
        synchronized(streamLock) {
            if (!applySampleRate(d, deviceSampleRate)) {
                Log.e(tag, "startSampling: the device rejected $deviceSampleRate Sps")
                callback?.onIQSourceInformation(this, "$name doesn't support $deviceSampleRate Sps - pick another sample rate")
            }
            converter.sampleRate = deviceSampleRate
            applyFrequency(d, tunedFrequency)
            applySettings(d)
            d.sampleBuffers.flush()
            lastDroppedSamples = 0
            samplingActive = true
            if (!d.startRX()) Log.e(tag, "startSampling: starting the stream failed.")
        }
        onStreamStarted(d)
    }

    override fun stopSampling() {
        synchronized(streamLock) {
            samplingActive = false
            device?.stopRX()
        }
    }

    /**
     * Waits up to [timeout] ms for a packet. Null after a stop, when no packet came in time, or
     * when the stream stopped by itself (reported as a source error); a sample-rate change in
     * between is waited out.
     */
    override fun getPacket(timeout: Int): ByteArray? {
        val d = device ?: return null
        val buffers = d.sampleBuffers
        val deadlineNs = System.nanoTime() + timeout * 1_000_000L
        while (true) {
            val packet = buffers.takeFilled(PACKET_POLL_MS)
            if (packet != null) {
                reportLosses(buffers)
                return packet
            }
            if (synchronized(streamLock) { samplingActive && !d.isStreaming() }) {
                Log.w(tag, "getPacket: no packet and the device is not streaming anymore.")
                callback?.onIQSourceError(this, "$name stopped streaming unexpectedly (USB connection lost?). Reconnect it to restart the analyzer.")
                return null
            }
            if (!samplingActive || System.nanoTime() > deadlineNs) return null
        }
    }

    // Samples the device delivered but nobody received: driver drops and waits for a free buffer.
    private fun reportLosses(buffers: SampleBufferPool) {
        stallTracker.update(buffers.emptyBufferStalls.get(), buffers.emptyBufferStallNs.get())
        val dropped = buffers.droppedSamples
        if (dropped < lastDroppedSamples) lastDroppedSamples = 0 // a new stream started counting
        if (dropped > lastDroppedSamples && deviceSampleRate > 0) {
            GlobalPerformanceData.incrementCounter("source.droppedSamples", dropped - lastDroppedSamples)
            OverloadMonitor.reportLoss("Source", (dropped - lastDroppedSamples) * 1_000_000_000L / deviceSampleRate)
        }
        lastDroppedSamples = dropped
    }

    override fun returnPacket(buffer: ByteArray) {
        device?.sampleBuffers?.returnBuffer(buffer)
    }

    override fun getValidByteCount(packet: ByteArray): Int = device?.sampleBuffers?.validByteCount(packet) ?: packet.size

    /** Buffers idle in the driver's pool (diagnostics for instrumented tests), -1 if not open. */
    fun availableBufferCount(): Int = device?.sampleBuffers?.availableCount() ?: -1

    override fun fillPacketIntoInterleavedBuffer(packet: ByteArray, interleavedBuffer: FloatArray): Boolean {
        return converter.fillPacketIntoInterleavedBuffer(packet, interleavedBuffer, getValidByteCount(packet))
    }

    override fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long): Int {
        return converter.mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency, getValidByteCount(packet))
    }
}
