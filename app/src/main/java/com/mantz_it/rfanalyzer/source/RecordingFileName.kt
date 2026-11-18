package com.mantz_it.rfanalyzer.source

import com.mantz_it.rfanalyzer.ui.tabs.FilesourceFileFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * <h1>RF Analyzer - Recording File Name</h1>
 *
 * Module:      RecordingFileName.kt
 * Description: What a recording's file name says about it - the file source takes format,
 *              frequency, sample rate and start time from it when a file is opened, as RF Analyzer
 *              names its own recordings ("20261113-012148_My Recording_RTLSDR_97MHz_1MSps.iq") and
 *              other tools name theirs ("…_14074kHz_12kSps.cf32"). Each part is null when the name
 *              doesn't tell it.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
data class RecordingFileName(
    val fileFormat: FilesourceFileFormat?,
    val frequencyHz: Long?,
    val sampleRate: Long?,
    /** From a leading "yyyyMMdd-HHmmss_" (local time). */
    val startTimeMs: Long?,
) {
    companion object {
        private val TIMESTAMP_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

        fun parse(filename: String, zone: ZoneId = ZoneId.systemDefault()): RecordingFileName =
            RecordingFileName(formatOf(filename), frequencyOf(filename), sampleRateOf(filename), startTimeOf(filename, zone))

        private fun formatOf(name: String): FilesourceFileFormat? {
            // The hardware name wins over the extension: .cs16 is AIRSPY's and HYDRASDR's layout.
            // AIRSPYHF contains AIRSPY, so it is checked after it.
            var format = when (name.substringAfterLast('.', "").lowercase()) {
                "cs8" -> FilesourceFileFormat.HACKRF
                "cu8" -> FilesourceFileFormat.RTLSDR
                "cs16" -> FilesourceFileFormat.AIRSPY
                "cf32" -> FilesourceFileFormat.AIRSPYHF
                else -> null
            }
            val lower = name.lowercase()
            if ("hackrf" in lower) format = FilesourceFileFormat.HACKRF
            if ("rtlsdr" in lower || "rtl-sdr" in lower) format = FilesourceFileFormat.RTLSDR
            if ("airspy" in lower) format = FilesourceFileFormat.AIRSPY
            if ("airspyhf" in lower) format = FilesourceFileFormat.AIRSPYHF
            if ("hydrasdr" in lower) format = FilesourceFileFormat.HYDRASDR
            return format
        }

        /** "_2MSps", "-192kSps", " 192000Sps" - a number right after a separator, then the unit. */
        private fun sampleRateOf(name: String): Long? = numberWithUnit(name, "msps", 1_000_000)
            ?: numberWithUnit(name, "ksps", 1_000) ?: numberWithUnit(name, "sps", 1)

        private fun frequencyOf(name: String): Long? = numberWithUnit(name, "mhz", 1_000_000)
            ?: numberWithUnit(name, "khz", 1_000) ?: numberWithUnit(name, "hz", 1)

        private fun numberWithUnit(name: String, unit: String, factor: Long): Long? =
            Regex("""(?:^|[_\-\s])(\d+)$unit(?![a-z])""", RegexOption.IGNORE_CASE).find(name)
                ?.groupValues?.get(1)?.toLongOrNull()?.times(factor)

        private fun startTimeOf(name: String, zone: ZoneId): Long? = runCatching {
            LocalDateTime.parse(name.substringBefore("_"), TIMESTAMP_FORMAT).atZone(zone).toInstant().toEpochMilli()
        }.getOrNull()
    }
}
