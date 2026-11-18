package com.mantz_it.rfanalyzer.digiext.lora.aprs

import com.mantz_it.rfanalyzer.digiext.aprs.AprsInfoParser
import com.mantz_it.rfanalyzer.digiext.aprs.Tnc2FrameParser
import com.mantz_it.rfanalyzer.digiext.lora.LoraAprsPacket

/**
 * <h1>RF Analyzer - LoRa-APRS Parser</h1>
 *
 * Module:      LoraAprsParser.kt
 * Description: OE5BPA-style "LoRa APRS" decode of a raw LoRa PHY payload
 *              (https://github.com/lora-aprs/LoRa_APRS_iGate). Unlike MeshCore/Meshtastic/LoRaWAN
 *              this protocol carries no binary framing of its own beyond a fixed 3-byte magic
 *              header - the payload is otherwise a plain-text TNC2 "monitor format" APRS line - the
 *              text form of an APRS packet, without AX.25 framing.
 *
 * Real-world LoRa-APRS trackers/iGates transmit on the same default sync word (0x12) most MeshCore
 * deployments use, so the magic header + a structural TNC2-header check together serve as the "is
 * this LoRa-APRS" gate (see LoraPayloadDecoder, which tries this parser before its sync-word
 * dispatch for exactly that reason).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object LoraAprsParser {
    private val MAGIC = byteArrayOf(0x3C, 0xFF.toByte(), 0x01)

    fun tryParse(payload: ByteArray): LoraAprsPacket? {
        if (payload.size < MAGIC.size + 1) return null
        for (i in MAGIC.indices) {
            if (payload[i] != MAGIC[i]) return null
        }
        val body = payload.copyOfRange(MAGIC.size, payload.size)
        val header = Tnc2FrameParser.splitHeader(body) ?: return null

        return LoraAprsPacket(
            fromCall = header.fromCall,
            toCall = header.toCall,
            path = header.path,
            info = AprsInfoParser.parseAprsInfo(header.bodyBytes, header.toCall),
        )
    }
}
