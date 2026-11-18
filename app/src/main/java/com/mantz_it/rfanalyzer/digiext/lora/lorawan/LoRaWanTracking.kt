package com.mantz_it.rfanalyzer.digiext.lora.lorawan

import com.mantz_it.rfanalyzer.digiext.lora.LoraFrame
import com.mantz_it.rfanalyzer.digiext.lora.LoRaWanFrame
import com.mantz_it.rfanalyzer.digiext.lora.LoRaWanMType
import com.mantz_it.rfanalyzer.digiext.lora.loraIdentityOf
import kotlinx.serialization.Serializable
import com.mantz_it.rfanalyzer.digiext.lora.LoraModulation
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore
import com.mantz_it.rfanalyzer.digiext.lora.LORA_IDLE_TIMEOUT_MS
import java.util.Locale

/**
 * <h1>RF Analyzer - LoRaWAN Tracking</h1>
 *
 * Module:      LoRaWanTracking.kt
 * Description: The tracked LoRaWAN device: its state ([LoRaWanDevice]) with the row preview and
 *              label, its [LoRaWanDeviceFamily], and the fold of an intact frame into it, which
 *              keeps the device's uplinks and the network's downlinks to it apart.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * A LoRaWAN device, keyed by its DevAddr - or, for a device only seen joining, by its DevEUI. The
 * network assigns the DevAddr in the encrypted JoinAccept, so a joining device and the same device
 * sending data later are two rows. LoRaWAN carries no GPS data at this decode layer, so a device
 * has no position.
 */
@Serializable
data class LoRaWanDevice(
    /** Hex, 8 digits. */
    val devAddr: String? = null,
    /** Hex, 16 digits - set instead of [devAddr] for a device only seen joining. */
    val devEui: String? = null,
    val joinEui: String? = null,
    /** The last uplink's (a frame the device sent) message type - the downlink fields below hold
     *  the network's frames to it, so the two counters and signals don't overwrite each other. */
    val mtype: LoRaWanMType? = null,
    /** The uplink frame counter (FCntUp). */
    val fCnt: Int? = null,
    val lastFPort: Int? = null,
    val modulation: LoraModulation? = null,
    /** SNR of the last uplink, dB - how well the device is heard. */
    val snrDb: Float? = null,
    val downlinkMType: LoRaWanMType? = null,
    /** The downlink frame counter (FCntDown). */
    val downlinkFCnt: Int? = null,
    val downlinkFPort: Int? = null,
    /** SNR of the last downlink, dB - how well its gateway is heard. */
    val downlinkSnrDb: Float? = null,
)

/** The row's one-line preview: the last uplink's message type and port (the last downlink's for a
 *  device only heard being sent to). */
fun LoRaWanDevice.preview(): String? =
    (mtype ?: downlinkMType)?.let { lorawanMTypeLabel(it) + ((if (mtype != null) lastFPort else downlinkFPort)?.let { port -> " · FPort $port" } ?: "") }

internal fun eui(value: Long): String = "%016X".format(Locale.US, value)

/** Only intact frames get here - for a downlink without CRC that means a reliable decode
 *  ([com.mantz_it.rfanalyzer.digiext.lora.isAttributable]). */
internal suspend fun applyLoRaWanFrame(devices: ObjectStore<LoRaWanDevice>, frame: LoraFrame, message: LoRaWanFrame) {
    val id = loraIdentityOf(message)
    if (id.isEmpty()) return
    val create = { LoRaWanDevice(devAddr = message.devAddr, devEui = message.devEui.takeIf { message.devAddr == null }) }
    devices.update(id, frame.timestampMs, frame.knownFrequencyHz, create = create) { device ->
        if (message.mtype.isDownlink) device.copy(
            downlinkMType = message.mtype,
            downlinkFCnt = message.fCnt,
            downlinkFPort = message.fPort,
            downlinkSnrDb = frame.phy.snrDb ?: device.downlinkSnrDb,
        ) else device.copy(
            mtype = message.mtype,
            fCnt = message.fCnt,
            joinEui = message.joinEui ?: device.joinEui,
            lastFPort = message.fPort,
            modulation = LoraModulation.of(frame.phy),
            snrDb = frame.phy.snrDb ?: device.snrDb,
        )
    }
}

/** A device's row label: its DevAddr, or - for one only seen joining - the low half of its DevEUI,
 *  which is the part that differs between devices of one manufacturer. */
internal fun lorawanDeviceName(devAddr: String?, devEui: String?): String = when {
    devAddr != null -> devAddr
    devEui != null -> "EUI …" + devEui.takeLast(8)
    else -> "?"
}

internal fun devAddrHex(value: Long): String = "%08X".format(Locale.US, value)

internal val LoRaWanDeviceFamily = ObjectFamily(
    kind = ObjectKind.LORAWAN_DEVICE,
    serializer = LoRaWanDevice.serializer(),
    displayName = { _, device -> lorawanDeviceName(device.devAddr, device.devEui) },
    idleTimeoutMs = LORA_IDLE_TIMEOUT_MS,
)
