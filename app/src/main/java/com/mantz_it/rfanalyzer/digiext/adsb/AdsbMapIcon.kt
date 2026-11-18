package com.mantz_it.rfanalyzer.digiext.adsb

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.ui.composable.DEFAULT_ICON_SIZE
import com.mantz_it.rfanalyzer.ui.composable.MapIcon

/**
 * <h1>RF Analyzer - ADS-B Map Icon</h1>
 *
 * Module:      AdsbMapIcon.kt
 * Description: How an aircraft is drawn: which silhouette, how big, and in which colour. Shared by
 *              the Map subtab's markers and the Overview table's identity cell.
 *
 *              The silhouette comes from the emitter category of the aircraft's identification
 *              message (TC 1-4), which is the only thing an ADS-B frame says about what kind of
 *              aircraft sent it - there is no type designator on air. The colour encodes altitude.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** One silhouette per group of emitter categories that look alike from above. */
enum class AdsbIconShape(@DrawableRes val resId: Int) {
    /** No identification message heard yet, or a category it left unset. */
    GENERIC(R.drawable.ic_marker_plane),
    LIGHT(R.drawable.ic_adsb_light),
    JET(R.drawable.ic_adsb_jet),
    HEAVY(R.drawable.ic_adsb_heavy),
    HIGH_PERFORMANCE(R.drawable.ic_adsb_highperf),
    ROTORCRAFT(R.drawable.ic_adsb_rotorcraft),
    GLIDER(R.drawable.ic_adsb_glider),
    BALLOON(R.drawable.ic_adsb_balloon),
    UAV(R.drawable.ic_adsb_uav),
    CANOPY(R.drawable.ic_adsb_canopy),
    VEHICLE(R.drawable.ic_adsb_vehicle),
    OBSTRUCTION(R.drawable.ic_adsb_obstruction);

    /** Key of this shape's [MapIcon]. */
    val iconKey: String get() = "adsb:$name"
}

/** Every ADS-B silhouette as a map image - tintable, so [adsbAltitudeColor] can colour it. */
val adsbMapIcons: List<MapIcon> = AdsbIconShape.entries.map { MapIcon(it.iconKey, it.resId, sdf = true) }

/** The aircraft's silhouette - generic until an identification message names its category. */
val AdsbAircraft.iconShape: AdsbIconShape get() = category?.shape ?: AdsbIconShape.GENERIC

/** Marker size: bigger aircraft get a visibly bigger icon on the map. */
fun AdsbAircraft.mapIconSize(): Float = category?.iconSize ?: DEFAULT_ICON_SIZE

/** On the ground, whatever the reported altitude. */
private val ADSB_GROUND_COLOR = Color(0xFF757575)
/** Airborne but no altitude decoded yet. */
private val ADSB_UNKNOWN_ALTITUDE_COLOR = Color(0xFF455A64)

private const val ALTITUDE_LOW_FT = 2_000
private const val ALTITUDE_MID_FT = 10_000
private const val ALTITUDE_HIGH_FT = 40_000

/**
 * Hue for an altitude, the scale tar1090/readsb users already read at a glance: orange near the
 * ground, through yellow and green around 10,000 ft, then cyan and blue, to magenta at 40,000 ft
 * and above. Two linear segments rather than one, because half of all traffic near an airport sits
 * below 10,000 ft and would otherwise share a sliver of the scale.
 */
internal fun adsbAltitudeHue(altitudeFt: Int): Float = when {
    altitudeFt <= ALTITUDE_LOW_FT -> 20f
    altitudeFt <= ALTITUDE_MID_FT -> 20f + 120f * (altitudeFt - ALTITUDE_LOW_FT) / (ALTITUDE_MID_FT - ALTITUDE_LOW_FT)
    altitudeFt <= ALTITUDE_HIGH_FT -> 140f + 160f * (altitudeFt - ALTITUDE_MID_FT) / (ALTITUDE_HIGH_FT - ALTITUDE_MID_FT)
    else -> 300f
}

/** Lightness of the altitude colours on the map: saturated mid-tones, which the map outlines. */
private const val MAP_LIGHTNESS = 0.5f
/** On a dark surface the same hues need lifting, or the blue-to-magenta end sinks into it. */
private const val DARK_SURFACE_LIGHTNESS = 0.65f

/**
 * Colour for an aircraft's silhouette. [lightness] is the HSL lightness of the altitude scale -
 * the default suits the map's pale basemap.
 */
fun adsbAltitudeColor(altitudeFt: Int?, onGround: Boolean?, lightness: Float = MAP_LIGHTNESS): Color = when {
    onGround == true -> ADSB_GROUND_COLOR
    altitudeFt == null -> ADSB_UNKNOWN_ALTITUDE_COLOR
    else -> Color.hsl(adsbAltitudeHue(altitudeFt), saturation = 0.9f, lightness = lightness)
}

/** The aircraft's silhouette in its altitude colour, for use outside the map. */
@Composable
fun AdsbAircraftIcon(aircraft: AdsbAircraft, modifier: Modifier = Modifier, size: Dp = 16.dp) {
    val onDarkSurface = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val tint = when {
        // The two off-scale greys are fixed colours; on a dark surface the theme's own muted
        // tones stand in for them.
        onDarkSurface && aircraft.onGround == true -> MaterialTheme.colorScheme.onSurfaceVariant
        onDarkSurface && aircraft.altitudeFt == null -> MaterialTheme.colorScheme.outline
        else -> adsbAltitudeColor(aircraft.altitudeFt, aircraft.onGround, if (onDarkSurface) DARK_SURFACE_LIGHTNESS else MAP_LIGHTNESS)
    }
    Icon(
        painter = painterResource(aircraft.iconShape.resId),
        contentDescription = aircraft.category?.label,
        tint = tint,
        modifier = modifier.size(size),
    )
}

/** Key to the marker colours, overlaid on a map that is showing aircraft. */
@Composable
fun AdsbAltitudeLegend(modifier: Modifier = Modifier) {
    val ticks = listOf(0, 10_000, 20_000, 30_000, 40_000)
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .width(132.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient((0..40).map { adsbAltitudeColor(it * 1_000, onGround = false) })),
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            ticks.forEach { ft ->
                Text(
                    text = if (ft == 0) "0" else "${ft / 1000}k",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text("ft", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
        }
    }
}
