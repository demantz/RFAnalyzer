package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.R

/**
 * <h1>RF Analyzer - AprsSymbolIcons</h1>
 *
 * Module:      AprsSymbolIcons.kt
 * Description: Maps an APRS symbol-table character + symbol character pair to a vendored drawable
 *              resource, for rendering the correct icon for an APRS station/object on the map.
 *
 *              Icon set: the aprs.fi machine-readable APRS symbol index and graphics
 *              (https://aprs-tools.temporarilyoffline.com/get-symbol.html), covering the full
 *              primary ('/') and alternate ('\') symbol tables (92 symbols each). The index is
 *              CC BY-SA 4.0; the graphics are published by that site for APRS application use -
 *              see the attribution in res/values/strings.xml's about_screen_html string.
 *
 *              Every primary/alternate symbol is covered, so [resourceFor] only returns `null`
 *              for malformed/unexpected data (e.g. an unrecognized symbol-table character);
 *              callers should still fall back to the generic antenna icon in that case (see
 *              MaplibreView.kt).
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
object AprsSymbolIcons {
    private class Sym(val resId: Int, val label: String? = null)

    // Primary table ('/') symbol character -> drawable resource id and name.
    private val primaryTable: Map<Char, Sym> = mapOf(
        '!' to Sym(R.drawable.aprs_primary_bb, "Police station"),
        '"' to Sym(R.drawable.aprs_primary_bc),
        '#' to Sym(R.drawable.aprs_primary_bd, "Digipeater"),
        '$' to Sym(R.drawable.aprs_primary_be, "Telephone"),
        '%' to Sym(R.drawable.aprs_primary_bf, "DX cluster"),
        '&' to Sym(R.drawable.aprs_primary_bg, "HF gateway"),
        '\'' to Sym(R.drawable.aprs_primary_bh, "Small aircraft"),
        '(' to Sym(R.drawable.aprs_primary_bi, "Mobile satellite station"),
        ')' to Sym(R.drawable.aprs_primary_bj, "Wheelchair, handicapped"),
        '*' to Sym(R.drawable.aprs_primary_bk, "Snowmobile"),
        '+' to Sym(R.drawable.aprs_primary_bl, "Red Cross"),
        ',' to Sym(R.drawable.aprs_primary_bm, "Boy Scouts"),
        '-' to Sym(R.drawable.aprs_primary_bn, "House"),
        '.' to Sym(R.drawable.aprs_primary_bo, "Red X"),
        '/' to Sym(R.drawable.aprs_primary_bp, "Red dot"),
        '0' to Sym(R.drawable.aprs_primary_p0, "Numbered circle: 0"),
        '1' to Sym(R.drawable.aprs_primary_p1, "Numbered circle: 1"),
        '2' to Sym(R.drawable.aprs_primary_p2, "Numbered circle: 2"),
        '3' to Sym(R.drawable.aprs_primary_p3, "Numbered circle: 3"),
        '4' to Sym(R.drawable.aprs_primary_p4, "Numbered circle: 4"),
        '5' to Sym(R.drawable.aprs_primary_p5, "Numbered circle: 5"),
        '6' to Sym(R.drawable.aprs_primary_p6, "Numbered circle: 6"),
        '7' to Sym(R.drawable.aprs_primary_p7, "Numbered circle: 7"),
        '8' to Sym(R.drawable.aprs_primary_p8, "Numbered circle: 8"),
        '9' to Sym(R.drawable.aprs_primary_p9, "Numbered circle: 9"),
        ':' to Sym(R.drawable.aprs_primary_mr, "Fire"),
        ';' to Sym(R.drawable.aprs_primary_ms, "Campground, tent"),
        '<' to Sym(R.drawable.aprs_primary_mt, "Motorcycle"),
        '=' to Sym(R.drawable.aprs_primary_mu, "Railroad engine"),
        '>' to Sym(R.drawable.aprs_primary_mv, "Car"),
        '?' to Sym(R.drawable.aprs_primary_mw, "File server"),
        '@' to Sym(R.drawable.aprs_primary_mx, "Hurricane predicted path"),
        'A' to Sym(R.drawable.aprs_primary_pa, "Aid station"),
        'B' to Sym(R.drawable.aprs_primary_pb, "BBS"),
        'C' to Sym(R.drawable.aprs_primary_pc, "Canoe"),
        'D' to Sym(R.drawable.aprs_primary_pd),
        'E' to Sym(R.drawable.aprs_primary_pe, "Eyeball"),
        'F' to Sym(R.drawable.aprs_primary_pf, "Farm vehicle, tractor"),
        'G' to Sym(R.drawable.aprs_primary_pg, "Grid square, 3 by 3"),
        'H' to Sym(R.drawable.aprs_primary_ph, "Hotel"),
        'I' to Sym(R.drawable.aprs_primary_pi, "TCP/IP network station"),
        'J' to Sym(R.drawable.aprs_primary_pj),
        'K' to Sym(R.drawable.aprs_primary_pk, "School"),
        'L' to Sym(R.drawable.aprs_primary_pl, "PC user"),
        'M' to Sym(R.drawable.aprs_primary_pm, "Mac apple"),
        'N' to Sym(R.drawable.aprs_primary_pn, "NTS station"),
        'O' to Sym(R.drawable.aprs_primary_po, "Balloon"),
        'P' to Sym(R.drawable.aprs_primary_pp, "Police car"),
        'Q' to Sym(R.drawable.aprs_primary_pq),
        'R' to Sym(R.drawable.aprs_primary_pr, "Recreational vehicle"),
        'S' to Sym(R.drawable.aprs_primary_ps, "Space Shuttle"),
        'T' to Sym(R.drawable.aprs_primary_pt, "SSTV"),
        'U' to Sym(R.drawable.aprs_primary_pu, "Bus"),
        'V' to Sym(R.drawable.aprs_primary_pv, "ATV, Amateur Television"),
        'W' to Sym(R.drawable.aprs_primary_pw, "Weather service site"),
        'X' to Sym(R.drawable.aprs_primary_px, "Helicopter"),
        'Y' to Sym(R.drawable.aprs_primary_py, "Sailboat"),
        'Z' to Sym(R.drawable.aprs_primary_pz, "Windows flag"),
        '[' to Sym(R.drawable.aprs_primary_hs, "Human"),
        '\\' to Sym(R.drawable.aprs_primary_ht, "DF triangle"),
        ']' to Sym(R.drawable.aprs_primary_hu, "Mailbox, post office"),
        '^' to Sym(R.drawable.aprs_primary_hv, "Large aircraft"),
        '_' to Sym(R.drawable.aprs_primary_hw, "Weather station"),
        '`' to Sym(R.drawable.aprs_primary_hx, "Satellite dish antenna"),
        'a' to Sym(R.drawable.aprs_primary_la, "Ambulance"),
        'b' to Sym(R.drawable.aprs_primary_lb, "Bicycle"),
        'c' to Sym(R.drawable.aprs_primary_lc, "Incident command post"),
        'd' to Sym(R.drawable.aprs_primary_ld, "Fire station"),
        'e' to Sym(R.drawable.aprs_primary_le, "Horse, equestrian"),
        'f' to Sym(R.drawable.aprs_primary_lf, "Fire truck"),
        'g' to Sym(R.drawable.aprs_primary_lg, "Glider"),
        'h' to Sym(R.drawable.aprs_primary_lh, "Hospital"),
        'i' to Sym(R.drawable.aprs_primary_li, "IOTA, islands on the air"),
        'j' to Sym(R.drawable.aprs_primary_lj, "Jeep"),
        'k' to Sym(R.drawable.aprs_primary_lk, "Truck"),
        'l' to Sym(R.drawable.aprs_primary_ll, "Laptop"),
        'm' to Sym(R.drawable.aprs_primary_lm, "Mic-E repeater"),
        'n' to Sym(R.drawable.aprs_primary_ln, "Node, black bulls-eye"),
        'o' to Sym(R.drawable.aprs_primary_lo, "Emergency operations center"),
        'p' to Sym(R.drawable.aprs_primary_lp, "Dog"),
        'q' to Sym(R.drawable.aprs_primary_lq, "Grid square, 2 by 2"),
        'r' to Sym(R.drawable.aprs_primary_lr, "Repeater tower"),
        's' to Sym(R.drawable.aprs_primary_ls, "Ship, power boat"),
        't' to Sym(R.drawable.aprs_primary_lt, "Truck stop"),
        'u' to Sym(R.drawable.aprs_primary_lu, "Semi-trailer truck, 18-wheeler"),
        'v' to Sym(R.drawable.aprs_primary_lv, "Van"),
        'w' to Sym(R.drawable.aprs_primary_lw, "Water station"),
        'x' to Sym(R.drawable.aprs_primary_lx, "X / Unix"),
        'y' to Sym(R.drawable.aprs_primary_ly, "House, yagi antenna"),
        'z' to Sym(R.drawable.aprs_primary_lz, "Shelter"),
        '{' to Sym(R.drawable.aprs_primary_j1),
        '}' to Sym(R.drawable.aprs_primary_j3),
    )

    // Alternate table ('\') symbol character -> drawable resource id and name.
    private val alternateTable: Map<Char, Sym> = mapOf(
        '!' to Sym(R.drawable.aprs_alt_ob, "Emergency"),
        '"' to Sym(R.drawable.aprs_alt_oc),
        '#' to Sym(R.drawable.aprs_alt_od, "Digipeater, green star"),
        '$' to Sym(R.drawable.aprs_alt_oe, "Bank or ATM"),
        '%' to Sym(R.drawable.aprs_alt_of),
        '&' to Sym(R.drawable.aprs_alt_og, "Gateway station"),
        '\'' to Sym(R.drawable.aprs_alt_oh, "Crash / incident site"),
        '(' to Sym(R.drawable.aprs_alt_oi, "Cloudy"),
        ')' to Sym(R.drawable.aprs_alt_oj, "Firenet MEO, MODIS Earth Observation"),
        '*' to Sym(R.drawable.aprs_alt_ok, "Snow"),
        '+' to Sym(R.drawable.aprs_alt_ol, "Church"),
        ',' to Sym(R.drawable.aprs_alt_om, "Girl Scouts"),
        '-' to Sym(R.drawable.aprs_alt_on, "House, HF antenna"),
        '.' to Sym(R.drawable.aprs_alt_oo, "Ambiguous, question mark inside circle"),
        '/' to Sym(R.drawable.aprs_alt_op, "Waypoint destination"),
        '0' to Sym(R.drawable.aprs_alt_a0, "Circle, IRLP / Echolink/WIRES"),
        '1' to Sym(R.drawable.aprs_alt_a1),
        '2' to Sym(R.drawable.aprs_alt_a2),
        '3' to Sym(R.drawable.aprs_alt_a3),
        '4' to Sym(R.drawable.aprs_alt_a4),
        '5' to Sym(R.drawable.aprs_alt_a5),
        '6' to Sym(R.drawable.aprs_alt_a6),
        '7' to Sym(R.drawable.aprs_alt_a7),
        '8' to Sym(R.drawable.aprs_alt_a8, "802.11 WiFi or other network node"),
        '9' to Sym(R.drawable.aprs_alt_a9, "Gas station"),
        ':' to Sym(R.drawable.aprs_alt_nr, "Hail"),
        ';' to Sym(R.drawable.aprs_alt_ns, "Park, picnic area"),
        '<' to Sym(R.drawable.aprs_alt_nt, "Advisory, single red flag"),
        '=' to Sym(R.drawable.aprs_alt_nu),
        '>' to Sym(R.drawable.aprs_alt_nv, "Red car"),
        '?' to Sym(R.drawable.aprs_alt_nw, "Info kiosk"),
        '@' to Sym(R.drawable.aprs_alt_nx, "Hurricane, Tropical storm"),
        'A' to Sym(R.drawable.aprs_alt_aa, "White box"),
        'B' to Sym(R.drawable.aprs_alt_ab, "Blowing snow"),
        'C' to Sym(R.drawable.aprs_alt_ac, "Coast Guard"),
        'D' to Sym(R.drawable.aprs_alt_ad, "Drizzling rain"),
        'E' to Sym(R.drawable.aprs_alt_ae, "Smoke, Chimney"),
        'F' to Sym(R.drawable.aprs_alt_af, "Freezing rain"),
        'G' to Sym(R.drawable.aprs_alt_ag, "Snow shower"),
        'H' to Sym(R.drawable.aprs_alt_ah, "Haze"),
        'I' to Sym(R.drawable.aprs_alt_ai, "Rain shower"),
        'J' to Sym(R.drawable.aprs_alt_aj, "Lightning"),
        'K' to Sym(R.drawable.aprs_alt_ak, "Kenwood HT"),
        'L' to Sym(R.drawable.aprs_alt_al, "Lighthouse"),
        'M' to Sym(R.drawable.aprs_alt_am),
        'N' to Sym(R.drawable.aprs_alt_an, "Navigation buoy"),
        'O' to Sym(R.drawable.aprs_alt_ao, "Rocket"),
        'P' to Sym(R.drawable.aprs_alt_ap, "Parking"),
        'Q' to Sym(R.drawable.aprs_alt_aq, "Earthquake"),
        'R' to Sym(R.drawable.aprs_alt_ar, "Restaurant"),
        'S' to Sym(R.drawable.aprs_alt_as, "Satellite"),
        'T' to Sym(R.drawable.aprs_alt_at, "Thunderstorm"),
        'U' to Sym(R.drawable.aprs_alt_au, "Sunny"),
        'V' to Sym(R.drawable.aprs_alt_av, "VORTAC, Navigational aid"),
        'W' to Sym(R.drawable.aprs_alt_aw, "NWS site"),
        'X' to Sym(R.drawable.aprs_alt_ax, "Pharmacy"),
        'Y' to Sym(R.drawable.aprs_alt_ay),
        'Z' to Sym(R.drawable.aprs_alt_az),
        '[' to Sym(R.drawable.aprs_alt_ds, "Wall Cloud"),
        '\\' to Sym(R.drawable.aprs_alt_dt),
        ']' to Sym(R.drawable.aprs_alt_du),
        '^' to Sym(R.drawable.aprs_alt_dv, "Aircraft"),
        '_' to Sym(R.drawable.aprs_alt_dw, "Weather site"),
        '`' to Sym(R.drawable.aprs_alt_dx, "Rain"),
        'a' to Sym(R.drawable.aprs_alt_sa, "Red diamond"),
        'b' to Sym(R.drawable.aprs_alt_sb, "Blowing dust, sand"),
        'c' to Sym(R.drawable.aprs_alt_sc, "CD triangle, RACES, CERTS, SATERN"),
        'd' to Sym(R.drawable.aprs_alt_sd, "DX spot"),
        'e' to Sym(R.drawable.aprs_alt_se, "Sleet"),
        'f' to Sym(R.drawable.aprs_alt_sf, "Funnel cloud"),
        'g' to Sym(R.drawable.aprs_alt_sg, "Gale, two red flags"),
        'h' to Sym(R.drawable.aprs_alt_sh, "Store"),
        'i' to Sym(R.drawable.aprs_alt_si, "Black box, point of interest"),
        'j' to Sym(R.drawable.aprs_alt_sj, "Work zone, excavating machine"),
        'k' to Sym(R.drawable.aprs_alt_sk, "SUV, ATV"),
        'l' to Sym(R.drawable.aprs_alt_sl),
        'm' to Sym(R.drawable.aprs_alt_sm, "Value sign, 3 digit display"),
        'n' to Sym(R.drawable.aprs_alt_sn, "Red triangle"),
        'o' to Sym(R.drawable.aprs_alt_so, "Small circle"),
        'p' to Sym(R.drawable.aprs_alt_sp, "Partly cloudy"),
        'q' to Sym(R.drawable.aprs_alt_sq),
        'r' to Sym(R.drawable.aprs_alt_sr, "Restrooms"),
        's' to Sym(R.drawable.aprs_alt_ss, "Ship, boat"),
        't' to Sym(R.drawable.aprs_alt_st, "Tornado"),
        'u' to Sym(R.drawable.aprs_alt_su, "Truck"),
        'v' to Sym(R.drawable.aprs_alt_sv, "Van"),
        'w' to Sym(R.drawable.aprs_alt_sw, "Flooding"),
        'x' to Sym(R.drawable.aprs_alt_sx),
        'y' to Sym(R.drawable.aprs_alt_sy, "Skywarn"),
        'z' to Sym(R.drawable.aprs_alt_sz, "Shelter"),
        '{' to Sym(R.drawable.aprs_alt_q1, "Fog"),
        '}' to Sym(R.drawable.aprs_alt_q3),
    )

    /** The entry for a symbol-table + symbol pair. Any table character other than '/' selects the
     *  alternate table: '\\' plainly, a digit or capital letter as an *overlay* drawn on top of
     *  the alternate symbol (see [overlayChar]). */
    private fun lookup(symbolTable: Char?, symbol: Char?): Sym? {
        if (symbol == null) return null
        return when {
            symbolTable == '/' -> primaryTable[symbol]
            symbolTable == '\\' || overlayChar(symbolTable) != null -> alternateTable[symbol]
            else -> null
        }
    }

    /** Returns the drawable resource id for the given APRS symbol-table + symbol pair, or `null`
     *  if this isn't a recognized symbol-table/symbol pair (caller should fall back to a generic icon). */
    fun resourceFor(symbolTable: Char?, symbol: Char?): Int? = lookup(symbolTable, symbol)?.resId

    /** Human-readable name of the symbol ("Car", "Digipeater"), or `null` if it has none. */
    fun label(symbolTable: Char?, symbol: Char?): String? = lookup(symbolTable, symbol)?.label

    /** The overlay character an alternate-table symbol carries (a digipeater's "S", a numbered
     *  circle's digit), or `null` when [symbolTable] is a plain table selector. */
    fun overlayChar(symbolTable: Char?): Char? =
        symbolTable?.takeIf { it in '0'..'9' || it in 'A'..'Z' }

    /** Stable string key for a symbol-table + symbol pair, used as the GeoJSON "icon" feature
     *  property so MaplibreView's data-driven iconImage expression can select the right image.
     *  An overlaid symbol maps to its plain alternate-table image; its [overlayChar] travels
     *  separately, as `MapMarker.overlay`. */
    fun iconKey(symbolTable: Char?, symbol: Char?): String? {
        if (resourceFor(symbolTable, symbol) == null) return null
        return "aprs:${if (symbolTable == '/') '/' else '\\'}$symbol"
    }

    /** All vendored (iconKey, drawable resource id) pairs - every image an APRS marker's
     *  [iconKey] can name. */
    val allIcons: List<Pair<String, Int>> =
        primaryTable.map { (symbol, sym) -> "aprs:/$symbol" to sym.resId } +
            alternateTable.map { (symbol, sym) -> "aprs:\\$symbol" to sym.resId }
}

/**
 * An APRS symbol as a small inline icon, with its overlay character (if any) drawn on top.
 * Renders nothing for an unknown symbol unless [reserveSpace] is set - a table column wants the
 * gap so the text after it stays aligned across rows.
 */
@Composable
fun AprsSymbolIcon(
    symbolTable: Char?,
    symbol: Char?,
    modifier: Modifier = Modifier,
    size: Dp = 18.dp,
    reserveSpace: Boolean = false,
) {
    val resId = AprsSymbolIcons.resourceFor(symbolTable, symbol)
    if (resId == null) {
        if (reserveSpace) Spacer(modifier.size(size))
        return
    }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(resId),
            contentDescription = AprsSymbolIcons.label(symbolTable, symbol),
            modifier = Modifier.fillMaxSize(),
        )
        AprsSymbolIcons.overlayChar(symbolTable)?.let { overlay ->
            Text(
                text = overlay.toString(),
                style = TextStyle(
                    fontSize = (size.value * 0.55f).sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    shadow = Shadow(color = Color.Black, blurRadius = 3f),
                ),
            )
        }
    }
}
