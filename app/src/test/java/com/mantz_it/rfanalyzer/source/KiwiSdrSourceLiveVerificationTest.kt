package com.mantz_it.rfanalyzer.source

import android.content.Context
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.mockito.Mockito
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * <h1>RF Analyzer - KiwiSDR Live Verification Tests</h1>
 *
 * Module:      KiwiSdrSourceLiveVerificationTest.kt
 * Description: Manual, opt-in verification against a REAL public KiwiSDR receiver - not part of the
 *              automated test suite (see class-level @Ignore below). KiwiSdrSourceTest proves the
 *              class against our own fake server; this checks the protocol against a real Kiwi.
 *
 * How to run:
 *   1. Pick an open public receiver from https://kiwisdr.com/public/ (avoid password-
 *      protected ones unless you have the password).
 *   2. Set it as the default of host/port/frequencyHz below (Gradle does not hand -D
 *      properties to the test JVM unless build.gradle.kts forwards them), then:
 *        ./gradlew testPlayDebugUnitTest --tests "*KiwiSdrSourceLiveVerificationTest*"
 *   3. Temporarily remove the @Ignore annotation on the class (or run with an IDE runner
 *      configuration that ignores @Ignore) so it actually executes.
 *   4. Be a good citizen: this connects, pulls a few seconds of real IQ, then disconnects
 *      promptly - do not loop this test against the same public receiver repeatedly.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Ignore("manual verification only - requires network access and a real public KiwiSDR host")
class KiwiSdrSourceLiveVerificationTest {

    private val host = System.getProperty("kiwisdr.host") ?: "sdr.example.invalid"
    private val port = System.getProperty("kiwisdr.port")?.toIntOrNull() ?: KiwiSdrSource.DEFAULT_PORT
    private val frequencyHz = System.getProperty("kiwisdr.freqHz")?.toLongOrNull() ?: 7_070_000L // the 40 m digital segment, likely to have activity
    private val runDurationSeconds = 5L

    private class RecordingCallback : IQSourceInterface.Callback {
        val readyLatch = CountDownLatch(1)
        val errorLatch = CountDownLatch(1)
        @Volatile var errorMessage: String? = null

        override fun onIQSourceReady(source: IQSourceInterface) {
            readyLatch.countDown()
        }

        override fun onIQSourceError(source: IQSourceInterface, message: String) {
            errorMessage = message
            errorLatch.countDown()
        }

        override fun onIQSourceInformation(source: IQSourceInterface, message: String) {
        }
    }

    @Test
    fun connectsToRealKiwiSdrAndReceivesPlausibleIqSamples() {
        val source = KiwiSdrSource(host, port, identUser = "RFAnalyzer-Prototype")
        source.frequency = frequencyHz
        val callback = RecordingCallback()

        println("Connecting to KiwiSDR at $host:$port, freq=${frequencyHz}Hz ...")
        assertTrue("open() returned false", source.open(Mockito.mock(Context::class.java), callback))
        assertTrue(
            "never became ready within 15s (error: ${callback.errorMessage})",
            callback.readyLatch.await(15, TimeUnit.SECONDS)
        )

        println("Ready. sampleRate=${source.sampleRate} packetSize=${source.packetSize}")
        assertTrue("advertised sample rate looks implausible for KiwiSDR IQ mode", source.sampleRate in 8000..25000)

        val framesReceived = AtomicInteger(0)
        var nonZeroSampleSeen = false
        val deadline = System.currentTimeMillis() + runDurationSeconds * 1000

        try {
            while (System.currentTimeMillis() < deadline) {
                val packet = source.getPacket(1000) ?: continue
                framesReceived.incrementAndGet()

                val numSamples = source.packetSize / source.bytesPerSample
                val sp = SamplePacket(numSamples)
                source.mixPacketIntoSamplePacket(packet, sp, source.frequency)
                for (i in 0 until sp.size()) {
                    if (sp.re(i) != 0.0f || sp.im(i) != 0.0f) {
                        nonZeroSampleSeen = true
                    }
                }
                source.returnPacket(packet)
            }
        } finally {
            println("Received ${framesReceived.get()} frames over ${runDurationSeconds}s. Closing.")
            source.close()
        }

        assertTrue("received zero SND frames from the server", framesReceived.get() > 0)
        assertTrue("all received samples were exactly zero - likely a parsing bug", nonZeroSampleSeen)
    }
}
