package com.mantz_it.rfanalyzer.digiext.ax25

import com.mantz_it.rfanalyzer.digiext.aprs.AprsTextMessage
import com.mantz_it.rfanalyzer.digiext.aprs.PositionReport
import com.mantz_it.rfanalyzer.digiext.aprs.StatusInfo
import com.mantz_it.rfanalyzer.digiext.aprs.ThirdPartyInfo
import com.mantz_it.rfanalyzer_ipc.Ax25Variant
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Phy
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - AX.25 Presenter Tests</h1>
 *
 * Module:      TestAx25Presenter.kt
 * Description: Tests for how AX.25 frames and APRS reports are put into words ([ax25FrameText],
 *              [aprsReportText], [cleanComment]) - the text of rows, cards, tickers and lists.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestAx25Presenter {

    private fun frame(addresses: List<Ax25Address>, control: Int, pid: Int?, aprs: com.mantz_it.rfanalyzer.digiext.aprs.AprsInfo?): Ax25Frame =
        Ax25Spec.packet(
            timestampMs = 0, frequencyHz = 0, raw = null, phy = Ax25Phy(Ax25Variant.VHF_1200),
            data = Ax25Message(addresses, Bytes(byteArrayOf(control.toByte())), pid, aprs),
        )

    private fun position(comment: String?, course: Int? = null, speed: Int? = null, altitude: Int? = null) = PositionReport(
        dti = '!', timestamp = null, latRaw = null, lonRaw = null, lat = 34.36571, lon = -116.85833,
        symbolTable = '/', symbol = '>', comment = comment, phg = null, course = course, speedKnots = speed, altitudeFeet = altitude,
    )

    @Test
    fun aPositionReadsAsCommentPositionMotionAltitude() {
        assertEquals("Out and About. · 34.3657, -116.8583 · 352° 49 kn · 1218 m", aprsReportText(position("Out and About.", 352, 49, 3996)))
    }

    /** A station standing still has no motion; one without a comment shows its position. */
    @Test
    fun aStandingStationShowsNoMotion() {
        assertEquals("34.3657, -116.8583", aprsReportText(position(null, 0, 0)))
    }

    @Test
    fun dataExtensionsAreStrippedFromComments() {
        assertEquals("W1 for LA area", cleanComment("PHG5664/W1 for LA area"))
        assertEquals("TM-D700", cleanComment("000/000/A=000729TM-D700"))
        assertEquals(null, cleanComment("/A=000100"))
    }

    @Test
    fun messagesNameTheirAddressee() {
        assertEquals("→ DL1ABC: hello", aprsReportText(AprsTextMessage("DL1ABC   ", "hello", "12")))
    }

    @Test
    fun aRelayedPacketNamesItsOriginator() {
        assertEquals("Relayed for DB0XYZ: up and running", aprsReportText(ThirdPartyInfo("DB0XYZ>APRS:>up and running", StatusInfo(null, "up and running"), source = "DB0XYZ")))
    }

    /** A connect request says what it does and to whom - not "SABM P". */
    @Test
    fun aConnectedModeFrameSaysWhatItDoes() {
        val sabm = frame(listOf(Ax25Address("KB9KC", 7), Ax25Address("KE0GB", 0)), control = 0x3F, pid = null, aprs = null)
        assertEquals("Connect request to KB9KC-7", ax25FrameText(sabm))
    }
}
