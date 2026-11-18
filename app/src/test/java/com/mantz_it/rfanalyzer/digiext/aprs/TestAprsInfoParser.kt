package com.mantz_it.rfanalyzer.digiext.aprs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - APRS Info Parser Tests</h1>
 *
 * Module:      TestAprsInfoParser.kt
 * Description: Pure-JVM unit tests for AprsInfoParser - plain byte-array parsing.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestAprsInfoParser {

    @Test
    fun testUncompressedPosition() {
        val info = AprsInfoParser.parseAprsInfo("!4903.50N/07201.75W-Test comment".toByteArray())
        assertTrue("Expected PositionReport", info is PositionReport)
        val pos = info as PositionReport
        assertEquals('!', pos.dti)
        assertEquals('/', pos.symbolTable)
        assertEquals('-', pos.symbol)
        assertEquals(49.0 + 3.50 / 60.0, pos.lat!!, 0.0001)
        assertEquals(-(72.0 + 1.75 / 60.0), pos.lon!!, 0.0001)
        assertEquals("Test comment", pos.comment)
    }

    // The canonical worked example from APRS101.PDF §9.4.4 (compressed position report):
    // "/5L!!<*e7>7P[" decodes to 49°30.00'N / 072°45.00'W, course 88, speed 36 knots.
    // Used here as a spec-verified cross-check for AprsInfoParser's base91 decode.
    @Test
    fun testCompressedPositionSpecExample() {
        val info = AprsInfoParser.parseAprsInfo("!/5L!!<*e7>7P[".toByteArray())
        assertTrue("Expected PositionReport", info is PositionReport)
        val pos = info as PositionReport
        assertEquals('!', pos.dti)
        assertEquals('/', pos.symbolTable)
        assertEquals('>', pos.symbol)
        assertEquals(49.5, pos.lat!!, 0.0001)
        assertEquals(-72.75, pos.lon!!, 0.0001)
        assertEquals(88, pos.course)
        assertEquals(36, pos.speedKnots)
    }

    @Test
    fun testCompressedPositionWithComment() {
        val info = AprsInfoParser.parseAprsInfo("!/5L!!<*e7>7P[Hello compressed".toByteArray())
        val pos = info as PositionReport
        assertEquals("Hello compressed", pos.comment)
    }

    // Real LoRa-APRS iGate beacon fixture (see TestLoraAprs.kt for the full LoRa-framed
    // payload and the research behind this decode) - compressed format with a non-'/'/'\'
    // overlay symbol-table char ('L') and blank course/speed ("  ").
    @Test
    fun testCompressedPositionRealFixtureWithOverlayAndNoCourseSpeed() {
        val info = AprsInfoParser.parseAprsInfo("!L5N-0P_A<a  GLoRa APRS iGate Dossenheim".toByteArray())
        assertTrue("Expected PositionReport", info is PositionReport)
        val pos = info as PositionReport
        assertEquals('L', pos.symbolTable)
        assertEquals('a', pos.symbol)
        assertEquals(49.4536, pos.lat!!, 0.001)
        assertEquals(8.6676, pos.lon!!, 0.001)
        assertNull("Blank compressed course/speed bytes must decode to no course/speed", pos.course)
        assertNull("Blank compressed course/speed bytes must decode to no course/speed", pos.speedKnots)
        assertEquals("LoRa APRS iGate Dossenheim", pos.comment)
    }

    @Test
    fun testTruncatedCompressedCandidateFallsBackToUnknown() {
        // Starts with a non-digit (would be treated as compressed) but far too short to
        // contain a full 13-byte compressed field - must not crash, must not fabricate a position.
        val info = AprsInfoParser.parseAprsInfo("!/abc".toByteArray())
        assertTrue("Expected fallback to UnknownAprsInfo, got $info", info !is PositionReport)
    }

    @Test
    fun testGarbageAfterMagicByteDoesNotCrash() {
        val random = java.util.Random(7)
        repeat(50) {
            val bytes = ByteArray(20)
            random.nextBytes(bytes)
            bytes[0] = '!'.code.toByte()
            AprsInfoParser.parseAprsInfo(bytes) // must not throw
        }
    }

    // --- PHG extension ---

    @Test
    fun phgIsSpelledOut() {
        assertEquals("25 W, 20 ft, 3 dB, omni", decodePhg("PHG5130"))
        assertEquals("4 W, 640 ft, 6 dB, 90°", decodePhg("PHG2662"))
        // The height code is "ASCII minus '0'" and may run past '9' (':' is 10 -> 10240 ft).
        assertEquals("81 W, 10240 ft, 9 dB, 360°", decodePhg("PHG9:98"))
    }

    @Test
    fun malformedPhgIsLeftAlone() {
        assertNull(decodePhg("PHG51"))
        assertNull(decodePhg("PHG5x30"))
        assertNull(decodePhg("PHG5139"))
        assertNull(decodePhg(""))
    }

    @Test
    fun symbolIsFoundThroughAThirdPartyWrapper() {
        val inner = ObjectReport("LEADER", killed = false, timestamp = null, latRaw = null, lonRaw = null,
            lat = null, lon = null, symbolTable = '/', symbol = '>', comment = null)
        assertEquals('/' to '>', inner.symbolOrNull())
        assertEquals('/' to '>', ThirdPartyInfo(rawInner = "", innerInfo = inner).symbolOrNull())
        assertNull(StatusInfo(timestamp = null, text = "hi").symbolOrNull())
        assertNull(inner.copy(symbol = null).symbolOrNull())
    }

    // --- Mic-E (APRS 1.0.1/1.2 ch. 10) ---

    private fun micE(destination: String?, info: String) =
        AprsInfoParser.parseAprsInfo(info.toByteArray(Charsets.ISO_8859_1), destination) as MicEInfo

    private fun degrees(degrees: Int, minutes: Double) = degrees + minutes / 60.0

    /**
     * The spec's worked examples: the destination S32U6T (33°25.64' N, position comment bits 1/0/0
     * standard = "Returning", West) and the information field `(_fn"Oj/ "with a longitude offset of
     * +100 degrees" (112°07.74' W, 20 kn, 251°, jeep) - so the destination with the +100 offset,
     * S32UVT (V: digit 6, +100).
     */
    @Test
    fun micEWorkedExampleFromTheSpec() {
        val info = micE("S32UVT", "`(_fn\"Oj/")
        assertEquals('`', info.dti)
        assertEquals("(_fn\"Oj/", info.deviceBlock)
        assertEquals(degrees(33, 25.64), info.lat!!, 1e-9)
        assertEquals(-degrees(112, 7.74), info.lon!!, 1e-9)
        assertEquals(20, info.speedKnots)
        assertEquals(251, info.course)
        assertEquals('/' to 'j', info.symbolTable to info.symbol)
        assertEquals('/' to 'j', info.symbolOrNull())
        assertEquals("Returning", info.messageType)
        assertNull(info.altitudeFeet)
        assertNull(info.device)
        assertNull(info.comment)
        // no type code: not known to take messages - whatever the DTI
        assertFalse(info.messagingCapable)
        assertEquals(0, info.positionAmbiguity)

        // S32U6T itself has the +0 offset: the same bytes are 12° W
        assertEquals(-degrees(12, 7.74), micE("S32U6T", "`(_fn\"Oj/").lon!!, 1e-9)
    }

    /** The spec's examples of the position comment bits, plus the rest of the table. */
    @Test
    fun micEPositionComments() {
        assertEquals("Returning", micE("S32U6T", "`(_fn\"Oj/").messageType)
        assertEquals("Custom 2", micE("F2DU6T", "`(_fn\"Oj/").messageType)
        assertEquals("Emergency", micE("234U6T", "`(_fn\"Oj/").messageType)
        assertEquals("Off Duty", micE("STSU6T", "`(_fn\"Oj/").messageType)
        assertEquals("En Route", micE("ST3U6T", "`(_fn\"Oj/").messageType)
        assertEquals("Priority", micE("34SU6T", "`(_fn\"Oj/").messageType)
        assertEquals("Custom 0", micE("CDEU6T", "`(_fn\"Oj/").messageType)
        assertEquals("Custom 6", micE("34EU6T", "`(_fn\"Oj/").messageType)
        // a mixture of standard and custom ones is "unknown" (spec)
        assertEquals("Unknown", micE("SDEU6T", "`(_fn\"Oj/").messageType)
    }

    /** A real TH-D72 (DG1ID, 2026-10): '>' type code, '=' suffix; 0-9° longitude (~ = 98 + 100 - 190 = 8°). */
    @Test
    fun micEKenwoodThD72() {
        val info = micE("T9QTW2", "'~]gl \u001c-/>=\r")
        assertEquals('\'', info.dti)
        assertEquals(degrees(49, 14.72), info.lat!!, 1e-9)
        assertEquals(degrees(8, 5.75), info.lon!!, 1e-9)
        assertEquals(0, info.speedKnots)
        // course 400 - 400 = 0: unknown
        assertNull(info.course)
        assertEquals('/' to '-', info.symbolTable to info.symbol)
        assertEquals("In Service", info.messageType)
        assertEquals("Kenwood TH-D72", info.device)
        assertTrue(info.messagingCapable)
        assertNull(info.comment)
    }

    @Test
    fun micEKenwoodTmD710WithAltitudeAndComment() {
        // "4T} is the spec's example altitude: 61 m = 200 ft
        val info = micE("T9QTW2", "'~]gl \u001c-/]\"4T}On the road=")
        assertEquals("Kenwood TM-D710", info.device)
        assertEquals(200, info.altitudeFeet)
        assertEquals("On the road", info.comment)
        assertTrue(info.messagingCapable)
    }

    /** A real TM-D700 beacon (KF6DQ-5): ']' type code without suffix, altitude, a comment - and
     *  position ambiguity 1 (Z for the last latitude digit), so the longitude's last digit counts as 0. */
    @Test
    fun micEKenwoodTmD700WithAmbiguity() {
        val info = micE("S4QUYZ", "'.Kel ~v/]\"6A}TM-D700\r")
        assertEquals(1, info.positionAmbiguity)
        assertEquals(degrees(34, 15.90), info.lat!!, 1e-9)
        assertEquals(-degrees(118, 47.70), info.lon!!, 1e-9)
        assertEquals(0, info.speedKnots)
        assertEquals(98, info.course)
        assertEquals('/' to 'v', info.symbolTable to info.symbol)
        assertEquals(735, info.altitudeFeet) // "6A} = 224 m
        assertEquals("Kenwood TM-D700", info.device)
        assertEquals("TM-D700", info.comment)
    }

    @Test
    fun micEYaesu() {
        // TestAx25Parser's Mic-E fixture (F5NVY-1): type code `, suffix _1;
        // x = 120 - 28 + 100 = 192 -> 2°, a -> 9', o -> 83 hundredths.
        val ftm300 = micE("TS3VQ2", "`xaol \u001cj/`_1\r")
        assertEquals("Yaesu FTM-300D", ftm300.device)
        assertTrue(ftm300.messagingCapable)
        assertNull(ftm300.comment)
        assertEquals(degrees(43, 36.12), ftm300.lat!!, 1e-9)
        assertEquals(degrees(2, 9.83), ftm300.lon!!, 1e-9)
        assertEquals("En Route", ftm300.messageType)

        val ftm400 = micE("TS3VQ2", "`xaol \u001cj/`\"4T}Hello_%")
        assertEquals("Yaesu FTM-400DR", ftm400.device)
        assertEquals(200, ftm400.altitudeFeet)
        assertEquals("Hello", ftm400.comment)

        // the VX-8's suffix ends in a space
        val vx8 = micE("TS3VQ2", "`xaol \u001cj/`QRV _ \r")
        assertEquals("Yaesu VX-8", vx8.device)
        assertEquals("QRV", vx8.comment)

        // a tracker: ' = no messaging
        val tinyTrak = micE("TS3VQ2", "`xaol \u001cj/'|3")
        assertEquals("Byonics TinyTrak3", tinyTrak.device)
        assertFalse(tinyTrak.messagingCapable)
    }

    /** OH7LZB's test vector in Ham::APRS::FAP: south, east, a 110-179° longitude with the +100 offset. */
    @Test
    fun micESouthernAndEasternHemisphere() {
        val info = micE("SX15S6", "'I',l \u001c>/]")
        assertEquals(-degrees(38, 15.36), info.lat!!, 1e-9)
        assertEquals(degrees(145, 11.16), info.lon!!, 1e-9)
        assertEquals('/' to '>', info.symbolTable to info.symbol)
        assertEquals("En Route", info.messageType)
        assertEquals("Kenwood TM-D700", info.device)
    }

    @Test
    fun micESouthernAndWesternHemisphereWithSpeedAndCourse() {
        // 34°36.00' S, 58°24.00' W; 35 kn (o: 83 -> 830 - 800 + 5), 270° (P: units 5, hundreds 2; b: 70)
        val info = micE("STS60P", "`V4\u001coPb>/")
        assertEquals(-34.6, info.lat!!, 1e-9)
        assertEquals(-58.4, info.lon!!, 1e-9)
        assertEquals(35, info.speedKnots)
        assertEquals(270, info.course)
        assertEquals("Off Duty", info.messageType)
    }

    @Test
    fun micELongitudeRanges() {
        // 100-109° (d+28 = l..u with +100: 180..189 - 80)
        assertEquals(-degrees(104, 7.74), micE("S32UVT", "`p_fn\"Oj/").lon!!, 1e-9)
        assertEquals(-degrees(100, 7.74), micE("S32UVT", "`l_fn\"Oj/").lon!!, 1e-9)
        // 0-9° (v..DEL with +100: 190..199 - 190)
        assertEquals(-degrees(0, 7.74), micE("S32UVT", "`v_fn\"Oj/").lon!!, 1e-9)
        assertEquals(-degrees(9, 7.74), micE("S32UVT", "`\u007f_fn\"Oj/").lon!!, 1e-9)
        // 10-99° with +0, 110-179° with +100
        assertEquals(-degrees(99, 7.74), micE("S32U6T", "`\u007f_fn\"Oj/").lon!!, 1e-9)
        assertEquals(-degrees(179, 7.74), micE("S32UVT", "`k_fn\"Oj/").lon!!, 1e-9)
        // 10-59 minutes are sent as m + 28, 0-9 minutes as m + 60 + 28
        assertEquals(-degrees(112, 59.74), micE("S32UVT", "`(Wfn\"Oj/").lon!!, 1e-9)
        assertEquals(-degrees(112, 0.74), micE("S32UVT", "`(Xfn\"Oj/").lon!!, 1e-9)
        assertEquals(-degrees(112, 9.74), micE("S32UVT", "`(afn\"Oj/").lon!!, 1e-9)
    }

    /** The spec's example: T4SQZZ leaves out the last two latitude digits, and the longitude (_f is
     *  then 112°07' - its hundredths ignored too. */
    @Test
    fun micEPositionAmbiguity() {
        val two = micE("T4SQZZ", "`(_fn\"Oj/")
        assertEquals(2, two.positionAmbiguity)
        assertEquals(degrees(44, 31.0), two.lat!!, 1e-9)
        assertEquals(-degrees(112, 7.0), two.lon!!, 1e-9)

        // all four minute digits left out (K/L/Z): whole degrees
        val four = micE("T4ZZZZ", "`(_fn\"Oj/")
        assertEquals(4, four.positionAmbiguity)
        assertEquals(44.0, four.lat!!, 1e-9)
        assertEquals(-112.0, four.lon!!, 1e-9)

        // a left-out digit followed by a digit is not ambiguity: no position, the rest still decodes
        val broken = micE("T4Z3ZZ", "`(_fn\"Oj/")
        assertNull(broken.lat)
        assertNull(broken.lon)
        assertEquals(20, broken.speedKnots)
    }

    @Test
    fun micEWithoutItsDestinationHasNoPosition() {
        for (destination in listOf(null, "APRS", "S32U6TX", "S32UVA")) {
            val info = micE(destination, "`(_fn\"Oj/")
            assertNull(info.lat)
            assertNull(info.lon)
            assertNull(info.messageType)
            assertEquals(251, info.course)
            assertEquals('/' to 'j', info.symbolOrNull())
        }
        // the SSID is not part of the encoding
        assertEquals(degrees(33, 25.64), micE("S32UVT-2", "`(_fn\"Oj/").lat!!, 1e-9)
    }

    /** Rev. 0 beta units sent 0x1C/0x1D as the DTI; fewer than 9 bytes must not be decoded (spec). */
    @Test
    fun micEOldDtisAndShortFields() {
        assertEquals(degrees(33, 25.64), micE("S32UVT", "\u001c(_fn\"Oj/").lat!!, 1e-9)
        assertEquals('\u001d', micE("S32UVT", "\u001d(_fn\"Oj/").dti)
        val short = micE("S32UVT", "`(_fn\"O")
        assertNull(short.lat)
        assertNull(short.symbol)
        assertEquals("(_fn\"O", short.deviceBlock)
    }

    @Test
    fun micEPositionFoldsLikeAPositionReport() {
        var position: Pair<Double?, Double?>? = null
        var symbol: Pair<Char?, Char?>? = null
        var courseSpeed: Pair<Double?, Double?>? = null
        var altitude: Int? = -1
        var comment: String? = "-"
        fun apply(info: AprsInfo) = applyAprsFields(
            info, onPosition = { la, lo -> position = la to lo }, onSymbol = { t, s -> symbol = t to s },
            onCourseSpeed = { c, s -> courseSpeed = c to s }, onAltitude = { altitude = it }, onComment = { comment = it },
        )
        apply(micE("T9QTW2", "'~]gl \u001c-/]\"4T}On the road="))
        assertEquals(degrees(49, 14.72), position!!.first!!, 1e-9)
        assertEquals('/' to '-', symbol)
        assertEquals(null to 0.0, courseSpeed)
        assertEquals(200, altitude)
        assertEquals("On the road", comment)

        // without its destination: the symbol and comment, but the position is left alone
        position = null; altitude = -1
        apply(micE(null, "'~]gl \u001c-/]Parked"))
        assertNull(position)
        assertEquals(-1, altitude)
        assertEquals("Parked", comment)
    }

    // --- Third-party traffic ---

    /** A real iGate relay (N6EX-3, 2026-10): the inner header names the originator. */
    @Test
    fun thirdPartyTrafficNamesItsOriginator() {
        val info = AprsInfoParser.parseAprsInfo(
            "}W6AHM>APRS,TCPIP,N6EX-3*:@230135z3350.28N/11818.85W_269/010g010t065r000P000p000h64b10155v6".toByteArray(),
        ) as ThirdPartyInfo
        assertEquals("W6AHM", info.source)
        val inner = info.innerInfo as PositionReport
        assertEquals(degrees(33, 50.28), inner.lat!!, 1e-9)
        assertEquals("W6AHM" to inner, info.originReport())
    }

    /** The relayed packet's destination reaches a Mic-E report inside it. */
    @Test
    fun thirdPartyMicEIsDecodedWithTheInnerDestination() {
        val info = AprsInfoParser.parseAprsInfo("}KF6DQ-5>S4QUYZ,TCPIP,KC6YRU*:'.Kel ~v/]\"6A}TM-D700".toByteArray(Charsets.ISO_8859_1)) as ThirdPartyInfo
        assertEquals("KF6DQ-5", info.source)
        assertEquals(degrees(34, 15.90), (info.innerInfo as MicEInfo).lat!!, 1e-9)
    }

    /** The relaying station takes nothing from the packet it relays. */
    @Test
    fun thirdPartyTrafficLeavesTheRelayAlone() {
        val info = AprsInfoParser.parseAprsInfo("}W6AHM>APRS,TCPIP,N6EX-3*:!3350.28N/11818.85W_Weather".toByteArray())
        var touched = false
        applyAprsFields(info, { _, _ -> touched = true }, { _, _ -> touched = true }, { _, _ -> touched = true }, { touched = true }, { touched = true })
        assertFalse(touched)
    }

    @Test
    fun thirdPartyTrafficWithoutAHeaderStillParsesItsInformation() {
        val info = AprsInfoParser.parseAprsInfo("}garbage:>status".toByteArray()) as ThirdPartyInfo
        assertNull(info.source)
        assertNull(info.originReport())
        assertEquals("status", (info.innerInfo as StatusInfo).text)
    }

    // --- Objects ---

    private fun objectReport(info: String) = AprsInfoParser.parseAprsInfo(info.toByteArray(Charsets.ISO_8859_1)) as ObjectReport

    /** Real Objects (2026-10): the comment is the text after the symbol, not the position string. */
    @Test
    fun anObjectsCommentIsItsText() {
        val repeater = objectReport(";146.70   *020658z3412.83N/11741.68WmPL 146.2 APRN & Mic-E Rpt on Sunset\r")
        assertEquals("146.70", repeater.objectName)
        assertEquals("020658z", repeater.timestamp)
        assertEquals(degrees(34, 12.83), repeater.lat!!, 1e-9)
        assertEquals('/' to 'm', repeater.symbolTable to repeater.symbol)
        assertEquals("PL 146.2 APRN & Mic-E Rpt on Sunset", repeater.comment)

        assertEquals("114 In 10 Minutes", objectReport(";LA LOAD  *220941z3352.18N\\11749.77W? 114 In 10 Minutes").comment)
    }

    @Test
    fun anObjectsCommentLeavesOutItsDataExtension() {
        val moving = objectReport(";LEADER   _092345z4903.50N/07201.75W>088/036Following the course")
        assertTrue(moving.killed)
        assertEquals("Following the course", moving.comment)
        assertEquals("Packet Node", objectReport(";ELYME    *190116z4122.06N/07212.98W#PHG5130 Packet Node").comment)
        assertEquals("Net 20:00", objectReport(";NET      *190116z4122.06N/07212.98W#RNG0050 Net 20:00").comment)
        assertNull(objectReport(";CAR      *190116z4122.06N/07212.98W>090/045").comment)
        // compressed position: the comment follows the 13 bytes
        assertEquals("Compressed", objectReport(";OBJ      *092345z/5L!!<*e7>7P[Compressed").comment)
    }

    /** The timestamp is the 7 characters after the flag - a 'z' in the comment is not one. */
    @Test
    fun anObjectsTimestampIsFound() {
        val hms = objectReport(";OBJ      *123456h4903.50N/07201.75W-buzz")
        assertEquals("123456h", hms.timestamp)
        assertEquals("buzz", hms.comment)
        assertEquals("092345/", objectReport(";OBJ      *092345/4903.50N/07201.75W-local").timestamp)

        val none = objectReport(";OBJ      *4903.50N/07201.75W-buzz")
        assertNull(none.timestamp)
        assertEquals(degrees(49, 3.5), none.lat!!, 1e-9)
        assertEquals("buzz", none.comment)
    }

    @Test
    fun testCourseSpeedOnlyFromTheDataExtension() {
        val moving = AprsInfoParser.parseAprsInfo("!4903.50N/07201.75W>088/036/A=001234".toByteArray()) as PositionReport
        assertEquals(88, moving.course)
        assertEquals(36, moving.speedKnots)
        // "145/435" further into the comment is text (a frequency pair), not course/speed
        val text = AprsInfoParser.parseAprsInfo("!4903.50N/07201.75W#Digi 145/435 MHz".toByteArray()) as PositionReport
        assertNull(text.course)
        assertNull(text.speedKnots)
    }

    @Test
    fun testWeatherStationWindIsNoCourse() {
        // symbol '_': "220/004" is wind direction/speed (APRS101 ch. 12)
        val wx = AprsInfoParser.parseAprsInfo("!4903.50N/07201.75W_220/004g005t077r000p000P000h50b09900".toByteArray()) as PositionReport
        assertEquals('_', wx.symbol)
        assertNull(wx.course)
        assertNull(wx.speedKnots)
    }

    @Test
    fun testZeroPositionKeepsTheLastGoodOne() {
        val good = AprsInfoParser.parseAprsInfo("!4903.50N/07201.75W-".toByteArray())
        val zero = AprsInfoParser.parseAprsInfo("!0000.00N/00000.00E-no fix".toByteArray())
        var lat: Double? = null
        var lon: Double? = null
        for (info in listOf(good, zero)) {
            applyAprsFields(info, onPosition = { la, lo -> lat = la; lon = lo }, onSymbol = { _, _ -> }, onCourseSpeed = { _, _ -> },
                onAltitude = {}, onComment = {})
        }
        assertEquals(49.0 + 3.50 / 60.0, lat!!, 0.0001)
        assertEquals(-(72.0 + 1.75 / 60.0), lon!!, 0.0001)
    }
}
