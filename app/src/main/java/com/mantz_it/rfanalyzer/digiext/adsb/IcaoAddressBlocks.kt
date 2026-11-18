package com.mantz_it.rfanalyzer.digiext.adsb

import com.mantz_it.rfanalyzer.database.COUNTRY_CODE_TO_NAME
import com.mantz_it.rfanalyzer.ui.composable.countryCodeToFlagEmoji

/**
 * <h1>RF Analyzer - ICAO Address Blocks</h1>
 *
 * Module:      IcaoAddressBlocks.kt
 * Description: The state an aircraft's 24-bit address was allocated to - its state of registry.
 *              ICAO Annex 10, Volume III, Part I, Chapter 9, Table 9-1 "Allocation of aircraft
 *              addresses to States", as replaced by Amendment 92 (applicable 28 November 2024;
 *              Amendment 94 leaves it unchanged): one block per member state plus three of ICAO's
 *              own. Every block is a binary prefix; the 57 % of the address space outside them is
 *              unallocated and has no country.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** ICAO's own blocks: temporary addresses, and special use in the interest of flight safety. */
internal enum class IcaoUse(val label: String) { TEMPORARY("ICAO temporary address"), SPECIAL_USE("ICAO special-use address") }

/** One block of addresses, inclusive; [isoCode] null for one of ICAO's own blocks ([use]). */
internal class IcaoBlock(val start: Int, val end: Int, val isoCode: String?, val use: IcaoUse? = null) {
    /** "Germany", or what ICAO keeps the block for. */
    val name: String get() = isoCode?.let { COUNTRY_CODE_TO_NAME[it] ?: it } ?: use!!.label

    /** The state's flag; null for ICAO's own blocks. */
    val flag: String? get() = isoCode?.let(::countryCodeToFlagEmoji)?.takeIf { it.isNotEmpty() }

    /** "🇩🇪 Germany". */
    val text: String get() = listOfNotNull(flag, name).joinToString(" ")
}

/** The block [address] belongs to, null if it is unallocated (or not a 24-bit address). */
internal fun icaoBlockOf(address: Int): IcaoBlock? {
    var lo = 0
    var hi = ICAO_BLOCKS.size - 1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        val block = ICAO_BLOCKS[mid]
        when {
            address < block.start -> hi = mid - 1
            address > block.end -> lo = mid + 1
            else -> return block
        }
    }
    return null
}

/** Sorted by start, not overlapping. */
internal val ICAO_BLOCKS: List<IcaoBlock> = listOf(
    IcaoBlock(0x004000, 0x0047FF, "ZW"), // Zimbabwe
    IcaoBlock(0x006000, 0x006FFF, "MZ"), // Mozambique
    IcaoBlock(0x008000, 0x00FFFF, "ZA"), // South Africa
    IcaoBlock(0x010000, 0x017FFF, "EG"), // Egypt
    IcaoBlock(0x018000, 0x01FFFF, "LY"), // Libya
    IcaoBlock(0x020000, 0x027FFF, "MA"), // Morocco
    IcaoBlock(0x028000, 0x02FFFF, "TN"), // Tunisia
    IcaoBlock(0x030000, 0x0307FF, "BW"), // Botswana
    IcaoBlock(0x032000, 0x032FFF, "BI"), // Burundi
    IcaoBlock(0x034000, 0x034FFF, "CM"), // Cameroon
    IcaoBlock(0x035000, 0x0357FF, "KM"), // Comoros
    IcaoBlock(0x036000, 0x036FFF, "CG"), // Congo
    IcaoBlock(0x038000, 0x038FFF, "CI"), // Côte d'Ivoire
    IcaoBlock(0x03E000, 0x03EFFF, "GA"), // Gabon
    IcaoBlock(0x040000, 0x040FFF, "ET"), // Ethiopia
    IcaoBlock(0x042000, 0x042FFF, "GQ"), // Equatorial Guinea
    IcaoBlock(0x044000, 0x044FFF, "GH"), // Ghana
    IcaoBlock(0x046000, 0x046FFF, "GN"), // Guinea
    IcaoBlock(0x048000, 0x0487FF, "GW"), // Guinea-Bissau
    IcaoBlock(0x04A000, 0x04A7FF, "LS"), // Lesotho
    IcaoBlock(0x04C000, 0x04CFFF, "KE"), // Kenya
    IcaoBlock(0x050000, 0x050FFF, "LR"), // Liberia
    IcaoBlock(0x054000, 0x054FFF, "MG"), // Madagascar
    IcaoBlock(0x058000, 0x058FFF, "MW"), // Malawi
    IcaoBlock(0x05A000, 0x05A7FF, "MV"), // Maldives
    IcaoBlock(0x05C000, 0x05CFFF, "ML"), // Mali
    IcaoBlock(0x05E000, 0x05E7FF, "MR"), // Mauritania
    IcaoBlock(0x060000, 0x0607FF, "MU"), // Mauritius
    IcaoBlock(0x062000, 0x062FFF, "NE"), // Niger
    IcaoBlock(0x064000, 0x064FFF, "NG"), // Nigeria
    IcaoBlock(0x068000, 0x068FFF, "UG"), // Uganda
    IcaoBlock(0x06A000, 0x06AFFF, "QA"), // Qatar
    IcaoBlock(0x06C000, 0x06CFFF, "CF"), // Central African Republic
    IcaoBlock(0x06E000, 0x06EFFF, "RW"), // Rwanda
    IcaoBlock(0x070000, 0x070FFF, "SN"), // Senegal
    IcaoBlock(0x074000, 0x0747FF, "SC"), // Seychelles
    IcaoBlock(0x076000, 0x0767FF, "SL"), // Sierra Leone
    IcaoBlock(0x078000, 0x078FFF, "SO"), // Somalia
    IcaoBlock(0x07A000, 0x07A7FF, "SZ"), // Eswatini
    IcaoBlock(0x07C000, 0x07CFFF, "SD"), // Sudan
    IcaoBlock(0x080000, 0x080FFF, "TZ"), // United Republic of Tanzania
    IcaoBlock(0x084000, 0x084FFF, "TD"), // Chad
    IcaoBlock(0x088000, 0x088FFF, "TG"), // Togo
    IcaoBlock(0x08A000, 0x08AFFF, "ZM"), // Zambia
    IcaoBlock(0x08C000, 0x08CFFF, "CD"), // Democratic Republic of the Congo
    IcaoBlock(0x090000, 0x090FFF, "AO"), // Angola
    IcaoBlock(0x094000, 0x0947FF, "BJ"), // Benin
    IcaoBlock(0x096000, 0x0967FF, "CV"), // Cabo Verde
    IcaoBlock(0x098000, 0x0987FF, "DJ"), // Djibouti
    IcaoBlock(0x09A000, 0x09AFFF, "GM"), // Gambia
    IcaoBlock(0x09C000, 0x09CFFF, "BF"), // Burkina Faso
    IcaoBlock(0x09E000, 0x09E7FF, "ST"), // Sao Tome and Principe
    IcaoBlock(0x0A0000, 0x0A7FFF, "DZ"), // Algeria
    IcaoBlock(0x0A8000, 0x0A8FFF, "BS"), // Bahamas
    IcaoBlock(0x0AA000, 0x0AA7FF, "BB"), // Barbados
    IcaoBlock(0x0AB000, 0x0AB7FF, "BZ"), // Belize
    IcaoBlock(0x0AC000, 0x0ADFFF, "CO"), // Colombia
    IcaoBlock(0x0AE000, 0x0AEFFF, "CR"), // Costa Rica
    IcaoBlock(0x0B0000, 0x0B0FFF, "CU"), // Cuba
    IcaoBlock(0x0B2000, 0x0B2FFF, "SV"), // El Salvador
    IcaoBlock(0x0B4000, 0x0B4FFF, "GT"), // Guatemala
    IcaoBlock(0x0B6000, 0x0B6FFF, "GY"), // Guyana
    IcaoBlock(0x0B8000, 0x0B8FFF, "HT"), // Haiti
    IcaoBlock(0x0BA000, 0x0BAFFF, "HN"), // Honduras
    IcaoBlock(0x0BC000, 0x0BC7FF, "VC"), // Saint Vincent and the Grenadines
    IcaoBlock(0x0BE000, 0x0BEFFF, "JM"), // Jamaica
    IcaoBlock(0x0C0000, 0x0C0FFF, "NI"), // Nicaragua
    IcaoBlock(0x0C2000, 0x0C2FFF, "PA"), // Panama
    IcaoBlock(0x0C4000, 0x0C4FFF, "DO"), // Dominican Republic
    IcaoBlock(0x0C6000, 0x0C6FFF, "TT"), // Trinidad and Tobago
    IcaoBlock(0x0C8000, 0x0C8FFF, "SR"), // Suriname
    IcaoBlock(0x0CA000, 0x0CA7FF, "AG"), // Antigua and Barbuda
    IcaoBlock(0x0CC000, 0x0CC7FF, "GD"), // Grenada
    IcaoBlock(0x0D0000, 0x0D7FFF, "MX"), // Mexico
    IcaoBlock(0x0D8000, 0x0DFFFF, "VE"), // Venezuela (Bolivarian Republic of)
    IcaoBlock(0x100000, 0x1FFFFF, "RU"), // Russian Federation
    IcaoBlock(0x201000, 0x2017FF, "NA"), // Namibia
    IcaoBlock(0x202000, 0x2027FF, "ER"), // Eritrea
    IcaoBlock(0x300000, 0x33FFFF, "IT"), // Italy
    IcaoBlock(0x340000, 0x37FFFF, "ES"), // Spain
    IcaoBlock(0x380000, 0x3BFFFF, "FR"), // France
    IcaoBlock(0x3C0000, 0x3FFFFF, "DE"), // Germany
    IcaoBlock(0x400000, 0x43FFFF, "GB"), // United Kingdom
    IcaoBlock(0x440000, 0x447FFF, "AT"), // Austria
    IcaoBlock(0x448000, 0x44FFFF, "BE"), // Belgium
    IcaoBlock(0x450000, 0x457FFF, "BG"), // Bulgaria
    IcaoBlock(0x458000, 0x45FFFF, "DK"), // Denmark
    IcaoBlock(0x460000, 0x467FFF, "FI"), // Finland
    IcaoBlock(0x468000, 0x46FFFF, "GR"), // Greece
    IcaoBlock(0x470000, 0x477FFF, "HU"), // Hungary
    IcaoBlock(0x478000, 0x47FFFF, "NO"), // Norway
    IcaoBlock(0x480000, 0x487FFF, "NL"), // Netherlands
    IcaoBlock(0x488000, 0x48FFFF, "PL"), // Poland
    IcaoBlock(0x490000, 0x497FFF, "PT"), // Portugal
    IcaoBlock(0x498000, 0x49FFFF, "CZ"), // Czechia
    IcaoBlock(0x4A0000, 0x4A7FFF, "RO"), // Romania
    IcaoBlock(0x4A8000, 0x4AFFFF, "SE"), // Sweden
    IcaoBlock(0x4B0000, 0x4B7FFF, "CH"), // Switzerland
    IcaoBlock(0x4B8000, 0x4BFFFF, "TR"), // Türkiye
    IcaoBlock(0x4C0000, 0x4C7FFF, "RS"), // Serbia
    IcaoBlock(0x4C8000, 0x4C87FF, "CY"), // Cyprus
    IcaoBlock(0x4CA000, 0x4CAFFF, "IE"), // Ireland
    IcaoBlock(0x4CC000, 0x4CCFFF, "IS"), // Iceland
    IcaoBlock(0x4D0000, 0x4D07FF, "LU"), // Luxembourg
    IcaoBlock(0x4D2000, 0x4D27FF, "MT"), // Malta
    IcaoBlock(0x4D4000, 0x4D47FF, "MC"), // Monaco
    IcaoBlock(0x500000, 0x5007FF, "SM"), // San Marino
    IcaoBlock(0x501000, 0x5017FF, "AL"), // Albania
    IcaoBlock(0x501800, 0x501FFF, "HR"), // Croatia
    IcaoBlock(0x502800, 0x502FFF, "LV"), // Latvia
    IcaoBlock(0x503800, 0x503FFF, "LT"), // Lithuania
    IcaoBlock(0x504800, 0x504FFF, "MD"), // Republic of Moldova
    IcaoBlock(0x505800, 0x505FFF, "SK"), // Slovakia
    IcaoBlock(0x506800, 0x506FFF, "SI"), // Slovenia
    IcaoBlock(0x507800, 0x507FFF, "UZ"), // Uzbekistan
    IcaoBlock(0x508000, 0x50FFFF, "UA"), // Ukraine
    IcaoBlock(0x510000, 0x5107FF, "BY"), // Belarus
    IcaoBlock(0x511000, 0x5117FF, "EE"), // Estonia
    IcaoBlock(0x512000, 0x5127FF, "MK"), // North Macedonia
    IcaoBlock(0x513000, 0x5137FF, "BA"), // Bosnia and Herzegovina
    IcaoBlock(0x514000, 0x5147FF, "GE"), // Georgia
    IcaoBlock(0x515000, 0x5157FF, "TJ"), // Tajikistan
    IcaoBlock(0x516000, 0x5167FF, "ME"), // Montenegro
    IcaoBlock(0x600000, 0x6007FF, "AM"), // Armenia
    IcaoBlock(0x600800, 0x600FFF, "AZ"), // Azerbaijan
    IcaoBlock(0x601000, 0x6017FF, "KG"), // Kyrgyzstan
    IcaoBlock(0x601800, 0x601FFF, "TM"), // Turkmenistan
    IcaoBlock(0x680000, 0x6807FF, "BT"), // Bhutan
    IcaoBlock(0x681000, 0x6817FF, "FM"), // Micronesia (Federated States of)
    IcaoBlock(0x682000, 0x6827FF, "MN"), // Mongolia
    IcaoBlock(0x683000, 0x6837FF, "KZ"), // Kazakhstan
    IcaoBlock(0x684000, 0x6847FF, "PW"), // Palau
    IcaoBlock(0x700000, 0x700FFF, "AF"), // Afghanistan
    IcaoBlock(0x702000, 0x702FFF, "BD"), // Bangladesh
    IcaoBlock(0x704000, 0x704FFF, "MM"), // Myanmar
    IcaoBlock(0x706000, 0x706FFF, "KW"), // Kuwait
    IcaoBlock(0x708000, 0x708FFF, "LA"), // Lao People's Democratic Republic
    IcaoBlock(0x70A000, 0x70AFFF, "NP"), // Nepal
    IcaoBlock(0x70C000, 0x70C7FF, "OM"), // Oman
    IcaoBlock(0x70E000, 0x70EFFF, "KH"), // Cambodia
    IcaoBlock(0x710000, 0x717FFF, "SA"), // Saudi Arabia
    IcaoBlock(0x718000, 0x71FFFF, "KR"), // Republic of Korea
    IcaoBlock(0x720000, 0x727FFF, "KP"), // Democratic People's Republic of Korea
    IcaoBlock(0x728000, 0x72FFFF, "IQ"), // Iraq
    IcaoBlock(0x730000, 0x737FFF, "IR"), // Iran (Islamic Republic of)
    IcaoBlock(0x738000, 0x73FFFF, "IL"), // Israel
    IcaoBlock(0x740000, 0x747FFF, "JO"), // Jordan
    IcaoBlock(0x748000, 0x74FFFF, "LB"), // Lebanon
    IcaoBlock(0x750000, 0x757FFF, "MY"), // Malaysia
    IcaoBlock(0x758000, 0x75FFFF, "PH"), // Philippines
    IcaoBlock(0x760000, 0x767FFF, "PK"), // Pakistan
    IcaoBlock(0x768000, 0x76FFFF, "SG"), // Singapore
    IcaoBlock(0x770000, 0x777FFF, "LK"), // Sri Lanka
    IcaoBlock(0x778000, 0x77FFFF, "SY"), // Syrian Arab Republic
    IcaoBlock(0x780000, 0x7BFFFF, "CN"), // China
    IcaoBlock(0x7C0000, 0x7FFFFF, "AU"), // Australia
    IcaoBlock(0x800000, 0x83FFFF, "IN"), // India
    IcaoBlock(0x840000, 0x87FFFF, "JP"), // Japan
    IcaoBlock(0x880000, 0x887FFF, "TH"), // Thailand
    IcaoBlock(0x888000, 0x88FFFF, "VN"), // Viet Nam
    IcaoBlock(0x890000, 0x890FFF, "YE"), // Yemen
    IcaoBlock(0x894000, 0x894FFF, "BH"), // Bahrain
    IcaoBlock(0x895000, 0x8957FF, "BN"), // Brunei Darussalam
    IcaoBlock(0x896000, 0x896FFF, "AE"), // United Arab Emirates
    IcaoBlock(0x897000, 0x8977FF, "SB"), // Solomon Islands
    IcaoBlock(0x898000, 0x898FFF, "PG"), // Papua New Guinea
    IcaoBlock(0x899000, 0x8997FF, null, IcaoUse.SPECIAL_USE), // ICAO2 (special use in the interest of flight safety)
    IcaoBlock(0x8A0000, 0x8A7FFF, "ID"), // Indonesia
    IcaoBlock(0x900000, 0x9007FF, "MH"), // Marshall Islands
    IcaoBlock(0x901000, 0x9017FF, "CK"), // Cook Islands
    IcaoBlock(0x902000, 0x9027FF, "WS"), // Samoa
    IcaoBlock(0xA00000, 0xAFFFFF, "US"), // United States
    IcaoBlock(0xC00000, 0xC3FFFF, "CA"), // Canada
    IcaoBlock(0xC80000, 0xC87FFF, "NZ"), // New Zealand
    IcaoBlock(0xC88000, 0xC88FFF, "FJ"), // Fiji
    IcaoBlock(0xC8A000, 0xC8A7FF, "NR"), // Nauru
    IcaoBlock(0xC8C000, 0xC8C7FF, "LC"), // Saint Lucia
    IcaoBlock(0xC8D000, 0xC8D7FF, "TO"), // Tonga
    IcaoBlock(0xC8E000, 0xC8E7FF, "KI"), // Kiribati
    IcaoBlock(0xC90000, 0xC907FF, "VU"), // Vanuatu
    IcaoBlock(0xC91000, 0xC917FF, "AD"), // Andorra
    IcaoBlock(0xC92000, 0xC927FF, "DM"), // Dominica
    IcaoBlock(0xC93000, 0xC937FF, "KN"), // Saint Kitts and Nevis
    IcaoBlock(0xC94000, 0xC947FF, "SS"), // South Sudan
    IcaoBlock(0xC95000, 0xC957FF, "TL"), // Timor-Leste
    IcaoBlock(0xC97000, 0xC977FF, "TV"), // Tuvalu
    IcaoBlock(0xE00000, 0xE3FFFF, "AR"), // Argentina
    IcaoBlock(0xE40000, 0xE7FFFF, "BR"), // Brazil
    IcaoBlock(0xE80000, 0xE80FFF, "CL"), // Chile
    IcaoBlock(0xE84000, 0xE84FFF, "EC"), // Ecuador
    IcaoBlock(0xE88000, 0xE88FFF, "PY"), // Paraguay
    IcaoBlock(0xE8C000, 0xE8CFFF, "PE"), // Peru
    IcaoBlock(0xE90000, 0xE90FFF, "UY"), // Uruguay
    IcaoBlock(0xE94000, 0xE94FFF, "BO"), // Bolivia (Plurinational State of)
    IcaoBlock(0xF00000, 0xF07FFF, null, IcaoUse.TEMPORARY), // ICAO1 (temporary addresses)
    IcaoBlock(0xF09000, 0xF097FF, null, IcaoUse.SPECIAL_USE), // ICAO2 (special use in the interest of flight safety)
)
