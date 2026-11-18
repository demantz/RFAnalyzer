package com.mantz_it.rfanalyzer.digiext.lora.lorawan

import com.mantz_it.rfanalyzer.digiext.lora.LoRaWanMType
import java.util.Locale

/**
 * <h1>RF Analyzer - LoRaWAN Labels</h1>
 *
 * Module:      LoRaWanLabels.kt
 * Description: Lookup tables turning raw LoRaWAN protocol numbers/fields into human-readable labels
 *              and inferred context. UI-only: the parsed types (LoRaWanFrame, LoRaWanMacCommand)
 *              carry the raw values this file names.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * MType label. MType 110 (binary) is RFU in LoRaWAN 1.0.x (TS001-1.0.4 §4.2), the most deployed
 * version, and Rejoin-Request in LoRaWAN 1.1 (§4.2.1 Table 1); a frame cannot tell, so the label
 * names both.
 */
fun lorawanMTypeLabel(mtype: LoRaWanMType): String = when (mtype) {
    LoRaWanMType.JOIN_REQUEST -> "Join request"
    LoRaWanMType.JOIN_ACCEPT -> "Join accept"
    // Unconfirmed is the default kind of data frame, so it goes without saying.
    LoRaWanMType.UNCONFIRMED_DATA_UP -> "Uplink"
    LoRaWanMType.UNCONFIRMED_DATA_DOWN -> "Downlink"
    LoRaWanMType.CONFIRMED_DATA_UP -> "Confirmed uplink"
    LoRaWanMType.CONFIRMED_DATA_DOWN -> "Confirmed downlink"
    LoRaWanMType.REJOIN_REQUEST -> "RFU (or rejoin request under LoRaWAN 1.1)"
    LoRaWanMType.PROPRIETARY -> "Proprietary"
}

data class NetIdMatch(val netIdHex: String, val operator: String)

private data class NetIdRow(val prefix: Long, val width: Int, val netIdHex: String, val operator: String)

// The LoRa Alliance NetID allocation table, 209 rows as of 2026-08-04:
// https://lora-alliance.org/wp-content/uploads/2022/09/LoRa-Alliance-NetID-Allocation-09092022.pdf
// A snapshot of a living registry - later allocations are missing. `prefix`/`width` are the
// NetID's DevAddr subnet ("<hex prefix>/<width in bits>").
private val NETID_TABLE = listOf(
    NetIdRow(0x00000000L, 7, "0x000000", "Private/experimental nodes"),
    NetIdRow(0x02000000L, 7, "0x000001", "Private/experimental nodes"),
    NetIdRow(0x04000000L, 7, "0x000002", "Actility"),
    NetIdRow(0x06000000L, 7, "0x000003", "Proximus"),
    NetIdRow(0x08000000L, 7, "0x000004", "Swisscom"),
    NetIdRow(0x0A000000L, 7, "0x000005", "Reserved for future use"),
    NetIdRow(0x0C000000L, 7, "0x000006", "Reserved for future use"),
    NetIdRow(0x0E000000L, 7, "0x000007", "Bouygues Telecom"),
    NetIdRow(0x10000000L, 7, "0x000008", "Orbiwise"),
    NetIdRow(0x12000000L, 7, "0x000009", "SENET"),
    NetIdRow(0x14000000L, 7, "0x00000A", "KPN"),
    NetIdRow(0x16000000L, 7, "0x00000B", "EveryNet"),
    NetIdRow(0x18000000L, 7, "0x00000C", "Reserved for future use"),
    NetIdRow(0x1A000000L, 7, "0x00000D", "SK Telecom"),
    NetIdRow(0x1C000000L, 7, "0x00000E", "SagemCom"),
    NetIdRow(0x1E000000L, 7, "0x00000F", "Orange"),
    NetIdRow(0x20000000L, 7, "0x000010", "A2A Smart City"),
    NetIdRow(0x22000000L, 7, "0x000011", "Reserved for future use"),
    NetIdRow(0x24000000L, 7, "0x000012", "Kerlink"),
    NetIdRow(0x26000000L, 7, "0x000013", "The Things Network"),
    NetIdRow(0x28000000L, 7, "0x000014", "Reserved for future use"),
    NetIdRow(0x2A000000L, 7, "0x000015", "Cisco Systems"),
    NetIdRow(0x2C000000L, 7, "0x000016", "Reserved for future use"),
    NetIdRow(0x2E000000L, 7, "0x000017", "MultiTech Systems"),
    NetIdRow(0x30000000L, 7, "0x000018", "Loriot"),
    NetIdRow(0x32000000L, 7, "0x000019", "NNNCo"),
    NetIdRow(0x34000000L, 7, "0x00001A", "Reserved for future use"),
    NetIdRow(0x36000000L, 7, "0x00001B", "Reserved for future use"),
    NetIdRow(0x38000000L, 7, "0x00001C", "Reserved for future use"),
    NetIdRow(0x3A000000L, 7, "0x00001D", "Reserved for future use"),
    NetIdRow(0x3C000000L, 7, "0x00001E", "Reserved for future use"),
    NetIdRow(0x3E000000L, 7, "0x00001F", "Axatel"),
    NetIdRow(0x40000000L, 7, "0x000020", "Reserved for future use"),
    NetIdRow(0x42000000L, 7, "0x000021", "Reserved for future use"),
    NetIdRow(0x44000000L, 7, "0x000022", "Comcast"),
    NetIdRow(0x46000000L, 7, "0x000023", "Ventia"),
    NetIdRow(0x48000000L, 7, "0x000024", "Reserved for future use"),
    NetIdRow(0x4A000000L, 7, "0x000025", "Reserved for future use"),
    NetIdRow(0x4C000000L, 7, "0x000026", "Reserved for future use"),
    NetIdRow(0x4E000000L, 7, "0x000027", "Reserved for future use"),
    NetIdRow(0x50000000L, 7, "0x000028", "Reserved for future use"),
    NetIdRow(0x52000000L, 7, "0x000029", "Reserved for future use"),
    NetIdRow(0x54000000L, 7, "0x00002A", "Reserved for future use"),
    NetIdRow(0x56000000L, 7, "0x00002B", "Reserved for future use"),
    NetIdRow(0x58000000L, 7, "0x00002C", "Reserved for future use"),
    NetIdRow(0x5A000000L, 7, "0x00002D", "Reserved for future use"),
    NetIdRow(0x5C000000L, 7, "0x00002E", "Reserved for future use"),
    NetIdRow(0x5E000000L, 7, "0x00002F", "Reserved for future use"),
    NetIdRow(0x60000000L, 7, "0x000030", "SoftBank"),
    NetIdRow(0x62000000L, 7, "0x000031", "Reserved for future use"),
    NetIdRow(0x64000000L, 7, "0x000032", "Reserved for future use"),
    NetIdRow(0x66000000L, 7, "0x000033", "Reserved for future use"),
    NetIdRow(0x68000000L, 7, "0x000034", "Reserved for future use"),
    NetIdRow(0x6A000000L, 7, "0x000035", "Shenzhen Tencent Computer Systems Company Limited"),
    NetIdRow(0x6C000000L, 7, "0x000036", "Netze BW GmbH"),
    NetIdRow(0x6E000000L, 7, "0x000037", "Tektelic"),
    NetIdRow(0x70000000L, 7, "0x000038", "Charter Communication"),
    NetIdRow(0x72000000L, 7, "0x000039", "Amazon"),
    NetIdRow(0xE0000000L, 15, "0x600000", "RESERVED"),
    NetIdRow(0xE0020000L, 15, "0x600001", "Digita"),
    NetIdRow(0xE0040000L, 15, "0x600002", "Netmore"),
    NetIdRow(0xE0060000L, 15, "0x600003", "QuaeNet Inc."),
    NetIdRow(0xE0080000L, 15, "0x600004", "eleven-x"),
    NetIdRow(0xE00A0000L, 15, "0x600005", "IoT Network AS"),
    NetIdRow(0xE00C0000L, 15, "0x600006", "Reserved for future use"),
    NetIdRow(0xE00E0000L, 15, "0x600007", "EDF"),
    NetIdRow(0xE0100000L, 15, "0x600008", "Unidata"),
    NetIdRow(0xE0120000L, 15, "0x600009", "Reserved for future use"),
    NetIdRow(0xE0140000L, 15, "0x60000A", "Öresundskraft"),
    NetIdRow(0xE0160000L, 15, "0x60000B", "Reserved for future use"),
    NetIdRow(0xE0180000L, 15, "0x60000C", "Reserved for future use"),
    NetIdRow(0xE01A0000L, 15, "0x60000D", "Reserved for future use"),
    NetIdRow(0xE01C0000L, 15, "0x60000E", "Spark"),
    NetIdRow(0xE01E0000L, 15, "0x60000F", "Reserved for future use"),
    NetIdRow(0xE0200000L, 15, "0x600010", "Senet"),
    NetIdRow(0xE0220000L, 15, "0x600011", "Reserved for future use"),
    NetIdRow(0xE0240000L, 15, "0x600012", "Reserved for future use"),
    NetIdRow(0xE0260000L, 15, "0x600013", "Actility"),
    NetIdRow(0xE0280000L, 15, "0x600014", "Kerlink"),
    NetIdRow(0xE02A0000L, 15, "0x600015", "Reserved for future use"),
    NetIdRow(0xE02C0000L, 15, "0x600016", "Cisco"),
    NetIdRow(0xE02E0000L, 15, "0x600017", "Schneider Electric"),
    NetIdRow(0xE0300000L, 15, "0x600018", "Minol ZENNER Connect GmbH"),
    NetIdRow(0xE0320000L, 15, "0x600019", "Reserved for future use"),
    NetIdRow(0xE0340000L, 15, "0x60001A", "NEC Corporation"),
    NetIdRow(0xE0360000L, 15, "0x60001B", "Shenzhen Tencent Computer Systems Company Limited"),
    NetIdRow(0xE0380000L, 15, "0x60001C", "MachineQ/Comcast"),
    NetIdRow(0xE03A0000L, 15, "0x60001D", "NTT (Nippon Telephone and Telegraph)"),
    NetIdRow(0xE03C0000L, 15, "0x60001E", "Reserved for future use"),
    NetIdRow(0xE03E0000L, 15, "0x60001F", "KPN"),
    NetIdRow(0xE0400000L, 15, "0x600020", "Spectrum"),
    NetIdRow(0xE0420000L, 15, "0x600021", "Microshare Inc."),
    NetIdRow(0xE0440000L, 15, "0x600022", "Reserved for future use"),
    NetIdRow(0xE0460000L, 15, "0x600023", "Reserved for future use"),
    NetIdRow(0xE0480000L, 15, "0x600024", "Netze BW GmbH"),
    NetIdRow(0xE04A0000L, 15, "0x600025", "Tektelic"),
    NetIdRow(0xE04C0000L, 15, "0x600026", "Reserved for future use"),
    NetIdRow(0xE04E0000L, 15, "0x600027", "Birdz"),
    NetIdRow(0xE0500000L, 15, "0x600028", "Charter Communication"),
    NetIdRow(0xE0520000L, 15, "0x600029", "Machines Talk"),
    NetIdRow(0xE0540000L, 15, "0x60002A", "Neptune Technology Group"),
    NetIdRow(0xE0560000L, 15, "0x60002B", "Amazon"),
    NetIdRow(0xE0580000L, 15, "0x60002C", "myDevices"),
    NetIdRow(0xE05A0000L, 15, "0x60002D", "Decentralized Wireless Foundation Inc"),
    NetIdRow(0xE05C0000L, 15, "0x60002E", "Eutelsat S.A."),
    NetIdRow(0xFC000000L, 22, "0xC00000", "RESERVED"),
    NetIdRow(0xFC000400L, 22, "0xC00001", "Reserved for future use"),
    NetIdRow(0xFC000800L, 22, "0xC00002", "ResIOT"),
    NetIdRow(0xFC000C00L, 22, "0xC00003", "SYSDEV"),
    NetIdRow(0xFC001000L, 22, "0xC00004", "Reserved for future use"),
    NetIdRow(0xFC001400L, 22, "0xC00005", "Macnica"),
    NetIdRow(0xFC001800L, 22, "0xC00006", "Reserved for future use"),
    NetIdRow(0xFC001C00L, 22, "0xC00007", "Reserved for future use"),
    NetIdRow(0xFC002000L, 22, "0xC00008", "Definium Technologies"),
    NetIdRow(0xFC002400L, 22, "0xC00009", "Reserved for future use"),
    NetIdRow(0xFC002800L, 22, "0xC0000A", "SenseWay"),
    NetIdRow(0xFC002C00L, 22, "0xC0000B", "3S"),
    NetIdRow(0xFC003000L, 22, "0xC0000C", "Reserved for future use"),
    NetIdRow(0xFC003400L, 22, "0xC0000D", "Packetworx"),
    NetIdRow(0xFC003800L, 22, "0xC0000E", "Reserved for future use"),
    NetIdRow(0xFC003C00L, 22, "0xC0000F", "Antenna Hungaria Co"),
    NetIdRow(0xFC004000L, 22, "0xC00010", "Reserved for future use"),
    NetIdRow(0xFC004400L, 22, "0xC00011", "Reserved for future use"),
    NetIdRow(0xFC004800L, 22, "0xC00012", "Netmore"),
    NetIdRow(0xFC004C00L, 22, "0xC00013", "Lyse AS"),
    NetIdRow(0xFC005000L, 22, "0xC00014", "VTC Digicom"),
    NetIdRow(0xFC005400L, 22, "0xC00015", "Machines Talk"),
    NetIdRow(0xFC005800L, 22, "0xC00016", "Schneider Electric"),
    NetIdRow(0xFC005C00L, 22, "0xC00017", "Connexin"),
    NetIdRow(0xFC006000L, 22, "0xC00018", "Minol ZENNER Connect GmbH"),
    NetIdRow(0xFC006400L, 22, "0xC00019", "Telekom Srbija"),
    NetIdRow(0xFC006800L, 22, "0xC0001A", "REQUEA"),
    NetIdRow(0xFC006C00L, 22, "0xC0001B", "Sensor Network Services"),
    NetIdRow(0xFC007000L, 22, "0xC0001C", "Reserved for future use"),
    NetIdRow(0xFC007400L, 22, "0xC0001D", "Boston Networks Limited"),
    NetIdRow(0xFC007800L, 22, "0xC0001E", "Reserved for future use"),
    NetIdRow(0xFC007C00L, 22, "0xC0001F", "mcf88 SRL"),
    NetIdRow(0xFC008000L, 22, "0xC00020", "NEC Corporation"),
    NetIdRow(0xFC008400L, 22, "0xC00021", "Hiber"),
    NetIdRow(0xFC008800L, 22, "0xC00022", "Reserved for future use"),
    NetIdRow(0xFC008C00L, 22, "0xC00023", "Reserved for future use"),
    NetIdRow(0xFC009000L, 22, "0xC00024", "NTT (Nippon Telephone and Telegraph)"),
    NetIdRow(0xFC009400L, 22, "0xC00025", "International Centre for Free and Open Source Software (ICFOSS)"),
    NetIdRow(0xFC009800L, 22, "0xC00026", "Reserved for future use"),
    NetIdRow(0xFC009C00L, 22, "0xC00027", "Reserved for future use"),
    NetIdRow(0xFC00A000L, 22, "0xC00028", "Lacuna Space"),
    NetIdRow(0xFC00A400L, 22, "0xC00029", "Andorra Telecom"),
    NetIdRow(0xFC00A800L, 22, "0xC0002A", "Xiamen Milesight IoT Co., Ltd."),
    NetIdRow(0xFC00AC00L, 22, "0xC0002B", "Grenoble Alps University"),
    NetIdRow(0xFC00B000L, 22, "0xC0002C", "Reserved for future use"),
    NetIdRow(0xFC00B400L, 22, "0xC0002D", "Reserved for future use"),
    NetIdRow(0xFC00B800L, 22, "0xC0002E", "Spectrum"),
    NetIdRow(0xFC00BC00L, 22, "0xC0002F", "Afnic"),
    NetIdRow(0xFC00C000L, 22, "0xC00030", "Reserved for future use"),
    NetIdRow(0xFC00C400L, 22, "0xC00031", "Reserved for future use"),
    NetIdRow(0xFC00C800L, 22, "0xC00032", "Microshare Inc."),
    NetIdRow(0xFC00CC00L, 22, "0xC00033", "HEIG-VD"),
    NetIdRow(0xFC00D000L, 22, "0xC00034", "Reserved for future use"),
    NetIdRow(0xFC00D400L, 22, "0xC00035", "Reserved for future use"),
    NetIdRow(0xFC00D800L, 22, "0xC00036", "Reserved for future use"),
    NetIdRow(0xFC00DC00L, 22, "0xC00037", "Alperia Fiber"),
    NetIdRow(0xFC00E000L, 22, "0xC00038", "First Snow Co., Ltd"),
    NetIdRow(0xFC00E400L, 22, "0xC00039", "Acklio"),
    NetIdRow(0xFC00E800L, 22, "0xC0003A", "Vutility"),
    NetIdRow(0xFC00EC00L, 22, "0xC0003B", "Meshed Pty Ltd"),
    NetIdRow(0xFC00F000L, 22, "0xC0003C", "Birdz"),
    NetIdRow(0xFC00F400L, 22, "0xC0003D", "Arthur D Riley & Co Ltd"),
    NetIdRow(0xFC00F800L, 22, "0xC0003E", "Komro GmbH"),
    NetIdRow(0xFC00FC00L, 22, "0xC0003F", "RSAWEB"),
    NetIdRow(0xFC010000L, 22, "0xC00040", "Ceske Radiokomunikace"),
    NetIdRow(0xFC010400L, 22, "0xC00041", "CM Systems LLC"),
    NetIdRow(0xFC010800L, 22, "0xC00042", "MIOT Melita.io Technology GmbH"),
    NetIdRow(0xFC010C00L, 22, "0xC00043", "PROESYS S.r.l."),
    NetIdRow(0xFC011000L, 22, "0xC00044", "MeWe, Inc"),
    NetIdRow(0xFC011400L, 22, "0xC00045", "Alpha-Omega Technology GmbH & Co. KG"),
    NetIdRow(0xFC011800L, 22, "0xC00046", "SSE Contracting Limited t/a Mayflower Smart Control"),
    NetIdRow(0xFC011C00L, 22, "0xC00047", "VEGA Grieshaber KG"),
    NetIdRow(0xFC012000L, 22, "0xC00048", "Afghan Wireless Communication Company"),
    NetIdRow(0xFC012400L, 22, "0xC00049", "API-K"),
    NetIdRow(0xFC012800L, 22, "0xC0004A", "Decstream LLC"),
    NetIdRow(0xFC012C00L, 22, "0xC0004B", "Nova Track Ltd"),
    NetIdRow(0xFC013000L, 22, "0xC0004C", "IMT Atlantique"),
    NetIdRow(0xFC013400L, 22, "0xC0004D", "Machines Talk"),
    NetIdRow(0xFC013800L, 22, "0xC0004E", "Yosensi Sp. z o.o."),
    NetIdRow(0xFC013C00L, 22, "0xC0004F", "The Saira 2.0, LLC dba The IoT Solutions"),
    NetIdRow(0xFC014000L, 22, "0xC00050", "Neptune Technology Group"),
    NetIdRow(0xFC014400L, 22, "0xC00051", "myDevices"),
    NetIdRow(0xFC014800L, 22, "0xC00052", "Savoie Mont Blanc University"),
    NetIdRow(0xFC014C00L, 22, "0xC00053", "Decentralized Wireless Foundation Inc"),
    NetIdRow(0xFC015000L, 22, "0xC00054", "X-Telia Group Inc"),
    NetIdRow(0xFC015400L, 22, "0xC00055", "Deviceroy"),
    NetIdRow(0xFC015800L, 22, "0xC00056", "Eutelsat S.A."),
    NetIdRow(0xFC015C00L, 22, "0xC00057", "Bejing Dingtek Technology Corp. Ltd"),
    NetIdRow(0xFC016000L, 22, "0xC00058", "The Things Network Foundation"),
    NetIdRow(0xFC016400L, 22, "0xC00059", "Quandify AB"),
    NetIdRow(0xFC016800L, 22, "0xC0005A", "Hutchison Drei Austria GmbH"),
    NetIdRow(0xFC016C00L, 22, "0xC0005B", "Agrology, PBC"),
    NetIdRow(0xFC017000L, 22, "0xC0005C", "mhascaro GmbH"),
    NetIdRow(0xFC017400L, 22, "0xC0005D", "Log5 Data"),
    NetIdRow(0xFC017800L, 22, "0xC0005E", "Citysens GmbH"),
    NetIdRow(0xFC017C00L, 22, "0xC0005F", "Wyld Networks"),
    NetIdRow(0xFE000000L, 21, "0xE00000", "RESERVED"),
    NetIdRow(0xFE000800L, 21, "0xE00010", "RESERVED"),
    NetIdRow(0xFE001000L, 21, "0xE00020", "Techtenna b.v."),
    NetIdRow(0xFE001800L, 21, "0xE00030", "LNX Solutions"),
    NetIdRow(0xFE002000L, 21, "0xE00040", "Cometa Sas"),
    NetIdRow(0xFE002800L, 21, "0xE00050", "Senwize B.V."),
    NetIdRow(0xFE003000L, 21, "0xE00060", "Intersaar GmbH"),
    NetIdRow(0xFE003800L, 21, "0xE00070", "Wyld Networks"),
)

/**
 * Looks up which LoRa Alliance NetID (and therefore network operator) a parsed DevAddr's
 * prefix belongs to. The rows do not overlap, so at most one matches; `null` for a prefix
 * without a row (not allocated, or allocated after the snapshot).
 */
fun lorawanNetIdLookup(devAddr: Long): NetIdMatch? {
    val addr = devAddr and 0xFFFFFFFFL
    val row = NETID_TABLE.firstOrNull { row ->
        val mask = if (row.width == 0) 0L else (0xFFFFFFFFL shl (32 - row.width)) and 0xFFFFFFFFL
        (addr and mask) == (row.prefix and mask)
    }
    return row?.let { NetIdMatch(it.netIdHex, it.operator) }
}

private data class FreqBand(val label: String, val startHz: Long, val endHz: Long)

// Coarse regulatory bands (RP002-1.0.3, ETSI EN 300 220, FCC Part 15.247/15.249). Between 863 and
// 928 MHz several regions' bands overlap; frequency alone cannot tell them apart, so those
// buckets name every plausible region.
private val REGULATORY_BANDS = listOf(
    FreqBand("EU433", 433_000_000, 434_800_000),
    FreqBand("EU868/RU864/IN865 (863-870 MHz)", 863_000_000, 870_000_000),
    FreqBand("US915 (902-915 MHz)", 902_000_000, 915_000_000),
    FreqBand("US915/AU915/AS923/KR920 (915-928 MHz, region ambiguous)", 915_000_000, 928_000_000),
    FreqBand("CN470", 470_000_000, 510_000_000),
    FreqBand("2.4 GHz ISM (LoRa 2.4G)", 2_400_000_000, 2_483_500_000),
)

/** Coarse regulatory-band guess from center frequency alone. `null` if nothing matches. */
fun lorawanRegulatoryBandLabel(frequencyHz: Long): String? =
    REGULATORY_BANDS.firstOrNull { frequencyHz in it.startHz..it.endHz }?.label

private data class Eu868DataRate(val label: String, val spreadingFactor: Int, val bandwidthHz: Int)

// The EU868 LoRa data rates DR0-DR6 (RP002-1.0.3 Table 8); DR7 is FSK. Other regions are not
// covered.
private val EU868_DATA_RATES = listOf(
    Eu868DataRate("DR0", 12, 125_000),
    Eu868DataRate("DR1", 11, 125_000),
    Eu868DataRate("DR2", 10, 125_000),
    Eu868DataRate("DR3", 9, 125_000),
    Eu868DataRate("DR4", 8, 125_000),
    Eu868DataRate("DR5", 7, 125_000),
    Eu868DataRate("DR6", 7, 250_000),
)

/** Guessed EU868 Data Rate index from SF/BW, EU868 only. `null` if no DR0-DR6 row matches. */
fun eu868DataRateLabel(spreadingFactor: Int, bandwidthHz: Int): String? =
    EU868_DATA_RATES.firstOrNull { it.spreadingFactor == spreadingFactor && it.bandwidthHz == bandwidthHz }?.label

/**
 * Name of a LoRaWAN MAC command (FOpts CID, TS001-1.0.4 §5 Table 14) by direction: one CID is
 * the Req in one direction and the Ans in the other. Only the commands LoRaWanParser can walk
 * reach this; the rest stay in LoRaWanFrame.fOptsUndecoded.
 */
fun lorawanMacCommandLabel(cid: Int, isDownlink: Boolean): String = when (cid to isDownlink) {
    0x02 to false -> "LinkCheckReq"
    0x02 to true -> "LinkCheckAns"
    0x03 to false -> "LinkADRAns"
    0x03 to true -> "LinkADRReq"
    0x04 to false -> "DutyCycleAns"
    0x04 to true -> "DutyCycleReq"
    0x05 to false -> "RXParamSetupAns"
    0x05 to true -> "RXParamSetupReq"
    0x06 to false -> "DevStatusAns"
    0x06 to true -> "DevStatusReq"
    0x07 to false -> "NewChannelAns"
    0x07 to true -> "NewChannelReq"
    0x08 to false -> "RXTimingSetupAns"
    0x08 to true -> "RXTimingSetupReq"
    0x09 to false -> "TXParamSetupAns"
    0x09 to true -> "TXParamSetupReq"
    0x0A to false -> "DlChannelAns"
    0x0A to true -> "DlChannelReq"
    0x0D to false -> "DeviceTimeReq"
    0x0D to true -> "DeviceTimeAns"
    else -> "CID 0x%02X".format(Locale.US, cid)
}

// IEEE OUI / MA-M / MA-S blocks of LoRaWAN device and module makers, network servers and join
// servers - a curated subset of the IEEE registry (taken from Wireshark's `manuf` table,
// 2026-10-20). DevEUIs, and often JoinEUIs, start with their maker's block.
private val EUI_VENDORS: Map<String, String> = mapOf(
    "000800" to "MultiTech", "008000" to "MultiTech", "000DB5" to "GlobalSat", "0CF0B4" to "GlobalSat",
    "00137A" to "Netvox", "0013EA" to "Kamstrup", "001616" to "Browan", "0016C0" to "Semtech",
    "0018B2" to "Adeunis", "00233D" to "Laird", "0004A3" to "Microchip", "001EC0" to "Microchip",
    "04B648" to "Zenner", "10DCB65" to "Milesight", "1CC316" to "Milesight", "24E124" to "Milesight",
    "C0BA1F" to "Milesight", "20635F" to "Abeeway", "2CF7F1" to "Seeed Studio", "485A3F" to "Wisol",
    "702C1F" to "Wisol", "48E6C6D" to "Digital Matter", "70B3D5705" to "Digital Matter", "58A0CB" to "TrackNet",
    "647FDA" to "Tektelic", "7076FF" to "Kerlink", "70B3D5078" to "OrbiWise", "70B3D52C0" to "Sensative",
    "70B3D540F" to "Nexelec", "70B3D5475" to "Ewattch", "70B3D5499" to "Pycom", "70B3D571B" to "Elsys",
    "70B3D5770" to "Strega", "70B3D57BA" to "Decentlab", "70B3D57ED" to "The Things Network",
    "70B3D58FF" to "IMST", "70B3D59BA" to "ATIM", "70B3D5BF6" to "comtac", "70B3D5F3F" to "comtac",
    "BC97401" to "comtac", "70B3D5E75" to "Watteco", "787264E" to "Heltec", "904E915" to "Enginko",
    "E8F928" to "Enginko", "94193A" to "Elvaco", "94A40C" to "Diehl Metering", "702E80" to "Diehl",
    "A4DA224" to "LORIOT", "A84041" to "Dragino", "A8610A" to "Arduino", "AC1F09" to "RAKwireless",
    "EC656E" to "The Things Industries", "F03D29" to "Actility",
)

/** The maker an EUI-64 (16 hex digits, most significant first) was assigned to, if it is one of
 *  [EUI_VENDORS]: the longest matching block (36, 28, then 24 bits). */
fun lorawanEuiVendor(eui: String): String? {
    val hex = eui.uppercase()
    return EUI_VENDORS[hex.take(9)] ?: EUI_VENDORS[hex.take(7)] ?: EUI_VENDORS[hex.take(6)]
}
