package com.mantz_it.rfanalyzer.digiext.lora.aprs

import com.mantz_it.rfanalyzer.digiext.aprs.AprsInfo
import com.mantz_it.rfanalyzer.digiext.aprs.ThirdPartyInfo
import com.mantz_it.rfanalyzer.digiext.aprs.originReport
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrame
import com.mantz_it.rfanalyzer.digiext.ax25.AprsStation
import com.mantz_it.rfanalyzer.digiext.ax25.aprsStationFamily
import com.mantz_it.rfanalyzer.digiext.lora.LoraAprsPacket
import com.mantz_it.rfanalyzer.digiext.lora.loraAprsObjectKey
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore
import com.mantz_it.rfanalyzer.digiext.lora.LORA_IDLE_TIMEOUT_MS

/**
 * <h1>RF Analyzer - LoRa-APRS Tracking</h1>
 *
 * Module:      LoraAprsTracking.kt
 * Description: LoRa-APRS is the same APRS information field as AX.25/APRS, carried over the LoRa
 *              PHY instead of AX.25 HDLC framing, so it folds into the same [AprsStation]. It lives
 *              in its own key namespace ([loraAprsObjectKey]) and has no Object concept: every row
 *              is a station.
 *
 * Relayed (third-party) traffic is folded twice, as on AX.25: the relaying station is heard but
 * takes nothing from the packet it relays, which goes to its originator instead.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
internal suspend fun applyLoraAprsFrame(stations: ObjectStore<AprsStation>, frame: LoraFrame, message: LoraAprsPacket) {
    suspend fun fold(callsign: String, info: AprsInfo) =
        stations.update(loraAprsObjectKey(callsign), frame.timestampMs, frame.knownFrequencyHz, create = { AprsStation(callsign) }) {
            it.withInfo(info)
        }
    fold(message.fromCall, message.info)
    loraAprsOrigin(message)?.let { (origin, report) -> fold(origin, report) }
}

/** The originator of relayed traffic and its report, unless the sender relays its own packet. */
internal fun loraAprsOrigin(message: LoraAprsPacket): Pair<String, AprsInfo>? =
    (message.info as? ThirdPartyInfo)?.originReport()?.takeIf { (origin, _) -> origin != message.fromCall }

internal val LoraAprsStationFamily = aprsStationFamily(ObjectKind.LORA_APRS_STATION, LORA_IDLE_TIMEOUT_MS)
