package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * <h1>RF Analyzer - Digital Format</h1>
 *
 * Module:      DigitalFormat.kt
 * Description: The one set of text formats the Digital tab shows times, ages, frequencies and
 *              counts in, so a value reads the same in a row, a ticker, a card and a details page.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

private val localZone: ZoneId get() = ZoneId.systemDefault()
private val TIME_HMS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")
private val TIME_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DAY_MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.")
private val DAY_MONTH_HM: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM. HH:mm")
private val FULL: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss.SSS")
private val FULL_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")

private fun isToday(timestampMs: Long, nowMs: Long): Boolean =
    Instant.ofEpochMilli(timestampMs).atZone(localZone).toLocalDate() == LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), localZone)

private fun DateTimeFormatter.of(timestampMs: Long): String = format(Instant.ofEpochMilli(timestampMs).atZone(localZone))

/**
 * What "now" is for the Digital tab's ages: the time of the samples being received while the shown
 * mode is live (a replayed recording's own time - see SourceClock), the wall clock otherwise.
 */
val LocalDigitalClock = staticCompositionLocalOf<() -> Long> { { System.currentTimeMillis() } }

/** Time since [timestampMs]: "8 s", "5 min", "3 h", "11 d" - the age column and ticker. A time more
 *  than a minute after [nowMs] (heard after the recording being replayed) reads as a time instead. */
fun formatAge(nowMs: Long, timestampMs: Long): String {
    if (timestampMs - nowMs > 60_000L) return formatShortTime(nowMs, timestampMs)
    val seconds = ((nowMs - timestampMs) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86_400 -> "${seconds / 3600} h"
        else -> "${seconds / 86_400} d"
    }
}

/** A message list's time cell: "20:13" today, "06.11." before. */
fun formatShortTime(nowMs: Long, timestampMs: Long): String =
    if (isToday(timestampMs, nowMs)) TIME_HM.of(timestampMs) else DAY_MONTH.of(timestampMs)

/** A collapsed card's time: "20:13:39" today, "06.11. 20:13" before. */
fun formatPacketTime(nowMs: Long, timestampMs: Long): String =
    if (isToday(timestampMs, nowMs)) TIME_HMS.of(timestampMs) else DAY_MONTH_HM.of(timestampMs)

/** Date and time to the millisecond, for an opened card: "06.11.2026 20:13:39.084". */
fun formatFullDateTime(timestampMs: Long): String = FULL.of(timestampMs)

/** Date and time to the second, for facts like "first heard": "06.11.2026 20:13:39". */
fun formatDateTime(timestampMs: Long): String = FULL_SECONDS.of(timestampMs)

/** "20:13:39" today, else with the date - for facts that are usually recent. */
fun formatWhen(nowMs: Long, timestampMs: Long): String =
    if (isToday(timestampMs, nowMs)) TIME_HMS.of(timestampMs) else FULL_SECONDS.of(timestampMs)

/** A duration: "49 s", "2:31 min", "1:05 h". */
fun formatDuration(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "%d:%02d min".format(Locale.US, seconds / 60, seconds % 60)
        else -> "%d:%02d h".format(Locale.US, seconds / 3600, (seconds % 3600) / 60)
    }
}

/**
 * A radio frequency as people write it: "144.390 MHz", "869.618 MHz", "14.0715 MHz", "4.583 MHz",
 * "1090 MHz", "147.3 kHz". At least three decimals in MHz so channel plans line up, more only
 * where the frequency has them.
 */
fun formatFrequency(hz: Long): String {
    if (hz == 0L) return ""
    if (abs(hz) < 1_000_000) {
        val khz = hz / 1000.0
        return trimDecimals("%.3f".format(Locale.US, khz), minDecimals = 0) + " kHz"
    }
    val mhz = hz / 1_000_000.0
    val text = trimDecimals("%.6f".format(Locale.US, mhz), minDecimals = 3)
    return (if (text.endsWith(".000") && hz % 1_000_000 == 0L && hz >= 100_000_000) text.removeSuffix(".000") else text) + " MHz"
}

private fun trimDecimals(text: String, minDecimals: Int): String {
    val dot = text.indexOf('.')
    if (dot < 0) return text
    var end = text.length
    while (end > dot + 1 + minDecimals && text[end - 1] == '0') end--
    if (end == dot + 1) end = dot
    return text.substring(0, end)
}

/** An offset in Hz with its sign: "+211 Hz", "−439 Hz". */
fun formatSignedHz(hz: Double): String = signed(hz.roundToLong()) + " Hz"

/** A signal ratio: "−13.9 dB", "+4 dB" (whole numbers without decimals). */
fun formatDb(db: Double): String {
    val rounded = Math.round(db * 10) / 10.0
    val text = if (rounded == Math.rint(rounded)) signed(rounded.toLong()) else (if (rounded > 0) "+" else if (rounded < 0) "−" else "") + "%.1f".format(Locale.US, abs(rounded))
    return "$text dB"
}

/** "+5", "−3", "0" with a real minus sign. */
fun signed(value: Long): String = when {
    value > 0 -> "+$value"
    value < 0 -> "−${-value}"
    else -> "0"
}

/** A count with digit groups: "14 751". */
fun formatCount(n: Int): String = formatCount(n.toLong())

fun formatCount(n: Long): String = "%,d".format(Locale.US, n).replace(',', ' ')

/** "1 station", "2 stations". */
fun plural(n: Int, one: String, many: String): String = "${formatCount(n)} ${if (n == 1) one else many}"

/** The Digital tab's one separator for small facts. */
const val META_SEPARATOR = " · "

/** Joins the non-blank [parts] with [META_SEPARATOR]. */
fun metaLine(vararg parts: String?): String = parts.filterNot { it.isNullOrBlank() }.joinToString(META_SEPARATOR)

fun metaLine(parts: List<String?>): String = parts.filterNot { it.isNullOrBlank() }.joinToString(META_SEPARATOR)

/** A [metaLine] shortened part by part from its end: "a", "a · b", "a · b · c". */
fun metaLinePrefixes(line: String): List<String> {
    val parts = line.split(META_SEPARATOR).filter { it.isNotBlank() }
    return parts.indices.map { n -> parts.subList(0, n + 1).joinToString(META_SEPARATOR) }
}

/** [text] on one line: runs of whitespace (line breaks, tabs) become one space. */
fun oneLine(text: String): String = text.trim().replace(WHITESPACE, " ")

private val WHITESPACE = Regex("\\s+")

/** A position as "49.4123, 8.6981" (four decimals: about 10 m). */
fun formatLatLon(lat: Double, lon: Double): String = "%.4f, %.4f".format(Locale.US, lat, lon)

/** "906 m" from feet. */
fun feetToMeters(feet: Int): String = "${Math.round(feet * 0.3048)} m"

/** "49 kn" plus km/h: "49 kn (91 km/h)". */
fun formatKnots(knots: Double): String = "${Math.round(knots)} kn (${Math.round(knots * 1.852)} km/h)"

/** A position for facts: "34.50733, -117.12967 (DM14KM)" - five decimals and the locator. */
fun formatPosition(lat: Double, lon: Double): String =
    "%.5f, %.5f".format(Locale.US, lat, lon) + " (" + com.mantz_it.rfanalyzer.database.Coordinates(lat, lon).toGridLocator() + ")"
