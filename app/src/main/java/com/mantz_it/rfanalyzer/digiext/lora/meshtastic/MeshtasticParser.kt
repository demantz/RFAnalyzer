package com.mantz_it.rfanalyzer.digiext.lora.meshtastic

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticAirQualityMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticDataFields
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticDeviceMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticEncrypted
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticEnvironmentMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticHealthMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticHostMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticInfo
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticLocalStats
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticNeighbor
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticNeighborInfo
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticNodeInfo
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticOpaquePayload
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPacket
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPosition
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPowerChannel
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticPowerMetrics
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticRoute
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticRouting
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticTelemetry
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticTextMessage
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticTraceroute
import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticWaypoint
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * <h1>RF Analyzer - Meshtastic Parser</h1>
 *
 * Module:      MeshtasticParser.kt
 * Description: Best-effort Meshtastic protocol decode of a raw LoRa PHY payload
 *              (github.com/meshtastic/firmware, github.com/meshtastic/protobufs). The header
 *              layout, the default channel key and the AES-CTR nonce are checked against real
 *              captured frames (TestMeshtastic).
 *
 * Unlike MeshCore's hash+MAC envelope, Meshtastic's `Data` protobuf has no authentication tag —
 * AES-CTR decryption always "succeeds" byte-wise, so the only available structural gate is: does
 * the decrypted plaintext parse as a plausible `Data` message (varint portnum + length-delimited
 * payload)? This means a packet on a channel this app has no key for will decrypt to garbage and
 * fail that gate, returning [MeshtasticEncrypted] rather than a false decode.
 *
 * Keys: the default key (every channel whose PSK is the 1-byte "default", whatever its name) is
 * always tried. The other "simple" PSKs (1-byte values 2-10: the default key with its last byte
 * raised) and unencrypted channels can only be told apart by the channel hash (xor of the channel
 * name's and the key's bytes), so they are tried when the hash matches a modem preset's channel
 * name with that key.
 *
 * The wire format is read by hand below - no protobuf codegen dependency. Field numbers are
 * Meshtastic's published protobuf schema (meshtastic/protobufs: mesh.proto, telemetry.proto,
 * portnums.proto), read for the numbers only.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object MeshtasticParser {
    private const val HEADER_SIZE = 16
    private const val PORTNUM_TEXT_MESSAGE = 1
    private const val PORTNUM_POSITION = 3
    private const val PORTNUM_NODEINFO = 4
    private const val PORTNUM_ROUTING = 5
    private const val PORTNUM_WAYPOINT = 8
    private const val PORTNUM_DETECTION_SENSOR = 10
    private const val PORTNUM_ALERT = 11
    private const val PORTNUM_RANGE_TEST = 66
    private const val PORTNUM_TELEMETRY = 67
    private const val PORTNUM_TRACEROUTE = 70
    private const val PORTNUM_NEIGHBORINFO = 71

    /** Labels for [MeshtasticPacket.decryptedWith]. */
    const val KEY_DEFAULT = "default key"
    const val KEY_NONE = "unencrypted"
    fun simpleKeyLabel(index: Int) = "simple key $index"

    // Meshtastic's well-known default/public channel ("LongFast" preset etc.) key —
    // not a secret, the whole point of the default channel is public readability.
    private val DEFAULT_KEY = byteArrayOf(
        0xd4.toByte(), 0xf1.toByte(), 0xbb.toByte(), 0x3a, 0x20, 0x29, 0x07, 0x59,
        0xf0.toByte(), 0xbc.toByte(), 0xff.toByte(), 0xab.toByte(), 0xcf.toByte(), 0x4e, 0x69, 0x01,
    )
    private val DEFAULT_CHANNEL_KEY = SecretKeySpec(DEFAULT_KEY, "AES")

    /** The firmware's channel names of the modem presets - the name of a channel left unnamed. */
    private val PRESET_CHANNEL_NAMES = listOf(
        "LongFast", "LongSlow", "LongMod", "LongTurbo", "MediumFast", "MediumSlow", "ShortFast", "ShortSlow", "ShortTurbo",
    )

    /** Simple PSK n (2..10): the default key with its last byte raised by n - 1. */
    private val SIMPLE_KEYS = (2..10).associateWith { index ->
        DEFAULT_KEY.copyOf().also { it[it.size - 1] = (it[it.size - 1] + index - 1).toByte() }
    }

    private fun xorHash(bytes: ByteArray): Int = bytes.fold(0) { acc, b -> acc xor (b.toInt() and 0xFF) }

    /** Channel hash -> (label, key or null for unencrypted) of the preset-named channels with a
     *  simple key or none; the default key is tried for every hash anyway. */
    private val DERIVABLE_KEYS: Map<Int, List<Pair<String, ByteArray?>>> = buildList {
        for (name in PRESET_CHANNEL_NAMES) {
            val nameHash = xorHash(name.toByteArray(Charsets.US_ASCII))
            add(nameHash to (KEY_NONE to null))
            for ((index, key) in SIMPLE_KEYS) add((nameHash xor xorHash(key)) to (simpleKeyLabel(index) to key))
        }
    }.groupBy({ it.first }, { it.second }).mapValues { (_, keys) -> keys.distinctBy { it.first } }

    fun tryParse(payload: ByteArray): MeshtasticPacket? {
        if (payload.size <= HEADER_SIZE) return null

        val dest = readUInt32LE(payload, 0)
        val sender = readUInt32LE(payload, 4)
        val packetId = readUInt32LE(payload, 8)
        val flags = payload[12].toInt() and 0xFF
        val channelHash = payload[13].toInt() and 0xFF
        val hopLimit = flags and 0x07
        val wantAck = (flags ushr 3) and 1 != 0
        val viaMqtt = (flags ushr 4) and 1 != 0
        val hopStart = (flags ushr 5) and 0x07

        val nextHop = payload[14].toInt() and 0xFF
        val relayNode = payload[15].toInt() and 0xFF

        val ciphertext = payload.copyOfRange(HEADER_SIZE, payload.size)
        // a derived key is a guess from one hash byte: it has to yield a fully decoded payload
        val decoded = DERIVABLE_KEYS[channelHash].orEmpty().firstNotNullOfOrNull { (label, key) ->
            val plain = if (key == null) ciphertext else decrypt(packetId, sender, ciphertext, SecretKeySpec(key, "AES"))
            plain?.let { parseData(it, strict = true) }?.let { label to it }
        } ?: decrypt(packetId, sender, ciphertext, DEFAULT_CHANNEL_KEY)?.let { parseData(it, strict = false) }?.let { KEY_DEFAULT to it }
        val info = decoded?.second?.first ?: MeshtasticEncrypted(Bytes(ciphertext))

        return MeshtasticPacket(
            dest, sender, packetId, hopLimit, wantAck, hopStart, viaMqtt, channelHash, info,
            nextHop = nextHop, relayNode = relayNode, data = decoded?.second?.second, decryptedWith = decoded?.first,
        )
    }

    private fun decrypt(packetId: Long, sender: Long, ciphertext: ByteArray, key: SecretKeySpec): ByteArray? {
        val nonce = ByteArray(16)
        // CryptoEngine::initNonce(fromNode, packetId): packetId as uint64 LE (8 bytes,
        // zero-extended from the 32-bit header field) + fromNode as uint32 LE (4 bytes)
        // + 4 zero bytes.
        writeUInt32LE(nonce, 0, packetId)
        writeUInt32LE(nonce, 8, sender)
        return try {
            Cipher.getInstance("AES/CTR/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, IvParameterSpec(nonce))
            }.doFinal(ciphertext)
        } catch (e: Exception) {
            null
        }
    }

    // Outer protobuf `Data` message (mesh.proto): 1 portnum (varint), 2 payload (bytes),
    // 3 want_response (bool), 4 dest, 5 source, 6 request_id, 7 reply_id, 8 emoji (fixed32),
    // 9 bitfield (varint). [strict]: only a payload decoded field by field counts, not an opaque one.
    private fun parseData(data: ByteArray, strict: Boolean): Pair<MeshtasticInfo, MeshtasticDataFields>? {
        val fields = parseProtoFields(data) ?: return null
        val portnum = fields.varint(1)?.toInt() ?: return null
        val innerPayload = fields.bytes(2) ?: return null

        val info = when (portnum) {
            PORTNUM_TEXT_MESSAGE, PORTNUM_DETECTION_SENSOR, PORTNUM_ALERT, PORTNUM_RANGE_TEST ->
                MeshtasticTextMessage(portnum, String(innerPayload, Charsets.UTF_8))
            PORTNUM_NODEINFO -> runCatching { parseNodeInfo(portnum, innerPayload) }.getOrNull()
            PORTNUM_POSITION -> runCatching { parsePosition(portnum, innerPayload) }.getOrNull()
            PORTNUM_TELEMETRY -> runCatching { parseTelemetry(portnum, innerPayload) }.getOrNull()
            PORTNUM_ROUTING -> runCatching { parseRouting(portnum, innerPayload) }.getOrNull()
            PORTNUM_TRACEROUTE -> runCatching { parseRouteDiscovery(innerPayload)?.let { MeshtasticTraceroute(portnum, it) } }.getOrNull()
            PORTNUM_NEIGHBORINFO -> runCatching { parseNeighborInfo(portnum, innerPayload) }.getOrNull()
            PORTNUM_WAYPOINT -> runCatching { parseWaypoint(portnum, innerPayload) }.getOrNull()
            else -> null
        } ?: if (strict) return null else MeshtasticOpaquePayload(portnum, Bytes(innerPayload))
        val extra = MeshtasticDataFields(
            wantResponse = (fields.varint(3) ?: 0L) != 0L,
            dest = fields.fixed32(4)?.takeIf { it != 0L },
            source = fields.fixed32(5)?.takeIf { it != 0L },
            requestId = fields.fixed32(6)?.takeIf { it != 0L },
            replyId = fields.fixed32(7)?.takeIf { it != 0L },
            emoji = (fields.fixed32(8) ?: 0L) != 0L,
            bitfield = fields.varint(9)?.toInt(),
        )
        return info to extra
    }

    // `User` submessage (mesh.proto): field 1 = id (string), field 2 = long_name (string),
    // field 3 = short_name (string), field 5 = hw_model (enum/varint), field 6 = is_licensed
    // (bool/varint), field 7 = role (enum/varint).
    private fun parseNodeInfo(portnum: Int, data: ByteArray): MeshtasticNodeInfo? {
        val fields = parseProtoFields(data) ?: return null
        fun str(fieldNum: Int) = fields.firstOrNull { it.number == fieldNum && it.wireType == WIRE_LEN }
            ?.bytes?.let { String(it, Charsets.UTF_8) }
        fun varint(fieldNum: Int) = fields.firstOrNull { it.number == fieldNum && it.wireType == WIRE_VARINT }?.varint
        return MeshtasticNodeInfo(
            portnum, nodeId = str(1), longName = str(2), shortName = str(3),
            hwModel = varint(5)?.toInt(),
            isLicensed = varint(6)?.let { it != 0L },
            role = varint(7)?.toInt(),
        )
    }

    // `Position` submessage (mesh.proto): field 1 = latitude_i (sfixed32), field 2 =
    // longitude_i (sfixed32), field 3 = altitude (int32, meters), field 4 = time (fixed32,
    // unix seconds), field 11 = PDOP (uint32/varint, *100), field 15 = ground_speed (int32/
    // varint, m/s), field 16 = ground_track (uint32/varint, degrees*1e5), field 19 =
    // sats_in_view (uint32/varint). latitude_i/longitude_i are decimal degrees scaled by 1e7.
    private fun parsePosition(portnum: Int, data: ByteArray): MeshtasticPosition? {
        val fields = parseProtoFields(data) ?: return null
        val lat = fields.firstOrNull { it.number == 1 && it.wireType == WIRE_FIXED32 }
            ?.bytes?.let { readInt32LE(it, 0) / 1e7 }
        val lon = fields.firstOrNull { it.number == 2 && it.wireType == WIRE_FIXED32 }
            ?.bytes?.let { readInt32LE(it, 0) / 1e7 }
        val alt = fields.firstOrNull { it.number == 3 && it.wireType == WIRE_VARINT }?.varint?.toInt()
        val timeSec = fields.firstOrNull { it.number == 4 && it.wireType == WIRE_FIXED32 }
            ?.bytes?.let { readUInt32LE(it, 0) }
        val pdop = fields.firstOrNull { it.number == 11 && it.wireType == WIRE_VARINT }?.varint?.toInt()
        val groundSpeed = fields.firstOrNull { it.number == 15 && it.wireType == WIRE_VARINT }?.varint?.toInt()
        val groundTrack = fields.firstOrNull { it.number == 16 && it.wireType == WIRE_VARINT }?.varint?.toInt()
        val satsInView = fields.firstOrNull { it.number == 19 && it.wireType == WIRE_VARINT }?.varint?.toInt()
        if (lat == null && lon == null && alt == null && timeSec == null && pdop == null &&
            groundSpeed == null && groundTrack == null && satsInView == null) return null
        return MeshtasticPosition(portnum, lat, lon, alt, groundSpeed, groundTrack, satsInView, pdop, timeSec)
    }

    // `Telemetry` submessage (telemetry.proto): field 1 = time (fixed32, unix seconds); oneof
    // `variant`: 2 device_metrics, 3 environment_metrics, 4 air_quality_metrics,
    // 5 power_metrics, 6 local_stats, 7 health_metrics, 8 host_metrics.
    private fun parseTelemetry(portnum: Int, data: ByteArray): MeshtasticTelemetry? {
        val fields = parseProtoFields(data) ?: return null
        val timeSec = fields.fixed32(1)
        val deviceMetrics = fields.bytes(2)?.let { parseDeviceMetrics(it) }
        val environmentMetrics = fields.bytes(3)?.let { parseEnvironmentMetrics(it) }
        val airQuality = fields.bytes(4)?.let { parseAirQuality(it) }
        val power = fields.bytes(5)?.let { parsePower(it) }
        val localStats = fields.bytes(6)?.let { parseLocalStats(it) }
        val health = fields.bytes(7)?.let { parseHealth(it) }
        val host = fields.bytes(8)?.let { parseHost(it) }
        if (timeSec == null && deviceMetrics == null && environmentMetrics == null && airQuality == null &&
            power == null && localStats == null && health == null && host == null) return null
        return MeshtasticTelemetry(portnum, timeSec, deviceMetrics, environmentMetrics, airQuality, power, localStats, health, host)
    }

    // `DeviceMetrics` submessage: field 1 = battery_level (uint32/varint, percent),
    // field 2 = voltage (float), field 3 = channel_utilization (float, percent),
    // field 4 = air_util_tx (float, percent), field 5 = uptime_seconds (uint32/varint).
    private fun parseDeviceMetrics(data: ByteArray): MeshtasticDeviceMetrics? {
        val fields = parseProtoFields(data) ?: return null
        val batteryLevel = fields.firstOrNull { it.number == 1 && it.wireType == WIRE_VARINT }?.varint?.toInt()
        val voltage = fields.firstOrNull { it.number == 2 && it.wireType == WIRE_FIXED32 }
            ?.bytes?.let { readFloatLE(it, 0) }
        val channelUtilization = fields.firstOrNull { it.number == 3 && it.wireType == WIRE_FIXED32 }
            ?.bytes?.let { readFloatLE(it, 0) }
        val airUtilTx = fields.firstOrNull { it.number == 4 && it.wireType == WIRE_FIXED32 }
            ?.bytes?.let { readFloatLE(it, 0) }
        val uptimeSeconds = fields.firstOrNull { it.number == 5 && it.wireType == WIRE_VARINT }?.varint
        if (batteryLevel == null && voltage == null && channelUtilization == null &&
            airUtilTx == null && uptimeSeconds == null) return null
        return MeshtasticDeviceMetrics(batteryLevel, voltage, channelUtilization, airUtilTx, uptimeSeconds)
    }

    // `EnvironmentMetrics` submessage: 1 temperature, 2 relative_humidity, 3 barometric_pressure,
    // 4 gas_resistance, 5 voltage, 6 current (floats), 7 iaq (varint), 8 distance, 9 lux (floats),
    // 13 wind_direction (varint), 14 wind_speed, 15 weight, 16 wind_gust, 18 radiation,
    // 19 rainfall_1h, 20 rainfall_24h (floats), 21 soil_moisture (varint), 22 soil_temperature.
    private fun parseEnvironmentMetrics(data: ByteArray): MeshtasticEnvironmentMetrics? {
        val f = parseProtoFields(data) ?: return null
        val metrics = MeshtasticEnvironmentMetrics(
            temperature = f.float(1), relativeHumidity = f.float(2), barometricPressure = f.float(3),
            gasResistance = f.float(4), voltage = f.float(5), current = f.float(6), iaq = f.varint(7)?.toInt(),
            distance = f.float(8), lux = f.float(9), windDirection = f.varint(13)?.toInt(), windSpeed = f.float(14),
            weight = f.float(15), windGust = f.float(16), radiation = f.float(18), rainfall1h = f.float(19),
            rainfall24h = f.float(20), soilMoisture = f.varint(21)?.toInt(), soilTemperature = f.float(22),
        )
        return metrics.takeIf { it != EMPTY_ENVIRONMENT }
    }

    private val EMPTY_ENVIRONMENT = MeshtasticEnvironmentMetrics(null, null, null)

    // `AirQualityMetrics`: 1-3 pm10/pm25/pm100 standard, 4-6 the same environmental, 13 co2 (varints).
    private fun parseAirQuality(data: ByteArray): MeshtasticAirQualityMetrics? {
        val f = parseProtoFields(data) ?: return null
        return MeshtasticAirQualityMetrics(
            pm10Standard = f.varint(1)?.toInt(), pm25Standard = f.varint(2)?.toInt(), pm100Standard = f.varint(3)?.toInt(),
            pm10Environmental = f.varint(4)?.toInt(), pm25Environmental = f.varint(5)?.toInt(),
            pm100Environmental = f.varint(6)?.toInt(), co2 = f.varint(13)?.toInt(),
        ).takeIf { it != MeshtasticAirQualityMetrics() }
    }

    // `PowerMetrics`: channel n's voltage at field 2n-1, its current at 2n (floats), n = 1..8.
    private fun parsePower(data: ByteArray): MeshtasticPowerMetrics? {
        val f = parseProtoFields(data) ?: return null
        val channels = (1..8).mapNotNull { n ->
            val voltage = f.float(2 * n - 1)
            val current = f.float(2 * n)
            if (voltage == null && current == null) null else MeshtasticPowerChannel(n, voltage, current)
        }
        return channels.takeIf { it.isNotEmpty() }?.let(::MeshtasticPowerMetrics)
    }

    // `LocalStats`: 1 uptime_seconds, 2 channel_utilization, 3 air_util_tx (floats), 4 num_packets_tx,
    // 5 num_packets_rx, 6 num_packets_rx_bad, 7 num_online_nodes, 8 num_total_nodes, 9 num_rx_dupe,
    // 10 num_tx_relay, 11 num_tx_relay_canceled, 15 noise_floor (int32).
    private fun parseLocalStats(data: ByteArray): MeshtasticLocalStats? {
        val f = parseProtoFields(data) ?: return null
        return MeshtasticLocalStats(
            uptimeSeconds = f.varint(1), channelUtilization = f.float(2), airUtilTx = f.float(3),
            packetsTx = f.varint(4), packetsRx = f.varint(5), packetsRxBad = f.varint(6),
            onlineNodes = f.varint(7)?.toInt(), totalNodes = f.varint(8)?.toInt(), rxDupe = f.varint(9),
            txRelay = f.varint(10), txRelayCanceled = f.varint(11), noiseFloor = f.varint(15)?.toInt(),
        ).takeIf { it != MeshtasticLocalStats() }
    }

    // `HealthMetrics`: 1 heart_bpm, 2 spO2 (varints), 3 temperature (float).
    private fun parseHealth(data: ByteArray): MeshtasticHealthMetrics? {
        val f = parseProtoFields(data) ?: return null
        return MeshtasticHealthMetrics(f.varint(1)?.toInt(), f.varint(2)?.toInt(), f.float(3))
            .takeIf { it != MeshtasticHealthMetrics() }
    }

    // `HostMetrics`: 1 uptime_seconds, 2 freemem_bytes, 3 diskfree1_bytes, 6-8 load1/5/15 (varints),
    // 9 user_string.
    private fun parseHost(data: ByteArray): MeshtasticHostMetrics? {
        val f = parseProtoFields(data) ?: return null
        return MeshtasticHostMetrics(
            uptimeSeconds = f.varint(1), freeMemBytes = f.varint(2), diskFreeBytes = f.varint(3),
            load1 = f.varint(6)?.toInt(), load5 = f.varint(7)?.toInt(), load15 = f.varint(8)?.toInt(),
            userString = f.bytes(9)?.let { String(it, Charsets.UTF_8) },
        ).takeIf { it != MeshtasticHostMetrics() }
    }

    // `Routing`: oneof 1 route_request, 2 route_reply (RouteDiscovery), 3 error_reason (enum).
    private fun parseRouting(portnum: Int, data: ByteArray): MeshtasticRouting? {
        val f = parseProtoFields(data) ?: return null
        val request = f.bytes(1)?.let { parseRouteDiscovery(it) }
        val reply = f.bytes(2)?.let { parseRouteDiscovery(it) }
        // proto3: an ack (error NONE = 0) is an empty message
        val error = f.varint(3)?.toInt() ?: if (request == null && reply == null) 0 else null
        return MeshtasticRouting(portnum, error, request, reply)
    }

    // `RouteDiscovery`: 1 route, 3 route_back (packed fixed32 node numbers), 2 snr_towards,
    // 4 snr_back (packed int32, dB × 4; INT8_MIN = not measured).
    private fun parseRouteDiscovery(data: ByteArray): MeshtasticRoute? {
        val f = parseProtoFields(data) ?: return null
        fun nodes(number: Int) = f.filter { it.number == number }.flatMap { field ->
            when (field.wireType) {
                WIRE_LEN -> if (field.bytes.size % 4 == 0) (0 until field.bytes.size / 4).map { readUInt32LE(field.bytes, 4 * it) } else emptyList()
                WIRE_FIXED32 -> listOf(readUInt32LE(field.bytes, 0))
                else -> emptyList()
            }
        }
        fun snrs(number: Int) = f.filter { it.number == number }.flatMap { field ->
            when (field.wireType) {
                WIRE_LEN -> readPackedVarints(field.bytes) ?: emptyList()
                WIRE_VARINT -> listOf(field.varint)
                else -> emptyList()
            }
        }.map { raw -> raw.toInt().takeIf { it != SNR_UNKNOWN }?.let { it / 4f } }
        return MeshtasticRoute(nodes(1), snrs(2), nodes(3), snrs(4))
    }

    private const val SNR_UNKNOWN = -128

    // `NeighborInfo`: 1 node_id, 2 last_sent_by_id, 3 node_broadcast_interval_secs (varints),
    // 4 neighbors (repeated Neighbor: 1 node_id, 2 snr (float), 3 last_rx_time (fixed32),
    // 4 node_broadcast_interval_secs).
    private fun parseNeighborInfo(portnum: Int, data: ByteArray): MeshtasticNeighborInfo? {
        val f = parseProtoFields(data) ?: return null
        val neighbors = f.filter { it.number == 4 && it.wireType == WIRE_LEN }.mapNotNull { field ->
            val n = parseProtoFields(field.bytes) ?: return@mapNotNull null
            val id = n.varint(1) ?: return@mapNotNull null
            MeshtasticNeighbor(id, n.float(2), n.fixed32(3), n.varint(4)?.toInt())
        }
        val nodeId = f.varint(1)
        if (nodeId == null && neighbors.isEmpty()) return null
        return MeshtasticNeighborInfo(portnum, nodeId, f.varint(2), f.varint(3)?.toInt(), neighbors)
    }

    // `Waypoint`: 1 id (varint), 2 latitude_i, 3 longitude_i (sfixed32, 1e-7 degrees), 4 expire,
    // 5 locked_to (varints), 6 name, 7 description (strings), 8 icon (fixed32 code point).
    private fun parseWaypoint(portnum: Int, data: ByteArray): MeshtasticWaypoint? {
        val f = parseProtoFields(data) ?: return null
        val id = f.varint(1) ?: return null
        return MeshtasticWaypoint(
            portnum, id,
            latitude = f.fixed32(2)?.let { it.toInt() / 1e7 }, longitude = f.fixed32(3)?.let { it.toInt() / 1e7 },
            expireSec = f.varint(4)?.takeIf { it != 0L }, lockedTo = f.varint(5)?.takeIf { it != 0L },
            name = f.bytes(6)?.let { String(it, Charsets.UTF_8) }, description = f.bytes(7)?.let { String(it, Charsets.UTF_8) },
            icon = f.fixed32(8)?.toInt()?.takeIf { it != 0 },
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Minimal protobuf wire-format reader — just enough to walk (field_number,
    // wire_type) tags for the message types decoded above.
    // ─────────────────────────────────────────────────────────────────────────

    private const val WIRE_VARINT = 0
    private const val WIRE_FIXED64 = 1
    private const val WIRE_LEN = 2
    private const val WIRE_FIXED32 = 5

    private class ProtoField(val number: Int, val wireType: Int, val varint: Long, val bytes: ByteArray)

    private fun List<ProtoField>.varint(number: Int) = firstOrNull { it.number == number && it.wireType == WIRE_VARINT }?.varint
    private fun List<ProtoField>.bytes(number: Int) = firstOrNull { it.number == number && it.wireType == WIRE_LEN }?.bytes
    private fun List<ProtoField>.fixed32(number: Int) =
        firstOrNull { it.number == number && it.wireType == WIRE_FIXED32 }?.bytes?.let { readUInt32LE(it, 0) }
    private fun List<ProtoField>.float(number: Int) =
        firstOrNull { it.number == number && it.wireType == WIRE_FIXED32 }?.bytes?.let { readFloatLE(it, 0) }

    /** A packed repeated varint field's values, or null if malformed. */
    private fun readPackedVarints(data: ByteArray): List<Long>? {
        val values = mutableListOf<Long>()
        var pos = 0
        while (pos < data.size) {
            val (value, after) = readVarint(data, pos) ?: return null
            values += value
            pos = after
        }
        return values
    }

    private fun parseProtoFields(data: ByteArray): List<ProtoField>? {
        val fields = mutableListOf<ProtoField>()
        var pos = 0
        while (pos < data.size) {
            val (tag, afterTag) = readVarint(data, pos) ?: return null
            pos = afterTag
            val fieldNum = (tag ushr 3).toInt()
            when (val wireType = (tag and 0x7).toInt()) {
                WIRE_VARINT -> {
                    val (value, after) = readVarint(data, pos) ?: return null
                    fields += ProtoField(fieldNum, wireType, value, ByteArray(0))
                    pos = after
                }
                WIRE_FIXED64 -> {
                    if (pos + 8 > data.size) return null
                    fields += ProtoField(fieldNum, wireType, 0L, data.copyOfRange(pos, pos + 8))
                    pos += 8
                }
                WIRE_LEN -> {
                    val (len, after) = readVarint(data, pos) ?: return null
                    pos = after
                    if (len < 0 || pos + len > data.size) return null
                    fields += ProtoField(fieldNum, wireType, 0L, data.copyOfRange(pos, pos + len.toInt()))
                    pos += len.toInt()
                }
                WIRE_FIXED32 -> {
                    if (pos + 4 > data.size) return null
                    fields += ProtoField(fieldNum, wireType, 0L, data.copyOfRange(pos, pos + 4))
                    pos += 4
                }
                else -> return null // groups (wire types 3/4) — not used by any message decoded here
            }
        }
        return fields
    }

    /** Returns (value, nextOffset), or null if truncated/malformed. */
    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var pos = offset
        while (pos < data.size) {
            val b = data[pos].toInt() and 0xFF
            if (shift >= 64) return null
            result = result or ((b.toLong() and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) return result to pos
            shift += 7
        }
        return null
    }

    private fun readUInt32LE(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun readInt32LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    // A protobuf float: wire type 5 (fixed32) holding IEEE-754 bits, unlike the scaled sfixed32
    // integers of latitude_i/longitude_i.
    private fun readFloatLE(b: ByteArray, off: Int): Float = Float.fromBits(readInt32LE(b, off))

    private fun writeUInt32LE(dst: ByteArray, off: Int, value: Long) {
        dst[off] = (value and 0xFF).toByte()
        dst[off + 1] = ((value ushr 8) and 0xFF).toByte()
        dst[off + 2] = ((value ushr 16) and 0xFF).toByte()
        dst[off + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
