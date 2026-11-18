package com.mantz_it.rfanalyzer.digiext.lora.lorawan

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer.digiext.lora.LoRaWanFrame
import com.mantz_it.rfanalyzer.digiext.lora.LoRaWanMType
import com.mantz_it.rfanalyzer.digiext.lora.LoRaWanMacCommand

/**
 * <h1>RF Analyzer - LoRaWAN Parser</h1>
 *
 * Module:      LoRaWanParser.kt
 * Description: Structural decode of a LoRa PHY payload locked with sync word 0x34 (the LoRaWAN
 *              public-network sync word; LoRaWAN L2 1.0.x specification). MHDR and, for Data
 *              messages, the FHDR (DevAddr/FCtrl/FCnt) are sent in clear; FRMPayload and the MIC
 *              need AppSKey/NwkSKey (Data) or AppKey (Join*), which this app doesn't have, so they
 *              stay opaque in [LoRaWanFrame.encryptedTail]. Without MIC verification, structural
 *              validity is the only gate.
 *
 * A JoinRequest is cleartext and fixed-size, so its JoinEUI/DevEUI/DevNonce are decoded.
 * Rejoin/Proprietary bodies are not decoded field by field (only mtype/major), and JoinAccept's
 * body is encrypted with the AppKey.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object LoRaWanParser {
    private const val MHDR_SIZE = 1
    private const val DEV_ADDR_SIZE = 4
    private const val FCTRL_SIZE = 1
    private const val FCNT_SIZE = 2
    private const val MIC_SIZE = 4
    private const val DATA_MIN_SIZE = MHDR_SIZE + DEV_ADDR_SIZE + FCTRL_SIZE + FCNT_SIZE + MIC_SIZE // 12
    private const val EUI_SIZE = 8
    private const val DEV_NONCE_SIZE = 2
    private const val JOIN_REQUEST_SIZE = MHDR_SIZE + 2 * EUI_SIZE + DEV_NONCE_SIZE + MIC_SIZE // 23

    private val MTYPES = arrayOf(
        LoRaWanMType.JOIN_REQUEST, LoRaWanMType.JOIN_ACCEPT,
        LoRaWanMType.UNCONFIRMED_DATA_UP, LoRaWanMType.UNCONFIRMED_DATA_DOWN,
        LoRaWanMType.CONFIRMED_DATA_UP, LoRaWanMType.CONFIRMED_DATA_DOWN,
        LoRaWanMType.REJOIN_REQUEST, LoRaWanMType.PROPRIETARY,
    )

    // MAC command payload lengths (bytes after the 1-byte CID), keyed by (cid to isDownlink).
    // FOpts is sent in clear in LoRaWAN 1.0.x (1.1 encrypts it), so the commands can be walked
    // without keys. At a CID not listed here (0x0B/0x0C/0x0E/0x0F RFU, 0x10+ Class B/C/RFU/
    // proprietary) walking stops rather than guessing a length.
    private val MAC_COMMAND_LENGTHS: Map<Pair<Int, Boolean>, Int> = mapOf(
        (0x02 to false) to 0, (0x02 to true) to 2,   // LinkCheckReq(up)/Ans(down)
        (0x03 to false) to 1, (0x03 to true) to 4,   // LinkADRAns(up)/Req(down)
        (0x04 to false) to 0, (0x04 to true) to 1,   // DutyCycleAns(up)/Req(down)
        (0x05 to false) to 1, (0x05 to true) to 4,   // RXParamSetupAns(up)/Req(down)
        (0x06 to false) to 2, (0x06 to true) to 0,   // DevStatusAns(up)/Req(down)
        (0x07 to false) to 1, (0x07 to true) to 5,   // NewChannelAns(up)/Req(down)
        (0x08 to false) to 0, (0x08 to true) to 1,   // RXTimingSetupAns(up)/Req(down)
        (0x09 to false) to 0, (0x09 to true) to 1,   // TXParamSetupAns(up)/Req(down)
        (0x0A to false) to 1, (0x0A to true) to 4,   // DlChannelAns(up)/Req(down)
        (0x0D to false) to 0, (0x0D to true) to 5,   // DeviceTimeReq(up)/Ans(down)
    )

    /**
     * Walks FOpts as a sequence of `cid(1B) | command payload` entries as far as
     * unambiguous (recognized cid+direction combos in [MAC_COMMAND_LENGTHS]); stops at
     * the first unrecognized cid and returns the remainder unparsed, rather than guessing.
     */
    private fun walkFOpts(fOptsBytes: ByteArray, isDownlink: Boolean): Pair<List<LoRaWanMacCommand>, Bytes> {
        val commands = mutableListOf<LoRaWanMacCommand>()
        var pos = 0
        while (pos < fOptsBytes.size) {
            val cid = fOptsBytes[pos].toInt() and 0xFF
            val length = MAC_COMMAND_LENGTHS[cid to isDownlink] ?: break
            if (pos + 1 + length > fOptsBytes.size) break
            commands.add(LoRaWanMacCommand(cid = cid, payload = Bytes(fOptsBytes.copyOfRange(pos + 1, pos + 1 + length))))
            pos += 1 + length
        }
        val undecoded = if (pos < fOptsBytes.size) Bytes(fOptsBytes.copyOfRange(pos, fOptsBytes.size)) else Bytes.EMPTY
        return commands to undecoded
    }

    fun tryParse(payload: ByteArray): LoRaWanFrame? {
        if (payload.isEmpty()) return null
        val mhdr = payload[0].toInt() and 0xFF
        val mtype = MTYPES[(mhdr ushr 5) and 0x7]
        val major = mhdr and 0x3

        val body = Bytes(payload.copyOfRange(MHDR_SIZE, payload.size))
        return when {
            mtype.isData -> parseDataFrame(payload, mtype, major)
            // Any other length is not a JoinRequest as specified; it stays opaque rather than guessed at.
            mtype == LoRaWanMType.JOIN_REQUEST && payload.size == JOIN_REQUEST_SIZE -> LoRaWanFrame(
                mtype = mtype, major = major,
                joinEui = eui(readUInt64LE(payload, MHDR_SIZE)),
                devEui = eui(readUInt64LE(payload, MHDR_SIZE + EUI_SIZE)),
                devNonce = readUInt16LE(payload, MHDR_SIZE + 2 * EUI_SIZE),
                encryptedTail = body,
            )
            else -> LoRaWanFrame(mtype = mtype, major = major, encryptedTail = body)
        }
    }

    private fun parseDataFrame(payload: ByteArray, mtype: LoRaWanMType, major: Int): LoRaWanFrame? {
        if (payload.size < DATA_MIN_SIZE) return null

        var pos = MHDR_SIZE
        val devAddr = readUInt32LE(payload, pos)
        pos += DEV_ADDR_SIZE

        val fctrl = payload[pos].toInt() and 0xFF
        pos += FCTRL_SIZE
        val adr = (fctrl ushr 7) and 1 != 0
        val ack = (fctrl ushr 5) and 1 != 0
        val fPending = (fctrl ushr 4) and 1 != 0
        val fOptsLen = fctrl and 0xF

        val fCnt = readUInt16LE(payload, pos)
        pos += FCNT_SIZE

        // FOpts (fOptsLen bytes) + whatever follows (FPort+FRMPayload, if any) + MIC must
        // fit exactly - reject if the declared FOptsLen makes that impossible.
        if (pos + fOptsLen + MIC_SIZE > payload.size) return null

        val tailStart = pos // FOpts+FPort+FRMPayload+MIC, all left opaque (FOpts is also walked below)
        val fOptsBytes = payload.copyOfRange(pos, pos + fOptsLen)
        val (fOpts, fOptsUndecoded) = walkFOpts(fOptsBytes, isDownlink = mtype.isDownlink)
        val remainingAfterFOpts = payload.size - (pos + fOptsLen) - MIC_SIZE
        val fPort = if (remainingAfterFOpts >= 1) payload[pos + fOptsLen].toInt() and 0xFF else null

        return LoRaWanFrame(
            mtype = mtype,
            major = major,
            devAddr = devAddrHex(devAddr),
            adr = adr,
            ack = ack,
            fPending = fPending,
            fOptsLen = fOptsLen,
            fCnt = fCnt,
            fPort = fPort,
            fOpts = fOpts,
            fOptsUndecoded = fOptsUndecoded,
            encryptedTail = Bytes(payload.copyOfRange(tailStart, payload.size)),
        )
    }

    private fun readUInt32LE(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
                ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private fun readUInt64LE(b: ByteArray, off: Int): Long =
        readUInt32LE(b, off) or (readUInt32LE(b, off + 4) shl 32)

    private fun readUInt16LE(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
}
