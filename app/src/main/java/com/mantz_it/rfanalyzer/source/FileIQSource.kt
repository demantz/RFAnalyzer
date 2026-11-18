package com.mantz_it.rfanalyzer.source

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.mantz_it.rfanalyzer.ui.tabs.FilesourceFileFormat
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import kotlin.math.min

/**
 * <h1>RF Analyzer - File Source of IQ samples</h1>
 *
 * Module:      FileIQSource.kt
 * Description: Plays an IQ recording (a file or a content Uri) as if it were received: packets of
 *              1/20 s, paced in real time, stamped with the recording's start time plus their
 *              place in the file. Optionally starts over at the end (repeat).
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class FileIQSource : IQSourceInterface {
    private var callback: IQSourceInterface.Callback? = null
    private var uri: Uri? = null
    private var contentResolver: ContentResolver? = null
    private var inputStream: BufferedInputStream? = null
    private var buffer = ByteArray(0)
    private var fileSampleRate = 1_000_000
    private var fileFrequency = 1L
    private var recordingStartTimestamp = 0L // UTC time (ms) of the file's first sample
    private var iqConverter: IQConverter? = null
    private var startTime = 0L               // System.nanoTime() when sampling started
    private var bytesRead = 0L               // bytes read since sampling started
    private var filePosition = 0L            // byte offset in the file of the next read
    private var packetFilePosition = 0L      // byte offset in the file of the packet in the buffer
    private var lastTooSlowLogMs = 0L        // the "too slow for the sample rate" log line, at most every 5 s
    private var tooSlowLogsSuppressed = 0

    /** Starts over at the end of the file. */
    var isRepeat = false

    /** The sample encoding of the file. */
    var fileFormat: FilesourceFileFormat = FilesourceFileFormat.RTLSDR
        set(value) {
            field = value
            val converter = when (value) {
                FilesourceFileFormat.HACKRF -> Signed8BitIQConverter()
                FilesourceFileFormat.RTLSDR -> Unsigned8BitIQConverter()
                FilesourceFileFormat.AIRSPY, FilesourceFileFormat.HYDRASDR -> Signed16BitIQConverter()
                FilesourceFileFormat.AIRSPYHF -> Float32IQConverter()
            }
            converter.frequency = fileFrequency
            converter.sampleRate = fileSampleRate
            iqConverter?.close() // free the old converter's native resources
            iqConverter = converter
        }

    /**
     * @param recordingStartTimestamp UTC time (ms) at which the file's first sample was received:
     *                                packets are stamped with it plus their position in the file
     */
    fun init(uri: Uri, contentResolver: ContentResolver, sampleRate: Int, frequency: Long, repeat: Boolean,
             fileFormat: FilesourceFileFormat, recordingStartTimestamp: Long): Boolean {
        this.uri = uri
        this.contentResolver = contentResolver
        this.isRepeat = repeat
        this.fileSampleRate = sampleRate
        this.fileFrequency = frequency
        this.fileFormat = fileFormat
        this.packetSize = sampleRate * bytesPerSample / 20 // 20 packets per second
        this.buffer = ByteArray(packetSize)
        this.recordingStartTimestamp = recordingStartTimestamp
        return true
    }

    private fun reportError(msg: String) {
        callback?.onIQSourceError(this, msg) ?: Log.e(LOGTAG, "Callback is null when reporting Error ($msg)")
    }

    private fun openFile(): Boolean {
        val uri = uri ?: return false
        return try {
            val stream: InputStream = if (uri.scheme == "content")
                contentResolver?.openInputStream(uri) ?: throw IOException("no stream for $uri")
            else
                FileInputStream(uri.path)
            inputStream = BufferedInputStream(stream)
            true
        } catch (e: IOException) {
            Log.e(LOGTAG, "openFile: Error while opening file: ${e.message}")
            reportError("Error while opening file: ${e.message}")
            false
        } catch (e: SecurityException) {
            Log.e(LOGTAG, "openFile: Security Exception: ${e.message}")
            reportError("Error while opening file: ${e.message}")
            false
        }
    }

    override fun open(context: Context, callback: IQSourceInterface.Callback): Boolean {
        this.callback = callback // before reporting: reportError() only logs without it
        if (uri == null || contentResolver == null) {
            Log.e(LOGTAG, "open: Error: Uri or contentResolver are null")
            reportError("Error: File Source not correctly initialized")
            return false
        }
        filePosition = 0
        if (!openFile()) return false
        callback.onIQSourceReady(this)
        return true
    }

    // Open while there is a stream (not by available(): that is 0 at the end of the file and for
    // content streams that don't know their size).
    override fun isOpen(): Boolean = inputStream != null

    override fun close(): Boolean {
        iqConverter?.close()
        return try {
            inputStream?.close()
            true
        } catch (e: IOException) {
            Log.e(LOGTAG, "close: Error while closing file: ${e.message}")
            reportError("Unexpected error while closing file: ${e.message}")
            false
        }
    }

    override val name: String
        get() = filename?.let { "IQ-File: $it" } ?: "IQ-File (uninitialized)"

    /** The file name of the recording, null if unknown. */
    val filename: String?
        get() {
            val uri = uri ?: return null
            val resolver = contentResolver
            if (uri.scheme != "content" || resolver == null) return uri.lastPathSegment
            // Also read while reporting that the file could not be opened, so a file that is gone
            // (empty cursor) or no longer accessible must not throw here.
            return try {
                resolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                }
            } catch (e: RuntimeException) {
                Log.e(LOGTAG, "filename: Cannot query file name: ${e.message}")
                null
            }
        }

    override var sampleRate: Int
        get() = fileSampleRate
        set(_) { Log.e(LOGTAG, "Setting the sample rate is not supported on a file source") }

    override var frequency: Long
        get() = fileFrequency
        set(_) { Log.e(LOGTAG, "Setting the frequency is not supported on a file source") }

    override val maxFrequency: Long get() = fileFrequency

    override val minFrequency: Long get() = fileFrequency

    override val supportedSampleRates: IntArray get() = intArrayOf(fileSampleRate)

    override var packetSize: Int = 0
        private set

    override val bytesPerSample: Int get() = fileFormat.bytesPerSample

    override fun getPacket(timeout: Int): ByteArray? {
        val stream = inputStream ?: return null
        var packet: ByteArray? = null
        try {
            if (readFully(stream, buffer) == buffer.size) {
                bytesRead += buffer.size
                packetFilePosition = filePosition
                filePosition += buffer.size
                packet = buffer
            } else if (isRepeat) {
                // rewind and try again:
                Log.i(LOGTAG, "getPacket: End of File. Rewind!")
                stream.close()
                if (!openFile()) {
                    Log.e(LOGTAG, "getPacket: Error while reopening file.")
                    reportError("Error while re-opening file")
                    return null
                }
                if (readFully(inputStream!!, buffer) == buffer.size) {
                    bytesRead += buffer.size
                    packetFilePosition = 0
                    filePosition = buffer.size.toLong()
                    packet = buffer
                }
            } else {
                Log.i(LOGTAG, "getPacket: End of File")
                reportError("End of File")
            }

            // Simulate sample rate of real hardware:
            val timePerSample = 1_000_000_000.0 / fileSampleRate
            val expectedTime = startTime + (timePerSample * bytesRead / bytesPerSample).toLong()
            val sleep = min(expectedTime - System.nanoTime(), timeout * 1_000_000L)
            if (sleep > 0) {
                Thread.sleep(sleep / 1_000_000, (sleep % 1_000_000).toInt())
            } else if (System.currentTimeMillis() - lastTooSlowLogMs > 5000) {
                Log.w(LOGTAG, "getPacket: Too slow for the sample rate! sleep=$sleep (bytesRead: $bytesRead) [suppressed log lines: $tooSlowLogsSuppressed]")
                tooSlowLogsSuppressed = 0
                lastTooSlowLogMs = System.currentTimeMillis()
            } else {
                tooSlowLogsSuppressed++
            }
        } catch (e: IOException) {
            Log.e(LOGTAG, "getPacket: Error while reading from file: ${e.message}")
            reportError("Unexpected error while reading file: ${e.message}")
        } catch (e: InterruptedException) {
            Log.w(LOGTAG, "getPacket: Interrupted while sleeping!")
        }
        return packet
    }

    override fun returnPacket(buffer: ByteArray) {
        // one buffer, refilled by the next getPacket()
    }

    override fun startSampling() {
        startTime = System.nanoTime()
        bytesRead = 0
    }

    override fun stopSampling() {
        // nothing to do here...
    }

    override fun fillPacketIntoInterleavedBuffer(packet: ByteArray, interleavedBuffer: FloatArray): Boolean =
        iqConverter?.fillPacketIntoInterleavedBuffer(packet, interleavedBuffer) ?: false

    override fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long): Int =
        restamp(samplePacket, iqConverter?.mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency) ?: 0)

    /** The packet's samples were received at the recording's start plus their position in the file (not when they are played). */
    private fun restamp(samplePacket: SamplePacket, appended: Int): Int {
        val bytesPerSample = bytesPerSample
        if (appended > 0 && bytesPerSample > 0 && fileSampleRate > 0)
            IQConverter.stampFromSource(samplePacket, appended,
                recordingStartTimestamp + packetFilePosition / bytesPerSample * 1000 / fileSampleRate)
        return appended
    }

    private companion object {
        const val LOGTAG = "FileIQSource"

        /**
         * Reads until buffer is full or the stream ends. A single read() may return fewer bytes
         * without being at the end (BufferedInputStream stops when available() is 0, e.g. for a
         * pipe- or network-backed content provider); only a short count from here means end of file.
         */
        fun readFully(stream: InputStream, buffer: ByteArray): Int {
            var total = 0
            while (total < buffer.size) {
                val n = stream.read(buffer, total, buffer.size - total)
                if (n < 0) break
                total += n
            }
            return total
        }
    }
}
