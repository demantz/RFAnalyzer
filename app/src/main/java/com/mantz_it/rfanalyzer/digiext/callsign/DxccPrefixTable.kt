package com.mantz_it.rfanalyzer.digiext.callsign


import com.mantz_it.rfanalyzer.ui.composable.countryCodeToFlagEmoji

/**
 * <h1>RF Analyzer - DXCC Prefix Table</h1>
 *
 * Module:      DxccPrefixTable.kt
 * Description: Callsign prefix -> country lookup, for the country flags next to callsigns and
 *              FT8's continent statistics. Mode-agnostic, hence not in `digiext.ft8`.
 *
 * **Country-level, not DXCC-entity-level**: every US prefix resolves to one "United States" entry,
 * not separately to Alaska, Hawaii and the mainland as a logging program's DXCC entity list would -
 * one ISO 3166 code per entry, which is what `countryCodeToFlagEmoji` takes.
 *
 * **Not the full DXCC list.** It covers the entities that turn up in real traffic, each with its
 * whole ITU prefix block rather than only the most common prefix. A callsign from an entity that
 * is not listed gets no flag, which is the right failure: a wrong flag is worse than a missing one.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** One prefix-block-to-country mapping. [continent] uses the same short codes throughout this
 *  table: NA, SA, EU, AS, AF, OC (no AN entry). */
data class DxccEntry(
    val prefix: String,
    val countryName: String,
    val isoCode: String,
    val continent: String,
)

/**
 * The entries of one country: each of [prefixes] is a single prefix (`"VK"`, `"HB0"`) or a block
 * whose last character runs over a range (`"DA-DR"`), the way the ITU allocates callsign series.
 */
private fun country(name: String, iso: String, continent: String, vararg prefixes: String): List<DxccEntry> =
    prefixes.flatMap { spec ->
        val (first, last) = spec.split('-').let { it.first() to it.last() }
        require(first.length == last.length && first.dropLast(1) == last.dropLast(1)) { "Bad prefix block: $spec" }
        (first.last()..last.last()).map { DxccEntry(first.dropLast(1) + it, name, iso, continent) }
    }

/**
 * Prefixes follow the ITU callsign series allocation. Where one series is split between entities
 * the longer prefix wins ([dxccCountryOf]): `B` is China except Taiwan's `BM-BQ`/`BU-BX`, `F` is
 * France except its overseas `FG`/`FK`/..., `G` the United Kingdom except the Crown Dependencies.
 * A continent is the one the DXCC list gives the entity, which is why Indonesia and the
 * Philippines are OC and Turkey is AS.
 */
internal val DXCC_TABLE: List<DxccEntry> = listOf(
    // --- North America ---
    country("United States", "US", "NA", "K", "N", "W", "AA-AL"),
    country("Puerto Rico", "PR", "NA", "KP3", "KP4", "NP3", "NP4", "WP3", "WP4"),
    country("US Virgin Islands", "VI", "NA", "KP2", "NP2", "WP2"),
    country("Canada", "CA", "NA", "VA-VG", "VO", "VY", "CF-CK", "CY-CZ", "XJ-XO"),
    country("Greenland", "GL", "NA", "OX"),
    country("Mexico", "MX", "NA", "XA-XI", "4A-4C", "6D-6J"),
    country("Cuba", "CU", "NA", "CL-CM", "CO", "T4"),
    country("Dominican Republic", "DO", "NA", "HI"),
    country("Haiti", "HT", "NA", "HH", "4V"),
    country("Jamaica", "JM", "NA", "6Y"),
    country("Bahamas", "BS", "NA", "C6"),
    country("Bermuda", "BM", "NA", "VP9"),
    country("Cayman Islands", "KY", "NA", "ZF"),
    country("Barbados", "BB", "NA", "8P"),
    country("Trinidad and Tobago", "TT", "NA", "9Y-9Z"),
    country("Grenada", "GD", "NA", "J3"),
    country("Saint Lucia", "LC", "NA", "J6"),
    country("Dominica", "DM", "NA", "J7"),
    country("Saint Vincent and the Grenadines", "VC", "NA", "J8"),
    country("Antigua and Barbuda", "AG", "NA", "V2"),
    country("Saint Kitts and Nevis", "KN", "NA", "V4"),
    country("Guadeloupe", "GP", "NA", "FG"),
    country("Martinique", "MQ", "NA", "FM"),
    country("Belize", "BZ", "NA", "V3"),
    country("Guatemala", "GT", "NA", "TG", "TD"),
    country("Costa Rica", "CR", "NA", "TI", "TE"),
    country("El Salvador", "SV", "NA", "YS", "HU"),
    country("Nicaragua", "NI", "NA", "YN", "HT", "H6-H7"),
    country("Panama", "PA", "NA", "HO-HP", "H3", "H8-H9", "3E-3F"),
    country("Honduras", "HN", "NA", "HQ-HR"),

    // --- South America ---
    country("Argentina", "AR", "SA", "LO-LW", "AY-AZ", "L2-L9"),
    country("Brazil", "BR", "SA", "PP-PY", "ZV-ZZ"),
    country("Chile", "CL", "SA", "CA-CE", "XQ-XR", "3G"),
    country("Colombia", "CO", "SA", "HJ-HK", "5J-5K"),
    country("Peru", "PE", "SA", "OA-OC", "4T"),
    country("Venezuela", "VE", "SA", "YV-YY", "4M"),
    country("Uruguay", "UY", "SA", "CV-CX"),
    country("Paraguay", "PY", "SA", "ZP"),
    country("Bolivia", "BO", "SA", "CP"),
    country("Ecuador", "EC", "SA", "HC-HD"),
    country("Suriname", "SR", "SA", "PZ"),
    country("Guyana", "GY", "SA", "8R"),
    country("French Guiana", "GF", "SA", "FY"),
    country("Falkland Islands", "FK", "SA", "VP8"),
    country("Aruba", "AW", "SA", "P4"),
    country("Curacao", "CW", "SA", "PJ2"),
    country("Bonaire, Sint Eustatius and Saba", "BQ", "SA", "PJ4"),
    country("Bonaire, Sint Eustatius and Saba", "BQ", "NA", "PJ5-PJ6"),
    country("Sint Maarten", "SX", "NA", "PJ7"),

    // --- Europe ---
    country("United Kingdom", "GB", "EU", "G", "M", "2"),
    country("Isle of Man", "IM", "EU", "GD", "GT", "MD", "MT", "2D"),
    country("Jersey", "JE", "EU", "GJ", "GH", "MJ", "MH", "2J"),
    country("Guernsey", "GG", "EU", "GU", "GP", "MU", "MP", "2U"),
    country("Ireland", "IE", "EU", "EI-EJ"),
    country("Germany", "DE", "EU", "DA-DR"),
    country("France", "FR", "EU", "F"),
    country("Italy", "IT", "EU", "I"),
    country("Spain", "ES", "EU", "EA-EH", "AM-AO"),
    country("Portugal", "PT", "EU", "CQ-CU"),
    country("Netherlands", "NL", "EU", "PA-PI"),
    country("Belgium", "BE", "EU", "ON-OT"),
    country("Luxembourg", "LU", "EU", "LX"),
    country("Switzerland", "CH", "EU", "HB", "HE"),
    country("Liechtenstein", "LI", "EU", "HB0"),
    country("Austria", "AT", "EU", "OE"),
    country("Sweden", "SE", "EU", "SA-SM", "7S", "8S"),
    country("Norway", "NO", "EU", "LA-LN"),
    country("Svalbard", "SJ", "EU", "JW"),
    country("Denmark", "DK", "EU", "OU-OW", "OZ", "5P-5Q"),
    country("Faroe Islands", "FO", "EU", "OY"),
    country("Finland", "FI", "EU", "OF-OJ"),
    country("Aland Islands", "AX", "EU", "OH0"),
    country("Iceland", "IS", "EU", "TF"),
    country("Poland", "PL", "EU", "SN-SR", "3Z", "HF"),
    country("Czech Republic", "CZ", "EU", "OK-OL"),
    country("Slovakia", "SK", "EU", "OM"),
    country("Hungary", "HU", "EU", "HA", "HG"),
    country("Romania", "RO", "EU", "YO-YR"),
    country("Bulgaria", "BG", "EU", "LZ"),
    country("Greece", "GR", "EU", "SV-SZ", "J4"),
    country("Serbia", "RS", "EU", "YT-YU"),
    country("Croatia", "HR", "EU", "9A"),
    country("Slovenia", "SI", "EU", "S5"),
    country("Bosnia and Herzegovina", "BA", "EU", "E7"),
    country("Montenegro", "ME", "EU", "4O"),
    country("North Macedonia", "MK", "EU", "Z3"),
    country("Kosovo", "XK", "EU", "Z6"),
    country("Albania", "AL", "EU", "ZA"),
    country("Ukraine", "UA", "EU", "UR-UZ", "EM-EO"),
    country("Belarus", "BY", "EU", "EU-EW"),
    country("Moldova", "MD", "EU", "ER"),
    country("Estonia", "EE", "EU", "ES"),
    country("Latvia", "LV", "EU", "YL"),
    country("Lithuania", "LT", "EU", "LY"),
    // Asiatic Russia is told apart by its call area digit, not its prefix - see dxccCountryOf.
    country("Russia", "RU", "EU", "R", "UA-UI"),
    country("Malta", "MT", "EU", "9H"),
    country("Monaco", "MC", "EU", "3A"),
    country("Andorra", "AD", "EU", "C3"),
    country("San Marino", "SM", "EU", "T7"),
    country("Vatican City", "VA", "EU", "HV"),
    country("Gibraltar", "GI", "EU", "ZB"),

    // --- Asia ---
    country("Japan", "JP", "AS", "JA-JS", "7J-7N", "8J-8N"),
    country("China", "CN", "AS", "B"),
    country("Taiwan", "TW", "AS", "BM-BQ", "BU-BX"),
    country("Hong Kong", "HK", "AS", "VR"),
    country("Macau", "MO", "AS", "XX9"),
    country("South Korea", "KR", "AS", "HL", "DS-DT", "6K-6N", "D7-D9"),
    country("Mongolia", "MN", "AS", "JT-JV"),
    country("India", "IN", "AS", "VU", "AT-AW", "8T-8Y"),
    country("Pakistan", "PK", "AS", "AP"),
    country("Sri Lanka", "LK", "AS", "4S"),
    country("Nepal", "NP", "AS", "9N"),
    country("Bangladesh", "BD", "AS", "S2"),
    country("Singapore", "SG", "AS", "9V", "S6"),
    country("Malaysia", "MY", "AS", "9M", "9W"),
    country("Thailand", "TH", "AS", "HS", "E2"),
    country("Vietnam", "VN", "AS", "XV", "3W"),
    country("Cambodia", "KH", "AS", "XU"),
    country("Turkey", "TR", "AS", "TA-TC"),
    country("Cyprus", "CY", "AS", "5B"),
    country("Israel", "IL", "AS", "4X", "4Z"),
    country("Jordan", "JO", "AS", "JY"),
    country("Lebanon", "LB", "AS", "OD"),
    country("Syria", "SY", "AS", "YK"),
    country("Iraq", "IQ", "AS", "YI"),
    country("Iran", "IR", "AS", "EP-EQ"),
    country("Kuwait", "KW", "AS", "9K"),
    country("Saudi Arabia", "SA", "AS", "HZ", "7Z", "8Z"),
    country("Oman", "OM", "AS", "A4"),
    country("United Arab Emirates", "AE", "AS", "A6"),
    country("Qatar", "QA", "AS", "A7"),
    country("Bahrain", "BH", "AS", "A9"),
    country("Georgia", "GE", "AS", "4L"),
    country("Armenia", "AM", "AS", "EK"),
    country("Azerbaijan", "AZ", "AS", "4J-4K"),
    country("Kazakhstan", "KZ", "AS", "UN-UQ"),
    country("Uzbekistan", "UZ", "AS", "UJ-UM"),
    country("Kyrgyzstan", "KG", "AS", "EX"),
    country("Tajikistan", "TJ", "AS", "EY"),
    country("Turkmenistan", "TM", "AS", "EZ"),

    // --- Oceania ---
    country("Australia", "AU", "OC", "VK", "AX"),
    country("New Zealand", "NZ", "OC", "ZL-ZM"),
    country("Indonesia", "ID", "OC", "YB-YH"),
    country("Philippines", "PH", "OC", "DU-DZ", "4D-4I"),
    country("Papua New Guinea", "PG", "OC", "P2"),
    country("Fiji", "FJ", "OC", "3D2"),
    country("New Caledonia", "NC", "OC", "FK"),
    country("French Polynesia", "PF", "OC", "FO"),

    // --- Africa ---
    country("South Africa", "ZA", "AF", "ZR-ZU"),
    country("Namibia", "NA", "AF", "V5"),
    country("Botswana", "BW", "AF", "A2"),
    country("Zimbabwe", "ZW", "AF", "Z2"),
    country("Mozambique", "MZ", "AF", "C9"),
    country("Angola", "AO", "AF", "D2"),
    country("Zambia", "ZM", "AF", "9J"),
    country("Tanzania", "TZ", "AF", "5H"),
    country("Kenya", "KE", "AF", "5Z"),
    country("Uganda", "UG", "AF", "5X"),
    country("Rwanda", "RW", "AF", "9X"),
    country("Ethiopia", "ET", "AF", "ET"),
    country("Sudan", "SD", "AF", "ST"),
    country("Egypt", "EG", "AF", "SU"),
    country("Libya", "LY", "AF", "5A"),
    country("Tunisia", "TN", "AF", "3V", "TS"),
    country("Algeria", "DZ", "AF", "7X"),
    country("Morocco", "MA", "AF", "CN"),
    country("Senegal", "SN", "AF", "6W"),
    country("Gambia", "GM", "AF", "C5"),
    country("Cape Verde", "CV", "AF", "D4"),
    country("Ghana", "GH", "AF", "9G"),
    country("Nigeria", "NG", "AF", "5N"),
    country("Cameroon", "CM", "AF", "TJ"),
    country("Ivory Coast", "CI", "AF", "TU"),
    country("Mali", "ML", "AF", "TZ"),
    country("DR Congo", "CD", "AF", "9Q"),
    country("Sao Tome and Principe", "ST", "AF", "S9"),
    country("Madagascar", "MG", "AF", "5R"),
    country("Mauritius", "MU", "AF", "3B8"),
    country("Reunion", "RE", "AF", "FR"),
    country("Seychelles", "SC", "AF", "S7"),
).flatten()

/**
 * Longest-prefix match against [DXCC_TABLE], after [normalizeCallsignForCountryLookup]. Null when
 * nothing matches, which is not an error - see this file's header.
 */
fun dxccCountryOf(callsign: String): DxccEntry? {
    val normalized = normalizeCallsignForCountryLookup(callsign)
    if (normalized.isEmpty()) return null
    val entry = DXCC_TABLE
        .filter { normalized.startsWith(it.prefix) }
        .maxByOrNull { it.prefix.length }
    // Russia spans two continents under every one of its prefixes; the call area digit decides.
    return if (entry?.isoCode == "RU" && normalized.firstOrNull { it.isDigit() } in ASIATIC_RUSSIA_CALL_AREAS) entry.copy(continent = "AS") else entry
}

private val ASIATIC_RUSSIA_CALL_AREAS = setOf('8', '9', '0')

/** The country flag for [callsign], or null when its prefix is not in the table. Anything without
 *  a digit is not a callsign (FT8's "CQ", "CQ DX", "QRZ" sit in the callsign slot) and gets none -
 *  "CQ" would otherwise match Portugal's CQ prefix block. */
fun callsignFlag(callsign: String): String? {
    if (callsign.none { it.isDigit() }) return null
    return dxccCountryOf(callsign)?.isoCode?.let { countryCodeToFlagEmoji(it) }?.takeIf { it.isNotEmpty() }
}

/** [callsign] with its country flag in front, where one is known. */
fun withFlag(callsign: String): String = callsignFlag(callsign)?.let { "$it $callsign" } ?: callsign

/**
 * Uppercases [callsign] and keeps the part before the first `/` for the country lookup. Right for
 * the common "home call/modifier" form (`"DL1ABC/P"`), wrong for a prefix naming where the station
 * operates (`"W4/G0ABC"`, a UK station in the US, resolves to the UK). FT8 sends /P and /R as flags
 * of the message, so its callsigns rarely carry a slash.
 */
internal fun normalizeCallsignForCountryLookup(callsign: String): String {
    val upper = callsign.uppercase().trim()
    return if ('/' in upper) upper.substringBefore('/') else upper
}
