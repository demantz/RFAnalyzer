package com.mantz_it.rfanalyzer.ui

import com.mantz_it.rfanalyzer.digiext.FM_BAND_TARGET
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.tabs.SourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Tuning Tests</h1>
 *
 * Module:      TestTuning.kt
 * Description: Tests for [computeTuning], [capabilityError] and [channelFor]: a station inside the
 *              viewport moves only the channel, one outside it moves the viewport with the mode's
 *              span, the sample rate is raised where a mode needs it but never lowered, the
 *              viewport stays inside what the source receives, a recording must cover the channel,
 *              and a KiwiSDR is tuned to the viewport's centre.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

class TestTuning {

    private val rtlsdr = SourceLimits(500_000L, 2_000_000_000L, SourceType.RTLSDR.defaultSupportedSampleRates)
    private val hackrf = SourceLimits(1L, 7_250_000_000L, SourceType.HACKRF.defaultSupportedSampleRates)
    private val airspyHf = SourceLimits(500_000L, 260_000_000L, SourceType.AIRSPYHF.defaultSupportedSampleRates)
    private val kiwi = SourceLimits(0L, 30_000_000L, SourceType.KIWISDR.defaultSupportedSampleRates)

    private val adsb = TuneTarget("1090 MHz", DemodulationMode.ADSB, 1_090_000_000, 1_000_000)
    private val ft8 = TuneTarget("FT8 20m", DemodulationMode.FT8, 14_074_000, 3000)
    private val aprs = TuneTarget("APRS", DemodulationMode.AX25, 144_800_000, 12500)
    private val lsb = TuneTarget("LSB", DemodulationMode.LSB, 3_700_000, 2700)

    /** A viewport of [span] around [viewport] on a source at [sourceFreq] with [rate]. */
    private fun view(rate: Long, sourceFreq: Long, viewport: Long = sourceFreq, span: Long = rate, channel: Long = 0) =
        CurrentView(rate, sourceFreq, viewport, span, channel)

    private fun tune(target: TuneTarget, limits: SourceLimits, current: CurrentView, centered: Boolean = false, file: Boolean = false, dcSpike: Boolean = true) =
        computeTuning(target, target.mode!!, target.channelWidth, limits, current, centered, file, dcSpike)

    private fun assertViewportReceived(t: TuningPlan) {
        assertTrue(t.viewportFrequency - t.viewportSpan / 2 >= t.sourceFrequency - t.sampleRate / 2)
        assertTrue(t.viewportFrequency + t.viewportSpan / 2 <= t.sourceFrequency + t.sampleRate / 2)
    }

    @Test
    fun aStationInsideTheViewportMovesNothingButTheChannel() {
        // 2 MHz viewport around 145 MHz shows APRS at 144.8 MHz: a tap on its label tunes, nothing moves
        val current = view(2_400_000, 145_000_000, viewport = 145_000_000, span = 2_000_000)
        val t = tune(aprs, rtlsdr, current)
        assertEquals(144_800_000L, t.channelFrequency)
        assertEquals(TuningPlan(2_400_000, 145_000_000, 145_000_000, 2_000_000, 144_800_000), t)
    }

    @Test
    fun aStationOutsideTheViewportMovesItWithTheModesSpan() {
        val current = view(2_400_000, 145_000_000, viewport = 145_500_000, span = 500_000) // 144.8 isn't visible
        val t = tune(aprs, rtlsdr, current)
        assertEquals(144_800_000L, t.viewportFrequency)
        assertEquals(50_000L, t.viewportSpan) // 4 x 12.5 kHz
        assertEquals(145_000_000L, t.sourceFrequency) // still received: no retune
        assertViewportReceived(t)
    }

    @Test
    fun aSignalHalfOutsideTheViewportMovesIt() {
        // FT8's signals lie 0..3 kHz above the dial frequency; a viewport ending at +1 kHz doesn't show them
        val current = view(12_000, 14_070_000, viewport = 14_070_000, span = 10_000)
        val t = tune(ft8, kiwi, current, dcSpike = false)
        assertEquals(14_075_500L, t.viewportFrequency)
        assertEquals(10_000L, t.viewportSpan)
    }

    @Test
    fun theViewportIsCentredOnTheSignalNotTheDialFrequency() {
        assertEquals(14_075_500L, tune(ft8, kiwi, view(12_000, 7_000_000), dcSpike = false).viewportFrequency)
        assertEquals(3_698_650L, tune(lsb, airspyHf, view(912_000, 7_000_000)).viewportFrequency)
    }

    @Test
    fun adsbRaisesRtlsdrRateAboveTwoMsps() {
        assertEquals(2_400_000L, tune(adsb, rtlsdr, view(1_000_000, 100_000_000)).sampleRate)
        assertEquals(2_400_000L, tune(adsb, rtlsdr, view(2_000_000, 100_000_000)).sampleRate)
        assertEquals(2_500_000L, tune(adsb, rtlsdr, view(2_500_000, 100_000_000)).sampleRate)
    }

    @Test
    fun sampleRateIsNeverLowered() {
        assertEquals(10_000_000L, tune(adsb, hackrf, view(10_000_000, 100_000_000)).sampleRate)
        assertEquals(2_500_000L, tune(aprs, rtlsdr, view(2_500_000, 100_000_000)).sampleRate)
    }

    @Test
    fun aRaisedRateKeepsAViewportThatStillShowsTheStation() {
        // ADS-B on a 1 Msps source whose viewport already shows 1090 MHz: the rate goes up, the viewport stays
        val current = view(1_000_000, 1_090_000_000, viewport = 1_090_000_000, span = 1_000_000)
        val t = tune(adsb, rtlsdr, current)
        assertEquals(2_400_000L, t.sampleRate)
        assertEquals(1_000_000L, t.viewportSpan)
        assertEquals(1_090_000_000L, t.sourceFrequency)
    }

    @Test
    fun retunesWithViewportReceivedAndChannelOffDc() {
        val t = tune(adsb, rtlsdr, view(1_000_000, 100_000_000))
        assertEquals(1_090_000_000L, t.channelFrequency)
        assertTrue(t.sourceFrequency != t.channelFrequency)
        assertViewportReceived(t)

        val aprsTuning = tune(aprs, rtlsdr, view(2_000_000, 100_000_000))
        assertEquals(144_800_000L + 2 * 12_500, aprsTuning.sourceFrequency)
        assertViewportReceived(aprsTuning)
    }

    @Test
    fun viewportSpanIsCappedToTheSampleRate() {
        val t = tune(FM_BAND_TARGET, rtlsdr, view(2_000_000, 50_000_000))
        assertEquals(2_000_000L, t.viewportSpan)
        assertViewportReceived(t)
    }

    @Test
    fun theFmBandKeepsAStationAlreadyTuned() {
        // listening to 101.3 MHz with it in view: starting RDS changes nothing but the settings
        val listening = view(2_400_000, 101_000_000, viewport = 101_000_000, span = 2_000_000, channel = 101_300_000)
        assertEquals(TuningPlan(2_400_000, 101_000_000, 101_000_000, 2_000_000, 101_300_000), tune(FM_BAND_TARGET, rtlsdr, listening))
        // not in view: moves there
        assertEquals(101_300_000L, tune(FM_BAND_TARGET, rtlsdr, view(2_000_000, 50_000_000, channel = 101_300_000)).viewportFrequency)
        assertEquals(FM_BAND_TARGET.frequency, tune(FM_BAND_TARGET, rtlsdr, view(2_000_000, 50_000_000, channel = 145_000_000)).channelFrequency)
    }

    @Test
    fun keepChannelCenteredCentersTheViewportOnTheChannel() {
        val inView = view(2_400_000, 145_000_000, viewport = 145_000_000, span = 2_000_000)
        val t = tune(aprs, rtlsdr, inView, centered = true)
        assertEquals(144_800_000L, t.viewportFrequency)
        assertEquals(2_000_000L, t.viewportSpan) // in view: the span stays
        assertEquals(ft8.frequency, tune(ft8, kiwi, view(12_000, 7_000_000), centered = true, dcSpike = false).viewportFrequency)
    }

    @Test
    fun capability() {
        fun error(target: TuneTarget, type: SourceType, limits: SourceLimits) =
            capabilityError(target, target.mode!!, target.channelWidth, target.frequency, type, limits)
        assertNotNull(error(adsb, SourceType.AIRSPYHF, airspyHf))
        assertNotNull(error(adsb, SourceType.KIWISDR, kiwi))
        assertNull(error(ft8, SourceType.KIWISDR, kiwi))
        assertNull(error(adsb, SourceType.RTLSDR, rtlsdr))
        // LoRa needs its demodulator's 500 kSps
        assertNotNull(error(TuneTarget("LoRa", DemodulationMode.LORA, 14_000_000, 125_000), SourceType.KIWISDR, kiwi))
    }

    /** A recording around [centerHz] at [rate], i.e. covering [rate] Hz. */
    private fun file(centerHz: Long, rate: Long = 1_000_000) = SourceLimits(centerHz - rate / 2, centerHz + rate / 2, listOf(rate))

    @Test
    fun fileSourceWorksWhenTheRecordingCoversTheChannel() {
        val recording = file(144_900_000)
        assertNull(capabilityError(aprs, aprs.mode!!, aprs.channelWidth, aprs.frequency, SourceType.FILESOURCE, recording))
        val t = tune(aprs, recording, view(1_000_000, 144_900_000, viewport = 145_200_000, span = 200_000), file = true)
        assertEquals(144_900_000L, t.sourceFrequency)
        assertEquals(1_000_000L, t.sampleRate)
        assertEquals(144_800_000L, t.channelFrequency)
        assertViewportReceived(t)
    }

    @Test
    fun fileSourceRefusesWhatTheRecordingDoesNotCover() {
        fun error(target: TuneTarget, limits: SourceLimits) =
            capabilityError(target, target.mode!!, target.channelWidth, target.frequency, SourceType.FILESOURCE, limits)
        assertNotNull(error(aprs, file(433_000_000)))
        // Channel edge outside the recording.
        assertNotNull(error(aprs, file(144_800_000 + 495_000)))
        // Covered, but recorded at too low a rate for ADS-B; 2 Msps (its demodulator's rate) is enough.
        assertNotNull(error(adsb, file(1_090_000_000, 1_000_000)))
        assertNull(error(adsb, file(1_090_000_000, 2_000_000)))
    }

    @Test
    fun fileSourceKeepsTheViewportInsideTheRecording() {
        // ADS-B recorded at 2.4 Msps: the 2 MHz viewport fits only centered on the recording.
        val recording = file(1_090_500_000, 2_400_000)
        val t = tune(adsb, recording, view(2_400_000, 1_090_500_000, viewport = 1_091_500_000, span = 100_000), file = true)
        assertEquals(1_090_500_000L, t.sourceFrequency)
        assertEquals(1_090_000_000L, t.channelFrequency)
        assertViewportReceived(t)
    }

    @Test
    fun fmBandOnAFilePicksAChannelInsideTheRecordedSliceOfTheBand() {
        val recording = file(101_000_000, 2_000_000)
        assertEquals(101_000_000L, channelFor(FM_BAND_TARGET, recording, currentChannelFrequency = 145_000_000))
        assertEquals(100_500_000L, channelFor(FM_BAND_TARGET, recording, currentChannelFrequency = 100_500_000))
    }

    /** A KiwiSDR has no DC spike and only +-5 kHz: tuned to the viewport's centre, not offset - an
     *  offset would push LSB out of the passband and cut the top of FT8's 3 kHz. */
    @Test
    fun kiwiSdrIsTunedToTheViewportCentre() {
        val t = tune(ft8, kiwi, view(12_000, 7_000_000), dcSpike = false)
        assertEquals(14_075_500L, t.sourceFrequency)
        assertViewportReceived(t)
    }
}
