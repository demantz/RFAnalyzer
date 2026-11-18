package com.mantz_it.libusb

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * <h1>RF Analyzer - Sample Buffer Pool</h1>
 *
 * Module:      SampleBufferPool.kt
 * Description: The hand-off of USB sample transfers from a driver's native RX thread to the app,
 *              shared by all USB SDR drivers. A fixed set of buffers cycles between the driver's
 *              callback (takeEmpty -> fill -> putFilled, see sample_handoff.h) and the consumer
 *              (takeFilled -> process -> returnBuffer).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class SampleBufferPool(bufferSize: Int, poolSize: Int = DEFAULT_POOL_SIZE) {

    companion object {
        const val DEFAULT_POOL_SIZE = 70 // ~0.5 s at 10 MSps with 256 KB transfers

        // How long the driver's callback waits for a free buffer before the transfer is dropped:
        // an unbounded wait would also block the driver's stop, which joins the callback's thread.
        const val EMPTY_BUFFER_WAIT_MS = 500L
    }

    private val available = ArrayBlockingQueue<ByteArray>(poolSize)
    private val filled = ArrayBlockingQueue<ByteArray>(poolSize)

    // Bytes of new sample data per filled buffer: a transfer can hold fewer than the buffer size
    // (HydraSDR below its hardware rate), the rest is content of the buffer's previous fill.
    private val validByteCounts = ConcurrentHashMap<ByteArray, Int>()

    /**
     * How often and how long the driver's callback had to wait for a free buffer because the
     * consumer didn't return them fast enough. Meanwhile the device keeps sending, and whatever
     * doesn't fit into the driver's own buffers is lost.
     */
    val emptyBufferStalls = AtomicLong(0)
    val emptyBufferStallNs = AtomicLong(0)

    /** The driver's cumulative count of samples it dropped in the current stream (0 if it doesn't report drops). */
    @Volatile
    var droppedSamples: Long = 0
        private set

    init {
        repeat(poolSize) { available.put(ByteArray(bufferSize)) }
    }

    /** Driver callback: a free buffer, or null if none came back within [EMPTY_BUFFER_WAIT_MS] (the transfer is dropped). */
    fun takeEmpty(): ByteArray? {
        available.poll()?.let { return it }
        val startNs = System.nanoTime()
        val buffer = available.poll(EMPTY_BUFFER_WAIT_MS, TimeUnit.MILLISECONDS)
        emptyBufferStalls.incrementAndGet()
        emptyBufferStallNs.addAndGet(System.nanoTime() - startNs)
        return buffer
    }

    /** Driver callback: the first [validByteCount] bytes of [buffer] hold new samples. */
    fun putFilled(buffer: ByteArray, validByteCount: Int, droppedSamples: Long) {
        validByteCounts[buffer] = validByteCount
        this.droppedSamples = droppedSamples
        filled.offer(buffer) // never full: it can hold every buffer of the pool
    }

    /** Consumer: the next filled buffer, or null after [timeoutMs]. */
    fun takeFilled(timeoutMs: Long): ByteArray? = filled.poll(timeoutMs, TimeUnit.MILLISECONDS)

    /** Consumer: gives a buffer from [takeFilled] back to the pool. */
    fun returnBuffer(buffer: ByteArray) {
        validByteCounts.remove(buffer)
        available.offer(buffer)
    }

    /** How many bytes of [buffer] (from [takeFilled]) are new samples. */
    fun validByteCount(buffer: ByteArray): Int = validByteCounts[buffer] ?: buffer.size

    /** Discards the filled buffers not taken yet, e.g. samples from before a retune. */
    fun flush() {
        while (true) returnBuffer(filled.poll() ?: return)
    }

    /** Buffers idle in the pool; staying at 0 means the consumer can't keep up. */
    fun availableCount(): Int = available.size
}
