package com.mantz_it.rfanalyzer.digiext.adsb

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer.ui.composable.feetToMetersString
import com.mantz_it.rfanalyzer.ui.composable.fpmToMetersPerSecondString
import com.mantz_it.rfanalyzer.ui.composable.knotsToKmhString
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketSpec
import com.mantz_it.rfanalyzer.digiext.db.ObjectStores
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer_ipc.digiext.AdsbPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import com.mantz_it.rfanalyzer.digiext.db.LatLon
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore

/**
 * <h1>RF Analyzer - ADS-B Data</h1>
 *
 * Module:      AdsbData.kt
 * Description: The data layer of ADS-B, which is decoded in the extension: [AdsbSpec], which reads
 *              the downlink format and address from the raw bytes; the tracked aircraft
 *              ([AdsbAircraft], [AdsbAircraftFamily]) and the fold of a frame into it, with the CPR
 *              decoding of even/odd position pairs; the emitter categories; and an aircraft's
 *              summary text and its frames grouped by payload type.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

// How far apart an even/odd CPR pair may be for a global decode: a fixed 10 s, simpler than the
// speed-adaptive window of a full aggregator. ADSB_MAX_PLAUSIBLE_SPEED_MPS (~778 kt) is a generous
// ceiling that only rejects an obviously wrong CPR decode, not a check of the flight envelope.
private const val CPR_PAIRING_WINDOW_MS = 10_000L
private const val ADSB_MAX_PLAUSIBLE_SPEED_MPS = 400.0
/**
 * How long after its last frame an aircraft still counts as present - readsb's TRACK_EXPIRE
 * convention. One constant in three roles so they cannot drift apart: the table's dimming threshold
 * (`AdsbPresenter.staleThresholdMs`), the fading of a Map marker, and how long an aircraft's trail
 * and decode state stay in memory ([AdsbAircraftFamily]).
 */
const val ADSB_STALE_TIMEOUT_MS = 5 * 60_000L

/**
 * ADS-B emitter category from the identification message (TC 1-4): set A for TC 4, B for TC 3, C
 * for TC 2, numbered by the message's category field (DO-260B). The silhouette and relative icon
 * size follow from it.
 */
enum class AdsbCategory(val label: String, val shape: AdsbIconShape, val iconSize: Float) {
    A1("Light (<7000kg)", AdsbIconShape.LIGHT, 1.3f),
    A2("Medium 1 (7000-34000kg)", AdsbIconShape.JET, 1.7f),
    A3("Medium 2 (34000-136000kg)", AdsbIconShape.JET, 2.0f),
    A4("High vortex aircraft", AdsbIconShape.JET, 2.2f),
    A5("Heavy (>136000kg)", AdsbIconShape.HEAVY, 2.4f),
    A6("High performance/high speed", AdsbIconShape.HIGH_PERFORMANCE, 1.6f),
    A7("Rotorcraft", AdsbIconShape.ROTORCRAFT, 1.5f),
    B1("Glider/sailplane", AdsbIconShape.GLIDER, 1.1f),
    B2("Lighter-than-air", AdsbIconShape.BALLOON, 1.1f),
    B3("Parachutist/skydiver", AdsbIconShape.CANOPY, 1.1f),
    B4("Ultralight/hang-glider/paraglider", AdsbIconShape.CANOPY, 1.1f),
    B6("UAV", AdsbIconShape.UAV, 1.0f),
    // No silhouette of its own.
    B7("Space/transatmospheric vehicle", AdsbIconShape.GENERIC, 1.8f),
    C1("Surface emergency vehicle", AdsbIconShape.VEHICLE, 1.0f),
    C2("Surface service vehicle", AdsbIconShape.VEHICLE, 1.0f),
    C3("Ground obstruction", AdsbIconShape.OBSTRUCTION, 1.0f);

    companion object {
        /** Null for category 0 ("no information") and the reserved/unassigned codes. */
        fun of(typeCode: Int, category: Int): AdsbCategory? {
            if (typeCode !in 2..4) return null
            val name = "${'A' + (4 - typeCode)}$category"
            return entries.firstOrNull { it.name == name }
        }
    }
}

/** One CPR-encoded half of a position, waiting for the other half. */
data class CprHalf(val lat: Int, val lon: Int, val timestampMs: Long)

/**
 * An ADS-B aircraft (ICAO address, the object's key): what its frames have said so far. The
 * position is the last one accepted by [withPosition]'s plausibility check.
 *
 * The CPR halves are decode state, not stored: a restored aircraft keeps its last position and
 * waits for a fresh even/odd pair.
 */
@Serializable
data class AdsbAircraft(
    val callsign: String? = null,
    val category: AdsbCategory? = null,
    val onGround: Boolean? = null,
    val lat: Double? = null,
    val lon: Double? = null,
    val positionMs: Long = 0,
    /** From the most recent airborne position frame; null when Gillham-coded or absent. */
    val altitudeFt: Int? = null,
    val speedKt: Int? = null,
    val headingDeg: Double? = null,
    val vertRateFpm: Int? = null,
    val squawk: String? = null,
    val emergencyState: Int? = null,
    val adsbVersion: Int? = null,
    val nacp: Int? = null,
    val sil: Int? = null,
    /** Its address is an ICAO address ([hasIcaoAddress]): true once a frame said so, false while
     *  only DF 18 frames of another code format came (anonymous, TIS-B, ADS-R - no country), null
     *  when unknown (treated as ICAO). */
    val addressIsIcao: Boolean? = null,
    @Transient val airborneEven: CprHalf? = null,
    @Transient val airborneOdd: CprHalf? = null,
    @Transient val surfaceEven: CprHalf? = null,
    @Transient val surfaceOdd: CprHalf? = null,
) {
    // 7500=hijack, 7600=radio failure, 7700=general emergency - the three universal emergency squawks.
    val isEmergency: Boolean get() = (emergencyState != null && emergencyState != 0) || squawk in EMERGENCY_SQUAWKS

    /** The state its address was allocated to; null for a non-ICAO address. */
    internal fun allocation(key: String): IcaoBlock? = if (addressIsIcao == false) null else key.toIntOrNull(16)?.let(::icaoBlockOf)

    /** Accepts a decoded position unless it implies an implausible jump from the last one (see
     *  ADSB_MAX_PLAUSIBLE_SPEED_MPS); always accepts when there is no earlier position. */
    fun withPosition(newLat: Double, newLon: Double, timestampMs: Long): AdsbAircraft {
        if (lat != null && lon != null && positionMs > 0) {
            val dtSeconds = (timestampMs - positionMs) / 1000.0
            if (dtSeconds > 0 && haversineMeters(lat, lon, newLat, newLon) / dtSeconds > ADSB_MAX_PLAUSIBLE_SPEED_MPS) return this
        }
        return copy(lat = newLat, lon = newLon, positionMs = timestampMs)
    }
}

private val EMERGENCY_SQUAWKS = setOf("7500", "7600", "7700")

/**
 * Orders aircraft the way their identity cell reads ([TrackedObject.displayName]): those with a
 * callsign first, by callsign, then the ones known by ICAO address only. The hex is fixed-width,
 * so its text order is its numeric order.
 */
internal fun adsbIdentitySortKey(aircraft: TrackedObject<AdsbAircraft>): String =
    aircraft.state.callsign?.let { "0$it" } ?: "1${aircraft.key}"

internal val AdsbAircraftFamily = ObjectFamily(
    kind = ObjectKind.ADSB_AIRCRAFT,
    serializer = AdsbAircraft.serializer(),
    displayName = { key, aircraft -> aircraft.callsign ?: key },
    position = { aircraft -> aircraft.lat?.let { lat -> aircraft.lon?.let { lon -> LatLon(lat, lon) } } },
    idleTimeoutMs = ADSB_STALE_TIMEOUT_MS,
)

/** A CRC-validated Mode S / ADS-B frame. */
typealias AdsbFrame = StoredPacket<Unit, AdsbPayload>

/** The downlink format: the first five bits of the frame. */
val AdsbFrame.df: Int get() = adsbDf(raw)

/** The aircraft address of a DF 11/17/18 frame; -1 for the other formats, which XOR it into their
 *  parity field, where it cannot be read without knowing the interrogator. */
val AdsbFrame.icao: Int get() = adsbIcao(raw)

private fun adsbDf(raw: Bytes?): Int = raw?.takeIf { it.size > 0 }?.let { (it[0].toInt() and 0xFF) ushr 3 } ?: -1

private fun adsbIcao(raw: Bytes?): Int {
    if (raw == null || raw.size < 4) return -1
    val df = adsbDf(raw)
    if (df != 11 && df != 17 && df != 18) return -1
    return ((raw[1].toInt() and 0xFF) shl 16) or ((raw[2].toInt() and 0xFF) shl 8) or (raw[3].toInt() and 0xFF)
}

/** Whether the frame's address is an ICAO aircraft address: always in DF 11 and DF 17, in DF 18
 *  only with code format 0 - the others carry an anonymous, TIS-B or ADS-R address, or may; null
 *  for a frame without a readable address. */
val AdsbFrame.hasIcaoAddress: Boolean? get() = when (df) {
    11, 17 -> true
    18 -> (raw!![0].toInt() and 0x07) == 0
    else -> null
}

/** The `objectKey` of an aircraft and of every frame it sent, or `""` for a frame whose address
 *  field was unreadable and so belongs to no aircraft. */
internal fun adsbObjectKey(icao: Int): String = if (icao >= 0) "%06X".format(Locale.US, icao) else ""

internal suspend fun applyAdsbFrame(aircraft: ObjectStore<AdsbAircraft>, frame: AdsbFrame) {
    val key = frame.objectKey?.ifEmpty { null } ?: return
    aircraft.update(key, frame.timestampMs, frame.knownFrequencyHz, create = ::AdsbAircraft) { it.fold(frame) }
}

/** ADS-B is decoded in the extension; the downlink format and address are read from the raw bytes. */
internal object AdsbSpec : DigitalPacketSpec<AdsbPacket, Unit, AdsbPayload>(
    mode = DigitalMode.ADSB,
    phySerializer = null,
    dataSerializer = AdsbPayload.serializer(),
) {
    override fun phyOf(packet: AdsbPacket) = Unit
    override fun dataOf(packet: AdsbPacket, phy: Unit) = packet.payload

    override fun objectKey(raw: Bytes?, phy: Unit, data: AdsbPayload) = adsbObjectKey(adsbIcao(raw))
    override suspend fun fold(packet: AdsbFrame, objects: ObjectStores) = applyAdsbFrame(objects[AdsbAircraftFamily], packet)
}

private fun AdsbAircraft.fold(frame: AdsbFrame): AdsbAircraft = withAddressKind(frame.hasIcaoAddress).foldPayload(frame)

private fun AdsbAircraft.withAddressKind(icao: Boolean?): AdsbAircraft = when {
    icao == null || addressIsIcao == true || addressIsIcao == icao -> this
    else -> copy(addressIsIcao = icao)
}

private fun AdsbAircraft.foldPayload(frame: AdsbFrame): AdsbAircraft {
    val ts = frame.timestampMs
    return when (val payload = frame.data) {
        // The callsign field is padded to 8 characters ('_' or spaces).
        is AircraftIdPayload -> copy(callsign = adsbCallsign(payload.callsign), category = AdsbCategory.of(payload.typeCode, payload.category))
        is AirbornePositionPayload -> {
            val half = CprHalf(payload.cprLat, payload.cprLon, ts)
            val next = copy(
                onGround = false, altitudeFt = payload.altitudeFt,
                airborneEven = if (payload.cprOddFrame) airborneEven else half,
                airborneOdd = if (payload.cprOddFrame) half else airborneOdd,
            )
            val even = next.airborneEven
            val odd = next.airborneOdd
            if (even != null && odd != null && abs(even.timestampMs - odd.timestampMs) <= CPR_PAIRING_WINDOW_MS) {
                cprDecodeAirborne(even, odd)?.let { (lat, lon) -> next.withPosition(lat, lon, ts) } ?: next
            } else next
        }
        is SurfacePositionPayload -> {
            val half = CprHalf(payload.cprLat, payload.cprLon, ts)
            val next = copy(
                onGround = true,
                speedKt = payload.groundSpeedKt?.roundToInt() ?: speedKt,
                headingDeg = payload.trackDeg ?: headingDeg,
                surfaceEven = if (payload.cprOddFrame) surfaceEven else half,
                surfaceOdd = if (payload.cprOddFrame) half else surfaceOdd,
            )
            val even = next.surfaceEven
            val odd = next.surfaceOdd
            // Surface CPR needs a reference position to resolve its quadrant: the last known one.
            if (lat != null && lon != null && even != null && odd != null && abs(even.timestampMs - odd.timestampMs) <= CPR_PAIRING_WINDOW_MS) {
                cprDecodeSurface(even, odd, lat, lon)?.let { (newLat, newLon) -> next.withPosition(newLat, newLon, ts) } ?: next
            } else next
        }
        is AirborneVelocityPayload -> copy(onGround = false, speedKt = payload.speedKt, headingDeg = payload.headingDeg, vertRateFpm = payload.vertRateFpm)
        is AircraftStatusPayload -> copy(squawk = payload.squawk, emergencyState = payload.emergencyState)
        is OperationStatusPayload -> copy(adsbVersion = payload.version, nacp = payload.nacp, sil = payload.sil)
        else -> this
    }
}

private fun cprNL(lat: Double): Int {
    if (abs(lat) >= 87.0) return 1
    val a = 1.0 - cos(PI / 30.0)   // 1 - cos(π / (2 * Nz)) with Nz = 15
    val b = cos(PI / 180.0 * abs(lat)).pow(2)
    return floor(2.0 * PI / acos((1.0 - a / b).coerceIn(-1.0, 1.0))).toInt().coerceIn(1, 59)
}

/** Airborne position from an even/odd pair (global decoding, no reference needed); the newer half sets the position. */
internal fun cprDecodeAirborne(even: CprHalf, odd: CprHalf): Pair<Double, Double>? {
    val cprLat0 = even.lat / 131072.0
    val cprLat1 = odd.lat  / 131072.0
    val j = floor(59 * cprLat0 - 60 * cprLat1 + 0.5).toInt()
    var lat0 = 6.0          * (((j % 60) + 60) % 60 + cprLat0)
    var lat1 = (360.0 / 59) * (((j % 59) + 59) % 59 + cprLat1)
    if (lat0 >= 270) lat0 -= 360
    if (lat1 >= 270) lat1 -= 360
    if (cprNL(lat0) != cprNL(lat1)) return null
    val useEven = even.timestampMs >= odd.timestampMs
    val lat = if (useEven) lat0 else lat1
    val nl = cprNL(lat)
    val n = maxOf(if (useEven) nl else nl - 1, 1)
    val cprLon0 = even.lon / 131072.0
    val cprLon1 = odd.lon  / 131072.0
    val m = floor(cprLon0 * (nl - 1) - cprLon1 * nl + 0.5).toInt()
    val cprLon = if (useEven) cprLon0 else cprLon1
    var lon = (360.0 / n) * (((m % n) + n) % n + cprLon)
    if (lon >= 180) lon -= 360
    return Pair(lat, lon)
}

/**
 * Surface position from an even/odd pair. The surface zones are a quarter of the airborne ones
 * (DO-260B A.1.7), so the pair fixes the position only up to 90° in latitude and in longitude;
 * the reference position ([refLat]/[refLon], within 45° of the aircraft) picks the solution.
 */
internal fun cprDecodeSurface(even: CprHalf, odd: CprHalf, refLat: Double, refLon: Double): Pair<Double, Double>? {
    val cprLat0 = even.lat / 131072.0
    val cprLat1 = odd.lat  / 131072.0
    val j = floor(59 * cprLat0 - 60 * cprLat1 + 0.5).toInt()
    // 0..90°, the northern solutions (90/(4*15) and 90/(4*15-1) per zone)
    val latEven = nearestHemisphere(1.5           * (((j % 60) + 60) % 60 + cprLat0), refLat)
    val latOdd  = nearestHemisphere((90.0 / 59.0) * (((j % 59) + 59) % 59 + cprLat1), refLat)
    if (cprNL(latEven) != cprNL(latOdd)) return null
    val useEven = even.timestampMs >= odd.timestampMs
    val lat = if (useEven) latEven else latOdd

    val nl = cprNL(lat)
    val n = maxOf(if (useEven) nl else nl - 1, 1)
    val cprLon0 = even.lon / 131072.0
    val cprLon1 = odd.lon  / 131072.0
    val m = floor(cprLon0 * (nl - 1) - cprLon1 * nl + 0.5).toInt()
    val cprLon = if (useEven) cprLon0 else cprLon1
    val lonBase = (90.0 / n) * (((m % n) + n) % n + cprLon)
    // the four solutions, 90° apart, in -180..180°: the one nearest the reference (across ±180° too)
    val lon = (0 until 4).map { wrapLongitude(lonBase + 90.0 * it) }.minBy { abs(wrapLongitude(it - refLon)) }
    return Pair(lat, lon)
}

/** The surface latitude solution [latNorth] (0..90°) or the southern one 90° below it, whichever is nearer [refLat]. */
private fun nearestHemisphere(latNorth: Double, refLat: Double): Double =
    if (abs(latNorth - refLat) <= abs(latNorth - 90.0 - refLat)) latNorth else latNorth - 90.0

/** [lon] in -180..180°. */
private fun wrapLongitude(lon: Double): Double = lon - 360.0 * floor((lon + 180.0) / 360.0)

/** Great-circle distance in meters - used only for the coarse implausible-jump reject in
 *  [AdsbAircraft.withPosition], not for any precision-sensitive purpose. */
private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

/** Plain-text summary for the "Copy summary" action (the JSON export is a separate action). */
fun TrackedObject<AdsbAircraft>.toSummaryText(): String {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    return with(state) { buildString {
        append("Aircraft $key")
        callsign?.let { append(" ($it)") }
        appendLine()
        allocation(key)?.let { appendLine("Country: ${it.name}") }
        category?.let { appendLine("Category: ${it.label}") }
        onGround?.let { appendLine(if (it) "Status: on ground" else "Status: airborne") }
        altitudeFt?.let { appendLine("Altitude: $it ft (${it.feetToMetersString()})") }
        if (speedKt != null || headingDeg != null || vertRateFpm != null) {
            append("Speed: ${speedKt?.let { "$it kt (${it.knotsToKmhString()})" } ?: "?"}")
            append(", Heading: ${headingDeg?.let { "%.0f°".format(Locale.US, it) } ?: "?"}")
            append(", Vertical rate: ${vertRateFpm?.let { "${if (it >= 0) "+" else ""}$it fpm (${it.fpmToMetersPerSecondString()})" } ?: "?"}")
            appendLine()
        }
        squawk?.let {
            append("Squawk: $it")
            if (isEmergency) append(" (EMERGENCY)")
            appendLine()
        }
        if (lat != null && lon != null) appendLine("Position: %.5f, %.5f".format(Locale.US, lat, lon))
        appendLine("Last seen: ${timeFormat.format(Date(lastSeenMs))} ($packetCount messages)")
    }.trim() }
}

/** One payload type's worth of an aircraft's frames, shown as one row of its frame list ([groupAdsbFramesByPayloadType]). */
data class AdsbPayloadTypeSummary(
    val typeLabel: String,
    val latestFrame: AdsbFrame,
    val frames: List<AdsbFrame>,
)

/** Label per [AdsbPayload] subtype. Airborne and surface position stay **separate** rows - they
 *  are distinct message types with different fields, and an aircraft rarely transmits both. */
internal fun adsbPayloadTypeLabel(payload: AdsbPayload): String = when (payload) {
    is AircraftIdPayload -> "ID"
    is AirbornePositionPayload -> "Pos (air)"
    is SurfacePositionPayload -> "Pos (surface)"
    is AirborneVelocityPayload -> "Vel"
    is AircraftStatusPayload -> "Status"
    is TargetStateAndStatusPayload -> "TSS"
    is OperationStatusPayload -> "OpStatus"
    is ModesCAllCallPayload -> "AllCall"
    is UnknownAdsbPayload -> "Unknown"
}

/**
 * Fixed row order of the grouped frame list, most useful first, the same for every aircraft so a
 * field group always sits in the same place. A label missing here sorts last.
 */
private val TYPE_PRIORITY = listOf(
    "ID", "Pos (air)", "Pos (surface)", "Vel", "TSS", "Status", "OpStatus", "AllCall", "Unknown",
)

private fun typePriorityOf(label: String): Int = TYPE_PRIORITY.indexOf(label).let { if (it < 0) Int.MAX_VALUE else it }

/**
 * Groups one aircraft's frames by payload type, one row per type rather than per frame: a single
 * squitter says little, the latest frame of each field group is what matters.
 *
 * [frames] must be newest-first (the order of the object's detail query): a group's first frame is
 * its `latestFrame`. The groups are in [TYPE_PRIORITY] order, not arrival order, so rows do not
 * move between refreshes.
 */
fun groupAdsbFramesByPayloadType(frames: List<AdsbFrame>): List<AdsbPayloadTypeSummary> =
    frames.groupBy { adsbPayloadTypeLabel(it.data) }
        .map { (label, groupFrames) -> AdsbPayloadTypeSummary(label, groupFrames.first(), groupFrames) }
        .sortedBy { typePriorityOf(it.typeLabel) }

/** A callsign as it is read: without the padding of its 8-character field, null when empty. */
internal fun adsbCallsign(field: String): String? = field.trimEnd('_', ' ').ifEmpty { null }
