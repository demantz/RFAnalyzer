package com.mantz_it.rfanalyzer.digiext.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.adsb.AdsbSpec
import com.mantz_it.rfanalyzer.digiext.adsb.adsbMapLayer
import com.mantz_it.rfanalyzer.digiext.adsb.adsbRecentLine
import com.mantz_it.rfanalyzer.digiext.ax25.AprsStationFamily
import com.mantz_it.rfanalyzer.digiext.ax25.Ax25Spec
import com.mantz_it.rfanalyzer.digiext.ax25.aprsMapLayer
import com.mantz_it.rfanalyzer.digiext.ax25.ax25FrameText
import com.mantz_it.rfanalyzer.digiext.db.ADSB_DETAIL_LIMIT
import com.mantz_it.rfanalyzer.digiext.db.DETAIL_LIMIT
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketRepository
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.PacketSpec
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.ft8.Ft8Spec
import com.mantz_it.rfanalyzer.digiext.ft8.ft8MapLayer
import com.mantz_it.rfanalyzer.digiext.ft8.ft8RecentLine
import com.mantz_it.rfanalyzer.digiext.lora.LoraSpec
import com.mantz_it.rfanalyzer.digiext.lora.aprs.LoraAprsStationFamily
import com.mantz_it.rfanalyzer.digiext.lora.loraFrameSummary
import com.mantz_it.rfanalyzer.digiext.lora.meshCoreMapLayer
import com.mantz_it.rfanalyzer.digiext.lora.meshtasticMapLayer
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.meshComMapLayer
import com.mantz_it.rfanalyzer.digiext.ui.common.RecentPacket
import com.mantz_it.rfanalyzer.ui.composable.MapMarker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * <h1>RF Analyzer - Map Layers</h1>
 *
 * Module:      MapLayers.kt
 * Description: How each kind of tracked object with a position appears on the Map subtab, and the
 *              registry of them - the Map's counterpart to the Overview's sections.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Opacity of a marker for an object that is not current: heard before this session, or an
 *  aircraft past its live window. */
internal const val STALE_MARKER_ALPHA = 0.4f

/** A popup card's widest: narrower than the map, so the map stays visible next to it (the camera
 *  leaves this much room right of a selected marker - see DigitalMapView). */
private val MAP_POPUP_MAX_WIDTH = 272.dp

/** What a Map popup card can do besides showing its object. */
internal class MapPopupCallbacks(
    val onClose: () -> Unit,
    /** Keep the camera on the object as it moves. */
    val onTrack: () -> Unit,
    val onShareJson: (json: String, filenamePrefix: String) -> Unit,
    val onOpenUrl: (url: String) -> Unit,
)

/**
 * One kind of tracked object on the Map subtab: its [marker], the [popup] card a tap on the marker
 * opens, and the [recent] packet lines shown in that card.
 */
internal class MapLayer<S : Any>(
    val family: ObjectFamily<S>,
    /** The object's stored packets as popup lines, newest first. */
    private val recent: (objectKey: String) -> Flow<List<RecentPacket>>,
    /** The marker of an object at [lat]/[lon]. [referenceMs] is "now" for the data shown - the
     *  clock while live, the newest object of stored data - so a layer can fade what has left. */
    private val marker: (obj: TrackedObject<S>, lat: Double, lon: Double, referenceMs: Long) -> MapMarker,
    private val popup: @Composable (obj: TrackedObject<S>, recent: List<RecentPacket>, callbacks: MapPopupCallbacks) -> Unit,
    /** The layer's map key, drawn with the map's controls (ADS-B's altitude colours). */
    val legend: (@Composable (Modifier) -> Unit)? = null,
    /** Only objects heard this long before [referenceMs] are drawn; null draws every one. Aircraft
     *  are many and move on: a day of them would cover the map. */
    val windowMs: Long? = null,
) {
    /** [obj]'s marker, or null while it has no position. */
    fun markerOf(obj: TrackedObject<*>, referenceMs: Long): MapMarker? {
        val typed = typed(obj)
        val lat = typed.lat ?: return null
        val lon = typed.lon ?: return null
        if (windowMs != null && referenceMs - typed.lastSeenMs > windowMs) return null
        return marker(typed, lat, lon, referenceMs)
    }

    @Composable
    fun Popup(obj: TrackedObject<*>, callbacks: MapPopupCallbacks) {
        Box(modifier = Modifier.widthIn(max = MAP_POPUP_MAX_WIDTH)) {
            popup(typed(obj), rememberDetail(obj.key, recent), callbacks)
        }
    }

    @Suppress("UNCHECKED_CAST") // the kind identifies the state type
    private fun typed(obj: TrackedObject<*>): TrackedObject<S> {
        require(obj.kind == family.kind) { "A ${obj.kind} is not on the ${family.kind} layer" }
        return obj as TrackedObject<S>
    }
}

/** The Map layer of [kind], or null for a kind without a position. Exhaustive, so a new kind does
 *  not compile until it is decided here whether it goes on the map. */
internal fun mapLayerOf(kind: ObjectKind, packets: DigitalPacketRepository): MapLayer<*>? {
    /** One object's stored packets as popup lines - the same query its Overview row opens into. */
    fun <Ph : Any, D : Any> recent(
        spec: PacketSpec<Ph, D>, limit: Int = DETAIL_LIMIT, bidirectional: Boolean = false, line: (StoredPacket<Ph, D>) -> String,
    ): (String) -> Flow<List<RecentPacket>> = { key ->
        packets.detail(spec, key, limit, bidirectional).map { list -> list.map { RecentPacket(it.timestampMs, line(it)) } }
    }
    return when (kind) {
        ObjectKind.ADSB_AIRCRAFT -> adsbMapLayer(recent(AdsbSpec, ADSB_DETAIL_LIMIT, line = ::adsbRecentLine))
        ObjectKind.APRS_STATION -> aprsMapLayer(AprsStationFamily, recent(Ax25Spec, bidirectional = true, line = ::ax25FrameText))
        ObjectKind.LORA_APRS_STATION -> aprsMapLayer(LoraAprsStationFamily, recent(LoraSpec, bidirectional = true, line = ::loraFrameSummary))
        ObjectKind.FT8_STATION -> ft8MapLayer(recent(Ft8Spec, bidirectional = true, line = ::ft8RecentLine))
        ObjectKind.MESHCORE_NODE -> meshCoreMapLayer(recent(LoraSpec, line = ::loraFrameSummary))
        ObjectKind.MESHTASTIC_NODE -> meshtasticMapLayer(recent(LoraSpec, line = ::loraFrameSummary))
        ObjectKind.MESHCOM_NODE -> meshComMapLayer(recent(LoraSpec, bidirectional = true, line = ::loraFrameSummary))
        // No position: a pager, a bus, a broadcast station and a packet BBS/node are not located by
        // what they send, and LoRaWAN carries no GPS at this decode layer.
        ObjectKind.POCSAG_PAGER, ObjectKind.RDS_STATION, ObjectKind.TMC_MESSAGE,
        ObjectKind.R09_BUS, ObjectKind.LORAWAN_DEVICE, ObjectKind.PACKET_STATION -> null
    }
}
