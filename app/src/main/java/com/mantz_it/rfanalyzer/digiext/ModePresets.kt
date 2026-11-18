package com.mantz_it.rfanalyzer.digiext

import com.mantz_it.rfanalyzer.database.Coordinates
import com.mantz_it.rfanalyzer.database.SourceProvider
import com.mantz_it.rfanalyzer.database.Station
import com.mantz_it.rfanalyzer.ui.TuneTarget
import com.mantz_it.rfanalyzer.ui.tabs.DemodulationMode
import com.mantz_it.rfanalyzer.ui.toTuneTarget
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * <h1>RF Analyzer - Mode Presets</h1>
 *
 * Module:      ModePresets.kt
 * Description: What the mode sheet (ModeSheet.kt) offers to start for each digital mode: the
 *              stations of that mode from the Digital Stations list (already filtered to the user's
 *              area) and the user's favorites - bookmarks, not a list of their own. The one that
 *              was started last comes first and is what the mode's Start button starts; then the
 *              favorites, then the list's entries, nearest first where the user's position and the
 *              entry's are known. RDS adds the one preset that isn't a station: the FM band.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

data class ModePreset(val target: TuneTarget, val favorite: Boolean = false, val distanceKm: Int? = null)

data class ModePresets(
    val items: List<ModePreset>,
    /** What the mode's Start button starts: the one started last, else the only one. */
    val startTarget: ModePreset?,
)

/** The mode sheet's stations and what can be done with them (see ModeSheet.kt). */
class ModeSheetStations(
    val presets: StateFlow<Map<DigitalMode, ModePresets>>,
    /** The area the presets are for ("Germany"); null while no location is known. */
    val area: StateFlow<String?>,
    val onStart: (DigitalMode, TuneTarget) -> Unit,
    /** Opens the Bookmark Manager on the stations of the mode. */
    val onShowAll: (DigitalMode) -> Unit,
    val onChangeLocation: () -> Unit,
)

/** The FM band, keeping the station the user already listens to - RDS's preset that isn't a station. */
val FM_BAND_TARGET = TuneTarget(
    "FM broadcast band", DemodulationMode.WFM, 97_750_000, 95_000,
    keepChannelInside = 87_500_000L..108_000_000L, viewportSpan = 20_500_000, key = "fm-band",
    params = { demodulationWfmStereoAndRdsEnabled.set(true) },
)

/** The demodulation modes whose stations the mode sheet looks at. */
val MODE_SHEET_DEMODULATION_MODES: List<DemodulationMode> = DigitalMode.entries.map { it.demodulationMode() }.distinct()

/**
 * The presets of every digital mode from [stations] (the Digital Stations list's rows and the
 * favorites); [lastUsed] holds the key of the station each mode was started with last, [position]
 * the user's position if known.
 */
fun buildModePresets(stations: List<Station>, lastUsed: Map<DigitalMode, String>, position: Coordinates?): Map<DigitalMode, ModePresets> =
    DigitalMode.entries.associateWith { mode ->
        val demodulationMode = mode.demodulationMode()
        fun distance(station: Station) = if (position == null) null else station.coordinates?.let { distanceKm(position, it) }
        val ofMode = stations.filter { it.mode == demodulationMode }
        val favorites = ofMode.filter { it.favorite }.sortedBy { it.name.lowercase() }
        val listed = ofMode.filter { it.source == SourceProvider.DIGITAL_LIST && !it.favorite }
            .sortedWith(compareBy<Station> { distance(it) ?: Int.MAX_VALUE }.thenBy { it.frequency })
        val builtIn = if (mode == DigitalMode.RDS) listOf(ModePreset(FM_BAND_TARGET)) else emptyList()
        val all = builtIn + (favorites + listed).map { station ->
            val target = station.toTuneTarget().let { t ->
                // Started for RDS, an FM station turns RDS on.
                if (mode == DigitalMode.RDS) t.copy(params = { t.params(this); demodulationWfmStereoAndRdsEnabled.set(true) }) else t
            }
            ModePreset(target, station.favorite, distance(station))
        }
        val last = all.firstOrNull { it.target.key == lastUsed[mode] }
        ModePresets(
            items = listOfNotNull(last) + all.filter { it !== last },
            startTarget = last ?: all.singleOrNull(),
        )
    }

private fun distanceKm(a: Coordinates, b: Coordinates): Int {
    val lat1 = Math.toRadians(a.latitude); val lat2 = Math.toRadians(b.latitude)
    val dLat = lat2 - lat1; val dLon = Math.toRadians(b.longitude - a.longitude)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return (2 * 6371.0 * asin(sqrt(h))).roundToInt()
}
