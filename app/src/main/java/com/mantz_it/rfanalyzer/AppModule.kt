package com.mantz_it.rfanalyzer

import com.mantz_it.rfanalyzer.location.LocationRepository
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.widget.Toast
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.mantz_it.rfanalyzer.database.AppDatabase
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.PrivateSettings
import com.mantz_it.rfanalyzer.database.PrivateSettingsMigration
import com.mantz_it.rfanalyzer.billing.BillingRepository
import com.mantz_it.rfanalyzer.billing.createBillingRepository
import com.mantz_it.rfanalyzer.database.BandDao
import com.mantz_it.rfanalyzer.database.BookmarkListDao
import com.mantz_it.rfanalyzer.digiext.db.CaptureSessionDao
import com.mantz_it.rfanalyzer.digiext.db.CaptureSessionRepository
import com.mantz_it.rfanalyzer.digiext.db.TrackedObjectDao
import com.mantz_it.rfanalyzer.digiext.db.DigitalExtensionDatabase
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionRepository
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionStatus
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketDao
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketRepository
import com.mantz_it.rfanalyzer.database.CURRENT_DB_SCHEMA_VERSION
import com.mantz_it.rfanalyzer.database.OnlineStationProviderSettingsDao
import com.mantz_it.rfanalyzer.database.RecordingDao
import com.mantz_it.rfanalyzer.database.StationDao
import com.mantz_it.rfanalyzer.database.StationRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import com.mantz_it.rfanalyzer.digiext.db.DigitalStoreWriter

/**
 * <h1>RF Analyzer - App Module (Hilt)</h1>
 *
 * Module:      AppModule.kt
 * Description: The App Module (Hilt) for the dependency injection.
 *
 * @author Dennis Mantz
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */


val Context.userDataStore: DataStore<Preferences> by preferencesDataStore("rf_analyzer_preferences")

/** Location, KiwiSDR password, KiwiSDR map position: left out of cloud backups (PrivateSettings). */
val Context.privateDataStore: DataStore<Preferences> by preferencesDataStore(
    PrivateSettings.FILE_NAME,
    produceMigrations = { context -> listOf(PrivateSettingsMigration(context.userDataStore)) },
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        val dbName = "recordings_db" // For historic reasons, this is called recordings_db, but it also holds all other db data

        val dbFile = context.getDatabasePath(dbName)
        if (dbFile.exists()) {
            val db = SQLiteDatabase.openDatabase(dbFile.path, null, SQLiteDatabase.OPEN_READONLY)
            val version = db.version
            db.close()
            if (version > CURRENT_DB_SCHEMA_VERSION) {
                Log.d("AppModule", "Database Schema too new ($version > $CURRENT_DB_SCHEMA_VERSION).")
                Toast.makeText(context, "Database Version too new ($version > $CURRENT_DB_SCHEMA_VERSION): Update App or clear App Data!", Toast.LENGTH_LONG).show()
                Thread.sleep(3000)
            }
        }

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            dbName
        )
        .build()
    }

    @Provides
    fun provideRecordingDao(db: AppDatabase): RecordingDao = db.recordingDao()

    @Provides
    fun provideStationDao(db: AppDatabase): StationDao = db.stationDao()

    @Provides
    fun provideBandDao(db: AppDatabase): BandDao = db.bandDao()

    @Provides
    fun provideBookmarkListDao(db: AppDatabase): BookmarkListDao = db.bookmarkListDao()

    @Provides
    fun provideOnlineStationProviderSettingsDao(db: AppDatabase): OnlineStationProviderSettingsDao = db.onlineStationProviderSettingsDao()

    @Provides
    @Singleton
    fun provideDigitalExtensionDatabase(@ApplicationContext context: Context): DigitalExtensionDatabase {
        // Deliberately a separate database file from "recordings_db" (AppDatabase) so a
        // "clear database" action can wipe packet history without touching bookmarks/recordings.
        return Room.databaseBuilder(
            context,
            DigitalExtensionDatabase::class.java,
            "digital_packets_db"
        )
            // A schema change needs a real Migration (CURRENT_DIGITAL_PACKET_DB_SCHEMA_VERSION)
            .build()
    }

    @Provides
    fun provideDigitalPacketDao(db: DigitalExtensionDatabase): DigitalPacketDao = db.digitalPacketDao()

    @Provides
    fun provideCaptureSessionDao(db: DigitalExtensionDatabase): CaptureSessionDao = db.captureSessionDao()

    @Provides
    fun provideTrackedObjectDao(db: DigitalExtensionDatabase): TrackedObjectDao = db.trackedObjectDao()

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.userDataStore
    }

    @Provides
    @Singleton
    fun provideAppStateRepository(@ApplicationContext context: Context, dataStore: DataStore<Preferences>): AppStateRepository {
        return AppStateRepository(dataStore, context.privateDataStore)
    }

    @Provides
    @Singleton
    fun provideStationRepository(
        @ApplicationContext context: Context,
        stationDao: StationDao,
        bandDao: BandDao,
        bookmarkListDao: BookmarkListDao,
        onlineStationProviderSettingsDao: OnlineStationProviderSettingsDao,
        appStateRepository: AppStateRepository,
        locationRepository: LocationRepository,
    ): StationRepository {
        return StationRepository(context, stationDao, bandDao, bookmarkListDao, onlineStationProviderSettingsDao, appStateRepository, locationRepository)
    }

    @Provides
    @Singleton
    fun provideBillingRepository(@ApplicationContext context: Context, appStateRepository: AppStateRepository): BillingRepository =
        createBillingRepository(context, appStateRepository)  // per flavor and build type

    @Provides
    @Singleton
    fun provideDigitalPacketRepository(
        dao: DigitalPacketDao,
        appStateRepository: AppStateRepository,
    ): DigitalPacketRepository {
        return DigitalPacketRepository(dao, appStateRepository)
    }

    @Provides
    @Singleton
    fun provideCaptureSessionRepository(dao: CaptureSessionDao): CaptureSessionRepository {
        return CaptureSessionRepository(dao)
    }

    @Provides
    @Singleton
    fun provideDigitalStoreWriter(
        db: DigitalExtensionDatabase,
        packetDao: DigitalPacketDao,
        trackedObjectDao: TrackedObjectDao,
        captureSessionDao: CaptureSessionDao,
        captureSessionRepository: CaptureSessionRepository,
        appStateRepository: AppStateRepository,
    ): DigitalStoreWriter {
        return DigitalStoreWriter(db, packetDao, trackedObjectDao, captureSessionDao, captureSessionRepository, appStateRepository)
    }

    @Provides
    @Singleton
    fun provideDigitalExtensionRepository(
        db: DigitalExtensionDatabase,
        packetRepository: DigitalPacketRepository,
        writer: DigitalStoreWriter,
        trackedObjectDao: TrackedObjectDao,
        captureSessionRepository: CaptureSessionRepository,
    ): DigitalExtensionRepository {
        return DigitalExtensionRepository(db, packetRepository, writer, trackedObjectDao, captureSessionRepository)
    }

    @Provides
    @Singleton
    fun provideDigitalExtensionStatus(
        @ApplicationContext context: Context,
        appStateRepository: AppStateRepository,
        digitalExtensionRepository: DigitalExtensionRepository,
    ): DigitalExtensionStatus {
        return DigitalExtensionStatus(context, appStateRepository, digitalExtensionRepository.extensionConnectionState)
    }
}
