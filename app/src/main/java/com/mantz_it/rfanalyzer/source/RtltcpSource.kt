package com.mantz_it.rfanalyzer.source

import android.content.Context
import android.util.Log
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * <h1>RF Analyzer - rtl_tcp source</h1>
 *
 * Module:      RtltcpSource.kt
 * Description: TCP client for the rtl_tcp wire protocol. Used to connect to an
 *              external rtl_tcp server (local driver app or remote instance).
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class RtltcpSource(val ipAddress: String, val port: Int) : IQSourceInterface {

    companion object {
        const val RTLSDR_TUNER_UNKNOWN = 0
        const val RTLSDR_TUNER_E4000 = 1
        const val RTLSDR_TUNER_FC0012 = 2
        const val RTLSDR_TUNER_FC0013 = 3
        const val RTLSDR_TUNER_FC2580 = 4
        const val RTLSDR_TUNER_R820T = 5
        const val RTLSDR_TUNER_R828D = 6
        val TUNER_STRING = arrayOf("UNKNOWN", "E4000", "FC0012", "FC0013", "FC2580", "R820T", "R828D")

        const val RTL_TCP_COMMAND_SET_FREQUENCY = 0x01
        const val RTL_TCP_COMMAND_SET_SAMPLERATE = 0x02
        const val RTL_TCP_COMMAND_SET_GAIN_MODE = 0x03
        const val RTL_TCP_COMMAND_SET_GAIN = 0x04
        const val RTL_TCP_COMMAND_SET_FREQ_CORR = 0x05
        const val RTL_TCP_COMMAND_SET_IFGAIN = 0x06
        const val RTL_TCP_COMMAND_SET_AGC_MODE = 0x08
        const val RTL_TCP_COMMAND_SET_DIRECT_SAMPLING_MODE = 0x09
        /** Shuts the rtl_tcp driver down. */
        private const val RTL_TCP_COMMAND_SHUTDOWN = 0x7e
        private val COMMAND_NAME = arrayOf("invalid", "SET_FREQUENCY", "SET_SAMPLERATE", "SET_GAIN_MODE",
            "SET_GAIN", "SET_FREQ_CORR", "SET_IFGAIN", "SET_TEST_MODE", "SET_AGC_MODE", "SET_DIRECT_SAMPLING_MODE")

        private const val LOGTAG = "RtltcpSource"
        private const val QUEUE_SIZE = 20
        private const val COMMAND_QUEUE_SIZE = 20
        val OPTIMAL_SAMPLE_RATES: IntArray = RtlsdrSource.OPTIMAL_SAMPLE_RATES
        // Deliberately no limit, as RtlsdrSource. Unlike it, this class can't identify the actual
        // dongle (only what the rtl_tcp server reports), so it doesn't report out-of-range tunes.
        const val MIN_FREQUENCY = 0L
        const val MAX_FREQUENCY = 3_000_000_000L // 3 GHz
        val POSSIBLE_GAIN_VALUES = arrayOf( // tenths of a dB, as in gr-osmosdr's rtl_tcp_source_c.cc
            intArrayOf(0),                                                              // invalid
            intArrayOf(-10, 15, 40, 65, 90, 115, 140, 165, 190, 215, 240, 290, 340, 420), // E4000
            intArrayOf(-99, -40, 71, 179, 192),                                         // FC0012
            intArrayOf(-99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67, 68,
                70, 71, 179, 181, 182, 184, 186, 188, 191, 197),                        // FC0013
            intArrayOf(0),                                                              // FC2580
            intArrayOf(0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280,
                297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496),       // R820T
            intArrayOf(0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254, 280,
                297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 483, 496),  // R828D
        )
        const val PACKET_SIZE = 16384

        // A network stream: see IQSourceInterface.maxPacketGapMs. rtl_tcp's receiver thread can
        // stop without reporting it, so the gap stays bounded.
        const val NETWORK_MAX_PACKET_GAP_MS = 15_000

        /** A command: its code and a 32-bit argument, big-endian (rtl_tcp's 5-byte format). */
        private fun command(command: Int, arg: Int) = byteArrayOf(
            command.toByte(), (arg shr 24).toByte(), (arg shr 16).toByte(), (arg shr 8).toByte(), arg.toByte())

        /** A command with two 16-bit arguments (big-endian). */
        private fun command(command: Int, arg1: Short, arg2: Short) = byteArrayOf(
            command.toByte(), (arg1.toInt() shr 8).toByte(), arg1.toByte(), (arg2.toInt() shr 8).toByte(), arg2.toByte())

        private fun commandName(command: ByteArray?): String {
            val code = command?.get(0)?.toInt() ?: return "(null)"
            return COMMAND_NAME.getOrElse(code) { "0x%02x".format(code) }
        }
    }

    private var receiverThread: ReceiverThread? = null
    @Volatile private var commandThread: CommandThread? = null
    private var callback: IQSourceInterface.Callback? = null
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var sourceName = "RTL-SDR"
    private var magic: String? = null
    private var tuner = RTLSDR_TUNER_UNKNOWN
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_SIZE)
    private val returnQueue = ArrayBlockingQueue<ByteArray>(QUEUE_SIZE).apply { repeat(QUEUE_SIZE) { offer(ByteArray(PACKET_SIZE)) } }
    private var tunedFrequency = 0L        // without frequencyOffset
    private var currentSampleRate = 0
    // How often/long the ReceiverThread had to wait for a free buffer because the app wasn't returning
    // them fast enough. Meanwhile nothing is read from the socket, so the server falls behind and
    // eventually drops samples - forwarded to the app's overload detection by getPacket().
    private val receiveStalls = AtomicLong(0)
    private val receiveStallNs = AtomicLong(0)
    private val stallTracker = SourceStallTracker()
    private var directSamplingMode = 0     // 0 = off; 1 = I; 2 = Q
    private val iqConverter: IQConverter = Unsigned8BitIQConverter()

    /** Forwards an error message to the callback. */
    private fun reportError(msg: String) {
        callback?.onIQSourceError(this, msg) ?: Log.e(LOGTAG, "reportError: Callback is null. (Error: $msg)")
    }

    /** Connects to the rtl_tcp server (in a background thread; the callback reports the result). */
    override fun open(context: Context, callback: IQSourceInterface.Callback): Boolean {
        this.callback = callback
        // The command thread performs the "open" procedure: connecting to the rtl_tcp instance,
        // reading its information and informing the callback.
        if (commandThread != null) {
            Log.e(LOGTAG, "open: Command thread is still running")
            reportError("Error while opening device")
            return false
        }
        commandThread = CommandThread().also { it.start() }
        return true
    }

    override fun isOpen(): Boolean = commandThread != null

    override fun close(): Boolean {
        iqConverter.close()

        // Stop receiving:
        if (receiverThread != null) stopSampling()

        // Stop the command thread:
        commandThread?.let { thread ->
            thread.stopCommandThread()
            if (Thread.currentThread() !== thread) {
                Log.d(LOGTAG, "close: joining command thread from Thread: ${Thread.currentThread().name}")
                try {
                    thread.join()
                    Log.d(LOGTAG, "close: command thread successfully joined")
                } catch (_: InterruptedException) {
                }
            }
            commandThread = null
        }

        tuner = RTLSDR_TUNER_UNKNOWN
        magic = null
        sourceName = "RTL-SDR"
        return true
    }

    override val name: String get() = sourceName

    override var sampleRate: Int
        get() = currentSampleRate
        set(value) {
            if (isOpen()) {
                if (value < minSampleRate || value > maxSampleRate) {
                    Log.e(LOGTAG, "setSampleRate: Sample rate out of valid range: $value")
                    return
                }
                if (commandThread?.executeCommand(command(RTL_TCP_COMMAND_SET_SAMPLERATE, value)) != true)
                    Log.e(LOGTAG, "setSampleRate: failed.")
            }
            flushQueue()
            currentSampleRate = value
            iqConverter.sampleRate = value
        }

    override var frequency: Long
        get() = tunedFrequency + frequencyOffset
        set(value) {
            val actualSourceFrequency = value - frequencyOffset
            if (isOpen())
                commandThread?.executeFrequencyChangeCommand(command(RTL_TCP_COMMAND_SET_FREQUENCY, actualSourceFrequency.toInt()))
            flushQueue()
            tunedFrequency = actualSourceFrequency
            iqConverter.frequency = value
        }

    override val maxFrequency: Long get() = MAX_FREQUENCY + frequencyOffset

    override val minFrequency: Long get() = MIN_FREQUENCY + frequencyOffset

    val maxSampleRate: Int get() = OPTIMAL_SAMPLE_RATES.last()

    val minSampleRate: Int get() = OPTIMAL_SAMPLE_RATES.first()

    override val supportedSampleRates: IntArray get() = OPTIMAL_SAMPLE_RATES

    /** true == manual; false == automatic */
    var isManualGain = true
        set(value) {
            if (isOpen() && commandThread?.executeCommand(command(RTL_TCP_COMMAND_SET_GAIN_MODE, if (value) 0x01 else 0x00)) != true)
                Log.e(LOGTAG, "setManualGain: failed.")
            field = value
        }

    private var gainValue = 0
    var gain: Int
        get() = gainValue
        set(value) {
            if (isOpen() && commandThread?.executeCommand(command(RTL_TCP_COMMAND_SET_GAIN, value)) != true)
                Log.e(LOGTAG, "setGain: failed.")
            gainValue = value
        }

    val possibleGainValues: IntArray get() = POSSIBLE_GAIN_VALUES[tuner]

    var ifGain = 0
        set(value) {
            if (isOpen() && tuner == RTLSDR_TUNER_E4000 &&
                commandThread?.executeCommand(command(RTL_TCP_COMMAND_SET_IFGAIN, 0.toShort(), value.toShort())) != true)
                Log.e(LOGTAG, "setIFGain: failed.")
            field = value
        }

    val possibleIFGainValues: IntArray
        get() = if (tuner == RTLSDR_TUNER_E4000) IntArray(54) { it + 3 } else intArrayOf(0)

    /** Frequency correction (ppm). */
    var frequencyCorrection = 0
        set(value) {
            if (isOpen() && commandThread?.executeCommand(command(RTL_TCP_COMMAND_SET_FREQ_CORR, value)) != true)
                Log.e(LOGTAG, "setFrequencyCorrection: failed.")
            field = value
        }

    var isAutomaticGainControl = false
        set(value) {
            if (isOpen() && commandThread?.executeCommand(command(RTL_TCP_COMMAND_SET_AGC_MODE, if (value) 0x01 else 0x00)) != true)
                Log.e(LOGTAG, "setAutomaticGainControl: failed.")
            field = value
        }

    /** Virtually offsets the frequency according to an external up/down-converter. */
    var frequencyOffset = 0
        set(value) {
            field = value
            iqConverter.frequency = tunedFrequency + value
        }

    /** 0 = off; 1 = I; 2 = Q */
    fun setDirectSamplingMode(value: Int) {
        Log.d(LOGTAG, "setDirectSamplingMode value = $value")
        if (value < 0 || value > 2) return
        if (isOpen() && commandThread?.executeCommand(command(RTL_TCP_COMMAND_SET_DIRECT_SAMPLING_MODE, value)) != true)
            Log.e(LOGTAG, "setDirectSamplingMode: failed.")
        directSamplingMode = value
    }

    override val packetSize: Int get() = PACKET_SIZE

    override val bytesPerSample: Int get() = 2

    override val maxPacketGapMs: Int get() = NETWORK_MAX_PACKET_GAP_MS

    override fun getPacket(timeout: Int): ByteArray? {
        stallTracker.update(receiveStalls.get(), receiveStallNs.get())
        return try {
            queue.poll(timeout.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Log.e(LOGTAG, "getPacket: Interrupted while polling packet from queue: ${e.message}")
            null
        }
    }

    override fun returnPacket(buffer: ByteArray) {
        returnQueue.offer(buffer)
    }

    override fun startSampling() {
        if (receiverThread != null) {
            Log.e(LOGTAG, "startSampling: receiver thread still running.")
            reportError("Could not start sampling")
            return
        }
        val stream = inputStream
        if (isOpen() && stream != null) {
            receiverThread = ReceiverThread(stream).also { it.start() }
        }
    }

    override fun stopSampling() {
        val thread = receiverThread ?: return
        thread.stopReceiving()
        if (Thread.currentThread() !== thread) {
            Log.d(LOGTAG, "stopSampling: joining receiver thread from Thread: ${Thread.currentThread().name}")
            try {
                thread.join()
                Log.d(LOGTAG, "stopSampling: receiver thread successfully joined")
            } catch (e: InterruptedException) {
                Log.w(LOGTAG, "stopSampling: Interrupted while joining receiver thread: ${e.message}")
            }
        }
        receiverThread = null
    }

    override fun fillPacketIntoInterleavedBuffer(packet: ByteArray, interleavedBuffer: FloatArray): Boolean =
        iqConverter.fillPacketIntoInterleavedBuffer(packet, interleavedBuffer)

    override fun mixPacketIntoSamplePacket(packet: ByteArray, samplePacket: SamplePacket, channelFrequency: Long): Int =
        iqConverter.mixPacketIntoSamplePacket(packet, samplePacket, channelFrequency)

    /** Empties the queue. */
    fun flushQueue() {
        repeat(QUEUE_SIZE) {
            returnPacket(queue.poll() ?: return) // done once the queue is empty
        }
    }

    /** Reads samples from the socket and puts them into the queue. */
    private inner class ReceiverThread(private val input: InputStream) : Thread() {
        @Volatile private var stopRequested = false

        fun stopReceiving() {
            stopRequested = true
        }

        override fun run() {
            var buffer: ByteArray? = null
            var index = 0
            name = "Thread-Rtlsdr-ReceiverThread-${System.currentTimeMillis()}"
            Log.i(LOGTAG, "ReceiverThread started (Thread: $name)")

            while (!stopRequested) {
                try {
                    // if buffer is null we request a new buffer from the return queue:
                    if (buffer == null) {
                        buffer = returnQueue.poll()
                        if (buffer == null) {
                            val startNs = System.nanoTime()
                            buffer = returnQueue.poll(1000, TimeUnit.MILLISECONDS)
                            receiveStalls.incrementAndGet()
                            receiveStallNs.addAndGet(System.nanoTime() - startNs)
                        }
                        index = 0
                    }
                    if (buffer == null) {
                        Log.e(LOGTAG, "ReceiverThread: Couldn't get buffer from input queue. stop.")
                        break
                    }

                    // Read into the buffer from the input stream:
                    val bytesRead = input.read(buffer, index, buffer.size - index)
                    if (bytesRead <= 0) {
                        Log.e(LOGTAG, "ReceiverThread: Couldn't read data from input stream. stop.")
                        break
                    }
                    index += bytesRead
                    if (index == buffer.size) {
                        // buffer is full. Send it to the queue:
                        queue.offer(buffer)
                        buffer = null
                    }
                } catch (e: SocketTimeoutException) {
                    // No samples for a second (the socket's timeout): a network stall, or the stop
                    // request this loop checks now. How long a stall may last is the Scheduler's
                    // call (maxPacketGapMs) - it stops the analyzer when the server stays silent.
                    continue
                } catch (e: InterruptedException) {
                    Log.e(LOGTAG, "ReceiverThread: Interrupted while waiting: ${e.message}")
                    break
                } catch (e: IOException) {
                    if (stopRequested) break // the socket closed under a stop: no error
                    Log.e(LOGTAG, "ReceiverThread: Error while reading from socket: ${e.message}")
                    reportError("Error while receiving samples.")
                    break
                }
            }
            stopRequested = true
            // still holding a buffer: return it to the pool
            buffer?.let { returnQueue.offer(it) }
            Log.i(LOGTAG, "ReceiverThread stopped (Thread: $name)")
        }
    }

    /**
     * Connects to the rtl_tcp instance and then sends commands to it. Commands can be queued for
     * execution by other threads.
     */
    private inner class CommandThread : Thread() {
        private val commandQueue = ArrayBlockingQueue<ByteArray>(COMMAND_QUEUE_SIZE)
        // Frequency changes happen very often, and too many of these commands make the driver lag
        // and eventually crash. They get a queue of their own of size 1, which always holds only
        // the latest one (executeFrequencyChangeCommand()).
        private val frequencyChangeCommandQueue = ArrayBlockingQueue<ByteArray>(1)
        @Volatile private var stopRequested = false

        fun stopCommandThread() {
            stopRequested = true
        }

        /** Schedules [command] (5 bytes, see the rtl_tcp documentation); false if the queue is full. */
        fun executeCommand(command: ByteArray): Boolean {
            Log.d(LOGTAG, "executeCommand: Queuing command: ${commandName(command)}")
            if (commandQueue.offer(command)) return true
            Log.e(LOGTAG, "executeCommand: command queue is full!")
            return false
        }

        /** Replaces any waiting frequency change with [command] (see frequencyChangeCommandQueue). */
        fun executeFrequencyChangeCommand(command: ByteArray) {
            frequencyChangeCommandQueue.poll()
            frequencyChangeCommandQueue.offer(command) // will always work
        }

        /** Sets up the connection to the rtl_tcp instance (called from run()). */
        private fun connect(timeoutMillis: Int): Boolean {
            if (socket != null) {
                Log.e(LOGTAG, "connect: Socket is still connected")
                return false
            }
            try {
                val timeoutTime = System.currentTimeMillis() + timeoutMillis
                while (!stopRequested && socket == null && System.currentTimeMillis() < timeoutTime) {
                    try {
                        val s = Socket()
                        val sockAddr = InetSocketAddress(ipAddress, port)
                        Log.d(LOGTAG, "CommandThread: (connect) connecting to $sockAddr")
                        s.connect(sockAddr, 500)
                        socket = s
                    } catch (e: IOException) {
                        Log.d(LOGTAG, "CommandThread: (connect) IOException: ${e.message}")
                        socket = null
                    }
                    sleep(100)
                }
                val s = socket
                if (s == null) {
                    if (stopRequested) Log.i(LOGTAG, "CommandThread: (connect) command thread stopped while connecting.")
                    else Log.e(LOGTAG, "CommandThread: (connect) hit timeout")
                    return false
                }

                // Set socket options:
                s.tcpNoDelay = true
                s.soTimeout = 1000

                val input = s.getInputStream()
                inputStream = input
                outputStream = s.getOutputStream()
                val buffer = ByteArray(4)

                // Read magic value:
                if (input.read(buffer, 0, buffer.size) != buffer.size) {
                    Log.e(LOGTAG, "CommandThread: (connect) Could not read magic value")
                    return false
                }
                magic = String(buffer, Charsets.US_ASCII)

                // Read tuner type:
                if (input.read(buffer, 0, buffer.size) != buffer.size) {
                    Log.e(LOGTAG, "CommandThread: (connect) Could not read tuner type")
                    return false
                }
                tuner = buffer[3].toInt()
                if (tuner <= 0 || tuner >= TUNER_STRING.size) {
                    Log.e(LOGTAG, "CommandThread: (connect) Invalid tuner type")
                    return false
                }

                // Read gain count (only logged)
                if (input.read(buffer, 0, buffer.size) != buffer.size) {
                    Log.e(LOGTAG, "CommandThread: (connect) Could not read gain count")
                    return false
                }

                Log.i(LOGTAG, "CommandThread: (connect) Connected to RTL-SDR (Tuner: ${TUNER_STRING[tuner]};  magic: $magic;  " +
                        "gain count: ${buffer[3]}) at $ipAddress:$port")

                // Update source name with the new information:
                sourceName = "RTL-SDR (${TUNER_STRING[tuner]}) at $ipAddress:$port"

                iqConverter.frequency = tunedFrequency + frequencyOffset
                if (currentSampleRate > maxSampleRate) currentSampleRate = maxSampleRate
                if (currentSampleRate < minSampleRate) currentSampleRate = minSampleRate
                possibleGainValues.firstOrNull { it >= gainValue }?.let { gainValue = it } // sent below

                // Set all parameters:
                executeCommand(command(RTL_TCP_COMMAND_SET_FREQUENCY, tunedFrequency.toInt()))
                executeCommand(command(RTL_TCP_COMMAND_SET_SAMPLERATE, currentSampleRate))
                executeCommand(command(RTL_TCP_COMMAND_SET_GAIN_MODE, if (isManualGain) 0x01 else 0x00))
                if (isManualGain) executeCommand(command(RTL_TCP_COMMAND_SET_GAIN, gainValue))
                if (isManualGain && tuner == RTLSDR_TUNER_E4000) executeCommand(command(RTL_TCP_COMMAND_SET_IFGAIN, 0.toShort(), ifGain.toShort()))
                executeCommand(command(RTL_TCP_COMMAND_SET_FREQ_CORR, frequencyCorrection))
                executeCommand(command(RTL_TCP_COMMAND_SET_AGC_MODE, if (isAutomaticGainControl) 0x01 else 0x00))
                executeCommand(command(RTL_TCP_COMMAND_SET_DIRECT_SAMPLING_MODE, directSamplingMode))
                return true
            } catch (e: UnknownHostException) {
                Log.e(LOGTAG, "CommandThread: (connect) Unknown host: $ipAddress")
                reportError("Unknown host: $ipAddress")
            } catch (e: IOException) {
                Log.e(LOGTAG, "CommandThread: (connect) Error while connecting to rtlsdr://$ipAddress:$port : ${e.message}")
            } catch (e: InterruptedException) {
                Log.e(LOGTAG, "CommandThread: (connect) Interrupted.")
            }
            return false
        }

        override fun run() {
            name = "Thread-Rtlsdr-CommandThread-${System.currentTimeMillis()}"
            Log.i(LOGTAG, "CommandThread started (Thread: $name)")
            var nextCommand: ByteArray? = null

            // "Device open": connect to the rtl_tcp instance and read its information
            if (connect(5000)) { // 5 seconds timeout for tcp
                callback?.onIQSourceReady(this@RtltcpSource)
            } else if (!stopRequested) {
                Log.e(LOGTAG, "CommandThread: (open) connect reported error.")
                reportError("Couldn't connect to rtl_tcp instance")
                stopRequested = true
            } // else: thread was stopped while connecting...

            // poll commands from the queues and send them over the socket:
            while (true) {
                val output = outputStream ?: break
                try {
                    if (stopRequested) {
                        output.write(command(RTL_TCP_COMMAND_SHUTDOWN, 0))
                        output.flush()
                        break
                    }
                    nextCommand = commandQueue.poll(100, TimeUnit.MILLISECONDS)
                        ?: frequencyChangeCommandQueue.poll() // frequency changes only when nothing else waits
                        ?: continue
                    output.write(nextCommand)
                    Log.d(LOGTAG, "CommandThread: Command was sent: ${commandName(nextCommand)}")
                } catch (e: IOException) {
                    Log.e(LOGTAG, "CommandThread: Error while sending command (${commandName(nextCommand)}): ${e.message}")
                    reportError("Error while sending command: ${commandName(nextCommand)}")
                    break
                } catch (e: InterruptedException) {
                    Log.e(LOGTAG, "CommandThread: Interrupted while sending command (${commandName(nextCommand)})")
                    reportError("Interrupted while sending command: ${commandName(nextCommand)}")
                    break
                }
            }

            // Clean up:
            try {
                socket?.close()
            } catch (e: IOException) {
                Log.w(LOGTAG, "CommandThread: Error while closing the socket: ${e.message}")
            }
            socket = null
            inputStream = null
            outputStream = null
            this@RtltcpSource.commandThread = null // mark this source as 'closed'
            Log.i(LOGTAG, "CommandThread stopped (Thread: $name)")
        }
    }
}
