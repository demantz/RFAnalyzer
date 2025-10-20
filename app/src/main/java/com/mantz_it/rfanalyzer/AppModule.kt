package com.mantz_it.rfanalyzer

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import com.mantz_it.rfanalyzer.database.AppDatabase
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.database.BillingRepository
import com.mantz_it.rfanalyzer.database.BillingRepositoryInterface
import com.mantz_it.rfanalyzer.database.MockedBillingRepository
import com.mantz_it.rfanalyzer.database.RecordingDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * <h1>RF Analyzer - App Module (Hilt)</h1>
 *
 * Module:      AppModule.kt
 * Description: The App Module (Hilt) for the dependency injection.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */


val Context.userDataStore: DataStore<Preferences> by preferencesDataStore("rf_analyzer_preferences")

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "recordings_db"
        ).build()
    }

    @Provides
    fun provideRecordingDao(db: AppDatabase): RecordingDao {
        return db.recordingDao()
    }

    @Provides
    @Singleton
    fun provideDataStore(@ApplicationContext context: Context): DataStore<Preferences> {
        return context.userDataStore
    }

    @Provides
    @Singleton
    fun provideAppStateRepository(dataStore: DataStore<Preferences>): AppStateRepository {
        return AppStateRepository(dataStore)
    }

    @Provides
    @Singleton
    fun provideBillingRepository(@ApplicationContext context: Context, appStateRepository: AppStateRepository): BillingRepositoryInterface {
        val buildType = BuildConfig.BUILD_TYPE
        val isFoss = BuildConfig.IS_FOSS
        return if (isFoss || buildType == "debug")
            MockedBillingRepository(context, appStateRepository)
        else
            BillingRepository(context, appStateRepository)
    }
}
