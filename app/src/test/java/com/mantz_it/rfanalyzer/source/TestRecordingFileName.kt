package com.mantz_it.rfanalyzer.source

import com.mantz_it.rfanalyzer.ui.tabs.FilesourceFileFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.ZoneOffset

/**
 * <h1>RF Analyzer - Recording File Name Tests</h1>
 *
 * Module:      TestRecordingFileName.kt
 * Description: What the file source reads from a recording's name (RecordingFileName) - real names
 *              from the phone.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestRecordingFileName {

    private fun parse(name: String) = RecordingFileName.parse(name, ZoneOffset.UTC)

    @Test
    fun rfAnalyzersOwnRecordings() {
        val r = parse("20261113-012148_My Recording_RTLSDR_97MHz_1MSps.iq")
        assertEquals(FilesourceFileFormat.RTLSDR, r.fileFormat)
        assertEquals(97_000_000L, r.frequencyHz)
        assertEquals(1_000_000L, r.sampleRate)
        assertEquals(1_794_532_908_000L, r.startTimeMs) // 2026-11-13 01:21:48 UTC

        val hz = parse("20250324-123813_gate_remote_RTLSDR_433756848Hz_2MSps.iq")
        assertEquals(433_756_848L, hz.frequencyHz)
        assertEquals(2_000_000L, hz.sampleRate)
    }

    @Test
    fun unitsAndPrefixes() {
        val khz = parse("000_ft8_300seconds_AIRSPYHF_14074kHz_12kSps.cf32")
        assertEquals(FilesourceFileFormat.AIRSPYHF, khz.fileFormat) // contains AIRSPY too
        assertEquals(14_074_000L, khz.frequencyHz)
        assertEquals(12_000L, khz.sampleRate)
        assertNull(khz.startTimeMs) // "000" is no time stamp

        val sps = parse("20250101-010101_psk31_14071526Hz_192000Sps.iq")
        assertEquals(14_071_526L, sps.frequencyHz)
        assertEquals(192_000L, sps.sampleRate)
        assertNull(sps.fileFormat) // neither a hardware name nor a known extension
    }

    @Test
    fun theExtensionIsTheFallbackForTheFormat() {
        assertEquals(FilesourceFileFormat.HACKRF, parse("capture.cs8").fileFormat)
        assertEquals(FilesourceFileFormat.RTLSDR, parse("capture.cu8").fileFormat)
        assertEquals(FilesourceFileFormat.AIRSPY, parse("capture.cs16").fileFormat)
        assertEquals(FilesourceFileFormat.AIRSPYHF, parse("capture.cf32").fileFormat)
        // the hardware name wins: .cs16 is HydraSDR's layout too
        assertEquals(FilesourceFileFormat.HYDRASDR, parse("x_HydraSDR_100MHz_2500kSps.cs16").fileFormat)
        assertEquals(FilesourceFileFormat.HACKRF, parse("hackrf_transfer_out.iq").fileFormat)
        assertEquals(FilesourceFileFormat.RTLSDR, parse("rtl-sdr dump.bin").fileFormat)
    }

    @Test
    fun namesThatSayNothing() {
        val r = parse("my file.iq")
        assertNull(r.fileFormat); assertNull(r.frequencyHz); assertNull(r.sampleRate); assertNull(r.startTimeMs)
        // a decimal frequency is not misread as its fraction
        assertNull(parse("x_97.5MHz.iq").frequencyHz)
        // a number too large for a Long is ignored, not thrown
        assertNull(parse("x_99999999999999999999Hz.iq").frequencyHz)
    }
}
