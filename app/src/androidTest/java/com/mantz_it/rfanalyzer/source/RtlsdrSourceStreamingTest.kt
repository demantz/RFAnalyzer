package com.mantz_it.rfanalyzer.source

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mantz_it.librtlsdr.RtlSdrDevice
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * <h1>RF Analyzer - RTL-SDR Source Streaming Tests</h1>
 *
 * Module:      RtlsdrSourceStreamingTest.kt
 * Description: Hardware-in-the-loop diagnostics for RtlsdrSource, run against a real RTL-SDR dongle
 *              attached to the test device via USB. They measure IQ packet arrival timing AND
 *              signal content (mean/stdDev of raw bytes - a muted device sends near-constant
 *              DC-midpoint bytes on schedule, which timing alone doesn't catch):
 *
 *  1. Live sample-rate changes to 1 Msps and back must neither stall nor mute the stream
 *     (testSampleRateChangeToOneMspsDoesNotStallStreamingAt97Mhz/At14Mhz,
 *     testRapidSampleRateCyclingCanStallStreaming)
 *  2. Rapid Play/Stop cycling must not leave the device muted, even at 2.5 Msps
 *     (testRapidPlayStopCyclingLeavesCleanState)
 *  3. No periodic gaps around 14 MHz, with 97 MHz as the control (testHfVsVhfGapPattern)
 *
 * Prerequisite: USB permission for this app must already be granted for the
 * dongle (launch the app manually once and accept "always allow" for this
 * device) - UsbManager.requestPermission()'s system dialog can't be driven
 * headlessly by an instrumented test. Tests are skipped (not failed) if no
 * device is attached or permission isn't granted.
 *
 * Run with (prefer `adb shell am instrument` directly over Gradle's
 * connectedAndroidTest, which uninstalls the app between runs and invalidates
 * the granted USB permission each time):
 *   adb shell am instrument -w -r -e class \
 *     com.mantz_it.rfanalyzer.source.RtlsdrSourceStreamingTest \
 *     com.mantz_it.rfanalyzer.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Pull the recorded CSVs after a run with:
 *   adb pull /sdcard/Android/data/<applicationId>/files/rtlsdr_rate_change_test_97mhz.csv
 *   adb pull /sdcard/Android/data/<applicationId>/files/rtlsdr_rate_change_test_14mhz.csv
 *   adb pull /sdcard/Android/data/<applicationId>/files/rtlsdr_hf_glitch_test.csv
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class RtlsdrSourceStreamingTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private var source: RtlsdrSource? = null

    @Before
    fun setUp() {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val device = usbManager.deviceList.values.firstOrNull {
            it.vendorId == RtlsdrSource.USB_VENDOR_ID && it.productId == RtlsdrSource.USB_PRODUCT_ID
        }
        assumeTrue("No RTL-SDR device attached - skipping hardware test", device != null)
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

    private fun openSource(): RtlsdrSource {
        val s = RtlsdrSource()
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
        assertTrue("Timed out opening RtlsdrSource", readyLatch.await(10, TimeUnit.SECONDS))
        assertTrue("Failed to open RtlsdrSource: $errorMessage", s.isOpen())
        source = s
        return s
    }

    private fun expectedIntervalMs(sampleRate: Int): Double {
        val samplesPerBuffer = RtlSdrDevice.BUFFER_SIZE / 2 // 2 bytes/sample (unsigned 8-bit I/Q)
        return samplesPerBuffer * 1000.0 / sampleRate
    }

    private fun writeCsv(fileName: String, header: String, rows: List<String>) {
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, fileName)
        file.writeText((listOf(header) + rows).joinToString("\n"))
        android.util.Log.i(TAG, "Wrote ${rows.size} rows to ${file.absolutePath}")
    }

    /**
     * Mean and standard deviation of unsigned-byte values in [buffer], sampled every 8th byte
     * (cheap but representative - a genuinely flat/muted stream is flat throughout). RtlsdrSource's
     * raw bytes are unsigned 8-bit, DC-centered at ~127.4 (see ByteIQConverter) - a device
     * that's muted/stuck-in-standby sends near-constant bytes there, which converts to near-(0,0)
     * IQ regardless of gain, i.e. a flat FFT at the noise floor.
     */
    private fun byteStats(buffer: ByteArray): Pair<Double, Double> {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        var i = 0
        while (i < buffer.size) {
            val v = (buffer[i].toInt() and 0xFF).toDouble()
            sum += v
            sumSq += v * v
            n++
            i += 8
        }
        val mean = sum / n
        val variance = (sumSq / n) - (mean * mean)
        return Pair(mean, kotlin.math.sqrt(kotlin.math.max(0.0, variance)))
    }

    /** Drains packets from [source] on a background thread, recording arrival time, buffer-pool availability, and signal content stats. */
    private class PacketDrainer(private val source: RtlsdrSource, private val byteStats: (ByteArray) -> Pair<Double, Double>) {
        data class Arrival(val nanoTime: Long, val bytes: Int, val availableBuffers: Int, val meanByte: Double, val stdDevByte: Double)

        private val arrivals = mutableListOf<Arrival>()
        private val lock = Any()
        private val running = AtomicBoolean(false)
        private var thread: Thread? = null

        fun start() {
            running.set(true)
            thread = Thread {
                while (running.get()) {
                    val packet = source.getPacket(200) ?: continue
                    val (mean, stdDev) = byteStats(packet)
                    val arrival = Arrival(System.nanoTime(), packet.size, source.availableBufferCount(), mean, stdDev)
                    synchronized(lock) { arrivals.add(arrival) }
                    source.returnPacket(packet)
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

        /** Longest gap between consecutive arrivals since [sinceNanoTime], in ms. -1 if fewer than 2 arrivals. */
        fun maxGapMs(sinceNanoTime: Long): Double {
            val snapshot = snapshotSince(sinceNanoTime)
            if (snapshot.size < 2) return -1.0
            var maxGap = 0L
            for (i in 1 until snapshot.size) {
                maxGap = maxOf(maxGap, snapshot[i].nanoTime - snapshot[i - 1].nanoTime)
            }
            return maxGap / 1_000_000.0
        }

        fun countSince(sinceNanoTime: Long): Int = snapshotSince(sinceNanoTime).size

        /** Average per-packet byte stdDev since [sinceNanoTime]. Low values indicate a flat/muted signal. -1 if no arrivals. */
        fun avgStdDevSince(sinceNanoTime: Long): Double {
            val snapshot = snapshotSince(sinceNanoTime)
            if (snapshot.isEmpty()) return -1.0
            return snapshot.map { it.stdDevByte }.average()
        }
    }

    @Test
    fun testSampleRateChangeToOneMspsDoesNotStallStreamingAt97Mhz() {
        runSampleRateChangeScenario(frequencyHz = 97_000_000L, csvFileName = "rtlsdr_rate_change_test_97mhz.csv")
    }

    @Test
    fun testSampleRateChangeToOneMspsDoesNotStallStreamingAt14Mhz() {
        // Same scenario as the 97 MHz variant, in the HF band: the RTL-SDR Blog V4 routes HF
        // through its upconverter/band-switch path (see tuner_r82xx.c band handling), which
        // the 97 MHz (VHF/FM) run never exercises.
        runSampleRateChangeScenario(frequencyHz = 14_028_335L, csvFileName = "rtlsdr_rate_change_test_14mhz.csv")
    }

    private fun runSampleRateChangeScenario(frequencyHz: Long, csvFileName: String) {
        val s = openSource()
        s.frequency = frequencyHz
        s.sampleRate = 2_500_000
        s.startSampling()

        val drainer = PacketDrainer(s, ::byteStats)
        drainer.start()

        // Baseline at 2.5 Msps
        val baselineStart = System.nanoTime()
        Thread.sleep(3000)
        val baselineGap = drainer.maxGapMs(baselineStart)
        val baselineStdDev = drainer.avgStdDevSince(baselineStart)
        val baselineExpected = expectedIntervalMs(2_500_000)
        android.util.Log.i(TAG, "Baseline @2.5Msps ($frequencyHz Hz): maxGap=${baselineGap}ms expected~${baselineExpected}ms avgStdDev=$baselineStdDev count=${drainer.countSince(baselineStart)}")

        // Switch to 1 Msps live - checked for a timing gap and for a flat/muted signal (stdDev)
        val switchToOneMspsAt = System.nanoTime()
        s.sampleRate = 1_000_000
        Thread.sleep(5000)
        val oneMspsGap = drainer.maxGapMs(switchToOneMspsAt)
        val oneMspsStdDev = drainer.avgStdDevSince(switchToOneMspsAt)
        val oneMspsExpected = expectedIntervalMs(1_000_000)
        android.util.Log.i(TAG, "After switch to 1Msps ($frequencyHz Hz): maxGap=${oneMspsGap}ms expected~${oneMspsExpected}ms avgStdDev=$oneMspsStdDev count=${drainer.countSince(switchToOneMspsAt)}")

        // Switch to 2 Msps live - streaming must recover from the 1 Msps phase
        val switchToTwoMspsAt = System.nanoTime()
        s.sampleRate = 2_000_000
        Thread.sleep(5000)
        val twoMspsGap = drainer.maxGapMs(switchToTwoMspsAt)
        val twoMspsStdDev = drainer.avgStdDevSince(switchToTwoMspsAt)
        val twoMspsExpected = expectedIntervalMs(2_000_000)
        android.util.Log.i(TAG, "After switch to 2Msps ($frequencyHz Hz): maxGap=${twoMspsGap}ms expected~${twoMspsExpected}ms avgStdDev=$twoMspsStdDev count=${drainer.countSince(switchToTwoMspsAt)}")

        drainer.stop()
        s.stopSampling()

        writeCsv(
            csvFileName,
            "phase,sample_rate,expected_interval_ms,max_gap_ms,avg_stddev_byte,packet_count",
            listOf(
                "baseline,2500000,%.2f,%.2f,%.3f,%d".format(baselineExpected, baselineGap, baselineStdDev, drainer.countSince(baselineStart)),
                "after_switch_to_1msps,1000000,%.2f,%.2f,%.3f,%d".format(oneMspsExpected, oneMspsGap, oneMspsStdDev, drainer.countSince(switchToOneMspsAt)),
                "after_switch_to_2msps,2000000,%.2f,%.2f,%.3f,%d".format(twoMspsExpected, twoMspsGap, twoMspsStdDev, drainer.countSince(switchToTwoMspsAt))
            )
        )

        assertTrue(
            "Baseline streaming at 2.5 Msps is not healthy (maxGap=${baselineGap}ms) - check hardware/setup before trusting this test",
            baselineGap in 0.0..(baselineExpected * 5)
        )
        assertTrue(
            "Baseline signal at 2.5 Msps appears flat/muted (avgStdDev=$baselineStdDev, threshold=$FLAT_SIGNAL_STDDEV_THRESHOLD) - check hardware/setup before trusting this test",
            baselineStdDev > FLAT_SIGNAL_STDDEV_THRESHOLD
        )
        assertTrue(
            "Streaming stalled after live switch to 1 Msps at $frequencyHz Hz: maxGap=${oneMspsGap}ms (expected ~${oneMspsExpected}ms)",
            oneMspsGap in 0.0..(oneMspsExpected * 5)
        )
        assertTrue(
            "Signal is flat/muted after live switch to 1 Msps at $frequencyHz Hz (avgStdDev=$oneMspsStdDev, threshold=$FLAT_SIGNAL_STDDEV_THRESHOLD) - " +
                "samples are arriving on schedule but contain no real signal, i.e. the device appears muted rather than stalled",
            oneMspsStdDev > FLAT_SIGNAL_STDDEV_THRESHOLD
        )
        assertTrue(
            "Streaming did not recover after switching away from 1 Msps to 2 Msps at $frequencyHz Hz: maxGap=${twoMspsGap}ms (expected ~${twoMspsExpected}ms)",
            twoMspsGap in 0.0..(twoMspsExpected * 5)
        )
        assertTrue(
            "Signal is still flat/muted after switching away from 1 Msps to 2 Msps at $frequencyHz Hz (avgStdDev=$twoMspsStdDev, threshold=$FLAT_SIGNAL_STDDEV_THRESHOLD)",
            twoMspsStdDev > FLAT_SIGNAL_STDDEV_THRESHOLD
        )
    }

    @Test
    fun testRapidSampleRateCyclingCanStallStreaming() {
        // The automatic sample rate (follows the zoom level) can set many sample rates in
        // quick succession while the user pinch-zooms the spectrum - this simulates that
        // rapid-fire pattern instead of one clean switch.
        val s = openSource()
        s.frequency = 14_028_335L
        s.sampleRate = 2_500_000
        s.startSampling()

        val drainer = PacketDrainer(s, ::byteStats)
        drainer.start()
        Thread.sleep(2000) // let baseline streaming settle before stressing it

        val cycleRates = intArrayOf(2_500_000, 1_000_000, 2_000_000, 1_000_000, 2_500_000, 1_000_000, 2_000_000, 1_000_000, 2_500_000)
        for (rate in cycleRates) {
            s.sampleRate = rate
            Thread.sleep(400) // short dwell, mimicking a rapid pinch-zoom gesture
        }

        // Settle at the final rate and check whether streaming actually recovered
        val settleStart = System.nanoTime()
        Thread.sleep(5000)
        val settleGap = drainer.maxGapMs(settleStart)
        val settleStdDev = drainer.avgStdDevSince(settleStart)
        val settleExpected = expectedIntervalMs(cycleRates.last())
        android.util.Log.i(TAG, "After rapid cycling, settled at ${cycleRates.last()}: maxGap=${settleGap}ms expected~${settleExpected}ms avgStdDev=$settleStdDev count=${drainer.countSince(settleStart)}")

        drainer.stop()
        s.stopSampling()

        assertTrue(
            "Streaming stalled after rapid sample-rate cycling: maxGap=${settleGap}ms (expected ~${settleExpected}ms)",
            settleGap in 0.0..(settleExpected * 5)
        )
        assertTrue(
            "Signal is flat/muted after rapid sample-rate cycling (avgStdDev=$settleStdDev, threshold=$FLAT_SIGNAL_STDDEV_THRESHOLD)",
            settleStdDev > FLAT_SIGNAL_STDDEV_THRESHOLD
        )
    }

    @Test
    fun testRapidPlayStopCyclingLeavesCleanState() {
        // Rapid Play/Stop presses must not leave the device muted (a flat signal even at
        // 2.5 Msps) - that would be a lifecycle/threading race in open()/close()/
        // startSampling()/stopSampling(), not a sample-rate problem. Drives RtlsdrSource
        // through several rapid, impatient open->start->stop->close cycles (a fresh instance
        // per cycle, as AnalyzerService.startAnalyzer() creates one), then verifies a clean
        // final start with real (non-flat) signal content.
        val warmupCycles = 8
        val dwellMs = 250L

        repeat(warmupCycles) { cycle ->
            val s = RtlsdrSource()
            val readyLatch = CountDownLatch(1)
            s.open(context, object : IQSourceInterface.Callback {
                override fun onIQSourceReady(source: IQSourceInterface) { readyLatch.countDown() }
                override fun onIQSourceError(source: IQSourceInterface, message: String) { readyLatch.countDown() }
                override fun onIQSourceInformation(source: IQSourceInterface, message: String) { }
            })
            // Don't wait for the full ~1s open to finish - an impatient rapid Stop press
            // wouldn't either. This exercises the close()-while-open()-in-flight path.
            readyLatch.await(dwellMs, TimeUnit.MILLISECONDS)
            s.frequency = 97_000_000L
            s.sampleRate = 2_500_000
            if (s.isOpen()) s.startSampling()
            Thread.sleep(dwellMs)
            s.stopSampling()
            s.close()
            android.util.Log.i(TAG, "testRapidPlayStopCyclingLeavesCleanState: warmup cycle $cycle done")
        }

        // Final cycle: open properly, wait for ready, and verify a clean, non-flat signal.
        val s = openSource()
        s.frequency = 97_000_000L
        s.sampleRate = 2_500_000
        s.startSampling()

        val drainer = PacketDrainer(s, ::byteStats)
        drainer.start()
        val captureStart = System.nanoTime()
        Thread.sleep(5000)
        val maxGap = drainer.maxGapMs(captureStart)
        val avgStdDev = drainer.avgStdDevSince(captureStart)
        val count = drainer.countSince(captureStart)
        val expected = expectedIntervalMs(2_500_000)
        android.util.Log.i(TAG, "After $warmupCycles rapid play/stop cycles: maxGap=${maxGap}ms expected~${expected}ms avgStdDev=$avgStdDev count=$count")

        drainer.stop()
        s.stopSampling()

        assertTrue(
            "Streaming stalled after rapid play/stop cycling: maxGap=${maxGap}ms count=$count",
            maxGap in 0.0..(expected * 5)
        )
        assertTrue(
            "Signal is flat/muted after rapid play/stop cycling (avgStdDev=$avgStdDev, threshold=$FLAT_SIGNAL_STDDEV_THRESHOLD) - " +
                "device may be stuck in a muted state from an overlapping open/close",
            avgStdDev > FLAT_SIGNAL_STDDEV_THRESHOLD
        )
    }

    @Test
    fun testHfVsVhfGapPattern() {
        val s = openSource()
        s.sampleRate = 2_500_000
        s.startSampling()

        val drainer = PacketDrainer(s, ::byteStats)
        drainer.start()

        val runDurationMs = 60_000L
        val gapThresholdMs = expectedIntervalMs(2_500_000) * 3

        // HF run - checked for periodic glitches (~every 5 s)
        s.frequency = 14_000_000L
        val hfStart = System.nanoTime()
        Thread.sleep(runDurationMs)
        val hfArrivals = drainer.snapshotSince(hfStart)

        // VHF/FM run - the control, same session/hardware/consumer
        s.frequency = 97_000_000L
        val vhfStart = System.nanoTime()
        Thread.sleep(runDurationMs)
        val vhfArrivals = drainer.snapshotSince(vhfStart)

        drainer.stop()
        s.stopSampling()

        fun gapsOver(arrivals: List<PacketDrainer.Arrival>, thresholdMs: Double): List<Double> {
            val gaps = mutableListOf<Double>()
            for (i in 1 until arrivals.size) {
                val gapMs = (arrivals[i].nanoTime - arrivals[i - 1].nanoTime) / 1_000_000.0
                if (gapMs > thresholdMs) gaps.add(gapMs)
            }
            return gaps
        }

        val hfGaps = gapsOver(hfArrivals, gapThresholdMs)
        val vhfGaps = gapsOver(vhfArrivals, gapThresholdMs)
        val hfMinAvailable = hfArrivals.minOfOrNull { it.availableBuffers } ?: -1
        val vhfMinAvailable = vhfArrivals.minOfOrNull { it.availableBuffers } ?: -1

        android.util.Log.i(TAG, "HF (14MHz): ${hfArrivals.size} packets, ${hfGaps.size} gaps > ${gapThresholdMs}ms, minAvailableBuffers=$hfMinAvailable, gaps=$hfGaps")
        android.util.Log.i(TAG, "VHF (97MHz): ${vhfArrivals.size} packets, ${vhfGaps.size} gaps > ${gapThresholdMs}ms, minAvailableBuffers=$vhfMinAvailable, gaps=$vhfGaps")

        val csvRows = mutableListOf<String>()
        for (i in 1 until hfArrivals.size) {
            val gapMs = (hfArrivals[i].nanoTime - hfArrivals[i - 1].nanoTime) / 1_000_000.0
            csvRows.add("hf,14000000,%.1f,%.3f,%d".format((hfArrivals[i].nanoTime - hfStart) / 1_000_000.0, gapMs, hfArrivals[i].availableBuffers))
        }
        for (i in 1 until vhfArrivals.size) {
            val gapMs = (vhfArrivals[i].nanoTime - vhfArrivals[i - 1].nanoTime) / 1_000_000.0
            csvRows.add("vhf,97000000,%.1f,%.3f,%d".format((vhfArrivals[i].nanoTime - vhfStart) / 1_000_000.0, gapMs, vhfArrivals[i].availableBuffers))
        }
        writeCsv("rtlsdr_hf_glitch_test.csv", "band,frequency_hz,t_offset_ms,gap_ms,available_buffers", csvRows)

        // Informational gate, not a strict correctness contract: fails when HF glitches
        // show at the IQ-source level (a real gap in the byte stream). The CSV's
        // available_buffers tells a USB-level gap from consumer-side buffer-pool starvation.
        assertTrue(
            "Gaps > ${gapThresholdMs}ms detected in IQ stream at 14 MHz (count=${hfGaps.size}, gaps=$hfGaps) " +
                "- see rtlsdr_hf_glitch_test.csv for full timing detail",
            hfGaps.isEmpty()
        )
    }

    companion object {
        private const val TAG = "RtlsdrStreamingTest"

        // A flat/muted stream (constant DC-midpoint bytes) has near-zero stdDev; a real
        // signal + noise floor from the tuner has substantially more. Deliberately generous
        // to avoid false positives - tune based on real avgStdDev values logged per run.
        private const val FLAT_SIGNAL_STDDEV_THRESHOLD = 1.0
    }
}
