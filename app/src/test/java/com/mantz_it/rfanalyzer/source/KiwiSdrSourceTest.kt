package com.mantz_it.rfanalyzer.source

import android.content.Context
import com.mantz_it.rfanalyzer.source.testutil.FakeKiwiSdrServer
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * <h1>RF Analyzer - KiwiSDR Source Tests</h1>
 *
 * Module:      KiwiSdrSourceTest.kt
 * Description: Tests KiwiSdrSource against FakeKiwiSdrServer (a hand-rolled local WebSocket server,
 *              not a real KiwiSDR). See KiwiSdrSourceLiveVerificationTest for the manual, opt-in
 *              test against a real public receiver - that's the actual protocol-correctness gate;
 *              these tests guard against regressions in KiwiSdrSource's own
 *              framing/parsing/lifecycle logic.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class KiwiSdrSourceTest {

    private val servers = mutableListOf<FakeKiwiSdrServer>()
    private val sources = mutableListOf<KiwiSdrSource>()

    @After
    fun tearDown() {
        sources.forEach { it.close() }
        servers.forEach { it.stop() }
        sources.clear()
        servers.clear()
    }

    private fun newServer(): FakeKiwiSdrServer {
        val server = FakeKiwiSdrServer()
        server.start()
        servers.add(server)
        return server
    }

    private fun newSource(
        host: String = "127.0.0.1",
        port: Int,
        password: String = "",
        identUser: String = "RFAnalyzer"
    ): KiwiSdrSource {
        val source = KiwiSdrSource(host, port, password, identUser)
        sources.add(source)
        return source
    }

    private fun mockContext(): Context = Mockito.mock(Context::class.java)

    private class RecordingCallback : IQSourceInterface.Callback {
        val readyLatch = CountDownLatch(1)
        val errorLatch = CountDownLatch(1)
        val errorCount = AtomicInteger(0)
        @Volatile var lastErrorMessage: String? = null

        override fun onIQSourceReady(source: IQSourceInterface) {
            readyLatch.countDown()
        }

        override fun onIQSourceError(source: IQSourceInterface, message: String) {
            lastErrorMessage = message
            errorCount.incrementAndGet()
            errorLatch.countDown()
        }

        override fun onIQSourceInformation(source: IQSourceInterface, message: String) {
        }
    }

    private fun connectAndBringReady(
        server: FakeKiwiSdrServer,
        callback: RecordingCallback,
        source: KiwiSdrSource
    ) {
        assertTrue("open() should return true", source.open(mockContext(), callback))
        assertTrue("server never observed a connection", server.awaitConnected())
        assertTrue("client never sent SET auth", server.awaitCommand("SET auth"))
        server.sendText("MSG sample_rate=12000")
        server.sendSndIqFrame(seq = 1, samples = listOf(1000 to -1000))
        assertTrue("onIQSourceReady never fired", callback.readyLatch.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun stopWhileConnectingCancelsTheConnectionWithoutAnError() {
        // a server that accepts the TCP connection but never answers the WebSocket upgrade:
        // the source is still connecting when the user presses Stop
        val silent = ServerSocket(0)
        try {
            val accepted = CountDownLatch(1)
            val closed = CountDownLatch(1)
            Thread {
                val socket = silent.accept()
                accepted.countDown()
                val buffer = ByteArray(4096)
                try { while (socket.getInputStream().read(buffer) >= 0) Unit } catch (_: Exception) {}
                closed.countDown()
            }.apply { isDaemon = true; start() }
            val source = newSource(port = silent.localPort)
            val callback = RecordingCallback()
            assertTrue(source.open(mockContext(), callback))
            assertTrue("never connected", accepted.await(5, TimeUnit.SECONDS))
            source.close()
            // the slot is given back at once, not when the server times out
            assertTrue("connection still open after Stop", closed.await(5, TimeUnit.SECONDS))
            Thread.sleep(300)
            assertEquals("a Stop is not an error", 0, callback.errorCount.get())
        } finally {
            silent.close()
        }
    }

    @Test
    fun openSendsHandshakeSequenceInOrderAndFiresOnIQSourceReady() {
        val server = newServer()
        val source = newSource(port = server.port)
        source.frequency = 7_070_000L
        val callback = RecordingCallback()

        connectAndBringReady(server, callback, source)

        assertNotNull(server.requestPath)
        assertFalse("path must not use a /kiwi/ prefix", server.requestPath!!.startsWith("/kiwi/"))
        assertTrue(server.requestPath!!.endsWith("/SND"))

        // onIQSourceReady fires off the read path (the SND frame); the SET command writes
        // triggered by the preceding MSG sample_rate frame are queued/flushed asynchronously
        // by OkHttp's writer, so wait for the tail of the sequence before inspecting order.
        assertTrue("compression=0 never arrived", server.awaitCommand("SET compression=0"))
        assertTrue("keepalive never arrived", server.awaitCommand("SET keepalive"))

        val commands = server.receivedCommands
        val authIdx = commands.indexOfFirst { it.startsWith("SET auth t=kiwi p=") }
        val identIdx = commands.indexOfFirst { it.startsWith("SET ident_user=RFAnalyzer") }
        val modIdx = commands.indexOfFirst { it.startsWith("SET mod=iq low_cut=-5000 high_cut=5000 freq=7070.0") }
        val compIdx = commands.indexOfFirst { it == "SET compression=0" }
        val keepaliveIdx = commands.indexOfFirst { it == "SET keepalive" }

        assertTrue("auth not sent", authIdx >= 0)
        assertTrue("ident_user not sent after auth. commands=$commands", identIdx > authIdx)
        assertTrue("mod=iq not sent with expected freq", modIdx > identIdx)
        assertTrue("compression=0 not sent after mod=iq", compIdx > modIdx)
        assertTrue("keepalive never sent", keepaliveIdx > authIdx)
    }

    @Test
    fun decodesBigEndianSamplesRegardlessOfLittleEndianFlag() {
        val server = newServer()
        val source = newSource(port = server.port)
        val callback = RecordingCallback()

        assertTrue(source.open(mockContext(), callback))
        assertTrue(server.awaitConnected())
        assertTrue(server.awaitCommand("SET auth"))
        server.sendText("MSG sample_rate=12000")

        val samples = listOf(1000 to -1000, 12345 to -12345)
        server.sendSndIqFrame(seq = 1, samples = samples)
        assertTrue(callback.readyLatch.await(5, TimeUnit.SECONDS))

        val numComplexSamples = source.packetSize / source.bytesPerSample
        assertEquals(samples.size, numComplexSamples)

        val packet1 = source.getPacket(2000)
        assertNotNull("no packet received for frame 1", packet1)
        val sp1 = SamplePacket(numComplexSamples)
        source.mixPacketIntoSamplePacket(packet1!!, sp1, source.frequency)
        assertEquals(2, sp1.size())
        assertEquals(1000 / 32768.0f, sp1.re(0), 1e-6f)
        assertEquals(-1000 / 32768.0f, sp1.im(0), 1e-6f)
        assertEquals(12345 / 32768.0f, sp1.re(1), 1e-6f)
        assertEquals(-12345 / 32768.0f, sp1.im(1), 1e-6f)
        source.returnPacket(packet1!!)

        // Same big-endian sample bytes, but with the (irrelevant outside camp mode) LE flag
        // bit set this time - must decode identically, since KiwiSdrSource must ignore it.
        server.sendSndIqFrame(seq = 2, samples = samples, extraFlags = FakeKiwiSdrServer.SND_FLAG_LITTLE_ENDIAN)
        val packet2 = source.getPacket(2000)
        assertNotNull("no packet received for frame 2", packet2)
        val sp2 = SamplePacket(numComplexSamples)
        source.mixPacketIntoSamplePacket(packet2!!, sp2, source.frequency)
        assertEquals(sp1.re(0), sp2.re(0), 0f)
        assertEquals(sp1.im(0), sp2.im(0), 0f)
        assertEquals(sp1.re(1), sp2.re(1), 0f)
        assertEquals(sp1.im(1), sp2.im(1), 0f)
    }

    @Test
    fun malformedFrameIsDroppedAndDoesNotCorruptSubsequentFrames() {
        val server = newServer()
        val source = newSource(port = server.port)
        val callback = RecordingCallback()

        assertTrue(source.open(mockContext(), callback))
        assertTrue(server.awaitConnected())
        assertTrue(server.awaitCommand("SET auth"))
        server.sendText("MSG sample_rate=12000")

        server.sendMalformedSndFrame()
        assertFalse("malformed frame must not fire onIQSourceReady", callback.readyLatch.await(300, TimeUnit.MILLISECONDS))

        server.sendSndIqFrame(seq = 1, samples = listOf(50 to -50))
        assertTrue("good frame after malformed one never arrived", callback.readyLatch.await(5, TimeUnit.SECONDS))

        val packet = source.getPacket(2000)
        assertNotNull(packet)
        val sp = SamplePacket(source.packetSize / source.bytesPerSample)
        source.mixPacketIntoSamplePacket(packet!!, sp, source.frequency)
        assertEquals(1, sp.size())
        assertEquals(50 / 32768.0f, sp.re(0), 1e-6f)
        assertEquals(-50 / 32768.0f, sp.im(0), 1e-6f)
    }

    @Test
    fun closeSendsWebSocketCloseFramePromptly() {
        val server = newServer()
        val source = newSource(port = server.port)
        val callback = RecordingCallback()
        connectAndBringReady(server, callback, source)

        source.close()

        assertTrue("server never observed a client close frame", server.clientCloseObserved.await(3, TimeUnit.SECONDS))
    }

    @Test
    fun abruptDisconnectFiresErrorOnceWithNoReconnectAttempt() {
        val server = newServer()
        val source = newSource(port = server.port)
        val callback = RecordingCallback()
        connectAndBringReady(server, callback, source)

        server.disconnectAbruptly()

        assertTrue("onIQSourceError never fired after disconnect", callback.errorLatch.await(5, TimeUnit.SECONDS))
        assertEquals(1, callback.errorCount.get())

        // No reconnect loop exists in KiwiSdrSource - give it a window and confirm no
        // second error (which a reconnect-then-fail-again cycle would produce) shows up.
        Thread.sleep(1000)
        assertEquals(1, callback.errorCount.get())
    }

    @Test
    fun authRejectionFiresOnIQSourceErrorNotOnIQSourceReady() {
        val server = newServer()
        val source = newSource(port = server.port, password = "wrong")
        val callback = RecordingCallback()

        assertTrue(source.open(mockContext(), callback))
        assertTrue(server.awaitConnected())
        assertTrue(server.awaitCommand("SET auth"))
        server.sendText("MSG badp=1")

        assertTrue("onIQSourceError never fired for bad password", callback.errorLatch.await(5, TimeUnit.SECONDS))
        assertFalse("onIQSourceReady must not fire after auth rejection", callback.readyLatch.await(300, TimeUnit.MILLISECONDS))
    }

    @Test
    fun unreachableHostFiresOnIQSourceErrorRatherThanHanging() {
        val closedPort = ServerSocket(0).use { it.localPort }
        val source = newSource(port = closedPort)
        val callback = RecordingCallback()

        assertTrue(source.open(mockContext(), callback))

        assertTrue("onIQSourceError never fired for an unreachable host", callback.errorLatch.await(10, TimeUnit.SECONDS))
        assertNotNull("error message should describe the failure", callback.lastErrorMessage)
        assertFalse(callback.readyLatch.await(200, TimeUnit.MILLISECONDS))
    }

    @Test
    fun reportedSampleRateSnapsToTheNominalRate() {
        assertEquals(12000, KiwiSdrSource.nominalSampleRate(11998.999528)) // DL0WH, 2026-10-19
        assertEquals(12000, KiwiSdrSource.nominalSampleRate(12001.135))
        assertEquals(12000, KiwiSdrSource.nominalSampleRate(12000.0))
        assertEquals(20250, KiwiSdrSource.nominalSampleRate(20250.47))
        // not near a multiple of 250 Hz: just rounded
        assertEquals(12346, KiwiSdrSource.nominalSampleRate(12345.6))
    }

    @Test
    fun serverSampleRateIsUsedAsNominalRate() {
        val server = newServer()
        val source = newSource(port = server.port)
        val callback = RecordingCallback()

        assertTrue(source.open(mockContext(), callback))
        assertTrue(server.awaitConnected())
        assertTrue(server.awaitCommand("SET auth"))
        server.sendText("MSG sample_rate=11998.999528")
        server.sendSndIqFrame(seq = 1, samples = listOf(1000 to -1000))
        assertTrue("onIQSourceReady never fired", callback.readyLatch.await(5, TimeUnit.SECONDS))

        assertEquals(12000, source.sampleRate)
    }

    @Test
    fun gpsBlockGivesTheUtcTimeOfTheFirstSample() {
        // DL0WH, 2026-10-19: frame received at 1792396717.233 (NTP-synced PC) with GPS time of
        // week 115134.977383830 s -> 2026-10-18 00:00 UTC (week start) + TOW - 18 leap seconds
        val frame = FakeKiwiSdrServer().buildSndIqFrame(1, listOf(0 to 0), gpsSeconds = 115134, gpsNanos = 977_383_830)
        val gpsOffset = 3 + 7
        assertEquals(1792396716977L, KiwiSdrSource.gpsTimestampMs(frame, gpsOffset, wallClockMs = 1792396717233L))
        // the week is the wall clock's, also across a week boundary: the last half second of week
        // 2441, received when the wall clock is already in week 2442
        val endOfWeek = FakeKiwiSdrServer().buildSndIqFrame(1, listOf(0 to 0), gpsSeconds = 604799, gpsNanos = 500_000_000)
        assertEquals(1792886381500L, KiwiSdrSource.gpsTimestampMs(endOfWeek, gpsOffset, wallClockMs = 1792886382500L))
        // no GPS time yet
        assertEquals(null, KiwiSdrSource.gpsTimestampMs(FakeKiwiSdrServer().buildSndIqFrame(1, listOf(0 to 0)), gpsOffset, 1792396717233L))
        // a stamp far from the wall clock is not trusted (a receiver without GPS)
        assertEquals(null, KiwiSdrSource.gpsTimestampMs(frame, gpsOffset, wallClockMs = 1792396717233L + 120_000L))
    }

    @Test
    fun packetsCarryTheirGpsTimeAndFallBackToTheClockWithout() {
        val server = newServer()
        val source = newSource(port = server.port)
        val callback = RecordingCallback()
        assertTrue(source.open(mockContext(), callback))
        assertTrue(server.awaitConnected())
        assertTrue(server.awaitCommand("SET auth"))
        server.sendText("MSG sample_rate=12000")

        // a GPS stamp 1.5 s in the past, as the network latency would make it
        val firstSampleMs = (ClockCorrection.now() - 1500) / 1000 * 1000 + 250
        val gpsMs = firstSampleMs - 315_964_800_000L + 18_000L
        val timeOfWeekMs = Math.floorMod(gpsMs, 7 * 86_400_000L)
        val samples = List(120) { 0 to 0 }
        server.sendSndIqFrame(seq = 1, samples = samples, gpsSeconds = timeOfWeekMs / 1000, gpsNanos = timeOfWeekMs % 1000 * 1_000_000)
        assertTrue(callback.readyLatch.await(5, TimeUnit.SECONDS))
        val packet = source.getPacket(2000)
        assertNotNull(packet)
        val sp = SamplePacket(samples.size)
        source.mixPacketIntoSamplePacket(packet!!, sp, 0L)
        assertEquals(firstSampleMs, sp.timestamp)
        source.returnPacket(packet!!)

        // without a GPS time: the corrected wall clock, minus the packet's duration (10 ms)
        server.sendSndIqFrame(seq = 2, samples = samples)
        val packet2 = source.getPacket(2000)
        assertNotNull(packet2)
        val sp2 = SamplePacket(samples.size)
        val before = ClockCorrection.now()
        source.mixPacketIntoSamplePacket(packet2!!, sp2, source.frequency)
        val after = ClockCorrection.now()
        assertTrue("${sp2.timestamp} not in [${before - 10}, ${after - 10}]", sp2.timestamp in before - 10..after - 10)
    }
}
