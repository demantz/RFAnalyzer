package com.mantz_it.rfanalyzer.digiext.lora

import com.mantz_it.rfanalyzer.digiext.lora.aprs.LoraAprsParser
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.LoRaWanParser
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreChannelRegistry
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreParser
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticParser
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.MeshComNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.MeshComParser
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.applyMeshComFrame
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.meshComNodeKey
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketSpec
import com.mantz_it.rfanalyzer.digiext.db.ObjectStores
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer.digiext.lora.aprs.LoraAprsStationFamily
import com.mantz_it.rfanalyzer.digiext.lora.aprs.applyLoraAprsFrame
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.LoRaWanDeviceFamily
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.applyLoRaWanFrame
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.applyMeshCoreFrame
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.applyMeshtasticFrame
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPacket
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPhy
import kotlinx.serialization.Serializable
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.lora.aprs.loraAprsOrigin

/**
 * <h1>RF Analyzer - LoRa Data</h1>
 *
 * Module:      LoraData.kt
 * Description: The data layer of LoRa: [LoraSpec], the mode's packet spec; [LoraPayloadDecoder],
 *              which tells the protocols riding on LoRa apart (MeshCore, Meshtastic, MeshCom,
 *              LoRaWAN, LoRa-APRS) by sync word and structure; and the identity of a decoded
 *              frame - its object key and kind, and whether it is intact enough to be attributed to
 *              a tracked object.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

// How long a LoRa node/device/station stays in memory (and keeps its trail) without being heard.
// Much longer than ADS-B's, since mesh and LoRaWAN stations legitimately beacon far less often
// than aircraft squitter.
internal const val LORA_IDLE_TIMEOUT_MS = 45 * 60_000L

/** The modulation a LoRa node was last heard on. */
@Serializable
data class LoraModulation(val spreadingFactor: Int, val bandwidthHz: Int, val codingRate: Int) {
    /** "SF11, BW 250 kHz, CR 4/5" - a fact's value, so not with the " · " that separates facts. */
    val label: String get() {
        val bandwidthKhz = if (bandwidthHz % 1000 == 0) "${bandwidthHz / 1000}" else "${bandwidthHz / 1000.0}"
        return "SF$spreadingFactor, BW $bandwidthKhz kHz, CR 4/${codingRate + 4}"
    }

    companion object {
        fun of(phy: LoraPhy) = LoraModulation(phy.spreadingFactor, phy.bandwidthHz, phy.codingRate)
    }
}

/** A received LoRa frame. */
typealias LoraFrame = StoredPacket<LoraPhy, LoraPayloadMessage>

// A frame no protocol parser recognized whose payload CRC failed holds corrupted bytes, not an
// unknown protocol, and is discarded ([LoraSpec.discard]). A frame sent without a payload CRC
// (LoRaWAN downlinks, for one) does not count as noise. Frames of a recognized protocol stay in
// the Raw subtab with a failed CRC, just without an object ([isAttributable]).
fun isNoisyRawPayload(phy: LoraPhy, message: LoraPayloadMessage): Boolean =
    message is RawLoraPayload && phy.hasCrc && !phy.crcValid

/**
 * Frames sent without a payload CRC count as intact from this decoder reliability on - above the
 * highest score a wrong frame reached in the receiver's evaluation (0.997).
 */
const val LORA_MIN_RELIABILITY = 0.999f

/** Whether the frame's bytes are known to be intact: a matching payload CRC, or - for a frame sent
 *  without one - a decoder reliability of at least [LORA_MIN_RELIABILITY]. */
fun isIntact(phy: LoraPhy): Boolean = if (phy.hasCrc) phy.crcValid else (phy.reliability ?: 0f) >= LORA_MIN_RELIABILITY

/**
 * Whether a frame may be attributed to a tracked object at all: its bytes are intact, or its
 * content carries an integrity check of its own that passed - a MeshCore group message's MAC, a
 * MeshCore advert's signature. A corrupted frame's identity bytes are as suspect as the rest (a
 * flipped bit in a Meshtastic sender id would invent a node), so it stays in the Raw subtab
 * without an object. An advert whose signature fails is never attributed, even with a matching CRC.
 */
fun isAttributable(phy: LoraPhy, message: LoraPayloadMessage): Boolean {
    if (message is MeshComFrame && !message.checksumValid) return false
    val info = (message as? MeshCorePacket)?.info
    if (info is MeshCoreAdvert) return info.signatureValid
    return isIntact(phy) || info is MeshCoreGroupText
}

/**
 * LoRa is parsed app-side: the extension sends the dewhitened payload, [LoraPayloadDecoder] tells
 * the protocols riding on it apart. One mode, five kinds of tracked object; only attributable
 * frames ([isAttributable]) get an object key and change their object.
 */
internal object LoraSpec : DigitalPacketSpec<LoraPacket, LoraPhy, LoraPayloadMessage>(
    mode = DigitalMode.LORA,
    phySerializer = LoraPhy.serializer(),
    dataSerializer = LoraPayloadMessage.serializer(),
    parserVersion = LoraPayloadDecoder.PARSER_VERSION,
) {
    override fun phyOf(packet: LoraPacket) = packet.phy
    override fun parse(raw: Bytes, phy: LoraPhy) = LoraPayloadDecoder.decode(raw.toByteArray(), phy.syncWord, intact = isIntact(phy))
    override suspend fun awaitParserInputs() = MeshCoreChannelRegistry.awaitKeys()
    override fun objectKey(raw: Bytes?, phy: LoraPhy, data: LoraPayloadMessage) =
        if (isAttributable(phy, data)) loraIdentityOf(data) else ""

    /** The other station in a relayed frame, so its detail lists them too: the originator of a
     *  LoRa-APRS packet relayed by another station, the MeshCom relay a frame was heard from. */
    override fun secondaryObjectKey(raw: Bytes?, phy: LoraPhy, data: LoraPayloadMessage): String? {
        if (!isAttributable(phy, data)) return null
        if (data is MeshComFrame) return if (data.hops > 0) meshComNodeKey(data.lastTransmitter) else null
        val (origin, _) = (data as? LoraAprsPacket)?.let(::loraAprsOrigin) ?: return null
        return loraAprsObjectKey(origin)
    }
    override fun searchTerms(packet: LoraFrame) = loraSearchTerms(packet.data)
    override fun discard(packet: LoraFrame) = isNoisyRawPayload(packet.phy, packet.data)

    override suspend fun fold(packet: LoraFrame, objects: ObjectStores) {
        if (packet.objectKey.isNullOrEmpty()) return
        when (val message = packet.data) {
            is MeshCorePacket -> applyMeshCoreFrame(objects[MeshCoreNodeFamily], packet, message)
            is MeshtasticPacket -> applyMeshtasticFrame(objects[MeshtasticNodeFamily], packet, message)
            is LoRaWanFrame -> applyLoRaWanFrame(objects[LoRaWanDeviceFamily], packet, message)
            is LoraAprsPacket -> applyLoraAprsFrame(objects[LoraAprsStationFamily], packet, message)
            is MeshComFrame -> applyMeshComFrame(objects[MeshComNodeFamily], packet, message)
            is MeshComAck, RawLoraPayload -> {} // no attributable identity - Raw subtab only
        }
    }
}

/**
 * Entry point for higher-layer LoRa-borne protocol decoding of a frame's payload ([LoraSpec]).
 * Runs app-side, so stored frames are re-parsed when a parser improves (Meshtastic keeps adding
 * portnums).
 *
 * The extension's LoraReceiver accepts a short list of known sync words and reports which one
 * matched, so dispatch here is deterministic by sync word rather than a structural guess -
 * MeshCoreParser's lenient structural gate could otherwise accept a non-MeshCore (e.g.
 * Meshtastic) payload. An unrecognized sync word falls back to trying every parser in order.
 *
 * MeshCom shares Meshtastic's sync word (0x2B): its strict structural check, ASCII callsign path and
 * checksum included (MeshComParser), runs before Meshtastic's parser, which has no integrity check
 * and would take any payload of 16 bytes or more. A frame with a failed CRC that starts like MeshCom
 * but lost its structure stays raw (and is dropped as noise) instead of passing for Meshtastic.
 *
 * LoRa-APRS is checked first, unconditionally, ahead of the sync-word switch: real-world
 * LoRa-APRS trackers/iGates use the same default sync word (0x12) most MeshCore deployments
 * do, so it can't be separated from MeshCore by sync word the way Meshtastic/LoRaWAN are.
 * Its 3-byte magic header plus a structural TNC2-header check (see LoraAprsParser) make a
 * false-positive match against MeshCore/Meshtastic/LoRaWAN payloads effectively impossible,
 * so trying it first is safe regardless of which sync word ended up locked.
 *
 * Adding another protocol that (like MeshCore/Meshtastic/LoRaWAN) has its own sync word: add
 * a `<Foo>Parser.tryParse(ByteArray): X?`-shaped object and one more branch below. Adding one
 * that (like LoRa-APRS) doesn't: gate it structurally and pre-filter it the same way.
 */
object LoraPayloadDecoder {
    /**
     * Raise whenever the output of [decode] changes for the same input - a new sub-protocol, a
     * fixed bug, an expanded portnum table: stored rows of a lower version are re-parsed from
     * their raw bytes and phy.
     */
    const val PARSER_VERSION = 4

    private const val SYNC_WORD_MESHCORE = 0x12
    private const val SYNC_WORD_MESHTASTIC = 0x2B
    private const val SYNC_WORD_LORAWAN = 0x34

    /** [intact]: false for a frame whose LoRa CRC failed ([isIntact]). */
    fun decode(payload: ByteArray, syncWord: Int = SYNC_WORD_MESHCORE, intact: Boolean = true): LoraPayloadMessage {
        LoraAprsParser.tryParse(payload)?.let { return it }
        return when (syncWord) {
            // Recognized sync word: only that protocol's parser gets a try. If it rejects
            // the payload, that's a real rejection — don't cross-try the other protocol's
            // parser (MeshtasticParser in particular has no MAC to reject garbage with, so
            // blindly cross-trying it would swallow MeshCore's own legitimate rejections).
            SYNC_WORD_MESHCORE -> MeshCoreParser.tryParse(payload) ?: RawLoraPayload
            SYNC_WORD_MESHTASTIC -> MeshComParser.tryParse(payload)
                ?: RawLoraPayload.takeIf { !intact && MeshComParser.looksDamaged(payload) }
                ?: MeshtasticParser.tryParse(payload) ?: RawLoraPayload
            SYNC_WORD_LORAWAN -> LoRaWanParser.tryParse(payload) ?: RawLoraPayload
            // Unrecognized sync word (only when the receiver is configured with other sync
            // words): every parser in turn.
            else -> MeshCoreParser.tryParse(payload)
                ?: MeshComParser.tryParse(payload)
                ?: MeshtasticParser.tryParse(payload)
                ?: LoRaWanParser.tryParse(payload)
                ?: RawLoraPayload
        }
    }
}

/**
 * The namespaced per-sub-protocol identity string for a decoded LoRa message - both the stored
 * frame's `objectKey` ([LoraSpec]) and its tracked object's key (the apply*Frame folds), so the two
 * can never drift apart. `""` for payloads with no attributable identity (RawLoraPayload,
 * MeshCore payload types with no reliable identity, LoRaWAN frames with neither DevAddr nor DevEUI) - a
 * tombstone, not "not yet resolved". MeshCore frames that carry the sender's full public key (an
 * advert, an anonymous request, a discovery response with the whole key) share its node.
 */
fun loraIdentityOf(message: LoraPayloadMessage): String = when (message) {
    is MeshCorePacket -> when (val info = message.info) {
        is MeshCoreAdvert -> meshCoreNodeKey(info.publicKey)
        is MeshCoreAnonRequest -> meshCoreNodeKey(info.senderPublicKey)
        is MeshCoreDiscoverResponse -> if (info.publicKey.size == 32) meshCoreNodeKey(info.publicKey) else ""
        is MeshCoreGroupText -> "MeshCoreGroup:${info.senderName}"
        else -> ""
    }
    is MeshtasticPacket -> "!" + message.sender.toString(16)
    // A joining device has no DevAddr yet and is keyed by its DevEUI instead; the two cannot be linked.
    is LoRaWanFrame -> message.devAddr?.let { "LoRaWAN:$it" } ?: message.devEui?.let { "LoRaWAN:EUI:$it" } ?: ""
    is LoraAprsPacket -> loraAprsObjectKey(message.fromCall)
    is MeshComFrame -> meshComNodeKey(message.origin)
    is MeshComAck, is RawLoraPayload -> ""
}

private fun meshCoreNodeKey(publicKey: Bytes) = "MeshCore:" + publicKey.toHex()

/** The kind of tracked object a decoded LoRa message belongs to, or null for a raw payload. */
fun loraKindOf(message: LoraPayloadMessage): ObjectKind? = when (message) {
    is MeshCorePacket -> ObjectKind.MESHCORE_NODE
    is MeshtasticPacket -> ObjectKind.MESHTASTIC_NODE
    is LoRaWanFrame -> ObjectKind.LORAWAN_DEVICE
    is LoraAprsPacket -> ObjectKind.LORA_APRS_STATION
    is MeshComFrame -> ObjectKind.MESHCOM_NODE
    is MeshComAck, is RawLoraPayload -> null
}

/**
 * The identity-like byte fields of [message] as hex, for the stored packet's search text - raw
 * bytes are otherwise left out of it (see `genericSearchText`). Routing hashes only: the sender's
 * own identity is already searchable through [loraIdentityOf], and payloads, ciphertext, MACs and
 * signatures are not something to search for.
 */
internal fun loraSearchTerms(message: LoraPayloadMessage): List<String> {
    if (message !is MeshCorePacket) return emptyList()
    val hashes = when (val info = message.info) {
        is MeshCoreEncryptedEnvelope -> listOfNotNull(info.srcHash, info.destHash, info.channelHash)
        is MeshCoreAnonRequest -> listOf(info.destHash)
        is MeshCoreTrace -> info.pathHashes
        else -> emptyList()
    }
    // a trace's path field holds SNRs, not node hashes
    val path = if (message.info is MeshCoreTrace) emptyList() else message.path
    return (path + hashes).map { it.toHex() }
}

/** LoRa-APRS's identity namespace - kept apart from AX.25/APRS's own `STATION:`/`OBJECT:` keys. */
fun loraAprsObjectKey(callsign: String): String = "LoraAprs:$callsign"
