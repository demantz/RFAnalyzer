package com.mantz_it.rfanalyzer.digiext.lora.meshtastic

import com.mantz_it.rfanalyzer.digiext.lora.LoraFrame
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticAirQualityMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticDeviceMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticEncrypted
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticEnvironmentMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticHealthMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticHostMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticLocalStats
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticNeighbor
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticNeighborInfo
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticNodeInfo
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticOpaquePayload
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPacket
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPosition
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPowerMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticRouting
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticTelemetry
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticTextMessage
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticTraceroute
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticWaypoint
import com.mantz_it.rfanalyzer.digiext.lora.loraIdentityOf
import kotlinx.serialization.Serializable
import com.mantz_it.rfanalyzer.digiext.db.LatLon
import com.mantz_it.rfanalyzer.digiext.lora.LoraModulation
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore
import com.mantz_it.rfanalyzer.digiext.db.isPlausiblePosition
import com.mantz_it.rfanalyzer.digiext.lora.LORA_IDLE_TIMEOUT_MS

/**
 * <h1>RF Analyzer - Meshtastic Tracking</h1>
 *
 * Module:      MeshtasticTracking.kt
 * Description: The tracked Meshtastic node: its state ([MeshtasticNode]) with the row preview, its
 *              [MeshtasticNodeFamily], and the fold of a frame into it - every intact frame counts
 *              towards its sender, whose node number is in the plaintext header.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** A Meshtastic node, keyed `"!" + NodeNum hex`. */
@Serializable
data class MeshtasticNode(
    val shortName: String? = null,
    val longName: String? = null,
    val hwModel: Int? = null,
    val role: Int? = null,
    val isLicensed: Boolean? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val deviceMetrics: MeshtasticDeviceMetrics? = null,
    val environmentMetrics: MeshtasticEnvironmentMetrics? = null,
    /** When the node's last telemetry was taken, by the node's clock, unix seconds. */
    val telemetryTimestampSec: Long? = null,
    /** What the last trusted frame was - the row text is rendered from it
     *  ([com.mantz_it.rfanalyzer.digiext.lora.meshtasticRowText]). Null portnum with
     *  [lastEncrypted] for a frame this app has no key for. */
    val lastPortnum: Int? = null,
    val lastEncrypted: Boolean = false,
    val lastText: String? = null,
    val modulation: LoraModulation? = null,
    /** SNR of the last frame heard from it, dB. */
    val snrDb: Float? = null,
    val airQualityMetrics: MeshtasticAirQualityMetrics? = null,
    val powerMetrics: MeshtasticPowerMetrics? = null,
    val localStats: MeshtasticLocalStats? = null,
    val healthMetrics: MeshtasticHealthMetrics? = null,
    val hostMetrics: MeshtasticHostMetrics? = null,
    /** The nodes it last reported hearing directly (NEIGHBORINFO), with their SNR. */
    val neighbors: List<MeshtasticNeighbor> = emptyList(),
)

/** The row's one-line preview of the node's last frame. */
fun MeshtasticNode.preview(key: String): String? = when {
    lastEncrypted -> "(encrypted, non-default channel)"
    lastPortnum == MESHTASTIC_PORT_TEXT -> lastText?.replace('\n', ' ')?.take(120)
    lastText != null && lastPortnum != null -> "${meshtasticPortnumLabel(lastPortnum)}: $lastText".take(120)
    lastPortnum == MESHTASTIC_PORT_POSITION -> "POSITION update"
    lastPortnum == MESHTASTIC_PORT_NODEINFO -> "NODEINFO: ${longName ?: key}"
    lastPortnum == MESHTASTIC_PORT_TELEMETRY -> "TELEMETRY" + (deviceMetrics?.batteryLevel?.let { " (battery $it%)" } ?: "")
    else -> lastPortnum?.let(::meshtasticPortnumLabel)
}

private const val MESHTASTIC_PORT_TEXT = 1
private const val MESHTASTIC_PORT_POSITION = 3
private const val MESHTASTIC_PORT_NODEINFO = 4
private const val MESHTASTIC_PORT_TELEMETRY = 67

/** Unlike MeshCore, MeshtasticPacket.sender (32-bit NodeNum) is in the always-plaintext 16-byte
 *  header of every packet, recognized portnum or not, so every intact frame counts towards its
 *  sender. */
internal suspend fun applyMeshtasticFrame(nodes: ObjectStore<MeshtasticNode>, frame: LoraFrame, message: MeshtasticPacket) {
    nodes.update(loraIdentityOf(message), frame.timestampMs, frame.knownFrequencyHz, create = ::MeshtasticNode) { node ->
        val heard = node.copy(
            modulation = LoraModulation.of(frame.phy), snrDb = frame.phy.snrDb ?: node.snrDb, lastEncrypted = false, lastText = null,
        )
        when (val info = message.info) {
            is MeshtasticNodeInfo -> heard.copy(
                longName = info.longName ?: node.longName,
                shortName = info.shortName ?: node.shortName,
                hwModel = info.hwModel ?: node.hwModel,
                role = info.role ?: node.role,
                isLicensed = info.isLicensed ?: node.isLicensed,
                lastPortnum = info.portnum,
            )
            is MeshtasticPosition -> {
                val hasPosition = isPlausiblePosition(info.latitude, info.longitude)
                heard.copy(lat = if (hasPosition) info.latitude else node.lat, lon = if (hasPosition) info.longitude else node.lon, lastPortnum = info.portnum)
            }
            is MeshtasticTelemetry -> heard.copy(
                deviceMetrics = info.deviceMetrics ?: node.deviceMetrics,
                environmentMetrics = info.environmentMetrics ?: node.environmentMetrics,
                airQualityMetrics = info.airQualityMetrics ?: node.airQualityMetrics,
                powerMetrics = info.powerMetrics ?: node.powerMetrics,
                localStats = info.localStats ?: node.localStats,
                healthMetrics = info.healthMetrics ?: node.healthMetrics,
                hostMetrics = info.hostMetrics ?: node.hostMetrics,
                telemetryTimestampSec = info.timeSec ?: node.telemetryTimestampSec,
                lastPortnum = info.portnum,
            )
            is MeshtasticTextMessage -> heard.copy(lastPortnum = info.portnum, lastText = info.text)
            // a relayed neighbour report (nodeId != sender) is about another node
            is MeshtasticNeighborInfo -> heard.copy(
                neighbors = if (info.nodeId == null || info.nodeId == message.sender) info.neighbors else node.neighbors,
                lastPortnum = info.portnum,
            )
            is MeshtasticRouting -> heard.copy(lastPortnum = info.portnum)
            is MeshtasticTraceroute -> heard.copy(lastPortnum = info.portnum)
            is MeshtasticWaypoint -> heard.copy(lastPortnum = info.portnum, lastText = info.name)
            is MeshtasticOpaquePayload -> heard.copy(lastPortnum = info.portnum)
            is MeshtasticEncrypted -> heard.copy(lastPortnum = null, lastEncrypted = true)
        }
    }
}

internal val MeshtasticNodeFamily = ObjectFamily(
    kind = ObjectKind.MESHTASTIC_NODE,
    serializer = MeshtasticNode.serializer(),
    displayName = { key, node -> node.longName ?: key },
    position = { node -> node.lat?.let { lat -> node.lon?.let { lon -> LatLon(lat, lon) } } },
    idleTimeoutMs = LORA_IDLE_TIMEOUT_MS,
)
