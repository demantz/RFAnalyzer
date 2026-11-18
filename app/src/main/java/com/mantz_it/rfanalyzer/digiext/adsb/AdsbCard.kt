package com.mantz_it.rfanalyzer.digiext.adsb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import com.mantz_it.rfanalyzer.digiext.ui.common.RecentPacketsList
import com.mantz_it.rfanalyzer.digiext.ui.common.RecentPacket
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.flightAwareUrl
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
import com.mantz_it.rfanalyzer_ipc.digiext.AdsbPayload
import com.mantz_it.rfanalyzer_ipc.digiext.AirbornePositionPayload
import com.mantz_it.rfanalyzer_ipc.digiext.AirborneVelocityPayload
import com.mantz_it.rfanalyzer_ipc.digiext.AircraftIdPayload
import com.mantz_it.rfanalyzer_ipc.digiext.AircraftStatusPayload
import com.mantz_it.rfanalyzer_ipc.digiext.ModesCAllCallPayload
import com.mantz_it.rfanalyzer_ipc.digiext.OperationStatusPayload
import com.mantz_it.rfanalyzer_ipc.digiext.SurfacePositionPayload
import com.mantz_it.rfanalyzer_ipc.digiext.TargetStateAndStatusPayload
import com.mantz_it.rfanalyzer_ipc.digiext.UnknownAdsbPayload
import kotlin.math.abs
import kotlin.math.roundToInt
import com.mantz_it.rfanalyzer.digiext.db.markerId
import com.mantz_it.rfanalyzer.digiext.ui.MapLayer
import com.mantz_it.rfanalyzer.digiext.ui.STALE_MARKER_ALPHA
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.CardListPadding
import com.mantz_it.rfanalyzer.digiext.ui.common.DigitalTextStyles
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.Fields
import com.mantz_it.rfanalyzer.digiext.ui.common.IdentityText
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketCard
import com.mantz_it.rfanalyzer.digiext.ui.common.PayloadText
import com.mantz_it.rfanalyzer.digiext.ui.common.RadioLine
import com.mantz_it.rfanalyzer.digiext.ui.common.RawBytes
import com.mantz_it.rfanalyzer.digiext.ui.common.WarningText
import com.mantz_it.rfanalyzer.digiext.ui.common.feetToMeters
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.formatPosition
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.common.packetActions
import com.mantz_it.rfanalyzer.digiext.ui.trackedObjectActions
import com.mantz_it.rfanalyzer.ui.composable.MapMarker
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * <h1>RF Analyzer - ADS-B Card</h1>
 *
 * Module:      AdsbCard.kt
 * Description: How ADS-B reads: the frame card (the aircraft, the message type in words, the
 *              decoded values), the aircraft's facts and actions, and the Map layer.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** A flight level: "FL316"; below 1000 ft the feet. */
internal fun flightLevel(feet: Int): String = if (feet >= 1000) "FL%03d".format(Locale.US, ((feet + 50) / 100)) else "$feet ft"

/** "−1472 ft/min" with its sign. */
private fun verticalRate(fpm: Int): String = (if (fpm > 0) "+" else if (fpm < 0) "−" else "") + "${abs(fpm)} ft/min"

/** What kind of message a frame is, in words. */
internal fun adsbTypeName(payload: AdsbPayload): String = when (payload) {
    is AircraftIdPayload -> "Identification"
    is AirbornePositionPayload -> "Position"
    is SurfacePositionPayload -> "Surface position"
    is AirborneVelocityPayload -> "Velocity"
    is AircraftStatusPayload -> "Status"
    is TargetStateAndStatusPayload -> "Target state"
    is OperationStatusPayload -> "Operation status"
    is ModesCAllCallPayload -> "All-call reply"
    is UnknownAdsbPayload -> "Unknown type"
}

/** What a frame says, in one line: "FL316 · 9632 m", "425 kt · 191° · −1472 ft/min". */
fun compactAdsbPayloadText(payload: AdsbPayload): String = when (payload) {
    is AircraftIdPayload -> metaLine(adsbCallsign(payload.callsign), AdsbCategory.of(payload.typeCode, payload.category)?.label)
    is AirbornePositionPayload -> metaLine(payload.altitudeFt?.let { "${flightLevel(it)} · ${feetToMeters(it)}" } ?: "Altitude unknown", payload.altSource.takeIf { it == "GNSS" })
    is SurfacePositionPayload -> metaLine("On the ground", payload.groundSpeedKt?.let { "${it.roundToInt()} kt" }, payload.trackDeg?.let { "${it.roundToInt()}°" })
    is AirborneVelocityPayload -> metaLine(payload.speedKt?.let { "$it kt" }, payload.headingDeg?.let { "${it.roundToInt()}°" }, payload.vertRateFpm?.let(::verticalRate))
    is AircraftStatusPayload -> metaLine("Squawk ${payload.squawk}", emergencyLabel(payload.emergencyState))
    is TargetStateAndStatusPayload -> metaLine(
        payload.targetAltitudeFt?.let { "Target ${flightLevel(it)}" }, payload.targetHeadingDeg?.let { "heading ${it.roundToInt()}°" },
        payload.qnhHpa?.let { "QNH %.0f hPa".format(Locale.US, it) },
        listOfNotNull("AP".takeIf { payload.autopilotEngaged == true }, "VNAV".takeIf { payload.vnavEngaged == true },
            "ALT HOLD".takeIf { payload.altitudeHoldEngaged == true }, "APPR".takeIf { payload.approachModeEngaged == true }).joinToString(" ").ifEmpty { null },
    )
    is OperationStatusPayload -> "ADS-B v${payload.version} · NACp ${payload.nacp} · SIL ${payload.sil}"
    is ModesCAllCallPayload -> "Capability ${payload.capability}: ${capabilityLabel(payload.capability)}"
    is UnknownAdsbPayload -> if (payload.typeCode >= 0) "Type code ${payload.typeCode}" else "Not an extended squitter"
}

/** The fields of one frame, for its opened card. */
private fun adsbPayloadFields(payload: AdsbPayload): List<Field> = fields {
    when (payload) {
        is AircraftIdPayload -> {
            add("Callsign", adsbCallsign(payload.callsign))
            add("Category", AdsbCategory.of(payload.typeCode, payload.category)?.let { "${it.name} · ${it.label}" } ?: "not reported")
        }
        is AirbornePositionPayload -> {
            add("Altitude", payload.altitudeFt?.let { "${flightLevel(it)} · $it ft · ${feetToMeters(it)} (${payload.altSource})" })
            add("Position", "CPR ${if (payload.cprOddFrame) "odd" else "even"} half - decoded with the other half on the aircraft")
        }
        is SurfacePositionPayload -> {
            add("Speed", payload.groundSpeedKt?.let { "%.0f kt".format(Locale.US, it) })
            add("Track", payload.trackDeg?.let { "%.1f°".format(Locale.US, it) })
            add("Position", "CPR ${if (payload.cprOddFrame) "odd" else "even"} half")
        }
        is AirborneVelocityPayload -> {
            add("Speed", payload.speedKt?.let { "$it kt (${Math.round(it * 1.852)} km/h)" })
            // subtypes 1-2 give the ground speed vector (its direction is the track), 3-4 the heading
            add(if (payload.subtype <= 2) "Track" else "Heading", payload.headingDeg?.let { "%.0f°".format(Locale.US, it) })
            add("Vertical rate", payload.vertRateFpm?.let { "${verticalRate(it)} (${payload.vertRateSource})" })
        }
        is AircraftStatusPayload -> {
            add("Squawk", payload.squawk)
            add("Emergency", emergencyLabel(payload.emergencyState))
        }
        is TargetStateAndStatusPayload -> {
            add("Target altitude", payload.targetAltitudeFt?.let { "${flightLevel(it)} (${payload.targetAltitudeSource})" })
            add("Target heading", payload.targetHeadingDeg?.let { "%.0f°".format(Locale.US, it) })
            add("QNH", payload.qnhHpa?.let { "%.1f hPa".format(Locale.US, it) })
            // the mode flags are null when the sender didn't fill them in: unknown, not "off"
            val modes = listOf(payload.autopilotEngaged, payload.vnavEngaged, payload.altitudeHoldEngaged, payload.approachModeEngaged)
            add("Autopilot", if (modes.all { it == null }) null else listOfNotNull(
                "AP".takeIf { payload.autopilotEngaged == true }, "VNAV".takeIf { payload.vnavEngaged == true },
                "ALT HOLD".takeIf { payload.altitudeHoldEngaged == true }, "APPR".takeIf { payload.approachModeEngaged == true },
            ).joinToString(", ").ifEmpty { "off" })
            add("TCAS", if (payload.tcasOperational) "operational" else "off")
            add("NACp", payload.nacp.toString())
        }
        is OperationStatusPayload -> {
            add("Version", "ADS-B v${payload.version}")
            add("Accuracy", "NACp ${payload.nacp} · SIL ${payload.sil} ${if (payload.silPerHour) "per hour" else "per sample"} · NIC supplement ${payload.nicSupplementA}")
            add("Vertical accuracy", gvaLabel(payload.verticalAccuracy))
            add("Reported", if (payload.subtypeSurface) "on the ground" else "airborne")
        }
        is ModesCAllCallPayload -> add("Capability", "${payload.capability}: ${capabilityLabel(payload.capability)}")
        is UnknownAdsbPayload -> add("Type code", payload.typeCode.takeIf { it >= 0 }?.toString())
    }
}

private fun capabilityLabel(capability: Int) = when (capability) {
    0 -> "Level 1 transponder"
    4 -> "Level 2+, on the ground"
    5 -> "Level 2+, airborne"
    6 -> "Level 2+, ground or airborne"
    7 -> "Downlink request / alert or SPI"
    else -> "Reserved"
}

private fun gvaLabel(gva: Int) = when (gva) {
    1 -> "within 150 m"
    2 -> "within 45 m"
    else -> "unknown or worse than 150 m"
}

private fun emergencyLabel(state: Int): String? = when (state) {
    0 -> null
    1 -> "Emergency"; 2 -> "Medical emergency"; 3 -> "Minimum fuel"
    4 -> "No communication"; 5 -> "Unlawful interference"; 6 -> "Downed aircraft"
    else -> "Emergency ($state)"
}

/**
 * One frame. Header: the aircraft (callsign where the frame names it, else the ICAO address), what
 * kind of message, the time. Payload: its values in words. Opened: all fields, the frame line, bytes.
 */
@Composable
fun AdsbFrameCard(
    frame: AdsbFrame,
    expanded: Boolean,
    onExpand: () -> Unit,
    actions: CardActions,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = CardListPadding,
) {
    val icao = if (frame.icao >= 0) "%06X".format(Locale.US, frame.icao) else null
    val callsign = (frame.data as? AircraftIdPayload)?.callsign?.let(::adsbCallsign)
    val allocation = frame.icao.takeIf { frame.hasIcaoAddress == true }?.let(::icaoBlockOf)
    PacketCard(
        expanded = expanded,
        onExpand = onExpand,
        timestampMs = frame.timestampMs,
        modifier = modifier,
        outerPadding = outerPadding,
        identity = { IdentityText(callsign ?: icao ?: "DF${frame.df}", leading = allocation?.flag?.let { { FlagText(it) } }) },
        subtitle = metaLine(icao.takeIf { callsign != null }, adsbTypeName(frame.data)),
        warning = (frame.data as? AircraftStatusPayload)?.let { status -> emergencyLabel(status.emergencyState)?.let { { WarningText(it) } } },
        payload = { open -> PayloadText(compactAdsbPayloadText(frame.data), open) },
        expandedContent = {
            Fields(listOfNotNull(allocation?.let { Field("Country", it.text) }) + adsbPayloadFields(frame.data))
            RadioLine("DF${frame.df}", icao?.let { "ICAO $it" }, frame.raw?.let { "${it.size} B" })
            RawBytes(frame.raw)
        },
        actions = packetActions(frame, actions, specific = listOfNotNull(
            icao?.let { PacketMenuAction(label = "FlightAware", prominent = true) { actions.onOpenUrl(flightAwareUrl(it)) } },
            icao?.let { copyTextAction("Copy ICAO address", it) },
        )),
    )
}

@Composable
internal fun adsbAircraftActions(
    aircraft: TrackedObject<AdsbAircraft>,
    onShareJson: (json: String, filenamePrefix: String) -> Unit,
    onTrackOnMap: () -> Unit,
    onOpenUrl: (String) -> Unit,
    shareMessages: ((filenamePrefix: String) -> Unit)? = null,
    withMap: Boolean = true,
): List<PacketMenuAction> = trackedObjectActions(
    aircraft, AdsbAircraftFamily, onShareJson, onTrackOnMap, onOpenUrl, shareMessages, withMap = withMap,
    specific = listOfNotNull(
        PacketMenuAction(label = "FlightAware", prominent = true) { onOpenUrl(flightAwareUrl(aircraft.key)) },
        copyTextAction("Copy summary", aircraft.toSummaryText()),
        copyTextAction("Copy ICAO address", aircraft.key),
        aircraft.state.callsign?.let(::adsbCallsign)?.let { copyTextAction("Copy callsign", it) },
    ),
)

/** What an aircraft's row says beyond its columns: only what is out of the ordinary. */
internal fun adsbRowText(aircraft: TrackedObject<AdsbAircraft>): String? = metaLine(
    aircraft.state.squawk?.takeIf { aircraft.state.isEmergency }?.let { "Squawk $it" },
    aircraft.state.emergencyState?.let(::emergencyLabel),
    "On the ground".takeIf { aircraft.state.onGround == true },
).ifEmpty { null }

/** What is known about an aircraft. */
internal fun adsbAircraftFacts(aircraft: TrackedObject<AdsbAircraft>): List<Field> = fields {
    val s = aircraft.state
    add("ICAO", aircraft.key)
    add("Country", s.allocation(aircraft.key)?.text)
    add("Address", "not an ICAO address (anonymous, TIS-B or ADS-R)".takeIf { s.addressIsIcao == false })
    add("Callsign", s.callsign?.let(::adsbCallsign))
    add("Category", s.category?.label)
    aircraft.lat?.let { lat -> aircraft.lon?.let { lon -> add("Position", formatPosition(lat, lon)) } }
    add("Altitude", s.altitudeFt?.let { "${flightLevel(it)} · $it ft · ${feetToMeters(it)}" })
    add("Speed", s.speedKt?.let { "$it kt (${Math.round(it * 1.852)} km/h)" })
    add("Track", s.headingDeg?.let { "%.0f°".format(Locale.US, it) })
    add("Vertical rate", s.vertRateFpm?.let(::verticalRate))
    add("Squawk", s.squawk, color = if (s.isEmergency) androidx.compose.ui.graphics.Color(0xFFE57373) else androidx.compose.ui.graphics.Color.Unspecified)
    add("Data quality", s.adsbVersion?.let { v -> metaLine("ADS-B v$v", s.nacp?.let { "NACp $it" }, s.sil?.let { "SIL $it" }) })
}

/** A country flag next to an aircraft's identity, in the size of the FT8 flags. */
@Composable
internal fun FlagText(flag: String) = Text(flag, fontSize = 13.sp)

/** One aircraft in the Map subtab's popup. */
@Composable
internal fun TrackedAdsbAircraftCard(
    aircraft: TrackedObject<AdsbAircraft>,
    onClose: () -> Unit,
    onShareJson: (json: String, filenamePrefix: String) -> Unit,
    onTrack: () -> Unit,
    onOpenUrl: (String) -> Unit,
    recent: List<RecentPacket>,
) {
    PacketCard(
        expanded = true,
        onExpand = onClose,
        timestampMs = aircraft.lastSeenMs,
        identity = {
            IdentityText(aircraft.state.callsign?.let(::adsbCallsign) ?: aircraft.key, leading = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    AdsbAircraftIcon(aircraft.state, size = 18.dp)
                    aircraft.state.allocation(aircraft.key)?.flag?.let { FlagText(it) }
                }
            })
        },
        subtitle = aircraft.key.takeIf { aircraft.state.callsign != null },
        warning = if (aircraft.state.isEmergency) { { WarningText("Emergency") } } else null,
        payload = {
            Text(
                metaLine(aircraft.state.altitudeFt?.let(::flightLevel), aircraft.state.speedKt?.let { "$it kt" }, aircraft.state.headingDeg?.let { "${it.roundToInt()}°" }),
                style = DigitalTextStyles.payload, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        },
        expandedContent = {
            Column {
                Fields(adsbAircraftFacts(aircraft).filter { it.label != "ICAO" && it.label != "Callsign" })
                RecentPacketsList(recent)
            }
        },
        actions = adsbAircraftActions(aircraft, onShareJson, onTrack, onOpenUrl, withMap = false),
        outerPadding = PaddingValues(0.dp),
        shortTime = true,
    )
}

/** A line of an aircraft's recent frames: the type and what it said. */
internal fun adsbRecentLine(frame: AdsbFrame): String = "${adsbTypeName(frame.data)}: ${compactAdsbPayloadText(frame.data)}"

/** How long after its last frame an aircraft is still on the map - long enough to see where the
 *  traffic of the last minutes was, short enough that a busy airspace stays readable. Relative to
 *  the newest aircraft, so stored data shows the end of its capture. */
internal const val ADSB_MAP_WINDOW_MS = 15 * 60_000L

internal fun adsbMapLayer(recent: (objectKey: String) -> Flow<List<RecentPacket>>) = MapLayer(
    family = AdsbAircraftFamily,
    recent = recent,
    windowMs = ADSB_MAP_WINDOW_MS,
    marker = { aircraft, lat, lon, newestMs ->
        MapMarker(
            id = aircraft.markerId,
            lat = lat,
            lon = lon,
            title = aircraft.displayName,
            description = metaLine(aircraft.state.altitudeFt?.let(::flightLevel), aircraft.state.speedKt?.let { "$it kt" }),
            iconKey = aircraft.state.iconShape.iconKey,
            color = adsbAltitudeColor(aircraft.state.altitudeFt, aircraft.state.onGround),
            rotationDeg = aircraft.state.headingDeg?.toFloat(),
            iconSize = aircraft.state.mapIconSize(),
            alpha = if (newestMs - aircraft.lastSeenMs > ADSB_STALE_TIMEOUT_MS) STALE_MARKER_ALPHA else 1f,
        )
    },
    popup = { aircraft, recent, map ->
        TrackedAdsbAircraftCard(aircraft = aircraft, onClose = map.onClose, onShareJson = map.onShareJson, onTrack = map.onTrack, onOpenUrl = map.onOpenUrl, recent = recent)
    },
    legend = { AdsbAltitudeLegend(it) },
)
