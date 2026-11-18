package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlin.math.abs

/**
 * <h1>RF Analyzer - Tracked Objects</h1>
 *
 * Module:      TrackedObjects.kt
 * Description: The `tracked_objects` table: [ObjectKind], the kinds of tracked object;
 *              [TrackedObjectEntity], one row per object for its whole lifetime; [TrackedObject],
 *              the immutable form everything above the storage layer sees; and [TrackedObjectDao]
 *              with its queries.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * What a tracked object *is* - one entry per aggregate type. Finer than [DigitalMode] because one
 * mode can carry several unrelated kinds (LoRa's mesh networks, AX.25's APRS and packet stations).
 */
enum class ObjectKind(val mode: DigitalMode, /** What one is called in menus. */ val label: String) {
    POCSAG_PAGER(DigitalMode.POCSAG, "POCSAG pager"),
    ADSB_AIRCRAFT(DigitalMode.ADSB, "ADS-B aircraft"),
    FT8_STATION(DigitalMode.FT8, "FT8 station"),
    RDS_STATION(DigitalMode.RDS, "RDS station"),
    APRS_STATION(DigitalMode.AX25, "APRS station"),
    /** An AX.25 station heard with something other than APRS: a BBS, a node, a connecting user. */
    PACKET_STATION(DigitalMode.AX25, "Packet station"),
    R09_BUS(DigitalMode.R09, "R09 bus"),
    MESHCORE_NODE(DigitalMode.LORA, "MeshCore node"),
    MESHTASTIC_NODE(DigitalMode.LORA, "Meshtastic node"),
    LORAWAN_DEVICE(DigitalMode.LORA, "LoRaWAN device"),
    LORA_APRS_STATION(DigitalMode.LORA, "LoRa-APRS station"),
    MESHCOM_NODE(DigitalMode.LORA, "MeshCom station"),
    /** An RDS-TMC traffic message, keyed `"<PI>/<messageKey>"` - listed in its station's detail. */
    TMC_MESSAGE(DigitalMode.RDS, "TMC message"),
}

/**
 * One row per `(mode, objectKey)` **for that object's whole lifetime** - one row per ICAO, per
 * callsign, per capcode, per node id, however many months apart the sightings are.
 *
 * This table, not the raw packet table, is "the collection": it stays in the tens of thousands of
 * rows where the raw table runs to millions, which is why raw packets need a retention budget and
 * this does not.
 *
 * The columns are the bookkeeping every kind of object shares, owned by [ObjectStore]; [stateJson]
 * holds only what is specific to the kind (its [ObjectFamily]'s state class), never a copy of a
 * column. [stateVersion] is the family's state version at write time - see
 * [ObjectFamily.migrateState].
 *
 * [packetCount] is the number of packets ever heard from the object. The packets themselves are
 * subject to retention, so an object's detail list (queried from `digital_packets` by [objectKey])
 * may hold fewer.
 */
internal const val TRACKED_OBJECTS_TABLE = "tracked_objects"

@Entity(
    tableName = TRACKED_OBJECTS_TABLE,
    primaryKeys = ["mode", "objectKey"],
    indices = [Index(value = ["mode", "lastSeenMs"])],
)
data class TrackedObjectEntity(
    val mode: DigitalMode,
    val kind: ObjectKind,
    /** Stable identity within the mode: ICAO hex, callsign, capcode, node id. Carries the same
     *  value as [PacketEntity.objectKey] on the object's packets, which name their object by mode
     *  and key alone - so the kinds sharing a mode keep their keys apart with a prefix of their own
     *  (`MeshCore:`, `LoRaWAN:`, an RDS station's PI vs. its TMC messages' `PI/...`). */
    val objectKey: String,
    val displayName: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val packetCount: Int,
    /** Frequency of the object's newest packet; null while unknown. */
    val frequencyHz: Long?,
    val lat: Double? = null,
    val lon: Double? = null,
    /** [objectKey], [displayName] and the readable leaves of [stateJson] - see [genericSearchText]. */
    val searchText: String,
    val stateJson: String,
    val stateVersion: Int,
)

/** A position, for the kinds of objects that have one. */
data class LatLon(val lat: Double, val lon: Double)

/** On the globe and not 0°/0° - what a tracker without a fix or a node without a set location
 *  reports. A fold keeps the object's last good position instead. */
internal fun isPlausiblePosition(lat: Double?, lon: Double?): Boolean =
    lat != null && lon != null && abs(lat) <= 90.0 && abs(lon) <= 180.0 && !(abs(lat) < 1e-4 && abs(lon) < 1e-4)

/**
 * One tracked object as everything above the storage layer sees it: the generic columns of its
 * `tracked_objects` row plus its kind's own [state]. Immutable - the writer replaces it on every
 * update, so it can be handed to the UI as it is.
 */
data class TrackedObject<S : Any>(
    val kind: ObjectKind,
    val key: String,
    val displayName: String,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val packetCount: Int,
    /** Frequency of the object's newest packet; null while unknown. */
    val frequencyHz: Long?,
    val lat: Double?,
    val lon: Double?,
    val state: S,
)

/** The object's id on the Map, unique across every kind shown there at once. */
val TrackedObject<*>.markerId: String get() = markerId(kind, key)

fun markerId(kind: ObjectKind, key: String): String = "${kind.name}:$key"

@Dao
interface TrackedObjectDao {

    @Upsert
    suspend fun upsertAll(objects: List<TrackedObjectEntity>)

    @Query("SELECT * FROM tracked_objects WHERE mode = :mode AND objectKey = :objectKey")
    suspend fun find(mode: DigitalMode, objectKey: String): TrackedObjectEntity?

    /** One read of a filtered/sorted object list - the SQL comes from [ObjectQuery.toSql]. Not
     *  observed: `DigitalExtensionRepository.objects` paces its re-reads itself. */
    @RawQuery
    suspend fun query(query: RoomRawQuery): List<TrackedObjectEntity>

    /** How many objects a filter selects - the SQL comes from [ObjectQuery.toCountSql]. */
    @RawQuery(observedEntities = [TrackedObjectEntity::class])
    fun observeCount(query: RoomRawQuery): Flow<Int>

    @Query("SELECT COUNT(*) FROM tracked_objects WHERE mode = :mode")
    suspend fun countForMode(mode: DigitalMode): Int

    @Query("DELETE FROM tracked_objects WHERE mode = :mode")
    suspend fun deleteByMode(mode: DigitalMode)

    @Query("DELETE FROM tracked_objects")
    suspend fun deleteAll()
}
