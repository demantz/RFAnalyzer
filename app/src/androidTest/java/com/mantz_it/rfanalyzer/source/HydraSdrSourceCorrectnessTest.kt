package com.mantz_it.rfanalyzer.source

import android.content.Context
import android.hardware.usb.UsbManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mantz_it.libhydrasdr.HydraSdrDevice
import com.mantz_it.libhydrasdr.HydraSdrGainType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * <h1>RF Analyzer - HydraSDR Source Correctness Tests</h1>
 *
 * Module:      HydraSdrSourceCorrectnessTest.kt
 * Description: Hardware-in-the-loop API-contract tests for HydraSdrSource, run against a real
 *              HydraSDR attached to the test device via USB: the documented behavior of open/close,
 *              tuning, gains, RF ports and streaming. HydraSdrSourceRobustnessTest covers the edge
 *              cases.
 *
 * Prerequisite: USB permission for this app must already be granted for the HydraSDR (launch the
 * app manually once and accept "always allow" for this device) - UsbManager.requestPermission()'s
 * system dialog can't be driven headlessly by an instrumented test. Tests are skipped (not failed)
 * if no device is attached or permission isn't granted.
 *
 * Run with (prefer `adb shell am instrument` directly over Gradle's connectedAndroidTest, which
 * uninstalls the app between runs and invalidates the granted USB permission):
 *   adb shell am instrument -w -r -e class \
 *     com.mantz_it.rfanalyzer.source.HydraSdrSourceCorrectnessTest \
 *     com.mantz_it.rfanalyzer.test/androidx.test.runner.AndroidJUnitRunner
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@RunWith(AndroidJUnit4::class)
class HydraSdrSourceCorrectnessTest {

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

    @Test
    fun testOpenReportsReadyAndIsOpenTrue() {
        val s = openSource()
        assertTrue(s.isOpen())
        val name = s.name
        assertNotNull("getName() should not be null once open", name)
        assertTrue("getName() should not be blank once open", name!!.isNotBlank())
    }

    @Test
    fun testGetSupportedSampleRatesIsNonEmptyAndSorted() {
        val s = openSource()
        val rates = s.supportedSampleRates
        assertNotNull(rates)
        assertTrue("Expected at least one supported sample rate, got ${rates!!.toList()}", rates.isNotEmpty())
        assertFalse(
            "Supported sample rates fell back to the [0] placeholder - device query failed",
            rates.size == 1 && rates[0] == 0
        )
        val sorted = rates.toList().sorted()
        assertEquals("Supported sample rates should already be sorted ascending", sorted, rates.toList())
    }

    @Test
    fun testSetFrequencyWithinRangeRoundTrips() {
        val s = openSource()
        val targetFrequency = 100_000_000L // 100 MHz, comfortably inside [MIN_FREQUENCY, MAX_FREQUENCY]
        s.frequency = targetFrequency
        assertEquals(targetFrequency, s.frequency)
    }

    @Test
    fun testSetFrequencyWithNonZeroOffsetRoundTrips() {
        val s = openSource()
        s.frequencyOffset = 1_000_000 // simulates an upconverter offset
        val targetFrequency = 150_000_000L
        s.frequency = targetFrequency
        assertEquals(targetFrequency, s.frequency)
    }

    @Test
    fun testSetFrequencyOutOfDocumentedRangeDoesNotCrash() {
        // The Kotlin layer doesn't clamp the frequency: whatever the driver does with an
        // out-of-range value, it must not throw or crash.
        val s = openSource()
        s.frequency = 1_000L // well below minFrequency
        android.util.Log.i(TAG, "After setFrequency(1000): getFrequency()=${s.frequency}")
        s.frequency = s.maxFrequency + 100_000_000L // above maxFrequency
        android.util.Log.i(TAG, "After setFrequency(maxFrequency+100e6): getFrequency()=${s.frequency}")
    }

    @Test
    fun testEachGainSetterCoercesIntoDocumentedRange() {
        val s = openSource()
        // Exercise every gain type the connected device actually reports (falls back to every
        // known HydraSdrGainType if deviceInfo failed to query - setGain()'s own generic
        // DEFAULT_MIN_GAIN/DEFAULT_MAX_GAIN fallback still applies in that case).
        val types = s.deviceInfo?.gains?.map { it.type } ?: HydraSdrGainType.entries.toList()
        for (type in types) {
            val descriptor = s.deviceInfo?.getGainDescriptor(type)
            val min = descriptor?.minValue ?: HydraSdrSource.DEFAULT_MIN_GAIN
            val max = descriptor?.maxValue ?: HydraSdrSource.DEFAULT_MAX_GAIN
            assertEquals("setGain($type, ${min - 5}) should coerce up to $min", min, s.setGain(type, min - 5))
            assertEquals("getGain($type) should reflect the coerced value", min, s.getGain(type))
            assertEquals("setGain($type, ${max + 5}) should coerce down to $max", max, s.setGain(type, max + 5))
            assertEquals("getGain($type) should reflect the coerced value", max, s.getGain(type))
        }
    }

    @Test
    fun testAdvancedGainEnabledTogglesActiveGainGroupWithoutError() {
        val s = openSource()
        s.advancedGainEnabled = true
        s.setGain(HydraSdrGainType.LNA, 5)
        s.setGain(HydraSdrGainType.MIXER, 5)
        s.setGain(HydraSdrGainType.VGA, 5)
        s.advancedGainEnabled = false
        s.setGain(HydraSdrGainType.LINEARITY, 10)
        s.setGain(HydraSdrGainType.SENSITIVITY, 10)
        s.advancedGainEnabled = true
        // No exception thrown across repeated toggles is the assertion itself; also sanity-check
        // isOpen() still holds (a native-layer exception mid-toggle would typically surface there).
        assertTrue(s.isOpen())
    }

    @Test
    fun testRfPortAssignmentDoesNotThrow() {
        val s = openSource()
        assertTrue("Expected at least one available RF port", s.availableRfPorts.isNotEmpty())
        for (port in s.availableRfPorts) {
            s.rfPortIndex = port.index
            assertEquals(port.index, s.rfPortIndex)
        }
    }

    @Test
    fun testStartStopSamplingProducesPacketsOfExpectedSize() {
        val s = openSource()
        s.frequency = 100_000_000L
        s.sampleRate = 10_000_000
        s.startSampling()
        try {
            var packetsSeen = 0
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (packetsSeen < 3 && System.nanoTime() < deadline) {
                val packet = s.getPacket(200) ?: continue
                assertEquals(
                    "Packet size should always equal the fixed buffer size today (pre category-3 fix)",
                    s.packetSize,
                    packet.size
                )
                assertEquals(HydraSdrDevice.BUFFER_SIZE, packet.size)
                s.returnPacket(packet)
                packetsSeen++
            }
            assertTrue("Did not receive any packets within 5s of starting sampling", packetsSeen > 0)
        } finally {
            s.stopSampling()
        }
    }

    @Test
    fun testCloseAfterOpenIsClean() {
        val s = openSource()
        assertTrue(s.isOpen())
        assertTrue("close() should report success for a cleanly-opened device", s.close())
        assertFalse(s.isOpen())
        source = null // already closed, avoid double-close in tearDown
    }

    @Test
    fun testCloseWithoutOpenDoesNotThrow() {
        val s = HydraSdrSource()
        assertTrue("close() on a never-opened source should be a clean no-op", s.close())
    }

    @Test
    fun testGetPacketWithoutStartSamplingHandlesGracefully() {
        val s = openSource()
        // startSampling() was never called, so there is no filled buffer to hand out.
        val packet = s.getPacket(200)
        assertTrue("getPacket() without startSampling() should return null, not a packet", packet == null)
    }

    companion object {
        private const val TAG = "HydraSdrCorrectnessTest"
    }
}
