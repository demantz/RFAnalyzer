package com.mantz_it.rfanalyzer.location

import com.mantz_it.rfanalyzer.database.COUNTRY_CODE_TO_NAME
import com.mantz_it.rfanalyzer.database.Coordinates
import java.util.Locale

/**
 * <h1>RF Analyzer - User Location</h1>
 *
 * Module:      UserLocation.kt
 * Description: Where the user listens from, as precisely as they chose to tell: an ITU region, a
 *              country, and optionally a position (a grid locator or the device's location). The
 *              parts are independent - the country is guessed without any permission (mobile
 *              network, time zone, SIM, language) unless the user picks one, and a position is
 *              only there if the user set one. LocationRepository combines them; every feature
 *              that depends on the area reads it from there.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

enum class ItuRegion(val number: Int, val label: String, val description: String) {
    REGION_1(1, "Region 1", "Africa, Europe, Middle East, and northern Asia"),
    REGION_2(2, "Region 2", "The Americas"),
    REGION_3(3, "Region 3", "Asia and the Pacific"),
}

/** Where [UserLocation.countryCode] came from - shown next to it ("Germany · from your mobile network"). */
enum class CountrySource(val description: String) {
    NETWORK("from your mobile network"),
    TIME_ZONE("from your time zone"),
    SIM("from your SIM card"),
    LANGUAGE("from your language setting"),
    CHOSEN("chosen by you"),
}

enum class PositionMode(val displayName: String) {
    NONE("Not set"),
    LOCATOR("Grid locator"),
    DEVICE("This device's location"),
}

data class UserLocation(
    /** ISO 3166-1 alpha-2 (upper case); null if unknown or the user gave only a region. */
    val countryCode: String? = null,
    val countrySource: CountrySource? = null,
    /** The country's region, or the region alone if the user chose only that. */
    val ituRegion: ItuRegion? = null,
    val positionMode: PositionMode = PositionMode.NONE,
    /** A locator's centre or the device's last fix; null if not set or no fix yet. */
    val position: Coordinates? = null,
    /** The locator as entered (JN49, JN49eh), or the device fix's six-character one. */
    val locator: String? = null,
    /** For [PositionMode.DEVICE]: when the fix was taken. */
    val fixTimeMs: Long? = null,
) {
    val countryName: String? get() = countryCode?.let { COUNTRY_CODE_TO_NAME[it] }

    /** "Germany", "Region 1", or null - the shortest name for what is known. */
    val areaName: String? get() = countryName ?: ituRegion?.let { "ITU ${it.label}" }
}

/**
 * The stored choice of [LocationRepository]: [area] is "" for the guess, "R1".."R3" for a region
 * alone, else a country code; [deviceFix] is "lat,lon,timeMs" or "".
 */
internal fun resolveUserLocation(
    area: String,
    guess: Pair<String, CountrySource>?,
    positionMode: PositionMode,
    locator: String,
    deviceFix: String,
): UserLocation {
    val chosenRegion = AREA_REGION_PREFIX.takeIf { area.startsWith(it) }?.let { area.removePrefix(it).toIntOrNull() }
        ?.let { n -> ItuRegion.entries.firstOrNull { it.number == n } }
    val (countryCode, countrySource) = when {
        chosenRegion != null -> null to null
        area.isNotEmpty() && isCountryCode(area) -> area to CountrySource.CHOSEN
        else -> guess?.first to guess?.second
    }
    val manualLocator = normalizeLocator(locator)
    val (position, fixTime) = when (positionMode) {
        PositionMode.NONE -> null to null
        PositionMode.LOCATOR -> manualLocator?.let(Coordinates::fromGridLocator) to null
        PositionMode.DEVICE -> parseDeviceFix(deviceFix)?.let { (lat, lon, time) -> Coordinates(lat, lon) to time } ?: (null to null)
    }
    return UserLocation(
        countryCode = countryCode,
        countrySource = countrySource,
        ituRegion = chosenRegion ?: countryCode?.let(::ituRegionOf),
        positionMode = positionMode,
        position = position,
        locator = if (positionMode == PositionMode.LOCATOR) manualLocator else position?.toGridLocator(),
        fixTimeMs = fixTime,
    )
}

/** A latitude/longitude box in degrees; [east] exceeds 180 for a box across the antimeridian. */
data class GeoBounds(val south: Double, val west: Double, val north: Double, val east: Double)

/**
 * What the maps show of the user's location: they start on [bounds] when they have nothing else to
 * show (the device fix's surroundings, the locator's square, the country, or the region), and draw
 * [square] - the locator's - as home.
 */
data class MapHome(val bounds: GeoBounds, val square: GeoBounds? = null, val label: String? = null)

/** Null if not even a region is known. [countryBounds] is the country's box (country_bounds.csv). */
fun UserLocation.mapHome(countryBounds: (String) -> GeoBounds?): MapHome? {
    val square = locator?.takeIf { positionMode == PositionMode.LOCATOR }?.let(::locatorBounds)
    val bounds = when {
        square != null -> square
        positionMode == PositionMode.DEVICE && position != null ->
            GeoBounds(position.latitude - 0.1, position.longitude - 0.15, position.latitude + 0.1, position.longitude + 0.15)
        else -> countryCode?.let(countryBounds) ?: ituRegion?.let { REGION_BOUNDS.getValue(it) }
    } ?: return null
    return MapHome(bounds, square, square?.let { locator })
}

/** Where each region's map starts for a user who gave only the region (or a country without a box). */
private val REGION_BOUNDS = mapOf(
    ItuRegion.REGION_1 to GeoBounds(-35.0, -20.0, 70.0, 60.0),
    ItuRegion.REGION_2 to GeoBounds(-56.0, -170.0, 72.0, -30.0),
    ItuRegion.REGION_3 to GeoBounds(-47.0, 60.0, 55.0, 180.0),
)

/** The square a four- or six-character locator covers: 2° × 1°, or 5' × 2.5'. */
fun locatorBounds(locator: String): GeoBounds? {
    val l = normalizeLocator(locator) ?: return null
    var west = (l[0] - 'A') * 20.0 - 180 + (l[2] - '0') * 2.0
    var south = (l[1] - 'A') * 10.0 - 90 + (l[3] - '0') * 1.0
    var width = 2.0
    var height = 1.0
    if (l.length == 6) {
        width /= 24; height /= 24
        west += (l[4] - 'a') * width
        south += (l[5] - 'a') * height
    }
    return GeoBounds(south, west, south + height, west + width)
}

/** Reads country_bounds.csv: "DE,47.28,5.86,54.90,15.02" (country, south, west, north, east) per line. */
fun parseCountryBounds(lines: Sequence<String>): Map<String, GeoBounds> = lines
    .filter { it.isNotBlank() && !it.startsWith("#") }
    .mapNotNull { line ->
        val parts = line.split(",")
        val values = parts.drop(1).mapNotNull { it.trim().toDoubleOrNull() }
        if (parts.size != 5 || values.size != 4) null
        else parts[0].trim() to GeoBounds(values[0], values[1], values[2], values[3])
    }
    .toMap()

/** A four- or six-character Maidenhead locator in its usual spelling (JN49eh), or null if [input]
 *  isn't one. */
fun normalizeLocator(input: String): String? {
    val trimmed = input.trim()
    if (!LOCATOR_REGEX.matches(trimmed)) return null
    return trimmed.take(4).uppercase(Locale.ROOT) + trimmed.drop(4).lowercase(Locale.ROOT)
}

private val LOCATOR_REGEX = Regex("[A-Ra-r]{2}[0-9]{2}([A-Xa-x]{2})?")

internal const val AREA_REGION_PREFIX = "R"

internal fun formatDeviceFix(latitude: Double, longitude: Double, timeMs: Long): String =
    // ~100 m: nothing here needs more, and the coarse permission gives ~2 km anyway
    "%.3f,%.3f,%d".format(Locale.US, latitude, longitude, timeMs)

internal fun parseDeviceFix(fix: String): Triple<Double, Double, Long>? {
    val parts = fix.split(",")
    if (parts.size != 3) return null
    val lat = parts[0].toDoubleOrNull() ?: return null
    val lon = parts[1].toDoubleOrNull() ?: return null
    val time = parts[2].toLongOrNull() ?: return null
    return Triple(lat, lon, time)
}

/**
 * The country the phone is most likely in, from what needs no permission - strongest first: the
 * mobile network it is registered with (follows the user abroad), the time zone (set from the
 * network or by the user, also on devices without a SIM), the SIM's home country, and last the
 * language setting (often just "US" for English).
 */
internal fun guessCountry(network: String?, timeZoneRegion: String?, sim: String?, language: String?): Pair<String, CountrySource>? =
    listOf(network to CountrySource.NETWORK, timeZoneRegion to CountrySource.TIME_ZONE, sim to CountrySource.SIM, language to CountrySource.LANGUAGE)
        .firstNotNullOfOrNull { (code, source) -> code?.uppercase(Locale.ROOT)?.takeIf(::isCountryCode)?.let { it to source } }

internal fun isCountryCode(code: String): Boolean = code.length == 2 && code in COUNTRY_CODE_TO_NAME

/** ITU Radio Regulations art. 5: Region 2 is the Americas and Greenland, Region 3 Asia/Pacific
 *  from Iran eastward; everything else - Europe, Africa, the Middle East, the former USSR and Mongolia -
 *  is Region 1. Territories follow their own location (Guam is Region 3, Hawaii as part of the US
 *  Region 2). */
fun ituRegionOf(countryCode: String): ItuRegion = when (countryCode) {
    in REGION_2_COUNTRIES -> ItuRegion.REGION_2
    in REGION_3_COUNTRIES -> ItuRegion.REGION_3
    else -> ItuRegion.REGION_1
}

private val REGION_2_COUNTRIES = setOf(
    "AG", "AI", "AR", "AW", "BB", "BL", "BM", "BO", "BQ", "BR", "BS", "BZ", "CA", "CL", "CO", "CR",
    "CU", "CW", "DM", "DO", "EC", "FK", "GD", "GF", "GL", "GP", "GS", "GT", "GY", "HN", "HT", "JM",
    "KN", "KY", "LC", "MF", "MQ", "MS", "MX", "NI", "PA", "PE", "PM", "PR", "PY", "SR", "SV", "SX",
    "TC", "TT", "UM", "US", "UY", "VC", "VE", "VG", "VI",
)

private val REGION_3_COUNTRIES = setOf(
    "AF", "AS", "AU", "BD", "BN", "BT", "CC", "CK", "CN", "CX", "FJ", "FM", "GU", "HK", "ID", "IN",
    "IO", "IR", "JP", "KH", "KI", "KP", "KR", "LA", "LK", "MH", "MM", "MO", "MP", "MV", "MY", "NC",
    "NF", "NP", "NR", "NU", "NZ", "PF", "PG", "PH", "PK", "PN", "PW", "SB", "SG", "TH", "TK", "TL",
    "TO", "TV", "TW", "VN", "VU", "WF", "WS",
)

/** Countries whose FM stations follow the North American RBDS standard (its own PTY table): the US
 *  with its territories, and Canada. */
fun usesRbds(countryCode: String?): Boolean = countryCode in RBDS_COUNTRIES

private val RBDS_COUNTRIES = setOf("US", "PR", "VI", "GU", "AS", "MP", "UM", "CA")
