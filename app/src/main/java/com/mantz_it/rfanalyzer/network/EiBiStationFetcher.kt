package com.mantz_it.rfanalyzer.network

import android.util.Log
import com.mantz_it.rfanalyzer.database.COUNTRY_CODE_TO_NAME
import com.mantz_it.rfanalyzer.database.Coordinates
import com.mantz_it.rfanalyzer.database.Schedule
import com.mantz_it.rfanalyzer.database.SourceProvider
import com.mantz_it.rfanalyzer.database.Station
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.composable.sha256
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.security.MessageDigest
import javax.net.ssl.*
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

/**
 * <h1>RF Analyzer - EiBiStationFetcher</h1>
 *
 * Module:      EiBiStationFetcher.kt
 * Description: Fetch stations (bookmarks) from EiBiSpace.de
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

object EiBiStationFetcher {
    private const val TAG = "EiBiStationFetcher"
    /** Connect and read timeout, so a stalled server can't hold the update (and block every
     *  retry as "already running"). */
    private const val TIMEOUT_MS = 15_000

    suspend fun fetchStations(url: String, oldStations: List<Station>): List<Station> = withContext(Dispatchers.IO) {
        val url = URL(url)
        Log.d(TAG, "fetchStation: Fetching $url")
        val connection = url.openConnection() as HttpsURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.connect()
        val lines = connection.inputStream.bufferedReader().readLines()
        val now = System.currentTimeMillis()
        Log.d(TAG, "fetchStations: Received ${lines.size} lines.")

        val parsedStations = lines.drop(1).mapNotNull { line ->
            val parts = line.split(";")
            if (parts.size > 7) {
                val uniqueHash = sha256("EIBI_$line")
                val oldStation = oldStations.find { it.remoteHash == uniqueHash }
                val freq = parts[0].toDoubleOrNull() ?: return@mapNotNull null
                val (startUtc, endUtc) = parts[1].split('-').let { it.getOrNull(0) to it.getOrNull(1) }
                val (weekdays, remarks) = parseWeekday(parts[2])
                val schedule = Schedule(
                    startTimeUtc = startUtc ?: "0000",
                    endTimeUtc = endUtc ?: "2400",
                    days = weekdays,
                    remarks = remarks
                )
                val ituCode = parts[3]
                val foreignItuCode = if (parts[7].startsWith("/")) parts[7].substring(1).substringBefore("-") else null
                val transmitterSiteCode = if (foreignItuCode == null) parts[7] else parts[7].substringAfter("-", "")
                val (locationDescription, coordinateString) = TRANSMITTER_SITE_CODES[ituCode]?.get(transmitterSiteCode) ?: (null to null)
                val coordinates = if (coordinateString!=null) Coordinates.fromDMS(coordinateString.replace('-', ',')) else null
                val country = ITU_TO_ISO[foreignItuCode ?: ituCode]
                val language = LANGUAGE_CODE_TO_STRING[parts[5]]
                var mode = DemodulationMode.AM
                if ("-CW" in parts[5]) mode = DemodulationMode.CW
                if ("LSB" in parts[2]) mode = DemodulationMode.LSB
                if ("USB" in parts[2]) mode = DemodulationMode.USB
                // Teleprinter broadcasts (the DWD's weather RTTY) are only named so - EiBi has no
                // language code for them. Not NAVTEX: that is SITOR-B, which the RTTY receiver doesn't decode.
                if (RTTY_NAME.containsMatchIn(parts[4])) mode = DemodulationMode.RTTY
                Station(
                    name = parts[4],
                    bookmarkListId = null,
                    frequency = (freq * 1000).toLong(),
                    bandwidth = mode.defaultChannelWidth,
                    createdAt = now,
                    updatedAt = now,
                    favorite = oldStation?.favorite ?: false,
                    mode = mode,
                    language = language,
                    countryCode = country,
                    countryName = COUNTRY_CODE_TO_NAME[country],
                    address = locationDescription,
                    coordinates = coordinates,
                    schedule = schedule,
                    remoteHash = uniqueHash,
                    source = SourceProvider.EIBI
                )
            } else null
        }
        Log.d(TAG, "fetchStations: parsed ${parsedStations.size} stations. Optimizing...")
        val mergedStations = optimizeStations(parsedStations)
        Log.d(TAG, "fetchStations: ${mergedStations.size} stations after optimizing.")
        mergedStations
    }

    internal fun parseWeekday(field: String): Pair<Set<Schedule.Weekday>, String?> {
        val trimmed = field.trim()
        if (trimmed.isEmpty()) return Pair(emptySet(), null)
        val weekdays = Schedule.Weekday.ordered
        val weekdayShort = weekdays.associateBy { it.shortName }
        val weekdayDigits = weekdays.withIndex()
            .associate { (i, d) -> (i + 1).toString() to d } // "1" -> Monday

        // 1) Check for pure digits meaning weekdays: "1245"
        if (trimmed.all { it.isDigit() }) {
            val parsed = trimmed.mapNotNull { weekdayDigits[it.toString()] }
            return if (parsed.isNotEmpty()) Pair(parsed.toSet(), null)
            else Pair(emptySet(), trimmed)
        }

        // 2) Check for Mo-We ranges (Su-Fr is also possible)
        if ("-" in trimmed) {
            val rangeParts = trimmed.split('-').map { it.trim() }
            if (rangeParts.size == 2) {
                val startDayStr = rangeParts[0]
                val endDayStr = rangeParts[1]

                val startDay = weekdayShort[startDayStr]
                val endDay = weekdayShort[endDayStr]

                if (startDay != null && endDay != null) {
                    val startIndex = weekdays.indexOf(startDay)
                    val endIndex = weekdays.indexOf(endDay)
                    if (startIndex != -1 && endIndex != -1) {
                        if (startIndex <= endIndex) {
                            return Pair(weekdays.subList(startIndex, endIndex + 1).toSet(), null)
                        } else { // Wraps around, e.g. Su-Fr
                            val wrappedDays = mutableSetOf<Schedule.Weekday>()
                            wrappedDays.addAll(weekdays.subList(startIndex, weekdays.size))
                            wrappedDays.addAll(weekdays.subList(0, endIndex + 1))
                            return Pair(wrappedDays, null)
                        }
                    }
                }
            }
            return Pair(emptySet(), trimmed)
        }

        // 3) Check for short names: "MoTuWe"
        val values = weekdays.filter { trimmed.contains(it.shortName) }.toSet()
        return if (values.isEmpty()) Pair(emptySet(), trimmed) else Pair(values, null)
    }
}

private fun optimizeStations(inputStations: List<Station>): List<Station> {
    data class StationIdentity(
        val frequency: Long,
        val name: String,
        val coordinates: Coordinates?,
        val countryCode: String?,
        val address: String?,
        val language: String?
    )

    fun Station.toIdentity() = StationIdentity(
        frequency, name, coordinates, countryCode, address, language
    )

    val grouped = inputStations.groupBy { it.toIdentity() }
    Log.d("EiBiStationFetcher", "optimizedStations: grouped.size = ${grouped.size}")
    val optimizedList = mutableListOf<Station>()

    for ((_, stationGroup) in grouped) {
        val mergedSchedules = mergeSchedules(stationGroup)
        optimizedList.addAll(mergedSchedules)
    }

    return optimizedList
}

private fun mergeSchedules(group: List<Station>): List<Station> {
    if (group.size < 2) return group

    val workingList = group.toMutableList()
    var changed: Boolean

    // We use a "greedy" iterative approach because merging two items
    // might enable a third merge (e.g., 0800-0900 + 0900-1000 + 1000-1100)
    do {
        changed = false
        var i = 0
        while (i < workingList.size) {
            var j = i + 1
            while (j < workingList.size) {
                val s1 = workingList[i]
                val s2 = workingList[j]
                val merged = tryMerge(s1, s2)

                if (merged != null) {
                    workingList[i] = merged
                    workingList.removeAt(j)
                    changed = true
                    // Stay on current i to check if new merged station can merge with others
                } else {
                    j++
                }
            }
            i++
        }
    } while (changed)

    return workingList
}

private fun tryMerge(s1: Station, s2: Station): Station? {
    if (s1.schedule == null && s2.schedule == null) // if no schedule, they can be merged
        return s1

    val sch1 = s1.schedule ?: return null
    val sch2 = s2.schedule ?: return null

    // Condition 1: Times match exactly -> Merge Days
    if (sch1.startTimeUtc == sch2.startTimeUtc && sch1.endTimeUtc == sch2.endTimeUtc) {
        return s1.copy(
            schedule = sch1.copy(days = sch1.days + sch2.days),
            updatedAt = maxOf(s1.updatedAt, s2.updatedAt)
        )
    }

    // Condition 2: Days match exactly -> Check Adjacency
    if (sch1.days == sch2.days) {
        // s1 ends when s2 starts
        if (sch1.endTimeUtc != null && sch1.endTimeUtc == sch2.startTimeUtc) {
            return s1.copy(
                schedule = sch1.copy(endTimeUtc = sch2.endTimeUtc),
                updatedAt = maxOf(s1.updatedAt, s2.updatedAt),
            )
        }
        // s2 ends when s1 starts
        if (sch2.endTimeUtc != null && sch2.endTimeUtc == sch1.startTimeUtc) {
            return s1.copy(
                schedule = sch1.copy(startTimeUtc = sch2.startTimeUtc),
                updatedAt = maxOf(s1.updatedAt, s2.updatedAt),
            )
        }
    }

    return null
}

// ITU country codes (as EiBi uses them) to ISO 3166-1 alpha-2; not checked entry by entry, may
// contain errors.
val ITU_TO_ISO: Map<String, String?> = mapOf(
    // ----- A -----
    "ABW" to "AW",
    "AFG" to "AF",
    "AFS" to "ZA",
    "AGL" to "AO",
    "AIA" to "AI",
    "ALB" to "AL",
    "ALG" to "DZ",
    "ALS" to "US",   // Alaska → USA
    "AMS" to "FR",   // Amsterdam/St Paul Islands → France
    "AND" to "AD",
    "AOE" to "EH",   // Western Sahara
    "ARG" to "AR",
    "ARM" to "AM",
    "ARS" to "SA",
    "ASC" to "SH",   // Ascension → part of SH (Saint Helena, Ascension, Tristan da Cunha)
    "ATA" to "AQ",   // Antarctica
    "ATG" to "AG",
    "ATN" to "AN",   // historical Netherlands Antilles → fallback to "AN"
    "AUS" to "AU",
    "AUT" to "AT",
    "AZE" to "AZ",
    "AZR" to "PT",   // Azores → Portugal

    // ----- B -----
    "B" to "BR",     // Brazil
    "BAH" to "BS",
    "BDI" to "BI",
    "BEL" to "BE",
    "BEN" to "BJ",
    "BER" to "BM",
    "BES" to "BQ",   // Dutch Caribbean
    "BFA" to "BF",
    "BGD" to "BD",
    "BHR" to "BH",
    "BIH" to "BA",
    "BIO" to "IO",   // British Indian Ocean Territory
    "BLM" to "BL",
    "BLR" to "BY",
    "BLZ" to "BZ",
    "BOL" to "BO",
    "BOT" to "BW",
    "BRB" to "BB",
    "BRU" to "BN",
    "BTN" to "BT",
    "BUL" to "BG",
    "BVT" to "BV",
    "CAB" to "AO",   // Cabinda → Angola
    "CAF" to "CF",
    "CAN" to "CA",
    "CBG" to "KH",
    "CEU" to "ES",
    "CG7" to "US",   // Guantanamo Bay → US
    "CHL" to "CL",
    "CHN" to "CN",
    "CHR" to "CX",   // Christmas Island (Indian Ocean)
    "CKH" to "CK",   // Cook Islands
    "CLA" to null,   // Clandestine
    "CLM" to "CO",
    "CLN" to "LK",
    "CME" to "CM",
    "CNR" to "ES",
    "COD" to "CD",
    "COG" to "CG",
    "COM" to "KM",
    "CPT" to "FR",
    "CPV" to "CV",
    "CRO" to "TF",   // French Southern Lands (Crozet)
    "CTI" to "CI",
    "CTR" to "CR",
    "CUB" to "CU",
    "CUW" to "CW",
    "CVA" to "VA",
    "CYM" to "KY",
    "CYP" to "CY",
    "CZE" to "CZ",

    // ----- D -----
    "D" to "DE",     // Germany
    "DJI" to "DJ",
    "DMA" to "DM",
    "DNK" to "DK",
    "DOM" to "DO",

    // ----- E -----
    "E" to "ES",
    "EGY" to "EG",
    "EQA" to "EC",
    "ERI" to "ER",
    "EST" to "EE",
    "ETH" to "ET",
    "EUR" to "TF",   // Bassas da India & Europa → French Southern Lands

    // ----- F -----
    "F" to "FR",
    "FIN" to "FI",
    "FJI" to "FJ",
    "FLK" to "FK",
    "FRO" to "FO",
    "FSM" to "FM",

    // ----- G -----
    "G" to "GB",
    "GAB" to "GA",
    "GEO" to "GE",
    "GHA" to "GH",
    "GIB" to "GI",
    "GLP" to "GP",
    "GMB" to "GM",
    "GNB" to "GW",
    "GNE" to "GQ",
    "GPG" to "EC",   // Galapagos → Ecuador
    "GRC" to "GR",
    "GRD" to "GD",
    "GRL" to "GL",
    "GTM" to "GT",
    "GUF" to "GF",
    "GUI" to "GN",
    "GUM" to "GU",
    "GUY" to "GY",

    // ----- H -----
    "HKG" to "HK",
    "HMD" to "HM",
    "HND" to "HN",
    "HNG" to "HU",
    "HOL" to "NL",
    "HRV" to "HR",
    "HTI" to "HT",
    "HWA" to "US",
    "HWL" to "UM",   // Howland & Baker → US minor outlying islands

    // ----- I -----
    "I" to "IT",
    "ICO" to "CC",   // Cocos Islands
    "IND" to "IN",
    "INS" to "ID",
    "IRL" to "IE",
    "IRN" to "IR",
    "IRQ" to "IQ",
    "ISL" to "IS",
    "ISR" to "IL",
    "IW" to null,    // International Waters
    "IWA" to "JP",

    // ----- J -----
    "J" to "JP",
    "JAR" to "UM",
    "JDN" to "FR",
    "JMC" to "JM",
    "JMY" to "NO",
    "JON" to "UM",
    "JOR" to "JO",
    "JUF" to "CL",

    // ----- K -----
    "KAL" to "RU",
    "KAZ" to "KZ",
    "KEN" to "KE",
    "KER" to "TF",
    "KGZ" to "KG",
    "KIR" to "KI",
    "KNA" to "KN",
    "KOR" to "KR",
    "KOS" to "XK",   // Kosovo (widely used)
    "KRE" to "KP",
    "KWT" to "KW",

    // ----- L -----
    "LAO" to "LA",
    "LBN" to "LB",
    "LBR" to "LR",
    "LBY" to "LY",
    "LCA" to "LC",
    "LIE" to "LI",
    "LSO" to "LS",
    "LTU" to "LT",
    "LUX" to "LU",
    "LVA" to "LV",

    // ----- M -----
    "MAC" to "MO",
    "MAF" to "MF",
    "MAU" to "MU",
    "MCO" to "MC",
    "MDA" to "MD",
    "MDG" to "MG",
    "MDR" to "PT",
    "MDW" to "UM",
    "MEL" to "ES",
    "MEX" to "MX",
    "MHL" to "MH",
    "MKD" to "MK",
    "MLA" to "MY",
    "MLD" to "MV",
    "MLI" to "ML",
    "MLT" to "MT",
    "MNE" to "ME",
    "MNG" to "MN",
    "MOZ" to "MZ",
    "MRA" to "MP",
    "MRC" to "MA",
    "MRN" to "ZA",
    "MRT" to "MQ",
    "MSR" to "MS",
    "MTN" to "MR",
    "MWI" to "MW",
    "MYA" to "MM",
    "MYT" to "YT",

    // ----- N -----
    "NCG" to "NI",
    "NCL" to "NC",
    "NFK" to "NF",
    "NGR" to "NE",
    "NIG" to "NG",
    "NIU" to "NU",
    "NMB" to "NA",
    "NOR" to "NO",
    "NPL" to "NP",
    "NRU" to "NR",
    "NZL" to "NZ",

    // ----- O -----
    "OCE" to "PF",
    "OMA" to "OM",

    // ----- P -----
    "PAK" to "PK",
    "PAQ" to "CL",
    "PHL" to "PH",
    "PHX" to "KI",
    "PLM" to "UM",
    "PLW" to "PW",
    "PNG" to "PG",
    "PNR" to "PA",
    "POL" to "PL",
    "POR" to "PT",
    "PRG" to "PY",
    "PRU" to "PE",
    "PRV" to "JP",
    "PSE" to "PS",
    "PTC" to "PN",
    "PTR" to "PR",
    "QAT" to "QA",

    // ----- R -----
    "REU" to "RE",
    "ROD" to "MU",
    "ROU" to "RO",
    "RRW" to "RW",
    "RUS" to "RU",

    // ----- S -----
    "S" to "SE",
    "SAP" to "CO",
    "SDN" to "SD",
    "SEN" to "SN",
    "SEY" to "SC",
    "SGA" to "GS",   // South Georgia & SS Islands
    "SHN" to "SH",
    "SLM" to "SB",
    "SLV" to "SV",
    "SMA" to "AS",
    "SMO" to "WS",
    "SMR" to "SM",
    "SNG" to "SG",
    "SOK" to "AQ",
    "SOM" to "SO",
    "SPM" to "PM",
    "SRB" to "RS",
    "SRL" to "SL",
    "SSD" to "SS",
    "SSI" to "GS",
    "STP" to "ST",
    "SUI" to "CH",
    "SUR" to "SR",
    "SVB" to "SJ",   // Svalbard
    "SVK" to "SK",
    "SVN" to "SI",
    "SWZ" to "SZ",
    "SXM" to "SX",
    "SYR" to "SY",

    // ----- T -----
    "TCA" to "TC",
    "TCD" to "TD",
    "TGO" to "TG",
    "THA" to "TH",
    "TJK" to "TJ",
    "TKL" to "TK",
    "TKM" to "TM",
    "TLS" to "TL",
    "TON" to "TO",
    "TRC" to "SH",
    "TRD" to "TT",
    "TUN" to "TN",
    "TUR" to "TR",
    "TUV" to "TV",
    "TWN" to "TW",
    "TZA" to "TZ",

    // ----- U -----
    "UAE" to "AE",
    "UGA" to "UG",
    "UKR" to "UA",
    "UN" to null,
    "URG" to "UY",
    "USA" to "US",
    "UZB" to "UZ",

    // ----- V -----
    "VCT" to "VC",
    "VEN" to "VE",
    "VIR" to "VI",
    "VRG" to "VG",
    "VTN" to "VN",
    "VUT" to "VU",

    // ----- W -----
    "WAK" to "UM",
    "WAL" to "WF",

    // ----- X -----
    "XBY" to "SD",   // Abyei (disputed Sudan/S. Sudan) → SD
    "XGZ" to "PS",   // Gaza Strip
    "XSP" to null,   // Spratly Islands
    "XUU" to null,   // Unidentified
    "XWB" to "PS",   // West Bank

    // ----- Y / Z -----
    "YEM" to "YE",
    "ZMB" to "ZM",
    "ZWE" to "ZW"
)

private val RTTY_NAME = Regex("\\bRTTY\\b")

// parsed from https://www.eibispace.de/dx/README.TXT
val LANGUAGE_CODE_TO_STRING: Map<String, String?> = mapOf(
    "A"    to "Arabic",
    "AB"   to "Abkhaz",
    "AC"   to "Aceh",
    "ACH"  to "Achang / Ngac'ang",
    "AD"   to "Adygea / Adyghe / Circassian",
    "ADI"  to "Adi",
    "AF"   to "Afrikaans",
    "AFA"  to "Afar",
    "AFG"  to "Pashto and Dari",
    "AH"   to "Amharic",
    "AJ"   to "Adja / Aja-Gbe",
    "AK"   to "Akha",
    "AKL"  to "Aklanon",
    "AL"   to "Albanian",
    "ALG"  to "Algerian (Arabic)",
    "AM"   to "Amoy",
    "AMD"  to "Tibetan Amdo (Tibet, Qinghai, Gansu, Sichuan",
    "Ang"  to "Angelus programme of Vatican Radio",
    "AR"   to "Armenian",
    "ARO"  to "Aromanian/Vlach",
    "ARU"  to "Languages of Arunachal, India (collectively)",
    "ASS"  to "Assamese",
    "ASY"  to "Assyrian/Syriac/Neo-Aramaic",
    "ATS"  to "Atsi / Zaiwa",
    "Aud"  to "Papal Audience (Vatican Radio)",
    "AV"   to "Avar",
    "AW"   to "Awadhi",
    "AY"   to "Aymara",
    "AZ"   to "Azeri/Azerbaijani",
    "BAD"  to "Badaga",
    "BAG"  to "Bagri",
    "BAI"  to "Bai",
    "BAJ"  to "Bajau",
    "BAL"  to "Balinese",
    "BAN"  to "Banjar/Banjarese",
    "BAO"  to "Baoulé",
    "BAR"  to "Bari",
    "BAS"  to "Bashkir/Bashkort",
    "BAY"  to "Bayash/Boyash (gypsy dialect of Romanian)",
    "BB"   to "Braj Bhasa/Braj Bhasha/Brij",
    "BC"   to "Baluchi",
    "BE"   to "Bengali/Bangla",
    "BED"  to "Bedawiyet / Bedawi / Beja",
    "BEM"  to "Bemba",
    "BET"  to "Bete / Bété (Guiberoua)",
    "BGL"  to "Bagheli",
    "BH"   to "Bhili",
    "BHN"  to "Bahnar",
    "BHT"  to "Bhatri",
    "BI"   to "Bilen/Bile",
    "BID"  to "Bidayuh languages",
    "BIS"  to "Bisaya",
    "BJ"   to "Bhojpuri/Bihari",
    "BK"   to "Balkarian",
    "BLK"  to "Balkan Romani",
    "BLT"  to "Balti",
    "BM"   to "Bambara/Bamanankan/Mandenkan",
    "BNA"  to "Borana Oromo/Afan Oromo",
    "BNG"  to "Bangala / Mbangala",
    "BNI"  to "Baniua/Baniwa",
    "BNJ"  to "Banjari / Banjara / Gormati / Lambadi",
    "BNT"  to "Bantawa",
    "BNY"  to "Banyumasan dialect of Javanese",
    "BON"  to "Bondo",
    "BOR"  to "Boro / Bodo",
    "BOS"  to "Bosnian (derived from Serbocroat)",
    "BR"   to "Burmese / Barma / Myanmar",
    "BRA"  to "Brahui",
    "BRB"  to "Bariba / Baatonum",
    "BRU"  to "Bru",
    "BSL"  to "Bislama",
    "BT"   to "Black Tai / Tai Dam",
    "BTK"  to "Batak-Toba",
    "BU"   to "Bulgarian",
    "BUG"  to "Bugis / Buginese",
    "BUK"  to "Bukharian/Bukhori",
    "BUN"  to "Bundeli / Bundelkhandi / Bundelkandi",
    "BUR"  to "Buryat",
    "BUY"  to "Bouyei/Buyi/Yay",
    "BY"   to "Byelorussian / Belarusian",
    "C"    to "Chinese (not further specified)",
    "CA"   to "Cantonese / Yue",
    "CC"   to "Chaochow/Chaozhou (Min-Nan dialect)",
    "CD"   to "Chowdary/Chaudhry/Chodri",
    "CEB"  to "Cebuano",
    "CH"   to "Chin (not further specified)",
    "C-A"  to "Chin-Asho",
    "C-D"  to "Chin-Daai",
    "C-F"  to "Chin-Falam / Halam",
    "C-H"  to "Chin-Haka",
    "CHA"  to "Cha'palaa / Chachi",
    "CHE"  to "Chechen",
    "CHG"  to "Chhattisgarhi",
    "CHI"  to "Chitrali / Khowar",
    "C-K"  to "Chin-Khumi",
    "C-M"  to "Chin-Mro",
    "C-O"  to "Chin-Thado / Thadou-Kuki",
    "CHR"  to "Chrau",
    "CHU"  to "Chuwabu",
    "C-T"  to "Chin-Tidim",
    "C-Z"  to "Chin-Zomin / Zomi-Chin",
    "CKM"  to "Chakma",
    "CKW"  to "Chokwe",
    "COF"  to "Cofan / Cofán",
    "COK"  to "Cook Islands Maori / Rarotongan",
    "CR"   to "Creole / Haitian",
    "CRU"  to "Chru",
    "CT"   to "Catalan",
    "CV"   to "Chuvash",
    "CVC"  to "Chavacano/Chabacano",
    "CW"   to "Chewa/Chichewa/Nyanja/Chinyanja",
    "CZ"   to "Czech",
    "D"    to "German",
    "D-P"  to "Lower German",
    "DA"   to "Danish",
    "DAH"  to "Dahayia",
    "DAO"  to "Dao",
    "DAR"  to "Dargwa/Dargin",
    "DD"   to "Dhodiya / Dhodia",
    "DEC"  to "Deccan/Deccani/Desi",
    "DEG"  to "Degar / Montagnard (Vietnam)",
    "DEN"  to "Dendi",
    "DEO"  to "Deori",
    "DES"  to "Desiya / Deshiya",
    "DH"   to "Dhivehi",
    "DI"   to "Dinka",
    "DIM"  to "Dimasa/Dhimasa",
    "DIT"  to "Ditamari",
    "DO"   to "Dogri (sometimes includes Kangri dialect)",
    "DR"   to "Dari / Eastern Farsi",
    "DU"   to "Dusun",
    "DUN"  to "Dungan",
    "DY"   to "Dyula/Jula",
    "DZ"   to "Dzongkha",
    "E"    to "English",
    "EC"   to "Eastern Cham",
    "EGY"  to "Egyptian Arabic",
    "EO"   to "Esperanto",
    "ES"   to "Estonian",
    "EWE"  to "Ewe / Éwé",
    "F"    to "French",
    "FA"   to "Faroese",
    "FI"   to "Finnish",
    "FJ"   to "Fijian",
    "FON"  to "Fon / Fongbe",
    "FP"   to "Filipino (based on Tagalog)",
    "FS"   to "Farsi / Iranian Persian",
    "FT"   to "Fiote / Vili",
    "FU"   to "Fulani/Fulfulde",
    "FUJ"  to "FutaJalon / Pular",
    "FUR"  to "Fur",
    "GA"   to "Garhwali",
    "GAG"  to "Gagauz",
    "GAR"  to "Garo",
    "GD"   to "Greenlandic Inuktikut",
    "GE"   to "Georgian",
    "GI"   to "Gilaki",
    "GJ"   to "Gujari/Gojri",
    "GL"   to "Galicic/Gallego",
    "GM"   to "Gamit",
    "GNG"  to "Gurung (Eastern and Western)",
    "GO"   to "Gorontalo",
    "GON"  to "Gondi",
    "GR"   to "Greek",
    "GU"   to "Gujarati",
    "GUA"  to "Guaraní",
    "GUN"  to "Gungbe / Gongbe / Goun",
    "GUR"  to "Gurage/Guragena",
    "GZ"   to "Ge'ez / Geez (liturgic language of Ethiopia)",
    "HA"   to "Haussa",
    "HAD"  to "Hadiya",
    "HAR"  to "Haryanvi /  Bangri / Harayanvi / Hariyanvi",
    "HAS"  to "Hassinya/Hassaniya",
    "HB"   to "Hebrew",
    "HD"   to "Hindko (Northern and Southern)",
    "HI"   to "Hindi",
    "HIM"  to "Himachali languages",
    "HK"   to "Hakka",
    "HM"   to "Hmong/Miao languages",
    "HMA"  to "Hmar",
    "HMB"  to "Hmong-Blue/Njua",
    "HMQ"  to "Hmong/Miao, Northern Qiandong / Black Hmong",
    "HMW"  to "Hmong-White/Daw",
    "HN"   to "Hani",
    "HO"   to "Ho",
    "HR"   to "Croatian/Hrvatski",
    "HRE"  to "Hre",
    "HU"   to "Hungarian",
    "HUI"  to "Hui / Huizhou",
    "HZ"   to "Hazaragi",
    "I"    to "Italian",
    "IB"   to "Iban",
    "IBN"  to "Ibanag",
    "IF"   to "Ifè / Ife",
    "IG"   to "Igbo / Ibo",
    "ILC"  to "Ilocano",
    "ILG"  to "Ilonggo / Hiligaynon",
    "IN"   to "Indonesian / Bahasa Indonesia",
    "INU"  to "Inuktikut",
    "IRQ"  to "Iraqi Arabic",
    "IS"   to "Icelandic",
    "ISA"  to "Isan / Northeastern Thai",
    "ITA"  to "Itawis / Tawit",
    "J"    to "Japanese",
    "JAI"  to "Jaintia / Pnar / Synteng",
    "JEH"  to "Jeh",
    "JG"   to "Jingpho",
    "JOR"  to "Jordanian Arabic",
    "JR"   to "Jarai / Giarai / Jra",
    "JU"   to "Juba Arabic",
    "JV"   to "Javanese",
    "K"    to "Korean",
    "KA"   to "Karen (unspecified)",
    "K-G"  to "Karen-Geba",
    "K-K"  to "Karen-Geko / Gekho",
    "K-M"  to "Manumanaw Karen / Kawyaw / Kayah",
    "K-P"  to "Karen-Pao / Black Karen / Pa'o",
    "K-S"  to "Karen-Sgaw / S'gaw",
    "K-W"  to "Karen-Pwo",
    "KAD"  to "Kadazan",
    "KAL"  to "Kalderash Romani (Dialect of Vlax)",
    "KAB"  to "Kabardian",
    "KAM"  to "Kambaata",
    "KAN"  to "Kannada",
    "KAO"  to "Kaonde",
    "KAR"  to "Karelian",
    "KAT"  to "Katu",
    "KAU"  to "Kau Bru / Kaubru/ Riang",
    "KAY"  to "Kayan",
    "KB"   to "Kabyle",
    "KBO"  to "Kok Borok/Tripuri",
    "KC"   to "Kachin / Jingpho",
    "KEN"  to "Kenyah",
    "KG"   to "Kyrgyz /Kirghiz",
    "KGU"  to "Kalanguya / Kallahan",
    "KH"   to "Khmer",
    "KHA"  to "Kham / Khams, Eastern",
    "KHM"  to "Khmu",
    "KHR"  to "Kharia / Khariya",
    "KHS"  to "Khasi / Kahasi",
    "KHT"  to "Khota (India)",
    "KIM"  to "Kimwani",
    "KIN"  to "Kinnauri / Kinori",
    "KiR"  to "KiRundi",
    "KIS"  to "Kisili",
    "KK"   to "KiKongo/Kongo",
    "KKA"  to "Kankana-ey",
    "KKN"  to "Kukna",
    "KKU"  to "Korku",
    "KMB"  to "Kimbundu/Mbundu/Luanda",
    "KMY"  to "Kumyk",
    "KND"  to "Khandesi",
    "KNG"  to "Kangri",
    "KNK"  to "KinyaRwanda",
    "KNU"  to "Kanuri",
    "KNY"  to "Konyak Naga",
    "KOH"  to "Koho/Kohor",
    "KOK"  to "Kokang Shan",
    "KOM"  to "Komering",
    "KON"  to "Konkani",
    "KOR"  to "Korambar / Kurumba Kannada",
    "KOT"  to "Kotokoli / Tem",
    "KOY"  to "Koya",
    "KPK"  to "Karakalpak",
    "KRB"  to "Karbi / Mikir / Manchati",
    "KRI"  to "Krio",
    "KRW"  to "KinyaRwanda",
    "KRY"  to "Karay-a",
    "KS"   to "Kashmiri",
    "KT"   to "Kituba (simplified Kikongo)",
    "KTW"  to "Kotwali (dialect of Bhili)",
    "KU"   to "Kurdish",
    "KuA"  to "Kurdish and Arabic",
    "KuF"  to "Kurdish and Farsi",
    "KUI"  to "Kui",
    "KUL"  to "Kulina",
    "KUM"  to "Kumaoni/Kumauni",
    "KUN"  to "Kunama",
    "KUP"  to "Kupia / Kupiya",
    "KUR"  to "Kurukh/Kurux",
    "KUs"  to "Sorani (Central) Kurdish",
    "KUT"  to "Kutchi",
    "KUV"  to "Kuvi",
    "KVI"  to "Kulluvi/Kullu",
    "KWA"  to "Kwanyama/Kuanyama (dialect of OW)",
    "KYB"  to "Kayan dialects of Borneo",
    "KYH"  to "Kayah",
    "KZ"   to "Kazakh",
    "L"    to "Latin",
    "LA"   to "Ladino",
    "LAD"  to "Ladakhi / Ladak",
    "LAH"  to "Lahu",
    "LAK"  to "Lak",
    "LAM"  to "Lampung",
    "LAO"  to "Lao",
    "LB"   to "Lun Bawang / Murut",
    "LBN"  to "Lebanon Arabic (North Levantine)",
    "LBO"  to "Limboo /Limbu",
    "LEP"  to "Lepcha",
    "LEZ"  to "Lezgi",
    "LIM"  to "Limba",
    "LIN"  to "Lingala",
    "LIS"  to "Lisu",
    "LND"  to "Lunda",
    "LNG"  to "Lungeli Magar",
    "LO"   to "Lomwe / Ngulu",
    "LOK"  to "Lokpa / Lukpa / Lupka",
    "LOZ"  to "Lozi / Silozi",
    "LT"   to "Lithuanian",
    "LTO"  to "Oriental Liturgy of Vatican Radio",
    "LU"   to "Lunda",
    "LUB"  to "Luba",
    "LUC"  to "Luchazi",
    "LUG"  to "Luganda",
    "LUN"  to "Lunyaneka/Nyaneka",
    "LUR"  to "Luri, Northern and Southern",
    "LUV"  to "Luvale",
    "LV"   to "Latvian",
    "M"    to "Mandarin (Standard Chinese / Beijing dialect)",
    "MA"   to "Maltese",
    "MAD"  to "Madurese/Madura",
    "MAG"  to "Maghi/Magahi/Maghai",
    "MAI"  to "Maithili / Maithali",
    "MAK"  to "Makonde",
    "MAL"  to "Malayalam",
    "MAM"  to "Maay / Mamay / Rahanweyn",
    "MAN"  to "Mandenkan (dialect continuum of BM, DY, MLK)",
    "MAO"  to "Maori",
    "MAR"  to "Marathi",
    "MAS"  to "Maasai/Massai/Masai",
    "MC"   to "Macedonian",
    "MCH"  to "Mavchi/Mouchi/Mauchi/Mawchi",
    "MEI"  to "Meithei/Manipuri/Meitei",
    "MEN"  to "Mende",
    "MEW"  to "Mewari/Mewadi (a Rajasthani variety)",
    "MGA"  to "Magar (Western and Eastern)",
    "MIE"  to "Mien / Iu Mien",
    "MIS"  to "Mising",
    "MKB"  to "Minangkabau",
    "MKS"  to "Makassar/Makasar",
    "MKU"  to "Makua / Makhuwa",
    "ML"   to "Malay / Baku",
    "MLK"  to "Malinke/Maninka (We/Ea)",
    "MLT"  to "Malto / Kumarbhag Paharia",
    "MNA"  to "Mina / Gen",
    "MNB"  to "Manobo / T'duray",
    "MNE"  to "Montenegrin",
    "MNO"  to "Mnong (Ea,Ce,So)",
    "MO"   to "Mongolian",
    "MON"  to "Mon",
    "MOO"  to "Moore/Mòoré/Mossi",
    "MOR"  to "Moro/Moru/Muro",
    "MR"   to "Maronite / Cypriot Arabic",
    "MRC"  to "Moroccan/Mugrabian Arabic",
    "MRI"  to "Mari",
    "MRU"  to "Maru / Lhao Vo",
    "MSY"  to "Malagasy",
    "MUN"  to "Mundari",
    "MUO"  to "Muong",
    "MUR"  to "Murut",
    "MV"   to "Malvi",
    "MW"   to "Marwari (a Rajasthani variety)",
    "MX"   to "Macuxi/Macushi",
    "MY"   to "Maya (Yucatec)",
    "MZ"   to "Mizo / Lushai",
    "NAG"  to "Naga (var.incl. Ao,Makware)",
    "NAP"  to "Naga Pidgin / Bodo / Nagamese",
    "NDA"  to "Ndau",
    "NDE"  to "Ndebele",
    "NE"   to "Nepali/Lhotshampa",
    "NG"   to "Nagpuri / Sadani / Sadari / Sadri",
    "NGA"  to "Ngangela/Nyemba",
    "NIC"  to "Nicobari",
    "NIG"  to "Nigerian Pidgin",
    "NIS"  to "Nishi/Nyishi",
    "NIU"  to "Niuean",
    "NJ"   to "Ngaju Dayak",
    "NL"   to "Dutch",
    "NLA"  to "Nga La / Matu Chin",
    "NO"   to "Norwegian",
    "NOC"  to "Nocte / Nockte",
    "NP"   to "Nupe",
    "NTK"  to "Natakani / Netakani / Varhadi-Nagpuri",
    "NU"   to "Nuer",
    "NUN"  to "Nung",
    "NW"   to "Newar/Newari",
    "NY"   to "Nyanja",
    "OG"   to "Ogan",
    "OH"   to "Otjiherero",
    "OO"   to "Oromo",
    "OR"   to "Odia / Oriya / Orissa",
    "OS"   to "Ossetic",
    "OW"   to "Oshiwambo",
    "P"    to "Portuguese",
    "PAL"  to "Palaung - Pale (Ruching)",
    "PAS"  to "Pasemah",
    "PED"  to "Pedi",
    "PJ"   to "Punjabi",
    "PO"   to "Polish",
    "POR"  to "Po",
    "POT"  to "Pothwari",
    "PS"   to "Pashto / Pushtu",
    "PU"   to "Pulaar",
    "Q"    to "Quechua",
    "QQ"   to "Qashqai",
    "R"    to "Russian",
    "RAD"  to "Rade/Ede",
    "RAJ"  to "Rajasthani",
    "RAK"  to "Rakhine/Arakanese",
    "RAT"  to "Rathivi",
    "REN"  to "Rengao",
    "RGM"  to "Rengma Naga",
    "RO"   to "Romanian",
    "ROG"  to "Roglai (Northern, Southern)",
    "ROH"  to "Rohingya (rjj)",
    "RON"  to "Rongmei Naga",
    "Ros"  to "Rosary session of Vatican Radio",
    "RU"   to "Rusyn / Ruthenian",
    "RUM"  to "Rumai Palaung",
    "RWG"  to "Rawang",
    "S"    to "Spanish/Castellano",
    "SAH"  to "Saho",
    "SAN"  to "Sango",
    "SAR"  to "Sara/Sar",
    "SAS"  to "Sasak",
    "SC"   to "Serbocroat",
    "SCA"  to "Scandinavian languages (Norwegian, Swedish, Finnish)",
    "SD"   to "Sindhi",
    "SED"  to "Sedang",
    "SEF"  to "Sefardi/Judeo Spanish/Ladino",
    "SEN"  to "Sena",
    "SFO"  to "Senoufo/Sénoufo-Syenara",
    "SGA"  to "Shangaan/Tsonga",
    "SGM"  to "Sara Gambai / Sara Ngambai",
    "SGO"  to "Songo",
    "SGT"  to "Sangtam",
    "SHA"  to "Shan",
    "SHC"  to "Sharchogpa / Sarchopa / Tshangla",
    "SHE"  to "Sheena/Shina",
    "SHK"  to "Shiluk/Shilluk",
    "SHO"  to "Shona",
    "SHP"  to "Sherpa",
    "SHT"  to "Shan-Khamti",
    "SHU"  to "Shuwa Arabic",
    "SI"   to "Sinhalese/Sinhala",
    "SID"  to "Sidamo/Sidama",
    "SIK"  to "Sikkimese/Bhutia",
    "SIR"  to "Siraiki/Seraiki",
    "SK"   to "Slovak",
    "SLM"  to "Pijin/Solomon Islands Pidgin",
    "SLT"  to "Silte / East Gurage / xst",
    "SM"   to "Samoan",
    "SMP"  to "Sambalpuri / Sambealpuri",
    "SNK"  to "Sanskrit",
    "SNT"  to "Santhali",
    "SO"   to "Somali",
    "SON"  to "Songhai",
    "SOT"  to "SeSotho",
    "SR"   to "Serbian",
    "SRA"  to "Soura / Sora",
    "STI"  to "Stieng",
    "SUA"  to "Shuar",
    "SUD"  to "Sudanese Arabic",
    "SUM"  to "Sumi Naga",
    "SUN"  to "Sunda/Sundanese",
    "SUR"  to "Surgujia",
    "SUS"  to "Sudan",
    "SV"   to "Slovenian",
    "SWA"  to "Swahili/Kisuaheli",
    "SWE"  to "Swedish",
    "SWZ"  to "SiSwati",
    "T"    to "Thai",
    "TAG"  to "Tagalog",
    "TAH"  to "Tachelhit/Sous",
    "TAL"  to "Talysh",
    "TAM"  to "Tamil",
    "TAU"  to "Tausug",
    "TB"   to "Tibetan / Lhasa Tibetan",
    "TBL"  to "Tboli / T'boli / Tagabili",
    "TBS"  to "Tabasaran",
    "TEL"  to "Telugu",
    "TEM"  to "Temme/Temne",
    "TFT"  to "Tarifit",
    "TGB"  to "Tagabawa / Bagobo",
    "TGK"  to "Tangkhul/Tangkul Naga",
    "TGR"  to "Tigre/Tigré/Tigrawit",
    "TGS"  to "Tangsa/Naga-Tase",
    "THA"  to "Tharu Buksa",
    "TIG"  to "Tigrinya/Tigray",
    "TJ"   to "Tajik",
    "TK"   to "Turkmen",
    "TKL"  to "Tagakaulo (dialect of Kalagan)",
    "TL"   to "Tai-Lu/Lu",
    "TM"   to "Tamazight",
    "TMG"  to "Tamang",
    "TMJ"  to "Tamajeq",
    "TN"   to "Tai-Nua/Chinese Shan",
    "TNG"  to "Tonga",
    "TO"   to "Tongan",
    "TOK"  to "Tokelau",
    "TOR"  to "Torajanese/Toraja",
    "TP"   to "Tok Pisin",
    "TS"   to "Tswana / SeTswana",
    "TSA"  to "Tsangla",
    "TSH"  to "Tshwa",
    "TT"   to "Tatar",
    "TTB"  to "Tatar-Bashkir",
    "TU"   to "Turkish",
    "TUL"  to "Tulu",
    "TUM"  to "Tumbuka",
    "TUN"  to "Tunisian Arabic",
    "TUR"  to "Turkana",
    "TV"   to "Tuva / Tuvinic",
    "TW"   to "Taiwanese/Fujian/Hokkien/Min",
    "TWI"  to "Twi/Akan",
    "TWT"  to "Tachawit/Shawiya/Chaouia",
    "TZ"   to "Tamazight/Berber",
    "UA"   to "Uab Meto / Dawan / Baikenu",
    "UD"   to "Udmurt",
    "UI"   to "Uighur",
    "UK"   to "Ukrainian",
    "UM"   to "Umbundu",
    "UR"   to "Urdu",
    "UZ"   to "Uzbek",
    "V"    to "Vasco / Basque / Euskera",
    "VAD"  to "Vadari / Waddar / Od",
    "VAR"  to "Varli / Warli",
    "Ves"  to "Vespers (Vatican Radio)",
    "Vn"   to "Vernacular = local language(s)",
    "VN"   to "Vietnamese",
    "VV"   to "Vasavi",
    "VX"   to "Vlax Romani / Romanes / Gypsy",
    "W"    to "Wolof",
    "WA"   to "Wa / Parauk",
    "WAO"  to "Waodani/Waorani",
    "WE"   to "Wenzhou",
    "WT"   to "White Tai / Tai Don",
    "WU"   to "Wu",
    "XH"   to "Xhosa",
    "YAO"  to "Yao/Yawo",
    "YER"  to "Yerukula",
    "YI"   to "Yi / Nosu",
    "YK"   to "Yakutian / Sakha",
    "YO"   to "Yoruba",
    "YOL"  to "Yolngu/Yuulngu",
    "YUN"  to "Dialects/languages of Yunnan (China)",
    "YZ"   to "Yezidi program (Kurdish-Kurmanji language)",
    "Z"    to "Zulu",
    "ZA"   to "Zarma/Zama",
    "ZD"   to "Zande",
    "ZG"   to "Zaghawa",
    "ZH"   to "Zhuang",
    "ZWE"  to "Languages of Zimbabwe",
)

val TRANSMITTER_SITE_CODES: Map<String, Map<String, Pair<String?,String?>>> = mapOf(
    "AFG" to mapOf(
        "k" to Pair("Kabul / Pol-e-Charkhi", "34N32-69E20"),
        "x" to Pair("Khost", "33N14-69E49"),
        "y" to Pair("Kabul-Yakatut", "34N32-69E13")
    ),
    "AFS" to mapOf(
        "*" to Pair("Meyerton  except:", "26S35-28E08"),
        "ct" to Pair("Cape Town", "33S41-18E42"),
        "j" to Pair("Johannesburg", "26S07'40\"-28E12'20\"")
    ),
    "AGL" to mapOf(
        "L" to Pair("Luena (Moxico)", "11S47'00\"-19E55'19\""),
        "lu" to Pair("Luanda Radio", "08S48-13E16"),
        "m" to Pair("Luanda - Mulenvos", "08S51-13E19")
    ),
    "AIA" to mapOf(
        "*" to Pair("The Valley", "18N13-63W01")
    ),
    "ALB" to mapOf(
        "c" to Pair("Cerrik (CRI)", "41N00-20E00"),
        "f" to Pair("Fllake (Durres, 500kW)", "41N22-19E30"),
        "s" to Pair("Shijiak (Radio Tirana) (1x100kW = 2x50kW)", "41N20-19E33")
    ),
    "ALG" to mapOf(
        "ad" to Pair("Adrar", "27N52-00W17"),
        "al" to Pair("Algiers", "36N46-03E03"),
        "an" to Pair("Annaba", "36N54-07E46"),
        "b" to Pair("Béchar", "31N34-02W21"),
        "fk" to Pair("F'Kirina (Aïn Beïda)", "35N44-07E21"),
        "o" to Pair("Ouargla / Ourgla", "31N55-05E04"),
        "of" to Pair("Ouled Fayet", "36N43-02E57"),
        "or" to Pair("Oran 7TO", "35N46-00W33"),
        "r" to Pair("Reggane", "26N42-00E10"),
        "s" to Pair("In Salah (Ain Salih)", "27N15-02E31"),
        "t" to Pair("Tindouf (Rabbouni)", "27N33-08W06"),
        "tm" to Pair("Timimoun", "29N16-00E14")
    ),
    "ALS" to mapOf(
        "an" to Pair("Annette", "55N03-131W34"),
        "ap" to Pair("Anchor Point", "59N44'58\"-151W43'56\""),
        "ba" to Pair("Barrow", "71N15'30\"-156W34'39\""),
        "cb" to Pair("Cold Bay", "55N13-162W43"),
        "e" to Pair("Elmendorf AFB", "61N15'04\"-149W48'23\""),
        "g" to Pair("Gakona", "62N23'30\"-145W08'48\""),
        "k" to Pair("Kodiak", "57N46'30\"-152W32"),
        "ks" to Pair("King Salmon", "58N41-156W40"),
        "no" to Pair("Nome", "64N31-165W25")
    ),
    "ARG" to mapOf(
        "b" to Pair("Buenos Aires", "34S37'19\"-58W21'18\""),
        "co" to Pair("Córdoba", "31S18'33\"-64W13'34\""),
        "cr" to Pair("Comodoro Rivadavia (Navy)", "45S53'01\"-67W30'33\""),
        "cv" to Pair("Comodoro Rivadavia (Air)", "45S47'29\"-67W28'46\""),
        "e" to Pair("Ezeiza, Prov. Buenos Aires", "34S49'58\"-58W31'55\""),
        "g" to Pair("General Pacheco", "34S36-58W22"),
        "mp" to Pair("Mar del Plata, Prov. Buenos Aires", "38S03-57W32"),
        "r" to Pair("Resistencia, Chaco", "27S27'51\"-59W04'14\""),
        "xx" to Pair("Unknown site", null)
    ),
    "ARM" to mapOf(
        "*" to Pair("Gavar (formerly Kamo)", "40N25-45E12"),
        "y" to Pair("Yerevan", "40N10-44E30")
    ),
    "ARS" to mapOf(
        "j" to Pair("Jeddah/Jiddah", "21N15-39E10"),
        "jr" to Pair("Jiddah Radio", "21N23-39E10"),
        "jz" to Pair("Jazan", "16N53-42E34"),
        "nj" to Pair("Najran", "17N30-44E08"),
        "r" to Pair("Riyadh", "24N30-46E23")
    ),
    "ASC" to mapOf(
        "*" to Pair("Ascension Island,", "07S54-14W23")
    ),
    "ATA" to mapOf(
        "e" to Pair("Base Esperanza", "63S24-57W00"),
        "f" to Pair("Bahia Fildes, King George Island", "62S12-58W58"),
        "ma" to Pair("Maramio Base, Seymour Island", "64S14-56W38")
    ),
    "AUS" to mapOf(
        "a" to Pair("Alice Springs NT", "23S49-133E51"),
        "ae" to Pair("Aero sites: Cape Pallarenda  and Broken Hill 31S55'38\"-141E28'57\" and Knuckeys Lagoon 12S25'52\"-130E57'51\"", "19S12'05\"-146E46'05\""),
        "al" to Pair("VKS737 Alice Springs NT", "23S41-133E52"),
        "as" to Pair("Alice Springs NT", "23S47'48\"-133E52'28\""),
        "at" to Pair("Alice Springs NT", "23S46'45\"-133E52'25\""),
        "av" to Pair("Alice Springs Velodrome NT", "23S40'14\"-133E51'54\""),
        "b" to Pair("Brandon QL", "19S31-147E20"),
        "be" to Pair("Bendigo VIC", "36S35'25\"-144E14'39\""),
        "bm" to Pair("Broadmeadows VIC", "37S41'31\"-144E56'44\""),
        "c" to Pair("Charleville QL", "26S25-146E15"),
        "ca" to Pair("Casino NSW", "28S52'31\"-153E03'04\""),
        "ch" to Pair("VKS737 Charter Towers QLD", "20S09-146E18"),
        "cl" to Pair("RFDS Charleville QLD", "26S24'55\"-146E13'35\""),
        "ct" to Pair("VKS737 Charter Towers QLD 20S05'06\"146E15'34\"", null),
        "cv" to Pair("RFDS Carnarvon WA", "24S53'20\"-113E40'24\""),
        "du" to Pair("Dural/Sydney NSW -", "33S41'55\"-151E03'20\""),
        "ee" to Pair("VKS737 Adelaide/Elizabeth East SA", "34S43'20\"-138E40'59\""),
        "ex" to Pair("Exmouth WA", "21S49-114E10"),
        "g" to Pair("Gunnedah NSW", "30S59-150E15"),
        "h" to Pair("Humpty Doo NT", "12S34-131E05"),
        "hc" to Pair("Halls Creek NSW, 50 km NE of Tamsworth", null),
        "hp" to Pair("Hurlstone Park, Sydney NSW", "33S54'20\"-151E07'56\""),
        "il" to Pair("VKS737 Alice Springs-Ilparpa NT", "23S45'20\"-133E49'26\""),
        "in" to Pair("Innisfail QL", "17S32-146E03"),
        "ka" to Pair("Katherine NT", "14S24-132E11"),
        "kd" to Pair("RFDS Kuranda QLD", "16S50'12\"-145E36'45\""),
        "kr" to Pair("Kuranda QLD", "16S49-145E38"),
        "ku" to Pair("Kununurra WA  (24 Jul 2012 moved to \"Lot 3000\" 2 mi west)", "15S49-128E40"),
        "kw" to Pair("Kununurra WA", "15S46'17\"-128E43'46\""),
        "L" to Pair("Sydney-Leppington NSW", "33S58-150E48"),
        "m" to Pair("Macleay Island QL", "27S37-153E21"),
        "ma" to Pair("Manilla NSW", "30S44'23\"-150E42'56\""),
        "md" to Pair("Mareeba-Dimbulah QL", "17S10-145E05"),
        "mi" to Pair("RFDS Mount Isa QLD 20S 43'31\"-139E29'14\"", null),
        "mk" to Pair("RFDS Meekatharra WA", "26S35'12\"-118E30'03\""),
        "n" to Pair("Ningi QL", "27S04'00\"-153E03'20\""),
        "nc" to Pair("VKS737 Newcastle/Edgeworth NSW", "32S55'20\"-151E36'28\""),
        "pc" to Pair("Perth / Chittering WA", "31S29'40\"-116E04'52\""),
        "pe" to Pair("Penong SA", "31S55'28\"-132E59'28\""),
        "pw" to Pair("VKS737 Perth/Wanneroo WA", "31S46'01\"-115E48'15\""),
        "ri" to Pair("Russell Island QL", "27S40-153E21"),
        "rm" to Pair("Roma QL", "26S33-148E48"),
        "rz" to Pair("Razorback NSW", "34S09-150E40"),
        "s" to Pair("Shepparton VIC", "36S20-145E25"),
        "sa" to Pair("Shepparton-Ardmona VIC", "36S21'39\"-145E17'38\""),
        "sb" to Pair("VKS737 Stawell-Black Range VIC", "37S06'01\"-142E45'14\""),
        "sf" to Pair("Schofields, western Sydney", "33S42-150E52"),
        "sm" to Pair("St Mary's, Sydney", "33S45-150E46"),
        "st" to Pair("VKS737 St Marys TAS", "41S35'08\"-148E12'53\""),
        "t" to Pair("Tennant Creek NT", "19S40-134E16"),
        "va" to Pair("VKS737 Adelaide/Virginia SA", "34S40'52\"-138E35'35\""),
        "vs" to Pair("VKS737 at sites va/ee/st/nc/ch/ct/pw/il/al/sb", null),
        "w" to Pair("Wiluna WA", "26S20-120E34"),
        "ww" to Pair("Wee Waa NSW", "30S12'55\"-149E27'26\"")
    ),
    "AUT" to mapOf(
        "*" to Pair("Moosbrunn", "48N00-16E28")
    ),
    "AZE" to mapOf(
        "b" to Pair("Baku", "40N28-50E03"),
        "g" to Pair("Gäncä", "40N36-46E20"),
        "s" to Pair("Stepanakert", "39N49'35\"-46E44'23\"")
    ),
    "AZR" to mapOf(
        "ho" to Pair("Horta", "38N32-28W38"),
        "lj" to Pair("Lajes Field", "38N46-27E05"),
        "sm" to Pair("Santa Maria", "36N56'50\"-25W09'30\"")
    ),
    "B" to mapOf(
        "a" to Pair("Porto Alegre, RS", "30S01'25\"-51W15'19\""),
        "ag" to Pair("Araguaína, TO", "07S12-48W12"),
        "am" to Pair("Amparo, SP", "22S42-46W46"),
        "an" to Pair("Anápolis, GO", "16S15'25\"-49W01'08\""),
        "ap" to Pair("Aparecida, SP", "22S50'47\"-45W13'13\""),
        "ar" to Pair("Araraquara, SP", "21S48-48W11"),
        "b" to Pair("Brasilia, Parque do Rodeador, DF", "15S36'40\"-48W07'53\""),
        "be" to Pair("Belém, PA", "01S27-48W29"),
        "bh" to Pair("Belo Horizonte, Minas Gerais", "19S55-43W56"),
        "br" to Pair("Braganca, PA", "01S03'48\"-46W46'24\""),
        "bt" to Pair("Belém, PA (Ondas Tropicais 5045)", "01S22-48W21"),
        "bv" to Pair("Boa Vista, RR", "02N55'19\"-60W42'38\""),
        "c" to Pair("Contagem/Belo Horizonte, MG", "19S53'59\"-44W03'16\""),
        "ca" to Pair("Campo Largo (Curitiba), PR", "25S25'48\"-49W23'49\""),
        "cb" to Pair("Camboriú, SC", "27S02'25\"-48W39'17\""),
        "cc" to Pair("Cáceres, MT", "16S04'36\"-57W38'27\""),
        "cg" to Pair("Campo Grande, MS", "20S31'12\"-54W35'00\""),
        "Cg" to Pair("Campo Grande, MS", "20S27-54W37"),
        "cl" to Pair("Cabedelo, PB", null),
        "cm" to Pair("Campinas, SP", "22S56'52\"-47W01'05\""),
        "cn" to Pair("Congonhas, MG", "20S30-43W52"),
        "co" to Pair("Coari, AM", "04S06'59\"-63W07'31\""),
        "cp" to Pair("Cachoeira Paulista, SP", "22S38'45\"-45W04'42\""),
        "Cp" to Pair("Cachoeira Paulista, SP", "22S38'39\"-45W04'38\""),
        "cs" to Pair("Cruzeiro do Sul, Estrada do Aeroporto, AC", "07S38-72W40"),
        "cu" to Pair("Curitiba, PR", "25S27'08\"-49W06'50\""),
        "cv" to Pair("Cuiabá, MT", "15S37'07\"-56W05'52\""),
        "c2" to Pair("Curitiba, PR RB2", "25S23'34\"-49W10'04\""),
        "E" to Pair("Esteio (Porto Alegre), RS", "29S49'41\"-51W09'54\""),
        "e" to Pair("Esteio (Porto Alegre), RS", "29S51'59\"-51W06'11\""),
        "f" to Pair("Foz do Iguacu, PR", "25S31'03\"-54W30'30\""),
        "fl" to Pair("Florianópolis, SC", "27S36'09\"-48W31'51\""),
        "fp" to Pair("Florianópolis - Comboriú, SC", "27S02'24\"-48W39'17\""),
        "g" to Pair("Guarujá, SP", "23S59'35\"-46W15'23\""),
        "gb" to Pair("Guaíba (Porto Alegre), RS", "29S59'50\"-51W17'08\""),
        "gc" to Pair("Sao Gabriel de Cachoeira, AM", "00S08-67W05"),
        "gm" to Pair("Guajará-Mirim, RO", "10S47-65W20"),
        "go" to Pair("Goiânia,", "16S39'30\"-49W13'38\""),
        "gu" to Pair("Guarulhos, SP", "23S26-46W25"),
        "h" to Pair("Belo Horizonte, MG", "19S58'34\"-43W56'00\""),
        "ib" to Pair("Ibitinga, SP", "21S46'20\"-48W50'10\""),
        "it" to Pair("Itapevi, SP", "23S30'39\"-46W40'34\""),
        "ld" to Pair("Londrina, PR", "23S20'16\"-51W13'18\""),
        "li" to Pair("Limeira, SP", "22S33'39\"-47W25'08\""),
        "lj" to Pair("Lajeado, RS", "29S28-51W58"),
        "lo" to Pair("Londrina, PR", "23S24'17\"-51W09'19\""),
        "m" to Pair("Manaus AM", "03S06-60W02"),
        "ma" to Pair("Manaus - Radiodif.Amazonas, AM", "03S08'16\"-59W58'53\""),
        "mc" to Pair("Macapá, AP", "00N03'50\"-51W02'20\""),
        "mg" to Pair("Manaus - Radio Globo, AM", "03S08'04\"-59W58'39\""),
        "mi" to Pair("Marília, SP", "22S13'33\"-49W57'46\""),
        "mm" to Pair("São Mateus do Maranhão, Maranhão", "04S02-44W28"),
        "mo" to Pair("Mogi das Cruces, SP", "23S30'55\"-46W12'08\""),
        "mp" to Pair("Mirandópolis, SP", "21S08-51W06"),
        "mr" to Pair("Manaus - Radio Rio Mar, AM", "03S07'18\"-60W02'30\""),
        "ob" to Pair("Óbidos, PA", "01S55-55W31"),
        "os" to Pair("Osasco, SP", "23S30'51\"-46W35'39\""),
        "pa" to Pair("Parintins, AM", "02S37-56W45"),
        "pc" to Pair("Pocos da Caldas, MG", "21S47'52\"-46W32'26\""),
        "pe" to Pair("Petrolina, PE", "09S24-40W30"),
        "pi" to Pair("Piraquara (Curitiba), PR", "25S23'34\"-49W10'04\""),
        "pv" to Pair("Porto Velho, RO  (4 dipolos em quadrado)", "08S47'45\"-63W46'38\""),
        "r" to Pair("Rio de Janeiro (Radio Globo), RJ", "22S49'24\"-43W05'49\""),
        "rb" to Pair("Rio Branco, AC", "09S58-67W49"),
        "rc" to Pair("Rio de Janeiro (Radio Capital), RJ", "22S46'43\"-43W00'56\""),
        "re" to Pair("Recife, PE", "08S04-34W58"),
        "rj" to Pair("Rio de Janeiro (Radio Relogio), RJ", "22S46'41\"-42W59'02\""),
        "ro" to Pair("Rio de Janeiro, Observatório Nacional,", "22S53'45\"-43W13'27\""),
        "rp" to Pair("Ribeirão Preto, SP", "21S11-47W48"),
        "rs" to Pair("Rio de Janeiro (Super Radio), RJ", "22S49'22\"-43W05'21\""),
        "rw" to Pair("Rio de Janeiro PWZ", "22S57-42W55"),
        "sa" to Pair("Santarém, PA", "02S26'55\"-54W43'58\""),
        "sb" to Pair("Sao Paulo - Radio Bandeirantes, SP", "23S38'54\"-46W36'02\""),
        "sc" to Pair("Sao Paulo - Radio Cultura, SP", "23S30'42\"-46W33'41\""),
        "se" to Pair("Senador Guiomard, AC", "10S03-67W37"),
        "sg" to Pair("Sao Paulo - Radio Globo, SP", "23S36'26\"-46W26'12\""),
        "sj" to Pair("Sao Paulo - Radio 9 de Julho, SP", "23S32'51\"-46W38'10\""),
        "sm" to Pair("Santa Maria, RS", "29S44'18\"-53W33'19\""),
        "so" to Pair("Sorocaba, SP / Votorantim,", "23S33-47W26"),
        "sr" to Pair("Sao Paulo - Radio Record, SP", "23S41'02\"-46W44'35\""),
        "sy" to Pair("Sao Paulo PYB45", "23S33-46W38"),
        "sz" to Pair("Sao Paulo - Radio Gazeta, SP", "23S40'10\"-46W45'00\""),
        "ta" to Pair("Taubaté, SP", "23S01-45W34"),
        "te" to Pair("Teresina, PI", "05S05'13\"-42W45'39\""),
        "tf" to Pair("Tefé, AM", "03S21'15\"-64W42'41\""),
        "vi" to Pair("Vitória, ES", "20S19-40W19"),
        "x" to Pair("Xapuri, AC", "10S39-68W30"),
        "xm" to Pair("Unknown location in Maranhão,", "02S30-44W15"),
        "xn" to Pair("Unknown location in Paraná,", "25S00-52W00"),
        "xp" to Pair("Unknown location in Paraíba,", "07S10-36W50"),
        "xx" to Pair("Unknown location", null)
    ),
    "BEL" to mapOf(
        "o" to Pair("Oostende", "51N11-02E48"),
        "r" to Pair("Ruiselede OSN", "51N04'45\"-03E20'05\""),
        "w" to Pair("Wingene", "51N11-02E49")
    ),
    "BEN" to mapOf(
        "c" to Pair("Cotonou", "06N28-02E21"),
        "p" to Pair("Parakou", "09N20-02E38")
    ),
    "BER" to mapOf(
        "h" to Pair("Bermuda Harbour", "32N23-64W41")
    ),
    "BES" to mapOf(
        "*" to Pair("Bonaire", "12N12-68W18")
    ),
    "BFA" to mapOf(
        "*" to Pair("Ouagadougou", "12N26-01W33")
    ),
    "BGD" to mapOf(
        "d" to Pair("Dhaka-Dhamrai", "23N54-90E12"),
        "k" to Pair("Dhaka-Khabirpur", "24N00-90E15"),
        "s" to Pair("Dhaka-Savar", "23N52-90E16")
    ),
    "BHR" to mapOf(
        "a" to Pair("Abu Hayan", "26N02-50E37"),
        "m" to Pair("Al Muharraq", "26N16-50E39")
    ),
    "BIH" to mapOf(
        "b" to Pair("Bijeljina", "44N42-19E10"),
        "z" to Pair("Zavidovici", "44N26-18E09")
    ),
    "BIO" to mapOf(
        "*" to Pair("Diego Garcia", "07S26-72E26")
    ),
    "BLR" to mapOf(
        "*" to Pair("Minsk-Sasnovy/Kalodziscy  except:", "53N58-27E47"),
        "b" to Pair("Brest", "52N18-23E54"),
        "g" to Pair("Hrodna/Grodno", "53N40-23E50"),
        "m" to Pair("Mahiliou/Mogilev (\"Orsha\")", "53N37-30E20"),
        "mo" to Pair("Molodechno/Vileyka (43 Comm Center Russian Navy)", "54N28-26E47"),
        "vi" to Pair("Vitebsk", "55N08-30E21"),
        "xx" to Pair("Unknown site(s)", null)
    ),
    "BOL" to mapOf(
        "ay" to Pair("Santa Ana del Yacuma", "13S45-65W32"),
        "cb" to Pair("Cochabamba", "17S23-66W11"),
        "p" to Pair("La Paz", "16S30-68W08"),
        "ri" to Pair("Riberalta", "10S59'49\"-66W04'01\""),
        "sc" to Pair("Santa Cruz", "17S48-63W10"),
        "si" to Pair("San Ignacio de Velasco", "16S22-60W57"),
        "sz" to Pair("Santa Cruz Airport", "17S40-63W08"),
        "uy" to Pair("Uyuni", "20S28-66W49"),
        "yu" to Pair("Yura", "20S04-66W08")
    ),
    "BOT" to mapOf(
        "*" to Pair("Mopeng Hill", "21S57-27E39")
    ),
    "BTN" to mapOf(
        "*" to Pair("Thimphu", "27N28-89E39")
    ),
    "BUL" to mapOf(
        "bg" to Pair("Blagoevgrad (864)", "42N03-23E03"),
        "bk" to Pair("Bankya", "42N43'36\"-23E09'33\""),
        "do" to Pair("Doulovo (1161)", "43N49-27E09"),
        "kj" to Pair("Kardjali (963)", "41N36-25E22"),
        "p" to Pair("Plovdiv-Padarsko", "42N23-24E52"),
        "pe" to Pair("Petrich", "41N28-23E20"),
        "s" to Pair("Sofia-Kostinbrod", "42N49-23E13"),
        "sa" to Pair("Samuil (864)", "43N32'06\"-26E44'13\""),
        "sl" to Pair("Salmanovo (747)", "43N11-26E58"),
        "tv" to Pair("Targovishte (1161)", "43N15-26E31"),
        "va" to Pair("Varna", "43N04-27E47"),
        "vi" to Pair("Vidin", "43N50-22E43"),
        "vk" to Pair("Vakarel (261)", "42N34-23E42")
    ),
    "CAF" to mapOf(
        "ba" to Pair("Bangui", "04N21-18E35"),
        "bo" to Pair("Boali", "04N39-18E12")
    ),
    "CAN" to mapOf(
        "al" to Pair("Aldergrove BC, Matsqui Tx site", "49N06'30\"-122W14'40\""),
        "ap" to Pair("VAE/XLK Amphitrite Point (Tofino) BC", "48N55'31\"-125W32'25\""),
        "c" to Pair("Calgary AB", "50N54'02\"-113W52'33\""),
        "cb" to Pair("Cambridge Bay, Victoria Island NU", "69N06'53\"-105W01'11\""),
        "cc" to Pair("Churchill MB", "58N45'42\"-93W56'39\""),
        "ch" to Pair("Coral Harbour NU", "64N09'01\"-83W22'22\""),
        "cr" to Pair("Cap des Rosiers", "48N51'40\"-64W12'53\""),
        "cw" to Pair("Cartwright NL", "53N42'30\"-57W01'17\""),
        "di" to Pair("VAJ Digby Island BC", "54N17'51\"-130W25'06\""),
        "ex" to Pair("Essex County (Harrow), near Detroit, ON", "42N02'30\"-82W58'27\""),
        "fg" to Pair("CFG8525 ON", "43N52-79W19"),
        "g" to Pair("Gander NL", "48N58'05\"-54W40'26\""),
        "h" to Pair("Halifax NS", "44N41'03\"-63W36'35\""),
        "hd" to Pair("Hopedale NL", "55N27'24\"-60W12'30\""),
        "hp" to Pair("VOH-498 Hunter Point BC", "53N15'31\"-132W42'53\""),
        "hr" to Pair("Hay River", "60N50'27\"-115W46'12\""),
        "hx" to Pair("Halifax CFH NS", "44N57'50\"-63W58'55\""),
        "i" to Pair("Iqaluit NU", "63N43'52\"-68W32'32\""),
        "in" to Pair("Inuvik NWT", "68N19'33\"-133W35'53\""),
        "j" to Pair("St John's NL", "47N34'10\"-52W48'52\""),
        "k" to Pair("Killiniq/Killinek NU", "60N25'27\"-64W50'30\""),
        "ki" to Pair("Kingsburg NS", "44N16'32\"-64W17'15\""),
        "lp" to Pair("Lockeport NS", "43N39'49\"-65W07'47\""),
        "lv" to Pair("La Vernière, Îles-de-la-Madeleine QC", "47N21'26\"-61W55'36\""),
        "na" to Pair("Natashquan QC", "50N09'06\"-61W47'42\""),
        "o" to Pair("Ottawa ON", "45N17'47\"-75W45'22\""),
        "pc" to Pair("Port Caledonia NS", "46N11'14\"-59W53'59\""),
        "r" to Pair("Resolute, Cornwallis Island NU", "74N44'47\"-95W00'11\""),
        "sa" to Pair("St Anthony / Pistolet Bay NL", "51N30'00\"-55W49'26\""),
        "sj" to Pair("St John's NL", "47N36'40\"-52W40'01\""),
        "sl" to Pair("St Lawrence NL", "46N55'09\"-55W22'45\""),
        "sm" to Pair("Sambro NS", "44N28'21\"-63W37'13\""),
        "sv" to Pair("Stephenville NL", "48N33'17\"-58W45'32\""),
        "t" to Pair("Toronto (Mississauga/Clarkson) ON", "43N30'23\"-79W38'01\""),
        "tr" to Pair("Trenton (Pointe Petre, Lake Ontario)", "43N50'39\"-77W08'47\""),
        "tr2" to Pair("Trenton Receiver Site ON", "44N01'56\"-77W33'02\""),
        "v" to Pair("Vancouver BC", "49N08'21\"-123W11'44\""),
        "ym" to Pair("Yarmouth/Chebogue NS", "43N44'39\"-66W07'21\"")
    ),
    "CBG" to mapOf(
        "ka" to Pair("Kandal", "11N25-104E50")
    ),
    "CHL" to mapOf(
        "a" to Pair("Antofagasta", "23S40-70W24"),
        "e" to Pair("Radio Esperanza", "38S41-72W35"),
        "fx" to Pair("Bahia Felix", "52S57'43\"-74W04'51\""),
        "gc" to Pair("\"General Carrera\" (RCW), unknown location", null),
        "jf" to Pair("Juan Fernández", "33S38-78W50"),
        "pa" to Pair("Punta Arenas", "53S10-70W54"),
        "pm" to Pair("Puerto Montt", "41S39'20\"-73W10'24\""),
        "q" to Pair("Isla de Pascua", "27S07-109W21"),
        "s" to Pair("Santiago (Calera de Tango)", "33S38'36\"-70W51'02\""),
        "t" to Pair("Talagante", "33S40-70W56"),
        "tq" to Pair("Talcahuano, Quiriquina Island", "36S37-73W04"),
        "v" to Pair("Valparaiso  or 33S01'13\"-71W38'32\"", "32S48-71W29"),
        "w" to Pair("Wollaston Island", "55S37-67W26")
    ),
    "CHN" to mapOf(
        "a" to Pair("Baoji-Xinjie (Shaanxi; CRI 150 kW; CNR2 9820) \"722\"", "34N30-107E10"),
        "as" to Pair("Baoji-Sifangshan (Shaanxi; CNR1,8) \"724\"", "37N27-107E41"),
        "b" to Pair("Beijing-Matoucun \"572\" (100 kW CNR1)", "39N45-116E49"),
        "B" to Pair("Beijing-Chaoyang/Gaobeidian/Shuangqiao \"491\" (CNR2-8)", "39N53-116E34"),
        "bd" to Pair("Beijing-Doudian (150/500 kW CRI) \"564\"", "39N38-116E05"),
        "bm" to Pair("Beijing BAF", "39N54-116E28"),
        "bs" to Pair("Beijing 3SD", "39N42-115E55"),
        "b0" to Pair("Basuo, Hainan", "19N05'46\"-108E38'04\""),
        "c" to Pair("Chengdu (Sichuan)", "30N54-104E07"),
        "cc" to Pair("Changchun \"523\" (Jilin)", "44N01'44\"-125E25'08\""),
        "ch" to Pair("Changzhou Henglinchen \"623\" (Jiangsu)", "31N42'33\"-120E06'44\""),
        "d" to Pair("Dongfang (Hainan)", "18N53-108E39"),
        "da" to Pair("Dalian", "38N55-121E39"),
        "db" to Pair("Dongfang-Basuo", "19N06-108E37"),
        "de" to Pair("Dehong (Yunnan)", "24N27-98E36"),
        "e" to Pair("Gejiu (Yunnan)", "23N21-103E08"),
        "eb" to Pair("Beijing, Posolstvo", "39N55-116E27"),
        "f" to Pair("Fuzhou (Fujian)", "26N06-119E24"),
        "fz" to Pair("Fuzhou-Mawei XSL (Fujian)  and Tailu 26N22'07\"-119E56'22\"", "26N01-119E27"),
        "g" to Pair("Gannan (Hezuo)", "34N58'30\"-102E55"),
        "gu" to Pair("Gutian-Xincheng", "26N34-118E44"),
        "gx" to Pair("Guangzhou XSQ", "23N09'27\"-113E30'51\""),
        "gy" to Pair("Guiyang", "26N25-106E36"),
        "gz" to Pair("Guangzhou-Huadu (Guangdong)", "23N24-113E14"),
        "h" to Pair("Hohhot \"694\" (Nei Menggu, CRI)", "40N48-111E47"),
        "ha" to Pair("Hailar (Nei Menggu)", "49N11-119E43"),
        "hd" to Pair("Huadian \"763\" (Jilin)", "43N07-126E31"),
        "he" to Pair("Hezuo", "34N58'14\"-102E54'32\""),
        "hh" to Pair("Hohhot-Yijianfang (Nei Menggu, PBS NM)", "40N43-111E33"),
        "hk" to Pair("Haikou (Hainan) XSR", "20N04-110E42"),
        "hu" to Pair("Hutubi (Xinjiang)", "44N10-86E54"),
        "j" to Pair("Jinhua", "29N07-119E19"),
        "k" to Pair("Kunming-Anning CRI (Yunnan)", "24N53-102E30"),
        "ka" to Pair("Kashi (Kashgar) (Xinjiang)", "39N21-75E46"),
        "kl" to Pair("Kunming-Lantao PBS (Yunnan)", "25N10-102E50"),
        "L" to Pair("Lingshi \"725\" (Shanxi)", "36N52-111E40"),
        "ly" to Pair("Lianyungang, Jiangsu", "34N42'04\"-119E18'45\""),
        "n" to Pair("Nanning (Guangxi) \"954\"", "22N47-108E11"),
        "nj" to Pair("Nanjing (Jiangsu)", "32N02-118E44"),
        "nm" to Pair("Nei Menggu network, see http://www.asiawaves.net/ for details", null),
        "p" to Pair("Pucheng (Shaanxi)", "35N00-109E31"),
        "pt" to Pair("Putian (Fujian)", "25N28-119E10"),
        "q" to Pair("Ge'ermu/Golmud \"916\" (Qinghai)", "36N26-95E00"),
        "qq" to Pair("Qiqihar  (500kW)", "47N02-124E03"),
        "qz" to Pair("Quanzhou \"641\" (Fujian)", "24N53-118E48"),
        "s" to Pair("Shijiazhuang \"723\" (Hebei; Nanpozhuang CRI 500 kW; Huikou CNR 100 kW)", "38N13-114E06"),
        "sg" to Pair("Shanghai-Taopuzhen", "31N15-121E29"),
        "sh" to Pair("Shanghai XSG Chongming Island", "31N37'30\"-121E43'49\""),
        "sn" to Pair("Sanya (Hainan)", "18N14-109E19"),
        "sq" to Pair("Shangqiu (Henan)", "34N56'54\"-109E32'34\""),
        "st" to Pair("Shantou (Guangdong)", "23N22-116E42"),
        "sw" to Pair("Nanping-Shaowu (Fujian)", "27N05-117E17"),
        "sy" to Pair("Shuangyashan \"128\" (Heilongjiang)", "46N43'19\"-131E12'40\""),
        "t" to Pair("Tibet (Lhasa-Baiding \"602\")", "29N39-91E15"),
        "tj" to Pair("Tianjin", "39N03'00\"-117E25'30\""),
        "u" to Pair("Urumqi (Xinjiang, CRI)", "44N08'47\"-86E53'43\""),
        "uc" to Pair("Urumqi-Changji (Xinjiang, PBS XJ)", "43N58'26\"-87E14'56\""),
        "x" to Pair("Xian-Xianyang \"594\" (Shaanxi)", "34N12-108E54"),
        "xc" to Pair("Xichang (Sichuan)", "27N49-102E14"),
        "xd" to Pair("Xiamen-Xiangdian (Fujian) XSM  and Dong'an 24N35'54\"-118E07'06\"", "24N30'17\"-118E08'37\""),
        "xg" to Pair("Xining (Qinghai)", "36N39-101E35"),
        "xm" to Pair("Xiamen (Fujian)", "24N29'32\"-118E04'23\""),
        "xt" to Pair("Xiangtan (Hunan)", "27N30-112E30"),
        "xw" to Pair("Xuanwei (Yunnan)", "26N09-104E02"),
        "xx" to Pair("Unknown site", null),
        "xy" to Pair("Xingyang (Henan)", "34N48-113E23"),
        "xz" to Pair("Xinzhaicun (Fujian)", "25N45-117E11"),
        "ya" to Pair("Yanbian-Yanji (Jilin)", "42N47'30\"-129E29'18\""),
        "yt" to Pair("Yantai (Shandong)", "37N42'23\"-121E08'09\""),
        "zh" to Pair("Zhuhai \"909\" (Guangdong)", "22N23-113E33"),
        "zj" to Pair("Zhanjiang (Guangdong)", "21N11-110E24"),
        "zn" to Pair("Zhongshan (Guangdong)", "22N32-113E21"),
        "zs" to Pair("Mount Putuo, Xiaohulu Island, Zhoushan", "30N00-122E23")
    ),
    "CKH" to mapOf(
        "rt" to Pair("Rarotonga", "21S12-159W49")
    ),
    "CLM" to mapOf(
        "b" to Pair("Barranquilla", "10N55-074W46"),
        "bu" to Pair("Buenaventura", "03N53-77W02"),
        "pl" to Pair("Puerto Lleras", "03N16-73W22"),
        "r" to Pair("Rioblanco, Tolima", "03N30-075W50"),
        "sa" to Pair("San Andrés Island (SAP)", "12N33-81W43")
    ),
    "CLN" to mapOf(
        "e" to Pair("Ekala (SLBC,RJ)", "07N06-79E54"),
        "i" to Pair("Iranawila (IBB)", "07N31-79E48"),
        "p" to Pair("Puttalam", "07N59-79E48"),
        "t" to Pair("Trincomalee (DW)", "08N44-81E10")
    ),
    "CME" to mapOf(
        "*" to Pair("Buea", "04N09-09E14")
    ),
    "CNR" to mapOf(
        "ar" to Pair("Arrecife (Lanzarote)", "29N08-13W31"),
        "fc" to Pair("Fuencaliente (Las Palmas)", "28N30'32\"-17W50'22\""),
        "gc" to Pair("Gran Canaria airport", "27N57-15W23"),
        "hr" to Pair("Haría (Tenerife)", "29N08'27\"-13W31'02\""),
        "hy" to Pair("Los Hoyos (Gran Canaria)", "28N02'55\"-15W26'59\""),
        "lm" to Pair("Las Mesas (Las Palmas)", "28N28'58\"-16W16'10\""),
        "pr" to Pair("Puerto del Rosario", "28N32'37\"-13W52'41\""),
        "xx" to Pair("Secret pirate site", null)
    ),
    "COD" to mapOf(
        "bk" to Pair("Bukavu", "02S30-28E50"),
        "bu" to Pair("Bunia", "01N32-30E11")
    ),
    "COG" to mapOf(
        "b" to Pair("Brazzaville-M'Pila", "04S15-15E18"),
        "bv" to Pair("Brazzaville Volmet", "04S13'59\"-15E15'42\""),
        "pn" to Pair("Pointe Noire", "04S47-11E52")
    ),
    "CTI" to mapOf(
        "a" to Pair("Abidjan", "05N22-03W58")
    ),
    "CTR" to mapOf(
        "*" to Pair("Cariari de Pococí (REE)  except:", "10N25-83W43"),
        "g" to Pair("Guápiles (Canton de Pococí, Prov.de Limón) ELCOR", "10N13-83W47")
    ),
    "CUB" to mapOf(
        "*" to Pair("La Habana sites Quivicán/Bejucal/Bauta", "23N00-82W30"),
        "b" to Pair("Bauta (Centro Transmisor No.1)", "22N57-82W33"),
        "be" to Pair("Bejucal (Centro Transmisor No.2)", "22N52-82W20"),
        "hr" to Pair("Havana Radio", "23N10-82W19"),
        "q" to Pair("Quivicán/Titan (Centro Transmisor No.3)", "22N50-82W18")
    ),
    "CVA" to mapOf(
        "*" to Pair("Santa Maria di Galeria  except:", "42N03-12E19"),
        "v" to Pair("Citta del Vaticano", "41N54-12E27")
    ),
    "CYP" to mapOf(
        "a" to Pair("Akrotiri (UK territory)", "34N37-32E56"),
        "cr" to Pair("Cyprus Radio", "35N03-33E17"),
        "g" to Pair("Cape Greco", "34N57-34E05"),
        "m" to Pair("Lady's Mile (UK territory)", "34N37-33E00"),
        "n" to Pair("Nicosia", "35N10-33E21"),
        "y" to Pair("Yeni Iskele", "35N17-33E55")
    ),
    "CZE" to mapOf(
        "b" to Pair("Brno-Dobrochov", "49N23-17E08"),
        "cb" to Pair("Ceske Budejovice-Husova kolonie", "48N59'34\"-14E29'37\""),
        "dl" to Pair("Dlouhá Louka", "50N38'53\"-13E39'22\""),
        "kv" to Pair("Karlovy Vary-Stará Role", "50N14'22\"-12E49'26\""),
        "mb" to Pair("Moravské Budejovice-Domamil", "49N04'35\"-15E42'24\""),
        "os" to Pair("Ostrava-Svinov", "49N48'41\"-18E11'36\""),
        "p" to Pair("Praha", "50N02-14E29"),
        "pl" to Pair("Praha-Liblice", "50N04'43\"-14E53'13\""),
        "pr" to Pair("Pruhonice / Pruhonice", "49N59'28\"-14E32'17\""),
        "pv" to Pair("Panská Ves", "50N31'41\"-14E34'01\""),
        "tr" to Pair("Trebon / Trebon", "49N00-14E46"),
        "va" to Pair("Vackov", "50N14-12E23")
    ),
    "D" to mapOf(
        "al" to Pair("Albersloh", "51N53'11\"-07E43'19\""),
        "b" to Pair("Biblis", "49N41'18\"-08E29'32\""),
        "be" to Pair("Berlin-Britz", "52N27-13E26"),
        "bl" to Pair("Berlin", "52N31-13E23"),
        "br" to Pair("Braunschweig", "52N17-10E43"),
        "bu" to Pair("Burg", "52N17'13\"-11E53'49\""),
        "bv" to Pair("Bonn-Venusberg", "50N42'29\"-07E05'48\""),
        "cx" to Pair("Cuxhaven-Sahlenburg", "53N51'50\"-08E37'32\""),
        "d" to Pair("Dillberg", "49N19-11E23"),
        "dd" to Pair("Dresden-Wilsdruff", "51N03'31\"-13E30'27\""),
        "dt" to Pair("Datteln", "51N39-07E21"),
        "e" to Pair("Erlangen-Tennenlohe", "49N35-11E00"),
        "fl" to Pair("Flensburg", "54N47'30\"-09E30'12\""),
        "g" to Pair("Goehren", "53N32'08\"-11E36'40\""),
        "ge" to Pair("Gera", "50N53-12E05"),
        "gl" to Pair("Glücksburg  and Neuharlingersiel, Schortens, Hürup, Rostock, Marlow", "54N50-09E30"),
        "ha" to Pair("Hannover", "52N23-09E42"),
        "hc" to Pair("Hamburg-Curslack", "53N27-10E13"),
        "he" to Pair("Hannover/Hemmingen", "52N19'40\"-09E44'12\""),
        "hh" to Pair("Hamburg-Moorfleet", "53N31'09\"-10E06'10\""),
        "ht" to Pair("Hartenstein (Sachsen)", "50N40-12E40"),
        "jr" to Pair("Juliusruh", "54N37'45\"-13E22'26\""),
        "k" to Pair("Kall-Krekel", "50N28'41\"-06E31'23\""),
        "L" to Pair("Lampertheim", "49N36'17\"-08E32'20\""),
        "la" to Pair("Langenberg", "51N21'22\"-07E08'03\""),
        "li" to Pair("Lingen", "52N32'06\"-07E21'11\""),
        "mf" to Pair("Mainflingen", "50N00'56\"-009E00'43\""),
        "n" to Pair("Nauen", "52N38'55\"-12E54'33\""),
        "nh" to Pair("Neuharlingersiel DHJ59", "53N40'35\"-07E36'45\""),
        "nu" to Pair("Nuernberg", "49N27-11E05"),
        "or" to Pair("Oranienburg", "52N47-13E23"),
        "pi" to Pair("Pinneberg", "53N40'23\"-09E48'30\""),
        "r" to Pair("Rohrbach", "48N36-11E33"),
        "rf" to Pair("Rhauderfehn", "53N05-07E37"),
        "s" to Pair("Stade", "53N36-09E28"),
        "w" to Pair("Wertachtal", "48N05'13\"-10E41'42\""),
        "wa" to Pair("Winsen (Aller)", "52N40-09E46"),
        "wb" to Pair("Wachenbrunn", "50N29'08\"-10E33'30\""),
        "we" to Pair("Weenermoor", "53N12-07E19"),
        "wh" to Pair("Waldheim", "51N04-12E59"),
        "xx" to Pair("Secret pirate site", null)
    ),
    "DJI" to mapOf(
        "d" to Pair("Djibouti", "11N30-43E00"),
        "i" to Pair("Centre de Transmissions Interarmées FUV", "11N32'09\"-43E09'20\"")
    ),
    "DNK" to mapOf(
        "a" to Pair("Aarhus-Mårslet", "56N09-10E13"),
        "bl" to Pair("Blaavand", "55N33-08E06"),
        "br" to Pair("Bramming", "55N28-08E42"),
        "bv" to Pair("Bovbjerg", "56N31-08E10"),
        "co" to Pair("Copenhagen OXT", "55N50-11E25"),
        "f" to Pair("Frederikshavn", "57N26-10E32"),
        "h" to Pair("Hillerod", "55N54-012E16"),
        "hv" to Pair("Copenhagen Hvidovre", "55N39-12E29"),
        "i" to Pair("Copenhagen Ishøj", "55N37-12E21"),
        "k" to Pair("Kalundborg", "55N40'35\"-11E04'10\""),
        "ra" to Pair("Randers", "56N28-10E02"),
        "ro" to Pair("Ronne", "55N02-15E06"),
        "sg" to Pair("Skagen", "57N44-10E34"),
        "sk" to Pair("Skamlebaek", "55N50-11E25")
    ),
    "DOM" to mapOf(
        "sd" to Pair("Santo Domingo", "18N28-69W53")
    ),
    "E" to mapOf(
        "af" to Pair("Alfabia (Mallorca)", "39N44'15\"-02E43'05\""),
        "ag" to Pair("Aguilas", "37N29'25\"-01W33'48\""),
        "ar" to Pair("Ares", "43N27'10\"-08W17'00\""),
        "as" to Pair("La Asomada", "37N37'48\"-00W57'48\""),
        "bo" to Pair("Boal", "43N27'23\"-06W49'14\""),
        "c" to Pair("Coruna", "43N22'01\"-08W27'07\""),
        "cg" to Pair("Cabo de Gata - Sabinar", "37N12'29\"-07W01'06\""),
        "cp" to Pair("Chipiona", "36N40-06W24"),
        "fi" to Pair("Finisterre", "42N54-09W16"),
        "gm" to Pair("Torreta de Guardamar, Guardamar del Segura", "38N04'18\"-00W39'53\""),
        "h" to Pair("Huelva", "37N12-07W01"),
        "hv" to Pair("\"Huelva\"", "43N20'41\"-01W51'21\""),
        "jq" to Pair("Jaizquibel", "43N20'41\"-01W51'21\""),
        "ma" to Pair("Madrid", "40N28-03W40"),
        "mu" to Pair("Muxía", "43N04'38\"-09W13'30\""),
        "mx" to Pair("Marratxí", "39N38'05\"-02E40'12\""),
        "n" to Pair("Noblejas", "39N57-03W26"),
        "pm" to Pair("Palma de Mallorca", "39N22-02E47"),
        "pz" to Pair("Pastoriza", "42N20'35\"-08W43'09\""),
        "rq" to Pair("Roquetas", "36N15'58\"-06W00'48\""),
        "rs" to Pair("Rostrío/Cabo de Peñas", "43N28'42\"-05W51'01\""),
        "sb" to Pair("Sabiner", "36N41-02W42"),
        "ta" to Pair("Tarifa", "36N03-05W33"),
        "tj" to Pair("Trijueque", "40N46'43\"-02W59'07\""),
        "to" to Pair("Torrejón de Ardoz (Pegaso, Pavon, Brujo)", "40N30-03W27"),
        "*" to Pair("Jota,Polar-Zaragoza; Perseo-Alcala de los Gazules; Matador-Villatobas; Embargo-Soller", null),
        "vj" to Pair("Vejer", "43N28'42\"-03W51'01\""),
        "xx" to Pair("Secret pirate site", null)
    ),
    "EGY" to mapOf(
        "a" to Pair("Abis", "31N10-30E05"),
        "al" to Pair("Alexandria / Al-Iskandaria", "31N12-29E54"),
        "ca" to Pair("Cairo", "30N04-31E13"),
        "ea" to Pair("El Arish", "31N06'43\"-33E41'59\""),
        "z" to Pair("Abu Zaabal", "30N16-31E22")
    ),
    "EQA" to mapOf(
        "a" to Pair("Ambato", "01S13-78W37"),
        "c" to Pair("Pico Pichincha", "00S11-78W32"),
        "g" to Pair("Guayaquil", "02S16-79W54"),
        "i" to Pair("Ibarra", "00N21-78W08"),
        "o" to Pair("Otavalo", "00N18-78W11"),
        "p" to Pair("Pifo", "00S14-78W20"),
        "s" to Pair("Saraguro", "03S42-79W18"),
        "t" to Pair("Tena", "01S00-77W48"),
        "u" to Pair("Sucúa", "02S28-78W10")
    ),
    "ERI" to mapOf(
        "*" to Pair("Asmara-Saladaro", "15N13-38E52")
    ),
    "EST" to mapOf(
        "ta" to Pair("Tallinn Radio", "59N27-24E45"),
        "tt" to Pair("Tartu", "58N25-27E06"),
        "tv" to Pair("Tallinn Volmet", "59N25-24E50")
    ),
    "ETH" to mapOf(
        "a" to Pair("Addis Abeba", "09N00-38E45"),
        "ad" to Pair("Adama", "08N32-39E16"),
        "b" to Pair("Bahir Dar", "11N36-37E23"),
        "d" to Pair("Geja Dera (HS)", "08N46-38E40"),
        "j" to Pair("Geja Jewe (FS)", "08N43-38E38"),
        "jj" to Pair("Jijiga", "09N21-42E48"),
        "m" to Pair("Mekele", "13N29-39E29"),
        "n" to Pair("Nekemte", "09N05-36E33"),
        "r" to Pair("Robe", "07N07-40E00")
    ),
    "F" to mapOf(
        "a" to Pair("Allouis", "47N10-02E12"),
        "au" to Pair("Auros", "44N30-00W09"),
        "av" to Pair("Avord", "47N03-02E28"),
        "br" to Pair("FUE Brest", "48N25'33\"-04W14'27\""),
        "cm" to Pair("Col de la Madone", "43N47-07E25"),
        "co" to Pair("Corsen", "48N25-04W47"),
        "g" to Pair("La Garde (Toulon)", "43N06'19\"-05E59'21\""),
        "gn" to Pair("Gris-Nez", "50N52-01E35"),
        "hy" to Pair("Hyères Island", "42N59'04\"-06E12'24\""),
        "i" to Pair("Issoudun", "46N56-01E54"),
        "jb" to Pair("Jobourg", "49N41'05\"-01W54'28\""),
        "ma" to Pair("Mont Angel/Fontbonne", "43N46-07E25'30\""),
        "oe" to Pair("Ouessant", "48N28-05W03"),
        "p" to Pair("Paris", "48N52-02E18"),
        "r" to Pair("Rennes", "48N06-01W41"),
        "ro" to Pair("Roumoules", "43N47-06E09"),
        "sa" to Pair("FUG Saissac (11)", "43N23'25\"-02E05'59\""),
        "sb" to Pair("Strasbourg", "48N14'59\"-07E26'37\""),
        "sg" to Pair("Saint Guénolé", "47N49-04W23"),
        "to" to Pair("FUO Toulon", "43N08'08\"-06E03'35\""),
        "v" to Pair("Favières FAV", "48N32-01E14"),
        "ve" to Pair("Vernon", "49N05-01E30"),
        "wu" to Pair("Rosnay (HWU)", "46N43-01E14"),
        "xx" to Pair("Unknown site", null)
    ),
    "FIN" to mapOf(
        "as" to Pair("Asikkala", "60N45-26E05"),
        "ha" to Pair("Hailuoto (Oulu)", "65N00-24E44"),
        "he" to Pair("Helsinki", "60N09-24E44"),
        "hk" to Pair("Hämeenkyrö", null),
        "lp" to Pair("Lappeenranta", "61N04-28E11"),
        "mh" to Pair("Mariehamn (Aland Islands)", "60N06-19E56"),
        "o" to Pair("Ovaniemi", "60N10'49\"-24E49'35\""),
        "p" to Pair("Pori", "61N28-21E35"),
        "pk" to Pair("Pori (Blacksmith Knoll)", "61N29-21E48"),
        "r" to Pair("Raseborg/Raasepori", "59N58-23E26"),
        "t" to Pair("Topeno, Loppi, near Riihimäki", "60N46-24E17"),
        "v" to Pair("Virrat", "62N23-23E37"),
        "va" to Pair("Vaasa", "63N05-21E36")
    ),
    "FJI" to mapOf(
        "n" to Pair("Nadi-Enamanu", "17S47'14\"-177E25'20\"")
    ),
    "FRO" to mapOf(
        "t" to Pair("Tórshavn", "62N01-06W47")
    ),
    "FSM" to mapOf(
        "*" to Pair("Pohnpei", "06N58-158E12")
    ),
    "G" to mapOf(
        "ab" to Pair("Aberdeen (Gregness)", "57N07'39\"-02W03'13\""),
        "aq" to Pair("Aberdeen (Blaikie's Quay)", "57N08'40\"-02W05'16\""),
        "an" to Pair("Anthorn", "54N54'40\"-03W16'50\""),
        "ba" to Pair("Bangor (No.Ireland)", "54N39'51\"-05W40'08\""),
        "bd" to Pair("Bridlington (East Yorkshire)", "54N05'38\"-00W10'33\""),
        "cf" to Pair("Collafirth Hill (Shetland)", "60N32'00\"-01W23'30\""),
        "cm" to Pair("Crimond (Aberdeenshire)", "57N37-01W53"),
        "cp" to Pair("London-Crystal Palace", "51N25-00W05"),
        "cr" to Pair("London-Croydon", "51N25-00W05"),
        "ct" to Pair("Croughton (Northants)", "51N59'50\"-01W12'33\""),
        "cu" to Pair("Cullercoats, Newcastle", "55N04'29\"-01W27'47\""),
        "d" to Pair("Droitwich", "52N18-02W06"),
        "dv" to Pair("Dover", "51N07'59\"-01E20'36\""),
        "ev" to Pair("St.Eval (Cornwall)", "50N29-05W00"),
        "fh" to Pair("Fareham (Hampshire)", "50N51'30\"-01W14'59\""),
        "fl" to Pair("Falmouth Coastguard", "50N08'43\"-05W02'44\""),
        "fm" to Pair("Falmouth (Lizard)", "49N57'37\"-05W12'06\""),
        "hh" to Pair("Holyhead (Isle of Anglesey, Wales)", "53N18'59\"-04W37'57\""),
        "hu" to Pair("Humber (Flamborough)", "54N07'00\"-00W04'41\""),
        "ic" to Pair("one of: Inskip (Lancashire)  / St.Eval (Cornwall) 50N29-05W00 / Crimond (Aberdeenshire) 57N37-01W53", "53N50-02W50"),
        "in" to Pair("Inskip (Lancashire)", "53N50-02W50"),
        "lo" to Pair("London", "51N30-00W11"),
        "lw" to Pair("Lerwick (Shetland)", "60N08'55\"-01W08'25\""),
        "mh" to Pair("Milford Haven, Wales", "51N42'28\"-05W03'09\""),
        "ni" to Pair("Niton Navtex, Isle of Wight", "50N35'11\"-01W15'17\""),
        "nw" to Pair("Northwood", "51N30-00W10"),
        "o" to Pair("Orfordness", "52N06-01E35"),
        "p" to Pair("Portland", "50N36-02W27"),
        "pp" to Pair("Portpatrick Navtex (Dumfries and Galloway)", "54N50'39\"-05W07'28\""),
        "r" to Pair("Rampisham", "50N48'30\"-02W38'35\""),
        "s" to Pair("Skelton", "54N44-02W54"),
        "sc" to Pair("Shetland (Lerwick)", "60N24'06\"-01W13'27\""),
        "sm" to Pair("St Mary's (Isles of Scilly)", "49N55'44\"-06W18'14\""),
        "sp" to Pair("Saint Peter Port (Guernsey)", "49N27-02W32"),
        "st" to Pair("Stornoway (Butt of Lewis)", "58N27'41\"-06W13'52\""),
        "sw" to Pair("Stornoway port", "58N12'12\"-06W22'32\""),
        "ti" to Pair("Tiree (Inner Hebrides)", "56N30'00\"-06W48'25\""),
        "w" to Pair("Woofferton", "52N19-02W43"),
        "wa" to Pair("Washford (Somerset)", "51N09'38\"-03W20'55\""),
        "xy" to Pair("Varying site, see rsgb.org/main/gb2rs/", null)
    ),
    "GAB" to mapOf(
        "*" to Pair("Moyabi", "01S40-13E31")
    ),
    "GEO" to mapOf(
        "s" to Pair("Sukhumi", "42N59'18\"-41E03'58\"")
    ),
    "GNE" to mapOf(
        "b" to Pair("Bata", "01N46-09E46"),
        "m" to Pair("Malabo", "03N45-08E47")
    ),
    "GRC" to mapOf(
        "a" to Pair("Avlis", "38N23-23E36"),
        "i" to Pair("Iraklion", "35N20-25E07"),
        "k" to Pair("Kerkyra", "39N37-19E55"),
        "L" to Pair("Limnos (Myrina)", "39N52-25E04"),
        "o" to Pair("Olimpia", "37N36'27\"-21E29'15\""),
        "r" to Pair("Rhodos", "36N25-28E13"),
        "xx" to Pair("Secret pirate site", null)
    ),
    "GRL" to mapOf(
        "aa" to Pair("Aasiaat", "68N41-52W50"),
        "ik" to Pair("Ikerasassuaq (Prins Christian Sund)", "60N03-43W09"),
        "ko" to Pair("Kook Island", "64N04-52W00"),
        "ma" to Pair("Maniitsoq", "65N24-52W52"),
        "n" to Pair("Nuuk", "64N04-52W00"),
        "pa" to Pair("Paamiut", "62N00-49W43"),
        "qa" to Pair("Qaqortoq", "60N41-46W36"),
        "qe" to Pair("Qeqertarsuaq", "69N14-53W31"),
        "si" to Pair("Sisimiut", "66N56-53W40"),
        "sq" to Pair("Simiutaq", "60N41-46W36"),
        "t" to Pair("Tasiilaq/Ammassalik", "65N36-37W38"),
        "up" to Pair("Upernavik", "72N47-56W10"),
        "uu" to Pair("Uummannaq", "70N47-52W08")
    ),
    "GUF" to mapOf(
        "*" to Pair("Montsinery", "04N54-52W29")
    ),
    "GUI" to mapOf(
        "c" to Pair("Conakry-Sonfonia", "09N41'10\"-13W32'11\"")
    ),
    "GUM" to mapOf(
        "a" to Pair("Station KSDA, Agat,", "13N20'28\"-144E38'56\""),
        "an" to Pair("Andersen Air Force Base", "13N34-144E55"),
        "b" to Pair("Barrigada", "13N29-144E50"),
        "h" to Pair("Agana HFDL site", "13N28-144E48"),
        "m" to Pair("Station KTWR, Agana/Merizo", "13N16'38\"-144E40'16\""),
        "n" to Pair("Naval station NPN", "13N26-144E39")
    ),
    "GUY" to mapOf(
        "*" to Pair("Sparendaam", "06N49-58W05")
    ),
    "HKG" to mapOf(
        "a" to Pair("Cape d'Aguilar", "22N13-114E15"),
        "m" to Pair("Marine Rescue Radio VRC", "22N17'24\"-114E09'12\"")
    ),
    "HND" to mapOf(
        "t" to Pair("Tegucigalpa", "14N04-87W13")
    ),
    "HNG" to mapOf(
        "b" to Pair("Budapest", "47N30-19E03"),
        "g" to Pair("Györ", null),
        "lh" to Pair("Lakihegy", "47N22-19E00"),
        "m" to Pair("Marcali-Somogyszentpal", null),
        "p" to Pair("Pecs-Kozarmisleny", null),
        "sz" to Pair("Szolnok-Besenyszoegi ut", null)
    ),
    "HOL" to mapOf(
        "a" to Pair("Alphen aan den Rijn", "52N08-04E38"),
        "b" to Pair("Borculo", "52N07-06E31"),
        "cg" to Pair("Coast Guard Den Helder - Scheveningen", "52N06-04E15"),
        "e" to Pair("Elburg", "52N26-05E52"),
        "he" to Pair("Heerde", "52N23-06E02"),
        "k" to Pair("Klazienaveen", "52N44-06E59"),
        "m" to Pair("Margraten", "50N48-05E48"),
        "n" to Pair("Nijmegen", "51N51-05E50"),
        "o" to Pair("Ouddorp, Goeree-Overflakkee island", "51N48-03E54"),
        "ov" to Pair("Overslag (Westdorpe)", "51N12-03E52"),
        "w" to Pair("Winterswijk", "51N58-06E43"),
        "xx" to Pair("Secret pirate site", null),
        "zw" to Pair("Zwolle", "52N31-06E05")
    ),
    "HRV" to mapOf(
        "*" to Pair("Deanovec", "45N39-16E27")
    ),
    "HWA" to mapOf(
        "a" to Pair("WWVH", "21N59'21\"-159W45'52\""),
        "b" to Pair("WWVH", "21N59'11\"-159W45'45\""),
        "c" to Pair("WWVH", "21N59'18\"-159W45'51\""),
        "d" to Pair("WWVH", "21N59'15\"-159W45'50\""),
        "hi" to Pair("Hickam AFB", "21N19-157W55"),
        "ho" to Pair("Honolulu/Iroquois Point", "21N19'23\"-157W59'36\""),
        "L" to Pair("Lualualei W", "21N25'12\"-158W08'54\""),
        "m" to Pair("Moloka'i", "21N11-157W11"),
        "n" to Pair("Naalehu", "19N01-155W40"),
        "nm" to Pair("NMO Honolulu/Maili", "21N25'41\"-158W09'11\""),
        "p" to Pair("Pearl Harbour", "21N25'41\"-158W09'11\"")
    ),
    "I" to mapOf(
        "a" to Pair("Andrate", "45N31-07E53"),
        "ac" to Pair("Ancona IDR", "43N36'40\"-13E31'33\""),
        "an" to Pair("Ancona IPA", "43N36'11\"-13E28'14\""),
        "au" to Pair("Augusta IQA (Sicily)", "37N14'14\"-15E14'26\""),
        "b" to Pair("San Benedetto de Tronto IQP", "42N58'15\"-13E51'55\""),
        "ba" to Pair("Bari IPB", "41N05'21\"-16E59'44\""),
        "cg" to Pair("Cagliari IDC (Sardinia)", "39N13'40\"-09E14'04\""),
        "cm" to Pair("Cagliari IDR", "39N11'31\"-09E08'44\""),
        "cv" to Pair("Civitavecchia IPD", "42N02'00\"-11E50'00\""),
        "eu" to Pair("Radio Europa, NW Italy", null),
        "ge" to Pair("Genova ICB", "44N25'45\"-08E55'59\""),
        "kr" to Pair("Crotone IPC", "39N03-17E08"),
        "li" to Pair("Livorno-Montenero IPL", "43N29'25\"-10E21'39\""),
        "lm" to Pair("Lampedusa-Ponente IQN", "35N31'03\"-12E33'58\""),
        "ls" to Pair("La Spazia IDR", "44N05-09E49"),
        "me" to Pair("Messina IDF (Sicily)", "38N16'06\"-15E37'19\""),
        "mp" to Pair("Monteparano (IPC)", "40N26'31\"-17E25'08\""),
        "mz" to Pair("Mazara del Vallo IQQ", "37N40'12\"-12E36'47\""),
        "na" to Pair("Napoli-Posillipo IQH", "40N48'02\"-14E11'00\""),
        "p" to Pair("Padova", "45N09-11E42"),
        "pa" to Pair("Palermo-Punta Raisi IPP (Sicily)", "38N11'24\"-13E06'30\""),
        "pi" to Pair("San Pietro island IDR", "40N26'52\"-17E09'40\""),
        "pt" to Pair("Porto Torres IZN (Sardinia)", "40N47'52\"-08E19'31\""),
        "r" to Pair("Roma", "41N48-12E31"),
        "ra" to Pair("Roma IMB", "41N47-12E28"),
        "re" to Pair("Rome", "41N55-12E29"),
        "sa" to Pair("Sant'Anna d'Alfaedo IDR", "45N37'40\"-10E56'45\""),
        "si" to Pair("Sigonella (Sicilia)", "37N24-14E55"),
        "sp" to Pair("Santa Panagia/Siracusa IDR (Sicilia)", "37N06-15E17"),
        "sr" to Pair("Santa Rosa (IDR Maritele), Roma", "41N59-12E22"),
        "sy" to Pair("NSY", "37N07-14E26"),
        "t" to Pair("Trieste (Monte Radio) IQX", "45N40'36\"-13E46'09\""),
        "v" to Pair("Viareggio, Toscana", "43N54-10E17"),
        "xx" to Pair("Secret pirate site", null)
    ),
    "IND" to mapOf(
        "a" to Pair("Aligarh (4x250kW)", "28N00-78E06"),
        "ah" to Pair("Ahmedabad", "22N52-72E37"),
        "az" to Pair("Aizawl(10kW)", "23N43-92E43"),
        "b" to Pair("Bengaluru-Doddaballapur (Bangalore)", "13N14-77E30"),
        "bh" to Pair("Bhopal(50kW)", "23N10-77E30"),
        "c" to Pair("Chennai (Madras)", "13N08-80E07"),
        "d" to Pair("Delhi (Kingsway)", "28N43-77E12"),
        "dn" to Pair("Delhi-Nangli Poona", "28N46-77E08"),
        "g" to Pair("Gorakhpur", "26N52-83E28"),
        "gt" to Pair("Gangtok", "27N22-88E37"),
        "hy" to Pair("Hyderabad", "17N20-78E33"),
        "im" to Pair("Imphal", "24N37-93E54"),
        "it" to Pair("Itanagar", "27N04-93E36"),
        "j" to Pair("Jalandhar", "31N19-75E18"),
        "ja" to Pair("Jaipur", "26N54-75E45"),
        "je" to Pair("Jeypore", "18N55-82E34"),
        "jm" to Pair("Jammu", "32N45-75E00"),
        "k" to Pair("Kham Pur, Delhi 110036 (Khampur)", "28N49-77E08"),
        "kc" to Pair("Kolkata-Chandi", "22N22-88E17"),
        "kh" to Pair("Kohima", "25N39-94E06"),
        "ko" to Pair("Kolkata(Calcutta)-Chinsurah", "23N01-88E21"),
        "ku" to Pair("Kurseong", "26N55-88E19"),
        "kv" to Pair("Kolkata Volmet", "22N39-88E27"),
        "le" to Pair("Leh", "34N08-77E29"),
        "lu" to Pair("Lucknow", "26N53-81E03"),
        "m" to Pair("Mumbai (Bombay)", "19N11-72E49"),
        "mv" to Pair("Mumbai Volmet", "19N05-72E51"),
        "n" to Pair("Nagpur, Maharashtra", "20N54-78E59"),
        "nj" to Pair("Najibabad, Uttar Pradesh", "29N38-78E23"),
        "p" to Pair("Panaji (Goa)", "15N31-73E52"),
        "pb" to Pair("Port Blair-Brookshabad", "11N37-92E45"),
        "r" to Pair("Rajkot", "22N22-70E41"),
        "ra" to Pair("Ranchi", "23N24-85E22"),
        "sg" to Pair("Shillong", "25N26-91E49"),
        "si" to Pair("Siliguri", "26N46-88E26"),
        "sm" to Pair("Shimla", "31N06-77E09"),
        "sr" to Pair("Srinagar", "34N00-74E50"),
        "su" to Pair("Suratgarh (Rajasthan)", "29N18-73E55"),
        "t" to Pair("Tuticorin (Tamil Nadu)", "08N49-78E05"),
        "tv" to Pair("Thiruvananthapuram(Trivendrum)", "08N29-76E59"),
        "v" to Pair("Vijayanarayanam (Tamil Nadu)", "08N23-77E45"),
        "vs" to Pair("Vishakapatnam (Andhra Pradesh)", "17N43-83E18"),
        "w" to Pair("Guwahati (1x200kW, 1x50kW)", "26N11-91E50")
    ),
    "INS" to mapOf(
        "am" to Pair("Ambon, Ambon Island, Maluku", "03S41'49\"-128E10'29\""),
        "ap" to Pair("Amamapare, Papua", "04S53-136E48"),
        "at" to Pair("Atapupu, Timor", "09S01'30\"-124E51'40\""),
        "ba" to Pair("Banggai, Banggai Island, Sulawesi Tengah", "01S35'25\"-123E29'56\""),
        "bb" to Pair("Banabungi, Buton Island, Sulawesi Tenggara", "05S30'50\"-122E50'40\""),
        "bd" to Pair("Badas, Sumbawa Island, West Nusa Tenggara", "08S27'44\"-117E22'38\""),
        "be" to Pair("Bade, Papua", "07S09'52\"-139E35'49\""),
        "bg" to Pair("Bagan Siapi-Api, Riau, Sumatra", "02N09'09\"-100E48'10\""),
        "bi" to Pair("Biak, Papua", "01S00-135E30"),
        "bj" to Pair("Banjarmasin, Kalimantan Selatan", "03S20-114E35"),
        "bk" to Pair("Bengkalis, Bengkalis Island, Riau", "01N27'05\"-102E06'34\""),
        "bl" to Pair("Batu Licin, Kalimantan Selatan", "03S25'55\"-116E00'07\""),
        "bm" to Pair("Batu Ampar, Batam Island next to Singapore", "01N10'51\"-104E00'52\""),
        "bn" to Pair("Bawean, Bawean Island, Jawa Timur", "05S51'20\"-112E39'20\""),
        "bo" to Pair("Benoa, Denpasar, Bali", "08S45'22\"-115E13'00\""),
        "bp" to Pair("Balikpapan, Kalimantan Timur", "01S15'44\"-116E49'13\""),
        "bt" to Pair("Benete, Sumbawa Island, West Nusa Tenggara", "08S54'03\"-116E44'50\""),
        "bu" to Pair("Bukittinggi, Sumatera Barat", "00S18-100E22"),
        "bw" to Pair("Belawan, Medan, Sumatera Utara", "03N43'17\"-98E40'08\""),
        "by" to Pair("Biak, Biak Island, Papua", "01S11'01\"-136E04'41\""),
        "b2" to Pair("Bau-Bau, Buton Island, Sulawesi Tenggara", "05S28'49\"-122E34'52\""),
        "b3" to Pair("Bengkulu, Sumatra", "03S53'59\"-102E18'32\""),
        "b4" to Pair("Bima, Sumbawa Island, West Nusa Tenggara", "08S26'26\"-118E43'32\""),
        "b5" to Pair("Bintuni, Papua Barat", "02S07'11\"-133E30'04\""),
        "b6" to Pair("Bitung, Sulawesi Utara", "01N27'53\"-125E11'03\""),
        "b7" to Pair("Bontang, Kalimantan Timur", "00S08-117E30"),
        "cb" to Pair("Celukan Bawang, Bali", "08S11'10\"-114E49'52\""),
        "cc" to Pair("Cilacap, Java", "07S44-109E00"),
        "ci" to Pair("Cigading, Merak, Banten, Java", "05S56-106E00"),
        "cr" to Pair("Cirebon, Jawa Barat", "06S43-108E34"),
        "dg" to Pair("Donggala, Sulawesi Tengah", "00S40'30\"-119E44'41\""),
        "dm" to Pair("Dumai, Riau, Sumatra", "01N41'10\"-101E27'20\""),
        "do" to Pair("Dobo, Wamar Island, Maluku", "05S45-134E14"),
        "ds" to Pair("Dabo Singkep, Singkep Island, Riau, Sumatra", "00S30-104E34"),
        "en" to Pair("Ende, Flores Island, Nusa Tenggara Timur", "08S50'20\"-121E38'38\""),
        "f" to Pair("Fakfak, Papua Barat", "02S56-132E18"),
        "fj" to Pair("Fatujuring, Wokam Island, Maluku", "06S01-134E09"),
        "g" to Pair("Gorontalo, Sulawesi", "00N34-123E04"),
        "gi" to Pair("Gilimanuk, Bali", "08S10'41\"-114E26'05\""),
        "go" to Pair("Gorontalo port, Sulawesi", "00N30'29\"-123E03'49\""),
        "gr" to Pair("Gresik, Surabaya, Jawa Timur", "07S09'51\"-112E39'37\""),
        "gs" to Pair("Gunung Sitoli, Nias Island, Sumatera Utara", "01N18'24\"-97E36'35\""),
        "j" to Pair("Jakarta (Cimanggis)", "06S23'30\"-106E51'40\""),
        "ja" to Pair("Jambi PKC3", "01S36'50\"-103E36'51\""),
        "jb" to Pair("Jakarta BMG", "06S17-106E52"),
        "jm" to Pair("Jambi, Sumatera", "01S38-103E34"),
        "jp" to Pair("Jepara, Jawa Tengah", "06S35'11\"-110E39'41\""),
        "js" to Pair("Jakarta, Sunda Kelapa port", "06S07'24\"-106E48'30\""),
        "jw" to Pair("Juwana, Jawa Tengah", "06S42'15\"-111E09'13\""),
        "jx" to Pair("Jakarta PKX", "06S07'08\"-106E51'15\""),
        "jy" to Pair("Jayapura, Papua", "02S31'10\"-140E43'22\""),
        "k" to Pair("Kebayoran Baru, South Jakarta, Java", "06S15'01\"-106E47'29\""),
        "ka" to Pair("Kaimana, Papua", "03S40-133E46"),
        "kb" to Pair("Kalabahi, Alor Island, East Nusa Tenggara", "08S13'18\"-124E30'40\""),
        "kd" to Pair("Kendari, Sulawesi Tenggara", "03S59-122E36"),
        "kg" to Pair("Kalianget, Sumenep, Madura Island, Jawa Timur", "07S04-113E58"),
        "ki" to Pair("Kumai, Kalimantan Tengah", "02S45'20\"-111E43'00\""),
        "kj" to Pair("Kijang, Bintan Island", "00N51'04\"-104E36'31\""),
        "kl" to Pair("Kolonodale, Sulawesi Tenggara", "02S01'16\"-121E20'28\""),
        "km" to Pair("Karimunjawa Island, off Java", "05S53-110E26"),
        "kn" to Pair("Kupang, Timor", "10S12-123E37"),
        "ko" to Pair("Kolaka, Sulawesi Tenggara", "04S02'55\"-121E34'42\""),
        "kp" to Pair("Ketapang, Kalimantan Barat", "01S49-109E58"),
        "ks" to Pair("Kota Langsa, Aceh, Sumatra", "04N29-97E57"),
        "kt" to Pair("Kuala Tungkal, Jambi, Sumatra", "00S49'15\"-103E28'06\""),
        "ku" to Pair("Kota Baru, Laut Island, Kalimantan Selatan", "03S14-116E14"),
        "kw" to Pair("Kwandang, Gorontalo, Sulawesi", "00N51'28\"-122E47'32\""),
        "le" to Pair("Lembar, Lombok", "08S43'41\"-116E04'23\""),
        "lh" to Pair("Lhokseumawe, Aceh, Sumatra", "05N12'41\"-97E02'21\""),
        "lk" to Pair("Larantuka, Flores 8", "08S20'28\"-122E59'25\""),
        "lo" to Pair("Lombok", "08S30'06\"-116E40'42\""),
        "lu" to Pair("Luwuky, Sulawesi Tengah", "00S53'59\"-122E47'39\""),
        "ma" to Pair("Manokwari, Papua Barat", "00S51'56\"-134E04'37\""),
        "mb" to Pair("Masalembo Island, Java Sea", "05S35-114E26"),
        "md" to Pair("Manado, Sulawesi Utara", "01N12-124E54"),
        "me" to Pair("Meneng, Banyuwangi, Java", "08S07'30\"-114E23'50\""),
        "mk" to Pair("Manokwari, Irian Jaya Barat", "00S48-134E00"),
        "mm" to Pair("Maumere, Flores, Nusa Tenggara Timur", "08S37-122E13"),
        "mn" to Pair("Manado, Sulawesi Utara", "01N32'25\"-124E50'04\""),
        "mr" to Pair("Merauke, Papua", "08S28'47\"-140E23'38\""),
        "ms" to Pair("Makassar, Sulawesi Selatan", "05S06'20\"-119E26'31\""),
        "mu" to Pair("Muntok, Bangka Island", "02S03'22\"-105E09'04\""),
        "n" to Pair("Nabire, Papua", "03S14-135E35"),
        "na" to Pair("Natuna, Tiga Island, Riau Islands", "03N40'10\"-108E07'45\""),
        "nu" to Pair("Nunukan, Nunukan Island, Kalimantan Utara", "04N07'18\"-117E41'20\""),
        "p" to Pair("Palangkaraya, Kalimantan Tengah", "00S11-113E54"),
        "pa" to Pair("Palu, Sulawesi Tengah", "00S36-129E36"),
        "pb" to Pair("Padang Bai, Bali", "08S31'37\"-115E30'28\""),
        "pd" to Pair("Padang, Sumatera Barat", "00S06-100E21"),
        "pe" to Pair("Pekalongan, Java", "06S51'35\"-109E41'30\""),
        "pf" to Pair("Pare-Pare, Sulawesi Selatan", "04S01-119E37"),
        "pg" to Pair("Pangkal Baru, Bangkal Island", "02S10-106E08"),
        "ph" to Pair("Panipahan, Riau, Sumatra", "02N25-100E20"),
        "pi" to Pair("Parigi, Sulawesi Tengah", "00S49'39\"-120E10'39\""),
        "pj" to Pair("Panjang, Lampung, Sumatra", "05S28'23\"-105E19'03\""),
        "pk" to Pair("Pontianak", "00S01'36\"-109E17'18\""),
        "pl" to Pair("Plaju, Palembang, Sumatera Selatan", "03S00-104E50"),
        "pm" to Pair("Palembang, Sumatera Selatan", "02S58-104E47"),
        "po" to Pair("Pomalaa, Sulawesi Tenggara", "04S10'59\"-121E38'56\""),
        "pp" to Pair("Palopo, Sulawesi Selatan", "02S59'20\"-120E12'10\""),
        "pq" to Pair("Probolinggo, Jawa Timur", "07S44-113E13"),
        "pr" to Pair("Panarukan, Jawa Timur", "07S42-113E56"),
        "ps" to Pair("Poso, Sulawesi Tengah", "01S23-120E45"),
        "pt" to Pair("Pantoloan, Sulawesi Tengah", "00S43-119E52"),
        "pu" to Pair("Pekanbaru, Riau, Sumatra", "00N19-103E10"),
        "pv" to Pair("Pulau Sambu, Riau Islands", "01N09'30\"-103E54'00\""),
        "ra" to Pair("Raha, Muna Island, Sulawesi Tenggara", "04S50-122E43"),
        "re" to Pair("Rengat, Riau, Sumatra", "00S28-102E41"),
        "ro" to Pair("Reo, Flores", "08S17'10\"-120E27'08\""),
        "sa" to Pair("Sabang, We Island, Aceh", "05N54-95E21"),
        "sb" to Pair("Seba, Sawu Island", "10S30-121E50"),
        "se" to Pair("Serui, Japen Island, Papua", "01S53-136E14"),
        "sg" to Pair("Semarang, Java", "06S58'35\"-110E20'50\""),
        "sh" to Pair("Susoh, Aceh, Sumatra", "03N43'09\"-96E48'34\""),
        "si" to Pair("Sarmi, Papua", "01S51-138E45"),
        "sj" to Pair("Selat Panjang, Tebingtinggi Island, Riau", "01N01'15\"-102E43'10\""),
        "sk" to Pair("Singkil, Aceh, Sumatra", "02N18-97E45"),
        "sm" to Pair("Simalungun, Sumatera", null),
        "sn" to Pair("Sanana, Sulabes Island, Maluku", "02S03-125E58"),
        "so" to Pair("Sorong, Papua Barat", "00S53-131E16"),
        "sp" to Pair("Sipange, Tapanuli, Sumatera Utara", "01N12'20\"-99E22'45\""),
        "sq" to Pair("Sibolga, Sumatera Utara", "01N44-98E47"),
        "sr" to Pair("Samarinda, Kalimantan Timur", "00S30'30\"-117E09'15\""),
        "st" to Pair("Sampit, Kalimantan Tengah", "02S33'26\"-112E57'24\""),
        "su" to Pair("Siau Island", "02N44-125E24"),
        "sy" to Pair("Selayar, Sulawesi Selatan", "06S07'10\"-120E27'30\""),
        "s2" to Pair("Sinabang, Simeulue Island, Aceh", "02N28-96E23"),
        "s3" to Pair("Sipura Island, Sumatera Barat", "02S12-99E40"),
        "s4" to Pair("Surabaya, Jawa Timur", "07S12-112E44"),
        "s8" to Pair("Sorong PKY8, Papua Barat", "00S39-130E43"),
        "s9" to Pair("Sorong PKY9, Papua Barat", "01S08-131E16"),
        "t" to Pair("Ternate, Ternate Island, Maluku Utara", "00N47-127E22"),
        "ta" to Pair("Tahuna, Sulawesi Utara", "03N36'20\"-125E30'15\""),
        "tb" to Pair("Tanjung Balai Karimun, Karimunbesar Island, Riau Islands", "00N59'17\"-103E26'14\""),
        "td" to Pair("Teluk Dalam, Dima Island, Sumatera Utara", "00N34-97E49"),
        "te" to Pair("Tegal, Java", "06S51-109E08"),
        "tg" to Pair("Tanjung Selor, Kalimantan Utara", "02N48-117E22"),
        "tk" to Pair("Tarakan, Tarakan Island, Kalimantan Utara", "03N17'20\"-117E35'25\""),
        "tl" to Pair("Tembilahan, Riau, Sumatra", "00S19'01\"-103E09'41\""),
        "tm" to Pair("Tarempa, Siantan Island, Riau Islands", "03N13-106E13"),
        "to" to Pair("Tobelo, Halmahera Island, Maluku Utara", "01N43'30\"-128E00'31\""),
        "ts" to Pair("Tanjung Santan, Kalimantan Timur", "00S06'08\"-117E27'51\""),
        "tt" to Pair("Toli-Toli, Sulawesi Tengah,", "01N03'18\"-120E48'20\""),
        "tu" to Pair("Tanjung Uban, Bintan Island, Riau Islands", "01N03'57\"-104E13'27\""),
        "tw" to Pair("Tual, Dullah Island, Maluku", "05S38-132E45"),
        "ty" to Pair("Taluk Bayur, Sumatera Barat", "01S02'29\"-100E22'50\""),
        "ul" to Pair("Ulee-Lheue, Banda Aceh, Aceh, Sumatra", "05N34-95E17"),
        "w" to Pair("Wamena, Papua", "04S06-138E56"),
        "wa" to Pair("Waingapu, Sumba Island, East Nusa Tenggara", "09S39'42\"-120E15'22\"")
    ),
    "IRL" to mapOf(
        "mh" to Pair("Malin Head, Co. Donegal", "55N22'19\"-07W20'21\""),
        "s" to Pair("Shannon", "52N44'40\"-08W55'37\""),
        "sk" to Pair("Sheskin, Co. Donegal", "55N21'08\"-07W16'26\""),
        "tr" to Pair("Tralee, Co. Kerry", "52N16-09W42"),
        "v" to Pair("Valentia, Co. Kerry", "51N52'04\"-10W20'03\""),
        "xx" to Pair("Secret pirate site", null),
        "xy" to Pair("Varying site, see irts.ie", null)
    ),
    "IRN" to mapOf(
        "a" to Pair("Ahwaz", "31N20-48E40"),
        "b" to Pair("Bandar-e Torkeman", "36N54-54E04"),
        "ba" to Pair("Bandar Abbas", "27N06'06\"-56E03'48\""),
        "bb" to Pair("Bonab", "37N18-46E03"),
        "c" to Pair("Chah Bahar", "25N29-60E32"),
        "g" to Pair("Gorgan", "36N51-54E26"),
        "j" to Pair("Jolfa", "38N56-45E36"),
        "k" to Pair("Kamalabad", "35N46-51E27"),
        "ke" to Pair("Kish Island", "26N34-53E56"),
        "ki" to Pair("Kiashar", "37N24-50E01"),
        "m" to Pair("Mashhad", "36N15-59E33"),
        "mh" to Pair("Bandar-e Mahshahr", "30N37-49E12"),
        "q" to Pair("Qasr-e Shirin", "34N27-45E37"),
        "s" to Pair("Sirjan", "29N27-55E41"),
        "t" to Pair("Tayebad", "34N44-60E48"),
        "te" to Pair("Tehran", "35N45-51E25"),
        "z" to Pair("Zahedan", "29N28-60E53"),
        "zb" to Pair("Zabol", "31N02-61E33")
    ),
    "IRQ" to mapOf(
        "d" to Pair("Salah al-Din (Saladin)", "34N27-43E35"),
        "s" to Pair("Sulaimaniya", "35N33-45E26")
    ),
    "ISL" to mapOf(
        "f" to Pair("Fjallabyggd", "66N09-18W55"),
        "g" to Pair("Keflavik/Grindavik", "64N01-22W34"),
        "gt" to Pair("Grindavik Thorbjöen", "63N51'08\"-22W26'00\""),
        "hf" to Pair("Hornafjördur", "64N15-15W13"),
        "if" to Pair("Isafjördur", "66N05-23W02"),
        "n" to Pair("Neskaupstadur", "65N09-13W42"),
        "r" to Pair("Reykjavik Aero/HFDL", "64N05-21W51"),
        "rf" to Pair("Raufarhöfn", "66N27-15W56"),
        "rs" to Pair("Reykjavik-Seltjarjarnes", "64N09'03\"-22W01'40\""),
        "s" to Pair("Saudanes", "66N11'08\"-18W57'05\""),
        "sh" to Pair("Stórhöfði", "63N23'58\"-20W17'19\""),
        "v" to Pair("Vestmannaeyjar", "63N27-20W16")
    ),
    "ISR" to mapOf(
        "h" to Pair("Haifa", "32N49-35E00"),
        "ii" to Pair("Unknown 1224", null),
        "L" to Pair("Lod (Galei Zahal)", "31N58-34E52"),
        "sy" to Pair("She'ar-Yeshuv", "33N12'55\"-35E38'41\""),
        "y" to Pair("Yavne", "31N54-34E45")
    ),
    "J" to mapOf(
        "ao" to Pair("Aonoyama Signal Station, Utazu (Kagawa)", "34N18-133E49"),
        "as" to Pair("Asahikawa AF JJU22", "43N48-142E22"),
        "ay" to Pair("Ashiya AB JJZ59", "33N53-130E39"),
        "c" to Pair("Chiba Nagara", "35N28-140E13"),
        "ct" to Pair("Chitose AB, Hokkaido JJR20", "42N48-141E40"),
        "es" to Pair("Esaki Signal Station (Osaka Bay), Awaji island (Hyogo)", "34N35'54\"-134E59'32\""),
        "f" to Pair("Chofu Campus, Tokyo", "35N39-139E33"),
        "fu" to Pair("Fuchu AB JJT55", "35N41-139E30"),
        "gf" to Pair("Gifu AB JJV67", "35N24-136E52"),
        "h" to Pair("Mount Hagane", "33N27'54\"-130E10'32\""),
        "hf" to Pair("Hofu / Bofu AB JJX36", "34N02-131E33"),
        "hm" to Pair("Hamamatsu AB JJV56", "34N45-137E42"),
        "hy" to Pair("Hyakurigahara AB JJT33", "36N11-140E25"),
        "io" to Pair("Imabari Ohama Vessel Station (Kurushima), Imabari (Ehime)", "34N05'25\"-132E59'16\""),
        "ir" to Pair("Iruma / Irumagawa AB JJT44", "35N51-139E25"),
        "it" to Pair("Itoman, Okinawa JFE", "26N09-127E40"),
        "iw" to Pair("Isewan Signal Station, Cape Irago, Tahara (Aichi)", "34N35-137E01"),
        "k" to Pair("Kagoshima JMH", "31N19-130E31"),
        "kg" to Pair("Kumagaya AB JJT66", "36N10-139E19"),
        "ki" to Pair("Kisarazu AB JJT22", "35N24-139E55"),
        "kk" to Pair("Komaki AB (Nagoya) JJV23", "35N15-136E55"),
        "km" to Pair("Kume Shima / Kumejina, Okinawa JJU66", "26N22-126E43"),
        "kn" to Pair("Kanmon Oseto Strait Signal Station,", "33N55-130E56"),
        "ko" to Pair("Komatsu AB JJV90", "36N24-136E24"),
        "ks" to Pair("Kasuga AB JJZ37", "33N32-130E28"),
        "ku" to Pair("Kumamoto JJE20", "32N50-130E51"),
        "ky" to Pair("Kyodo", "36N11-139E51"),
        "kz" to Pair("Kannonzaki Signal Station, Yokosuka (Kanagawa)", "35N15'22\"-139E44'36\""),
        "m" to Pair("Miura", "35N08'23\"-139E38'32\""),
        "mh" to Pair("Miho AB, Yonago JJX25", "35N30-133E14"),
        "ms" to Pair("Misawa AB JJS21", "40N42-141E22"),
        "mt" to Pair("Matsushima AB JJS32", "38N24-141E13"),
        "mu" to Pair("Muroto (Kochi, Shikoku)", "33N17-134E09"),
        "mz" to Pair("Makurazaki (Kagoshima, Kyushu)", "31N16-130E18"),
        "n" to Pair("Nemuro", "43N18-145E34"),
        "nh" to Pair("Naha AB, Okinawa", "26N12-127E39"),
        "nk" to Pair("Nagoya Kinjo Signal Station, Nagoya (Aichi)", "35N02'06\"-136E50'47\""),
        "nr" to Pair("Nara JJW24", "34N34-135E46"),
        "ny" to Pair("Nyutabaru AB JJZ26", "32N05-131E27"),
        "o" to Pair("Mount Otakadoya", "37N22'22\"-140E50'56\""),
        "oe" to Pair("Okinoerabu JJZ44", "27N26-128E42"),
        "ok" to Pair("Okinawa", "26N29-127E56"),
        "os" to Pair("Osaka JJD20", "34N47-135E26"),
        "ot" to Pair("Otaru, Hokkaido JJS65", "43N11-141E00"),
        "sa" to Pair("Sapporo / Chitose AB JJA20", "42N47-141E40"),
        "sg" to Pair("Sodegaura (Kubota), Chiba", "35N26'48\"-140E01'11\""),
        "sn" to Pair("Sendai / Kasuminome AB JJB20", "38N14-140E55"),
        "sz" to Pair("Shizuoka", "34N59-138E23"),
        "tk" to Pair("Tokyo / Tachikawa AF JJC20 JJT88", "35N43-139E24"),
        "ts" to Pair("Tsuiki AB JJZ48", "33N41-131E02"),
        "tv" to Pair("Tokyo Volmet, Kagoshima Broadcasting Station", "31N43-130E44"),
        "xx" to Pair("Unknown site", null),
        "y" to Pair("Yamata", "36N10-139E50"),
        "yo" to Pair("Yokota AFB", "35N44'55\"-139E20'55\""),
        "yz" to Pair("Yozadake (Okinawa)", "26N08-127E42"),
        "zz" to Pair("Trading sites, licensed until 2027: Kubota 35N26'47\"N-140E01'11\"/Minami-Boso /Kawato 34N56'47\"-139E56'13\"/Kawato 34N22'48\"-139E56'11\"/Kawato 35N57'49\"-139E56'14\"/Kaihotsu 35N01'05\"-139E59'00\"", "35N06'53\"-139E57'20\"")
    ),
    "JOR" to mapOf(
        "ak" to Pair("Al Karanah / Qast Kherane", "31N44-36E26"),
        "am" to Pair("Amman", "31N58-35E53")
    ),
    "KAZ" to mapOf(
        "a" to Pair("Almaty", "43N15-76E55"),
        "ak" to Pair("Aktyubinsk/Aktöbe", "50N15-57E13"),
        "av" to Pair("Almaty Volmet", "43N21-77E03"),
        "n" to Pair("Nursultan (Akmolinsk/Tselinograd/Astana)", "51N01-71E28")
    ),
    "KEN" to mapOf(
        "ny" to Pair("Nairobi 5YE", "01S15-36E52")
    ),
    "KGZ" to mapOf(
        "b" to Pair("Bishkek (Krasnaya Rechka)", "42N53-74E59"),
        "bk" to Pair("Bishkek Beta", "43N04-73E39")
    ),
    "KOR" to mapOf(
        "c" to Pair("Chuncheon", "37N56-127E46"),
        "d" to Pair("Dangjin", "36N58-126E37"),
        "db" to Pair("Daebu-do (Ansan)", "37N13'14\"-126E33'29\""),
        "g" to Pair("Goyang / Koyang, Gyeonggi-do / Kyonggi-do", "37N36-126E51"),
        "h" to Pair("Hwasung/Hwaseong", "37N13-126E47"),
        "j" to Pair("Jeju/Aewol HLAZ", "33N29-126E23"),
        "k" to Pair("Kimjae", "35N50-126E50"),
        "m" to Pair("Muan HFDL", "35N1'56\"-126E14'19\""),
        "n" to Pair("Hwaseong?", "37N13-126E47"),
        "o" to Pair("Suwon-Osan/Hwaseong-Jeongnam", "37N09-127E00"),
        "s" to Pair("Seoul-Incheon HLKX", "37N25-126E45"),
        "sg" to Pair("Seoul-Gangseo-gu", "37N34-126E58"),
        "t" to Pair("Taedok", "36N23-127E22"),
        "w" to Pair("Nowon Gyeonggi-do / Seoul-Taereung", "37N38-127E07"),
        "xx" to Pair("Unknown site", null)
    ),
    "KOS" to mapOf(
        "b" to Pair("Camp Bondsteel", "42N22-21E15")
    ),
    "KRE" to mapOf(
        "c" to Pair("Chongjin", "41N45'45\"-129E42'30\""),
        "e" to Pair("Hyesan", "41N04-128E02"),
        "h" to Pair("Hamhung", "39N56-127E39"),
        "hw" to Pair("Hwadae/Kimchaek", "40N41-129E12"),
        "j" to Pair("Haeju", "38N01-125E43"),
        "k" to Pair("Kanggye", "40N58-126E36"),
        "kn" to Pair("Kangnam", "38N54-125E39"),
        "p" to Pair("Pyongyang", "39N05-125E23"),
        "s" to Pair("Sariwon", "38N05-125E08"),
        "sg" to Pair("Samgo", "38N02-126E32"),
        "sn" to Pair("Sinuiju", "40N05-124E27"),
        "sw" to Pair("Sangwon", "38N51-125E31"),
        "u" to Pair("Kujang", "40N05-126E05"),
        "w" to Pair("Wonsan", "39N05-127E25"),
        "y" to Pair("Pyongsong", "40N05-124E24")
    ),
    "KWT" to mapOf(
        "j" to Pair("Jahra/Umm al-Rimam", "29N30-47E40"),
        "k" to Pair("Kabd/Sulaibiyah", "29N09-47E46"),
        "kw" to Pair("Kuwait", "29N23-47E39")
    ),
    "LAO" to mapOf(
        "s" to Pair("Sam Neua", "20N16-104E04"),
        "v" to Pair("Vientiane", "17N58-102E33")
    ),
    "LBN" to mapOf(
        "be" to Pair("Beirut", "33N51-35E33")
    ),
    "LBR" to mapOf(
        "e" to Pair("Monrovia ELWA", "06N14-10W42"),
        "m" to Pair("Monrovia Mamba Point", "06N19-10W49"),
        "s" to Pair("Star Radio Monrovia", "06N18-10W47")
    ),
    "LBY" to mapOf(
        "*" to Pair("Sabrata", "32N54-13E11")
    ),
    "LTU" to mapOf(
        "*" to Pair("Sitkunai  except:", "55N02'37\"-23E48'28\""),
        "v" to Pair("Viesintos", "55N42-24E59")
    ),
    "LUX" to mapOf(
        "j" to Pair("Junglinster", "49N43-06E15"),
        "m" to Pair("Marnach", "50N03-06E05")
    ),
    "LVA" to mapOf(
        "*" to Pair("Ulbroka", "56N56-24E17")
    ),
    "MAU" to mapOf(
        "m" to Pair("Malherbes", "20S20'28\"-57E30'46\"")
    ),
    "MDA" to mapOf(
        "*" to Pair("Maiac near Grigoriopol  except:", "47N17-29E24"),
        "ca" to Pair("Cahul", "45N56-28E17"),
        "ce" to Pair("Chisinau", "47N01-28E49"),
        "co" to Pair("Codru-Costiujeni", "46N57-28E50"),
        "ed" to Pair("Edinet", "48N11-27E18")
    ),
    "MDG" to mapOf(
        "*" to Pair("Talata Volonondry  except:", "18S50-47E35"),
        "a" to Pair("Ambohidrano/Sabotsy", "18S55-47E32"),
        "m" to Pair("Mahajanga (WCBC)", "15S43'38\"-46E26'45\"")
    ),
    "MDR" to mapOf(
        "ps" to Pair("Porto Santo", "33N04-16W21")
    ),
    "MEX" to mapOf(
        "c" to Pair("Cuauhtémoc, Mexico City", "19N26-99W09"),
        "cb" to Pair("Choya Bay, Sonora", "31N20-113E38"),
        "e" to Pair("Mexico City (Radio Educación)", "19N16-99W03"),
        "i" to Pair("Iztacalco, Mexico City", "19N23-98W57"),
        "m" to Pair("Merida", "20N58-89W36"),
        "mz" to Pair("Mazatlan", null),
        "pr" to Pair("Progreso", "21N16-89W47"),
        "s" to Pair("San Luis Potosi", "22N10-101W00"),
        "p" to Pair("Chiapas", "17N00-92W00"),
        "u" to Pair("UNAM, Mexico City", "19N23-99W10"),
        "vh" to Pair("Villahermosa, Tabasco", "18N00-93W00")
    ),
    "MLA" to mapOf(
        "ka" to Pair("Kajang", "03N01-101E46"),
        "kk" to Pair("Kota Kinabalu", "06N12-116E14"),
        "ku" to Pair("Kuching-Stapok (closed 2011)", "01N33-110E20"),
        "l" to Pair("Lumut", "04N14-100E38"),
        "s" to Pair("Sibu", "02N18-111E49")
    ),
    "MLI" to mapOf(
        "c" to Pair("CRI-Bamako", "12N41-08W02"),
        "k" to Pair("Kati(Bamako)", "12N45-08W03")
    ),
    "MLT" to mapOf(
        "mr" to Pair("Malta Radio", "35N49-14E32")
    ),
    "MNE" to mapOf(
        "oc" to Pair("Ocas", "42N01-19E08")
    ),
    "MNG" to mapOf(
        "a" to Pair("Altay", "46N19-096E15"),
        "c" to Pair("Choybalsan", null),
        "m" to Pair("Moron/Mörön", "49N37-100E10"),
        "u" to Pair("Ulaanbaatar-Khonkhor", "47N55-107E00")
    ),
    "MRA" to mapOf(
        "m" to Pair("Marpi, Saipan (KFBS)", "15N16-145E48"),
        "s" to Pair("Saipan/Agingan Point (IBB)", "15N07-145E41"),
        "t" to Pair("Tinian (IBB)", "15N03-145E36")
    ),
    "MRC" to mapOf(
        "ag" to Pair("Agadir", "30N22-09W33"),
        "b" to Pair("Briech (VoA/RL/RFE)", "35N33-05W58"),
        "ca" to Pair("Casablanca", "33N37-07W38"),
        "L" to Pair("Laayoune (UN 6678)", "27N09-13W13"),
        "n" to Pair("Nador (RTM,Medi1)", "34N58-02W55"),
        "s" to Pair("Safi", "32N18-09W14")
    ),
    "MRT" to mapOf(
        "fc" to Pair("Fort-de-France CROSS", "14N36-61W05"),
        "u" to Pair("FUF Martinique", "14N31'55\"-60W58'44\"")
    ),
    "MTN" to mapOf(
        "*" to Pair("Nouakchott", "18N07-15W57")
    ),
    "MYA" to mapOf(
        "n" to Pair("Naypyidaw", "20N11-96E08"),
        "p" to Pair("Phin Oo Lwin, Mandalay", "22N00'58\"-96E33'01\""),
        "t" to Pair("Taunggyi(Kalaw)", "20N38-96E35"),
        "y" to Pair("Yegu (Yangon/Rangoon)", "16N52-96E10")
    ),
    "NCL" to mapOf(
        "n" to Pair("Nouméa - FUJ Ouen-Toro", "22S18'19\"-166E27'17\"")
    ),
    "NFK" to mapOf(
        "*" to Pair("Norfolk Island", "29S02-167E57")
    ),
    "NGR" to mapOf(
        "*" to Pair("Niamey", "13N30-02E06")
    ),
    "NIG" to mapOf(
        "a" to Pair("Abuja-Gwagwalada", "08N56-07E04"),
        "b" to Pair("Ibadan", "07N23-03E54"),
        "e" to Pair("Enugu", "06N27-07E27"),
        "i" to Pair("Ikorodu", "06N36-03E30"),
        "j" to Pair("Abuja-Lugbe (new site, opened March 2012)", "08N58-07E21"),
        "k" to Pair("Kaduna", "10N31-07E25")
    ),
    "NMB" to mapOf(
        "wb" to Pair("Walvis Bay", "23S05'15\"-14E37'30\"")
    ),
    "NOR" to mapOf(
        "a" to Pair("Andenes", "69N16'08\"-16E02'26\""),
        "as" to Pair("Andenes-Saura", "69N08'24\"-16E01'12\""),
        "at" to Pair("Andenes (Telenor site)", "69N17-16E03"),
        "be" to Pair("Bergen (LLE station, Erdal, Askoy Island)", "60N26-05E13"),
        "bj" to Pair("Bjørnøya / Bear Island", "74N26-19E03"),
        "bk" to Pair("Bergen-Kvarren", "60N23'22\"-05E15"),
        "bo" to Pair("Bodø", "67N17-14E23"),
        "bs" to Pair("Bodø-Seines", "67N12-14E22"),
        "bv" to Pair("Berlevåg", "70N52-29E04"),
        "e" to Pair("Erdal", "60N26'56\"-05E12'59\""),
        "f" to Pair("Florø", "61N36-05E02"),
        "fs" to Pair("Farsund", "58N05-06E47"),
        "hf" to Pair("Hammerfest", "70N40-23E41"),
        "hp" to Pair("Hopen Island", "76N33-25E07"),
        "jm" to Pair("Jan Mayen Island", "71N00-08W30"),
        "ly" to Pair("Longyearbyen, Svalbard", "78N04-13E37"),
        "ma" to Pair("Marøy", "60N42-04E53"),
        "mg" to Pair("Molde-Gossen", "62N50'30\"-06E47'35\""),
        "mr" to Pair("Mågerø", "59N09-10E26"),
        "nm" to Pair("Nordmela-Andøya", "69N07'40\"-15E38"),
        "no" to Pair("Novik E", "66N58'58\"-13E52'23\""),
        "oh" to Pair("Oslo-Helgelandsmoen", "60N07-10E12"),
        "or" to Pair("Ørlandet", "63N41-09E39"),
        "ro" to Pair("Rogaland (Vigreskogen)", "58N39-05E35"),
        "sa" to Pair("Sandnessjøen", "66N01-12E38"),
        "sr" to Pair("Sørreisa", "69N04-18E00"),
        "st" to Pair("Stavanger-Ulsnes", "59N00-05E43"),
        "tj" to Pair("Tjøme", null),
        "va" to Pair("Vardø", "70N22-31E06")
    ),
    "NPL" to mapOf(
        "*" to Pair("Khumaltar", "27N30-85E30")
    ),
    "NZL" to mapOf(
        "a" to Pair("Auckland (Wiroa Island)", "37S01-174E49"),
        "du" to Pair("Dunedin", "45S52-170E30"),
        "r" to Pair("Rangitaiki", "38S50-176E25"),
        "ru" to Pair("Russell", "35S17-174E07"),
        "t" to Pair("Taupo", "38S52-176E26"),
        "xx" to Pair("Unknown site", null)
    ),
    "OCE" to mapOf(
        "fa" to Pair("Faa'a airport", "17S33-149W37"),
        "ma" to Pair("Mahina (FUM Tahiti)", "17S30'21\"-149W28'57\"")
    ),
    "OMA" to mapOf(
        "a" to Pair("A'Seela", "21N55-59E37"),
        "s" to Pair("Seeb", "23N40-58E10"),
        "t" to Pair("Thumrait", "17N38-53E56")
    ),
    "PAK" to mapOf(
        "i" to Pair("Islamabad", "33N27-73E12"),
        "kv" to Pair("Karachi Volmet", "24N54-67E10"),
        "m" to Pair("Multan", "30N05'22\"-71E29'30\""),
        "p" to Pair("Peshawar", "34N00-71E30"),
        "q" to Pair("Quetta", "30N15-67E00"),
        "r" to Pair("Rawalpindi", "33N30-73E00")
    ),
    "PHL" to mapOf(
        "b" to Pair("Bocaue (FEBC)", "14N48-120E55"),
        "dv" to Pair("Davao City, Mindanao", "07N05-125E36"),
        "i" to Pair("Iba (FEBC)", "15N20-119E58"),
        "ko" to Pair("Koronadal City, Mindanao", "06N31-124E49"),
        "m" to Pair("Marulas/Quezon City, Valenzuela (PBS 6170,9581)", "14N41-120E58"),
        "p" to Pair("Palauig, Zembales (RVA)", "15N28-119E50"),
        "po" to Pair("Poro", "16N26-120E17"),
        "sc" to Pair("Santiago City, Luzon", "16N42-121E36"),
        "t" to Pair("Tinang (VoA)", "15N21-120E37"),
        "x" to Pair("Tinang-2/portable 50kW (VoA)", "15N21-120E37"),
        "zm" to Pair("Zamboanga City, Mindanao", "06N55-122E07")
    ),
    "PLW" to mapOf(
        "*" to Pair("Koror-Babeldaob (Medorn)", "07N27'22\"-134E28'24\"")
    ),
    "PNG" to mapOf(
        "a" to Pair("Alotau", "10S18-150E28"),
        "b" to Pair("Bougainville/Buka-Kubu", "05S25-154E40"),
        "d" to Pair("Daru", "09S05-143E10"),
        "g" to Pair("Goroka", "06S02-145E22"),
        "ka" to Pair("Kavieng", "02S34-150E48"),
        "kb" to Pair("Kimbe", "05S33-150E09"),
        "ke" to Pair("Kerema", "07S59-145E46"),
        "ki" to Pair("Kiunga", "06S07-141E18"),
        "ku" to Pair("Kundiawa", "06S00-144E57"),
        "la" to Pair("Lae (Morobe)", "06S44-147E00"),
        "ln" to Pair("Lae Nadzab airport", "06S34-146E44"),
        "lo" to Pair("Lorengau", "02S01-147E15"),
        "ma" to Pair("Madang", "05S14-145E45"),
        "me" to Pair("Mendi", "06S13-143E39"),
        "mh" to Pair("Mount Hagen", "05S54-144E13"),
        "pm" to Pair("Port Moresby (Waigani)", "09S28-147E11"),
        "pr" to Pair("Port Moresby Maritime Radio", "09S28-147E11"),
        "po" to Pair("Popondetta", "08S45-148E15"),
        "r" to Pair("Rabaul", "04S13-152E13"),
        "v" to Pair("Vanimo", "02S40-141E17"),
        "va" to Pair("Vanimo", "02S41-141E18"),
        "wa" to Pair("Port Moresby (Radio Wantok)", "09S28-147E11"),
        "ww" to Pair("Wewak", "03S35-143E40")
    ),
    "PNR" to mapOf(
        "al" to Pair("Albrook, Panama City", "08N58'08\"-79W33'03\"")
    ),
    "POL" to mapOf(
        "b" to Pair("Babice", "52N15-20E50"),
        "p" to Pair("Puchaly, in Falenty", "52N08'37\"-20E54"),
        "sk" to Pair("Solec Kujawski", "53N01'13\"-18E15'44\""),
        "u" to Pair("Ustka", "54N34'57\"-16E50'12\""),
        "w" to Pair("Witowo", "54N33-16E32")
    ),
    "POR" to mapOf(
        "ms" to Pair("Monsanto", "38N44-09W11")
    ),
    "PRG" to mapOf(
        "c" to Pair("Capiatá", "25S24-57W28"),
        "f" to Pair("Filadelfia", "22S21-60W02")
    ),
    "PRU" to mapOf(
        "ar" to Pair("Arequipa", "16S25-71W32"),
        "at" to Pair("Atalaya", "10S43'48\"-73W45'20\""),
        "bv" to Pair("Bolívar", "07S21-77W50"),
        "cc" to Pair("Chiclayo/Santa Ana (Carretera a Lambayeque)", "06S44-79W51"),
        "ce" to Pair("Celendín", "06S53-78W09"),
        "ch" to Pair("Chachapoyas", "06S14-77W52"),
        "cl" to Pair("Callalli", "15S30-71W26"),
        "cp" to Pair("Cerre de Pasco", "10S40-76W15"),
        "ct" to Pair("Chota", "06S33-78W39"),
        "cu" to Pair("Cuzco-Cerro Oscollo", "13S31'08\"-72W00'37\""),
        "cz" to Pair("Chazuta/Tarapoto, San Martin", "06S34-76W08"),
        "hb" to Pair("Huancabamba", "05S14-79W27"),
        "hc" to Pair("Huancayo/Viques", "12S12'06\"-75W13'11\""),
        "ho" to Pair("Huánuco", "09S56-76W14"),
        "ht" to Pair("Huanta/Tirapampa", "12S57-74W15"),
        "hu" to Pair("Huanta/Vista Alegre (Pasaje Amauta)", "12S56'12\"-74W15'10\""),
        "hv" to Pair("Huancavelica", "12S47-74W59"),
        "hz" to Pair("Huaraz", "09S31-77W32"),
        "in" to Pair("Chau Alto/Independencia, Huarez, Ancash (planned for 6090 kHz in 2015/16)", "09S31'05\"-77W32'57\""),
        "iq" to Pair("Iquitos/Moronacocha", "03S45-73W16"),
        "ja" to Pair("Jaén", "05S45-78W51"),
        "ju" to Pair("Junín/Cuncush", "11S10-76W00"),
        "li" to Pair("Lima", "12S06-77W03"),
        "or" to Pair("La Oroya", "11S32-75W54"),
        "pc" to Pair("Paucartambo", "10S54-75W51"),
        "pm" to Pair("Puerto Maldonado", "12S36-69W10"),
        "qb" to Pair("Quillabamba/Macamango", "12S52-72W42"),
        "qt" to Pair("Quillabamba/Tiobamba Baja", "12S49-72W41"),
        "rm" to Pair("Rodrigues de Mendoza", "06S23-77W30"),
        "sc" to Pair("Santa Cruz (R Satelite)", "06S41-79W02"),
        "si" to Pair("Sicuani", "14S16-71W14"),
        "su" to Pair("Santiago de Chuco", "08S09-78W11"),
        "ta" to Pair("Tarma/Cerro Penitencia", "11S24'32\"-75W41'31\""),
        "tc" to Pair("Tacna", "18S00-70W13"),
        "ur" to Pair("valle de Urubamba, Cusco", "13S21-72W07"),
        "vv" to Pair("Valle de Vitor, San Luís, Arequipa", "16S28'06\"-71W54'14\""),
        "yu" to Pair("Yurimaguas", "05S54-76W07")
    ),
    "PTR" to mapOf(
        "i" to Pair("Isabela", "18N23-67W11"),
        "s" to Pair("Salinas, Camp Santiago", "17N59-66E18")
    ),
    "QAT" to mapOf(
        "dr" to Pair("Doha Radio", "25N42-16E32")
    ),
    "REU" to mapOf(
        "su" to Pair("FUX Sainte-Suzanne", "20S54'36\"-55E35'05\"")
    ),
    "ROU" to mapOf(
        "b" to Pair("Bucuresti/Bucharest airport", "44N34-26E05"),
        "c" to Pair("Constanta", "44N06-28E37"),
        "g" to Pair("Galbeni", "46N45-26E41"),
        "s" to Pair("Saftica 100kW", "44N38-27E05"),
        "t" to Pair("Tiganesti 300kW", "44N45-26E05")
    ),
    "RRW" to mapOf(
        "*" to Pair("Kigali", "01S55-30E07")
    ),
    "RUS" to mapOf(
        "a" to Pair("Armavir/Tblisskaya/Krasnodar", "45N29-40E07"),
        "af" to Pair("Astrakhan Fedorovka", "45N50'37\"-47E38'37\""),
        "ag" to Pair("Angarsk", "56N08'08\"-101E38'20\""),
        "ak" to Pair("Arkhangelsk Beta", "64N24-41E32"),
        "am" to Pair("Amderma", "69N46-61E34"),
        "an" to Pair("Astrakhan Narimanovo", "46N17-48E00"),
        "ar" to Pair("Arkhangelsk", "64N35-40E36"),
        "as" to Pair("Astrakhan Military Base", "47N25-47E55"),
        "at" to Pair("Arkhangelsk-Talagi", "64N36-40E43"),
        "ay" to Pair("Anadyr, Chukotka", "64N44'24\"-177E30'32\""),
        "b" to Pair("Blagoveshchensk (Amur)", "50N16-127E33"),
        "ba" to Pair("Barnaul, Altay", "53N20-83E48"),
        "bg" to Pair("Belaya Gora, Sakha(Yakutia)", "68N32-146E11"),
        "bo" to Pair("Bolotnoye, Novosibirsk oblast", "55N45'22\"-84E26'52\""),
        "B1" to Pair("Buzzer sites: Kerro (St Petersburg)", "60N18'40\"-30E16'40\""),
        "B2" to Pair("Buzzer sites: Iskra, Naro-Fominsk (Moscow)  and Kerro (St Petersburg) 60N18'40\"-30E16'40\"", "55N25'35\"-36E42'33\""),
        "c" to Pair("Chita (Atamanovka) (S Siberia)", "51N50-113E43"),
        "cb" to Pair("Cheboksary", null),
        "cs" to Pair("Cherskiy, Yakutia", "68N45-161E20"),
        "cy" to Pair("Chelyabinsk", "55N18-61E30"),
        "di" to Pair("Dikson", "73N30-80E32"),
        "ek" to Pair("Yekaterinburg", null),
        "el" to Pair("Elista", "46N22-44E20"),
        "ey" to Pair("Yeysk port", "46N43'25\"-38E16'34\""),
        "ge" to Pair("Gelendzhik", "44N35'56\"-37E57'52\""),
        "gk" to Pair("Goryachiy Klyuch, Omsk", "55N01'04\"-73E11'32\""),
        "go" to Pair("Gorbusha", "55N51'28\"-38E13'46\""),
        "gr" to Pair("Grozny", "43N16-45E43"),
        "i" to Pair("Irkutsk (Angarsk) (S Siberia)", "52N25-103E40"),
        "ig" to Pair("Igrim XMAO", "63N11-64E25"),
        "ik" to Pair("Ivashka, Kamchatka", "58N33'43\"-162E18'26\""),
        "io" to Pair("Ioshkar-Ola", null),
        "ir" to Pair("Irkutsk port", "52N19'15\"-104E17'05\""),
        "iv" to Pair("Irkutsk Volmet", "52N16-104E23"),
        "iz" to Pair("Izhevsk sites", "56N50-53E15"),
        "k" to Pair("Kaliningrad-Bolshakovo", "54N54-21E43"),
        "ka" to Pair("Komsomolsk-na-Amur (Far East)", "50N39-136E55"),
        "kd" to Pair("Krasnodar Beta", "44N36-39E34"),
        "kf" to Pair("Krasnoyarsk HFDL site", "56N06-92E18"),
        "kg" to Pair("Kaliningrad Radio UIW23", "54N43-20E44"),
        "kh" to Pair("Khabarovsk (Far East)", "48N33-135E15"),
        "ki" to Pair("Kirinskoye, Sakhalin", "51N25-143E26"),
        "kl" to Pair("Kaliningrad Military Base Yantarny", "54N44-19E58"),
        "km" to Pair("Khanty-Mansiysk", "61N02-69E05"),
        "ko" to Pair("Korsakov, Sakhalin", "46N37-142E46"),
        "kp" to Pair("Krasnodar-Poltovskaya", "45N24'18\"-38E09'29\""),
        "kr" to Pair("Krasnoyarsk", "56N02-92E44"),
        "kt" to Pair("Kotlas", "61N14-46E42"),
        "ku" to Pair("Kurovskaya-Avsyunino (near Moscow)", "55N34-39E09"),
        "kv" to Pair("Kirensk Volmet", "57N46-108E04"),
        "kx" to Pair("Kamenka, Sakha", "69N30-161E20"),
        "ky" to Pair("Kyzyl", "51N41-94E36"),
        "kz" to Pair("Kazan", "55N36-49E17"),
        "k1" to Pair("Krasnodar Pashkovsky", "45N02-39E10"),
        "k2" to Pair("Kamskoye Ustye, Tatarstan", "55N11'44\"-49E17'13\""),
        "k3" to Pair("Kolpashevo, Tomsk", "58N19'06\"-82E59'10\""),
        "k4" to Pair("Komsomolsk-na-Amure", "50N32-137E02"),
        "k5" to Pair("Kultayevo, Perm", "57N54'38\"-55E55'07\""),
        "k6" to Pair("Kozmino, Cape Povorotny, Primorye", "42N40'50\"-133E02'10\""),
        "L" to Pair("Lesnoy (near Moscow)", "56N04-37E58"),
        "li" to Pair("Liski, Voronezh", "50N58'06\"-39E31'17\""),
        "ln" to Pair("Labytnangi, YNAO", "66N38'37\"-66E31'47\""),
        "l2" to Pair("Labytnangi, YNAO Gazprom", "66N39'30\"-66E13'27\""),
        "m" to Pair("Moscow/Moskva", "55N45-37E18"),
        "ma" to Pair("Magadan/Arman", "59N41'40\"-150E09'31\""),
        "mg" to Pair("Magadan Military Base", "59N42-150E10"),
        "mi" to Pair("Mineralnye Vody", "44N14-43E05"),
        "mk" to Pair("Makhachkala, Dagestan", "42N49-47E39"),
        "mm" to Pair("Murmansk Meteo", "68N52'00\"-33E04'30\""),
        "mp" to Pair("Maykop", "44N40-40E02"),
        "mr" to Pair("Moscow-Razdory", "55N45-37E18"),
        "mt" to Pair("MTUSI University, Moscow", "55N45'15\"-37E42'43\""),
        "mu" to Pair("Murmansk/Monchegorsk", "67N55-32E59"),
        "mv" to Pair("Magadan Volmet", "59N55-150E43"),
        "mx" to Pair("Makhachkala, Dagestan", "43N00'13\"-47E28'13\""),
        "mz" to Pair("Mozdok, North Ossetia", "43N45-44E39"),
        "m2" to Pair("Magadan Rosmorport", "59N42'44\"-150E59'39\""),
        "m3" to Pair("Makhachkala, Dagestan", "42N59-47E31"),
        "m4" to Pair("Mezen, Arkhangelsk", "65N54-44E16"),
        "m5" to Pair("Murmansk MRCC", "68N51'10\"-32E59'15\""),
        "n" to Pair("Novosibirsk / Oyash, (500 kW, 1000 kW)", "55N31-83E45"),
        "nc" to Pair("Nalchik, Kabardino-Balkaria", "43N31-43E38"),
        "ne" to Pair("Nevelsk, Sakhalin", "46N31-141E51"),
        "ni" to Pair("Nizhnevartovsk", "60N57-76E29"),
        "nk" to Pair("Nizhnekamsk", null),
        "nm" to Pair("Naryan-Mar", "67N38-53E07"),
        "nn" to Pair("Nizhni Novgorod sites", "56N11-43E58"),
        "no" to Pair("Novosibirsk City", "55N02-82E55"),
        "np" to Pair("Novosibirsk city port", "55N00'55\"-82E55'03\""),
        "nr" to Pair("Novorossiysk", "44N44'46\"-37E41'09\""),
        "ns" to Pair("Novosibirsk Shipping Canal", "54N50'42\"-83E02'25\""),
        "nu" to Pair("Novy Urengoy", "66N04-76E31"),
        "nv" to Pair("Novosibirsk Volmet", "55N00'16\"-82E33'44\""),
        "ny" to Pair("Nadym", "65N29-72E42"),
        "oe" to Pair("Okhotskoye, Sakhalin", "46N52'18\"-143E09'11\""),
        "og" to Pair("Orenburg-Gagarin airport", "51N48-55E27"),
        "ok" to Pair("Oktyarbskiy, Kamchatka", "52N39'26\"-156E14'41\""),
        "ol" to Pair("Oleniy, Yamalo-Nenets", "72N35'47\"-77E39'34\""),
        "om" to Pair("Omsk", "54N58-73E19"),
        "or" to Pair("Orenburg", "51N46-55E06"),
        "ox" to Pair("Okhotsk Bulgin", "59N22-143E09"),
        "o2" to Pair("Orenburg-2", "51N42-55E01"),
        "p" to Pair("Petropavlovsk-Kamchatskiy (Yelizovo)", "53N11-158E24"),
        "pc" to Pair("Pechora", "65N07-57E08"),
        "pe" to Pair("Perm", "58N03-56E14"),
        "pk" to Pair("Petropavlovsk-Kamchatskij Military Base", "53N10-158E27"),
        "pm" to Pair("Perm airport", "57N55-56E01"),
        "po" to Pair("Preobrazhenie, Primorye", "42N53'53\"-133E53'54\""),
        "pp" to Pair("Petropavlovsk-Kamchatskiy Port", "53N03'47\"-158E34'09\""),
        "ps" to Pair("Pskov airport", "57N47-28E24"),
        "pt" to Pair("Sankt Peterburg Military Base", "60N00-30E00"),
        "pu" to Pair("Puteyets, Pechora, Rep.Komi", "65N10-57E05"),
        "pv" to Pair("Sankt Peterburg Volmet / Pulkovo", "59N46'50\"-30E14'54\""),
        "py" to Pair("Peleduy, Sakha", "59N36'38\"-112E44'38\""),
        "p2" to Pair("Petropavlovsk-Kamchatskiy Port", "53N01'59\"-158E38'32\""),
        "p3" to Pair("Petropavlovsk-Kamchatskiy Commercial sea port", "53N00'23\"-158E39'15\""),
        "p4" to Pair("Pevek, Chukotka", "69N42'03\"-170E15'26\""),
        "p5" to Pair("Plastun, Primorye", "44N43'42\"-136E19'03\""),
        "p6" to Pair("Poronaisk, Sakhalin", "49N13'54\"-143E06'52\""),
        "rd" to Pair("Reydovo, Etorofu Island, Kuril", "45N16'40\"-148E01'30\""),
        "re" to Pair("Revda", "68N02'08\"-34E31'00\""),
        "ro" to Pair("Rossosh, Voronezhskaya oblast", "50N12-39E35"),
        "rp" to Pair("Rostov-na-Donu", "47N17'58\"-39E40'25\""),
        "ru" to Pair("Russkoye Ustye", "71N16-150E16"),
        "rv" to Pair("Rostov Volmet, Rostov-na-Donu", "47N15'12\"-39E49'02\""),
        "ry" to Pair("Rybachi, Primorye", "43N22'30\"-131E53'40\""),
        "s" to Pair("Samara (Zhygulevsk)", "53N17-50E15"),
        "sa" to Pair("Samara Centre", "53N12-50E10"),
        "sb" to Pair("Sabetta, Yamalo-Nenets", "71N17-72E02"),
        "sc" to Pair("Seshcha (Bryansk)", "53N43-33E20"),
        "sd" to Pair("Severodvinsk", "64N34-39E51"),
        "se" to Pair("Sevastopol", "44N56-34E28"),
        "sh" to Pair("Salekhard", "66N35-66E36"),
        "sk" to Pair("Smolensk", "54N47-32E03"),
        "sl" to Pair("Seleznevo, Sakhalin", "46N36'31\"-141E49'59\""),
        "sm" to Pair("Severomorsk/Arkhangelsk Military Base", "69N03-33E19"),
        "so" to Pair("Sochi", "43N27-39E57"),
        "sp" to Pair("St.Petersburg (Popovka/Krasnyj Bor)", "59N39-30E42"),
        "sr" to Pair("Sevastopol Radio", "44N34-33E32"),
        "st" to Pair("Saratov", "51N32-45E51"),
        "su" to Pair("Surgut", "61N20-73E24"),
        "sv" to Pair("Samara airport", "53N30-50E09"),
        "sy" to Pair("Syktyvkar Volmet", "61N38'17\"-50E51'49\""),
        "sz" to Pair("Syzran", null),
        "s1" to Pair("Saratov airport", "51N34-46E03"),
        "s2" to Pair("Stavropol airport", "45N07-42E07"),
        "s3" to Pair("St Petersburg port", "59N54-30E15"),
        "s4" to Pair("St Petersburg Shepelevo", "59N59'06\"-29E07'37\""),
        "s5" to Pair("Sterlegova Cape, Taymyr, Krasnoyarski krai", "75N23'55\"-88E45'34\""),
        "s6" to Pair("Stolbovoy Island, New Siberian Islands, Sakha", "74N10'10\"-135E27'55\""),
        "s7" to Pair("Svobodny, Amur", "51N20'07\"-128E10'33\""),
        "t" to Pair("Taldom - Severnyj, Radiotsentr 3 (near Moscow)", "56N44-37E38"),
        "tc" to Pair("Taganrog Centralny", "47N15-38E50"),
        "tg" to Pair("Taganrog", "47N12'19\"-38E57'06\""),
        "ti" to Pair("Tiksi, Sakha", "71N38'28\"-128E52'48\""),
        "tm" to Pair("Tomsk", "56N29'22\"-84E56'43\""),
        "to" to Pair("Tver-Migalovo", "56N49'30\"-35E45'36\""),
        "tr" to Pair("Temryuk, Krasnodar", "45N19'49\"-37E13'45\""),
        "ts" to Pair("Tarko-Sale", "64N56-77E49"),
        "tu" to Pair("Tulagino, Sakha", "62N14'16\"-129E48'46\""),
        "tv" to Pair("Tavrichanka (Vladivostok, 549, 1377)", "43N20-131E54"),
        "ty" to Pair("Tyumen Volmet", "57N10-65E19"),
        "t3" to Pair("Tiksi-3, Sakha", "71N41'36\"-128E52'51\""),
        "u" to Pair("Ulan-Ude", "51N44-107E26"),
        "ub" to Pair("Ust-Barguzin, Buryatia", "53N25'14\"-109E00'56\""),
        "uf" to Pair("Ufa", "54N34-55E53"),
        "ug" to Pair("Uglegorsk, Sakhalin", "49N05-142E05"),
        "uk" to Pair("Ust-Kamchatsk, Kamchatka", "56N13'04\"-162E30'48\""),
        "us" to Pair("Ulan-Ude/Selenginsk", "52N02-106E56"),
        "uu" to Pair("Ulan-Ude port", "51N50'17\"-107E34'22\""),
        "uy" to Pair("Ustyevoye, Kamchatka", "54N09'16\"-155E50'31\""),
        "v" to Pair("Vladivostok Razdolnoye (Ussuriysk)", "43N32-131E57"),
        "va" to Pair("Vanino, Khabarovski kray", "49N05-140E16"),
        "vg" to Pair("Vologda", "59N17-39E57"),
        "vk" to Pair("Vorkuta", "67N29-63E59"),
        "vl" to Pair("Vladivostok Military Base", "43N07-131E54"),
        "vm" to Pair("Vzmorye, Kaliningrad", "54N41-20E15"),
        "vo" to Pair("Volgograd", "48N47-44E21"),
        "vp" to Pair("Vladivostok port", "43N07-131E53"),
        "vr" to Pair("Varandey, Nenets", "68N48'06\"-57E58'58\""),
        "vv" to Pair("Veselo-Voznesenka", "47N08'30\"-38E19'41\""),
        "vz" to Pair("Vladikavkaz Beslan, North Ossetia", "43N12-44E36"),
        "xe" to Pair("Khabarovsk-Elban", "50N04'24\"-136E36'24\""),
        "xo" to Pair("Kholmsk, Sakhalin", "47N02-142E03"),
        "xv" to Pair("Khabarovsk Volmet", "48N31-135E10"),
        "xx" to Pair("Unknown site(s)", null),
        "xy" to Pair("Ship position given as \"99123 10456\" meaning 12.3N-45.6E", null),
        "ya" to Pair("Yakutsk/Tulagino", "62N01-129E48"),
        "ys" to Pair("Yuzhno-Sakhalinsk (Vestochka)", "46N55-142E54"),
        "yv" to Pair("Yekaterinburg Volmet (Koltsovo)", "56N45-60E48"),
        "yy" to Pair("Yakutsk Volmet", "62N05-129E46"),
        "za" to Pair("Zyryanka, Sakha", "65N45'26\"-150E50'21\""),
        "zg" to Pair("Zhigalovo, Irkutsk region", "54N48'34\"-105E10'10\""),
        "zp" to Pair("Zaporozhye, Kamchatka", "51N30'29\"-156E29'31\""),
        "zy" to Pair("Zhatay, Yakutsk", "62N10'20\"-129E48'21\"")
    ),
    "S" to mapOf(
        "b" to Pair("Bjuröklubb", "64N27'42\"-21E35'30\""),
        "d" to Pair("Delsbo", "61N48-16E33"),
        "gr" to Pair("Varberg-Grimeton", "57N06'30\"-12E23'36\""),
        "gs" to Pair("Gislövshammar", "55N29'22\"-14E18'52\""),
        "h" to Pair("Härnösand", "62N42'24\"-18E07'47\""),
        "j" to Pair("Julita", "59N08-16E03"),
        "k" to Pair("Kvarnberget-Vallentuna", "59N30-18E08"),
        "s" to Pair("Sala", "59N36'25\"-17E12'44\""),
        "st" to Pair("Stavsnäs", "59N17-18E41"),
        "t" to Pair("Tingstäde", "57N43'40\"-18E35'55\""),
        "v" to Pair("Vaxholm, The Castle", "59N24-18E21")
    ),
    "SDN" to mapOf(
        "a" to Pair("Al-Aitahab", "15N35-32E26"),
        "r" to Pair("Reiba", "13N33-33E31")
    ),
    "SEN" to mapOf(
        "r" to Pair("Rufisque 6WW DIRISI", "14N45'37\"-17W16'26\""),
        "y" to Pair("Dakar Yoff", "14N44-17W29")
    ),
    "SEY" to mapOf(
        "mh" to Pair("Mahe", "04S37-55E26")
    ),
    "SLM" to mapOf(
        "*" to Pair("Honiara", "09S26-160E03")
    ),
    "SNG" to mapOf(
        "*" to Pair("Kranji  except:", "01N25-103E44"),
        "j" to Pair("Jurong", "01N16-103E40"),
        "v" to Pair("Singapore Volmet", "01N20'11\"-103E41'10\"")
    ),
    "SOM" to mapOf(
        "b" to Pair("Baydhabo", "03N07-43E39"),
        "g" to Pair("Garoowe", "08N24-48E29"),
        "h" to Pair("Hargeisa", "09N33-44E03"),
        "ma" to Pair("Mogadishu Airport", "02N01-45E18")
    ),
    "SRB" to mapOf(
        "be" to Pair("Beograd/Belgrade", "44N48-20E28"),
        "s" to Pair("Stubline", "44N34-20E09")
    ),
    "SSD" to mapOf(
        "n" to Pair("Narus", "04N46-33E35")
    ),
    "STP" to mapOf(
        "*" to Pair("Pinheira", "00N18-06E42")
    ),
    "SUI" to mapOf(
        "be" to Pair("Bern Radio HEB, Prangins", "46N24-06E15"),
        "ge" to Pair("Geneva", "46N14-06E08"),
        "lu" to Pair("Luzern (approx; Ampegon?)", "46N50-08E24")
    ),
    "SUR" to mapOf(
        "pm" to Pair("Paramaribo", "05N51-55W09")
    ),
    "SVK" to mapOf(
        "*" to Pair("Rimavska Sobota", "48N24-20E08")
    ),
    "SWZ" to mapOf(
        "*" to Pair("Manzini/Mpangela Ranch", "26S34-31E59")
    ),
    "SYR" to mapOf(
        "*" to Pair("Adra", "33N27-36E30")
    ),
    "TCD" to mapOf(
        "*" to Pair("N'Djamena-Gredia", "12N08-15E03")
    ),
    "THA" to mapOf(
        "b" to Pair("Bangkok / Prathum Thani", "14N03-100E43"),
        "bm" to Pair("Bangkok Meteo", "13N44-100E30"),
        "bv" to Pair("Bangkok Volmet", "13N41'40\"-100E46'14\""),
        "hy" to Pair("Hat Yai", "06N56'11\"-100E23'18\""),
        "n" to Pair("Nakhon Sawan", "15N49-100E04"),
        "u" to Pair("Udon Thani", "17N25-102E48")
    ),
    "TJK" to mapOf(
        "da" to Pair("Dushanbe airport", "38N32-68E49"),
        "y" to Pair("Yangi Yul (Dushanbe)", "38N29-68E48"),
        "o" to Pair("Orzu", "37N32-68E42")
    ),
    "TKM" to mapOf(
        "a" to Pair("Asgabat", "37N51-58E22"),
        "as" to Pair("Ashgabat airport", "37N59-58E22"),
        "ds" to Pair("Dasoguz/Dashoguz", "41N46-59E50"),
        "s" to Pair("Seyda/Seidi", "39N28'16\"-62E43'07\""),
        "tb" to Pair("Turkmenbashi", "40N03-53E00")
    ),
    "TRD" to mapOf(
        "np" to Pair("North Post", "10N45-61W34")
    ),
    "TUN" to mapOf(
        "bz" to Pair("Bizerte", "37N17-09E53"),
        "gu" to Pair("La Goulette", "36N49-10E18"),
        "ke" to Pair("Kelibia", "36N50-11E05"),
        "mh" to Pair("Mahdia", "35N31-11E04"),
        "s" to Pair("Sfax", "34N48-10E53"),
        "sf" to Pair("Sfax", "34N44-10E44"),
        "tb" to Pair("Tabarka", "36N57-08E45"),
        "te" to Pair("Tunis", "36N50-10E11"),
        "tu" to Pair("Tunis", "36N54-10E11"),
        "zz" to Pair("Zarzis", "33N30-11E06")
    ),
    "TUR" to mapOf(
        "a" to Pair("Ankara", "39N55-32E51"),
        "c" to Pair("Cakirlar", "39N58-32E40"),
        "e" to Pair("Emirler", "39N29-32E51"),
        "is" to Pair("Istanbul TAH", "40N59-28E49"),
        "iz" to Pair("Izmir", "38N25-27E09"),
        "m" to Pair("Mersin", "36N48-34E38")
    ),
    "TWN" to mapOf(
        "f" to Pair("Fangliao FAN", "22N23-120E34"),
        "h" to Pair("Huwei (Yunlin province)", "23N43-120E25"),
        "k" to Pair("Kouhu (Yunlin province)", "23N32-120E10"),
        "L" to Pair("Lukang", "24N03-120E25"),
        "m" to Pair("Minhsiung (Chiayi province)", "23N34-120E25"),
        "n" to Pair("Tainan/Annan (Tainan city)", "23N02-120E10"),
        "p" to Pair("Paochung/Baujong (Yunlin province) PAO/BAJ", "23N43-120E18"),
        "pe" to Pair("Penghu (Pescadores), Jiangmei", "23N38-119E36"),
        "q" to Pair("Tainan-Qigu/Cigu, Mount Wufen (Central Weather Bureau)", null),
        "s" to Pair("Danshui/Tanshui/Tamsui (Taipei province)", "25N11-121E25"),
        "t" to Pair("Taipei (Pali)", "25N06-121E26"),
        "w" to Pair("Taipei, Mount Wufen (Central Weather Bureau)", "25N09-121E34"),
        "y" to Pair("Kuanyin (Han Sheng)", "25N02-121E06"),
        "yl" to Pair("Yilin", "24N45-121E44")
    ),
    "TZA" to mapOf(
        "d" to Pair("Daressalam", "06S50-39E14"),
        "z" to Pair("Zanzibar/Dole", "06S05-39E14")
    ),
    "UAE" to mapOf(
        "*" to Pair("Dhabbaya  except:", "24N11-54E14"),
        "aj" to Pair("Al-Abjan", "24N35'51\"-55E23'51\""),
        "da" to Pair("Das Island", "25N09'16\"-52E52'36\""),
        "mu" to Pair("Musaffah, Abu Dhabi", "24N22'58\"-54E30'52\""),
        "sj" to Pair("Sharjah", "25N21-55E23")
    ),
    "UGA" to mapOf(
        "k" to Pair("Kampala-Bugolobi", "00N20-32E36"),
        "m" to Pair("Mukono", "00N21-32E46")
    ),
    "UKR" to mapOf(
        "be" to Pair("Berdiansk", "46N38-36E46"),
        "c" to Pair("Chernivtsi", "48N18-25E50"),
        "k" to Pair("Kyyiv/Kiev/Brovary", "50N31-30E46"),
        "ke" to Pair("Kiev", "50N26-30E32"),
        "L" to Pair("Lviv (Krasne)", "49N51-24E40"),
        "lu" to Pair("Luch", "46N49-32E13"),
        "m" to Pair("Mykolaiv (Kopani)", "46N49-32E14"),
        "od" to Pair("Odessa", "46N29-30E44"),
        "pe" to Pair("Petrivka", "46N54-30E43"),
        "rv" to Pair("Rivne", "50N37-26E15"),
        "x" to Pair("Kharkiv (Taranivka)", "49N38-36E07"),
        "xx" to Pair("Unknown site(s)", null),
        "z" to Pair("Zaporizhzhya", "47N50-35E08")
    ),
    "URG" to mapOf(
        "lp" to Pair("La Paloma", "34S39-54W08"),
        "m" to Pair("Montevideo", "34S50-56W15"),
        "pc" to Pair("Punta Carretas", "34S48-56W21"),
        "pe" to Pair("Punta del Este", "34S58-54W51"),
        "rb" to Pair("Rio Branco", "32S34-53W23"),
        "t" to Pair("Tacuarembó", "31S38-55W58"),
        "tr" to Pair("Trouville", "34S52-56W18")
    ),
    "USA" to mapOf(
        "a" to Pair("Andrews AFB, MD", "38N48'39\"-76W52'01\""),
        "b" to Pair("Birmingham / Vandiver, AL (WEWN)", "33N30'13\"-86W28'27\""),
        "ba" to Pair("WBMD Baltimore, MD", "39N19'26\"-76W32'56\""),
        "bg" to Pair("Barnegat, NJ", "39N45-74W23'30\""),
        "bo" to Pair("Boston, MA", "41N42'30\"-70W33'00\""),
        "bt" to Pair("Bethel, PA (WMLK)", "40N28'46\"-76W16'47\""),
        "c" to Pair("Cypress Creek, SC (WHRI)", "32N41'03\"-81W07'50\""),
        "ch" to Pair("Chesapeake - Pungo Airfield, VA", "36N40'40\"-76W01'40\""),
        "ci" to Pair("WLW Cincinnati, OH", "39N21'11\"-84W19'30\""),
        "cu" to Pair("Cutler, ME", "44N38-67W17"),
        "da" to Pair("Davidsonville, MD", null),
        "de" to Pair("KOA Denver, CO", "39N30'25\"-104W46'04\""),
        "ds" to Pair("Destin, FL", "30N23-86W26"),
        "dv" to Pair("Dover, NC (KNC)", "35N13'01\"-77W26'18\""),
        "dx" to Pair("Dixon, CA", "38N22'46\"-121W45'50\""),
        "ej" to Pair("Ellijay, GA (KJM)", "34N38'08\"-84W27'44\""),
        "fa" to Pair("Fort Collins, CO", "40N40'55\"-105W02'31\""),
        "fb" to Pair("Fort Collins, CO", "40N40'42\"-105W02'25\""),
        "fc" to Pair("Fort Collins, CO", "40N40'48\"-105W02'25\""),
        "fd" to Pair("Fort Collins, CO", "40N40'45\"-105W02'25\""),
        "fe" to Pair("Fort Collins, CO", "40N40'53\"-105W02'29\""),
        "ff" to Pair("Fort Collins, CO", "40N40'41\"-105W02'49\""),
        "fg" to Pair("Fort Collins, CO", "40N40'51\"-105W02'33\""),
        "fv" to Pair("Forest, VA", "37N23'42\"-79W12'35\""),
        "g" to Pair("Greenville, NC", "35N28-77W12"),
        "hw" to Pair("KWHW Altus, OK", "34N37'35\"-99W20'10\""),
        "jc" to Pair("Jim Creek, WA", "48N12-121W55"),
        "k" to Pair("Key Saddlebunch, FL", "24N39-81W36"),
        "kc" to Pair("KSCS Fort Worth, TX", "32N35-96W58"),
        "ks" to Pair("KSL Salt Lake City, UT", "40N46'45\"-112W06'00\""),
        "L" to Pair("Lebanon, TN (WTWW)", "36N16'35\"-86W05'58\""),
        "LL" to Pair("Lakeland, FL (WCY)", "27N58'53\"-082W03'05\""),
        "lm" to Pair("Lamoure, ND", "46N22-98W20"),
        "m" to Pair("Miami / Hialeah Gardens, FL (WRMI)", "25N54'00\"-80W21'49\""),
        "ma" to Pair("Manchester / Morrison, TN (WWRB) 35N37'27'-86W00'52\"", null),
        "mi" to Pair("Milton, FL (WJHR)", "30N39'03\"-87W05'27\""),
        "mo" to Pair("Mobile, AL (WLO)", "30N35'42\"-88W13'17\""),
        "n" to Pair("Nashville, TN (WWCR)", "36N12'30\"-86W53'38\""),
        "nm" to Pair("NMG New Orleans, LA", "29N53'04\"-89W56'43\""),
        "no" to Pair("New Orleans, LA (WRNO)", "29N50'10\"-90W06'57\""),
        "np" to Pair("Newport, NC (WTJC)", "34N46'41\"-76W52'37\""),
        "o" to Pair("Okeechobee, FL (WYFR)", "27N27'30\"-80W56'00\""),
        "ob" to Pair("San Luis Obispo, CA, 3 tx sites: San Luis Obispo , Ragged Point 35N47-121W20, Vandenberg 34N35-120W39", "35N13-120W52"),
        "of" to Pair("Offutt AFB, NE", "41N06'49\"-95W55'42\""),
        "q" to Pair("Monticello, ME (WBCQ)", "46N20'30\"-67W49'40\""),
        "pa" to Pair("Palo Alto, CA (KFS)", "37N26'44\"-122W06'48\""),
        "pg" to Pair("Punta Gorda, FL (KPK)", "26N53'39\"-82W03'35\""),
        "pr" to Pair("Point Reyes / Bolinas, CA", "37N55'32\"-122W43'52\""),
        "rh" to Pair("Riverhead, Long Island, NY", "40N53-72W38"),
        "rl" to Pair("Red Lion (York), PA (WINB)", "39N54'22\"-76W34'56\""),
        "rs" to Pair("Los Angeles / Rancho Simi, CA (KVOH)", "34N15'23\"-118W38'29\""),
        "rv" to Pair("Rio Vista, CA (KFS/KPH)", "38N11'54\"-121W48'34\""),
        "sc" to Pair("KEBR Sacramento, CA", "38N27'46\"-121W07'49\""),
        "se" to Pair("Seattle, WA", "48N07'32\"-122W15'02\""),
        "sf" to Pair("San Francisco Radio; one of: dx; HWA-m; ALS-ba; THA-hy", null),
        "sk" to Pair("Seattle, WA (KLB)  and CA site 38N12'54\"-121W48'40\"", "48N11'40\"-122W14'06\""),
        "sm" to Pair("University of Southern Mississippi, 3 tx sites: Destin FL , Gulf Shores AL 30N15-87W40, Pascagoula MS 30N20-84W34", "30N23-86W26"),
        "uc" to Pair("University of California Davis, 6 tx sites: Bodega Bay , Jenner 38N34-123W20, Inverness 38N02'49\"-122W59'20\", Muir Beach 37N52-122W36, Sausalito 37N49-122W32, Inverness 38N01'41\"-122W57'40\"", "38N19-123W04"),
        "ud" to Pair("University of California Davis, 5 tx sites: Bodega Bay , Fort Bragg 39N26-123W49, Point Arena 39N56-123W44, Trinidad 41N04-124W09, Samoa 40N46-124W13", "38N19-123W04"),
        "v" to Pair("Vado, NM (KJES)", "32N08'02\"-106W35'24\""),
        "vs" to Pair("Vashon Island, WA", "47N22'15\"-122W29'16\""),
        "wa" to Pair("Washington, DC", "38N55-77W03"),
        "wb" to Pair("WBAP Fort Worth, TX", "32N36'38\"-97W10'00\""),
        "wc" to Pair("\"West Coast\" Beale AFB Marysville, CA", "39N08-121W26"),
        "wg" to Pair("WGM Fort Lauderdale, FL", "26N34-80W05"),
        "ws" to Pair("KOVR West Sacramento, CA", "38N35-121W33"),
        "wx" to Pair("WHX Hillsboro, WV", "38N16'07\"-80W16'07\""),
        "xx" to Pair("Unknown site", null)
    ),
    "UZB" to mapOf(
        "*" to Pair("Tashkent  except:", "41N13-69E09"),
        "a" to Pair("Tashkent Airport", "41N16-69E17"),
        "nu" to Pair("Nukus, Karakalpakstan", "42N29-59E37"),
        "s" to Pair("Samarkand", "39N42-66E59"),
        "ta" to Pair("Tashkent I/II", "41N19-69E15")
    ),
    "VEN" to mapOf(
        "t" to Pair("El Tigre", "08N53-64W16"),
        "y" to Pair("YVTO Caracas", "10N30'13\"-66W55'44\"")
    ),
    "VTN" to mapOf(
        "b" to Pair("Buon Me Thuot, Daclac", "12N38-108E01"),
        "bt" to Pair("Ben Thuy", "18N49-105E43"),
        "cm" to Pair("Ca Mau", "09N11'23\"-105E08'01\""),
        "co" to Pair("Cua Ong", "21N01'34\"-107E22'01\""),
        "cr" to Pair("Cam Ranh", "12N04'47\"-109E10'55\""),
        "ct" to Pair("Can Tho", "10N04'18\"-105E45'32\""),
        "db" to Pair("Dien Bien", "21N22-103E00"),
        "dn" to Pair("Da Nang", "16N03'17\"-108E09'28\""),
        "hc" to Pair("Ho Chi Minh City / Vung Tau", "10N23'41\"-107E08'42\""),
        "hg" to Pair("Hon Gai (Ha Long)", "20N57-107E04"),
        "hp" to Pair("Hai Phong", "20N51'01\"-106E44'01\""),
        "hu" to Pair("Hue", "16N33'02\"-107E38'28\""),
        "kg" to Pair("Kien Giang", "09N59'29\"-105E06'09\""),
        "L" to Pair("Son La", "21N20-103E55"),
        "m" to Pair("Hanoi-Metri", "21N00-105E47"),
        "mc" to Pair("Mong Cai", "21N31'33\"-107E57'59\""),
        "mh" to Pair("My Hao", "20N55-106E05"),
        "nt" to Pair("Nha Trang", "12N13'19\"-109E10'50\""),
        "pd" to Pair("Phuoc Dinh", "11N23-108E59"),
        "pr" to Pair("Phan Rang", "11N34-109E01"),
        "pt" to Pair("Phan Tiet", "10N55'04\"-108E06'22\""),
        "py" to Pair("Phu Yen", "13N06'22\"-109E18'41\""),
        "qn" to Pair("Quy Nhon", "13N46'40\"-109E14'21\""),
        "s" to Pair("Hanoi-Sontay", "21N12-105E22"),
        "t" to Pair("Thoi Long / Thoi Hung", "10N07-105E34"),
        "th" to Pair("Thanh Hoa", "19N20'58\"-105E47'36\""),
        "vt" to Pair("Vung Tau", "10N23'41\"-107E08'42\""),
        "x" to Pair("Xuan Mai", "20N43-105E33"),
        "xx" to Pair("Unknown site", null)
    ),
    "VUT" to mapOf(
        "*" to Pair("Empten Lagoon", "17S45-168E22")
    ),
    "XUU" to mapOf(
        "*" to Pair("Unknown site", null)
    ),
    "YEM" to mapOf(
        "a" to Pair("Al Hiswah/Aden", "12N50-45E02"),
        "s" to Pair("Sanaa", "15N22-44E11")
    ),
    "ZMB" to mapOf(
        "L" to Pair("Lusaka", "15S30-28E15"),
        "m" to Pair("Makeni Ranch", "15S32-28E00")
    ),
    "ZWE" to mapOf(
        "*" to Pair("Gweru/Guinea Fowl", "19S26-29E51")
    ),
)

