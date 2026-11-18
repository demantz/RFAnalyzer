package com.mantz_it.rfanalyzer.digiext.rds

import java.util.Locale
import java.time.LocalDateTime
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.ui.OverviewContext
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.CardListPadding
import com.mantz_it.rfanalyzer.digiext.ui.common.DigitalTextStyles
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.Fields
import com.mantz_it.rfanalyzer.digiext.ui.common.LiveTicker
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketCard
import com.mantz_it.rfanalyzer.digiext.ui.common.RawSection
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLines
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerWaiting
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.formatFrequency
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.common.packetActions
import com.mantz_it.rfanalyzer.ui.composable.IconTuneStation
import com.mantz_it.rfanalyzer_ipc.digiext.RdsEvent
import kotlin.math.roundToLong

/**
 * <h1>RF Analyzer - RDS Card</h1>
 *
 * Module:      RdsCard.kt
 * Description: How RDS reads: the group card (one line - the group type and what it said in
 *              words), the station facts, the ticker of the station being received, and the
 *              traffic messages and other networks it announces.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** What one RDS event says, as a label and a value in words. */
internal fun rdsEventField(event: RdsEvent, ptyUs: Boolean): Field = when (event) {
    is RdsEvent.Flags -> Field("Flags", metaLine("Traffic programme".takeIf { event.tp }, "Traffic announcement".takeIf { event.ta }, if (event.music) "Music" else "Speech"))
    is RdsEvent.ProgramService -> Field("Name", event.name.trim())
    is RdsEvent.RadioText -> Field("Radio text", event.text.trim())
    is RdsEvent.ClockTime -> Field("Clock", rdsClockTimeText(event))
    is RdsEvent.AlternativeFrequencies -> Field("Other frequencies", rdsFrequencyListText(event.list, event.complete))
    is RdsEvent.AidAnnouncement -> Field("Data application", "${rdsOdaName(event.aid)} in group ${event.applicationGroup}${if (event.groupTypeA) "A" else "B"}")
    is RdsEvent.AidVariant0 -> Field("TMC", "location table ${event.locationTable}" + if (event.afi) ", alternative frequencies carry it too" else "")
    is RdsEvent.AidVariant1 -> Field("TMC", "service ${event.serviceId}, gap ${event.gapGroups} groups")
    is RdsEvent.TmcTuningInfo -> Field("TMC", "tuning information")
    is RdsEvent.TmcUserMessage -> Field("TMC", "event ${event.eventCode} at location ${event.locationCode}")
    is RdsEvent.TmcContinuation -> Field("TMC", "continuation of message ${event.continuityIndex}")
    is RdsEvent.TmcAssembledMessage -> Field("TMC", "event ${event.eventCode} at location ${event.locationCode}" + if (!event.complete) " (incomplete)" else "")
    is RdsEvent.Eon -> Field("Other network", metaLine(event.ps.trim().ifEmpty { null }, "PI %04X".format(Locale.US, event.pi), event.pty?.let { rdsPtyName(it, ptyUs) }))
    is RdsEvent.EonVariantRaw -> Field("Other network", "PI %04X, data for variant ${event.variant}".format(Locale.US, event.pi))
}

/** A group's events in one line: "Name SWR1 RLP · Music". */
internal fun rdsGroupText(group: RdsGroup, ptyUs: Boolean): String =
    group.data.events.joinToString(" · ") { event ->
        val field = rdsEventField(event, ptyUs)
        when (event) {
            is RdsEvent.Flags, is RdsEvent.RadioText -> field.value
            else -> "${field.label} ${field.value}"
        }
    }

/**
 * One group. One line: its type, what it said, the time. Opened: the events as fields and the
 * four blocks. Groups arrive about 11 times a second, so the card stays one line.
 */
@Composable
fun RdsGroupCard(
    group: RdsGroup,
    expanded: Boolean,
    onExpand: () -> Unit,
    actions: CardActions,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = CardListPadding,
) {
    val ptyUs = LocalRdsPtyUs.current
    PacketCard(
        expanded = expanded,
        onExpand = onExpand,
        timestampMs = group.timestampMs,
        modifier = modifier,
        outerPadding = outerPadding,
        identity = { Text(group.typeName, style = DigitalTextStyles.identity, maxLines = 1) },
        subtitle = rdsGroupText(group, ptyUs).ifEmpty { "PI %04X".format(Locale.US, group.pi) },
        expandedContent = {
            Fields(listOf(Field("Station", "PI %04X · ${rdsPtyName(group.pty, ptyUs)}".format(Locale.US, group.pi))) + group.data.events.map { rdsEventField(it, ptyUs) })
            RawSection("Blocks") { rdsBlocksText(group) }
        },
        actions = packetActions(group, actions),
    )
}

/** The four blocks as hex and bits. */
private fun rdsBlocksText(group: RdsGroup): String = listOf('A', 'B', 'C', 'D').mapIndexed { index, letter ->
    val value = group.block(index)
    "$letter  %04X  ${value.toString(2).padStart(16, '0').chunked(4).joinToString(" ")}".format(Locale.US, value)
}.joinToString("\n")

/** What is known about a station beyond its row. */
internal fun rdsStationFacts(item: TrackedObject<RdsStation>, ptyUs: Boolean): List<Field> = fields {
    val s = item.state
    add("PI", item.key)
    add("Programme", s.pty?.let { rdsPtyName(it, ptyUs) })
    add("Traffic", metaLine("programme".takeIf { s.trafficProgram }, "announcement now".takeIf { s.trafficAnnouncement }).ifEmpty { null })
    add("Content", s.music?.let { if (it) "Music" else "Speech" })
    add("Frequency", item.frequencyHz?.let(::formatFrequency))
    add("Other frequencies", s.alternativeFrequencies.takeIf { it.isNotEmpty() }?.let { rdsFrequencyListText(it, s.alternativeFrequenciesComplete) })
    add("Clock", s.clockTime?.let(::rdsClockTimeText))
    add("Data applications", s.odaApplications.takeIf { it.isNotEmpty() }?.entries?.sortedBy { it.key }?.joinToString(", ") { (group, aid) -> "${rdsOdaName(aid)} ($group)" })
    add("TMC", s.tmcService?.let(::tmcServiceText))
    if (!s.tuned) add("Known from", "other stations' announcements")
}

internal fun tmcServiceText(service: TmcService): String = listOfNotNull(
    service.providerName.trim().takeIf { it.isNotEmpty() },
    service.locationTable?.let { "table $it" },
    service.serviceId?.let { "SID $it" },
    service.scope.takeIf { it.isNotEmpty() }?.joinToString("/") { it.name.lowercase() },
    service.otherNetworks.takeIf { it.isNotEmpty() }?.entries?.joinToString(", ", prefix = "also on ") { (pi, sid) -> "$pi (SID $sid)" },
).joinToString(" · ").ifEmpty { "announced" }

/** The station's local time and its offset: "09.11. 14:03 (UTC+1)". RDS sends the time as UTC plus
 *  the local offset. */
internal fun rdsClockTimeText(ct: RdsEvent.ClockTime): String {
    val offsetStr = if (ct.offset == ct.offset.toLong().toDouble()) "%+d".format(Locale.US, ct.offset.toLong()) else "%+.1f".format(Locale.US, ct.offset)
    val local = runCatching { LocalDateTime.of(ct.year, ct.month, ct.day, ct.hour, ct.min).plusMinutes(Math.round(ct.offset * 60)) }.getOrNull()
        ?: return "%02d.%02d. %02d:%02d UTC".format(Locale.US, ct.day, ct.month, ct.hour, ct.min)
    return "%02d.%02d. %02d:%02d (UTC%s)".format(Locale.US, local.dayOfMonth, local.monthValue, local.hour, local.minute, offsetStr)
}

internal fun rdsFrequencyListText(frequencies: List<Double>, complete: Boolean): String =
    if (frequencies.isEmpty()) {
        if (complete) "none" else "receiving…"
    } else {
        frequencies.joinToString(", ") { "%.1f".format(Locale.US, it) } + " MHz" + if (complete) "" else " (receiving…)"
    }

/** An other-network station's frequencies, each with a button to tune to it. */
@Composable
internal fun EonFrequencyList(eon: RdsEvent.Eon, onTuneToFrequency: (Long) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        if (eon.alternativeFrequencies.isEmpty()) {
            Text(if (eon.afComplete) "No frequencies announced" else "Frequencies: receiving…", style = DigitalTextStyles.faint)
        } else {
            eon.alternativeFrequencies.forEach { freqMHz ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("%.1f MHz".format(Locale.US, freqMHz), style = DigitalTextStyles.value)
                    IconButton(onClick = { onTuneToFrequency((freqMHz * 1_000_000).roundToLong()) }, modifier = Modifier.size(32.dp)) {
                        Icon(IconTuneStation, contentDescription = "Tune to $freqMHz MHz", modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

/** One traffic message: what, where, how urgent, and its details. */
@Composable
internal fun TmcMessageRow(msg: ResolvedTmcMessage) {
    Column(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            msg.eventText,
            style = DigitalTextStyles.value.copy(fontWeight = FontWeight.Medium, color = urgencyColor(msg.urgency) ?: DigitalTextStyles.value.color),
        )
        val details = buildList {
            add("Location ${msg.locationCode}" + if (msg.extent > 0) " (+${msg.extent})" else "")
            msg.direction?.let { add(if (it) "direction +" else "direction −") }
            if (msg.diversionAdvised) add("diversion advised")
            // "+15 minutes" (a duration) or "until midnight" (an end).
            msg.expiryText?.let { add(if (it.startsWith("until")) it else "for ${it.removePrefix("+")}") }
            msg.quantifierText?.takeIf { it !in msg.eventText }?.let { add(it) } // a template already put it into the event text
            msg.lengthOfRouteKm?.let { add("%.0f km".format(Locale.US, it)) }
            msg.speedLimitKmh?.let { add("advised $it km/h") }
            addAll(msg.supplementaryInfo)
            msg.explicitStartTime?.let { add("from $it") }
            msg.explicitStopTime?.let { add("until $it") }
            msg.additionalEvents.forEach { add(it.text) }
            addAll(msg.controlCodes)
        }
        Text(metaLine(listOf(msg.urgency.takeIf { it != "Normal" }) + details), style = DigitalTextStyles.meta)
    }
}

private fun urgencyColor(urgency: String): Color? = when (urgency) {
    "X-Urgent" -> Color(0xFFE57373)
    "Urgent" -> Color(0xFFFFB74D)
    else -> null
}

/**
 * The RDS ticker: the station being received - its name, PI, programme type and flags on line 1,
 * the radio text below. "Listening…" until a station's groups arrive.
 */
@Composable
internal fun RdsTicker(station: TrackedObject<RdsStation>?, context: OverviewContext, lines: Int) {
    val ptyUs = LocalRdsPtyUs.current
    LiveTicker(label = context.tickerLabel(), flashKey = station?.state?.radioText) {
        if (station == null) {
            TickerWaiting("Listening on ${formatFrequency(context.live?.frequencyHz ?: 0)}…", lines)
        } else {
            val s = station.state
            TickerLines(
                lines = lines,
                identity = s.name ?: "PI ${station.key}",
                meta = metaLine(
                    if (s.name != null) "PI ${station.key}" else null,
                    s.pty?.let { rdsPtyName(it, ptyUs) },
                    "TP".takeIf { s.trafficProgram },
                    "TA".takeIf { s.trafficAnnouncement },
                ),
                timestampMs = null,
                payload = s.radioText?.trim()?.ifEmpty { null } ?: "No radio text yet",
            )
        }
    }
}
