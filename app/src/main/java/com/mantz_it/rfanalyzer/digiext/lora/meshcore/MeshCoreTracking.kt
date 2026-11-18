package com.mantz_it.rfanalyzer.digiext.lora.meshcore

import com.mantz_it.rfanalyzer.digiext.lora.LoraFrame
import com.mantz_it.rfanalyzer.digiext.lora.MESHCORE_NODE_CHAT
import com.mantz_it.rfanalyzer.digiext.lora.MESHCORE_NODE_REPEATER
import com.mantz_it.rfanalyzer.digiext.lora.MESHCORE_NODE_ROOM_SERVER
import com.mantz_it.rfanalyzer.digiext.lora.MESHCORE_NODE_SENSOR
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreAdvert
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreAnonRequest
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreDiscoverResponse
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreGroupText
import com.mantz_it.rfanalyzer.digiext.lora.MeshCorePacket
import com.mantz_it.rfanalyzer.digiext.lora.loraIdentityOf
import kotlinx.serialization.Serializable
import com.mantz_it.rfanalyzer.digiext.db.LatLon
import com.mantz_it.rfanalyzer.digiext.lora.LoraModulation
import com.mantz_it.rfanalyzer.digiext.lora.MeshCorePayloadType
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore
import com.mantz_it.rfanalyzer.digiext.db.isPlausiblePosition
import com.mantz_it.rfanalyzer.digiext.lora.LORA_IDLE_TIMEOUT_MS

/**
 * <h1>RF Analyzer - MeshCore Tracking</h1>
 *
 * Module:      MeshCoreTracking.kt
 * Description: The tracked MeshCore node: its state ([MeshCoreNode]) with the row preview, its
 *              [MeshCoreNodeFamily], and the fold of a frame into it - adverts, group messages,
 *              anonymous requests and discovery responses; a frame without an attributable identity
 *              is not folded.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * A MeshCore node, folded from every frame that can be attributed to it. Each LoRa protocol has a
 * state type of its own ([com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNode],
 * [com.mantz_it.rfanalyzer.digiext.lora.lorawan.LoRaWanDevice],
 * [com.mantz_it.rfanalyzer.digiext.lora.meshcom.MeshComNode], and for LoRa-APRS AX.25's
 * [com.mantz_it.rfanalyzer.digiext.ax25.AprsStation]): their natural columns differ too much for one
 * shared shape.
 *
 * The identities - [loraIdentityOf] gives both the stored frame's `objectKey` and its object's key:
 *  - MeshCore ADVERT: its 32-byte Ed25519 public key ("MeshCore:" + hex); only adverts whose
 *    signature verifies are attributed. An anonymous request and a discovery response carrying the
 *    full key count towards the same node.
 *  - MeshCore GRP_TXT: no sender key or hash, only a self-reported sender name, so a weaker
 *    namespace of its own ("MeshCoreGroup:" + senderName) that is never merged with an advert's
 *    node - a user who sends both shows up as two rows. Linking them by name would make a packet's
 *    identity depend on what was heard before it, and a name is easily spoofed.
 *  - MeshCore REQ/RESPONSE/TXT_MSG/PATH/ACK/TRACE: no attributable sender (the 1-byte hashes
 *    collide too often to serve as one) - Raw subtab only.
 *  - Meshtastic: the 32-bit node number of its plaintext header ("!" + hex), for every portnum.
 *  - LoRaWAN: the DevAddr of a data frame ("LoRaWAN:" + hex) or, for a device only seen joining,
 *    the DevEUI of its join request ("LoRaWAN:EUI:" + hex); the other frame types carry neither.
 *    LoRaWAN frames carry no position, so no device is on the Map subtab.
 *  - LoRa-APRS: "LoraAprs:" + fromCall, apart from AX.25/APRS's `STATION:`/`OBJECT:` keys.
 *  - MeshCom: "MeshCom:" + the originator's callsign.
 */
@Serializable
data class MeshCoreNode(
    /** The ADVERT's node name, or a group message's self-reported sender name. */
    val name: String? = null,
    // ADVERT-keyed nodes only.
    val publicKeyHex: String? = null,
    val isChatNode: Boolean = false,
    val isRepeater: Boolean = false,
    val isRoomServer: Boolean = false,
    val isSensor: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    // GroupText-keyed nodes only.
    val channelName: String? = null,
    /** What the last trusted frame was - the row text is rendered from it
     *  ([com.mantz_it.rfanalyzer.digiext.lora.meshCoreRowText]). */
    val lastPayloadType: MeshCorePayloadType? = null,
    val lastText: String? = null,
    val modulation: LoraModulation? = null,
    /** SNR of the last frame heard from it, dB. */
    val snrDb: Float? = null,
)

/** The row's one-line preview, e.g. "ADVERT: Node" or "#test DMANTZ: Test123" (the channel isn't
 *  otherwise shown in the compact rows). */
fun MeshCoreNode.preview(): String? = when (lastPayloadType) {
    MeshCorePayloadType.ADVERT -> "ADVERT" + (name?.let { ": $it" } ?: "")
    MeshCorePayloadType.GRP_TXT -> "$channelName $name: ${lastText?.replace('\n', ' ')}".take(120)
    else -> null
}

internal val MeshCoreNodeFamily = ObjectFamily(
    kind = ObjectKind.MESHCORE_NODE,
    serializer = MeshCoreNode.serializer(),
    displayName = { _, node -> node.name?.takeIf { it.isNotBlank() } ?: node.publicKeyHex?.take(8) ?: "(unnamed)" },
    position = { node -> node.lat?.let { lat -> node.lon?.let { lon -> LatLon(lat, lon) } } },
    idleTimeoutMs = LORA_IDLE_TIMEOUT_MS,
)

/** Folds a frame into its node; one without an identity is not folded (see [MeshCoreNode]). */
internal suspend fun applyMeshCoreFrame(nodes: ObjectStore<MeshCoreNode>, frame: LoraFrame, message: MeshCorePacket) {
    val id = loraIdentityOf(message)
    if (id.isEmpty()) return // hash-addressed/opaque payload - no reliable identity, not folded
    nodes.update(id, frame.timestampMs, frame.knownFrequencyHz, create = ::MeshCoreNode) { node ->
        val heard = node.copy(
            modulation = LoraModulation.of(frame.phy), snrDb = frame.phy.snrDb ?: node.snrDb, lastPayloadType = message.payloadType,
        )
        when (val info = message.info) {
            is MeshCoreAdvert -> {
                val hasPosition = info.hasLocation && isPlausiblePosition(info.latitude, info.longitude)
                heard.copy(
                    name = info.name?.takeIf { it.isNotBlank() } ?: node.name,
                    publicKeyHex = info.publicKey.toHex(),
                    isChatNode = info.isChatNode, isRepeater = info.isRepeater,
                    isRoomServer = info.isRoomServer, isSensor = info.isSensor,
                    lat = if (hasPosition) info.latitude else node.lat,
                    lon = if (hasPosition) info.longitude else node.lon,
                )
            }
            is MeshCoreGroupText -> heard.copy(name = info.senderName, channelName = info.channelName, lastText = info.text)
            is MeshCoreAnonRequest -> heard.copy(publicKeyHex = info.senderPublicKey.toHex())
            is MeshCoreDiscoverResponse -> heard.copy(
                publicKeyHex = info.publicKey.toHex(),
                isChatNode = info.nodeType == MESHCORE_NODE_CHAT, isRepeater = info.nodeType == MESHCORE_NODE_REPEATER,
                isRoomServer = info.nodeType == MESHCORE_NODE_ROOM_SERVER, isSensor = info.nodeType == MESHCORE_NODE_SENSOR,
            )
            else -> heard // unreachable - id is only non-empty for the types above, see loraIdentityOf
        }
    }
}
