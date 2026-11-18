package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import java.io.Serializable
import java.security.MessageDigest
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.pow

/**
 * <h1>RF Analyzer - Format Utilities</h1>
 *
 * Module:      FormatUtils.kt
 * Description: Formatting helpers for the UI: sizes in bytes, numbers with SI prefixes, frequency
 *              ranges, ADS-B's imperial values with their metric equivalents, timestamps with their
 *              age, time spans in words and country flags. Also a SHA-256 hex digest, the
 *              Quadruple/Quintuple tuples, a text color readable on a given color and the
 *              saturation curves that smooth user input (tuner wheel, zoom slider).
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

fun sha256(input: String): String {
    val bytes = MessageDigest
        .getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}

// extension function for Long values which hold a size in bytes
fun Long.asSizeInBytesToString(): String {
    val sizeInMB = this / (1024 * 1024)
    return if (this < 1024) {
        "$this B"
    } else if (sizeInMB < 1) {
        // a KiwiSDR recording (48 kB/s) is often smaller than a megabyte
        "${this / 1024} kB"
    } else if (sizeInMB < 1024) {
        "$sizeInMB MB"
    } else {
        val sizeInGB = this / (1024F * 1024F * 1024F)
        "%.2f GB".format(sizeInGB)
    }
}

// extension function for Long values which hold numbers like frequency, sample rate, ...
fun Long.asStringWithUnit(unit: String): String {
    val units = listOf("", "k", "M", "G", "T")  // SI prefixes
    var value = this
    var index = 0
    while (value % 1000L == 0L && value >= 1000L && index < units.lastIndex) {
        value /= 1000L
        index++
    }
    val symbols = DecimalFormatSymbols(Locale.US).apply { groupingSeparator = ' ' }
    val formatter = DecimalFormat("#,###", symbols)
    return "${formatter.format(value)} ${units[index]}$unit"
}

/** A frequency range with both ends in the unit of the larger one: "446.0125 - 446.025 MHz". */
fun frequencyRangeString(startHz: Long, endHz: Long): String {
    val top = maxOf(kotlin.math.abs(startHz), kotlin.math.abs(endHz))
    val (divisor, unit) = when {
        top >= 1_000_000_000L -> 1_000_000_000L to "GHz"
        top >= 1_000_000L -> 1_000_000L to "MHz"
        top >= 1_000L -> 1_000L to "kHz"
        else -> 1L to "Hz"
    }
    fun format(hz: Long) = java.math.BigDecimal.valueOf(hz).divide(java.math.BigDecimal.valueOf(divisor)).stripTrailingZeros().toPlainString()
    return "${format(startHz)} - ${format(endHz)} $unit"
}

// extension functions for ADS-B imperial values which need a metric equivalent alongside them
fun Int.feetToMetersString(): String = "${Math.round(this * 0.3048)} m"
fun Int.knotsToKmhString(): String = "${Math.round(this * 1.852)} km/h"
fun Int.fpmToMetersPerSecondString(): String {
    val mps = this * 0.00508
    return "${if (mps >= 0) "+" else ""}%.1f m/s".format(mps)
}

// extension function for Long values which hold timestamps
fun Long.asDateTimeWithRelative(
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val timestamp = Instant.ofEpochMilli(this)
    val dateTime = LocalDateTime.ofInstant(timestamp, zoneId)
    val formattedDate = dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault()))
    val duration = Duration.between(timestamp, now)
    val days = duration.toDays()
    val hours = duration.toHours()
    val minutes = duration.toMinutes()
    val relative = when {
        minutes < 1 -> "just now"
        minutes < 60 -> pluralize(minutes, "minute") + " ago"
        hours < 24 -> pluralize(hours, "hour") + " ago"
        days < 30 -> pluralize(days, "day") + " ago"
        days < 365 -> pluralize(days / 30, "month") + " ago"
        else -> pluralize(days / 365, "year") + " ago"
    }
    return "$formattedDate ($relative)"
}

fun Long.toTimeSpanString(): String {
    if (this <= 0) return pluralize(0, "second")
    var remaining = this / 1000 // milliseconds → seconds
    val days = remaining / 86_400
    remaining %= 86_400
    val hours = remaining / 3_600
    remaining %= 3_600
    val minutes = remaining / 60
    val seconds = remaining % 60

    val parts = mutableListOf<String>()
    if (days > 0) parts += pluralize(days, "day")
    if (hours > 0) parts += pluralize(hours, "hour")
    if (minutes > 0) parts += pluralize(minutes, "minute")
    if (seconds > 0) parts += pluralize(seconds, "second")

    return when (parts.size) {
        0 -> pluralize(0, "second")
        1 -> parts[0]
        2 -> parts.joinToString(" and ")
        else -> parts.dropLast(1).joinToString(", ") + " and " + parts.last()
    }
}

data class Quadruple<out A, out B, out C, out D>(val first: A, val second: B, val third: C, val fourth: D) : Serializable
fun <T> Quadruple<T, T, T, T>.toList(): List<T> = listOf(first, second, third, fourth)
data class Quintuple<out A, out B, out C, out D, out E>(val first: A, val second: B, val third: C, val fourth: D, val fifth: E) : Serializable
fun <T> Quintuple<T, T, T, T, T>.toList(): List<T> = listOf(first, second, third, fourth, fifth)

private fun pluralize(value: Long, unit: String): String {
    return "$value $unit" + if (value != 1L) "s" else ""
}

// black or white, whichever is readable on this color (perceived luminance)
fun Color.contrastingTextColor(): Color {
    val argb = this.toArgb()
    val r = (argb shr 16 and 0xFF)
    val g = (argb shr 8 and 0xFF)
    val b = (argb and 0xFF)

    val luminance = 0.299 * r + 0.587 * g + 0.114 * b
    return if (luminance > 186) Color.Black else Color.White
}

// saturation functions for smoother user input (e.g. for tuner wheel or zoom slider)
// see https://www.desmos.com/calculator/s93u7whlpo
fun saturationFunction(x: Float, a: Float, k: Float): Float {
    //x^{a}/(x^{a}+k^{a})
    val xPowA = x.pow(a)
    return xPowA / (xPowA + k.pow(a))
}
fun reverseSaturationFunction(y: Float, a: Float, k: Float): Float {
    // ((yk^a)/(1-y))^(1/a)
    return (y * k.pow(a) / (1 - y)).pow(1/a)
}

fun countryCodeToFlagEmoji(code: String): String {
    if (code.length != 2) return ""
    return code.uppercase(Locale.US).map { char ->
        Character.toChars(Character.codePointAt(char.toString(), 0) - 'A'.code + 0x1F1E6)
    }.joinToString("") {
        String(it)
    }
}
