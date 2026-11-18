package com.mantz_it.rfanalyzer.digiext

import com.mantz_it.rfanalyzer.digiext.adsb.AdsbAircraftFamily
import com.mantz_it.rfanalyzer.digiext.adsb.AdsbSpec
import com.mantz_it.rfanalyzer.digiext.ax25.AprsStationFamily
import com.mantz_it.rfanalyzer.digiext.ax25.Ax25Spec
import com.mantz_it.rfanalyzer.digiext.ax25.PacketStationFamily
import com.mantz_it.rfanalyzer.digiext.db.Interpreted
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.PacketSpec
import com.mantz_it.rfanalyzer.digiext.ft8.Ft8Spec
import com.mantz_it.rfanalyzer.digiext.ft8.Ft8StationFamily
import com.mantz_it.rfanalyzer.digiext.k2k.Psk31Spec
import com.mantz_it.rfanalyzer.digiext.k2k.RttySpec
import com.mantz_it.rfanalyzer.digiext.lora.LoraSpec
import com.mantz_it.rfanalyzer.digiext.lora.aprs.LoraAprsStationFamily
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.LoRaWanDeviceFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.MeshComNodeFamily
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagPagerFamily
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagSpec
import com.mantz_it.rfanalyzer.digiext.r09.R09BusFamily
import com.mantz_it.rfanalyzer.digiext.r09.R09Spec
import com.mantz_it.rfanalyzer.digiext.rds.RdsSpec
import com.mantz_it.rfanalyzer.digiext.rds.RdsStationFamily
import com.mantz_it.rfanalyzer.digiext.rds.TmcMessageFamily
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer_ipc.digiext.AdsbPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Ax25Packet
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Ft8Packet
import com.mantz_it.rfanalyzer_ipc.digiext.LoraPacket
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPacket
import com.mantz_it.rfanalyzer_ipc.digiext.R09Packet
import com.mantz_it.rfanalyzer_ipc.digiext.RdsPacket

/**
 * <h1>RF Analyzer - Digital Modes</h1>
 *
 * Module:      Modes.kt
 * Description: The registry of the digital extension's modes: which demodulation mode decodes
 *              which digital mode, which [PacketSpec] handles a mode's packets and which
 *              [ObjectFamily] each kind of tracked object has. Exhaustive `when`s, so a new
 *              demodulation mode, packet class, mode or object kind does not compile until it is
 *              listed here.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The digital mode [this] demodulation mode decodes, or null for an analog one. Broadcast FM is RDS
 *  only while stereo & RDS decoding ([stereoAndRdsEnabled]) is on. */
fun DemodulationMode.digitalMode(stereoAndRdsEnabled: Boolean): DigitalMode? = when (this) {
    DemodulationMode.WFM -> if (stereoAndRdsEnabled) DigitalMode.RDS else null
    DemodulationMode.RTTY -> DigitalMode.RTTY
    DemodulationMode.PSK31 -> DigitalMode.PSK31
    DemodulationMode.R09 -> DigitalMode.R09
    DemodulationMode.FT8 -> DigitalMode.FT8
    DemodulationMode.AX25 -> DigitalMode.AX25
    DemodulationMode.ADSB -> DigitalMode.ADSB
    DemodulationMode.POCSAG -> DigitalMode.POCSAG
    DemodulationMode.LORA -> DigitalMode.LORA
    DemodulationMode.OFF, DemodulationMode.AM, DemodulationMode.NFM, DemodulationMode.LSB, DemodulationMode.USB,
    DemodulationMode.CW -> null
}

/** The demodulation mode that receives [this]: Broadcast FM for RDS. */
fun DigitalMode.demodulationMode(): DemodulationMode = when (this) {
    DigitalMode.RDS -> DemodulationMode.WFM
    DigitalMode.RTTY -> DemodulationMode.RTTY
    DigitalMode.PSK31 -> DemodulationMode.PSK31
    DigitalMode.R09 -> DemodulationMode.R09
    DigitalMode.FT8 -> DemodulationMode.FT8
    DigitalMode.AX25 -> DemodulationMode.AX25
    DigitalMode.ADSB -> DemodulationMode.ADSB
    DigitalMode.POCSAG -> DemodulationMode.POCSAG
    DigitalMode.LORA -> DemodulationMode.LORA
}

/**
 * How a mode is named in the Digital tab: the Demodulation tab's name, full name and description
 * (one source for both pickers), plus what one stored packet is called.
 */
data class ModeNames(val name: String, val fullName: String, val description: String, val packetOne: String, val packetMany: String)

val DigitalMode.names: ModeNames
    get() {
        val demod = demodulationMode()
        return when (this) {
            DigitalMode.RDS -> ModeNames("RDS", "Radio Data System", "FM station names, radio text, traffic", "group", "groups")
            DigitalMode.FT8 -> ModeNames(demod.displayName, demod.fullName, demod.description, "decode", "decodes")
            DigitalMode.POCSAG -> ModeNames(demod.displayName, demod.fullName, demod.description, "page", "pages")
            DigitalMode.R09 -> ModeNames(demod.displayName, demod.fullName, demod.description, "telegram", "telegrams")
            DigitalMode.RTTY, DigitalMode.PSK31 -> ModeNames(demod.displayName, demod.fullName, demod.description, "transmission", "transmissions")
            DigitalMode.AX25, DigitalMode.ADSB, DigitalMode.LORA -> ModeNames(demod.displayName, demod.fullName, demod.description, "frame", "frames")
        }
    }

/** The mode's page of the user manual (docs/, bundled into the app). */
val DigitalMode.manualPage: String
    get() = when (this) {
        DigitalMode.RTTY -> "rtty.html"
        DigitalMode.PSK31 -> "psk31.html"
        DigitalMode.R09 -> "r09.html"
        DigitalMode.FT8 -> "ft8.html"
        DigitalMode.AX25 -> "ax25.html"
        DigitalMode.ADSB -> "adsb.html"
        DigitalMode.POCSAG -> "pocsag.html"
        DigitalMode.LORA -> "lora.html"
        DigitalMode.RDS -> "rds.html"
    }

/**
 * Whether the extension stamps this mode's packets with the time of their samples - a replayed
 * recording's own time - rather than the wall clock when they are decoded. While such a mode is
 * received, the Digital tab counts in the samples' time (see [SourceClock]); the others count in
 * the wall clock, which their packets carry even when a recording replays.
 */
val DigitalMode.stampedWithSampleTime: Boolean
    get() = when (this) {
        DigitalMode.FT8, DigitalMode.LORA -> true
        DigitalMode.AX25, DigitalMode.RDS, DigitalMode.R09, DigitalMode.RTTY, DigitalMode.PSK31,
        DigitalMode.ADSB, DigitalMode.POCSAG -> false
    }

/** The modes in the order of the Demodulation tab's digital list, RDS (part of Broadcast FM) last. */
val DIGITAL_MODE_ORDER: List<DigitalMode> =
    DemodulationMode.entries.filter { it.isDigitalExtension }.mapNotNull { it.digitalMode(stereoAndRdsEnabled = true) } + DigitalMode.RDS

internal fun specOf(mode: DigitalMode): PacketSpec<*, *> = when (mode) {
    DigitalMode.FT8 -> Ft8Spec
    DigitalMode.AX25 -> Ax25Spec
    DigitalMode.RDS -> RdsSpec
    DigitalMode.R09 -> R09Spec
    DigitalMode.RTTY -> RttySpec
    DigitalMode.PSK31 -> Psk31Spec
    DigitalMode.ADSB -> AdsbSpec
    DigitalMode.POCSAG -> PocsagSpec
    DigitalMode.LORA -> LoraSpec
}

/** [packet] as its mode's [StoredPacket][com.mantz_it.rfanalyzer.digiext.db.StoredPacket], stored
 *  with [frequencyHz]: its own, or the tuned frequency when it carries none. */
internal fun interpret(packet: DigitalPacket, frequencyHz: Long): Interpreted<*, *> = when (packet) {
    is Ft8Packet -> Ft8Spec.interpret(packet, frequencyHz)
    is Ax25Packet -> Ax25Spec.interpret(packet, frequencyHz)
    is RdsPacket -> RdsSpec.interpret(packet, frequencyHz)
    is R09Packet -> R09Spec.interpret(packet, frequencyHz)
    is AdsbPacket -> AdsbSpec.interpret(packet, frequencyHz)
    is PocsagPacket -> PocsagSpec.interpret(packet, frequencyHz)
    is LoraPacket -> LoraSpec.interpret(packet, frequencyHz)
}

internal fun familyOf(kind: ObjectKind): ObjectFamily<*> = when (kind) {
    ObjectKind.POCSAG_PAGER -> PocsagPagerFamily
    ObjectKind.ADSB_AIRCRAFT -> AdsbAircraftFamily
    ObjectKind.FT8_STATION -> Ft8StationFamily
    ObjectKind.RDS_STATION -> RdsStationFamily
    ObjectKind.APRS_STATION -> AprsStationFamily
    ObjectKind.PACKET_STATION -> PacketStationFamily
    ObjectKind.R09_BUS -> R09BusFamily
    ObjectKind.MESHCORE_NODE -> MeshCoreNodeFamily
    ObjectKind.MESHTASTIC_NODE -> MeshtasticNodeFamily
    ObjectKind.LORAWAN_DEVICE -> LoRaWanDeviceFamily
    ObjectKind.LORA_APRS_STATION -> LoraAprsStationFamily
    ObjectKind.MESHCOM_NODE -> MeshComNodeFamily
    ObjectKind.TMC_MESSAGE -> TmcMessageFamily
}

/** Every kind of tracked object's family - one working set each in the store writer. */
internal val OBJECT_FAMILIES: List<ObjectFamily<*>> get() = ObjectKind.entries.map(::familyOf)
