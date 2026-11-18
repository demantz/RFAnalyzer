package com.mantz_it.rfanalyzer.source

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mantz_it.libhydrasdr.HydraSdrDevice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * <h1>RF Analyzer - HydraSDR Source Robustness Tests</h1>
 *
 * Module:      HydraSdrSourceRobustnessTest.kt
 * Description: Hardware-in-the-loop robustness tests for HydraSdrSource, run against a real
 *              HydraSDR attached to the test device via USB: sustained streaming, rapid start/stop
 *              cycles, rate and frequency changes while streaming, concurrent buffer-pool access,
 *              frequencies above 2^32 Hz and partially filled buffers at decimated rates.
 *
 * Prerequisite: USB permission for this app must already be granted for the HydraSDR (launch the
 * app manually once and accept "always allow" for this device) - UsbManager.requestPermission()'s
 * system dialog can't be driven headlessly by an instrumented test. Tests are skipped (not failed)
 * if no device is attached or permission isn't granted.
 *
 * Run with (prefer `adb shell am instrument` directly over Gradle's connectedAndroidTest, which
 * uninstalls the app between runs and invalidates the granted USB permission):
 *   adb shell am instrument -w -r -e class \
 *     com.mantz_it.rfanalyzer.source.HydraSdrSourceRobustnessTest \
 *     com.mantz_it.rfanalyzer.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Pull the recorded CSV after a run with:
 *   adb pull /sdcard/Android/data/<applicationId>/files/hydrasdr_valid_byte_count_test.csv
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class HydraSdrSourceRobustnessTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var source: HydraSdrSource? = null

    @Before
    fun setUp() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == HydraSdrSource.USB_VENDOR_ID && it.productId == HydraSdrSource.USB_PRODUCT_ID
        }
        assumeTrue("No HydraSDR device attached - skipping hardware test", device != null)
        assumeTrue(
            "USB permission not granted for this app - launch the app manually once and grant it",
            usbManager.hasPermission(device)
        )
    }

    @After
    fun tearDown() {
        source?.stopSampling()
        source?.close()
        source = null
    }

    private fun openSource(): HydraSdrSource {
        val s = HydraSdrSource()
        val readyLatch = CountDownLatch(1)
        var errorMessage: String? = null
        s.open(context, object : IQSourceInterface.Callback {
            override fun onIQSourceReady(source: IQSourceInterface) {
                readyLatch.countDown()
            }
            override fun onIQSourceError(source: IQSourceInterface, message: String) {
                errorMessage = message
                readyLatch.countDown()
            }
            override fun onIQSourceInformation(source: IQSourceInterface, message: String) {
            }
        })
        assertTrue("Timed out opening HydraSdrSource", readyLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Failed to open HydraSdrSource: $errorMessage", s.isOpen())
        source = s
        return s
    }

    private fun writeCsv(fileName: String, header: String, rows: List<String>) {
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, fileName)
        file.writeText((listOf(header) + rows).joinToString("\n"))
        android.util.Log.i(TAG, "Wrote ${rows.size} rows to ${file.absolutePath}")
    }

    /** Mean and standard deviation of the signed 16-bit little-endian I values of every 8th complex sample in [buffer]. */
    private fun sampleStats(buffer: ByteArray): Pair<Double, Double> {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var i = 0
        while (i + 1 < buffer.size) {
            val s = ((buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)).toShort().toDouble()
            sum += s
            sumSq += s * s
            n++
            i += 32 // 8 complex samples of 4 bytes each
        }
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)
        return Pair(mean, kotlin.math.sqrt(kotlin.math.max(0.0, variance)))
    }

    /** Drains packets from [source] on a background thread, recording arrival time and buffer-pool availability. */
    private class PacketDrainer(private val source: HydraSdrSource) {
        data class Arrival(val nanoTime: Long, val bytes: Int, val availableBuffers: Int, val meanSample: Double, val stdDevSample: Double)

        private val arrivals = mutableListOf<Arrival>()
        private val lock = Any()
        private val running = AtomicBoolean(false)
        private var thread: Thread? = null
        val exceptions = mutableListOf<Throwable>()

        fun start(statsFn: (ByteArray) -> Pair<Double, Double>) {
            running.set(true)
            thread = Thread {
                while (running.get()) {
                    try {
                        val packet = source.getPacket(200) ?: continue
                        val (mean, stdDev) = statsFn(packet)
                        val arrival = Arrival(System.nanoTime(), packet.size, source.availableBufferCount(), mean, stdDev)
                        synchronized(lock) { arrivals.add(arrival) }
                        source.returnPacket(packet)
                    } catch (t: Throwable) {
                        synchronized(lock) { exceptions.add(t) }
                    }
                }
            }
            thread?.start()
        }

        fun stop() {
            running.set(false)
            thread?.join(2000)
        }

        fun snapshotSince(sinceNanoTime: Long): List<Arrival> =
            synchronized(lock) { arrivals.filter { it.nanoTime >= sinceNanoTime } }

        fun countSince(sinceNanoTime: Long): Int = snapshotSince(sinceNanoTime).size
    }

    @Test
    fun testAvailableBufferCountNeverGoesNegativeDuringSustainedStreaming() {
        val s = openSource()
        s.frequency = 100_000_000L
        s.sampleRate = 10_000_000
        s.startSampling()

        val drainer = PacketDrainer(s)
        drainer.start(::sampleStats)
        val start = System.nanoTime()
        Thread.sleep(4000)
        val arrivals = drainer.snapshotSince(start)
        drainer.stop()
        s.stopSampling()

        assertTrue("Expected packets to arrive during sustained streaming", arrivals.isNotEmpty())
        val minAvailable = arrivals.minOf { it.availableBuffers }
        assertTrue("availableBufferCount() went negative ($minAvailable) during streaming", minAvailable >= 0)
    }

    @Test
    fun testRapidPlayStopCyclingLeavesCleanState() {
        // RtlsdrSourceStreamingTest's pattern: repeated fresh-instance, impatient
        // open->start->stop->close cycles, matching AnalyzerService.createSource()'s per-cycle
        // lifecycle.
        val warmupCycles = 8
        val dwellMs = 250L

        repeat(warmupCycles) { cycle ->
            val s = HydraSdrSource()
            val readyLatch = CountDownLatch(1)
            s.open(context, object : IQSourceInterface.Callback {
                override fun onIQSourceReady(source: IQSourceInterface) { readyLatch.countDown() }
                override fun onIQSourceError(source: IQSourceInterface, message: String) { readyLatch.countDown() }
                override fun onIQSourceInformation(source: IQSourceInterface, message: String) { }
            })
            readyLatch.await(dwellMs, TimeUnit.MILLISECONDS)
            s.frequency = 100_000_000L
            s.sampleRate = 10_000_000
            if (s.isOpen()) s.startSampling()
            Thread.sleep(dwellMs)
            s.stopSampling()
            s.close()
            android.util.Log.i(TAG, "testRapidPlayStopCyclingLeavesCleanState: warmup cycle $cycle done")
        }

        val s = openSource()
        s.frequency = 100_000_000L
        s.sampleRate = 10_000_000
        s.startSampling()

        val drainer = PacketDrainer(s)
        drainer.start(::sampleStats)
        val captureStart = System.nanoTime()
        Thread.sleep(3000)
        val count = drainer.countSince(captureStart)
        drainer.stop()
        s.stopSampling()

        assertTrue("No packets arrived after $warmupCycles rapid play/stop cycles", count > 0)
        assertTrue("PacketDrainer hit exceptions during rapid cycling: ${drainer.exceptions}", drainer.exceptions.isEmpty())
    }

    @Test
    fun testFlushBufferQueueDuringActiveStreamingDoesNotCrash() {
        val s = openSource()
        s.frequency = 100_000_000L
        s.sampleRate = 10_000_000
        s.startSampling()

        val drainer = PacketDrainer(s)
        drainer.start(::sampleStats)
        Thread.sleep(500)

        // Setting the sample rate or the frequency flushes the buffer pool - hammer both while the
        // drainer is taking and returning buffers.
        repeat(30) {
            s.frequency = 90_000_000L + it * 1_000_000L
            s.sampleRate = if (it % 2 == 0) 10_000_000 else 5_000_000
        }
        Thread.sleep(1000)

        drainer.stop()
        s.stopSampling()

        assertTrue("PacketDrainer hit exceptions during flush/rate-change hammering: ${drainer.exceptions}", drainer.exceptions.isEmpty())
    }

    @Test
    fun testSwitchingBetweenDecimatedSampleRatesDoesNotCrash() {
        // hydrasdr_set_samplerate() swaps the driver's decimators (update_ddc_decimation() in
        // hydrasdr_shared.c) without synchronizing with the RX threads still using them - a SIGSEGV
        // in dec_hb33_int16_process. HydraSdrSource's sampleRate setter therefore stops RX around
        // the change (hydrasdr_stop_rx() joins the RX threads). This cycles through the lower half
        // of the supported rates (where decimation kicks in) while streaming and draining packets;
        // a regression crashes the instrumentation process instead of failing the test.
        val s = openSource()
        val supportedRates = s.supportedSampleRates!!.filter { it > 0 }.sorted()
        assumeTrue("Device reported fewer than 2 usable supported sample rates", supportedRates.size >= 2)
        val lowRates = supportedRates.take((supportedRates.size / 2).coerceAtLeast(2))

        s.frequency = 92_000_000L
        s.sampleRate = lowRates.last()
        s.startSampling()

        val drainer = PacketDrainer(s)
        drainer.start(::sampleStats)
        Thread.sleep(500)

        repeat(3) {
            for (rate in lowRates) {
                s.sampleRate = rate
                Thread.sleep(300) // the ~250-450 ms between rate changes that triggered the crash
            }
        }
        Thread.sleep(500)

        drainer.stop()
        s.stopSampling()

        assertTrue("PacketDrainer hit exceptions during decimated sample-rate cycling: ${drainer.exceptions}", drainer.exceptions.isEmpty())
    }

    @Test
    fun testConcurrentGetPacketAndReturnPacketFromMultipleThreadsDoesNotCorruptPool() {
        val s = openSource()
        s.frequency = 100_000_000L
        s.sampleRate = 10_000_000
        s.startSampling()

        val exceptions = mutableListOf<Throwable>()
        val exceptionsLock = Any()
        val running = AtomicBoolean(true)
        val consumed = AtomicInteger(0)
        val threads = (1..4).map {
            Thread {
                while (running.get()) {
                    try {
                        val packet = s.getPacket(200) ?: continue
                        consumed.incrementAndGet()
                        s.returnPacket(packet)
                    } catch (t: Throwable) {
                        synchronized(exceptionsLock) { exceptions.add(t) }
                    }
                }
            }
        }
        threads.forEach { it.start() }
        Thread.sleep(3000)
        running.set(false)
        threads.forEach { it.join(2000) }
        s.stopSampling()

        assertTrue("No packets consumed by concurrent drainer threads", consumed.get() > 0)
        assertTrue("Concurrent access to the buffer pool threw: $exceptions", exceptions.isEmpty())
    }

    @Test
    fun testFrequencyAbove4295GHzNoLongerWrapsToZero() {
        // 2^32 Hz (~4.295 GHz) must reach the driver as itself: HydraSdrDevice.setFrequency takes a
        // Long down to hydrasdr_set_freq (uint64_t), so it cannot wrap to 0, which the driver
        // rejects. Checked on HydraSdrDevice directly, since HydraSdrSource's frequency setter
        // discards the driver's result.
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = usbManager.deviceList.values.first {
            it.vendorId == HydraSdrSource.USB_VENDOR_ID && it.productId == HydraSdrSource.USB_PRODUCT_ID
        }
        val connection = usbManager.openDevice(usbDevice)
        assertNotNull("Failed to open USB connection to HydraSDR for direct HydraSdrDevice test", connection)
        val (device, error) = HydraSdrDevice.open(connection!!.fileDescriptor)
        assertNotNull("Failed to open HydraSdrDevice directly: $error", device)
        try {
            // hydrasdr_generic_set_freq() rejects only 0 Hz and > 10 GHz.
            assertTrue(
                "Expected 4.295 GHz to be accepted now that setFrequency() takes a Long end-to-end " +
                    "(it used to wrap to the invalid sentinel 0 under the old Int-based API)",
                device!!.setFrequency(4_294_967_296L)
            )
        } finally {
            device?.close()
        }
    }

    @Test
    fun testShrinkingPacketAfterSampleRateChangeDoesNotLeaveStaleTailBytes() {
        // Below ~2.5 MSps a USB transfer fills only the front of the pooled 262144-byte buffer:
        // the driver reports how many bytes are valid (UsbSdrSource.getValidByteCount()) and only
        // those may be decoded - the rest is left over from an earlier fill. Measured through
        // HydraSdrSource.mixPacketIntoSamplePacket() (the production path): decoded samples per
        // second must track the configured rate at the lowest rate as they do at the highest.
        // Decoding whole buffers gives a ratio of ~64 at 39,062 Sps on a HydraSDR RFOne (1.003 when
        // correct, ~1.001 at 10 MSps, where buffers are full).
        val s = openSource()
        val supportedRates = s.supportedSampleRates!!.filter { it > 0 }
        assumeTrue("Device reported no usable supported sample rates", supportedRates.isNotEmpty())
        val lowRate = supportedRates.min()
        val highRate = supportedRates.max()
        assumeTrue("Lowest and highest supported rates are the same - cannot compare", lowRate < highRate)

        s.frequency = 100_000_000L

        // Decoded samples per second (not packet arrivals): what matters is how many samples are
        // decoded per packet.
        fun measureApparentThroughput(rate: Int, durationMs: Long): Pair<Int, Double> {
            s.sampleRate = rate
            s.startSampling()
            val samplePacket = com.mantz_it.rfanalyzer_ipc.SamplePacket(HydraSdrDevice.BUFFER_SIZE / 4)
            var totalDecoded = 0L
            var packetCount = 0
            val running = AtomicBoolean(true)
            val exceptions = mutableListOf<Throwable>()
            val thread = Thread {
                while (running.get()) {
                    try {
                        val packet = s.getPacket(200) ?: continue
                        samplePacket.setSize(0)
                        val decoded = s.mixPacketIntoSamplePacket(packet, samplePacket, s.frequency)
                        totalDecoded += decoded
                        packetCount++
                        s.returnPacket(packet)
                    } catch (t: Throwable) {
                        synchronized(exceptions) { exceptions.add(t) }
                    }
                }
            }
            thread.start()
            Thread.sleep(durationMs)
            running.set(false)
            thread.join(2000)
            s.stopSampling()
            assertTrue("Decode thread hit exceptions at rate=$rate: $exceptions", exceptions.isEmpty())
            val elapsedSec = durationMs / 1000.0
            val apparentThroughput = totalDecoded / elapsedSec
            return Pair(packetCount, apparentThroughput)
        }

        val (highCount, highApparent) = measureApparentThroughput(highRate, 4000)
        val (lowCount, lowApparent) = measureApparentThroughput(lowRate, 4000)

        val highRatio = highApparent / highRate
        val lowRatio = lowApparent / lowRate

        android.util.Log.i(
            TAG,
            "Throughput check: highRate=$highRate ($highCount packets, apparent=$highApparent, ratio=$highRatio) " +
                "lowRate=$lowRate ($lowCount packets, apparent=$lowApparent, ratio=$lowRatio)"
        )
        writeCsv(
            "hydrasdr_valid_byte_count_test.csv",
            "rate,packet_count,apparent_throughput,ratio_to_configured_rate",
            listOf(
                "$highRate,$highCount,%.1f,%.3f".format(highApparent, highRatio),
                "$lowRate,$lowCount,%.1f,%.3f".format(lowApparent, lowRatio)
            )
        )

        assertTrue("No packets received at the highest supported rate ($highRate)", highCount > 0)
        assertTrue("No packets received at the lowest supported rate ($lowRate)", lowCount > 0)

        // At the highest rate, buffers should be legitimately full - apparent throughput should track
        // the configured rate reasonably closely (generous tolerance for USB/scheduling jitter).
        assertTrue(
            "Apparent throughput at the highest rate ($highRatio x configured) is unexpectedly far from 1.0 " +
                "- check hardware/setup before trusting the low-rate comparison below",
            highRatio in 0.5..2.0
        )
        // The actual regression guard: a ratio far from 1.0 at the low rate means stale tail bytes
        // are decoded as if they were samples.
        assertTrue(
            "Decoded throughput at the lowest rate ($lowRatio x configured, apparent=$lowApparent vs " +
                "configured=$lowRate) overshoots the configured sample rate - packets are arriving faster " +
                "than real data justifies, meaning stale/garbage tail bytes are being decoded as if they " +
                "were real samples. See hydrasdr_valid_byte_count_test.csv for full detail.",
            lowRatio in 0.5..2.0
        )
    }

    companion object {
        private const val TAG = "HydraSdrRobustnessTest"
    }
}
