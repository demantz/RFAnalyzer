package com.mantz_it.rfanalyzer.analyzer

import com.mantz_it.rfanalyzer.ui.tabs.FilesourceFileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * <h1>RF Analyzer - Unfinished Recordings Tests</h1>
 *
 * Module:      TestUnfinishedRecordings.kt
 * Description: A recording the app was closed during is found at the next start, and kept or
 *              deleted.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestUnfinishedRecordings {
    @get:Rule val tmp = TemporaryFolder()

    private val details = RecordingDetails("My Recording", 97_000_000L, 1_000_000L, 1_700_000_000_000L, FilesourceFileFormat.RTLSDR, isBaseband = false)
    private val noGuess: (Boolean) -> RecordingDetails = { error("details are known") }

    private fun ongoingRaw(bytes: Int, withDetails: Boolean = true): File {
        val file = File(tmp.root, UnfinishedRecordings.RAW_FILE_NAME).apply { writeBytes(ByteArray(bytes)) }
        if (withDetails) UnfinishedRecordings.writeDetails(file, details)
        return file
    }

    @Test
    fun aRecordingWithItsDetailsIsFound() {
        ongoingRaw(2_000_000)
        val found = UnfinishedRecordings.find(tmp.root, noGuess).single()
        assertTrue(found.detailsKnown)
        assertFalse(found.isBaseband)
        assertEquals(97_000_000L, found.recording.frequency)
        assertEquals(2_000_000L, found.recording.sizeInBytes)
        assertEquals(1000L, found.recording.durationMs())  // 8-bit IQ: 2 bytes per sample at 1 MSps
    }

    @Test
    fun nothingRecordedIsNoUnfinishedRecording() {
        assertTrue(UnfinishedRecordings.find(tmp.root, noGuess).isEmpty())
        val empty = ongoingRaw(0)
        assertTrue(UnfinishedRecordings.find(tmp.root, noGuess).isEmpty())
        assertFalse(empty.exists())  // removed right away
    }

    @Test
    fun anOlderVersionsFileGetsTheSettingsAsAGuess() {
        val file = ongoingRaw(4_000_000, withDetails = false).apply { setLastModified(1_700_000_010_000L) }
        val found = UnfinishedRecordings.find(tmp.root) { details.copy(date = 0L) }.single()
        assertFalse(found.detailsKnown)
        assertEquals(1_700_000_008_000L, found.recording.date)  // it ended when last written, 2 s before
        assertEquals(file.absolutePath, found.recording.filePath)
    }

    @Test
    fun keepingRenamesItNeverOverAnExistingFile() {
        ongoingRaw(2_000_000)
        val found = UnfinishedRecordings.find(tmp.root, noGuess).single()
        val taken = File(tmp.root, File(UnfinishedRecordings.keep(found).filePath).name)
        assertTrue(taken.exists())
        assertFalse(File(tmp.root, UnfinishedRecordings.RAW_FILE_NAME).exists())
        // a second one with the same details: the name is taken, so it gets a suffix
        ongoingRaw(2_000_000)
        val second = File(UnfinishedRecordings.keep(UnfinishedRecordings.find(tmp.root, noGuess).single()).filePath)
        assertTrue(second.name, second.name.endsWith("_2.cu8"))
        assertEquals(listOf(second.name, taken.name).sorted(), tmp.root.list()!!.sorted())  // no details files left
    }

    @Test
    fun deletingRemovesFileAndDetails() {
        ongoingRaw(2_000_000)
        UnfinishedRecordings.delete(UnfinishedRecordings.find(tmp.root, noGuess).single())
        assertTrue(tmp.root.list()!!.isEmpty())
    }
}
