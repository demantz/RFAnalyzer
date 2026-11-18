package com.mantz_it.rfanalyzer.ui.composable

import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.database.MapCameraPosition
import com.mantz_it.rfanalyzer.location.GeoBounds
import com.mantz_it.rfanalyzer.location.MapHome
import com.mantz_it.rfanalyzer.location.rememberLocationPermissionRequest
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.maplibre.compose.camera.CameraMoveReason
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.CameraUpdate
import org.maplibre.compose.expressions.dsl.Feature as ExprFeature
import org.maplibre.compose.expressions.dsl.case
import org.maplibre.compose.expressions.dsl.coalesce
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.expressions.dsl.convertToColor
import org.maplibre.compose.expressions.dsl.image
import org.maplibre.compose.expressions.dsl.switch
import org.maplibre.compose.expressions.dsl.times
import org.maplibre.compose.expressions.value.BooleanValue
import org.maplibre.compose.expressions.value.FloatValue
import org.maplibre.compose.expressions.value.FormattedValue
import org.maplibre.compose.expressions.value.IconRotationAlignment
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.interaction.ClickEvent
import org.maplibre.compose.interaction.ClickResult
import org.maplibre.compose.interaction.MapInteractions
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.location.rememberLocationState
import org.maplibre.compose.map.MapState
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.rememberMapState
import org.maplibre.compose.material3.LocationIndicatorLayer
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.DpPadding
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Geometry
import org.maplibre.spatialk.geojson.LineString
import org.maplibre.spatialk.geojson.Point
import org.maplibre.spatialk.geojson.Polygon
import org.maplibre.spatialk.geojson.Position
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * <h1>RF Analyzer - MapLibre Map View</h1>
 *
 * Module:      MaplibreView.kt
 * Description: The app's map on MapLibre Compose (OpenFreeMap "Liberty" style), used by the Digital
 *              tab's Map and the KiwiSDR picker: MaplibreView draws markers, trails, the user's
 *              locator square and location, and the selected marker's card (MapInfoWindow).
 *              MapCameraController lets a caller move the camera; fitCamera and homeCamera compute
 *              the view a map starts with.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * An image a [MapMarker] can render as, registered with the map through [MaplibreView]'s `icons`.
 * An [sdf] image is a single-colour silhouette the map can tint per marker ([MapMarker.color]); a
 * non-SDF one is drawn in its own colours.
 */
data class MapIcon(val key: String, val resId: Int, val sdf: Boolean)

data class MapMarker(
    val id: String,
    val lat: Double,
    val lon: Double,
    val title: String,
    val description: String,
    /** [MapIcon.key] of the image to draw. Null, or a key the map was not given, draws the
     *  generic antenna. */
    val iconKey: String? = null,
    /** Tint for an SDF icon; null is the default marker blue. Ignored by full-colour icons. */
    val color: Color? = null,
    /** Heading/course in degrees, clockwise from north; `null` renders the icon unrotated. */
    val rotationDeg: Float? = null,
    /** Short text drawn centred on the icon - an APRS overlay character (the "1" in a digipeater's
     *  star). Null draws the icon alone. */
    val overlay: String? = null,
    /** Whether this marker is the current "Track on map" target - rendered with a colored
     *  highlight ring behind the icon (works for full-color/non-SDF icons too, unlike an
     *  icon tint or halo, which MapLibre only supports for SDF icons). */
    val highlighted: Boolean = false,
    /** Scales the icon (1.5 = the default/original size) - e.g. ADS-B uses this to reflect
     *  aircraft size (see AdsbMapIcon.kt's mapIconSize). */
    val iconSize: Float = DEFAULT_ICON_SIZE,
    /** Below 1 the marker is drawn faded - an object that is no longer there (an aircraft out of
     *  range) but is still being shown. */
    val alpha: Float = 1f,
)

private val DEFAULT_MARKER_COLOR = Color.Blue
private val HOME_COLOR = Color(0xFF6A1B9A)
private val MARKER_OUTLINE_COLOR = Color(0xFF1B1B1B)

/** `#RRGGBB`, the form MapLibre's `to-color` parses from a feature property. */
private fun Color.toHexString(): String = "#%06X".format(toArgb() and 0xFFFFFF)

const val DEFAULT_ICON_SIZE = 1.5f

/** A tracked object's recent position history, rendered as a line on the map. Needs at least two
 *  points - callers should filter shorter histories out before passing them in. */
data class MapTrail(
    val id: String,
    val points: List<Position>,
)

/** The highest zoom a fit goes to: one object alone is shown with its surroundings. */
const val MAX_FIT_ZOOM = 12.0
/**
 * The camera that shows [points] (lat, lon) in a viewport of [widthDp] × [heightDp]: their bounds
 * with a margin, the zoom capped at [maxZoom]. With four or more points, those farther than three
 * times the median distance from the median point are left out - one station heard from across
 * the ocean weeks ago must not zoom the map out to the whole world. Null for no points.
 */
fun fitCamera(points: List<Pair<Double, Double>>, widthDp: Float, heightDp: Float, maxZoom: Double = MAX_FIT_ZOOM): MapCameraPosition? {
    if (points.isEmpty() || widthDp <= 0f || heightDp <= 0f) return null
    val medLat = points.map { it.first }.sorted()[points.size / 2]
    val medLon = points.map { it.second }.sorted()[points.size / 2]
    val cosLat = cos(medLat * PI / 180)
    fun distance(p: Pair<Double, Double>) = Math.hypot(p.first - medLat, (p.second - medLon) * cosLat)
    val kept = if (points.size >= 4) {
        val medDistance = points.map(::distance).sorted()[points.size / 2]
        points.filter { distance(it) <= max(3 * medDistance, 0.05) }
    } else points
    val minLat = kept.minOf { it.first }; val maxLat = kept.maxOf { it.first }
    val minLon = kept.minOf { it.second }; val maxLon = kept.maxOf { it.second }
    // Web Mercator in units of the world (0..1); MapLibre's world is 512 dp wide at zoom 0.
    fun mercY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.0, 85.0) * PI / 180
        return (1 - ln(tan(clamped) + 1 / cos(clamped)) / PI) / 2
    }
    val spanX = (maxLon - minLon) / 360
    val spanY = abs(mercY(minLat) - mercY(maxLat))
    val usableW = widthDp * 0.8
    val usableH = heightDp * 0.75
    val zoomX = if (spanX > 0) log2(usableW / (512 * spanX)) else maxZoom
    val zoomY = if (spanY > 0) log2(usableH / (512 * spanY)) else maxZoom
    // below zoom 1 for a large country (Canada, Russia) in a small map
    val zoom = min(min(zoomX, zoomY), maxZoom).coerceAtLeast(0.0)
    val centerY = (mercY(minLat) + mercY(maxLat)) / 2
    val centerLat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(PI * (1 - 2 * centerY))))
    // a box across the antimeridian has longitudes beyond 180
    val centerLon = ((minLon + maxLon) / 2 + 540) % 360 - 180
    return MapCameraPosition(latitude = centerLat, longitude = centerLon, zoom = zoom)
}


/** The camera that starts a map on [home] (see [MapHome]) - the whole world without one. */
fun homeCamera(home: MapHome?, widthDp: Float, heightDp: Float): MapCameraPosition =
    home?.bounds?.shownPart()?.let { fitCamera(listOf(it.south to it.west, it.north to it.east), widthDp, heightDp) } ?: MapCameraPosition.DEFAULT

/** The latitudes a Web Mercator map shows. */
private const val MAX_MAP_LATITUDE = 85.0

/** The part of the box the map can show; null if there is none (a locator square at a pole,
 *  which drawn or flown to would leave the map blank). */
private fun GeoBounds.shownPart(): GeoBounds? {
    val s = south.coerceIn(-MAX_MAP_LATITUDE, MAX_MAP_LATITUDE)
    val n = north.coerceIn(-MAX_MAP_LATITUDE, MAX_MAP_LATITUDE)
    return if (n > s) copy(south = s, north = n) else null
}

/**
 * Lets a [MaplibreView] caller command and observe the map's camera (e.g. "center and zoom in on
 * this marker" from a cross-tab "Show on map" action). The map itself is created inside
 * [MaplibreView], because MapLibre Compose declares a map's layers together with its state.
 */
@Stable
class MapCameraController internal constructor() {
    internal var mapState: MapState? by mutableStateOf(null)

    /** What started the most recent camera movement - [CameraMoveReason.Gesture] for a manual
     *  pan/zoom/rotate, as opposed to the programmatic moves [animateTo] triggers. */
    val moveReason: CameraMoveReason
        get() = mapState?.cameraMoveReason ?: CameraMoveReason.None

    /** Moves the camera to [center]; a null [zoom] or [padding] keeps the current value. Waits
     *  for the map if it isn't composed yet. */
    suspend fun animateTo(center: Position, zoom: Double? = null, padding: DpPadding? = null) {
        val state = snapshotFlow { mapState }.filterNotNull().first()
        state.animateCamera(CameraUpdate(center = center, zoom = zoom, padding = padding))
    }
}

@Composable
fun rememberMapCameraController(): MapCameraController = remember { MapCameraController() }

// Approximate rendered half-width/height of a marker icon at DEFAULT_ICON_SIZE, in dp - used to
// anchor MapInfoWindow directly below a marker with matching left edges (see its doc comment).
// Necessarily approximate: MapLibre doesn't expose the actual rendered icon bounds, and our own
// icon sources aren't uniformly sized (24dp vector plane/antenna icons vs 64px nodpi APRS bitmaps).
private const val BASE_ICON_HALF_SIZE_DP = 18f
private const val ICON_BOTTOM_GAP_DP = 6f

@Composable
fun MapInfoWindow(
    mapState: MapState,
    targetPosition: Position,
    modifier: Modifier = Modifier,
    // Scales the position offset below to roughly match the marker's actual rendered size (see
    // MapMarker.iconSize) - defaults to the standard/unscaled marker size.
    markerIconSize: Float = DEFAULT_ICON_SIZE,
    content: @Composable (BoxScope.() -> Unit),
) {
    // 1. Project the geographic position to screen coordinates (re-read on every camera change);
    // nothing is drawn until the projection is ready.
    val target = remember(targetPosition, mapState.cameraPosition, mapState.viewport) {
        mapState.screenLocationFromPosition(targetPosition)
    } ?: return
    // 2. Position the content directly below the marker, its left edge aligned with the marker
    // icon's left edge.
    val sizeRatio = markerIconSize / DEFAULT_ICON_SIZE
    val halfWidth = (BASE_ICON_HALF_SIZE_DP * sizeRatio).dp
    val bottomOffset = (BASE_ICON_HALF_SIZE_DP * sizeRatio + ICON_BOTTOM_GAP_DP).dp
    // The part of the map on screen: a half-open bottom sheet pushes the rest below the screen.
    val density = LocalDensity.current
    var visibleHeight by remember { mutableStateOf<Dp?>(null) }
    // Clipped: a marker panned past the map's edge takes its card along, which must not draw over
    // whatever lies beyond the map.
    BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds().onGloballyPositioned { coordinates ->
        visibleHeight = with(density) { (coordinates.boundsInWindow().bottom - coordinates.positionInWindow().y).toDp() }
    }) {
        val top = target.y + bottomOffset
        // 3. The content gets the room left below the marker and scrolls if it is taller (a card
        // with many facts on a short map). Capped at the map's height instead, a custom layout
        // reporting more than that is centred by Compose and slides over the content above it.
        val room = minOf(maxHeight, visibleHeight ?: maxHeight) - top
        val left = target.x - halfWidth
        val markerOnMap = target.x in 0.dp..maxWidth
        Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0))
                    // 4. A marker near the map's side moves its card inwards, so the card stays
                    // whole on the map; a marker panned off the map takes it along.
                    val x = if (markerOnMap) left.roundToPx().coerceAtMost(constraints.maxWidth - placeable.width).coerceAtLeast(0)
                            else left.roundToPx()
                    layout(placeable.width, placeable.height) { placeable.place(x, top.roundToPx()) }
                }
                .heightIn(max = room.coerceAtLeast(MIN_INFO_WINDOW_HEIGHT))
                .verticalScroll(rememberScrollState()),
            content = content,
        )
    }
}

private val MIN_INFO_WINDOW_HEIGHT = 96.dp

@Composable
fun MaplibreView(
    modifier: Modifier = Modifier,
    markers: List<MapMarker>,
    // The images markers may ask for by MapMarker.iconKey, beyond the built-in antenna.
    icons: List<MapIcon> = emptyList(),
    onMarkerClick: (MapMarker) -> Unit,
    // Fires when the selected marker's card should close: a tap on empty map area, or on the
    // selected marker again - handled here so every map behaves the same.
    onDeselect: () -> Unit = {},
    selectedMarker: MapMarker? = null,
    trails: List<MapTrail> = emptyList(),
    showTrails: Boolean = true,
    initialCameraPosition: MapCameraPosition = MapCameraPosition.DEFAULT,
    // Hoistable so a caller can command the camera - see MapCameraController.
    cameraController: MapCameraController = rememberMapCameraController(),
    onCameraIdle: (latitude: Double, longitude: Double, zoom: Double) -> Unit = { _, _, _ -> },
    /** Whether the "show my location" button is drawn - a caller that keeps the map clear by
     *  default shows it only together with its own controls. */
    showLocationButton: Boolean = true,
    /** The user's locator square, drawn as home - see [MapHome]. */
    home: MapHome? = null,
    selectedMarkerComposable: @Composable () -> Unit = {}
) {
    // "Show my location" button and indicator for every map (Digital tab's Map, KiwiSDR picker).
    // Location is collected only once permission is granted; the button below requests it.
    val locationState = rememberLocationState()
    // Set by the button press; consumed by the LaunchedEffect below once a location fix is in
    // (at once if one is cached, else with the first update after the permission was granted).
    var centerOnUserLocationRequested by remember { mutableStateOf(false) }
    val requestUserLocation = rememberLocationPermissionRequest { centerOnUserLocationRequested = true }

    val mapState = rememberMapState(
        baseStyle = BaseStyle.Uri("https://tiles.openfreemap.org/styles/liberty"), // Using OpenFreeMap "Liberty" style (Vector tiles)
        initialCameraPosition = CameraPosition(
            center = Position(initialCameraPosition.longitude, initialCameraPosition.latitude),
            zoom = initialCameraPosition.zoom,
        ),
    ) {
        // The home square lies under everything else.
        home?.square?.shownPart()?.let { square ->
            val homeFeatures = remember(square, home.label) {
                val corners = listOf(
                    Position(square.west, square.south), Position(square.east, square.south),
                    Position(square.east, square.north), Position(square.west, square.north), Position(square.west, square.south),
                )
                FeatureCollection(
                    features = listOf(
                        Feature(geometry = Polygon(corners), properties = null),
                        Feature(
                            geometry = Point(Position((square.west + square.east) / 2, (square.south + square.north) / 2)),
                            properties = buildJsonObject { put("label", home.label ?: "") },
                        ),
                    )
                )
            }
            val homeSource = rememberGeoJsonSource(data = GeoJsonData.Features(homeFeatures))
            FillLayer(id = "my-home-fill", source = homeSource, color = const(HOME_COLOR), opacity = const(0.12f))
            LineLayer(id = "my-home-outline", source = homeSource, color = const(HOME_COLOR), width = const(2.dp))
            SymbolLayer(
                id = "my-home-label",
                source = homeSource,
                textField = ExprFeature.get("label").cast<FormattedValue>(),
                textFont = const(listOf("Noto Sans Bold")),
                textSize = const(13.sp),
                textColor = const(HOME_COLOR),
                textHaloColor = const(Color.White),
                textHaloWidth = const(1.5.dp),
            )
        }

        // Trails are rendered before the symbol layer so markers draw on top of the lines.
        if (showTrails && trails.isNotEmpty()) {
            val trailsWithEnoughPoints = remember(trails) { trails.filter { it.points.size >= 2 } }
            if (trailsWithEnoughPoints.isNotEmpty()) {
                val trailFeatureCollection = remember(trailsWithEnoughPoints) {
                    FeatureCollection(
                        features = trailsWithEnoughPoints.map { trail ->
                            Feature(
                                geometry = LineString(trail.points),
                                properties = buildJsonObject { put("id", trail.id) }
                            )
                        }
                    )
                }
                val trailSource = rememberGeoJsonSource(
                    data = GeoJsonData.Features(trailFeatureCollection),
                )
                LineLayer(
                    id = "my-trail-layer",
                    source = trailSource,
                    color = const(Color(0xFFFF6D00)),
                    width = const(2.dp),
                    opacity = const(0.85f),
                )
            }
        }

        if(markers.isNotEmpty()) {
            val featureCollection = remember(markers) {
                FeatureCollection(
                    features = markers.map { marker ->
                        Feature(
                            geometry = Point(Position(marker.lon, marker.lat)),
                            properties = buildJsonObject {
                                put("id", marker.id)
                                put("icon", marker.iconKey ?: "")
                                put("overlay", marker.overlay ?: "")
                                put("color", (marker.color ?: DEFAULT_MARKER_COLOR).toHexString())
                                // Exactly one of the two is true: it picks the marker's layer.
                                put("rotating", marker.rotationDeg != null)
                                put("upright", marker.rotationDeg == null)
                                put("rotation", marker.rotationDeg ?: 0f)
                                put("tracked", marker.highlighted)
                                put("size", marker.iconSize)
                                put("alpha", marker.alpha)
                                // The halo lies under the whole silhouette, not just around it, so
                                // at the icon's own opacity alone it would darken a faded marker
                                // instead of fading with it.
                                put("outline", MARKER_OUTLINE_COLOR.run { "rgba(${(red * 255).toInt()},${(green * 255).toInt()},${(blue * 255).toInt()},${marker.alpha})" })
                            }
                        )
                    }
                )
            }
            val source = rememberGeoJsonSource(
                data = GeoJsonData.Features(featureCollection),
            )
            // Only the images a marker actually asks for: the map renders every image named here
            // to a bitmap on the main thread, ~5 ms each, so the whole APRS symbol set (~190
            // images) would freeze the UI for about a second whenever the map appears.
            val usedIcons = remember(markers, icons) {
                val usedKeys = markers.mapNotNullTo(HashSet()) { it.iconKey }
                icons.filter { it.key in usedKeys }
            }
            val iconImageExpr = switch(
                input = ExprFeature.get("icon").cast<StringValue>(),
                *usedIcons.map { icon ->
                    // Keyed, so an icon keeps its painter (and its rendered image) when the set
                    // of used icons changes around it.
                    key(icon.key) { case(icon.key, image(painterResource(icon.resId), drawAsSdf = icon.sdf)) }
                }.toTypedArray(),
                fallback = image(painterResource(R.drawable.ic_marker_antenna), drawAsSdf = true),
            )
            val trackedTest = coalesce(ExprFeature.get("tracked").cast<BooleanValue>(), fallback = const(false))
            val sizeExpr = coalesce(ExprFeature.get("size").cast<FloatValue>(), fallback = const(DEFAULT_ICON_SIZE))

            // Highlights the tracked marker with a colored ring behind its icon, rendered
            // before (i.e. below) the symbol layer. A ring works uniformly for both SDF icons
            // (plane/antenna) and the full-color APRS PNGs - iconColor/iconHaloColor only
            // affect SDF icons, so they can't highlight the APRS symbols by themselves.
            CircleLayer(
                id = "my-tracked-highlight-layer",
                source = source,
                filter = trackedTest,
                color = const(Color(0xFFFFEB3B)),
                opacity = const(0.55f),
                radius = const(22.dp),
            )
            // The overlay text, sized against the icon like AprsSymbolIcon does in the rest of the
            // UI. The font has to be one the base style serves glyphs for.
            val overlayTextExpr = ExprFeature.get("overlay").cast<FormattedValue>()
            val overlayFontExpr = const(listOf("Noto Sans Bold"))
            val overlaySizeExpr = sizeExpr * const(12.sp)
            val iconColorExpr = ExprFeature.get("color").convertToColor(const(DEFAULT_MARKER_COLOR))
            val outlineColorExpr = ExprFeature.get("outline").convertToColor(const(MARKER_OUTLINE_COLOR))
            val alphaExpr = coalesce(ExprFeature.get("alpha").cast<FloatValue>(), fallback = const(1f))
            val onMarkerFeatureClick: ClickEvent.(List<Feature<Geometry, JsonObject?>>) -> ClickResult = { features ->
                val id = features.firstOrNull()?.properties?.get("id")?.jsonPrimitive?.content
                val marker = markers.find { it.id == id }
                if (marker != null) {
                    // Re-tapping the already-selected marker toggles its card closed,
                    // instead of re-opening the same card (see onDeselect's doc comment).
                    if (marker.id == selectedMarker?.id) onDeselect() else onMarkerClick(marker)
                }
                ClickResult.Consume
            }
            // Two layers over the one source, because rotation alignment is a property of the
            // layer and not of the feature. A marker with a heading is aligned to the *map*, so
            // its nose keeps pointing along its track when the user rotates the map; one without
            // stays upright on screen. The dark halo outlines a tinted silhouette so it stays
            // legible on any basemap colour; halo and tint only affect SDF icons.
            SymbolLayer(
                id = "my-symbol-layer",
                source = source,
                filter = ExprFeature.get("upright").cast<BooleanValue>(),
                iconImage = iconImageExpr,
                iconColor = iconColorExpr,
                iconOpacity = alphaExpr,
                iconHaloColor = outlineColorExpr,
                iconHaloWidth = const(1.dp),
                iconSize = sizeExpr,
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
                textField = overlayTextExpr,
                textFont = overlayFontExpr,
                textSize = overlaySizeExpr,
                textColor = const(Color.White),
                textOpacity = alphaExpr,
                textHaloColor = const(Color.Black),
                textHaloWidth = const(1.dp),
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
                onClick = onMarkerFeatureClick,
            )
            SymbolLayer(
                id = "my-heading-symbol-layer",
                source = source,
                filter = ExprFeature.get("rotating").cast<BooleanValue>(),
                iconImage = iconImageExpr,
                iconColor = iconColorExpr,
                iconOpacity = alphaExpr,
                iconHaloColor = outlineColorExpr,
                iconHaloWidth = const(1.dp),
                iconRotate = coalesce(ExprFeature.get("rotation").cast<FloatValue>(), fallback = const(0f)),
                iconRotationAlignment = const(IconRotationAlignment.Map),
                iconSize = sizeExpr,
                iconAllowOverlap = const(true),
                iconIgnorePlacement = const(true),
                textField = overlayTextExpr,
                textFont = overlayFontExpr,
                textSize = overlaySizeExpr,
                textColor = const(Color.White),
                textOpacity = alphaExpr,
                textHaloColor = const(Color.Black),
                textHaloWidth = const(1.dp),
                textAllowOverlap = const(true),
                textIgnorePlacement = const(true),
                onClick = onMarkerFeatureClick,
            )
        }


        LocationIndicatorLayer(id = "my-location", locationState = locationState)
    }
    DisposableEffect(cameraController, mapState) {
        cameraController.mapState = mapState
        onDispose { cameraController.mapState = null }
    }

    LaunchedEffect(locationState.lastLocation, centerOnUserLocationRequested) {
        val location = locationState.lastLocation
        if (centerOnUserLocationRequested && location != null) {
            centerOnUserLocationRequested = false
            mapState.animateCamera(CameraUpdate(center = location.position, padding = DpPadding.Zero))
        }
    }

    // The latest callback: the effect below outlives recompositions that pass a new one.
    val currentOnCameraIdle by rememberUpdatedState(onCameraIdle)
    LaunchedEffect(mapState) {
        snapshotFlow { mapState.isCameraMoving }
            .drop(1) // skip the initial value so we don't immediately re-report initialCameraPosition
            .filter { !it }
            .collect {
                val position = mapState.cameraPosition
                currentOnCameraIdle(position.center.latitude, position.center.longitude, position.zoom)
            }
    }

    // Reads the latest onDeselect without rebuilding the interactions (which would cancel a
    // gesture in progress) on every recomposition.
    val currentOnDeselect by rememberUpdatedState(onDeselect)
    val interactions = remember {
        MapInteractions {
            callbacks { click { onUnhandled { currentOnDeselect(); ClickResult.Pass } } }
        }
    }

    Box(modifier = modifier) {
        MaplibreMap(
            modifier = Modifier.fillMaxSize(),
            state = mapState,
            interactions = interactions,
        )

        // The selected marker's card
        selectedMarker?.let { marker ->
            // keyed: another marker's card starts at its top
            key(marker.id) {
                MapInfoWindow(
                    mapState = mapState,
                    targetPosition = Position(marker.lon, marker.lat),
                    markerIconSize = marker.iconSize,
                ) {
                    selectedMarkerComposable()
                }
            }
        }

        if (showLocationButton) {
            SmallFloatingActionButton(
                onClick = requestUserLocation,
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "Show my location")
            }
        }
    }
}
