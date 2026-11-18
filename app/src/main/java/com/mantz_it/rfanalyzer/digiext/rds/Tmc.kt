package com.mantz_it.rfanalyzer.digiext.rds

import com.mantz_it.rfanalyzer_ipc.digiext.RdsEvent
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * <h1>RF Analyzer - RDS-TMC Message Resolution</h1>
 *
 * Module:      Tmc.kt
 * Description: RDS-TMC (ALERT-C) message resolution. The texts and formulas for quantifiers,
 *              durations, explicit start/stop times and route lengths in [TmcMessageResolver]
 *              are derived from RDS Surveyor (AlertC.java, tmc/TMCEvent.java;
 *              https://github.com/ChristopheJacquet/RdsSurveyor); so are the event and
 *              supplementary-information tables that [TmcEventTables] loads
 *              (`src/main/resources/rds/LICENSE-RDSSURVEYOR.md`).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * RDS Surveyor: Copyright (c) 2009, 2010 Christophe Jacquet and the RDS Surveyor contributors
 * License: GNU Lesser General Public License version 3 or later, as RDS Surveyor.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

/**
 * One fully human-readable ALERT-C traffic message, resolved from a raw
 * [RdsEvent.TmcAssembledMessage] using the [TmcEventTables] event list.
 */
@Serializable
data class ResolvedTmcMessage(
    val messageKey: String,
    val eventCode: Int,
    val eventText: String,
    val nature: String, // Info / Forecast / Silent
    val urgency: String, // Normal / Urgent / X-Urgent
    val direction: Boolean?, // null when the event is not bidirectional (not meaningful)
    val extent: Int,
    val locationCode: Int, // the raw location code (LCID): location names are not looked up
    val diversionAdvised: Boolean,
    val expiryText: String? = null,
    val expiryMinutes: Int? = null,
    val lengthOfRouteKm: Double? = null,
    val speedLimitKmh: Int? = null,
    val quantifierText: String? = null,
    val supplementaryInfo: List<String> = emptyList(),
    val explicitStartTime: String? = null,
    val explicitStopTime: String? = null,
    val additionalEvents: List<ChainedEvent> = emptyList(),
    val controlCodes: List<String> = emptyList(),
) {
    @Serializable
    data class ChainedEvent(val code: Int, val text: String)
}

/**
 * Turns a raw, uninterpreted [RdsEvent.TmcAssembledMessage] (from the closed-source
 * rfanalyzer_digital service, which only demultiplexes ALERT-C frame bits) into a fully
 * human-readable [ResolvedTmcMessage], using the ALERT-C event list / supplementary-info
 * tables ([TmcEventTables]) and the ALERT-C optional-field formulas (duration table, quantifier
 * formulas, control-code meanings) - both from RDS Surveyor, see the file header.
 */
object TmcMessageResolver {

    private val CONTROL_CODE_TEXT = listOf(
        "increase urgency",
        "decrease urgency",
        "reverse directionality",
        "reverse duration type",
        "reverse spoken flag",
        "set diversion advised",
        "increase extent by 8",
        "increase extent by 16",
    )

    private data class DurationEntry(val minutes: Int?, val text: String)

    private val DURATION_DYNAMIC = listOf(
        DurationEntry(15, "+15 minutes"),
        DurationEntry(15, "+15 minutes"),
        DurationEntry(30, "+30 minutes"),
        DurationEntry(60, "+1 hour"),
        DurationEntry(120, "+2 hours"),
        DurationEntry(180, "+3 hours"),
        DurationEntry(240, "+4 hours"),
        DurationEntry(null, "until midnight"),
    )
    private val DURATION_LONGER_LASTING = listOf(
        DurationEntry(60, "+1 hour"),
        DurationEntry(120, "+2 hours"),
        DurationEntry(null, "until midnight"),
        DurationEntry(null, "until midnight (+24h)"),
        DurationEntry(null, "until midnight (+24h)"),
        DurationEntry(null, "until midnight (+24h)"),
        DurationEntry(null, "until midnight (+24h)"),
        DurationEntry(null, "until midnight (+24h)"),
    )

    fun resolve(message: RdsEvent.TmcAssembledMessage): ResolvedTmcMessage {
        val event = TmcEventTables.lookupEvent(message.eventCode)

        val quantifierField = message.optionalFields.firstOrNull { it.label == 4 }
            ?: message.optionalFields.firstOrNull { it.label == 5 }
        val quantifierText = quantifierField?.let {
            resolveQuantifier(event?.quantifierType, it.content, isWide = it.label == 5)
        }

        val eventText = when {
            event == null -> "Unknown event ${message.eventCode}"
            event.textTemplate != null && quantifierText != null -> event.textTemplate.replace("\$Q", quantifierText)
            else -> event.text
        }

        val durationRaw = if (message.singleGroup) message.durationOrCi
        else message.optionalFields.firstOrNull { it.label == 0 }?.content

        val durationEntry = durationRaw?.let { dp ->
            val table = if (event?.durationType == "Longer-lasting") DURATION_LONGER_LASTING else DURATION_DYNAMIC
            table.getOrNull(dp)
        }

        val diversionAdvised = if (message.singleGroup) message.diversionRecommended
        else message.optionalFields.any { it.label == 1 && it.content == 5 }

        val controlCodes = message.optionalFields.filter { it.label == 1 }
            .map { field -> CONTROL_CODE_TEXT.getOrElse(field.content) { "unknown control code ${field.content}" } }

        val lengthOfRouteKm = message.optionalFields.firstOrNull { it.label == 2 }?.let { resolveLengthOfRouteKm(it.content) }
        val speedLimitKmh = message.optionalFields.firstOrNull { it.label == 3 }?.let { resolveSpeedLimitKmh(it.content) }

        val supplementaryInfo = message.optionalFields.filter { it.label == 6 }
            .mapNotNull { TmcEventTables.lookupSupplementaryInfo(it.content) }

        val explicitStart = message.optionalFields.firstOrNull { it.label == 7 }?.let { resolveExplicitTime(it.content) }
        val explicitStop = message.optionalFields.firstOrNull { it.label == 8 }?.let { resolveExplicitTime(it.content) }

        val additionalEvents = message.optionalFields.filter { it.label == 9 }.map { field ->
            val chained = TmcEventTables.lookupEvent(field.content)
            ResolvedTmcMessage.ChainedEvent(field.content, chained?.text ?: "event ${field.content}")
        }

        return ResolvedTmcMessage(
            messageKey = message.messageKey,
            eventCode = message.eventCode,
            eventText = eventText,
            nature = event?.nature ?: "Info",
            urgency = event?.urgency ?: "Normal",
            direction = if (event?.bidirectional == true) message.direction else null,
            extent = message.extent,
            locationCode = message.locationCode,
            diversionAdvised = diversionAdvised,
            expiryText = durationEntry?.text,
            expiryMinutes = durationEntry?.minutes,
            lengthOfRouteKm = lengthOfRouteKm,
            speedLimitKmh = speedLimitKmh,
            quantifierText = quantifierText,
            supplementaryInfo = supplementaryInfo,
            explicitStartTime = explicitStart,
            explicitStopTime = explicitStop,
            additionalEvents = additionalEvents,
            controlCodes = controlCodes,
        )
    }

    /** Formulas per ALERT-C quantifier type 0-12 (label=4: 5-bit "small range", label=5: 8-bit
     * "large range"). `raw == 0` means "the maximum value for the field width" (32 or 256). */
    private fun resolveQuantifier(quantifierType: Int?, raw: Int, isWide: Boolean): String? {
        if (quantifierType == null) return null
        val max = if (isWide) 256 else 32
        val q = if (raw == 0) max else raw
        return when (quantifierType) {
            0 -> (if (q <= 28) q else (q - 29) * 2 + 30).toString()
            1 -> when {
                q <= 4 -> q.toString()
                q <= 14 -> ((q - 4) * 10).toString()
                else -> ((q - 12) * 50).toString()
            }
            2 -> "less than ${q * 10} m"
            3 -> "${(q - 1) * 5} %"
            4 -> "of up to ${5 * q} km/h"
            5 -> when {
                q <= 10 -> "of up to ${5 * q} minutes"
                q <= 22 -> "of up to ${q - 10} hours"
                else -> "of up to ${(q - 20) * 6} hours"
            }
            6 -> "${q - 51} °C"
            7 -> "%02d:%02d".format(Locale.US, (q - 1) / 6, ((q - 1) % 6) * 10)
            8 -> "${if (q <= 100) q / 10.0 else 0.5 * (q - 80)} t"
            9 -> "${if (q <= 100) q / 10.0 else 0.5 * (q - 80)} m"
            10 -> "of up to $q mm"
            11 -> "${87.5 + q / 10.0} MHz"
            12 -> null // a frequency in kHz: not resolved
            else -> null
        }
    }

    /** `0` is a sentinel meaning ~1000 km (unspecified/very long); `1..10` are literal km;
     * `11..15` and `16..31` use two different linear-scale steps. */
    private fun resolveLengthOfRouteKm(v: Int): Double = when {
        v == 0 -> 1000.0
        v in 1..10 -> v.toDouble()
        v in 11..15 -> 10.0 + (v - 10) * 2
        else -> 20.0 + (v - 15) * 5
    }

    /** `0` means "no speed advice given". */
    private fun resolveSpeedLimitKmh(v: Int): Int? = if (v == 0) null else 5 * v

    private fun resolveExplicitTime(v: Int): String = when {
        v in 0..95 -> "%02d:%02d".format(Locale.US, v / 4, (v % 4) * 15)
        v in 96..200 -> "midnight + ${(v - 96) / 24} days, ${(v - 96) % 24} hours"
        v in 201..231 -> "day ${v - 200} this/next month"
        else -> {
            val n = (v - 230) / 2
            if (v % 2 == 1) "end of month $n" else "middle of month $n"
        }
    }
}

/**
 * One row of the ALERT-C event list (`quantifierType`/`durationType`/`bidirectional` drive the
 * per-message duration/quantifier formulas in [TmcMessageResolver]).
 */
data class TmcEventDef(
    val code: Int,
    val text: String,
    val textTemplate: String?,
    val nature: String,
    val quantifierType: Int?,
    val durationType: String, // "Dynamic" or "Longer-lasting"
    val bidirectional: Boolean,
    val urgency: String,
    val updateClass: Int?,
)

/**
 * ALERT-C event-list / supplementary-information lookup tables. Derived from RdsSurveyor
 * (LGPL-3.0) — see `src/main/resources/rds/LICENSE-RDSSURVEYOR.md`. This data is bundled only in
 * RF Analyzer, not in the closed-source digital extension, which only ever emits raw,
 * uninterpreted event/location codes.
 *
 * Loaded lazily from plain classpath resources (the TSV files under `src/main/resources/rds`,
 * not Android assets) so this stays testable from fast JVM unit tests without a Context.
 */
object TmcEventTables {

    private val events: Map<Int, TmcEventDef> by lazy { loadEvents() }
    private val supplementaryInfo: Map<Int, String> by lazy { loadSupplementaryInfo() }

    fun lookupEvent(code: Int): TmcEventDef? = events[code]
    fun lookupSupplementaryInfo(code: Int): String? = supplementaryInfo[code]

    private fun resourceLines(name: String): List<String> {
        val stream = TmcEventTables::class.java.getResourceAsStream("/rds/$name")
            ?: throw IllegalStateException("Missing bundled resource: rds/$name")
        return stream.bufferedReader(Charsets.UTF_8).readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
    }

    private fun loadEvents(): Map<Int, TmcEventDef> {
        // header: code, text, text_template, nature, quantifier_type, duration_type, bidirectional, urgency, update_class
        val lines = resourceLines("tmc_event_list.tsv").drop(1)
        return lines.associate { line ->
            val f = line.split("\t")
            val code = f[0].toInt()
            code to TmcEventDef(
                code = code,
                text = f[1],
                textTemplate = f[2].takeIf { it.isNotEmpty() },
                nature = f[3],
                quantifierType = f[4].takeIf { it.isNotEmpty() }?.toIntOrNull(),
                durationType = f[5],
                bidirectional = f[6] == "1",
                urgency = f[7],
                updateClass = f.getOrNull(8)?.takeIf { it.isNotEmpty() }?.toIntOrNull(),
            )
        }
    }

    private fun loadSupplementaryInfo(): Map<Int, String> {
        // header: code, text
        val lines = resourceLines("tmc_supplementary_info.tsv").drop(1)
        return lines.associate { line ->
            val f = line.split("\t")
            f[0].toInt() to f[1]
        }
    }
}
