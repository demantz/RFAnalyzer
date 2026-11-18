package com.mantz_it.rfanalyzer.digiext.adsb

import com.mantz_it.rfanalyzer.digiext.db.memoryObjectStore
import com.mantz_it.rfanalyzer.digiext.db.toTrackedObject
import com.mantz_it.rfanalyzer_ipc.digiext.AirbornePositionPayload
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.SurfacePositionPayload
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

/**
 * <h1>RF Analyzer - ADS-B CPR Tests</h1>
 *
 * Module:      TestAdsbCpr.kt
 * Description: CPR position decoding against positions encoded here with the CPR encoding of
 *              DO-260B A.1.7: airborne pairs anywhere, surface pairs in every quadrant of the globe
 *              and across ±180°.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestAdsbCpr {

    /** Longitude zones at [lat] (DO-260B A.1.7.2d), with the published transition latitudes checked below. */
    private fun nl(lat: Double): Int {
        if (abs(lat) >= 87.0) return 1
        val a = 1 - cos(PI / (2 * 15))
        return floor(2 * PI / acos(1 - a / cos(PI / 180 * lat).let { it * it })).toInt()
    }

    private fun mod(x: Double, y: Double) = x - y * floor(x / y)

    /** The 17-bit CPR half of [lat]/[lon]: [span] 360° for airborne, 90° for surface. */
    private fun encode(lat: Double, lon: Double, odd: Boolean, span: Double, timestampMs: Long): CprHalf {
        val i = if (odd) 1 else 0
        val dLat = span / (60 - i)
        val yz = floor(131072 * mod(lat, dLat) / dLat + 0.5)
        val rLat = dLat * (yz / 131072 + floor(lat / dLat))
        val dLon = span / max(nl(rLat) - i, 1)
        val xz = floor(131072 * mod(lon, dLon) / dLon + 0.5)
        return CprHalf(yz.toInt() and 0x1FFFF, xz.toInt() and 0x1FFFF, timestampMs)
    }

    private fun assertNear(lat: Double, lon: Double, decoded: Pair<Double, Double>?, meters: Double) {
        assertNotNull("no position for $lat, $lon", decoded)
        val (dLat, dLon) = decoded!!
        val dLonWrapped = mod(dLon - lon + 180, 360.0) - 180
        val error = 111_320 * kotlin.math.hypot(dLat - lat, dLonWrapped * cos(lat * PI / 180))
        assertTrue("$lat, $lon decoded as $dLat, $dLon ($error m off)", error < meters)
        assertTrue("longitude $dLon outside -180..180", dLon >= -180 && dLon < 180)
    }

    @Test
    fun zoneCountsMatchTheTransitionLatitudes() {
        assertEquals(59, nl(0.0))
        assertEquals(59, nl(10.47))
        assertEquals(58, nl(10.48))
        assertEquals(2, nl(86.54))
        assertEquals(2, nl(-86.9))
        assertEquals(1, nl(87.0))
    }

    private val places = listOf(
        50.0379 to 8.5622,      // Frankfurt
        40.6413 to -73.7781,    // New York JFK
        -33.9461 to 151.1772,   // Sydney
        -23.4356 to -46.4731,   // São Paulo
        -0.1292 to -78.3575,    // Quito, just south of the equator
        64.1300 to -21.9406,    // Reykjavík
        51.8780 to -176.6460,   // Adak, west of 180°
        -17.7554 to 177.4431,   // Nadi, east of 180°
    )

    @Test
    fun airbornePairsDecodeAnywhere() {
        for ((lat, lon) in places) {
            for (evenNewer in listOf(true, false)) {
                val even = encode(lat, lon, odd = false, span = 360.0, timestampMs = if (evenNewer) 2_000 else 1_000)
                val odd = encode(lat, lon, odd = true, span = 360.0, timestampMs = if (evenNewer) 1_000 else 2_000)
                assertNear(lat, lon, cprDecodeAirborne(even, odd), meters = 10.0)
            }
        }
    }

    /** The reference only picks among solutions 90° apart, so one some way off still finds the aircraft. */
    @Test
    fun surfacePairsDecodeInEveryQuadrant() {
        for ((lat, lon) in places) {
            for ((refLat, refLon) in listOf(lat + 0.3 to lon - 0.4, lat - 20 to lon + 30)) {
                for (evenNewer in listOf(true, false)) {
                    val even = encode(lat, lon, odd = false, span = 90.0, timestampMs = if (evenNewer) 2_000 else 1_000)
                    val odd = encode(lat, lon, odd = true, span = 90.0, timestampMs = if (evenNewer) 1_000 else 2_000)
                    assertNear(lat, lon, cprDecodeSurface(even, odd, refLat, mod(refLon + 180, 360.0) - 180), meters = 3.0)
                }
            }
        }
    }

    /** An aircraft landing at JFK: the airborne position is the reference of its surface ones. */
    @Test
    fun surfacePositionFollowsTheLastAirborneOne() = runBlocking {
        val store = memoryObjectStore(AdsbAircraftFamily)
        val raw = Bytes(byteArrayOf(0x8D.toByte(), 0xA0.toByte(), 0x12, 0x34) + ByteArray(10))
        fun airborne(lat: Double, lon: Double, odd: Boolean, ts: Long) = encode(lat, lon, odd, 360.0, ts).let {
            AdsbSpec.packet(timestampMs = ts, frequencyHz = 1_090_000_000L, raw = raw, phy = Unit,
                data = AirbornePositionPayload(11, 1000, "BARO", cprOddFrame = odd, cprLat = it.lat, cprLon = it.lon))
        }
        fun surface(lat: Double, lon: Double, odd: Boolean, ts: Long) = encode(lat, lon, odd, 90.0, ts).let {
            AdsbSpec.packet(timestampMs = ts, frequencyHz = 1_090_000_000L, raw = raw, phy = Unit,
                data = SurfacePositionPayload(7, 15.0, 220.0, cprOddFrame = odd, cprLat = it.lat, cprLon = it.lon))
        }
        applyAdsbFrame(store, airborne(40.6500, -73.8000, odd = false, ts = 1_000))
        applyAdsbFrame(store, airborne(40.6500, -73.8000, odd = true, ts = 1_500))
        applyAdsbFrame(store, surface(40.6413, -73.7781, odd = false, ts = 60_000))
        applyAdsbFrame(store, surface(40.6413, -73.7781, odd = true, ts = 60_500))

        val aircraft = store.dirtyEntities().single().toTrackedObject(AdsbAircraftFamily).state
        assertEquals(true, aircraft.onGround)
        assertNear(40.6413, -73.7781, aircraft.lat!! to aircraft.lon!!, meters = 3.0)
    }
}
