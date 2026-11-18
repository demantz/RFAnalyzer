package com.mantz_it.rfanalyzer.digiext.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.db.markerId
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.database.MapCameraPosition
import com.mantz_it.rfanalyzer.location.MapHome
import com.mantz_it.rfanalyzer.digiext.ui.common.LocalDigitalClock
import com.mantz_it.rfanalyzer.digiext.adsb.adsbMapIcons
import com.mantz_it.rfanalyzer.ui.MapFocusRequest
import com.mantz_it.rfanalyzer.ui.composable.AprsSymbolIcons
import com.mantz_it.rfanalyzer.ui.composable.MapIcon
import com.mantz_it.rfanalyzer.ui.composable.MapTrail
import com.mantz_it.rfanalyzer.ui.composable.MaplibreView
import com.mantz_it.rfanalyzer.ui.composable.fitCamera
import com.mantz_it.rfanalyzer.ui.composable.homeCamera
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import com.mantz_it.rfanalyzer.ui.composable.rememberMapCameraController
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Position
import com.mantz_it.rfanalyzer.digiext.PositionPoint
import com.mantz_it.rfanalyzer.digiext.ft8.FT8_GRID_ICON
import com.mantz_it.rfanalyzer.digiext.lora.MESH_NODE_ICON
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

/**
 * <h1>RF Analyzer - Digital Map View</h1>
 *
 * Module:      DigitalMapView.kt
 * Description: The Digital tab's Map subtab: [DigitalMapView] draws the tracked objects that have a
 *              position as markers, each kind through its [MapLayer], with their live trails, the
 *              selected object's popup card and controls shown on a tap. The camera fits the recent
 *              objects once per shown mode and is remembered per mode.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The Map subtab's objects - those the Overview subtab lists for the same filter, of every kind
 *  with a [MapLayer], minus those without a position. Built by `MainViewModel.mapMarkerState`. */
data class MapMarkerState(
    val objects: List<TrackedObject<*>> = emptyList(),
    /** What [objects] were read for: the mode's name, or "all" for every mode - until the reads for a
     *  newly shown mode arrive, the previous mode's objects are still here. */
    val key: String = "",
)

/** Every image a Digital-tab marker can ask for: the APRS symbol set (full colour), the ADS-B
 *  silhouettes and the FT8 grid square and mesh node (tintable). */
private val digitalMapIcons: List<MapIcon> =
    AprsSymbolIcons.allIcons.map { (key, resId) -> MapIcon(key, resId, sdf = false) } + adsbMapIcons +
        MapIcon(FT8_GRID_ICON, R.drawable.ic_marker_grid, sdf = true) + MapIcon(MESH_NODE_ICON, R.drawable.ic_marker_mesh, sdf = true)

// Moves a selected or followed marker toward the top left of the map: MapInfoWindow puts a card's
// top-left corner just below the marker and lets it grow right and down, so this leaves the card
// room without measuring it.
private val MAP_CARD_BIAS_PADDING = DpPadding(right = 290.dp, bottom = 200.dp)

private const val STALE_MARKER_TICK_MS = 5_000L
private const val CONTROLS_SHOWN_MS = 4_000L

/** Objects heard within this of the newest are "recent" for a fit of stored data. */
private const val RECENT_FIT_WINDOW_MS = 24 * 3_600_000L

/** The objects a fit is about: those heard this [session] while live, else those heard within a
 *  day of the newest - what the user is looking at, not everything ever recorded. */
internal fun recentForFit(objects: List<TrackedObject<*>>, session: SessionWindow?): List<TrackedObject<*>> {
    if (objects.isEmpty()) return objects
    if (session != null) objects.filter { it.lastSeenMs in session }.takeIf { it.isNotEmpty() }?.let { return it }
    val newest = objects.maxOf { it.lastSeenMs }
    return objects.filter { newest - it.lastSeenMs <= RECENT_FIT_WINDOW_MS }
}

/**
 * The Map subtab. Markers are tracked objects read from the database (see
 * MainViewModel.mapMarkerState), so they survive an app restart; [trails] are live-only.
 *
 * Nothing covers the map until it is tapped: a tap on an empty spot shows the controls (fit, paths,
 * all modes, the layer's key, my location) for a few seconds. The camera fits the recent objects
 * once per shown mode and then stays where it is - new packets never move it, except the first
 * positions arriving on a map that had none to fit yet. Without any, it shows the user's area
 * ([home]). Where the user put it is remembered per mode in [cameraMemory] for as long as the app
 * runs.
 */
@Composable
internal fun DigitalMapView(
    markerState: MapMarkerState,
    /** The user's area: where the camera starts without objects to fit, and the locator square. */
    home: MapHome?,
    /** The shown mode - the camera is kept per mode. */
    mode: DigitalMode?,
    /** Whether the shown mode is being received: then "recent" is this [session]. */
    live: Boolean,
    session: SessionWindow?,
    showTrails: Boolean,
    onShowTrailsChanged: (Boolean) -> Unit,
    showAllModes: Boolean,
    onShowAllModesChanged: (Boolean) -> Unit,
    /** Incremented by the header's "Fit to Markers". */
    fitRequest: Int,
    pendingFocus: MapFocusRequest?,
    onFocusHandled: () -> Unit,
    /** Live position trails by marker id, newest point first - see `DigitalExtensionRepository.trails`. */
    trails: Map<String, List<PositionPoint>>,
    onShareJsonExportClicked: (json: String, filenamePrefix: String) -> Unit,
    onOpenUrlClicked: (url: String) -> Unit,
    /** How each kind of object is drawn - see [mapLayerOf]. */
    layers: Map<com.mantz_it.rfanalyzer.digiext.db.ObjectKind, MapLayer<*>>,
    cameraMemory: MutableMap<String, MapCameraPosition>,
    modifier: Modifier = Modifier,
) {
    val cameraController = rememberMapCameraController()
    val coroutineScope = rememberCoroutineScope()
    val cameraKey = if (showAllModes) "all" else mode?.name ?: "none"

    // Objects that have left (an aircraft past its window) fade with time alone.
    val clock = LocalDigitalClock.current
    var nowMs by remember(clock) { mutableLongStateOf(clock()) }
    LaunchedEffect(clock) {
        while (true) {
            delay(STALE_MARKER_TICK_MS)
            nowMs = clock()
        }
    }
    val objects = markerState.objects
    val referenceMs = if (live) nowMs else (objects.maxOfOrNull { it.lastSeenMs } ?: nowMs)
    val objectMarkers = remember(objects, layers, referenceMs, live, session) {
        objects.mapNotNull { obj ->
            layers[obj.kind]?.markerOf(obj, referenceMs)?.let { marker ->
                // Not heard this session: drawn faded, like its row in the table.
                if (live && session != null && obj.lastSeenMs !in session && marker.alpha == 1f) marker.copy(alpha = STALE_MARKER_ALPHA) else marker
            }
        }
    }
    val shownIds = remember(objectMarkers) { objectMarkers.mapTo(HashSet()) { it.id } }

    var selectedMarkerId by remember { mutableStateOf<String?>(null) }
    // The "Map" target followed as it moves - cleared the instant the user pans or zooms.
    var trackedMarkerId by remember { mutableStateOf<String?>(null) }
    var lastTrackedPosition by remember { mutableStateOf<Position?>(null) }

    val markers = objectMarkers.map { marker -> if (marker.id == trackedMarkerId) marker.copy(highlighted = true) else marker }
    val selectedMarker = markers.find { it.id == selectedMarkerId }
    val mapTrails = remember(markers, trails) {
        markers.mapNotNull { marker ->
            trails[marker.id]?.let { points -> MapTrail(id = marker.id, points = points.asReversed().map { Position(it.lon, it.lat) }) }
        }
    }

    // Controls: shown on a tap on an empty spot, hidden again after a while without one.
    var controlsShownAt by remember { mutableLongStateOf(0L) }
    var controlsVisible by remember { mutableStateOf(false) }
    LaunchedEffect(controlsShownAt) {
        if (controlsShownAt == 0L) return@LaunchedEffect
        controlsVisible = true
        delay(CONTROLS_SHOWN_MS)
        controlsVisible = false
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        // Only the shown mode's own objects are fitted to - not the previous mode's, still listed
        // until the new reads arrive.
        val recentPoints = remember(objects, session, live, shownIds, markerState.key, cameraKey) {
            if (markerState.key != cameraKey) emptyList()
            else recentForFit(objects.filter { it.markerId in shownIds }, if (live) session else null)
                .mapNotNull { obj -> obj.lat?.let { lat -> obj.lon?.let { lon -> lat to lon } } }
        }
        // A fit is owed when this mode's map is shown for the first time - and stays owed while
        // there is nothing to fit to yet, so the first positions of a session bring the camera to
        // them once. Taking the camera by hand settles it.
        var fitOwed by remember(cameraKey) { mutableStateOf(cameraKey !in cameraMemory) }
        var lastFitRequest by remember { mutableIntStateOf(fitRequest) }
        val currentPoints by rememberUpdatedState(recentPoints)

        val currentHome by rememberUpdatedState(home)
        // "Fit" without objects goes home.
        fun fit() {
            val camera = fitCamera(currentPoints, widthDp, heightDp)
                ?: currentHome?.let { homeCamera(it, widthDp, heightDp) } ?: return
            fitOwed = false
            coroutineScope.launch { cameraController.animateTo(Position(camera.longitude, camera.latitude), zoom = camera.zoom) }
        }
        // The user's area changed while the camera still waits for objects: go there. Saveable like
        // the map's camera, which a tab switch restores instead of starting on the new home.
        var homeShown by rememberSaveable { mutableStateOf(home?.bounds?.toString()) }
        LaunchedEffect(home) {
            val shown = home?.bounds?.toString()
            if (shown == homeShown) return@LaunchedEffect
            if (fitOwed && cameraKey !in cameraMemory && currentPoints.isEmpty()) {
                val camera = homeCamera(home, widthDp, heightDp)
                cameraController.animateTo(Position(camera.longitude, camera.latitude), zoom = camera.zoom)
            }
            homeShown = shown
        }
        LaunchedEffect(cameraKey) {
            // Another mode on the same map: back to where its camera was, or its own fit.
            cameraMemory[cameraKey]?.let { remembered ->
                cameraController.animateTo(Position(remembered.longitude, remembered.latitude), zoom = remembered.zoom)
            }
        }
        LaunchedEffect(fitOwed, recentPoints.isNotEmpty(), widthDp, heightDp) {
            if (fitOwed && recentPoints.isNotEmpty()) fit()
        }
        LaunchedEffect(fitRequest) {
            if (fitRequest != lastFitRequest) {
                lastFitRequest = fitRequest
                fit()
            }
        }

        // "Map" from a row or card: select the marker, fly to it (biased so its card fits), and
        // for a tracking request keep following it.
        LaunchedEffect(pendingFocus) {
            val focus = pendingFocus ?: return@LaunchedEffect
            selectedMarkerId = focus.markerId
            fitOwed = false
            val targetPosition = Position(focus.lon, focus.lat)
            if (focus.track) {
                trackedMarkerId = focus.markerId
                lastTrackedPosition = targetPosition
            }
            cameraController.animateTo(targetPosition, zoom = focus.zoom, padding = MAP_CARD_BIAS_PADDING)
            onFocusHandled()
        }
        LaunchedEffect(trackedMarkerId, markers) {
            val id = trackedMarkerId ?: return@LaunchedEffect
            val marker = markers.find { it.id == id } ?: return@LaunchedEffect
            val newPosition = Position(marker.lon, marker.lat)
            if (newPosition != lastTrackedPosition) {
                lastTrackedPosition = newPosition
                cameraController.animateTo(newPosition, padding = MAP_CARD_BIAS_PADDING)
            }
        }
        LaunchedEffect(cameraController) {
            snapshotFlow { cameraController.moveReason }
                .filter { it == CameraMoveReason.Gesture }
                .collect {
                    trackedMarkerId = null
                    fitOwed = false
                }
        }

        MaplibreView(
            modifier = Modifier.fillMaxSize(),
            markers = markers,
            icons = digitalMapIcons,
            onMarkerClick = { marker ->
                selectedMarkerId = marker.id
                coroutineScope.launch { cameraController.animateTo(Position(marker.lon, marker.lat), padding = MAP_CARD_BIAS_PADDING) }
            },
            onDeselect = {
                if (selectedMarkerId != null) selectedMarkerId = null else controlsShownAt = System.currentTimeMillis()
            },
            selectedMarker = selectedMarker,
            trails = mapTrails,
            showTrails = showTrails,
            initialCameraPosition = cameraMemory[cameraKey] ?: homeCamera(home, widthDp, heightDp),
            cameraController = cameraController,
            onCameraIdle = { lat, lon, zoom ->
                val position = MapCameraPosition(lat, lon, zoom)
                // A camera that was never placed (a fit still owed) is not worth remembering.
                if (!fitOwed) cameraMemory[cameraKey] = position
            },
            showLocationButton = controlsVisible,
            home = home,
        ) {
            val selected = objects.find { it.markerId == selectedMarkerId }
            val layer = selected?.let { layers[it.kind] }
            if (selected != null && layer != null) {
                layer.Popup(
                    selected,
                    MapPopupCallbacks(
                        onClose = { selectedMarkerId = null },
                        onTrack = { trackedMarkerId = selected.markerId; lastTrackedPosition = null },
                        onShareJson = onShareJsonExportClicked,
                        onOpenUrl = onOpenUrlClicked,
                    ),
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapControl("Fit", Icons.Default.CenterFocusStrong, selected = false) { controlsShownAt = System.currentTimeMillis(); fit() }
                    MapControl("Paths", Icons.Default.Route, selected = showTrails) { controlsShownAt = System.currentTimeMillis(); onShowTrailsChanged(!showTrails) }
                    MapControl("All modes", Icons.Default.Layers, selected = showAllModes) { controlsShownAt = System.currentTimeMillis(); onShowAllModesChanged(!showAllModes) }
                }
                // The key of every layer with a marker on the map.
                objects.mapNotNullTo(LinkedHashSet()) { layers[it.kind]?.legend }.forEach { legend -> legend(Modifier) }
            }
        }
    }
}

/** One of the map's controls: a chip on the map's surface colour, so it reads on any tiles. */
@Composable
private fun MapControl(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp)) },
        colors = FilterChipDefaults.filterChipColors(containerColor = MaterialTheme.colorScheme.surface),
    )
}
