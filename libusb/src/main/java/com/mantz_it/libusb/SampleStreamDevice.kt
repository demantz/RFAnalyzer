package com.mantz_it.libusb

/**
 * <h1>RF Analyzer - Sample Stream Device</h1>
 *
 * Module:      SampleStreamDevice.kt
 * Description: The streaming part of a USB SDR driver, the same for all of them (the app's USB
 *              sources build on it). Device settings (gains, ports, ...) stay specific to each
 *              driver.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
interface SampleStreamDevice {
    /** Where the driver hands its transfers to the app. */
    val sampleBuffers: SampleBufferPool

    fun startRX(): Boolean
    fun stopRX(): Boolean

    /** False when not streaming, also after the device was lost while streaming. */
    fun isStreaming(): Boolean

    fun close(): Boolean
}
