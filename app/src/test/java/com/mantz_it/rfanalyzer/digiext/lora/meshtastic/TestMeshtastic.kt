package com.mantz_it.rfanalyzer.digiext.lora.meshtastic

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer.digiext.lora.LoraPayloadDecoder
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticEncrypted
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticNodeInfo
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticOpaquePayload
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPacket
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPosition
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticTelemetry
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticTextMessage
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticNeighborInfo
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticRouting
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticTraceroute
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticWaypoint
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * <h1>RF Analyzer - Meshtastic Tests</h1>
 *
 * Module:      TestMeshtastic.kt
 * Description: Pure-JVM unit tests for MeshtasticParser / LoraPayloadDecoder — no JNI/device
 *              needed, this is plain byte-array parsing + standard-library AES-CTR. The real
 *              fixtures are two LongFast frames from a recording: one decrypts to a POSITION_APP
 *              report, the other to the TEXT_MESSAGE_APP "Test aus Lübeck. Guten Abend!" - they pin
 *              the default-channel key, the AES-CTR nonce construction and the protobuf field
 *              numbers MeshtasticParser uses.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class TestMeshtastic {

    // ---- Real fixtures (meshtastic_longfast_msg_869526kHz_500kSps.cf32) ----

    private val realPositionFrame = byteArrayOf(
        0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xb0.toByte(), 0x25, 0x83.toByte(), 0x69,
        0x9d.toByte(), 0x6e, 0xd8.toByte(), 0xee.toByte(), 0xe4.toByte(), 0x08, 0x00, 0xdc.toByte(),
        0x13, 0x4d, 0x03, 0x89.toByte(), 0x69, 0xbb.toByte(), 0x6a, 0x46,
        0x97.toByte(), 0x8f.toByte(), 0x0e, 0x30, 0x1c, 0x82.toByte(), 0x07, 0x96.toByte(),
        0x00, 0x49, 0x5c, 0x08, 0x88.toByte(), 0x6b, 0xb8.toByte(), 0x61,
        0xa2.toByte(), 0x09, 0x0f, 0x7b, 0xa2.toByte(), 0x4c, 0x6e, 0xd0.toByte(),
        0xc3.toByte(), 0x20, 0xbd.toByte(), 0x6d, 0xcf.toByte(), 0x9d.toByte(), 0x10, 0x55,
        0x76, 0x26, 0xf2.toByte(),
    )

    private val realTextFrame = byteArrayOf(
        0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xff.toByte(), 0xdc.toByte(), 0xb1.toByte(), 0x76, 0x9e.toByte(),
        0x2d, 0xdc.toByte(), 0xe8.toByte(), 0x14, 0x63, 0x08, 0x00, 0xdc.toByte(),
        0xfe.toByte(), 0xe7.toByte(), 0xc4.toByte(), 0xc8.toByte(), 0xec.toByte(), 0xec.toByte(), 0x33, 0xf3.toByte(),
        0x79, 0x08, 0xe8.toByte(), 0x9e.toByte(), 0x77, 0xca.toByte(), 0x84.toByte(), 0x6f,
        0xb2.toByte(), 0x0e, 0xe3.toByte(), 0xbe.toByte(), 0xee.toByte(), 0x0e, 0x06, 0xd0.toByte(),
        0x9f.toByte(), 0x73, 0x72, 0x04, 0xf2.toByte(), 0x9a.toByte(), 0xdf.toByte(), 0xd9.toByte(),
        0x2e, 0xc2.toByte(), 0x49, 0x09,
    )

    @Test
    fun testRealPositionFixture() {
        val packet = MeshtasticParser.tryParse(realPositionFrame)
        assertTrue("Expected a MeshtasticPacket", packet != null)
        packet!!

        assertEquals(0xFFFFFFFFL, packet.dest)
        assertEquals(0x698325b0L, packet.sender)
        assertEquals(0xeed86e9dL, packet.packetId)
        assertEquals(4, packet.hopLimit)   // flags=0xe4: bits0-2
        assertEquals(7, packet.hopStart)   // flags=0xe4: bits5-7
        assertTrue(!packet.wantAck)
        assertTrue(!packet.viaMqtt)
        assertEquals(0x08, packet.channelHash)

        assertTrue("Expected MeshtasticPosition info but got ${packet.info}", packet.info is MeshtasticPosition)
        val position = packet.info as MeshtasticPosition
        assertEquals(53.8574848, position.latitude!!, 0.0001)
        assertEquals(10.682368, position.longitude!!, 0.0001)
        assertEquals(-36, position.altitudeM)

        assertTrue(LoraPayloadDecoder.decode(realPositionFrame, syncWord = 0x2B) is MeshtasticPacket)
    }

    @Test
    fun testRealTextMessageFixture() {
        val packet = MeshtasticParser.tryParse(realTextFrame)
        assertTrue("Expected a MeshtasticPacket", packet != null)
        packet!!

        assertEquals(0xFFFFFFFFL, packet.dest)
        assertEquals(0x9e76b1dcL, packet.sender)
        assertEquals(0x14e8dc2dL, packet.packetId)
        assertEquals(3, packet.hopLimit)   // flags=0x63: bits0-2 -> default HopLimit
        assertEquals(3, packet.hopStart)   // flags=0x63: bits5-7 -> fresh packet, no hops yet

        assertTrue("Expected MeshtasticTextMessage info but got ${packet.info}", packet.info is MeshtasticTextMessage)
        val text = packet.info as MeshtasticTextMessage
        assertEquals("Test aus Lübeck. Guten Abend!", text.text)

        assertTrue(LoraPayloadDecoder.decode(realTextFrame, syncWord = 0x2B) is MeshtasticPacket)
    }

    // ---- Synthetic fixtures: build+encrypt a Data envelope ourselves, round-trip it ----

    private val defaultChannelKeyBytes = byteArrayOf(
        0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a, 0x20, 0x29, 0x07, 0x59,
        0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(), 0xab.toByte(), 0xcf.toByte(), 0x4e, 0x69, 0x01,
    )

    private fun encodeVarint(value: Long): ByteArray {
        val bytes = mutableListOf<Byte>()
        var v = value
        do {
            var b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v != 0L) b = b or 0x80
            bytes.add(b.toByte())
        } while (v != 0L)
        return bytes.toByteArray()
    }

    private fun encodeTag(fieldNum: Int, wireType: Int) = encodeVarint(((fieldNum shl 3) or wireType).toLong())
    private fun encodeLenDelimited(fieldNum: Int, data: ByteArray) = encodeTag(fieldNum, 2) + encodeVarint(data.size.toLong()) + data
    private fun encodeVarintField(fieldNum: Int, value: Long) = encodeTag(fieldNum, 0) + encodeVarint(value)
    private fun encodeFixed32Field(fieldNum: Int, value: Int): ByteArray {
        val bytes = ByteArray(4)
        bytes[0] = (value and 0xFF).toByte()
        bytes[1] = ((value ushr 8) and 0xFF).toByte()
        bytes[2] = ((value ushr 16) and 0xFF).toByte()
        bytes[3] = ((value ushr 24) and 0xFF).toByte()
        return encodeTag(fieldNum, 5) + bytes
    }
    private fun encodeFloatField(fieldNum: Int, value: Float) = encodeFixed32Field(fieldNum, value.toRawBits())
    private fun encodeDataEnvelope(portnum: Int, innerPayload: ByteArray) =
        encodeVarintField(1, portnum.toLong()) + encodeLenDelimited(2, innerPayload)

    private fun writeU32LE(dst: ByteArray, off: Int, value: Long) {
        dst[off] = (value and 0xFF).toByte()
        dst[off + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun buildMeshtasticPayload(
        plainDataEnvelope: ByteArray,
        dest: Long = 0xFFFFFFFFL,
        sender: Long = 0x11223344L,
        packetId: Long = 0x0A0B0C0DL,
        flags: Int = 0x63,
        channelHash: Int = 0x08,
        /** null: an unencrypted channel. */
        key: ByteArray? = defaultChannelKeyBytes,
        nextHop: Int = 0,
        relayNode: Int = 0,
    ): ByteArray {
        val header = ByteArray(16)
        writeU32LE(header, 0, dest)
        writeU32LE(header, 4, sender)
        writeU32LE(header, 8, packetId)
        header[12] = flags.toByte()
        header[13] = channelHash.toByte()
        header[14] = nextHop.toByte()
        header[15] = relayNode.toByte()
        if (key == null) return header + plainDataEnvelope

        val nonce = ByteArray(16)
        writeU32LE(nonce, 0, packetId)
        writeU32LE(nonce, 8, sender)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(nonce))
        }
        val ciphertext = cipher.doFinal(plainDataEnvelope)
        return header + ciphertext
    }

    private fun xorHash(bytes: ByteArray) = bytes.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }
    private fun packed(values: List<Long>) = values.fold(ByteArray(0)) { acc, v -> acc + encodeVarint(v) }
    private fun packedFixed32(values: List<Long>) = values.fold(ByteArray(0)) { acc, v ->
        acc + ByteArray(4).also { writeU32LE(it, 0, v) }
    }
    private fun decode(portnum: Int, inner: ByteArray, extra: ByteArray = ByteArray(0)) =
        MeshtasticParser.tryParse(buildMeshtasticPayload(encodeDataEnvelope(portnum, inner) + extra))!!

    // ---- header, Data fields, keys ----

    @Test
    fun testHeaderRelayBytesAndDataFields() {
        val extra = encodeVarintField(3, 1) + encodeFixed32Field(6, 0x01020304) + encodeFixed32Field(7, 0x0A0B0C0D) + encodeFixed32Field(8, 1)
        val packet = MeshtasticParser.tryParse(buildMeshtasticPayload(encodeDataEnvelope(1, "👍".toByteArray()) + extra, nextHop = 0x5A, relayNode = 0xC3))!!
        assertEquals(0x5A, packet.nextHop)
        assertEquals(0xC3, packet.relayNode)
        val data = packet.data!!
        assertTrue(data.wantResponse)
        assertEquals(0x01020304L, data.requestId)
        assertEquals(0x0A0B0C0DL, data.replyId)
        assertTrue(data.emoji)
        assertEquals(MeshtasticParser.KEY_DEFAULT, packet.decryptedWith)
    }

    /** A preset-named channel with a simple key (PSK index 2-10) or none is told apart by its hash. */
    @Test
    fun testSimpleKeyAndUnencryptedPresetChannels() {
        val simple3 = defaultChannelKeyBytes.copyOf().also { it[15] = (it[15] + 2).toByte() }
        val mediumFast = xorHash("MediumFast".toByteArray()) xor xorHash(simple3)
        val envelope = encodeDataEnvelope(1, "hello".toByteArray())
        val simple = MeshtasticParser.tryParse(buildMeshtasticPayload(envelope, channelHash = mediumFast, key = simple3))!!
        assertEquals("hello", (simple.info as MeshtasticTextMessage).text)
        assertEquals(MeshtasticParser.simpleKeyLabel(3), simple.decryptedWith)
        // the same packet under a hash no preset/simple-key pair gives stays encrypted
        val otherHash = (0..255).first { h -> (2..10).none { i ->
            val k = defaultChannelKeyBytes.copyOf().also { it[15] = (it[15] + i - 1).toByte() }
            listOf("LongFast", "LongSlow", "LongMod", "LongTurbo", "MediumFast", "MediumSlow", "ShortFast", "ShortSlow", "ShortTurbo")
                .any { n -> h == xorHash(n.toByteArray()) xor xorHash(k) || h == xorHash(n.toByteArray()) }
        } }
        assertTrue(MeshtasticParser.tryParse(buildMeshtasticPayload(envelope, channelHash = otherHash, key = simple3))!!.info is MeshtasticEncrypted)

        val plain = MeshtasticParser.tryParse(buildMeshtasticPayload(envelope, channelHash = xorHash("LongFast".toByteArray()), key = null))!!
        assertEquals("hello", (plain.info as MeshtasticTextMessage).text)
        assertEquals(MeshtasticParser.KEY_NONE, plain.decryptedWith)
    }

    // ---- routing, traceroute, neighbours, waypoints ----

    @Test
    fun testRoutingAckAndError() {
        val ack = decode(5, ByteArray(0)).info as MeshtasticRouting
        assertEquals(0, ack.errorReason)
        val timeout = decode(5, encodeVarintField(3, 3)).info as MeshtasticRouting
        assertEquals(3, timeout.errorReason)
    }

    @Test
    fun testTracerouteRouteAndSnrs() {
        // SNRs are dB × 4 as int32 (negative ones as 10-byte varints); INT8_MIN = not measured
        val discovery = encodeLenDelimited(1, packedFixed32(listOf(0x11111111L, 0x22222222L))) +
            encodeLenDelimited(2, packed(listOf(24L, -128L, -21L))) +
            encodeLenDelimited(4, packed(listOf(10L)))
        val route = (decode(70, discovery).info as MeshtasticTraceroute).route
        assertEquals(listOf(0x11111111L, 0x22222222L), route.route)
        assertEquals(listOf(6f, null, -5.25f), route.snrTowards)
        assertEquals(listOf(2.5f), route.snrBack)
        assertTrue(route.routeBack.isEmpty())
    }

    @Test
    fun testNeighborInfo() {
        val neighbor = { id: Long, snr: Float -> encodeLenDelimited(4, encodeVarintField(1, id) + encodeFloatField(2, snr)) }
        val info = encodeVarintField(1, 0x1C4977B0L) + encodeVarintField(3, 900) + neighbor(0x303AEB26L, 7.25f) + neighbor(0xDA656F24L, -3.5f)
        val decoded = decode(71, info).info as MeshtasticNeighborInfo
        assertEquals(0x1C4977B0L, decoded.nodeId)
        assertEquals(900, decoded.broadcastIntervalSec)
        assertEquals(listOf(0x303AEB26L to 7.25f, 0xDA656F24L to -3.5f), decoded.neighbors.map { it.nodeId to it.snr })
    }

    @Test
    fun testWaypoint() {
        val waypoint = encodeVarintField(1, 42) + encodeFixed32Field(2, 538_970_000) + encodeFixed32Field(3, 106_950_000) +
            encodeLenDelimited(6, "Camp".toByteArray()) + encodeLenDelimited(7, "Meet here".toByteArray()) + encodeFixed32Field(8, 0x1F3D5)
        val decoded = decode(8, waypoint).info as MeshtasticWaypoint
        assertEquals(42L, decoded.id)
        assertEquals(53.897, decoded.latitude!!, 1e-6)
        assertEquals(10.695, decoded.longitude!!, 1e-6)
        assertEquals("Camp", decoded.name)
        assertEquals("Meet here", decoded.description)
        assertEquals(0x1F3D5, decoded.icon)
    }

    @Test
    fun testTextLikePortnums() {
        for (portnum in intArrayOf(10, 11, 66)) {
            val text = decode(portnum, "seq 7".toByteArray()).info as MeshtasticTextMessage
            assertEquals(portnum, text.portnum)
            assertEquals("seq 7", text.text)
        }
    }

    @Test
    fun testTelemetryVariants() {
        fun telemetry(field: Int, body: ByteArray) = decode(67, encodeFixed32Field(1, 1_780_000_000) + encodeLenDelimited(field, body)).info as MeshtasticTelemetry
        val air = telemetry(4, encodeVarintField(2, 12) + encodeVarintField(13, 600)).airQualityMetrics!!
        assertEquals(12, air.pm25Standard)
        assertEquals(600, air.co2)
        val power = telemetry(5, encodeFloatField(1, 3.7f) + encodeFloatField(4, 120f)).powerMetrics!!
        assertEquals(listOf(Triple(1, 3.7f, null), Triple(2, null, 120f)), power.channels.map { Triple(it.channel, it.voltage, it.current) })
        val stats = telemetry(6, encodeVarintField(7, 5) + encodeVarintField(8, 9) + encodeVarintField(15, -110L)).localStats!!
        assertEquals(5, stats.onlineNodes)
        assertEquals(9, stats.totalNodes)
        assertEquals(-110, stats.noiseFloor)
        val health = telemetry(7, encodeVarintField(1, 72) + encodeVarintField(2, 98)).healthMetrics!!
        assertEquals(72, health.heartBpm)
        val host = telemetry(8, encodeVarintField(1, 3600) + encodeVarintField(6, 125) + encodeLenDelimited(9, "pi".toByteArray())).hostMetrics!!
        assertEquals(3600L, host.uptimeSeconds)
        assertEquals(125, host.load1)
        assertEquals("pi", host.userString)
        val environment = telemetry(3, encodeFloatField(1, 21.5f) + encodeVarintField(13, 270) + encodeFloatField(14, 4.5f)).environmentMetrics!!
        assertEquals(21.5f, environment.temperature)
        assertEquals(270, environment.windDirection)
        assertEquals(4.5f, environment.windSpeed)
    }

    @Test
    fun testSyntheticTextMessage() {
        val envelope = encodeDataEnvelope(1, "hello mesh".toByteArray(Charsets.UTF_8))
        val payload = buildMeshtasticPayload(envelope)

        val packet = MeshtasticParser.tryParse(payload)
        assertTrue(packet != null)
        assertTrue("Expected MeshtasticTextMessage but got ${packet!!.info}", packet.info is MeshtasticTextMessage)
        assertEquals("hello mesh", (packet.info as MeshtasticTextMessage).text)
    }

    @Test
    fun testSyntheticNodeInfo() {
        val user = encodeLenDelimited(1, "!deadbeef".toByteArray(Charsets.UTF_8)) +
                encodeLenDelimited(2, "Test Node".toByteArray(Charsets.UTF_8)) +
                encodeLenDelimited(3, "TSTN".toByteArray(Charsets.UTF_8))
        val envelope = encodeDataEnvelope(4, user)
        val payload = buildMeshtasticPayload(envelope)

        val packet = MeshtasticParser.tryParse(payload)
        assertTrue(packet != null)
        assertTrue("Expected MeshtasticNodeInfo but got ${packet!!.info}", packet.info is MeshtasticNodeInfo)
        val nodeInfo = packet.info as MeshtasticNodeInfo
        assertEquals("!deadbeef", nodeInfo.nodeId)
        assertEquals("Test Node", nodeInfo.longName)
        assertEquals("TSTN", nodeInfo.shortName)
    }

    @Test
    fun testUnknownPortnumBecomesOpaque() {
        val innerPayload = byteArrayOf(1, 2, 3, 4, 5)
        val envelope = encodeDataEnvelope(6, innerPayload) // ADMIN_APP — not decoded field-by-field
        val payload = buildMeshtasticPayload(envelope)

        val packet = MeshtasticParser.tryParse(payload)
        assertTrue(packet != null)
        assertTrue("Expected MeshtasticOpaquePayload but got ${packet!!.info}", packet.info is MeshtasticOpaquePayload)
        val opaque = packet.info as MeshtasticOpaquePayload
        assertEquals(6, opaque.portnum)
        assertEquals(Bytes(innerPayload), opaque.payload)
        // a known portnum whose payload doesn't parse is kept opaque too - it did decrypt
        assertEquals(5, (MeshtasticParser.tryParse(buildMeshtasticPayload(encodeDataEnvelope(5, innerPayload)))!!.info as MeshtasticOpaquePayload).portnum)
    }

    // ---- Telemetry / extended Position / extended NodeInfo (synthetic) ----
    // Field numbers per Meshtastic's publicly documented protobuf schema
    // (meshtastic/protobufs telemetry.proto/mesh.proto); of these only Telemetry.time was
    // also checked against real TELEMETRY_APP traffic.

    @Test
    fun testSyntheticTelemetryDeviceMetrics() {
        val deviceMetrics = encodeVarintField(1, 87) +           // battery_level
                encodeFloatField(2, 4.01f) +                     // voltage
                encodeFloatField(3, 12.5f) +                     // channel_utilization
                encodeFloatField(4, 2.3f) +                      // air_util_tx
                encodeVarintField(5, 123456)                     // uptime_seconds
        val telemetry = encodeFixed32Field(1, 1785668407) + encodeLenDelimited(2, deviceMetrics)
        val envelope = encodeDataEnvelope(67, telemetry)
        val payload = buildMeshtasticPayload(envelope)

        val packet = MeshtasticParser.tryParse(payload)
        assertTrue(packet != null)
        assertTrue("Expected MeshtasticTelemetry but got ${packet!!.info}", packet.info is MeshtasticTelemetry)
        val t = packet.info as MeshtasticTelemetry
        assertEquals(1785668407L, t.timeSec)
        assertTrue("Expected deviceMetrics", t.deviceMetrics != null)
        assertEquals(87, t.deviceMetrics!!.batteryLevel)
        assertEquals(4.01f, t.deviceMetrics!!.voltage!!, 0.001f)
        assertEquals(12.5f, t.deviceMetrics!!.channelUtilization!!, 0.001f)
        assertEquals(2.3f, t.deviceMetrics!!.airUtilTx!!, 0.001f)
        assertEquals(123456L, t.deviceMetrics!!.uptimeSeconds)
        assertTrue("Expected no environmentMetrics", t.environmentMetrics == null)
    }

    @Test
    fun testSyntheticTelemetryEnvironmentMetrics() {
        val environmentMetrics = encodeFloatField(1, 21.4f) +    // temperature
                encodeFloatField(2, 55.0f) +                     // relative_humidity
                encodeFloatField(3, 1013.25f)                    // barometric_pressure
        val telemetry = encodeFixed32Field(1, 1785668500) + encodeLenDelimited(3, environmentMetrics)
        val envelope = encodeDataEnvelope(67, telemetry)
        val payload = buildMeshtasticPayload(envelope)

        val packet = MeshtasticParser.tryParse(payload)
        assertTrue(packet != null)
        assertTrue("Expected MeshtasticTelemetry but got ${packet!!.info}", packet.info is MeshtasticTelemetry)
        val t = packet.info as MeshtasticTelemetry
        assertTrue("Expected environmentMetrics", t.environmentMetrics != null)
        assertEquals(21.4f, t.environmentMetrics!!.temperature!!, 0.001f)
        assertEquals(55.0f, t.environmentMetrics!!.relativeHumidity!!, 0.001f)
        assertEquals(1013.25f, t.environmentMetrics!!.barometricPressure!!, 0.001f)
        assertTrue("Expected no deviceMetrics", t.deviceMetrics == null)
    }

    @Test
    fun testSyntheticExtendedPositionFields() {
        val position = encodeFixed32Field(1, (53.8574848 * 1e7).toInt()) +
                encodeFixed32Field(2, (10.682368 * 1e7).toInt()) +
                encodeVarintField(3, 10) +                       // altitude
                encodeFixed32Field(4, 1785668407) +               // time
                encodeVarintField(11, 150) +                      // PDOP
                encodeVarintField(15, 3) +                        // ground_speed
                encodeVarintField(16, 18000) +                    // ground_track
                encodeVarintField(19, 9)                          // sats_in_view
        val envelope = encodeDataEnvelope(3, position)
        val payload = buildMeshtasticPayload(envelope)

        val packet = MeshtasticParser.tryParse(payload)
        assertTrue(packet != null)
        assertTrue("Expected MeshtasticPosition but got ${packet!!.info}", packet.info is MeshtasticPosition)
        val p = packet.info as MeshtasticPosition
        assertEquals(1785668407L, p.timeSec)
        assertEquals(150, p.pdop)
        assertEquals(3, p.groundSpeed)
        assertEquals(18000, p.groundTrack)
        assertEquals(9, p.satsInView)
    }

    @Test
    fun testSyntheticExtendedNodeInfoFields() {
        val user = encodeLenDelimited(1, "!deadbeef".toByteArray(Charsets.UTF_8)) +
                encodeLenDelimited(2, "Test Node".toByteArray(Charsets.UTF_8)) +
                encodeLenDelimited(3, "TSTN".toByteArray(Charsets.UTF_8)) +
                encodeVarintField(5, 9) +      // hw_model
                encodeVarintField(6, 1) +      // is_licensed
                encodeVarintField(7, 2)        // role
        val envelope = encodeDataEnvelope(4, user)
        val payload = buildMeshtasticPayload(envelope)

        val packet = MeshtasticParser.tryParse(payload)
        assertTrue(packet != null)
        assertTrue("Expected MeshtasticNodeInfo but got ${packet!!.info}", packet.info is MeshtasticNodeInfo)
        val nodeInfo = packet.info as MeshtasticNodeInfo
        assertEquals(9, nodeInfo.hwModel)
        assertEquals(true, nodeInfo.isLicensed)
        assertEquals(2, nodeInfo.role)
    }

    @Test
    fun testTooShortPayloadReturnsNull() {
        assertNull(MeshtasticParser.tryParse(ByteArray(16))) // header only, no ciphertext at all
        assertNull(MeshtasticParser.tryParse(ByteArray(10)))
    }

    @Test
    fun testGarbageCiphertextStaysRecognizedNotCrashing() {
        // Meshtastic's Data envelope has no MAC (unlike MeshCore), so a structurally valid
        // header always yields a MeshtasticPacket — content is either successfully parsed,
        // MeshtasticOpaquePayload (parsed as a protobuf, unknown portnum), or
        // MeshtasticEncrypted (didn't parse as a protobuf at all). Never null, never a crash.
        val random = Random(0L)
        repeat(200) {
            val payload = ByteArray(random.nextInt(60) + 17)
            random.nextBytes(payload)
            val packet = MeshtasticParser.tryParse(payload)
            assertTrue("Expected a MeshtasticPacket for any payload > 16 bytes", packet != null)
        }
    }
}
