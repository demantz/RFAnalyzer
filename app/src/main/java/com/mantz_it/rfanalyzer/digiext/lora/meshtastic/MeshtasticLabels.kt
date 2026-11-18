package com.mantz_it.rfanalyzer.digiext.lora.meshtastic

import com.mantz_it.rfanalyzer.digiext.lora.MeshtasticTelemetry
import java.util.Locale

/**
 * <h1>RF Analyzer - Meshtastic Labels</h1>
 *
 * Module:      MeshtasticLabels.kt
 * Description: Lookup tables turning raw Meshtastic protocol numbers (portnum, hwModel, role, and
 *              guessed radio config) into human-readable labels. UI-only: the parsed types
 *              (MeshtasticInfo, LoraPhy) carry the raw numbers this file names.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

private data class PortnumInfo(val label: String, val description: String)

// Meshtastic PortNum enum (portnums.proto), from
// https://raw.githubusercontent.com/meshtastic/protobufs/master/meshtastic/portnums.proto
// as of 2026-08-04. Ranges (the proto's header comment): 0-63 core Meshtastic use, 64-127
// registered 3rd-party apps, 256-511 private/unregistered app development, everything else
// reserved. The 'master' branch: some entries may not exist on older deployed firmware.
private val PORTNUMS: Map<Int, PortnumInfo> = mapOf(
    0 to PortnumInfo("Unknown/Opaque", "Deprecated legacy portnum for messages the mesh doesn't understand"),
    1 to PortnumInfo("Text Message", "Plain UTF-8 text message"),
    2 to PortnumInfo("Remote Hardware", "Built-in GPIO/example app"),
    3 to PortnumInfo("Position", "Position protobuf"),
    4 to PortnumInfo("Node Info", "User protobuf (name, hardware, role)"),
    5 to PortnumInfo("Routing", "Mesh protocol control packet"),
    6 to PortnumInfo("Admin", "Administrative control packet"),
    7 to PortnumInfo("Text Message (Compressed)", "Unishox2-compressed text message"),
    8 to PortnumInfo("Waypoint", "Waypoint protobuf"),
    9 to PortnumInfo("Audio", "Codec2 audio frames (2.4 GHz only)"),
    10 to PortnumInfo("Detection Sensor", "Text from the Detection Sensor module"),
    11 to PortnumInfo("Alert", "Critical alert (like Text Message, higher priority)"),
    12 to PortnumInfo("Key Verification", "Key verification request/response"),
    13 to PortnumInfo("Remote Shell", "Primitive remote shell access"),
    32 to PortnumInfo("Reply/Ping", "Replies to any packet it receives"),
    33 to PortnumInfo("IP Tunnel", "Python IP-tunnel feature"),
    34 to PortnumInfo("Paxcounter", "Paxcounter integration"),
    35 to PortnumInfo("Store & Forward++", "Chained store-forward (Linux native)"),
    36 to PortnumInfo("Node Status", "Extra free-text node status"),
    37 to PortnumInfo("Mesh Beacon", "Beacon-mode channel/preset broadcast"),
    64 to PortnumInfo("Serial Bridge", "Hardware serial bridge (38400 8N1)"),
    65 to PortnumInfo("Store & Forward", "Store & forward module"),
    66 to PortnumInfo("Range Test", "Range-test module (ASCII)"),
    67 to PortnumInfo("Telemetry", "Device/environment telemetry"),
    68 to PortnumInfo("GPS-less Position (ZPS)", "Experimental position estimation without GPS"),
    69 to PortnumInfo("Simulator", "Multi-instance Linux LoRa simulator"),
    70 to PortnumInfo("Traceroute", "Route-discovery traceroute"),
    71 to PortnumInfo("Neighbor Info", "Aggregated per-node neighbor list"),
    72 to PortnumInfo("ATAK Plugin", "Official ATAK plugin payload"),
    73 to PortnumInfo("Map Report", "Unencrypted node info for MQTT-based map"),
    74 to PortnumInfo("Power Stress Test", "Automated power-consumption testing"),
    75 to PortnumInfo("LoRaWAN Bridge", "LoRaWAN uplink transport"),
    76 to PortnumInfo("Reticulum Tunnel", "Reticulum Network Stack tunnel"),
    77 to PortnumInfo("Cayenne LPP", "Cayenne Low Power Payload for LoRaWAN sensors"),
    78 to PortnumInfo("ATAK Plugin V2", "ATAK plugin v2 (TAKPacketV2 compression)"),
    79 to PortnumInfo("Firmware OTA Update", "Signed firmware update transport"),
    112 to PortnumInfo("GroupAlarm", "GroupAlarm integration"),
    256 to PortnumInfo("Private App", "Private/unregistered app development"),
    257 to PortnumInfo("ATAK Forwarder", "ATAK forwarder (libcotshrink)"),
    511 to PortnumInfo("Reserved (MAX)", "Ceiling value for portnum assignment"),
)

fun meshtasticPortnumLabel(portnum: Int): String = PORTNUMS[portnum]?.label ?: when (portnum) {
    in 256..511 -> "Private/unregistered app (#$portnum)"
    in 64..127 -> "Registered 3rd-party app (#$portnum)"
    else -> "Unknown (#$portnum)"
}

fun meshtasticPortnumDescription(portnum: Int): String? = PORTNUMS[portnum]?.description

// Meshtastic HardwareModel enum (mesh.proto), ~144 board/vendor codenames. Source:
// https://raw.githubusercontent.com/meshtastic/protobufs/master/meshtastic/mesh.proto
// fetched 2026-08-04
private val HW_MODELS: Map<Int, String> = mapOf(
    0 to "UNSET", 1 to "TLORA_V2", 2 to "TLORA_V1", 3 to "TLORA_V2_1_1P6", 4 to "TBEAM",
    5 to "HELTEC_V2_0", 6 to "TBEAM_V0P7", 7 to "T_ECHO", 8 to "TLORA_V1_1P3", 9 to "RAK4631",
    10 to "HELTEC_V2_1", 11 to "HELTEC_V1", 12 to "LILYGO_TBEAM_S3_CORE", 13 to "RAK11200",
    14 to "NANO_G1", 15 to "TLORA_V2_1_1P8", 16 to "TLORA_T3_S3", 17 to "NANO_G1_EXPLORER",
    18 to "NANO_G2_ULTRA", 19 to "LORA_TYPE", 20 to "WIPHONE", 21 to "WIO_WM1110",
    22 to "RAK2560", 23 to "HELTEC_HRU_3601", 24 to "HELTEC_WIRELESS_BRIDGE", 25 to "STATION_G1",
    26 to "RAK11310", 27 to "MAKERFABS_TRACKER", 28 to "MAKERFABS_RESERVED", 29 to "CANARYONE",
    30 to "RP2040_LORA", 31 to "STATION_G2", 32 to "LORA_RELAY_V1", 33 to "T_ECHO_PLUS",
    34 to "PPR", 35 to "GENIEBLOCKS", 36 to "NRF52_UNKNOWN", 37 to "PORTDUINO",
    38 to "ANDROID_SIM", 39 to "DIY_V1", 40 to "NRF52840_PCA10059", 41 to "DR_DEV",
    42 to "M5STACK", 43 to "HELTEC_V3", 44 to "HELTEC_WSL_V3", 45 to "BETAFPV_2400_TX",
    46 to "BETAFPV_900_NANO_TX", 47 to "RPI_PICO", 48 to "HELTEC_WIRELESS_TRACKER",
    49 to "HELTEC_WIRELESS_PAPER", 50 to "T_DECK", 51 to "T_WATCH_S3", 52 to "PICOMPUTER_S3",
    53 to "HELTEC_HT62", 54 to "EBYTE_ESP32_S3", 55 to "ESP32_S3_PICO", 56 to "CHATTER_2",
    57 to "HELTEC_WIRELESS_PAPER_V1_0", 58 to "HELTEC_WIRELESS_TRACKER_V1_0", 59 to "UNPHONE",
    60 to "TD_LORAC", 61 to "CDEBYTE_EORA_S3", 62 to "TWC_MESH_V4", 63 to "NRF52_PROMICRO_DIY",
    64 to "RADIOMASTER_900_BANDIT_NANO", 65 to "HELTEC_CAPSULE_SENSOR_V3",
    66 to "HELTEC_VISION_MASTER_T190", 67 to "HELTEC_VISION_MASTER_E213",
    68 to "HELTEC_VISION_MASTER_E290", 69 to "HELTEC_MESH_NODE_T114", 70 to "SENSECAP_INDICATOR",
    71 to "TRACKER_T1000_E", 72 to "RAK3172", 73 to "WIO_E5", 74 to "RADIOMASTER_900_BANDIT",
    75 to "ME25LS01_4Y10TD", 76 to "RP2040_FEATHER_RFM95", 77 to "M5STACK_COREBASIC",
    78 to "M5STACK_CORE2", 79 to "RPI_PICO2", 80 to "M5STACK_CORES3", 81 to "SEEED_XIAO_S3",
    82 to "MS24SF1", 83 to "TLORA_C6", 84 to "WISMESH_TAP", 85 to "ROUTASTIC", 86 to "MESH_TAB",
    87 to "MESHLINK", 88 to "XIAO_NRF52_KIT", 89 to "THINKNODE_M1", 90 to "THINKNODE_M2",
    91 to "T_ETH_ELITE", 92 to "HELTEC_SENSOR_HUB", 93 to "MUZI_BASE", 94 to "HELTEC_MESH_POCKET",
    95 to "SEEED_SOLAR_NODE", 96 to "NOMADSTAR_METEOR_PRO", 97 to "CROWPANEL", 98 to "LINK_32",
    99 to "SEEED_WIO_TRACKER_L1", 100 to "SEEED_WIO_TRACKER_L1_EINK", 101 to "MUZI_R1_NEO",
    102 to "T_DECK_PRO", 103 to "T_LORA_PAGER", 104 to "M5STACK_RESERVED", 105 to "WISMESH_TAG",
    106 to "RAK3312", 107 to "THINKNODE_M5", 108 to "HELTEC_MESH_SOLAR", 109 to "T_ECHO_LITE",
    110 to "HELTEC_V4", 111 to "M5STACK_C6L", 112 to "M5STACK_CARDPUTER_ADV",
    113 to "HELTEC_WIRELESS_TRACKER_V2", 114 to "T_WATCH_ULTRA", 115 to "THINKNODE_M3",
    116 to "WISMESH_TAP_V2", 117 to "RAK3401", 118 to "RAK6421", 119 to "THINKNODE_M4",
    120 to "THINKNODE_M6", 121 to "MESHSTICK_1262", 122 to "TBEAM_1_WATT",
    123 to "T5_S3_EPAPER_PRO", 124 to "TBEAM_BPF", 125 to "MINI_EPAPER_S3",
    126 to "TDISPLAY_S3_PRO", 127 to "HELTEC_MESH_NODE_T096", 128 to "MESH_TRACKER_X1",
    129 to "THINKNODE_M7", 130 to "THINKNODE_M8", 131 to "THINKNODE_M9", 132 to "HELTEC_V4_R8",
    133 to "HELTEC_MESH_NODE_T1", 134 to "STATION_G3", 135 to "T_IMPULSE_PLUS",
    136 to "T_ECHO_CARD", 137 to "SEEED_WIO_TRACKER_L2", 138 to "CROWPANEL_P4",
    139 to "HELTEC_MESH_TOWER_V2", 140 to "MESHNOLOGY_W10", 141 to "HELTEC_RC32",
    142 to "HELTEC_RC52", 143 to "HELTEC_RCC6", 255 to "PRIVATE_HW",
)

fun meshtasticHwModelLabel(hwModel: Int): String =
    HW_MODELS[hwModel]?.replace('_', ' ') ?: "Unknown (#$hwModel)"

// Meshtastic Role enum, nested in DeviceConfig (config.proto). Source:
// https://raw.githubusercontent.com/meshtastic/protobufs/master/meshtastic/config.proto
// fetched 2026-08-04
private val ROLES: Map<Int, String> = mapOf(
    0 to "Client", 1 to "Client (Mute)", 2 to "Router",
    3 to "Router+Client (deprecated v2.3.15)", 4 to "Repeater (deprecated v2.7.11)",
    5 to "Tracker", 6 to "Sensor", 7 to "TAK", 8 to "Client (Hidden)",
    9 to "Lost & Found", 10 to "TAK Tracker", 11 to "Router (Late)", 12 to "Client (Base)",
)

fun meshtasticRoleLabel(role: Int): String = ROLES[role] ?: "Unknown (#$role)"

private data class ModemPreset(val name: String, val spreadingFactor: Int, val bandwidthHz: Int, val codingRate: Int)

// SF/BW/CR per modem preset (https://meshtastic.org/docs/overview/radio-settings/, the
// ModemPreset enum of config.proto). The newer LITE_*/NARROW_*/TINY_*/MEDIUM_TURBO presets are
// not in that table and are left out rather than guessed.
private val MODEM_PRESETS = listOf(
    ModemPreset("Short Turbo", 7, 500_000, 1),
    ModemPreset("Short Fast", 7, 250_000, 1),
    ModemPreset("Short Slow", 8, 250_000, 1),
    ModemPreset("Medium Fast", 9, 250_000, 1),
    ModemPreset("Medium Slow", 10, 250_000, 1),
    ModemPreset("Long Turbo", 11, 500_000, 4),
    ModemPreset("Long Fast", 11, 250_000, 1),
    ModemPreset("Long Moderate", 11, 125_000, 4),
    ModemPreset("Long Slow", 12, 125_000, 4),
)

/** Guessed modem preset from an observed SF/BW/CR combo - each triple is unique in the table,
 *  but a custom Meshtastic channel or another LoRa sender may use the same one. */
fun meshtasticModemPresetGuess(spreadingFactor: Int, bandwidthHz: Int, codingRate: Int): String? =
    MODEM_PRESETS.firstOrNull {
        it.spreadingFactor == spreadingFactor && it.bandwidthHz == bandwidthHz && it.codingRate == codingRate
    }?.name

private data class RegionRange(val name: String, val startHz: Long, val endHz: Long)

// Meshtastic RegionCode frequency ranges (the regions[] table of
// https://github.com/meshtastic/firmware/blob/master/src/mesh/RadioInterface.cpp, as of
// 2026-08-04). Many of them overlap (US/ANZ/PH_915/MY_919 all sit in 915-928 MHz), so a
// frequency can match several regions.
private val REGIONS = listOf(
    RegionRange("US", 902_000_000, 928_000_000),
    RegionRange("EU_433", 433_000_000, 434_000_000),
    RegionRange("EU_868", 869_400_000, 869_650_000),
    RegionRange("CN", 470_000_000, 510_000_000),
    RegionRange("JP", 920_500_000, 923_500_000),
    RegionRange("ANZ", 915_000_000, 928_000_000),
    RegionRange("ANZ_433", 433_050_000, 434_790_000),
    RegionRange("RU", 868_700_000, 869_200_000),
    RegionRange("KR", 920_000_000, 923_000_000),
    RegionRange("TW", 920_000_000, 925_000_000),
    RegionRange("IN", 865_000_000, 867_000_000),
    RegionRange("NZ_865", 864_000_000, 868_000_000),
    RegionRange("TH", 920_000_000, 925_000_000),
    RegionRange("UA_433", 433_000_000, 434_700_000),
    RegionRange("UA_868", 868_000_000, 868_600_000),
    RegionRange("MY_433", 433_000_000, 435_000_000),
    RegionRange("MY_919", 919_000_000, 924_000_000),
    RegionRange("SG_923", 917_000_000, 925_000_000),
    RegionRange("PH_433", 433_000_000, 434_700_000),
    RegionRange("PH_868", 868_000_000, 869_400_000),
    RegionRange("PH_915", 915_000_000, 918_000_000),
    RegionRange("KZ_433", 433_075_000, 434_775_000),
    RegionRange("KZ_863", 863_000_000, 868_000_000),
    RegionRange("NP_865", 865_000_000, 868_000_000),
    RegionRange("BR_902", 902_000_000, 907_500_000),
    RegionRange("LORA_24", 2_400_000_000, 2_483_500_000),
)

/** Guessed Meshtastic region(s) from center frequency: every matching region code
 *  (slash-joined, see [REGIONS]), `null` if the frequency falls in none of them. */
fun meshtasticRegionGuess(frequencyHz: Long): String? {
    val matches = REGIONS.filter { frequencyHz in it.startHz..it.endHz }.map { it.name }
    return matches.takeIf { it.isNotEmpty() }?.joinToString("/")
}

// Routing.Error (mesh.proto).
private val ROUTING_ERRORS: Map<Int, String> = mapOf(
    0 to "ACK", 1 to "No route", 2 to "Got NAK", 3 to "Timeout", 4 to "No interface", 5 to "Max retransmit",
    6 to "No channel", 7 to "Too large", 8 to "No response", 9 to "Duty cycle limit", 32 to "Bad request",
    33 to "Not authorized", 34 to "PKI failed", 35 to "PKI unknown public key", 36 to "Admin bad session key",
    37 to "Admin public key unauthorized", 38 to "Rate limit exceeded",
)

/** "ACK" for error NONE, otherwise the error's name. */
fun meshtasticRoutingErrorLabel(error: Int): String = ROUTING_ERRORS[error] ?: "Error #$error"

/** A node number as Meshtastic writes it ("!1c4977b0"). */
fun meshtasticNodeLabel(nodeNum: Long): String = if (nodeNum == 0xFFFFFFFFL) "Broadcast" else "!%08x".format(Locale.US, nodeNum)

/** Every reading of a telemetry message as label/value pairs, whichever variant it carries. */
fun meshtasticTelemetryReadings(info: MeshtasticTelemetry): List<Pair<String, String>> = buildList {
    fun add(label: String, value: Any?, format: (Any) -> String = { it.toString() }) { if (value != null) add(label to format(value)) }
    fun f1(v: Any) = "%.1f".format(Locale.US, v as Float)
    info.deviceMetrics?.let { m ->
        add("Battery", m.batteryLevel) { if (it == 101) "powered" else "$it%" }
        add("Voltage", m.voltage) { "%.2f V".format(Locale.US, it as Float) }
        add("Ch. util", m.channelUtilization) { f1(it) + " %" }
        add("Air util TX", m.airUtilTx) { f1(it) + " %" }
        add("Uptime", m.uptimeSeconds) { formatMeshtasticUptime(it as Long) }
    }
    info.environmentMetrics?.let { m ->
        add("Temperature", m.temperature) { f1(it) + " °C" }
        add("Humidity", m.relativeHumidity) { f1(it) + " %" }
        add("Pressure", m.barometricPressure) { f1(it) + " hPa" }
        add("Gas resistance", m.gasResistance) { "%.2f MΩ".format(Locale.US, it as Float) }
        add("Voltage", m.voltage) { "%.2f V".format(Locale.US, it as Float) }
        add("Current", m.current) { f1(it) + " mA" }
        add("IAQ", m.iaq)
        add("Distance", m.distance) { f1(it) + " mm" }
        add("Light", m.lux) { f1(it) + " lx" }
        add("Wind", m.windDirection) { "$it°" }
        add("Wind speed", m.windSpeed) { f1(it) + " m/s" }
        add("Gusts", m.windGust) { f1(it) + " m/s" }
        add("Weight", m.weight) { "%.2f kg".format(Locale.US, it as Float) }
        add("Radiation", m.radiation) { f1(it) + " µR/h" }
        add("Rain 1 h", m.rainfall1h) { f1(it) + " mm" }
        add("Rain 24 h", m.rainfall24h) { f1(it) + " mm" }
        add("Soil moisture", m.soilMoisture) { "$it %" }
        add("Soil temperature", m.soilTemperature) { f1(it) + " °C" }
    }
    info.airQualityMetrics?.let { m ->
        add("PM1.0", m.pm10Standard) { "$it µg/m³" }
        add("PM2.5", m.pm25Standard) { "$it µg/m³" }
        add("PM10", m.pm100Standard) { "$it µg/m³" }
        add("PM1.0 env.", m.pm10Environmental) { "$it µg/m³" }
        add("PM2.5 env.", m.pm25Environmental) { "$it µg/m³" }
        add("PM10 env.", m.pm100Environmental) { "$it µg/m³" }
        add("CO₂", m.co2) { "$it ppm" }
    }
    info.powerMetrics?.channels?.forEach { c ->
        add("Ch${c.channel} voltage", c.voltage) { "%.2f V".format(Locale.US, it as Float) }
        add("Ch${c.channel} current", c.current) { f1(it) + " mA" }
    }
    info.localStats?.let { m ->
        add("Uptime", m.uptimeSeconds) { formatMeshtasticUptime(it as Long) }
        add("Ch. util", m.channelUtilization) { f1(it) + " %" }
        add("Air util TX", m.airUtilTx) { f1(it) + " %" }
        add("Nodes online", m.onlineNodes?.let { online -> m.totalNodes?.let { "$online of $it" } ?: online })
        add("Packets TX", m.packetsTx)
        add("Packets RX", m.packetsRx)
        add("RX bad", m.packetsRxBad)
        add("RX duplicates", m.rxDupe)
        add("Relayed", m.txRelay)
        add("Relays cancelled", m.txRelayCanceled)
        add("Noise floor", m.noiseFloor) { "$it dBm" }
    }
    info.healthMetrics?.let { m ->
        add("Heart rate", m.heartBpm) { "$it bpm" }
        add("SpO₂", m.spO2) { "$it %" }
        add("Body temperature", m.temperature) { f1(it) + " °C" }
    }
    info.hostMetrics?.let { m ->
        add("Uptime", m.uptimeSeconds) { formatMeshtasticUptime(it as Long) }
        add("Free memory", m.freeMemBytes) { "${(it as Long) / 1_048_576} MiB" }
        add("Free disk", m.diskFreeBytes) { "${(it as Long) / 1_048_576} MiB" }
        add("Load", m.load1?.let { "%.2f %.2f %.2f".format(Locale.US, it / 100f, (m.load5 ?: 0) / 100f, (m.load15 ?: 0) / 100f) })
        add("Host", m.userString)
    }
}

fun formatMeshtasticUptime(seconds: Long): String {
    val days = seconds / 86400
    val hours = (seconds % 86400) / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        days > 0 -> "${days}d ${hours}h"
        hours > 0 -> "${hours}h ${minutes}m"
        else -> "${minutes}m"
    }
}
