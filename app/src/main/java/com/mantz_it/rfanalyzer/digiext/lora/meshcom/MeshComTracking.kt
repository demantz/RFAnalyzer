package com.mantz_it.rfanalyzer.digiext.lora.meshcom

import com.mantz_it.rfanalyzer.digiext.db.LatLon
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore
import com.mantz_it.rfanalyzer.digiext.db.isPlausiblePosition
import com.mantz_it.rfanalyzer.digiext.lora.LORA_IDLE_TIMEOUT_MS
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrame
import com.mantz_it.rfanalyzer.digiext.lora.LoraModulation
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrame
import com.mantz_it.rfanalyzer.digiext.lora.MeshComFrameType
import com.mantz_it.rfanalyzer.digiext.lora.MeshComHey
import com.mantz_it.rfanalyzer.digiext.lora.MeshComNeighbour
import com.mantz_it.rfanalyzer.digiext.lora.MeshComNeighbourReport
import com.mantz_it.rfanalyzer.digiext.lora.MeshComPosition
import com.mantz_it.rfanalyzer.digiext.lora.MeshComText
import com.mantz_it.rfanalyzer.digiext.lora.MeshComTextKind
import kotlinx.serialization.Serializable

/**
 * <h1>RF Analyzer - MeshCom Tracking</h1>
 *
 * Module:      MeshComTracking.kt
 * Description: A MeshCom station, keyed `"MeshCom:" + callsign`. A frame's content goes to its
 *              originator; a relayed copy also counts for the relay it was heard from - its
 *              signal, not the originator's, is what was received (the signal fields are only
 *              taken from frames a station sent itself or relayed).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Serializable
data class MeshComNode(
    /** The last position beacon: position, symbol, comment, name and telemetry. */
    val position: MeshComPosition? = null,
    /** Its board ([meshComHardwareName]); 0 for the MeshCom server. */
    val hardware: Int? = null,
    /** "4.40a" */
    val firmware: String? = null,
    /** It sent a HEY as a gateway (an internet connection to the MeshCom server). */
    val isGateway: Boolean = false,
    /** How many stations it hears directly, from its last HEY or position. */
    val neighbours: Int? = null,
    /** The stations it last reported hearing, best first (neighbour report). */
    val neighbourList: List<MeshComNeighbour> = emptyList(),
    val lastType: MeshComFrameType? = null,
    /** Its last text (and what kind of text), for the row. */
    val lastText: String? = null,
    val lastTextKind: MeshComTextKind? = null,
    /** Where its last text went: `*`, a group, a callsign. */
    val lastTextTo: String? = null,
    /** The modulation and SNR it was last heard with - itself, or as a relay. */
    val modulation: LoraModulation? = null,
    val snrDb: Float? = null,
    /** Frames heard as relayed by it. */
    val relayed: Int = 0,
)

fun meshComNodeKey(callsign: String) = "MeshCom:$callsign"

internal suspend fun applyMeshComFrame(nodes: ObjectStore<MeshComNode>, frame: LoraFrame, message: MeshComFrame) {
    val direct = message.hops == 0
    nodes.update(meshComNodeKey(message.origin), frame.timestampMs, frame.knownFrequencyHz, create = ::MeshComNode) { node ->
        val heard = if (direct) node.copy(modulation = LoraModulation.of(frame.phy), snrDb = frame.phy.snrDb ?: node.snrDb) else node
        val described = heard.copy(hardware = message.hardware, firmware = meshComFirmware(message) ?: heard.firmware, lastType = message.frameType)
        when (val info = message.info) {
            is MeshComPosition -> described.copy(position = info, neighbours = info.neighbours ?: described.neighbours)
            is MeshComText -> described.copy(lastText = info.text, lastTextKind = info.kind, lastTextTo = message.destination)
            is MeshComHey -> described.copy(isGateway = described.isGateway || message.destination == "HG", neighbours = info.neighbours ?: described.neighbours)
            is MeshComNeighbourReport -> described.copy(neighbourList = info.neighbours, neighbours = info.heard ?: described.neighbours)
        }
    }
    if (!direct) {
        nodes.update(meshComNodeKey(message.lastTransmitter), frame.timestampMs, frame.knownFrequencyHz, create = ::MeshComNode) { node ->
            node.copy(modulation = LoraModulation.of(frame.phy), snrDb = frame.phy.snrDb ?: node.snrDb, relayed = node.relayed + 1)
        }
    }
}

internal val MeshComNodeFamily = ObjectFamily(
    kind = ObjectKind.MESHCOM_NODE,
    serializer = MeshComNode.serializer(),
    displayName = { key, _ -> key.removePrefix("MeshCom:") },
    position = { node -> node.position?.takeIf { isPlausiblePosition(it.latitude, it.longitude) }?.let { LatLon(it.latitude, it.longitude) } },
    idleTimeoutMs = LORA_IDLE_TIMEOUT_MS,
)
