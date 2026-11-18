package com.mantz_it.rfanalyzer.digiext.rds

import com.mantz_it.rfanalyzer_ipc.digiext.RdsEvent
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketSpec
import com.mantz_it.rfanalyzer.digiext.db.ObjectStores
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.RdsPacket
import kotlin.math.roundToLong
import kotlinx.serialization.Serializable
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore
import java.util.Locale

/**
 * <h1>RF Analyzer - RDS Data</h1>
 *
 * Module:      RdsData.kt
 * Description: The data layer of RDS, which is decoded in the extension: [RdsSpec], the tracked
 *              [RdsStation] (keyed by PI, also for a neighbour only announced through EON) with its
 *              [TmcService], the TMC messages as objects of their own, and the fold of a group into
 *              all of them. Also the accessors of a group's blocks and type.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Geographic scope of a TMC service (AID variant 0's scope flags). */
enum class TmcScope { INTERNATIONAL, NATIONAL, REGIONAL, URBAN }

/**
 * The RDS-TMC service a station carries, assembled from its TMC system information: AID variants
 * 0/1 of the 3A announcement (location table, scope, service ID) and the TMC tuning information of
 * its 8A groups (provider name from variants 4/5, other networks carrying the service from variant
 * 9). The location table and service ID are what a location-name lookup needs.
 */
@Serializable
data class TmcService(
    /** Eight characters, variant 4 filling the first four and variant 5 the last four. */
    val providerName: String = "        ",
    val locationTable: Int? = null,
    val serviceId: Int? = null,
    val scope: Set<TmcScope> = emptySet(),
    /** Other networks carrying TMC: PI (hex) to their service ID. */
    val otherNetworks: Map<String, Int> = emptyMap(),
) {
    fun withEvent(event: RdsEvent): TmcService = when (event) {
        is RdsEvent.AidVariant0 -> copy(
            locationTable = event.locationTable,
            scope = buildSet {
                if (event.international) add(TmcScope.INTERNATIONAL)
                if (event.national) add(TmcScope.NATIONAL)
                if (event.regional) add(TmcScope.REGIONAL)
                if (event.urban) add(TmcScope.URBAN)
            },
        )
        is RdsEvent.AidVariant1 -> copy(serviceId = event.serviceId)
        is RdsEvent.TmcTuningInfo -> when (event.variant) {
            4 -> copy(providerName = rdsChars(event.data2, event.data3) + providerName.substring(4))
            5 -> copy(providerName = providerName.substring(0, 4) + rdsChars(event.data2, event.data3))
            // Block 3: LTN (6 bits), MGS (4 bits), SID (6 bits) of the other network; block 4: its PI.
            9 -> copy(otherNetworks = otherNetworks + ("%04X".format(Locale.US, event.data3) to (event.data2 and 0x3F)))
            else -> this
        }
        else -> this
    }
}

/** The four characters two RDS blocks carry. Characters outside printable ASCII become '?'. */
private fun rdsChars(block3: Int, block4: Int): String =
    intArrayOf(block3 shr 8, block3, block4 shr 8, block4)
        .map { (it and 0xFF).let { code -> if (code in 0x20..0x7E) code.toChar() else '?' } }
        .joinToString("")

/** Well-known Open Data Application IDs. */
internal fun rdsOdaName(aid: Int): String = when (aid) {
    0xCD46, 0xCD47 -> "RDS-TMC"
    0x4BD7 -> "RadioText+"
    0x6552 -> "Enhanced RadioText"
    else -> "AID %04X".format(Locale.US, aid)
}

/**
 * One RDS station, keyed by PI - either a station the receiver is (or was) directly tuned to, or one
 * only known via a neighboring station's EON announcement (see [applyRdsGroup]). Its TMC messages
 * are objects of their own ([TmcMessageFamily]).
 */
@Serializable
data class RdsStation(
    /** Programme type code; its name depends on the region setting ([rdsPtyName]). */
    val pty: Int? = null,
    val programService: String? = null,
    val radioText: String? = null,
    val trafficProgram: Boolean = false,
    val trafficAnnouncement: Boolean = false,
    val music: Boolean? = null,
    val clockTime: RdsEvent.ClockTime? = null,
    val alternativeFrequencies: List<Double> = emptyList(),
    val alternativeFrequenciesComplete: Boolean = false,
    /** EON neighbours this station announces, by PI. */
    val otherNetworks: List<RdsEvent.Eon> = emptyList(),
    /** Open Data Applications announced in 3A groups: group type (e.g. "8A") to AID. */
    val odaApplications: Map<String, Int> = emptyMap(),
    val tmcService: TmcService? = null,
    /** True once this PI has been decoded directly (we were tuned to it at least once), as opposed
     *  to being known only through a neighbor's EON announcement. */
    val tuned: Boolean = false,
    /** When this station was last decoded directly. Unlike the object's last-seen time it is not
     *  advanced by a neighbour's EON announcement, which is what makes it tell "being received now"
     *  - see [isLive]. */
    val lastOwnGroupMs: Long = 0,
    /** Own groups received per group type ("0A", "2A", …) and when each type was last received -
     *  the station's group list, which the stored groups (only the most recent are kept) cannot give. */
    val groupTypeCounts: Map<String, Int> = emptyMap(),
    val groupTypeLastMs: Map<String, Long> = emptyMap(),
) {
    val name: String? get() = programService?.trim()?.takeIf { it.isNotEmpty() }

    /** Applies a group this station broadcast directly - i.e. the receiver is tuned to it. There is
     *  no reset on retune: a retuned-away station simply stops receiving own groups, and [isLive]
     *  turns false. */
    fun withOwnGroup(group: RdsGroup): RdsStation {
        val type = group.typeName
        val counted = copy(
            tuned = true, pty = group.pty, lastOwnGroupMs = group.timestampMs,
            groupTypeCounts = groupTypeCounts + (type to (groupTypeCounts[type] ?: 0) + 1),
            groupTypeLastMs = groupTypeLastMs + (type to group.timestampMs),
        )
        return group.data.events.fold(counted) { station, event -> station.withEvent(event) }
    }

    private fun withEvent(event: RdsEvent): RdsStation = when (event) {
        is RdsEvent.Flags -> copy(trafficProgram = event.tp, trafficAnnouncement = event.ta, music = event.music)
        is RdsEvent.ProgramService -> copy(programService = event.name)
        is RdsEvent.RadioText -> copy(radioText = event.text)
        is RdsEvent.ClockTime -> copy(clockTime = event)
        is RdsEvent.AlternativeFrequencies -> copy(alternativeFrequencies = event.list, alternativeFrequenciesComplete = event.complete)
        // Replaced in place, so the list keeps the order the neighbours were first announced in.
        is RdsEvent.Eon -> copy(
            otherNetworks = if (otherNetworks.any { it.pi == event.pi }) otherNetworks.map { if (it.pi == event.pi) event else it }
            else otherNetworks + event,
        )
        is RdsEvent.AidAnnouncement -> copy(odaApplications = odaApplications + ("${event.applicationGroup}${if (event.groupTypeA) "A" else "B"}" to event.aid))
        is RdsEvent.AidVariant0, is RdsEvent.AidVariant1, is RdsEvent.TmcTuningInfo ->
            copy(tmcService = (tmcService ?: TmcService()).withEvent(event))
        // Folded into their own objects (TMC messages) or raw per-group diagnostics (Raw subtab).
        is RdsEvent.TmcAssembledMessage, is RdsEvent.TmcUserMessage, is RdsEvent.TmcContinuation,
        is RdsEvent.EonVariantRaw -> this
    }

    /**
     * Applies a neighboring station's EON announcement - only whatever partial data the EON block
     * itself carries (PS/PTY/TA/TP/AF); fields an EON event can never supply (radio text, clock
     * time, TMC) are left untouched.
     */
    fun withEon(eon: RdsEvent.Eon): RdsStation = copy(
        programService = eon.ps.takeIf { it.isNotBlank() } ?: programService,
        pty = eon.pty ?: pty,
        trafficAnnouncement = eon.ta ?: trafficAnnouncement,
        trafficProgram = eon.tp ?: trafficProgram,
        alternativeFrequencies = eon.alternativeFrequencies.ifEmpty { alternativeFrequencies },
        alternativeFrequenciesComplete = if (eon.alternativeFrequencies.isNotEmpty()) eon.afComplete else alternativeFrequenciesComplete,
    )
}

/** No own group for this long means the receiver is no longer on the station (tuned away, or
 *  stopped). Groups arrive about 11 times a second, so this only has to ride out a fade and the
 *  ~2 s the Overview's stored aggregate lags behind. */
internal const val RDS_LIVE_WINDOW_MS = 10_000L

/** Whether this is the station being received right now - as opposed to [RdsStation.tuned], which
 *  stays true forever once a station has been decoded directly. */
internal fun RdsStation.isLive(nowMs: Long): Boolean = lastOwnGroupMs != 0L && nowMs - lastOwnGroupMs < RDS_LIVE_WINDOW_MS

/** The `objectKey` of a station and of every group it broadcast: its PI code. */
internal fun rdsObjectKey(pi: Int): String = "%04X".format(Locale.US, pi)

/** The `objectKey` of a TMC message: its station's PI and the ALERT-C message key. */
internal fun tmcObjectKey(pi: Int, messageKey: String): String = "${rdsObjectKey(pi)}/$messageKey"

internal val RdsStationFamily = ObjectFamily(
    kind = ObjectKind.RDS_STATION,
    serializer = RdsStation.serializer(),
    displayName = { key, station -> station.name ?: "PI $key" },
    // A station known only from a neighbour's EON data has no frequency of its own to list.
    fallbackFrequencyHz = { station -> station.alternativeFrequencies.firstOrNull()?.let { (it * 1_000_000).roundToLong() } },
)

/**
 * A TMC (ALERT-C) traffic message, resolved to human-readable text. ALERT-C messages are
 * retransmitted continuously while active, so its object's last-seen time says whether it still is
 * (see [isActiveTmcMessage]).
 */
internal val TmcMessageFamily = ObjectFamily(
    kind = ObjectKind.TMC_MESSAGE,
    serializer = ResolvedTmcMessage.serializer(),
    displayName = { _, message -> message.eventText },
)

/** A message not retransmitted for this long has been withdrawn. */
private const val TMC_ACTIVE_WINDOW_MS = 30 * 60_000L

/** Whether a station's TMC message is still being broadcast as of the station's last own group:
 *  heard within [TMC_ACTIVE_WINDOW_MS] and not past its own expiry. */
internal fun isActiveTmcMessage(message: TrackedObject<ResolvedTmcMessage>, stationLastOwnGroupMs: Long): Boolean {
    if (stationLastOwnGroupMs - message.lastSeenMs > TMC_ACTIVE_WINDOW_MS) return false
    val expiryMinutes = message.state.expiryMinutes ?: return true
    return stationLastOwnGroupMs <= message.firstSeenMs + expiryMinutes * 60_000L
}

/**
 * Folds one group into the station that broadcast it, its TMC messages, and every neighbouring
 * station its EON events announce - which is how one tuned frequency spawns several RDS rows. A
 * neighbour's announcement does not count as a packet of the neighbour's own.
 */
internal suspend fun applyRdsGroup(stations: ObjectStore<RdsStation>, tmcMessages: ObjectStore<ResolvedTmcMessage>, group: RdsGroup) {
    val pi = group.pi
    val timestampMs = group.timestampMs
    stations.update(rdsObjectKey(pi), timestampMs, group.knownFrequencyHz, create = ::RdsStation) { it.withOwnGroup(group) }
    group.data.events.forEach { event ->
        when {
            event is RdsEvent.TmcAssembledMessage -> {
                val resolved = TmcMessageResolver.resolve(event)
                tmcMessages.update(tmcObjectKey(pi, event.messageKey), timestampMs, group.knownFrequencyHz, create = { resolved }) { resolved }
            }
            event is RdsEvent.Eon && event.pi != pi ->
                stations.update(rdsObjectKey(event.pi), timestampMs, frequencyHz = null, counts = false, create = ::RdsStation) { it.withEon(event) }
        }
    }
}

/**
 * What the extension decoded from one RDS group. The decode stays in the extension because it
 * depends on earlier groups too (PS/RT/AF assembly, EON, TMC reassembly).
 */
@Serializable
data class RdsGroupContent(val events: List<RdsEvent>)

/** One RDS group; [raw] holds its four 16-bit blocks A-D, big-endian. */
typealias RdsGroup = StoredPacket<Unit, RdsGroupContent>

/** Block [index] (0 = A ... 3 = D). */
fun RdsGroup.block(index: Int): Int = rdsBlock(raw, index)

val RdsGroup.pi: Int get() = block(0)
val RdsGroup.groupType: Int get() = block(1) ushr 12
/** Version A (block C carries data) or B (block C repeats the PI). */
val RdsGroup.versionA: Boolean get() = (block(1) ushr 11) and 1 == 0
/** "0A", "2B", … */
val RdsGroup.typeName: String get() = "$groupType${if (versionA) "A" else "B"}"

/** Where a group's type sits in its raw bytes as hex - block B's first digit (type) and second
 *  (its top bit: version B) - and the GLOB that matches [typeName] there, e.g. "E[0-7]*" for 14A. */
internal const val RDS_TYPE_HEX_OFFSET = 4

internal fun rdsTypeHexGlob(typeName: String): String? {
    val type = typeName.dropLast(1).toIntOrNull()?.takeIf { it in 0..15 } ?: return null
    val version = when (typeName.last()) { 'A' -> "[0-7]"; 'B' -> "[89A-F]"; else -> return null }
    return type.toString(16).uppercase() + version + "*"
}
val RdsGroup.pty: Int get() = (block(1) ushr 5) and 0x1F

private fun rdsBlock(raw: Bytes?, index: Int): Int {
    if (raw == null || raw.size < 2 * index + 2) return 0
    return ((raw[2 * index].toInt() and 0xFF) shl 8) or (raw[2 * index + 1].toInt() and 0xFF)
}

internal object RdsSpec : DigitalPacketSpec<RdsPacket, Unit, RdsGroupContent>(
    mode = DigitalMode.RDS,
    phySerializer = null,
    dataSerializer = RdsGroupContent.serializer(),
) {
    override fun phyOf(packet: RdsPacket) = Unit
    override fun dataOf(packet: RdsPacket, phy: Unit) = RdsGroupContent(packet.events)
    override fun objectKey(raw: Bytes?, phy: Unit, data: RdsGroupContent) = rdsObjectKey(rdsBlock(raw, 0))
    override suspend fun fold(packet: RdsGroup, objects: ObjectStores) =
        applyRdsGroup(objects[RdsStationFamily], objects[TmcMessageFamily], packet)
}
