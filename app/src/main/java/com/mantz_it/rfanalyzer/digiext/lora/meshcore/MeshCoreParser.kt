package com.mantz_it.rfanalyzer.digiext.lora.meshcore

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreAck
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreAdvert
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreAnonRequest
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreDiscoverRequest
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreDiscoverResponse
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreEncryptedEnvelope
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreGroupData
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreGroupText
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreInfo
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreOpaquePayload
import com.mantz_it.rfanalyzer.digiext.lora.MeshCorePacket
import com.mantz_it.rfanalyzer.digiext.lora.MeshCorePayloadType
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreRouteType
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreTrace

/**
 * <h1>RF Analyzer - MeshCore Parser</h1>
 *
 * Module:      MeshCoreParser.kt
 * Description: Best-effort MeshCore protocol decode of a raw LoRa PHY payload
 *              (https://github.com/meshcore-dev/MeshCore/blob/main/docs/packet_format.md,
 *              https://docs.meshcore.io/payloads/). LoRa has no PID-style sentinel field like
 *              AX.25, so "is this MeshCore" is a structural-validity gate: parse the header
 *              assuming MeshCore, and only accept it if all declared lengths are internally
 *              consistent with the actual payload size. Returns null (falls back to RawLoraPayload)
 *              whenever that consistency check fails. TRACE and CONTROL layouts as in the
 *              firmware's Mesh.cpp (MeshCore is MIT licensed).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object MeshCoreParser {
    private const val ADVERT_FIXED_LEN = 32 + 4 + 64 // pubkey + timestamp + signature
    private const val CONTROL_DISCOVER_REQ = 0x8
    private const val CONTROL_DISCOVER_RESP = 0x9

    private val PAYLOAD_TYPES = arrayOf(
        MeshCorePayloadType.REQ, MeshCorePayloadType.RESPONSE, MeshCorePayloadType.TXT_MSG, MeshCorePayloadType.ACK,
        MeshCorePayloadType.ADVERT, MeshCorePayloadType.GRP_TXT, MeshCorePayloadType.GRP_DATA, MeshCorePayloadType.ANON_REQ,
        MeshCorePayloadType.PATH, MeshCorePayloadType.TRACE, MeshCorePayloadType.MULTIPART, MeshCorePayloadType.CONTROL,
        MeshCorePayloadType.RESERVED_C, MeshCorePayloadType.RESERVED_D, MeshCorePayloadType.RESERVED_E, MeshCorePayloadType.RAW_CUSTOM,
    )

    fun tryParse(payload: ByteArray): MeshCorePacket? {
        if (payload.size < 2) return null

        val header = payload[0].toInt() and 0xFF
        val routeTypeCode = header and 0x03
        val payloadTypeCode = (header ushr 2) and 0x0F
        val payloadVersion = (header ushr 6) and 0x03
        if (payloadTypeCode in 0xC..0xE) return null // reserved/unassigned — don't claim MeshCore

        val routeType = MeshCoreRouteType.entries[routeTypeCode]
        val payloadType = PAYLOAD_TYPES[payloadTypeCode]

        var offset = 1
        val needsTransportCodes = routeType == MeshCoreRouteType.TRANSPORT_FLOOD ||
                routeType == MeshCoreRouteType.TRANSPORT_DIRECT
        val transportCodes: Bytes? = if (needsTransportCodes) {
            if (offset + 4 > payload.size) return null
            Bytes(payload.copyOfRange(offset, offset + 4)).also { offset += 4 }
        } else null

        if (offset >= payload.size) return null
        val pathLenByte = payload[offset].toInt() and 0xFF
        offset += 1
        val hopCount = pathLenByte and 0x3F
        val hashSizeCode = (pathLenByte ushr 6) and 0x03
        if (hashSizeCode == 0x03) return null // reserved hash-size code
        val hashSize = hashSizeCode + 1
        val pathBytesLen = hopCount * hashSize
        if (offset + pathBytesLen > payload.size) return null
        val path = (0 until hopCount).map { i ->
            Bytes(payload.copyOfRange(offset + i * hashSize, offset + (i + 1) * hashSize))
        }
        offset += pathBytesLen

        val body = payload.copyOfRange(offset, payload.size)
        val info: MeshCoreInfo = when (payloadType) {
            MeshCorePayloadType.ADVERT -> parseAdvert(body) ?: return null
            MeshCorePayloadType.REQ, MeshCorePayloadType.RESPONSE, MeshCorePayloadType.TXT_MSG, MeshCorePayloadType.PATH ->
                parseHashMacEnvelope(body, twoHashes = true) ?: MeshCoreOpaquePayload(Bytes(body))
            MeshCorePayloadType.GRP_TXT -> {
                val envelope = parseHashMacEnvelope(body, twoHashes = false) ?: MeshCoreOpaquePayload(Bytes(body))
                (envelope as? MeshCoreEncryptedEnvelope)?.let { MeshCoreChannelCrypto.tryDecryptGroupText(it) } ?: envelope
            }
            MeshCorePayloadType.GRP_DATA -> {
                val envelope = parseHashMacEnvelope(body, twoHashes = false) ?: MeshCoreOpaquePayload(Bytes(body))
                (envelope as? MeshCoreEncryptedEnvelope)?.let { MeshCoreChannelCrypto.tryDecryptGroupData(it) } ?: envelope
            }
            MeshCorePayloadType.ACK -> if (body.size == 4) MeshCoreAck(Bytes(body)) else MeshCoreOpaquePayload(Bytes(body))
            MeshCorePayloadType.ANON_REQ -> parseAnonRequest(body) ?: MeshCoreOpaquePayload(Bytes(body))
            MeshCorePayloadType.TRACE -> parseTrace(body, path) ?: MeshCoreOpaquePayload(Bytes(body))
            MeshCorePayloadType.CONTROL -> parseControl(body) ?: MeshCoreOpaquePayload(Bytes(body))
            else -> MeshCoreOpaquePayload(Bytes(body))
        }

        return MeshCorePacket(routeType, payloadType, payloadVersion, transportCodes, hashSize, path, info)
    }

    private fun parseAdvert(body: ByteArray): MeshCoreAdvert? {
        if (body.size < ADVERT_FIXED_LEN) return null
        val publicKey = Bytes(body.copyOfRange(0, 32))
        val timestamp = readUInt32LE(body, 32)
        val signature = Bytes(body.copyOfRange(36, 100))
        val appdata = body.copyOfRange(100, body.size)

        // signed: public key, timestamp and appdata
        val signatureValid = Ed25519.verify(body.copyOfRange(0, 32), body.copyOfRange(0, 36) + appdata, body.copyOfRange(36, 100))

        if (appdata.isEmpty()) {
            return MeshCoreAdvert(
                publicKey = publicKey, timestamp = timestamp, signature = signature, flags = 0,
                latitude = null, longitude = null, feature1 = null, feature2 = null, name = null,
                signatureValid = signatureValid,
            )
        }

        val flags = appdata[0].toInt() and 0xFF
        var idx = 1
        var lat: Double? = null
        var lon: Double? = null
        if (flags and 0x10 != 0) {
            if (idx + 8 > appdata.size) return null
            lat = readInt32LE(appdata, idx) / 1_000_000.0; idx += 4
            lon = readInt32LE(appdata, idx) / 1_000_000.0; idx += 4
        }
        val feature1: Bytes? = if (flags and 0x20 != 0) {
            if (idx + 2 > appdata.size) return null
            Bytes(appdata.copyOfRange(idx, idx + 2)).also { idx += 2 }
        } else null
        val feature2: Bytes? = if (flags and 0x40 != 0) {
            if (idx + 2 > appdata.size) return null
            Bytes(appdata.copyOfRange(idx, idx + 2)).also { idx += 2 }
        } else null
        val name: String? = if (flags and 0x80 != 0) {
            String(appdata, idx, appdata.size - idx, Charsets.UTF_8).trimEnd(' ')
        } else null

        return MeshCoreAdvert(
            publicKey = publicKey, timestamp = timestamp, signature = signature, flags = flags,
            latitude = lat, longitude = lon, feature1 = feature1, feature2 = feature2, name = name,
            signatureValid = signatureValid,
        )
    }

    /** ANON_REQ: dest_hash(1) | sender public key(32) | MAC(2) | ciphertext. */
    private fun parseAnonRequest(body: ByteArray): MeshCoreAnonRequest? {
        if (body.size < 35) return null
        return MeshCoreAnonRequest(
            destHash = Bytes(body.copyOfRange(0, 1)), senderPublicKey = Bytes(body.copyOfRange(1, 33)),
            mac = Bytes(body.copyOfRange(33, 35)), ciphertext = Bytes(body.copyOfRange(35, body.size)),
        )
    }

    /** TRACE: tag(4) | auth code(4) | flags(1, low 2 bits: hash size 1 << n) | the hashes of the
     *  nodes to pass. The packet's path field holds the SNR × 4 (int8) of each hop so far. */
    private fun parseTrace(body: ByteArray, path: List<Bytes>): MeshCoreTrace? {
        if (body.size < 9) return null
        val flags = body[8].toInt() and 0xFF
        val hashSize = 1 shl (flags and 0x03)
        val hashes = body.size - 9
        if (hashes % hashSize != 0) return null
        return MeshCoreTrace(
            tag = readUInt32LE(body, 0), authCode = readUInt32LE(body, 4), flags = flags,
            pathHashes = (0 until hashes / hashSize).map { Bytes(body.copyOfRange(9 + it * hashSize, 9 + (it + 1) * hashSize)) },
            snrs = path.flatMap { it.toByteArray().toList() }.map { it / 4f },
        )
    }

    /** CONTROL: flags(1, upper nibble the sub-type) | data. Only the zero-hop discovery sub-types
     *  are documented. */
    private fun parseControl(body: ByteArray): MeshCoreInfo? {
        if (body.isEmpty()) return null
        val flags = body[0].toInt() and 0xFF
        return when (flags ushr 4) {
            // flags | type_filter(1) | tag(4) | since(4, optional)
            CONTROL_DISCOVER_REQ -> if (body.size < 6) null else MeshCoreDiscoverRequest(
                prefixOnly = flags and 0x01 != 0, typeFilter = body[1].toInt() and 0xFF, tag = readUInt32LE(body, 2),
                since = if (body.size >= 10) readUInt32LE(body, 6) else null,
            )
            // flags (low nibble: node type) | snr × 4 (int8) | tag(4) | public key or its first 8 bytes
            CONTROL_DISCOVER_RESP -> if (body.size != 14 && body.size != 38) null else MeshCoreDiscoverResponse(
                nodeType = flags and 0x0F, snr = body[1] / 4f, tag = readUInt32LE(body, 2),
                publicKey = Bytes(body.copyOfRange(6, body.size)),
            )
            else -> null
        }
    }

    private fun parseHashMacEnvelope(body: ByteArray, twoHashes: Boolean): MeshCoreEncryptedEnvelope? {
        val headerLen = if (twoHashes) 4 else 3
        if (body.size < headerLen) return null
        return if (twoHashes) {
            MeshCoreEncryptedEnvelope(
                destHash = Bytes(body.copyOfRange(0, 1)), srcHash = Bytes(body.copyOfRange(1, 2)), channelHash = null,
                mac = Bytes(body.copyOfRange(2, 4)), ciphertext = Bytes(body.copyOfRange(4, body.size)),
            )
        } else {
            MeshCoreEncryptedEnvelope(
                destHash = null, srcHash = null, channelHash = Bytes(body.copyOfRange(0, 1)),
                mac = Bytes(body.copyOfRange(1, 3)), ciphertext = Bytes(body.copyOfRange(3, body.size)),
            )
        }
    }

    private fun readUInt32LE(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun readInt32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)
}

/**
 * Decodes MeshCore group-channel envelopes (GRP_TXT / GRP_DATA) against every channel key the
 * app holds, via [MeshCoreChannelRegistry] — the Public channel, the built-in/user hashtag
 * channels, and the opportunistic candidate dictionary. The crypto itself (channel key
 * derivation, encrypt-then-MAC) lives in the registry.
 *
 * Plaintext layouts (as in github.com/meshcore-dev/MeshCore,
 * BaseChatMesh::sendGroupMessage / Mesh::createGroupDatagram):
 *  - GRP_TXT : uint32 timestamp (LE) + 1 flags/txt_type byte + "sender: text" (NUL/zero padded).
 *  - GRP_DATA: uint16 data_type (LE) + 1 data_len byte + data_len payload bytes (zero padded).
 *
 * A dictionary (speculative) match is additionally gated on the plaintext looking structurally
 * sane ([looksLikeGroupText]), since a 2-byte MAC can collide across the many dictionary
 * candidates sharing a channel_hash byte. Default/user-table matches are trusted outright
 * (a MAC hit there is effectively unambiguous).
 */
object MeshCoreChannelCrypto {
    /** The Public channel's `channel_hash` (for tests). */
    val publicChannelHash: Byte get() = MeshCoreChannelRegistry.publicChannelHash

    /** Decrypts a GRP_TXT envelope against any held channel, or null to keep it opaque. */
    fun tryDecryptGroupText(envelope: MeshCoreEncryptedEnvelope): MeshCoreGroupText? {
        val channelHash = envelope.channelHash ?: return null
        if (channelHash.size != 1 || envelope.mac.size < 2) return null
        val match = MeshCoreChannelRegistry.tryDecrypt(channelHash[0], envelope.mac.toByteArray(), envelope.ciphertext.toByteArray()) ?: return null
        val plain = match.plaintext
        if (plain.size < 6) return null // 4-byte timestamp + 1 flags byte + at least 1 text byte
        if (match.fromDictionary && !looksLikeGroupText(plain)) return null

        val timestamp = readUInt32LE(plain, 0)
        // byte[4] is the flags byte (attempt count / TXT_TYPE); not surfaced.
        var end = 5
        while (end < plain.size && plain[end].toInt() != 0) end++
        val message = String(plain, 5, end - 5, Charsets.UTF_8)
        val sepIdx = message.indexOf(": ")
        val senderName = if (sepIdx >= 0) message.substring(0, sepIdx) else ""
        val text = if (sepIdx >= 0) message.substring(sepIdx + 2) else message

        return MeshCoreGroupText(
            channelName = match.channel.name,
            timestamp = timestamp,
            senderName = senderName,
            text = text,
        )
    }

    /** Decrypts a GRP_DATA envelope against any held channel, or null to keep it opaque.
     *  The payload is surfaced raw (type + bytes); its per-data_type structure is not decoded. */
    fun tryDecryptGroupData(envelope: MeshCoreEncryptedEnvelope): MeshCoreGroupData? {
        val channelHash = envelope.channelHash ?: return null
        if (channelHash.size != 1 || envelope.mac.size < 2) return null
        val match = MeshCoreChannelRegistry.tryDecrypt(channelHash[0], envelope.mac.toByteArray(), envelope.ciphertext.toByteArray()) ?: return null
        val plain = match.plaintext
        if (plain.size < 3) return null // 2-byte data_type + 1 data_len byte

        val dataType = (plain[0].toInt() and 0xFF) or ((plain[1].toInt() and 0xFF) shl 8)
        val dataLen = plain[2].toInt() and 0xFF
        if (3 + dataLen > plain.size) return null // declared length overruns the decrypted block
        // A dictionary match with no human-readable field to sanity-check is too collision-prone
        // to trust speculatively; only accept GRP_DATA from the trusted default/user table.
        if (match.fromDictionary) return null
        val data = Bytes(plain.copyOfRange(3, 3 + dataLen))

        return MeshCoreGroupData(channelName = match.channel.name, dataType = dataType, data = data)
    }

    /** True if a decrypted GRP_TXT block plausibly holds a real message: a "sender: text" string
     *  without control bytes and zero padding after it, not AES garbage from a spurious MAC collision. */
    private fun looksLikeGroupText(plain: ByteArray): Boolean {
        var end = 5
        while (end < plain.size && plain[end].toInt() != 0) end++
        if (end <= 5) return false
        for (i in 5 until end) {
            val c = plain[i].toInt() and 0xFF
            if (c < 0x09 || (c in 0x0E..0x1F) || c == 0x7F) return false // reject control bytes
        }
        return (0 until (plain.size - end)).all { plain[end + it].toInt() == 0 } // tail must be zero padding
    }

    private fun readUInt32LE(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)
}
