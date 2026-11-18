package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import androidx.room.Database
import androidx.room.Entity
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import kotlinx.serialization.json.Json

/**
 * <h1>RF Analyzer - Digital Extension Database</h1>
 *
 * Module:      DigitalExtensionDatabase.kt
 * Description: Room database of everything the digital extension decodes. Three tables:
 *              `digital_packets` (one row per decoded packet, trimmed by per-mode retention),
 *              `tracked_objects` (one row per aircraft/station/pager/..., kept for good) and
 *              `capture_sessions` (one row per analyzer run). Separate from `AppDatabase`
 *              (bookmarks/recordings) so it can be cleared independently.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The one [Json] configuration for everything this database stores as JSON. `ignoreUnknownKeys`
 *  lets a row written by a newer build (with fields this one doesn't know) still decode. */
internal val storageJson = Json { ignoreUnknownKeys = true }

/**
 * Default cap on retained raw packets per mode; 0 means unlimited. Overridable per mode via
 * AppStateRepository.digitalPacketRetentionByMode.
 *
 * Packet rates differ by three orders of magnitude between modes, so one shared threshold cannot
 * serve them. The split is by *rate* and by how much a single raw packet is individually worth:
 *
 *  - Unlimited (POCSAG, R09, RTTY, PSK31): a few packets per second at most, and each decoded
 *    message is itself the artifact worth collecting. Even years of use stay in the tens of MB.
 *  - Large cap (FT8, AX25, LORA): individually meaningful, but big rows and/or steady rates.
 *  - Small cap (RDS, ADSB): firehoses - RDS is a deterministic 11.4 groups/s, ADS-B runs 50-200
 *    frames/s - where an individual raw packet is nearly worthless on its own (one RDS group is a
 *    fragment of a station name, one squitter is half a CPR position pair). The value lives in the
 *    aggregated station/aircraft, not the packets it was folded from.
 *
 * Row costs are measured, not estimated - see PacketRowSizeMeasurement. Comments below give the
 * resulting storage and roughly how long that is of *continuous* decoding of that one mode.
 */
val DigitalMode.defaultRetention: Int
    get() = when (this) {
        DigitalMode.RDS -> 20_000      // ~290 B/row => ~5.5 MB, ~30 min at 11.4 groups/s
        DigitalMode.ADSB -> 100_000    // ~346 B/row => ~33 MB, ~8-33 min at 50-200 frames/s
        DigitalMode.FT8 -> 200_000     // ~467 B/row => ~89 MB, ~28 h at ~2 decodes/s
        DigitalMode.AX25 -> 100_000    // ~813 B/row => ~78 MB, ~140 h at ~0.2 frames/s
        DigitalMode.LORA -> 100_000    // ~711 B/row => ~68 MB, ~550 h at ~0.05 frames/s
        DigitalMode.POCSAG, DigitalMode.R09, DigitalMode.RTTY, DigitalMode.PSK31 -> 0
    }

/**
 * The packet history's schema version. 1 is the schema RF Analyzer 2.3 ships, the first release
 * that carries this database. Every schema change raises it and adds a [Migration] (users' history
 * must survive an update; there is no destructive fallback), and its exported schema
 * (`app/schemas/`) is committed with it.
 */
const val CURRENT_DIGITAL_PACKET_DB_SCHEMA_VERSION = 1

@Database(
    entities = [PacketEntity::class, CaptureSessionEntity::class, TrackedObjectEntity::class],
    version = CURRENT_DIGITAL_PACKET_DB_SCHEMA_VERSION,
    exportSchema = true,
)
abstract class DigitalExtensionDatabase : RoomDatabase() {
    abstract fun digitalPacketDao(): DigitalPacketDao
    abstract fun captureSessionDao(): CaptureSessionDao
    abstract fun trackedObjectDao(): TrackedObjectDao
}
