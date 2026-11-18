package com.mantz_it.rfanalyzer.digiext.ax25

import com.mantz_it.rfanalyzer.digiext.aprs.ObjectReport
import com.mantz_it.rfanalyzer.digiext.aprs.PositionReport
import com.mantz_it.rfanalyzer.digiext.aprs.StatusInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * <h1>RF Analyzer - AX.25 Identity Tests</h1>
 *
 * Module:      TestAx25Identity.kt
 * Description: Tests for [ax25IdentityOf] - an [AprsStation]'s key, shared by insert-time
 *              ([Ax25Spec]) and fold-time ([applyAx25Frame]) so a raw packet row's `objectKey`
 *              always matches its owning `tracked_objects` row: a station and an Object it reports
 *              are two rows.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestAx25Identity {

    private fun positionReport() = PositionReport(
        dti = '!', timestamp = null, latRaw = null, lonRaw = null, lat = 37.0, lon = -122.0,
        symbolTable = '/', symbol = '>', comment = null, phg = null, course = null, speedKnots = null, altitudeFeet = null,
    )

    private fun statusInfo() = StatusInfo(timestamp = null, text = "status")

    private fun objectReport(objectName: String, killed: Boolean = false) = ObjectReport(
        objectName = objectName, killed = killed, timestamp = null, latRaw = null, lonRaw = null,
        lat = 37.0, lon = -122.0, symbolTable = '/', symbol = '>', comment = null,
    )

    @Test
    fun positionReportIsAPlainStationIdentity() {
        assertEquals("STATION:KF6YVS-6", ax25IdentityOf("KF6YVS-6", positionReport()))
    }

    @Test
    fun statusInfoIsAPlainStationIdentity() {
        assertEquals("STATION:KF6YVS-6", ax25IdentityOf("KF6YVS-6", statusInfo()))
    }

    @Test
    fun objectReportIsKeyedByItsOwnObjectNameNotTheReportingStation() {
        assertEquals("OBJECT:WX-STATION", ax25IdentityOf("KF6YVS-6", objectReport("WX-STATION")))
    }

    @Test
    fun aKilledObjectReportStillKeysByItsObjectName() {
        // killed=true only means the object is being retired/withdrawn - identity is unaffected,
        // the same station/object still needs to be found by later queries.
        assertEquals("OBJECT:WX-STATION", ax25IdentityOf("KF6YVS-6", objectReport("WX-STATION", killed = true)))
    }

    @Test
    fun aStationAndAnObjectItReportsNeverShareAnIdentityEvenWithTheSameCallsign() {
        val stationId = ax25IdentityOf("KF6YVS-6", positionReport())
        val objectId = ax25IdentityOf("KF6YVS-6", objectReport("KF6YVS-6"))
        // Pathological but real: nothing stops an object sharing its name with its reporting
        // station's callsign - the "STATION:"/"OBJECT:" namespace prefix is what keeps them apart.
        org.junit.Assert.assertNotEquals(stationId, objectId)
    }
}
