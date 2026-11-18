package com.mantz_it.rfanalyzer.digiext.lora.meshcore

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer.digiext.lora.LoraPayloadDecoder
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreAck
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreAdvert
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreAnonRequest
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreDiscoverRequest
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreDiscoverResponse
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreEncryptedEnvelope
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreGroupData
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreGroupText
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreOpaquePayload
import com.mantz_it.rfanalyzer.digiext.lora.MeshCorePacket
import com.mantz_it.rfanalyzer.digiext.lora.MeshCorePayloadType
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreRouteType
import com.mantz_it.rfanalyzer.digiext.lora.MeshCoreTrace
import com.mantz_it.rfanalyzer.digiext.lora.RawLoraPayload
import java.util.Base64
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - MeshCore Tests</h1>
 *
 * Module:      TestMeshCore.kt
 * Description: Pure-JVM unit tests for MeshCoreParser / LoraPayloadDecoder — no JNI/device needed,
 *              this is plain byte-array parsing. The real ADVERT fixture bytes are transcribed from
 *              the gr-lora-sdr hexdump of a real capture — see realAdvertPayload's doc comment for
 *              the capture settings and reference-decoder output.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestMeshCore {

    // ---- Real ADVERT fixture (lora_001) ----
    // Captured via AirSpy HF+, 869.618 MHz, 250 kSps, bw=62500 cr=1 sf=8 (sent from the
    // MeshCore app as a "send advert"). Ground truth from gr-lora-sdr's reference flow
    // graph: "Payload length: 111, CRC presence: 1, Coding rate: 1, Header checksum
    // valid!" — hex bytes below hand-transcribed from that decode's hexdump output.
    private val realAdvertPayload = byteArrayOf(
        0x12, 0x00, 0xe7.toByte(), 0xe8.toByte(), 0x22, 0xdc.toByte(), 0x88.toByte(), 0x5a,
        0x37, 0x3c, 0xee.toByte(), 0xa9.toByte(), 0xf3.toByte(), 0xeb.toByte(), 0x4e, 0xd8.toByte(),
        0xa2.toByte(), 0x8a.toByte(), 0x45, 0x63, 0x96.toByte(), 0x77, 0x54, 0x2e,
        0xf4.toByte(), 0x9a.toByte(), 0x26, 0xb8.toByte(), 0x74, 0x27, 0x02, 0xfa.toByte(),
        0x2d, 0xc2.toByte(), 0x3d, 0xee.toByte(), 0x1d, 0x6a, 0xd8.toByte(), 0xc8.toByte(),
        0x47, 0x04, 0x8c.toByte(), 0x08, 0x88.toByte(), 0x2a, 0x8b.toByte(), 0xf9.toByte(),
        0x51, 0x74, 0xd5.toByte(), 0x6d, 0x7e, 0x75, 0xa5.toByte(), 0xf3.toByte(),
        0xe0.toByte(), 0x45, 0x8a.toByte(), 0xc3.toByte(), 0xe5.toByte(), 0xd5.toByte(), 0xbd.toByte(), 0x2c,
        0xc2.toByte(), 0x54, 0xa3.toByte(), 0x42, 0x6d, 0x13, 0xdf.toByte(), 0xde.toByte(),
        0xcf.toByte(), 0x92.toByte(), 0xaf.toByte(), 0x0a, 0xc2.toByte(), 0x29, 0xe2.toByte(), 0x7c,
        0xbd.toByte(), 0xb0.toByte(), 0xe1.toByte(), 0x23, 0x13, 0xb8.toByte(), 0xd4.toByte(), 0x19,
        0x45, 0x21, 0x8c.toByte(), 0xfe.toByte(), 0xc6.toByte(), 0x2c, 0x41, 0xfe.toByte(),
        0xfa.toByte(), 0xdd.toByte(), 0x6f, 0x64, 0x8c.toByte(), 0x0e, 0x81.toByte(), 0x45,
        0x37, 0x45, 0x38, 0x32, 0x32, 0x44, 0x43,
    )

    private val realAdvertPublicKeyHex =
        "E7E822DC885A373CEEA9F3EB4ED8A28A45639677542EF49A26B8742702FA2DC2"

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    @Test
    fun testRealAdvertFixture() {
        assertEquals(111, realAdvertPayload.size)

        val packet = MeshCoreParser.tryParse(realAdvertPayload)
        assertTrue("Expected a MeshCorePacket", packet != null)
        packet!!

        assertEquals(MeshCoreRouteType.DIRECT, packet.routeType)
        assertEquals(MeshCorePayloadType.ADVERT, packet.payloadType)
        assertEquals(0, packet.payloadVersion)
        assertTrue(packet.path.isEmpty())
        assertNull(packet.transportCodes)

        assertTrue("Expected MeshCoreAdvert info", packet.info is MeshCoreAdvert)
        val advert = packet.info as MeshCoreAdvert
        assertEquals(32, advert.publicKey.size)
        assertEquals(realAdvertPublicKeyHex, advert.publicKey.toHex())
        assertEquals(1780346429L, advert.timestamp)
        assertEquals(64, advert.signature.size)
        assertEquals(0x81, advert.flags)
        assertTrue(advert.isChatNode)
        assertTrue(!advert.isRepeater)
        assertTrue(!advert.isRoomServer)
        assertTrue(!advert.isSensor)
        assertTrue(!advert.hasLocation)
        assertNull(advert.latitude)
        assertNull(advert.longitude)
        assertEquals("E7E822DC", advert.name)
        assertTrue("the real advert's Ed25519 signature verifies", advert.signatureValid)

        // Also confirm the full LoraPayloadDecoder dispatch reaches the same result.
        assertTrue(LoraPayloadDecoder.decode(realAdvertPayload) is MeshCorePacket)
    }

    /** The signature covers public key, timestamp and appdata: any changed byte breaks it. */
    @Test
    fun testAdvertSignatureCoversTimestampAndAppdata() {
        for (index in intArrayOf(2, 2 + 33, 2 + 100 + 1, realAdvertPayload.size - 1, 2 + 40)) {
            val changed = realAdvertPayload.copyOf().also { it[index] = (it[index].toInt() xor 0x01).toByte() }
            val advert = MeshCoreParser.tryParse(changed)!!.info as MeshCoreAdvert
            assertTrue("byte $index changed", !advert.signatureValid)
        }
    }

    @Test
    fun testSyntheticAdvertWithLocation() {
        val pubkey = ByteArray(32) { it.toByte() }
        val timestampBytes = byteArrayOf(0x78, 0x56, 0x34, 0x12) // LE -> 0x12345678
        val signature = ByteArray(64) { 0xAB.toByte() }
        val flags = byteArrayOf((0x10 or 0x80).toByte()) // location + name
        // 52.5200 deg * 1_000_000 = 52520000 = 0x03216440 -> LE bytes
        val latBytes = intToLeBytes(52_520_000)
        // 13.4050 deg * 1_000_000 = 13405000 -> LE bytes
        val lonBytes = intToLeBytes(13_405_000)
        val name = "Berlin".toByteArray(Charsets.UTF_8)

        val header = byteArrayOf(0x12) // route=DIRECT, type=ADVERT, version=0
        val pathLen = byteArrayOf(0x00)
        val payload = header + pathLen + pubkey + timestampBytes + signature + flags + latBytes + lonBytes + name

        val packet = MeshCoreParser.tryParse(payload)
        assertTrue(packet != null)
        val advert = packet!!.info as MeshCoreAdvert
        assertTrue(advert.hasLocation)
        assertEquals(52.52, advert.latitude!!, 0.0001)
        assertEquals(13.405, advert.longitude!!, 0.0001)
        assertEquals("Berlin", advert.name)
        assertTrue("a made-up signature does not verify", !advert.signatureValid)
    }

    @Test
    fun testGrpTxtEnvelopeOnly() {
        val header = byteArrayOf(((MeshCorePayloadType.GRP_TXT.ordinal shl 2) or MeshCoreRouteType.FLOOD.ordinal).toByte())
        val pathLen = byteArrayOf(0x00)
        val channelHash = byteArrayOf(0x9C.toByte())
        val mac = byteArrayOf(0x1F, 0x2A)
        val ciphertext = ByteArray(20) { (it + 1).toByte() }
        val payload = header + pathLen + channelHash + mac + ciphertext

        val packet = MeshCoreParser.tryParse(payload)
        assertTrue(packet != null)
        assertEquals(MeshCorePayloadType.GRP_TXT, packet!!.payloadType)
        assertTrue(packet.info is MeshCoreEncryptedEnvelope)
        val env = packet.info as MeshCoreEncryptedEnvelope
        assertNull(env.destHash)
        assertNull(env.srcHash)
        assertEquals(Bytes(channelHash), env.channelHash)
        assertEquals(Bytes(mac), env.mac)
        assertEquals(Bytes(ciphertext), env.ciphertext)
    }

    @Test
    fun testTxtMsgEnvelopeOnly() {
        val header = byteArrayOf(((MeshCorePayloadType.TXT_MSG.ordinal shl 2) or MeshCoreRouteType.FLOOD.ordinal).toByte())
        val pathLen = byteArrayOf(0x00)
        val destHash = byteArrayOf(0x11)
        val srcHash = byteArrayOf(0x22)
        val mac = byteArrayOf(0x33, 0x44)
        val ciphertext = ByteArray(10) { it.toByte() }
        val payload = header + pathLen + destHash + srcHash + mac + ciphertext

        val packet = MeshCoreParser.tryParse(payload)
        assertTrue(packet != null)
        val env = packet!!.info as MeshCoreEncryptedEnvelope
        assertEquals(Bytes(destHash), env.destHash)
        assertEquals(Bytes(srcHash), env.srcHash)
        assertNull(env.channelHash)
    }

    // ---- Real Public-channel GRP_TXT fixture (meshcore_public_channel_message_869618kHz_500kSps.cf32) ----
    // These are the exact 101 payload bytes as decoded by the reference gr-lora-sdr
    // decoder (hard AND soft decoding both agree, CRC valid) cross-checked against
    // this recording. Kept here as a parser/crypto-layer-only fixture (no PHY/JNI
    // dependency, so this stays a plain JVM test).
    private val realGroupTextPayload = byteArrayOf(
        0x15, 0x00, 0x11, 0x34, 0x37, 0xfd.toByte(), 0x8d.toByte(), 0x00,
        0x1a, 0x71, 0x6b, 0x66, 0x18, 0xbb.toByte(), 0x0f, 0x59,
        0xcb.toByte(), 0xa0.toByte(), 0x1a, 0x75, 0xca.toByte(), 0x31, 0xc7.toByte(), 0x89.toByte(),
        0x57, 0x07, 0x0a, 0xb5.toByte(), 0xee.toByte(), 0x25, 0xcd.toByte(), 0xa1.toByte(),
        0xdf.toByte(), 0xcd.toByte(), 0xc7.toByte(), 0xad.toByte(), 0x62, 0x6e, 0x8a.toByte(), 0x44,
        0x80.toByte(), 0x77, 0x71, 0xc3.toByte(), 0x0e, 0xab.toByte(), 0x18, 0x7b,
        0x61, 0x84.toByte(), 0x4a, 0xf2.toByte(), 0xd2.toByte(), 0xc6.toByte(), 0xd6.toByte(), 0xeb.toByte(),
        0xd6.toByte(), 0x0e, 0x7b, 0xc0.toByte(), 0x5d, 0xe7.toByte(), 0xd3.toByte(), 0x80.toByte(),
        0x6f, 0x91.toByte(), 0x16, 0xe4.toByte(), 0x99.toByte(), 0x4c, 0x42, 0x66,
        0x3d, 0x8a.toByte(), 0x3c, 0x80.toByte(), 0x1e, 0xca.toByte(), 0x84.toByte(), 0x1a,
        0x65, 0x2a, 0x29, 0x9a.toByte(), 0xc6.toByte(), 0x39, 0x38, 0x4b,
        0x6c, 0x0f, 0xc8.toByte(), 0xc1.toByte(), 0xfe.toByte(), 0x3b, 0xdf.toByte(), 0xd3.toByte(),
        0x3d, 0x33, 0xca.toByte(), 0xbd.toByte(), 0x27,
    )

    @Test
    fun testRealPublicChannelGroupTextFixture() {
        assertEquals(101, realGroupTextPayload.size)

        val packet = MeshCoreParser.tryParse(realGroupTextPayload)
        assertTrue("Expected a MeshCorePacket", packet != null)
        packet!!

        assertEquals(MeshCoreRouteType.FLOOD, packet.routeType)
        assertEquals(MeshCorePayloadType.GRP_TXT, packet.payloadType)
        assertTrue(packet.path.isEmpty())

        assertTrue("Expected MeshCoreGroupText info but got ${packet.info}", packet.info is MeshCoreGroupText)
        val groupText = packet.info as MeshCoreGroupText
        assertEquals("Public", groupText.channelName)
        assertEquals(1784151875L, groupText.timestamp)
        assertEquals("E7E822DC", groupText.senderName)
        assertEquals("Hi, can anyone receive this message? I'm currently located Lübeck in Germany.", groupText.text)

        assertTrue(LoraPayloadDecoder.decode(realGroupTextPayload) is MeshCorePacket)
    }

    // ---- Public-channel GRP_TXT decode (AES-128-ECB + truncated HMAC-SHA256) ----

    private val publicPsk = Base64.getDecoder().decode("izOH6cXN6mrJ5e26oRXNcg==") // 16 bytes
    private val publicSecret32 = publicPsk.copyOf(32) // firmware zero-pads PSK to PUB_KEY_SIZE

    /** The 16-byte secret for a hashtag channel: SHA256("#"+name)[:16]. Mirrors the firmware /
     *  MeshCoreChannelRegistry derivation so tests can forge traffic for any hashtag channel. */
    private fun hashtagKey16(name: String): ByteArray {
        val tag = if (name.startsWith("#")) name else "#$name"
        return java.security.MessageDigest.getInstance("SHA-256").digest(tag.toByteArray(Charsets.UTF_8)).copyOf(16)
    }

    private fun channelHashByte(key16: ByteArray): Byte =
        java.security.MessageDigest.getInstance("SHA-256").digest(key16)[0]

    /** Builds a GRP_TXT/GRP_DATA envelope on an arbitrary channel key (encrypt-then-MAC,
     *  AES-128-ECB + truncated HMAC-SHA256), mirroring Mesh::createGroupDatagram. */
    private fun buildGrpPayload(
        payloadType: MeshCorePayloadType,
        key16: ByteArray,
        plain: ByteArray,
        channelHashByte: Byte = channelHashByte(key16),
        corruptMac: Boolean = false,
    ): ByteArray {
        val secret32 = key16.copyOf(32)
        val blockLen = ((plain.size + 15) / 16).coerceAtLeast(1) * 16
        val padded = plain.copyOf(blockLen) // zero-padded to block size, like Utils::encrypt

        val ciphertext = Cipher.getInstance("AES/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16, "AES"))
        }.doFinal(padded)

        val mac = Mac.getInstance("HmacSHA256").apply {
            init(SecretKeySpec(secret32, "HmacSHA256"))
        }.doFinal(ciphertext).let { if (corruptMac) it.also { m -> m[0] = (m[0] + 1).toByte() } else it }

        val header = byteArrayOf(((payloadType.ordinal shl 2) or MeshCoreRouteType.FLOOD.ordinal).toByte())
        val pathLen = byteArrayOf(0x00)
        return header + pathLen + byteArrayOf(channelHashByte) + mac.copyOf(2) + ciphertext
    }

    private fun grpTxtPlaintext(timestamp: Long, senderName: String, text: String): ByteArray {
        val message = "$senderName: $text"
        val plain = ByteArray(5 + message.toByteArray(Charsets.UTF_8).size + 1) // ts(4)+flags(1)+text+NUL
        plain[0] = (timestamp and 0xFF).toByte()
        plain[1] = ((timestamp ushr 8) and 0xFF).toByte()
        plain[2] = ((timestamp ushr 16) and 0xFF).toByte()
        plain[3] = ((timestamp ushr 24) and 0xFF).toByte()
        plain[4] = 0 // flags: TXT_TYPE_PLAIN
        message.toByteArray(Charsets.UTF_8).copyInto(plain, 5)
        return plain
    }

    /** Mirrors Mesh::createGroupDatagram / Utils::encryptThenMAC for the "Public" channel. */
    private fun buildGrpTxtPayload(
        timestamp: Long,
        senderName: String,
        text: String,
        channelHashByte: Byte = MeshCoreChannelCrypto.publicChannelHash,
        corruptMac: Boolean = false,
    ): ByteArray = buildGrpPayload(
        payloadType = MeshCorePayloadType.GRP_TXT,
        key16 = publicPsk,
        plain = grpTxtPlaintext(timestamp, senderName, text),
        channelHashByte = channelHashByte,
        corruptMac = corruptMac,
    )

    @Test
    fun testPublicChannelGroupTextDecodes() {
        val payload = buildGrpTxtPayload(timestamp = 1780346429L, senderName = "Alice", text = "Hi, can anyone receive this message?")

        val packet = MeshCoreParser.tryParse(payload)
        assertTrue("Expected a MeshCorePacket", packet != null)
        assertEquals(MeshCorePayloadType.GRP_TXT, packet!!.payloadType)
        assertTrue("Expected MeshCoreGroupText info but got ${packet.info}", packet.info is MeshCoreGroupText)
        val groupText = packet.info as MeshCoreGroupText
        assertEquals("Public", groupText.channelName)
        assertEquals(1780346429L, groupText.timestamp)
        assertEquals("Alice", groupText.senderName)
        assertEquals("Hi, can anyone receive this message?", groupText.text)
    }

    @Test
    fun testGrpTxtOnUnknownChannelStaysOpaque() {
        // Wrong channel hash byte -> not the Public channel -> must not attempt decrypt.
        val payload = buildGrpTxtPayload(
            timestamp = 1L, senderName = "Bob", text = "secret group",
            channelHashByte = (MeshCoreChannelCrypto.publicChannelHash + 1).toByte(),
        )
        val packet = MeshCoreParser.tryParse(payload)
        assertTrue(packet != null)
        assertTrue("Expected opaque envelope for unknown channel but got ${packet!!.info}",
            packet.info is MeshCoreEncryptedEnvelope)
    }

    @Test
    fun testGrpTxtWithBadMacStaysOpaque() {
        val payload = buildGrpTxtPayload(
            timestamp = 1L, senderName = "Bob", text = "tampered", corruptMac = true,
        )
        val packet = MeshCoreParser.tryParse(payload)
        assertTrue(packet != null)
        assertTrue("Expected opaque envelope when MAC fails but got ${packet!!.info}",
            packet.info is MeshCoreEncryptedEnvelope)
    }

    // ---- ACK (0x3) decode ----

    @Test
    fun testAckDecodesFourByteChecksum() {
        val header = byteArrayOf(((MeshCorePayloadType.ACK.ordinal shl 2) or MeshCoreRouteType.FLOOD.ordinal).toByte())
        val pathLen = byteArrayOf(0x00)
        val checksum = byteArrayOf(0x11, 0x22, 0x33, 0x44)
        val payload = header + pathLen + checksum

        val packet = MeshCoreParser.tryParse(payload)
        assertTrue(packet != null)
        assertEquals(MeshCorePayloadType.ACK, packet!!.payloadType)
        assertTrue("Expected MeshCoreAck but got ${packet.info}", packet.info is MeshCoreAck)
        assertEquals(Bytes(checksum), (packet.info as MeshCoreAck).checksum)
    }

    @Test
    fun testAckWrongSizeFallsBackToOpaque() {
        val header = byteArrayOf(((MeshCorePayloadType.ACK.ordinal shl 2) or MeshCoreRouteType.FLOOD.ordinal).toByte())
        val pathLen = byteArrayOf(0x00)
        val payload = header + pathLen + byteArrayOf(0x11, 0x22, 0x33) // only 3 bytes, not 4

        val packet = MeshCoreParser.tryParse(payload)
        assertTrue(packet != null)
        assertTrue("Expected MeshCoreOpaquePayload but got ${packet!!.info}", packet.info is MeshCoreOpaquePayload)
    }

    // ---- hash-addressed and plaintext payloads ----

    private fun packet(type: MeshCorePayloadType, path: ByteArray, body: ByteArray, route: MeshCoreRouteType = MeshCoreRouteType.FLOOD) =
        byteArrayOf(((type.ordinal shl 2) or route.ordinal).toByte(), path.size.toByte()) + path + body

    /** REQ, RESPONSE, TXT_MSG and PATH share one envelope: destination hash, source hash, MAC, ciphertext. */
    @Test
    fun testHashAddressedPayloadsDecodeTheirEnvelope() {
        for (type in listOf(MeshCorePayloadType.REQ, MeshCorePayloadType.RESPONSE, MeshCorePayloadType.TXT_MSG, MeshCorePayloadType.PATH)) {
            val parsed = MeshCoreParser.tryParse(packet(type, byteArrayOf(0x42), byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0x01, 0x02, 0x10, 0x20, 0x30)))!!
            val envelope = parsed.info as MeshCoreEncryptedEnvelope
            assertEquals("$type", Bytes(byteArrayOf(0xAA.toByte())), envelope.destHash)
            assertEquals("$type", Bytes(byteArrayOf(0xBB.toByte())), envelope.srcHash)
            assertEquals("$type", Bytes(byteArrayOf(0x01, 0x02)), envelope.mac)
            assertEquals("$type", 3, envelope.ciphertext.size)
        }
        // shorter than the envelope's fixed part
        val short = MeshCoreParser.tryParse(packet(MeshCorePayloadType.PATH, ByteArray(0), byteArrayOf(0x05, 0xAA.toByte())))!!
        assertTrue(short.info is MeshCoreOpaquePayload)
    }

    @Test
    fun testAnonymousRequestCarriesTheSendersPublicKey() {
        val key = ByteArray(32) { (it + 1).toByte() }
        val parsed = MeshCoreParser.tryParse(packet(MeshCorePayloadType.ANON_REQ, ByteArray(0), byteArrayOf(0x7F) + key + byteArrayOf(0x0A, 0x0B) + ByteArray(16)))!!
        val request = parsed.info as MeshCoreAnonRequest
        assertEquals(Bytes(byteArrayOf(0x7F)), request.destHash)
        assertEquals(Bytes(key), request.senderPublicKey)
        assertEquals(16, request.ciphertext.size)
        assertEquals("MeshCore:" + Bytes(key).toHex(), com.mantz_it.rfanalyzer.digiext.lora.loraIdentityOf(parsed))
    }

    /** A trace's path field holds the SNR × 4 each hop measured, its payload the hashes to pass. */
    @Test
    fun testTraceDecodesHopsAndSnrs() {
        val body = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x00, 0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte())
        val parsed = MeshCoreParser.tryParse(packet(MeshCorePayloadType.TRACE, byteArrayOf(40, -22), body, MeshCoreRouteType.DIRECT))!!
        val trace = parsed.info as MeshCoreTrace
        assertEquals(0x04030201L, trace.tag)
        assertEquals(0x08070605L, trace.authCode)
        assertEquals(listOf("a1", "b2", "c3"), trace.pathHashes.map { it.toHex().lowercase() })
        assertEquals(listOf(10f, -5.5f), trace.snrs)
        // flags 1: 2-byte hashes
        val twoByte = MeshCoreParser.tryParse(packet(MeshCorePayloadType.TRACE, ByteArray(0), body.copyOf(9).also { it[8] = 1 } + ByteArray(4), MeshCoreRouteType.DIRECT))!!
        assertEquals(2, (twoByte.info as MeshCoreTrace).pathHashes.size)
    }

    @Test
    fun testDiscoveryRequestAndResponse() {
        val request = MeshCoreParser.tryParse(packet(MeshCorePayloadType.CONTROL, ByteArray(0),
            byteArrayOf(0x81.toByte(), 0x04, 0x11, 0x22, 0x33, 0x44), MeshCoreRouteType.DIRECT))!!.info as MeshCoreDiscoverRequest
        assertTrue(request.prefixOnly)
        assertEquals(0x04, request.typeFilter)
        assertEquals(0x44332211L, request.tag)
        assertNull(request.since)

        val key = ByteArray(32) { 0x33 }
        val responsePacket = MeshCoreParser.tryParse(packet(MeshCorePayloadType.CONTROL, ByteArray(0),
            byteArrayOf(0x92.toByte(), (-26).toByte(), 0x11, 0x22, 0x33, 0x44) + key, MeshCoreRouteType.DIRECT))!!
        val response = responsePacket.info as MeshCoreDiscoverResponse
        assertEquals(2, response.nodeType)
        assertEquals(-6.5f, response.snr)
        assertEquals(Bytes(key), response.publicKey)
        assertEquals("MeshCore:" + Bytes(key).toHex(), com.mantz_it.rfanalyzer.digiext.lora.loraIdentityOf(responsePacket))
        // an unknown control sub-type stays opaque
        val other = MeshCoreParser.tryParse(packet(MeshCorePayloadType.CONTROL, ByteArray(0), byteArrayOf(0x30, 0x01), MeshCoreRouteType.DIRECT))!!
        assertTrue(other.info is MeshCoreOpaquePayload)
    }

    @Test
    fun testReservedPayloadTypeFallsBackToRaw() {
        for (reserved in intArrayOf(0xC, 0xD, 0xE)) {
            val header = byteArrayOf(((reserved shl 2) or MeshCoreRouteType.FLOOD.ordinal).toByte())
            val payload = header + byteArrayOf(0x00) + ByteArray(10)
            assertTrue("type=$reserved should fall back to raw",
                LoraPayloadDecoder.decode(payload) is RawLoraPayload)
        }
    }

    @Test
    fun testTruncatedAdvertFallsBackToRaw() {
        val header = byteArrayOf(0x12)
        val pathLen = byteArrayOf(0x00)
        val shortBody = ByteArray(50) // < 100 bytes required for ADVERT
        val payload = header + pathLen + shortBody
        assertTrue(LoraPayloadDecoder.decode(payload) is RawLoraPayload)
    }

    @Test
    fun testReservedHashSizeCodeFallsBackToRaw() {
        val header = byteArrayOf(0x12)
        val pathLen = byteArrayOf(0xC0.toByte()) // hash-size-code 0b11 = reserved
        val payload = header + pathLen + ByteArray(120)
        assertTrue(LoraPayloadDecoder.decode(payload) is RawLoraPayload)
    }

    @Test
    fun testRandomGarbageMostlyFallsBackToRaw() {
        val random = Random(0L)
        var rawCount = 0
        val total = 200
        repeat(total) {
            val payload = ByteArray(random.nextInt(30) + 2)
            random.nextBytes(payload)
            if (LoraPayloadDecoder.decode(payload) is RawLoraPayload) rawCount++
        }
        // Structural gate should reject most short random payloads (many payload types
        // only require header/path-length consistency, so some false-positive opaque
        // matches are expected and fine — this just guards against the gate being broken
        // outright, e.g. accepting everything).
        assertTrue("Expected most random payloads to fail the MeshCore gate, got $rawCount/$total raw",
            rawCount > total * 0.5)
    }

    // ---- Hashtag channels (SHA256("#"+name)[:16] key) ----

    @Test
    fun testRegistryKeyAndHashDerivationVectors() {
        // Known vectors (verified against a real capture): #test and #rhein-neckar.
        val test = MeshCoreChannel.fromHashtag("test")
        assertEquals("#test", test.name)
        assertEquals("9cd8fcf22a47333b591d96a2b848b73f", test.key16.toHex())
        assertEquals(0xD9.toByte(), test.hashByte)

        val rn = MeshCoreChannel.fromHashtag("#rhein-neckar") // leading '#' already present
        assertEquals("#rhein-neckar", rn.name)
        assertEquals(0x32.toByte(), rn.hashByte)

        // The Public channel's hash byte happens to be 0x11 (collides with other channels on the
        // air — hence the per-candidate MAC check, not a hash-only match).
        assertEquals(0x11.toByte(), MeshCoreChannelRegistry.publicChannelHash)
    }

    @Test
    fun testBuiltInHashtagChannelDecodes() {
        // #test and #rhein-neckar are preloaded defaults, so they decode with no user config.
        for (name in listOf("test", "rhein-neckar")) {
            val key = hashtagKey16(name)
            val payload = buildGrpPayload(MeshCorePayloadType.GRP_TXT, key,
                grpTxtPlaintext(1793016479L, "WS", "hello $name"))
            val packet = MeshCoreParser.tryParse(payload)
            assertTrue("Expected MeshCoreGroupText for #$name but got ${packet?.info}", packet?.info is MeshCoreGroupText)
            val gt = packet!!.info as MeshCoreGroupText
            assertEquals("#$name", gt.channelName)
            assertEquals("WS", gt.senderName)
            assertEquals("hello $name", gt.text)
        }
    }

    @Test
    fun testUserAddedHashtagChannelDecodesThenClears() {
        val name = "somepeculiarname12345"
        val key = hashtagKey16(name)
        val payload = buildGrpPayload(MeshCorePayloadType.GRP_TXT, key, grpTxtPlaintext(1L, "Carol", "hi"))

        // Not held yet -> opaque.
        assertTrue(MeshCoreParser.tryParse(payload)!!.info is MeshCoreEncryptedEnvelope)

        try {
            MeshCoreChannelRegistry.updateCustomChannels(listOf(name))
            val gt = MeshCoreParser.tryParse(payload)!!.info
            assertTrue("Expected decode after adding channel, got $gt", gt is MeshCoreGroupText)
            assertEquals("#$name", (gt as MeshCoreGroupText).channelName)
        } finally {
            MeshCoreChannelRegistry.resetCustomChannelsForTest()
        }
        // After clearing -> opaque again.
        assertTrue(MeshCoreParser.tryParse(payload)!!.info is MeshCoreEncryptedEnvelope)
    }

    // ---- GRP_DATA decode ----

    @Test
    fun testGrpDataDecodesOnKnownChannel() {
        // [data_type u16 LE][data_len 1][payload]
        val dataType = 0x0102
        val data = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val plain = byteArrayOf((dataType and 0xFF).toByte(), ((dataType ushr 8) and 0xFF).toByte(), data.size.toByte()) + data
        val payload = buildGrpPayload(MeshCorePayloadType.GRP_DATA, publicPsk, plain)

        val packet = MeshCoreParser.tryParse(payload)
        assertEquals(MeshCorePayloadType.GRP_DATA, packet!!.payloadType)
        assertTrue("Expected MeshCoreGroupData but got ${packet.info}", packet.info is MeshCoreGroupData)
        val gd = packet.info as MeshCoreGroupData
        assertEquals("Public", gd.channelName)
        assertEquals(dataType, gd.dataType)
        assertEquals(Bytes(data), gd.data)
    }

    // ---- Dictionary tier (speculative matches held to a stricter plaintext check) ----

    @Test
    fun testDictionaryChannelDecodesValidText() {
        val name = "dictonlychannel999"
        val key = hashtagKey16(name)
        val payload = buildGrpPayload(MeshCorePayloadType.GRP_TXT, key, grpTxtPlaintext(1L, "Dave", "from the dict"))
        try {
            MeshCoreChannelRegistry.setDictionaryForTest(listOf(name))
            val info = MeshCoreParser.tryParse(payload)!!.info
            assertTrue("Expected dictionary decode, got $info", info is MeshCoreGroupText)
            assertEquals("#$name", (info as MeshCoreGroupText).channelName)
        } finally {
            MeshCoreChannelRegistry.setDictionaryForTest(emptyList())
        }
    }

    @Test
    fun testDictionaryMatchWithGarbagePlaintextStaysOpaque() {
        // A valid MAC but non-text plaintext: the sort of thing a spurious dictionary MAC
        // collision would yield. A *dictionary* match must additionally pass the plaintext
        // sanity check, so this stays opaque even though the MAC verifies.
        val name = "dictgarbagechannel"
        val key = hashtagKey16(name)
        val garbage = ByteArray(16) { if (it < 5) 0 else 0x01 } // control bytes in the text region
        val payload = buildGrpPayload(MeshCorePayloadType.GRP_TXT, key, garbage)
        try {
            MeshCoreChannelRegistry.setDictionaryForTest(listOf(name))
            assertTrue("Dictionary match on garbage plaintext must stay opaque",
                MeshCoreParser.tryParse(payload)!!.info is MeshCoreEncryptedEnvelope)
        } finally {
            MeshCoreChannelRegistry.setDictionaryForTest(emptyList())
        }
    }

    @Test
    fun testGrpDataFromDictionaryStaysOpaque() {
        // GRP_DATA has no human-readable field to sanity-check, so speculative dictionary matches
        // are never trusted for it — only the default/user table.
        val name = "dictdatachannel"
        val key = hashtagKey16(name)
        val plain = byteArrayOf(0x01, 0x00, 0x02, 0x11, 0x22)
        val payload = buildGrpPayload(MeshCorePayloadType.GRP_DATA, key, plain)
        try {
            MeshCoreChannelRegistry.setDictionaryForTest(listOf(name))
            assertTrue("GRP_DATA dictionary match must stay opaque",
                MeshCoreParser.tryParse(payload)!!.info is MeshCoreEncryptedEnvelope)
        } finally {
            MeshCoreChannelRegistry.setDictionaryForTest(emptyList())
        }
    }

    // ---- Clipboard paste parsing (MeshCoreChannelsDialog) ----

    @Test
    fun testParsePastedChannelNames() {
        // Mixed separators (newline / comma / semicolon), with and without '#', mixed case, blanks.
        val parsed = parsePastedChannelNames("#Test, rhein-neckar\n#ECHO ;; \n  , #okimesh")
        assertEquals(listOf("test", "rhein-neckar", "echo", "okimesh"), parsed)
    }

    @Test
    fun testParsePastedChannelNamesDropsOverLength() {
        val tooLong = "a".repeat(MeshCoreChannelRegistry.MAX_CHANNEL_NAME_BYTES) // '#' + this = 33 bytes > 32
        val ok = "b".repeat(MeshCoreChannelRegistry.MAX_CHANNEL_NAME_BYTES - 1) // '#' + this = 32 bytes, kept
        assertEquals(listOf(ok), parsePastedChannelNames("$tooLong\n$ok"))
    }

    private fun intToLeBytes(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value ushr 8) and 0xFF).toByte(),
        ((value ushr 16) and 0xFF).toByte(),
        ((value ushr 24) and 0xFF).toByte(),
    )
}
