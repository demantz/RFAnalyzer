package com.mantz_it.rfanalyzer.digiext.ft8

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * <h1>RF Analyzer - FT8 Messages</h1>
 *
 * Module:      Ft8Messages.kt
 * Description: What an FT8 message says ([Ft8Parser] unpacks it from the 77 payload bits) - also
 *              the stored `payloadJson`: never rename a class's serial name or a field, new fields
 *              need defaults. [text] is the message as WSJT-X shows it.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Serializable
sealed class Ft8Message {
    abstract val text: String
}

/** i3=0 n3=0: up to 13 characters of free text. */
@Serializable
@SerialName("TextMessage")
data class TextMessage(override val text: String) : Ft8Message()

/** i3=0 n3=5: 71 bits of telemetry, as hex without leading zeros. */
@Serializable
@SerialName("TelemetryMessage")
data class TelemetryMessage(val hex: String = "") : Ft8Message() {
    override val text get() = hex
}

/**
 * i3=1: the standard message - [call1] is the called station (or CQ/QRZ/DE), [call2] the
 * transmitting one, then a grid, a signal report or RRR/RR73/73. [rover1]/[rover2]: "/R".
 */
@Serializable
@SerialName("StandardMessage")
data class StandardMessage(
    val call1: Callsign?,
    val rover1: Boolean,
    val call2: Callsign?,
    val rover2: Boolean,
    val roger: Boolean,
    val report: Report?
) : Ft8Message() {
    override val text get() = standardText(call1, if (rover1) "/R" else "", call2, if (rover2) "/R" else "", roger, report)
}

/** i3=2: the standard message with "/P" instead of "/R" (EU VHF contests). */
@Serializable
@SerialName("EUVHFMessage")
data class EUVHFMessage(
    val call1: Callsign?,
    val portable1: Boolean,
    val call2: Callsign?,
    val portable2: Boolean,
    val roger: Boolean,
    val report: Report?
) : Ft8Message() {
    override val text get() = standardText(call1, if (portable1) "/P" else "", call2, if (portable2) "/P" else "", roger, report)
}

private fun standardText(call1: Callsign?, suffix1: String, call2: Callsign?, suffix2: String, roger: Boolean, report: Report?) = buildString {
    append(call1.text()).append(suffix1).append(' ').append(call2.text()).append(suffix2)
    when (report) {
        null -> {}
        is LocationReport -> append(if (roger) " R " else " ").append(report.grid)
        is SignalReport -> append(if (roger) " R" else " ").append(report)
        else -> append(' ').append(report)
    }
}

private fun Callsign?.text() = this?.toString() ?: "<...>"

/**
 * i3=4: a callsign that doesn't fit the standard 28 bits (up to 11 characters, e.g. "PJ4/K1ABC"),
 * the other one only as a hash. [report]: "", "RRR", "RR73" or "73". A CQ has [call1] "CQ".
 */
@Serializable
@SerialName("NonstandardCallMessage")
data class NonstandardCallMessage(
    val call1: Callsign?,
    val call2: Callsign?,
    val report: OtherReport
) : Ft8Message() {
    override val text get() = buildString {
        append(call1.text()).append(' ').append(call2.text())
        if (report.value.isNotEmpty()) append(' ').append(report.value)
    }
}

/** i3=3: ARRL RTTY Roundup exchange - [signal] the RST (529..599), [state] a state/province or a 4-digit serial. */
@Serializable
@SerialName("RTTYMessage")
data class RTTYMessage(
    val call1: Callsign?,
    val call2: Callsign?,
    val roger: Boolean,
    val signal: RTTYSignal,
    val state: RTTYState,
    val thankYou: Boolean
) : Ft8Message() {
    override val text get() = buildString {
        if (thankYou) append("TU; ")
        append(call1.text()).append(' ').append(call2.text()).append(' ')
        if (roger) append("R ")
        append(signal).append(' ').append(state)
    }
}

/**
 * i3=0 n3=1: a DXpedition (Fox) answering two stations at once - [call1] gets RR73, [call2] a
 * signal [report]; [call3], the DXpedition itself, is only sent as a 10-bit hash.
 */
@Serializable
@SerialName("DXpeditionMessage")
data class DXpeditionMessage(
    val call1: Callsign?,
    val call2: Callsign?,
    val call3: Callsign?,
    val report: Int,
) : Ft8Message() {
    override val text get() = "${call1.text()} RR73; ${call2.text()} ${call3.text()} ${SignalReport(report)}"
}

/** i3=0 n3=3/4: ARRL Field Day exchange - number of [transmitters], the [entryClass] letter and the ARRL/RAC [section]. */
@Serializable
@SerialName("FieldDayMessage")
data class FieldDayMessage(
    val call1: Callsign?,
    val call2: Callsign?,
    val roger: Boolean,
    val transmitters: Int,
    val entryClass: String,
    val section: String,
) : Ft8Message() {
    override val text get() = "${call1.text()} ${call2.text()} ${if (roger) "R " else ""}$transmitters$entryClass $section"
}

/**
 * i3=5: EU VHF contest exchange - both calls as hashes, the RS [report] (52..59), a [serial]
 * number and a 6-character [grid].
 */
@Serializable
@SerialName("EuVhfContestMessage")
data class EuVhfContestMessage(
    val call1: Callsign?,
    val call2: Callsign?,
    val roger: Boolean,
    val report: Int,
    val serial: Int,
    val grid: String,
) : Ft8Message() {
    override val text get() = "${call1.text()} ${call2.text()} ${if (roger) "R " else ""}$report${"%04d".format(Locale.US, serial)} $grid"
}

/** A message type the parser does not unpack (i3=0 n3=2/6/7, i3=6/7) or one with invalid fields - kept so a later parser can. */
@Serializable
@SerialName("UnknownMessage")
data class UnknownMessage(val i3: Int, val n3: Int? = null) : Ft8Message() {
    override val text get() = "(message type $i3${n3?.let { ".$it" } ?: ""})"
}

/**
 * A callsign or token as sent. [token]: CQ, QRZ, DE, "CQ DX", "CQ 123". [fromHash]: only its hash
 * was sent - [callsign] is the call an earlier message had with that hash, or "..." if none.
 */
@Serializable
data class Callsign(
    val callsign: String,
    val pack28: Int? = null,
    val token: Boolean = false,
    val standard: Boolean = false,
    val fromHash: Boolean = false
) {
    override fun toString(): String = if (fromHash) "<$callsign>" else callsign
}

@Serializable
sealed class Report

@Serializable
@SerialName("LocationReport")
data class LocationReport(val grid: String) : Report() {
    override fun toString(): String = grid
}

/** Signal report in dB. */
@Serializable
@SerialName("SignalReport")
data class SignalReport(val value: Int) : Report() {
    override fun toString(): String = String.format(Locale.US, "%+03d", value)
}

/** RRR, RR73, 73. */
@Serializable
@SerialName("OtherReport")
data class OtherReport(val value: String) : Report() {
    override fun toString(): String = value
}

@Serializable
@SerialName("SerialReport")
data class SerialReport(val value: Int) : Report() {
    override fun toString(): String = value.toString()
}

@Serializable
data class RTTYSignal(val value: Int) {
    override fun toString(): String = value.toString()
}

@Serializable
data class RTTYState(val value: String) {
    override fun toString(): String = value
}
