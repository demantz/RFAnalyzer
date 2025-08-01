package com.mantz_it.rfanalyzer.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.mantz_it.rfanalyzer.ui.composable.FilesourceFileFormat
import com.mantz_it.rfanalyzer.ui.composable.asStringWithUnit
import kotlinx.coroutines.flow.Flow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * <h1>RF Analyzer - Recording DAO</h1>
 *
 * Module:      RecordingDao.kt
 * Description: Data Access Object for the Recording Table in the room database.
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


@Entity(tableName = "recordings")
data class Recording(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val frequency: Long,
    val sampleRate: Long,
    val date: Long,
    val fileFormat: FilesourceFileFormat,
    val sizeInBytes: Long,
    val filePath: String,
    val favorite: Boolean
)

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings ORDER BY date DESC")
    fun getAllRecordings(): Flow<List<Recording>>

    @Query("SELECT COALESCE(SUM(sizeInBytes), 0) FROM recordings")
    suspend fun getTotalSizeInBytes(): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(recording: Recording): Long

    @Query("SELECT * FROM recordings WHERE id = :id")
    suspend fun get(id: Long): Recording

    @Query("UPDATE recordings SET favorite = NOT favorite WHERE id = :id")
    suspend fun toggleFavorite(id: Long)

    @Query("UPDATE recordings SET name = :newName WHERE id = :id")
    suspend fun rename(id: Long, newName: String)

    @Query("UPDATE recordings SET filePath = :newPath WHERE id = :id")
    suspend fun setFilePath(id: Long, newPath: String)

    @Query("DELETE FROM recordings")
    suspend fun deleteAllRecordings()

    @Delete
    suspend fun delete(recording: Recording)
}

fun Recording.calculateFileName(): String {
    val timestampString = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(this.date));
    return "${timestampString}_${this.name}_${this.fileFormat}_${this.frequency.asStringWithUnit("Hz").replace(" ", "")}_${this.sampleRate.asStringWithUnit("Sps").replace(" ", "")}.iq"
}