package com.mantz_it.rfanalyzer.source

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.mantz_it.rfanalyzer.ui.tabs.FilesourceFileFormat
import com.mantz_it.rfanalyzer_ipc.SamplePacket
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.io.File
import kotlin.random.Random

/**
 * <h1>RF Analyzer - File IQ Source Tests</h1>
 *
 * Module:      TestFileIQSource.kt
 * Description: The file source: packets of 1/20 s, paced in real time, stamped with their place in
 *              the file.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestFileIQSource {
    @get:Rule val tmp = TemporaryFolder()

    private val start = 1_700_000_000_000L
    private val errors = mutableListOf<String>()
    private var ready = 0
    private val callback = object : IQSourceInterface.Callback {
        override fun onIQSourceReady(source: IQSourceInterface) { ready++ }
        override fun onIQSourceError(source: IQSourceInterface, message: String) { errors.add(message) }
        override fun onIQSourceInformation(source: IQSourceInterface, message: String) {}
    }

    private fun fileUri(file: File): Uri = mock(Uri::class.java).also {
        `when`(it.scheme).thenReturn("file")
        `when`(it.path).thenReturn(file.absolutePath)
        `when`(it.lastPathSegment).thenReturn(file.name)
    }

    /** 48 kSps 8-bit: packets of 4800 bytes; the file holds 3.5 of them. */
    private fun source(repeat: Boolean): Pair<FileIQSource, ByteArray> {
        val content = Random(5).nextBytes(4800 * 3 + 2400)
        val file = tmp.newFile("rec_7100kHz_48kSps.cu8").apply { writeBytes(content) }
        val source = FileIQSource()
        source.init(fileUri(file), mock(ContentResolver::class.java), 48_000, 7_100_000L, repeat, FilesourceFileFormat.RTLSDR, start)
        return source to content
    }

    @Test
    fun playsTheFileInRealTimeAndEndsAtItsEnd() {
        val (source, content) = source(repeat = false)
        assertEquals(4800, source.packetSize)
        assertEquals(2, source.bytesPerSample)
        assertEquals("IQ-File: rec_7100kHz_48kSps.cu8", source.name)
        assertTrue(source.open(mock(Context::class.java), callback))
        assertEquals(1, ready)
        source.startSampling()
        val t0 = System.nanoTime()
        for (i in 0 until 3) {
            val packet = source.getPacket(1000)!!
            assertArrayEquals(content.copyOfRange(i * 4800, (i + 1) * 4800), packet)
            // its samples were received at the recording's start plus their place in the file
            val samplePacket = SamplePacket(2400)
            assertEquals(2400, source.mixPacketIntoSamplePacket(packet, samplePacket, 7_100_000L))
            assertEquals(start + i * 50L, samplePacket.timestamp)
            source.returnPacket(packet)
        }
        assertTrue("3 packets of 50 ms take their time", (System.nanoTime() - t0) / 1_000_000 >= 140)
        assertNull(source.getPacket(1000)) // the half packet at the end is not played
        assertEquals(listOf("End of File"), errors)
        assertTrue(source.close())
    }

    @Test
    fun repeatStartsOverWithTheFirstPacket() {
        val (source, content) = source(repeat = true)
        source.open(mock(Context::class.java), callback)
        source.startSampling()
        repeat(3) { source.getPacket(1000) }
        val again = source.getPacket(1000)!!
        assertArrayEquals(content.copyOfRange(0, 4800), again)
        val samplePacket = SamplePacket(2400)
        source.mixPacketIntoSamplePacket(again, samplePacket, 7_100_000L)
        assertEquals(start, samplePacket.timestamp)
        assertTrue(errors.isEmpty())
        source.close()
    }

    @Test
    fun theFileFormatSetsTheSampleSize() {
        val source = FileIQSource()
        val file = tmp.newFile("x.cf32")
        for ((format, bytes) in listOf(FilesourceFileFormat.HACKRF to 2, FilesourceFileFormat.AIRSPY to 4, FilesourceFileFormat.AIRSPYHF to 8)) {
            source.init(fileUri(file), mock(ContentResolver::class.java), 1_000_000, 0L, false, format, 0L)
            assertEquals(bytes, source.bytesPerSample)
            assertEquals(1_000_000 * bytes / 20, source.packetSize)
        }
        assertArrayEquals(intArrayOf(1_000_000), source.supportedSampleRates)
    }
}
