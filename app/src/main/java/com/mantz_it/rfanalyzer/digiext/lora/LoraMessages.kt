package com.mantz_it.rfanalyzer.digiext.lora

import com.mantz_it.rfanalyzer.digiext.aprs.AprsInfo
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * <h1>RF Analyzer - LoRa Messages</h1>
 *
 * Module:      LoraMessages.kt
 * Description: Higher-layer protocol decode of a LoRa frame's payload. One subtype per LoRa-borne
 *              protocol this app understands (MeshCore, Meshtastic, MeshCom, LoRaWAN, LoRa-APRS).
 *              RawLoraPayload is the fallback when no protocol-specific parser recognizes the byte
 *              structure - its bytes are the frame's raw payload. See LoraPayloadDecoder for the
 *              dispatch order. All subtypes live in this one file/package: Kotlin requires a sealed
 *              class's direct subclasses to share its package.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Serializable
sealed class LoraPayloadMessage

/** No parser recognized the payload; the frame's raw bytes are all there is. */
@Serializable
@SerialName("RawLoraPayload")
data object RawLoraPayload : LoraPayloadMessage()

/* ----------------------------------------------------------------------
 * MeshCore packet types (https://github.com/meshcore-dev/MeshCore docs/packet_format.md)
 * -------------------------------------------------------------------- */

/** MeshCore header byte bits 0-1. */
enum class MeshCoreRouteType { TRANSPORT_FLOOD, FLOOD, DIRECT, TRANSPORT_DIRECT }

/** MeshCore header byte bits 2-5, in value order. MeshCoreParser rejects RESERVED_C/D/E, so no
 *  packet carries them. */
enum class MeshCorePayloadType {
    REQ, RESPONSE, TXT_MSG, ACK, ADVERT, GRP_TXT, GRP_DATA, ANON_REQ,
    PATH, TRACE, MULTIPART, CONTROL, RESERVED_C, RESERVED_D, RESERVED_E, RAW_CUSTOM
}

/**
 * A structurally-valid MeshCore packet decoded from a LoRa PHY payload.
 * `info` carries the payload-type-specific decode (MeshCoreOpaquePayload for MULTIPART,
 * RAW_CUSTOM and control sub-types without a documented layout).
 */
@Serializable
@SerialName("MeshCorePacket")
data class MeshCorePacket(
    val routeType: MeshCoreRouteType,
    val payloadType: MeshCorePayloadType,
    val payloadVersion: Int,            // header bits 6-7; 0 = v1, others reserved
    val transportCodes: Bytes?,         // 4 raw bytes; present only for TRANSPORT_FLOOD/TRANSPORT_DIRECT
    val pathHashSize: Int,              // 1, 2, or 3 bytes per path entry (from path_length byte bits 6-7)
    val path: List<Bytes>,              // one raw hash per hop, each pathHashSize bytes; empty if hop_count==0
    val info: MeshCoreInfo,
) : LoraPayloadMessage() {
    override fun toString(): String =
        "MeshCorePacket(route=$routeType type=$payloadType v$payloadVersion hops=${path.size} info=$info)"
}

@Serializable
sealed class MeshCoreInfo

/** ADVERT (0x4): fully plaintext. [signatureValid] is the Ed25519 check of the signature over
 *  public key, timestamp and appdata. */
@Serializable
@SerialName("MeshCoreAdvert")
data class MeshCoreAdvert(
    val publicKey: Bytes,         // 32 bytes, Ed25519 public key
    val timestamp: Long,          // unix seconds, decoded little-endian
    val signature: Bytes,         // 64 bytes, Ed25519 signature
    val flags: Int,               // raw appdata flags byte (0 if appdata absent)
    val latitude: Double?,        // decimal degrees; present iff hasLocation
    val longitude: Double?,       // decimal degrees; present iff hasLocation
    val feature1: Bytes?,         // 2 raw bytes if flags & 0x20 set (reserved), else null
    val feature2: Bytes?,         // 2 raw bytes if flags & 0x40 set (reserved), else null
    val name: String?,            // remainder as UTF-8 text if flags & 0x80 set, else null
    val signatureValid: Boolean,
) : MeshCoreInfo() {
    /** The node type in the flags' low nibble ([MESHCORE_NODE_CHAT] …), not a bit set. */
    val nodeType: Int get() = flags and 0x0F
    val isChatNode: Boolean get() = nodeType == MESHCORE_NODE_CHAT
    val isRepeater: Boolean get() = nodeType == MESHCORE_NODE_REPEATER
    val isRoomServer: Boolean get() = nodeType == MESHCORE_NODE_ROOM_SERVER
    val isSensor: Boolean get() = nodeType == MESHCORE_NODE_SENSOR
    val hasLocation: Boolean get() = flags and 0x10 != 0

    override fun toString(): String =
        "MeshCoreAdvert(name=${name ?: "?"} type=$nodeType signatureValid=$signatureValid " +
        "loc=${if (hasLocation) "$latitude,$longitude" else "none"})"
}

/** MeshCore node types (advert flags' low nibble, DISCOVER_RESP flags' low nibble). */
const val MESHCORE_NODE_CHAT = 1
const val MESHCORE_NODE_REPEATER = 2
const val MESHCORE_NODE_ROOM_SERVER = 3
const val MESHCORE_NODE_SENSOR = 4

/** Hash(+hash)+MAC-prefixed encrypted body: REQ, RESPONSE, TXT_MSG and PATH (destination and
 *  source hash), GRP_TXT and GRP_DATA (channel hash). The ciphertext needs the two nodes' shared
 *  secret or the channel key; group messages on a channel the app holds the key of are decoded
 *  down to [MeshCoreGroupText]/[MeshCoreGroupData] instead, see MeshCoreChannelCrypto. */
@Serializable
@SerialName("MeshCoreEncryptedEnvelope")
data class MeshCoreEncryptedEnvelope(
    val destHash: Bytes?,         // 1 byte; REQ/RESPONSE/TXT_MSG/PATH
    val srcHash: Bytes?,          // 1 byte; REQ/RESPONSE/TXT_MSG/PATH
    val channelHash: Bytes?,      // 1 byte; present for GRP_TXT/GRP_DATA
    val mac: Bytes,               // 2 bytes
    val ciphertext: Bytes,        // remainder, opaque
) : MeshCoreInfo() {
    override fun toString(): String = "MeshCoreEncryptedEnvelope(cipherLen=${ciphertext.size})"
}

/** Decoded GRP_TXT plaintext for a MeshCore group channel whose key the app holds — the
 *  well-known "Public" channel (hardcoded firmware PSK), a built-in or user-added hashtag
 *  channel, or a candidate-dictionary match. All of these are the protocol's *public* broadcast
 *  channels (key derived from, or shared alongside, the channel name), not confidential ones.
 *  See MeshCoreChannelRegistry / MeshCoreChannelCrypto. */
@Serializable
@SerialName("MeshCoreGroupText")
data class MeshCoreGroupText(
    val channelName: String,   // "Public" or a "#hashtag" channel name (the matched channel)
    val timestamp: Long,       // unix seconds, decoded little-endian
    val senderName: String,    // text before the first ": " separator
    val text: String,          // text after the first ": " separator
) : MeshCoreInfo() {
    override fun toString(): String = "MeshCoreGroupText(channel=$channelName from=$senderName text=$text)"
}

/** Decoded GRP_DATA plaintext for a group channel whose key the app holds. Unlike GRP_TXT this
 *  carries no human-readable text and no sender identity — just a [dataType] tag and raw [data]
 *  bytes (the per-type structure is not decoded). Surfaced in the Raw subtab only; never folded
 *  into a tracked node (see loraIdentityOf). See MeshCoreChannelCrypto.tryDecryptGroupData. */
@Serializable
@SerialName("MeshCoreGroupData")
data class MeshCoreGroupData(
    val channelName: String = "",   // the matched channel ("Public" or "#hashtag")
    val dataType: Int = 0,          // uint16 data_type (LE) from the decrypted payload
    val data: Bytes = Bytes.EMPTY,
) : MeshCoreInfo() {
    override fun toString(): String = "MeshCoreGroupData(channel=$channelName type=$dataType len=${data.size})"
}

/** ACK (0x3): a 4-byte checksum of the acknowledged message (timestamp, text, sender's public
 *  key). Its algorithm is not settled (the MeshCore docs say "CRC", other sources a truncated
 *  SHA-256), so it is kept as raw bytes and never presented as verified. */
@Serializable
@SerialName("MeshCoreAck")
data class MeshCoreAck(
    val checksum: Bytes,   // 4 raw bytes, as received, no byte-order or algorithm claim
) : MeshCoreInfo() {
    override fun toString(): String = "MeshCoreAck(checksum=$checksum)"
}

/** ANON_REQ (0x7): a request from a node the recipient may not know yet (a login, say), so the
 *  sender's full public key travels in clear ahead of the encrypted body. */
@Serializable
@SerialName("MeshCoreAnonRequest")
data class MeshCoreAnonRequest(
    val destHash: Bytes,          // 1 byte
    val senderPublicKey: Bytes,   // 32 bytes
    val mac: Bytes,               // 2 bytes
    val ciphertext: Bytes,
) : MeshCoreInfo() {
    override fun toString(): String = "MeshCoreAnonRequest(sender=${senderPublicKey.toHex().take(8)} cipherLen=${ciphertext.size})"
}

/** TRACE (0x9): a direct-routed probe along [pathHashes]; every hop that forwards it appends the
 *  SNR it received it with to the packet's path field, which therefore holds [snrs] instead of
 *  node hashes. */
@Serializable
@SerialName("MeshCoreTrace")
data class MeshCoreTrace(
    val tag: Long,
    val authCode: Long,
    val flags: Int,
    val pathHashes: List<Bytes>,
    /** dB per hop so far, in path order. */
    val snrs: List<Float>,
) : MeshCoreInfo() {
    override fun toString(): String = "MeshCoreTrace(tag=${tag.toString(16)} hops=${pathHashes.size} snrs=$snrs)"
}

/** CONTROL (0xB) DISCOVER_REQ: asks the nodes in range (zero hop) to answer with their identity. */
@Serializable
@SerialName("MeshCoreDiscoverRequest")
data class MeshCoreDiscoverRequest(
    val prefixOnly: Boolean,
    /** One bit per node type ([MESHCORE_NODE_CHAT] = bit 1 …). */
    val typeFilter: Int,
    val tag: Long,
    val since: Long? = null,
) : MeshCoreInfo()

/** CONTROL (0xB) DISCOVER_RESP: a node's answer - its type, the SNR it heard the request with and
 *  its public key (or the key's first 8 bytes). */
@Serializable
@SerialName("MeshCoreDiscoverResponse")
data class MeshCoreDiscoverResponse(
    val nodeType: Int,
    val snr: Float,
    val tag: Long,
    val publicKey: Bytes,
) : MeshCoreInfo()

/** Fallback for the MeshCore payloads without a documented field layout: MULTIPART, RAW_CUSTOM,
 *  control sub-types other than discovery. Route type / payload type / path are still available
 *  on the enclosing MeshCorePacket. */
@Serializable
@SerialName("MeshCoreOpaquePayload")
data class MeshCoreOpaquePayload(
    val payload: Bytes,           // full body after header/transport_codes/path, unparsed
) : MeshCoreInfo() {
    override fun toString(): String = "MeshCoreOpaquePayload(len=${payload.size})"
}

/* ----------------------------------------------------------------------
 * Meshtastic packet types (github.com/meshtastic/firmware, github.com/meshtastic/protobufs)
 * -------------------------------------------------------------------- */

/**
 * A structurally-valid Meshtastic packet decoded from a LoRa PHY payload (16-byte
 * header: dest/sender/packetId/flags/channelHash/nextHop/relayNode, matching
 * firmware's mesh/PacketHeader). `info` carries the decrypted-and-parsed payload
 * when a key the app holds decrypts it to a plausible protobuf `Data` message (see
 * MeshtasticParser); otherwise [MeshtasticEncrypted] — Meshtastic's Data envelope has
 * no authentication tag, so (unlike MeshCore) there is no MAC to verify decryption
 * succeeded; structural plausibility of the decoded protobuf is the only gate.
 */
@Serializable
@SerialName("MeshtasticPacket")
data class MeshtasticPacket(
    val dest: Long,          // node ID; 0xFFFFFFFF = NODENUM_BROADCAST
    val sender: Long,        // node ID
    val packetId: Long,
    val hopLimit: Int,       // header flags bits 0-2; remaining hops allowed
    val wantAck: Boolean,    // header flags bit 3
    val hopStart: Int,       // header flags bits 5-7; hops allowed at origin
    val viaMqtt: Boolean,    // header flags bit 4
    val channelHash: Int,    // 1 byte: xor of the channel name's and key's bytes
    val info: MeshtasticInfo,
    /** Header byte 14 (firmware 2.6+): last byte of the node expected to relay next, 0 = none. */
    val nextHop: Int = 0,
    /** Header byte 15 (firmware 2.6+): last byte of the node that sent this copy, 0 = unknown. */
    val relayNode: Int = 0,
    /** The decrypted `Data` envelope's fields besides port and payload; null while encrypted. */
    val data: MeshtasticDataFields? = null,
    /** The key that decrypted it ([MeshtasticParser]'s labels); null while encrypted. */
    val decryptedWith: String? = null,
) : LoraPayloadMessage() {
    override fun toString(): String =
        "MeshtasticPacket(dest=${dest.toString(16)} sender=${sender.toString(16)} hopLimit=$hopLimit info=$info)"
}

/** `Data` envelope fields besides portnum and payload (request/reply linkage, reactions). */
@Serializable
data class MeshtasticDataFields(
    val wantResponse: Boolean = false,
    val dest: Long? = null,
    val source: Long? = null,
    /** The packet this one answers (an ack, a traceroute or telemetry reply). */
    val requestId: Long? = null,
    /** The message this one replies or reacts to. */
    val replyId: Long? = null,
    /** The payload is an emoji reaction to [replyId]. */
    val emoji: Boolean = false,
    val bitfield: Int? = null,
)

@Serializable
sealed class MeshtasticInfo

/** Plain UTF-8 text: TEXT_MESSAGE_APP (1), DETECTION_SENSOR_APP (10), ALERT_APP (11),
 *  RANGE_TEST_APP (66) - [portnum] tells them apart. */
@Serializable
@SerialName("MeshtasticTextMessage")
data class MeshtasticTextMessage(
    val portnum: Int,
    val text: String,
) : MeshtasticInfo() {
    override fun toString(): String = "MeshtasticTextMessage(text=$text)"
}

/** PortNum NODEINFO_APP (4): best-effort decode of the protobuf `User` submessage. */
@Serializable
@SerialName("MeshtasticNodeInfo")
data class MeshtasticNodeInfo(
    val portnum: Int,
    val nodeId: String?,
    val longName: String?,
    val shortName: String?,
    val hwModel: Int? = null,       // User.hw_model enum value (HardwareModel protobuf enum)
    val role: Int? = null,          // User.role enum value (Config.DeviceConfig.Role)
    val isLicensed: Boolean? = null,
) : MeshtasticInfo()

/** PortNum POSITION_APP (3): best-effort decode of the protobuf `Position` submessage. */
@Serializable
@SerialName("MeshtasticPosition")
data class MeshtasticPosition(
    val portnum: Int,
    val latitude: Double?,   // decimal degrees, decoded from latitude_i * 1e-7
    val longitude: Double?,  // decimal degrees, decoded from longitude_i * 1e-7
    val altitudeM: Int?,
    val groundSpeed: Int? = null,   // m/s
    val groundTrack: Int? = null,   // degrees * 1e-5, true north
    val satsInView: Int? = null,
    val pdop: Int? = null,          // positional dilution of precision * 100
    val timeSec: Long? = null,      // GPS-derived unix timestamp of the fix
) : MeshtasticInfo()

/** PortNum TELEMETRY_APP (67): the protobuf `Telemetry` message - one of its variants is set. */
@Serializable
@SerialName("MeshtasticTelemetry")
data class MeshtasticTelemetry(
    val portnum: Int,
    val timeSec: Long?,
    val deviceMetrics: MeshtasticDeviceMetrics?,
    val environmentMetrics: MeshtasticEnvironmentMetrics?,
    val airQualityMetrics: MeshtasticAirQualityMetrics? = null,
    val powerMetrics: MeshtasticPowerMetrics? = null,
    val localStats: MeshtasticLocalStats? = null,
    val healthMetrics: MeshtasticHealthMetrics? = null,
    val hostMetrics: MeshtasticHostMetrics? = null,
) : MeshtasticInfo()

@Serializable
data class MeshtasticDeviceMetrics(
    val batteryLevel: Int?,       // percent, 0-100 (101 = powered/no battery per firmware convention)
    val voltage: Float?,          // volts
    val channelUtilization: Float?, // percent
    val airUtilTx: Float?,        // percent
    val uptimeSeconds: Long?,
)

@Serializable
data class MeshtasticEnvironmentMetrics(
    val temperature: Float?,        // degrees Celsius
    val relativeHumidity: Float?,   // percent
    val barometricPressure: Float?, // hPa
    val gasResistance: Float? = null, // MOhm
    val voltage: Float? = null,
    val current: Float? = null,     // mA
    val iaq: Int? = null,           // indoor air quality index 0-500
    val distance: Float? = null,    // mm (water level etc.)
    val lux: Float? = null,
    val windDirection: Int? = null, // degrees
    val windSpeed: Float? = null,   // m/s
    val windGust: Float? = null,    // m/s
    val weight: Float? = null,      // kg
    val radiation: Float? = null,   // µR/h
    val rainfall1h: Float? = null,  // mm
    val rainfall24h: Float? = null, // mm
    val soilMoisture: Int? = null,  // percent
    val soilTemperature: Float? = null, // degrees Celsius
)

/** Particle counts and CO2 (`AirQualityMetrics`). */
@Serializable
data class MeshtasticAirQualityMetrics(
    val pm10Standard: Int? = null,  // µg/m³
    val pm25Standard: Int? = null,
    val pm100Standard: Int? = null,
    val pm10Environmental: Int? = null,
    val pm25Environmental: Int? = null,
    val pm100Environmental: Int? = null,
    val co2: Int? = null,           // ppm
)

/** Voltage (V) and current (mA) of each measured channel (`PowerMetrics`). */
@Serializable
data class MeshtasticPowerMetrics(val channels: List<MeshtasticPowerChannel> = emptyList())

@Serializable
data class MeshtasticPowerChannel(val channel: Int, val voltage: Float? = null, val current: Float? = null)

/** The node's own view of the mesh (`LocalStats`). */
@Serializable
data class MeshtasticLocalStats(
    val uptimeSeconds: Long? = null,
    val channelUtilization: Float? = null, // percent
    val airUtilTx: Float? = null,          // percent
    val packetsTx: Long? = null,
    val packetsRx: Long? = null,
    val packetsRxBad: Long? = null,
    val onlineNodes: Int? = null,
    val totalNodes: Int? = null,
    val rxDupe: Long? = null,
    val txRelay: Long? = null,
    val txRelayCanceled: Long? = null,
    val noiseFloor: Int? = null,           // dBm
)

@Serializable
data class MeshtasticHealthMetrics(
    val heartBpm: Int? = null,
    val spO2: Int? = null,          // percent
    val temperature: Float? = null, // degrees Celsius
)

/** A Linux host running meshtasticd (`HostMetrics`). */
@Serializable
data class MeshtasticHostMetrics(
    val uptimeSeconds: Long? = null,
    val freeMemBytes: Long? = null,
    val diskFreeBytes: Long? = null,
    /** Load averages × 100. */
    val load1: Int? = null,
    val load5: Int? = null,
    val load15: Int? = null,
    val userString: String? = null,
)

/** ROUTING_APP (5): an acknowledgement ([errorReason] 0, the acked packet in `Data.request_id`),
 *  an error report, or a route request/reply of the older traceroute. */
@Serializable
@SerialName("MeshtasticRouting")
data class MeshtasticRouting(
    val portnum: Int,
    val errorReason: Int? = null,
    val routeRequest: MeshtasticRoute? = null,
    val routeReply: MeshtasticRoute? = null,
) : MeshtasticInfo()

/** TRACEROUTE_APP (70): the nodes a traceroute passed so far, each with the SNR it heard the
 *  previous hop with. */
@Serializable
@SerialName("MeshtasticTraceroute")
data class MeshtasticTraceroute(val portnum: Int, val route: MeshtasticRoute) : MeshtasticInfo()

@Serializable
data class MeshtasticRoute(
    val route: List<Long> = emptyList(),
    /** dB per hop towards the destination; null where a hop measured none. */
    val snrTowards: List<Float?> = emptyList(),
    val routeBack: List<Long> = emptyList(),
    val snrBack: List<Float?> = emptyList(),
)

/** NEIGHBORINFO_APP (71): the nodes a node hears directly, with their SNR. */
@Serializable
@SerialName("MeshtasticNeighborInfo")
data class MeshtasticNeighborInfo(
    val portnum: Int,
    val nodeId: Long? = null,
    val lastSentById: Long? = null,
    val broadcastIntervalSec: Int? = null,
    val neighbors: List<MeshtasticNeighbor> = emptyList(),
) : MeshtasticInfo()

@Serializable
data class MeshtasticNeighbor(
    val nodeId: Long,
    val snr: Float? = null,
    val lastRxTimeSec: Long? = null,
    val broadcastIntervalSec: Int? = null,
)

/** WAYPOINT_APP (8): a named point shared with the mesh ([latitude] null = deleted). */
@Serializable
@SerialName("MeshtasticWaypoint")
data class MeshtasticWaypoint(
    val portnum: Int,
    val id: Long,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Unix seconds; 0/null = never. */
    val expireSec: Long? = null,
    /** Only this node may change it; 0/null = anyone. */
    val lockedTo: Long? = null,
    val name: String? = null,
    val description: String? = null,
    /** Unicode code point of the waypoint's emoji. */
    val icon: Int? = null,
) : MeshtasticInfo()

/** Fallback for every portnum this app does not decode field by field (ADMIN_APP - encrypted
 *  with the node's key -, TEXT_MESSAGE_COMPRESSED_APP, STORE_FORWARD_APP, PAXCOUNTER_APP, ...). */
@Serializable
@SerialName("MeshtasticOpaquePayload")
data class MeshtasticOpaquePayload(
    val portnum: Int,
    val payload: Bytes,
) : MeshtasticInfo() {
    override fun toString(): String = "MeshtasticOpaquePayload(portnum=$portnum len=${payload.size})"
}

/** Header was structurally valid but the payload didn't decrypt+parse to a plausible
 *  Data protobuf under any key the app can derive (see MeshtasticParser) — a private channel or
 *  a direct message encrypted with the recipient's public key. */
@Serializable
@SerialName("MeshtasticEncrypted")
data class MeshtasticEncrypted(
    val ciphertext: Bytes,
) : MeshtasticInfo() {
    override fun toString(): String = "MeshtasticEncrypted(cipherLen=${ciphertext.size})"
}

/* ----------------------------------------------------------------------
 * LoRaWAN structural decode of a LoRa PHY payload locked with sync word 0x34
 * (the standard LoRaWAN public-network sync word). Envelope only: the MHDR/FHDR
 * fields (message type, DevAddr, FCtrl flags, FCnt) are cleartext and decoded
 * here; FRMPayload and the MIC need AppSKey/NwkSKey/AppKey, which this app
 * doesn't have, so they stay opaque in encryptedTail. See LoRaWanParser.kt
 * for the structural-validity gate.
 * -------------------------------------------------------------------- */

enum class LoRaWanMType {
    JOIN_REQUEST, JOIN_ACCEPT,
    UNCONFIRMED_DATA_UP, UNCONFIRMED_DATA_DOWN,
    CONFIRMED_DATA_UP, CONFIRMED_DATA_DOWN,
    REJOIN_REQUEST, PROPRIETARY;

    val isData: Boolean
        get() = this == UNCONFIRMED_DATA_UP || this == UNCONFIRMED_DATA_DOWN ||
                this == CONFIRMED_DATA_UP || this == CONFIRMED_DATA_DOWN

    val isDownlink: Boolean
        get() = this == UNCONFIRMED_DATA_DOWN || this == CONFIRMED_DATA_DOWN || this == JOIN_ACCEPT
}

/**
 * A single piggybacked LoRaWAN MAC command walked out of FOpts, which LoRaWAN 1.0.x sends in
 * clear - see [LoRaWanFrame.fOpts]. [cid] is the command identifier; [payload] is the
 * command-specific bytes (their length follows from cid and direction, see
 * LoRaWanParser.MAC_COMMAND_LENGTHS).
 */
@Serializable
@SerialName("LoRaWanMacCommand")
data class LoRaWanMacCommand(
    val cid: Int,
    val payload: Bytes,
)

/**
 * A structurally-valid LoRaWAN frame (MHDR byte parses to a known MType and the
 * payload length is consistent with that MType's minimum size). Only [mtype]/[major]
 * are populated for JoinAccept/RejoinRequest/Proprietary — their bodies are fully
 * encrypted (JoinAccept with AppKey) or not worth decoding further here
 * (RejoinRequest/Proprietary); Data messages additionally get FHDR fields, and a
 * JoinRequest its EUIs and nonce.
 */
@Serializable
@SerialName("LoRaWanFrame")
data class LoRaWanFrame(
    val mtype: LoRaWanMType,
    val major: Int,                    // MHDR bits 0-1; 0 = LoRaWAN R1
    val devAddr: String? = null,       // 4B LE, as 8 hex digits; Data messages only
    val adr: Boolean? = null,          // FCtrl bit 7
    val ack: Boolean? = null,          // FCtrl bit 5
    val fPending: Boolean? = null,     // FCtrl bit 4 (downlink) / ClassB (uplink) - same bit, direction from mtype
    val fOptsLen: Int? = null,         // FCtrl bits 0-3
    val fCnt: Int? = null,             // 2B LE (low 16 bits of the real frame counter)
    val fPort: Int? = null,            // present only if FRMPayload is non-empty
    // FOpts MAC commands walked as far as unambiguous (recognized cid+direction lengths);
    // [fOptsUndecoded] holds whatever's left once an unrecognized cid is hit (Class B/C/RFU/
    // proprietary ranges, or fOptsLen==0). Both empty for non-Data frames.
    val fOpts: List<LoRaWanMacCommand> = emptyList(),
    val fOptsUndecoded: Bytes = Bytes.EMPTY,
    // JoinRequest only: sent in clear, so a joining device is identifiable before it has a DevAddr.
    val joinEui: String? = null,       // 8B LE, as 16 hex digits (AppEUI in LoRaWAN 1.0.x)
    val devEui: String? = null,        // 8B LE, as 16 hex digits
    val devNonce: Int? = null,         // 2B LE
    // FOpts+FPort+FRMPayload+MIC (Data), or body-after-MHDR (Join*/Proprietary). Its FOpts
    // prefix is plaintext (see [fOpts]); "encrypted" refers to FRMPayload, which needs the
    // AppSKey/NwkSKey this app doesn't have.
    val encryptedTail: Bytes,
) : LoraPayloadMessage() {
    override fun toString(): String =
        "LoRaWanFrame(mtype=$mtype devAddr=$devAddr fCnt=$fCnt tailLen=${encryptedTail.size})"
}

/**
 * OE5BPA-style "LoRa APRS" packet: a 3-byte magic header (0x3C 0xFF 0x01) followed by a
 * plain-text TNC2-format APRS line ("FROM>TO,PATH1,PATH2:info"), transmitted directly over
 * the LoRa PHY (no AX.25 HDLC framing). [info] is the same [AprsInfo] hierarchy AX.25/APRS
 * parses to, since it's the identical information-field format.
 */
@Serializable
@SerialName("LoraAprsPacket")
data class LoraAprsPacket(
    val fromCall: String,
    val toCall: String?,
    val path: List<String>,
    val info: AprsInfo,
) : LoraPayloadMessage() {
    override fun toString(): String =
        "LoraAprsPacket(from=$fromCall to=${toCall ?: "?"} via=${path.joinToString(",")} info=$info)"
}

/* ----------------------------------------------------------------------
 * MeshCom (ICSSW): the amateur-radio mesh on 433.175 MHz (SF11, BW 250 kHz,
 * CR 4/6, sync word 0x2B like Meshtastic). Plain text frames with callsign
 * paths; MeshComParser describes the format.
 * -------------------------------------------------------------------- */

/** The frame type, byte 0: ':' text, '!' position, '@' HEY beacon or neighbour report. */
enum class MeshComFrameType { TEXT, POSITION, HEY }

/**
 * A MeshCom frame. Relays keep [msgId] (the key to tell repeats apart), count [hopsLeft] down and
 * append their callsign to [sourcePath]; [hardware], [firmware] and [firmwareSubVersion] describe
 * the originator, [lastHardware] the station this copy was heard from.
 */
@Serializable
@SerialName("MeshComFrame")
data class MeshComFrame(
    val frameType: MeshComFrameType,
    /** Little-endian uint32: 22 bits node id, 10 bits message counter (0..999). */
    val msgId: Long,
    val hopsLeft: Int,
    /** Byte 5's flag bits ([MESHCOM_FLAG_SERVER] …). */
    val flags: Int,
    /** The originator first, then the relays in order: the last element is the station this copy came from. */
    val sourcePath: List<String>,
    /** An optional via list, the destination last: `*` (everyone), a group number, a callsign, `H`/`HG`/`HN`. */
    val destPath: List<String>,
    val payload: String,
    /** The originator's board ([meshComHardwareName]); 0 = the MeshCom server. */
    val hardware: Int,
    /** The transmitter's region profile (high nibble of the `mod` byte). */
    val country: Int,
    /** The transmitter's modulation id (low nibble of the `mod` byte; 8 = SF11 CR4/6 BW250). */
    val modulation: Int,
    /** The 16-bit sum over the frame up to the `mod` byte matched. */
    val checksumValid: Boolean,
    /** The originator's firmware minor version (40 = 4.40); 0 from the server. */
    val firmware: Int? = null,
    /** The board of the station this copy was heard from. */
    val lastHardware: Int? = null,
    val firmwareSubVersion: String? = null,
    val info: MeshComInfo,
) : LoraPayloadMessage() {
    val origin: String get() = sourcePath.first()
    val lastTransmitter: String get() = sourcePath.last()
    val destination: String get() = destPath.last()
    /** Relays between the originator and this copy. */
    val hops: Int get() = sourcePath.size - 1

    override fun toString(): String =
        "MeshComFrame($frameType ${sourcePath.joinToString(",")}>${destPath.joinToString(",")} id=${"%08X".format(msgId)} info=$info)"
}

const val MESHCOM_FLAG_SERVER = 0x80     // came from or through a MeshCom server (an internet gateway)
const val MESHCOM_FLAG_TRACK = 0x40      // a position sent in track (smart beacon) mode
const val MESHCOM_FLAG_APP_OFFLINE = 0x20
const val MESHCOM_FLAG_MESH = 0x10       // the transmitter relays (mesh enabled)

/** A gateway's acknowledgement of a text (12 bytes, no callsigns): it reached the MeshCom server. */
@Serializable
@SerialName("MeshComAck")
data class MeshComAck(
    val ackId: Long,
    val hopsLeft: Int,
    /** The message id of the acknowledged text. */
    val ackedMsgId: Long,
) : LoraPayloadMessage()

@Serializable
sealed class MeshComInfo

/** What a text is beyond chat, from its destination and its payload's conventions. */
enum class MeshComTextKind {
    CHAT,
    /** `CALL :ackNNN` - the recipient of a direct message confirms it. */
    ACK,
    /** `{CET}…` - a MeshCom server's time beacon (the servers' clocks differ despite the tag: shown as sent). */
    TIME,
    PING, PONG,
    /** APRS telemetry to `100001`. */
    TELEMETRY,
    /** Server or remote-management commands: `{SET}`, `{mcp}`, `RM1`. */
    COMMAND,
}

/** A text frame: [text] without a direct message's `{NNN` acknowledgement request, which is [ackNumber]. */
@Serializable
@SerialName("MeshComText")
data class MeshComText(
    val text: String,
    val kind: MeshComTextKind = MeshComTextKind.CHAT,
    /** The number a direct message asks to be acknowledged with, or an ACK confirms. */
    val ackNumber: Int? = null,
) : MeshComInfo()

/** A position beacon: APRS-style position and symbol, then MeshCom's `/X=` tags. */
@Serializable
@SerialName("MeshComPosition")
data class MeshComPosition(
    val latitude: Double,
    val longitude: Double,
    val symbolTable: Char,
    val symbolCode: Char,
    val comment: String? = null,
    /** The node's name (after `#`). */
    val name: String? = null,
    val batteryPercent: Int? = null,
    val altitudeFt: Int? = null,
    /** Directly heard neighbours (`/N`). */
    val neighbours: Int? = null,
    val pressureHpa: Float? = null,
    val humidityPercent: Float? = null,
    val temperatureC: Float? = null,
    val temperature2C: Float? = null,
    val qnhHpa: Float? = null,
    val gasKohm: Float? = null,
    val co2Ppm: Int? = null,
    /** The groups the node is in (`/R=`). */
    val groups: List<Int> = emptyList(),
    val voltageV: Float? = null,
    val currentMa: Float? = null,
) : MeshComInfo()

/** A HEY beacon (to `H`, or `HG` from a gateway): the originator's neighbour count, and one signal
 *  report per relay - how well it heard the station before it. */
@Serializable
@SerialName("MeshComHey")
data class MeshComHey(
    val neighbours: Int? = null,
    val reports: List<MeshComSignalReport> = emptyList(),
) : MeshComInfo()

@Serializable
data class MeshComSignalReport(val neighbours: Int? = null, val rssiDbm: Int, val snrDb: Int)

/** A neighbour report (to `HN`, never relayed): the stations a node hears directly, best first. */
@Serializable
@SerialName("MeshComNeighbourReport")
data class MeshComNeighbourReport(
    val heard: Int? = null,
    val neighbours: List<MeshComNeighbour> = emptyList(),
    /** It hears more than it lists. */
    val more: Boolean = false,
) : MeshComInfo()

@Serializable
data class MeshComNeighbour(val callsign: String, val snrDb: Float? = null)
