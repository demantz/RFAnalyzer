package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.paging.PagingSource
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * <h1>RF Analyzer - Packet Table</h1>
 *
 * Module:      Packets.kt
 * Description: The `digital_packets` table: [PacketEntity], one row per decoded packet or finished
 *              transmission of any mode, and [DigitalPacketDao] with its queries - inserts and
 *              in-place rewrites for the re-parse, the raw queries behind every filtered read,
 *              counts, deletion and the retention eviction.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * One row per decoded packet (or finished transmission), unified across all [DigitalMode]s so
 * mode/time/frequency/search filtering, sorting and paging share a single query shape. A row is
 * the stored form of a [StoredPacket], written and read by its mode's [PacketSpec]:
 *
 *  - [raw]: the bits as received - the observation. Null only for modes that have none (K2K).
 *  - [phyJson]: the radio parameters not contained in [raw] (null for modes without any).
 *  - [payloadJson]: what the packet says - the extension's decode, or the app parser's
 *    interpretation of [raw] stamped with [parserVersion].
 *  - [searchText]: the readable leaves of [payloadJson] plus the identities - see [genericSearchText].
 *
 * No lat/lon columns: a position is a property of the tracked object, not of a packet. No session
 * column either: a packet belongs to the capture session whose start/end brackets its [timestampMs].
 */
@Entity(
    tableName = "digital_packets",
    indices = [
        Index(value = ["mode", "timestampMs"]),
        // Covers "this one object's packets, newest first" end to end, ORDER BY included - the
        // query behind an expanded tracked row's message history.
        Index(value = ["mode", "objectKey", "timestampMs"]),
        // Same shape, for the "other party" half of a bidirectional lookup (a callsign that was
        // called rather than transmitting) - see [PacketEntity.secondaryObjectKey].
        Index(value = ["mode", "secondaryObjectKey", "timestampMs"]),
        // Lets the start-up check for rows an older parser wrote ([DigitalPacketDao.hasOutdated])
        // answer without reading the table.
        Index(value = ["mode", "parserVersion"]),
    ]
)
data class PacketEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val mode: DigitalMode,
    /** When the packet was received; the start of a transmission. */
    val timestampMs: Long,
    /** The end of a transmission; null for a packet. */
    val endTimestampMs: Long? = null,
    val frequencyHz: Long,
    /**
     * The tracked object this packet belongs to - a POCSAG capcode, an ICAO hex, a callsign -
     * carrying the same value as [TrackedObjectEntity.objectKey]. Null for modes with no tracked
     * objects (K2K), `""` for a packet that carries no identity (an unreadable ICAO, an FT8
     * free-text message).
     */
    val objectKey: String? = null,
    /**
     * The *other* party's identity, for modes where one packet involves two - FT8's called station,
     * an APRS message's addressee. Lets an object's detail list both directions with one indexed
     * OR query - see [PacketQuery.bidirectional].
     */
    val secondaryObjectKey: String? = null,
    val raw: ByteArray? = null,
    val phyJson: String? = null,
    val payloadJson: String,
    /** The app parser version that produced [payloadJson]; 0 for extension-decoded modes. */
    val parserVersion: Int = 0,
    val searchText: String,
) {
    // A data class compares a ByteArray member by identity. Room doesn't compare entities, but
    // anything else that does gets content equality.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PacketEntity) return false
        return id == other.id && mode == other.mode && timestampMs == other.timestampMs &&
                endTimestampMs == other.endTimestampMs && frequencyHz == other.frequencyHz &&
                objectKey == other.objectKey && secondaryObjectKey == other.secondaryObjectKey &&
                (raw?.contentEquals(other.raw) ?: (other.raw == null)) &&
                phyJson == other.phyJson && payloadJson == other.payloadJson &&
                parserVersion == other.parserVersion && searchText == other.searchText
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + timestampMs.hashCode()
        result = 31 * result + (raw?.contentHashCode() ?: 0)
        result = 31 * result + payloadJson.hashCode()
        return result
    }
}

/** [mode] by name: the one read across all modes must not fail on a mode an older build does not
 *  know (after a downgrade). */
data class ModePacketCount(val mode: String, val count: Int, val lastMs: Long)

@Dao
interface DigitalPacketDao {

    @Insert
    suspend fun insertAll(packets: List<PacketEntity>)

    /** In-place rewrite of stored rows (matched by primary key): the re-parse after a parser
     *  change or a newly known channel key. */
    @Update
    suspend fun updateAll(packets: List<PacketEntity>)

    /** Rows of [mode] carrying no tracked identity (`objectKey = ''`) - re-parse candidates when a
     *  newly-known channel key might now decode them further. Paged by [afterId] in insertion (=
     *  receive) order, so the caller never holds the whole set. */
    @Query("SELECT * FROM digital_packets WHERE mode = :mode AND objectKey = '' AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun findWithoutIdentity(mode: DigitalMode, afterId: Long, limit: Int): List<PacketEntity>

    /** Whether any row of [mode] was written by a parser older than [parserVersion]. */
    @Query("SELECT EXISTS(SELECT 1 FROM digital_packets WHERE mode = :mode AND parserVersion < :parserVersion)")
    suspend fun hasOutdated(mode: DigitalMode, parserVersion: Int): Boolean

    /** Rows of [mode] written by a parser older than [parserVersion] - re-parse candidates after an
     *  app update. Paged like [findWithoutIdentity]. */
    @Query("SELECT * FROM digital_packets WHERE mode = :mode AND parserVersion < :parserVersion AND id > :afterId ORDER BY id LIMIT :limit")
    suspend fun findOutdated(mode: DigitalMode, parserVersion: Int, afterId: Long, limit: Int): List<PacketEntity>

    // Every filtered packet read - paging, per-object detail, export, counts - goes through these,
    // with the SQL built in one place ([PacketQuery.toSql]/[PacketQuery.toCountSql]).

    @RawQuery(observedEntities = [PacketEntity::class])
    suspend fun query(query: RoomRawQuery): List<PacketEntity>

    @RawQuery(observedEntities = [PacketEntity::class])
    fun observe(query: RoomRawQuery): Flow<List<PacketEntity>>

    @RawQuery(observedEntities = [PacketEntity::class])
    fun paging(query: RoomRawQuery): PagingSource<Int, PacketEntity>

    @RawQuery
    suspend fun count(query: RoomRawQuery): Int

    @RawQuery(observedEntities = [PacketEntity::class])
    fun observeCount(query: RoomRawQuery): Flow<Int>

    @Query("SELECT COUNT(*) FROM digital_packets")
    suspend fun getTotalCount(): Int

    @Query("SELECT COUNT(*) FROM digital_packets WHERE mode = :mode")
    suspend fun getCountForMode(mode: DigitalMode): Int

    /** Only modes that hold at least one packet have a row. */
    @Query("SELECT mode, COUNT(*) AS count, MAX(timestampMs) AS lastMs FROM digital_packets GROUP BY mode")
    suspend fun getCountsByMode(): List<ModePacketCount>

    @Query("DELETE FROM digital_packets WHERE mode = :mode")
    suspend fun deleteByMode(mode: DigitalMode)

    @Query("DELETE FROM digital_packets")
    suspend fun deleteAll()

    /**
     * Receive timestamp of the (threshold + 1)-th newest row for [mode], or null when the mode
     * holds [threshold] rows or fewer - i.e. null means "nothing to evict". Walks the
     * (mode, timestampMs) index to OFFSET and stops, so cost is independent of how far past
     * the threshold the table has grown.
     *
     * Paired with [deleteOlderThan] this trims to **threshold + 1** rows: the row at the cutoff is
     * kept. Deliberate - retention is a bound, not a quota, and null at exactly [threshold] is the
     * caller's "nothing to do". Shifting the OFFSET changes that null case too (DigitalPacketDaoTest
     * pins both).
     */
    @Query("""
        SELECT timestampMs FROM digital_packets WHERE mode = :mode
        ORDER BY timestampMs DESC LIMIT 1 OFFSET :threshold
    """)
    suspend fun findEvictionCutoff(mode: DigitalMode, threshold: Int): Long?

    /** Strictly-older-than so rows sharing the cutoff timestamp are kept: RDS can emit several
     *  groups per millisecond, and over-retaining a few rows is harmless where dropping rows the
     *  threshold said to keep would not be. */
    @Query("DELETE FROM digital_packets WHERE mode = :mode AND timestampMs < :cutoff")
    suspend fun deleteOlderThan(mode: DigitalMode, cutoff: Long)
}
