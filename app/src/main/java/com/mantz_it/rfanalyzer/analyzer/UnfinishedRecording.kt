package com.mantz_it.rfanalyzer.analyzer

import android.util.Log
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.Recording
import com.mantz_it.rfanalyzer.database.calculateFileName
import com.mantz_it.rfanalyzer.ui.tabs.FilesourceFileFormat
import com.mantz_it.rfanalyzer.ui.tabs.SourceType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * <h1>RF Analyzer - Unfinished Recordings</h1>
 *
 * Module:      UnfinishedRecording.kt
 * Description: A recording that never finished because the app was force-stopped, crashed or was
 *              killed while it ran. A running recording writes to a fixed file name and keeps its
 *              details next to it ("<file>.json"); finishing renames the file and stores its row.
 *              So a file under one of those names at app start, while nothing records, is an
 *              unfinished recording, and the user keeps it (stored like a finished one) or deletes
 *              it. Looking for one costs two file checks, however many recordings there are.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** What a recording is stored as, known when it starts. */
@Serializable
data class RecordingDetails(
    val name: String,
    val frequency: Long,
    val sampleRate: Long,
    /** Start time (ms). */
    val date: Long,
    val fileFormat: FilesourceFileFormat,
    val isBaseband: Boolean,
) {
    fun toRecording(file: File, sizeInBytes: Long) = Recording(
        name = name, frequency = frequency, sampleRate = sampleRate, date = date, fileFormat = fileFormat,
        sizeInBytes = sizeInBytes, filePath = file.absolutePath, favorite = false,
    )
}

/**
 * What a recording started now is stored as - read again when it ends, as the source can't be
 * retuned while a recording runs. A baseband recording is the channel at the mode's baseband rate,
 * always 32-bit float IQ.
 */
fun AppStateRepository.recordingDetails(isBaseband: Boolean) = if (isBaseband) RecordingDetails(
    name = recordingName.value,
    frequency = channelFrequency.value,
    sampleRate = demodulationMode.value.basebandSamplerate.toLong(),
    date = recordingStartedTimestamp.value,
    fileFormat = FilesourceFileFormat.AIRSPYHF,
    isBaseband = true,
) else RecordingDetails(
    name = recordingName.value,
    frequency = sourceFrequency.value,
    sampleRate = sourceSampleRate.value,
    date = recordingStartedTimestamp.value,
    fileFormat = when (sourceType.value) {
        SourceType.HACKRF -> FilesourceFileFormat.HACKRF
        SourceType.RTLSDR -> FilesourceFileFormat.RTLSDR
        SourceType.AIRSPY -> FilesourceFileFormat.AIRSPY
        SourceType.AIRSPYHF -> FilesourceFileFormat.AIRSPYHF
        SourceType.HYDRASDR -> FilesourceFileFormat.HYDRASDR
        SourceType.KIWISDR -> FilesourceFileFormat.HYDRASDR // same 16-bit signed IQ byte layout
        SourceType.FILESOURCE -> filesourceFileFormat.value
    },
    isBaseband = false,
)

/** An unfinished recording: its file and the row it would get. */
data class UnfinishedRecording(
    val file: File,
    val recording: Recording,
    val isBaseband: Boolean,
    /** False for a file an older version left without its details: they are a guess. */
    val detailsKnown: Boolean,
)

/** The recording's length, from its size. */
fun Recording.durationMs(): Long =
    if (sampleRate <= 0) 0 else sizeInBytes / fileFormat.bytesPerSample * 1000 / sampleRate

object UnfinishedRecordings {
    private const val TAG = "UnfinishedRecordings"
    /** The file names of a running recording (also 2.2.1's raw recording name). */
    const val RAW_FILE_NAME = "ongoing_recording.iq"
    const val BASEBAND_FILE_NAME = "ongoing_baseband_recording.cf32"

    private val json = Json { ignoreUnknownKeys = true }

    private fun detailsFile(file: File) = File(file.parentFile, "${file.name}.json")

    /** When a recording starts. Failing only costs the details of an unfinished recording. */
    fun writeDetails(file: File, details: RecordingDetails) {
        runCatching { detailsFile(file).writeText(json.encodeToString(RecordingDetails.serializer(), details)) }
            .onFailure { Log.w(TAG, "writeDetails: ${it.message}") }
    }

    /** When a recording is stored. */
    fun deleteDetails(file: File) {
        detailsFile(file).delete()
    }

    /**
     * The unfinished recordings in [dir] - call only while nothing records. A file without samples
     * (a recording that never got any) is deleted right away. [guess] supplies the details for a
     * file an older version left without them: the source's settings now.
     */
    fun find(dir: File, guess: (isBaseband: Boolean) -> RecordingDetails): List<UnfinishedRecording> =
        listOf(RAW_FILE_NAME to false, BASEBAND_FILE_NAME to true).mapNotNull { (name, isBaseband) ->
            val file = File(dir, name)
            if (!file.exists()) return@mapNotNull null
            if (file.length() == 0L) {
                delete(file)
                return@mapNotNull null
            }
            val stored = runCatching { json.decodeFromString(RecordingDetails.serializer(), detailsFile(file).readText()) }.getOrNull()
            val details = stored ?: guess(isBaseband).let { guessed ->
                // it ended when the file was last written
                val endMs = file.lastModified()
                guessed.copy(date = endMs - guessed.toRecording(file, file.length()).durationMs())
            }
            UnfinishedRecording(file, details.toRecording(file, file.length()), isBaseband, detailsKnown = stored != null)
        }

    /**
     * Keeps it: the file gets a finished recording's name (never one that exists - the next
     * recording would overwrite a file left under the running name). Returns the row to insert.
     */
    fun keep(unfinished: UnfinishedRecording): Recording {
        val name = unfinished.recording.calculateFileName(isBaseband = unfinished.isBaseband)
        val dir = unfinished.file.parentFile
        val target = generateSequence(1) { it + 1 }
            .map { n -> File(dir, if (n == 1) name else name.substringBeforeLast('.') + "_$n." + name.substringAfterLast('.')) }
            .first { !it.exists() }
        val kept = if (unfinished.file.renameTo(target)) target else unfinished.file
        deleteDetails(unfinished.file)
        return unfinished.recording.copy(filePath = kept.absolutePath)
    }

    fun delete(unfinished: UnfinishedRecording) = delete(unfinished.file)

    private fun delete(file: File) {
        file.delete()
        deleteDetails(file)
    }
}
