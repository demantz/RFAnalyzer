package com.mantz_it.rfanalyzer.source

import android.content.Context
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock
import java.io.DataInputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random

/**
 * <h1>RF Analyzer - rtl_tcp Source Tests</h1>
 *
 * Module:      TestRtltcpSource.kt
 * Description: The rtl_tcp client against a fake server: handshake, the settings sent on connect,
 *              samples, commands, shutdown.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestRtltcpSource {

    /** Speaks rtl_tcp's server side: the 12-byte header (magic, tuner type, gain count), then 5-byte commands in, samples out. */
    private class FakeRtlTcpServer : AutoCloseable {
        val server = ServerSocket(0)
        val commands = LinkedBlockingQueue<Pair<Int, Int>>()
        @Volatile var client: Socket? = null
        private val connected = CountDownLatch(1)

        init {
            thread(isDaemon = true) {
                val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
                client = socket
                socket.getOutputStream().write(ByteBuffer.allocate(12).put("RTL0".toByteArray()).putInt(5).putInt(29).array())
                connected.countDown()
                val input = DataInputStream(socket.getInputStream())
                runCatching { while (true) commands.put(input.readUnsignedByte() to input.readInt()) }
            }
        }

        fun send(bytes: ByteArray) {
            assertTrue(connected.await(5, TimeUnit.SECONDS))
            client!!.getOutputStream().apply { write(bytes); flush() }
        }

        fun nextCommand(): Pair<Int, Int> = commands.poll(5, TimeUnit.SECONDS) ?: throw AssertionError("no command")

        override fun close() { client?.close(); server.close() }
    }

    private val server = FakeRtlTcpServer()
    private val ready = CountDownLatch(1)
    private val errors = LinkedBlockingQueue<String>()
    private val callback = object : IQSourceInterface.Callback {
        override fun onIQSourceReady(source: IQSourceInterface) { ready.countDown() }
        override fun onIQSourceError(source: IQSourceInterface, message: String) { errors.add(message) }
        override fun onIQSourceInformation(source: IQSourceInterface, message: String) {}
    }

    @After fun tearDown() = server.close()

    @Test
    fun connectsSendsItsSettingsStreamsAndShutsTheServerDown() {
        val source = RtltcpSource("127.0.0.1", server.server.localPort)
        source.frequency = 100_000_000L
        source.sampleRate = 1_024_000
        source.frequencyCorrection = 3
        assertTrue(source.open(mock(Context::class.java), callback))
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals("RTL-SDR (R820T) at 127.0.0.1:${server.server.localPort}", source.name)
        assertEquals(16384, source.packetSize)
        assertEquals(2, source.bytesPerSample)

        // what the client sends after connecting: frequency, rate, gain mode, gain, correction, AGC, direct sampling
        assertEquals(0x01 to 100_000_000, server.nextCommand())
        assertEquals(0x02 to 1_024_000, server.nextCommand())
        assertEquals(0x03 to 1, server.nextCommand())
        assertEquals(0x04 to 0, server.nextCommand())
        assertEquals(0x05 to 3, server.nextCommand())
        assertEquals(0x08 to 0, server.nextCommand())
        assertEquals(0x09 to 0, server.nextCommand())

        source.startSampling()
        val samples = Random(9).nextBytes(16384 * 3)
        server.send(samples)
        for (i in 0 until 3) {
            val packet = source.getPacket(2000)
            assertNotNull("packet $i", packet)
            assertArrayEquals(samples.copyOfRange(i * 16384, (i + 1) * 16384), packet)
            source.returnPacket(packet!!)
        }

        source.frequency = 145_000_000L
        assertEquals(0x01 to 145_000_000, server.nextCommand())
        source.gain = 197
        assertEquals(0x04 to 197, server.nextCommand())

        source.stopSampling() // with no samples arriving: no error
        assertTrue(source.close())
        assertEquals(0x7e to 0, server.nextCommand()) // the shutdown command
        assertEquals(emptyList<String>(), errors.toList())
    }

    @Test
    fun aNetworkStallLongerThanTheSocketTimeoutIsRiddenOut() {
        val source = RtltcpSource("127.0.0.1", server.server.localPort)
        source.sampleRate = 1_024_000
        source.open(mock(Context::class.java), callback)
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        source.startSampling()
        server.send(ByteArray(16384) { 1 })
        assertNotNull(source.getPacket(2000)?.also { source.returnPacket(it) })
        Thread.sleep(2500) // 2.5 s without samples (the socket times out after 1 s)
        server.send(ByteArray(16384) { 2 })
        val after = source.getPacket(2000)
        assertNotNull("the stream goes on after the stall", after)
        assertEquals(2.toByte(), after!![0])
        assertEquals(emptyList<String>(), errors.toList())
        source.stopSampling()
        source.close()
    }

    @Test
    fun aServerThatIsntThereIsReported() {
        val port = ServerSocket(0).use { it.localPort } // nothing listens here any more
        val source = RtltcpSource("127.0.0.1", port)
        source.open(mock(Context::class.java), callback)
        assertEquals("Couldn't connect to rtl_tcp instance", errors.poll(10, TimeUnit.SECONDS))
        source.close()
    }
}
