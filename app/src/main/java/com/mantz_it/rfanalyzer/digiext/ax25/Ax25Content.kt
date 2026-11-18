package com.mantz_it.rfanalyzer.digiext.ax25

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * <h1>RF Analyzer - AX.25 Content</h1>
 *
 * Module:      Ax25Content.kt
 * Description: What an AX.25 frame carries when it is not APRS - the packet-radio traffic heard on
 *              HF and VHF besides APRS (e.g. on Net105, 14.105 MHz LSB): BBS and node beacons,
 *              connected-mode sessions.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Serializable
sealed class Ax25Content {
    /** A mailbox's beacon "Mail for: CALL CALL …": the callsigns with mail waiting there. */
    @Serializable
    @SerialName("mail")
    data class MailBeacon(val callsigns: List<String>) : Ax25Content()

    /** An FBB BBS's unproto list (sent to "FBB"): the messages and bulletins it just received. */
    @Serializable
    @SerialName("fbb")
    data class BulletinList(val entries: List<FbbEntry>) : Ax25Content()

    /** A NET/ROM routing broadcast (PID 0xCF): the sending node's alias and the routes it offers. */
    @Serializable
    @SerialName("netrom")
    data class NetRomNodes(val alias: String, val routes: List<NetRomRoute>) : Ax25Content()

    /** Readable text: a beacon, a node's ID, the text of a connected session. */
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : Ax25Content()
}

/** One line of an FBB unproto list: "49642  B   1260 EQUAK  HP2DFA 261025 EARTHQUAKE ALERT …". */
@Serializable
data class FbbEntry(
    val number: Long,
    /** B = bulletin, P = personal, T = NTS traffic. */
    val type: Char,
    val sizeBytes: Int,
    /** Addressee, with the distribution after '@' when there is one. */
    val to: String,
    val from: String,
    /** YYMMDD as sent. */
    val date: String,
    val title: String,
)

/** One destination of a NET/ROM routing broadcast. */
@Serializable
data class NetRomRoute(val callsign: String, val alias: String, val neighbour: String, val quality: Int)

/**
 * The frame type from the control field (AX.25 2.2 §4.3; modulo 8 - the modulo-128 form is
 * negotiated per connection and cannot be told from one frame). [command] comes from the C bits of
 * the destination and source SSID octets; null for AX.25 1.x frames (both bits equal).
 */
data class Ax25FrameType(
    /** I, RR, RNR, REJ, SREJ, SABM, SABME, DISC, DM, UA, FRMR, UI, XID, TEST or "U?". */
    val name: String,
    /** N(S) of an I frame. */
    val ns: Int? = null,
    /** N(R) of an I or S frame. */
    val nr: Int? = null,
    val pollFinal: Boolean = false,
    val command: Boolean? = null,
) {
    val isUi: Boolean get() = name == "UI"
    val isInformation: Boolean get() = name == "I"

    /** "SABM", "I S2 R5", "RR R3", with a set P/F bit as " P" (command) or " F" (response). */
    val label: String
        get() = buildString {
            append(name)
            ns?.let { append(" S").append(it) }
            nr?.let { append(" R").append(it) }
            if (pollFinal) append(if (command == false) " F" else " P")
        }

    /** What the frame does in a connection, for a card. */
    val meaning: String?
        get() = when (name) {
            "SABM", "SABME" -> "connect request"
            "UA" -> "acknowledged"
            "DISC" -> "disconnect"
            "DM" -> "disconnected mode (refused)"
            "FRMR" -> "frame rejected"
            "RR" -> "ready to receive"
            "RNR" -> "busy"
            "REJ", "SREJ" -> "reject (resend)"
            "I" -> "connected data"
            "UI" -> "unconnected data"
            else -> null
        }
}

/** Decodes the frame type of a frame's 1-octet control field. */
fun ax25FrameType(control: Int, destination: Ax25Address?, source: Ax25Address?): Ax25FrameType {
    // the H bit position of destination/source is their C bit (only digipeaters have an H bit)
    val command = if (destination != null && source != null && destination.hasBeenRepeated != source.hasBeenRepeated) destination.hasBeenRepeated else null
    val pf = control and 0x10 != 0
    return when {
        control and 0x01 == 0 -> Ax25FrameType("I", ns = (control shr 1) and 7, nr = (control shr 5) and 7, pollFinal = pf, command = command)
        control and 0x03 == 0x01 -> Ax25FrameType(
            listOf("RR", "RNR", "REJ", "SREJ")[(control shr 2) and 3], nr = (control shr 5) and 7, pollFinal = pf, command = command)
        else -> Ax25FrameType(
            when (control and 0xEF) {
                0x2F -> "SABM"; 0x6F -> "SABME"; 0x43 -> "DISC"; 0x0F -> "DM"; 0x63 -> "UA"
                0x87 -> "FRMR"; 0x03 -> "UI"; 0xAF -> "XID"; 0xE3 -> "TEST"; else -> "U?"
            },
            pollFinal = pf, command = command,
        )
    }
}

/** The non-APRS information fields known from the air, see [Ax25Content]. */
object Ax25ContentParser {
    private const val PID_NETROM = 0xCF
    private val MAIL_FOR = Regex("""^\s*mail\s+for\s*:(.*)$""", setOf(RegexOption.IGNORE_CASE))
    private val CALLSIGN = Regex("""[A-Za-z0-9]{1,6}(-\d{1,2})?""")
    private val FBB_LINE = Regex("""^\s*(\d+)\s+([A-Z])\s+(\d+)\s+(\S+)\s+(\S+)\s+(\d{6})\s*(.*)$""")

    fun parse(destination: Ax25Address?, pid: Int?, info: ByteArray): Ax25Content? {
        if (info.isEmpty()) return null
        if (pid == PID_NETROM) return netRom(info)
        val text = decodeText(info).trimEnd('\r', '\n', ' ', '\u0000')
        MAIL_FOR.find(text.lineSequence().first())?.let { m ->
            // "Mail for: None" (BPQ32) - nobody
            return Ax25Content.MailBeacon(m.groupValues[1].trim().split(Regex("\\s+")).filter { CALLSIGN.matches(it) && !it.equals("none", ignoreCase = true) })
        }
        val lines = text.split('\r', '\n').filter { it.isNotBlank() }
        val entries = lines.mapNotNull { FBB_LINE.matchEntire(it)?.let(::fbbEntry) }
        if (entries.isNotEmpty() && (entries.size == lines.size || destination?.callsign == "FBB")) return Ax25Content.BulletinList(entries)
        return if (readable(text)) Ax25Content.Text(text) else null
    }

    private fun fbbEntry(m: MatchResult): FbbEntry? {
        val g = m.groupValues
        return FbbEntry(g[1].toLongOrNull() ?: return null, g[2][0], g[3].toIntOrNull() ?: return null, g[4], g[5], g[6], g[7].trim())
    }

    /** 0xFF, the 6-character alias of the sender, then 21 octets per route. */
    private fun netRom(info: ByteArray): Ax25Content? {
        if (info.size < 7 || info[0] != 0xFF.toByte()) return null
        val alias = ascii(info, 1, 6)
        val routes = (7 until info.size - 20 step 21).map { o ->
            NetRomRoute(callsign = shifted(info, o), alias = ascii(info, o + 7, 6), neighbour = shifted(info, o + 13), quality = info[o + 20].toInt() and 0xFF)
        }
        return Ax25Content.NetRomNodes(alias, routes)
    }

    /** UTF-8 when the octets are valid UTF-8 (newer BBS software), Latin-1 otherwise. */
    fun decodeText(info: ByteArray): String = try {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(info)).toString()
    } catch (e: CharacterCodingException) {
        String(info, Charsets.ISO_8859_1)
    }

    private fun ascii(b: ByteArray, from: Int, len: Int) = String(b, from, len, Charsets.ISO_8859_1).trim()

    /** An AX.25 address subfield (callsign shifted left by one, then the SSID octet) as "CALL-SSID". */
    private fun shifted(b: ByteArray, from: Int): String {
        val call = (0 until 6).map { ((b[from + it].toInt() and 0xFF) shr 1).toChar() }.joinToString("").trim()
        val ssid = (b[from + 6].toInt() shr 1) and 0x0F
        return if (ssid == 0) call else "$call-$ssid"
    }

    /** Mostly printable: text, not a binary transfer (FBB's compressed forwarding, YAPP). */
    private fun readable(text: String): Boolean {
        if (text.isEmpty()) return false
        val ok = text.count { it == '\r' || it == '\n' || it == '\t' || it.code in 0x20..0x7E || it.code >= 0xA0 }
        return ok >= 0.9 * text.length
    }
}
