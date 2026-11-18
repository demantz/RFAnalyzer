package com.mantz_it.rfanalyzer.source

import android.content.Context
import com.mantz_it.rfanalyzer_ipc.SamplePacket

/**
 * <h1>RF Analyzer - IQ Source Interface</h1>
 *
 * Module:      IQSourceInterface.kt
 * Description: This interface represents a source of IQ samples. It allows the Scheduler to
 *              be independent of the SDR hardware.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
interface IQSourceInterface {

    /**
     * Opens the device. This is usually asynchronous: [Callback.onIQSourceReady] tells when the
     * source is ready to use.
     *
     * @param context  needed to open external devices
     * @return false if an error occurred
     */
    fun open(context: Context, callback: Callback): Boolean

    /** True if the source is open and ready to use. */
    fun isOpen(): Boolean

    /** Closes the device; false if an error occurred. */
    fun close(): Boolean

    /** A human readable name of the source. */
    val name: String

    /** The rate at which this source receives samples. */
    var sampleRate: Int

    /** The frequency to which the source is tuned. */
    var frequency: Long

    /** The highest frequency to which the source can be tuned. */
    val maxFrequency: Long

    /** The lowest frequency to which the source can be tuned. */
    val minFrequency: Long

    /** All supported (optimal) sample rates, in ascending order. */
    val supportedSampleRates: IntArray

    /** The lowest supported sample rate higher than [sampleRate], or the highest supported one if there is none. */
    fun getNextHigherOptimalSampleRate(sampleRate: Int): Int {
        val rates = supportedSampleRates
        if (rates.isEmpty()) return sampleRate
        return rates.firstOrNull { it > sampleRate } ?: rates.last()
    }

    /** The highest supported sample rate lower than [sampleRate], or the lowest supported one if there is none. */
    fun getNextLowerOptimalSampleRate(sampleRate: Int): Int {
        val rates = supportedSampleRates
        if (rates.isEmpty()) return sampleRate
        return rates.lastOrNull { it < sampleRate } ?: rates.first()
    }

    /** The size (in bytes) of a packet returned by [getPacket]. */
    val packetSize: Int

    /** The number of bytes the source uses to encode a single sample. */
    val bytesPerSample: Int

    /**
     * The next packet from the source, or null if none arrived within [timeout] ms. Return the
     * packet to the buffer pool with [returnPacket] when it is no longer used.
     */
    fun getPacket(timeout: Int): ByteArray?

    /**
     * How long (ms) the Scheduler waits for the next packet before it treats the stream as ended.
     * A local SDR delivers steadily (and its source reports a stop itself); a network stream can
     * stall for seconds (Wi-Fi, a busy server) and then catch up, and reports a real end via
     * onIQSourceError - but keeps a limit, since a half-open socket goes unnoticed otherwise.
     */
    val maxPacketGapMs: Int get() = 1000

    /** Returns a packet that [getPacket] handed out to the source's buffer pool. */
    fun returnPacket(buffer: ByteArray)

    /**
     * How many bytes at the start of [packet] are sample data. Sources that hand out partially
     * filled pooled buffers (HydraSDR at decimated rates) override this; the rest of such a packet
     * is left over from an earlier fill.
     */
    fun getValidByteCount(packet: ByteArray): Int = packet.size

    /** Starts receiving samples. */
    fun startSampling()

    /** Stops receiving samples. */
    fun stopSampling()

    /**
     * Converts a packet from this source into interleaved float samples (I, Q, I, Q, ...),
     * normalized between -1 and 1, starting at index 0 of [interleavedBuffer] (the spectrum FFT
     * feed). False if [interleavedBuffer] is too small.
     */
    fun fillPacketIntoInterleavedBuffer(packet: ByteArray, interleavedBuffer: FloatArray): Boolean

    /**
     * Converts a packet from this source into [samplePacket] while mixing the signal so that
     * [channelFrequency] ends up at 0 Hz (floats normalized between -1 and 1). The samples are
     * appended from index samplePacket.size() on - set the size to 0 first to overwrite.
     *
     * @return the number of samples appended
     */
    fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long): Int

    /** Asynchronous notifications from the source. */
    interface Callback {
        /** The source is ready to use after [open]. */
        fun onIQSourceReady(source: IQSourceInterface)

        /** An error with the source, described by [message]. */
        fun onIQSourceError(source: IQSourceInterface, message: String)

        /**
         * Non-fatal information for the user (e.g. a tune request outside what the actual
         * hardware supports - the tune is still performed).
         */
        fun onIQSourceInformation(source: IQSourceInterface, message: String)
    }
}
