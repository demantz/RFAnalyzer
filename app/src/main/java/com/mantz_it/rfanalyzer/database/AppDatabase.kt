package com.mantz_it.rfanalyzer.database

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * <h1>RF Analyzer - Database</h1>
 *
 * Module:      AppDatabase.kt
 * Description: The room-based database.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

const val CURRENT_DB_SCHEMA_VERSION = 2

@Database(
    entities = [
        Recording::class,
        Station::class,
        Band::class,
        BookmarkList::class,
        OnlineStationProviderSettings::class
    ],
    version = CURRENT_DB_SCHEMA_VERSION,
    autoMigrations = [
        AutoMigration(from = 1, to = 2)
    ],
    exportSchema = true
)
@TypeConverters(StationTypeConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recordingDao(): RecordingDao
    abstract fun stationDao(): StationDao
    abstract fun bandDao(): BandDao
    abstract fun bookmarkListDao(): BookmarkListDao
    abstract fun onlineStationProviderSettingsDao(): OnlineStationProviderSettingsDao
}

