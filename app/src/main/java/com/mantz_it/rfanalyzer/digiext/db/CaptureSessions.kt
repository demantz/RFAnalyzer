package com.mantz_it.rfanalyzer.digiext.db

import android.util.Log
import com.mantz_it.rfanalyzer.digiext.SourceClock
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * <h1>RF Analyzer - Capture Sessions</h1>
 *
 * Module:      CaptureSessions.kt
 * Description: One row per capture run, recording which receiver produced the packets of that
 *              time span.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * A single capture run: analyzer start to analyzer stop. Dozens of rows per year.
 *
 * Records what received the packets of that time span - a packet belongs to the session whose
 * [startedAtMs]/[endedAtMs] brackets its timestamp - and gives the "this session" scope
 * ([RecencyScope.SESSION]) its start time.
 */
@Entity(
    tableName = "capture_sessions",
    indices = [Index(value = ["startedAtMs"])],
)
data class CaptureSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtMs: Long,
    /** Null while the session is still running. */
    val endedAtMs: Long? = null,
    /** Source class, e.g. "HackRF", "RTL-SDR", "KiwiSDR", "File". */
    val sourceType: String,
    /** Free-text detail for the specific device/server, when one is known. */
    val sourceLabel: String? = null,
    /** Tuned frequency when the session started. */
    val tunedFrequencyHz: Long = 0,
    val sampleRate: Long = 0,
    val appVersion: String = "",
    /** Null when the digital extension was not installed during this session. */
    val extensionVersion: String? = null,
)

@Dao
interface CaptureSessionDao {

    @Insert
    suspend fun insert(session: CaptureSessionEntity): Long

    @Query("UPDATE capture_sessions SET endedAtMs = :endedAtMs WHERE id = :id")
    suspend fun markEnded(id: Long, endedAtMs: Long)

    @Query("SELECT * FROM capture_sessions ORDER BY startedAtMs DESC LIMIT 1")
    suspend fun getMostRecent(): CaptureSessionEntity?

    /** Sessions that were never closed - the app was killed mid-capture. Closed on next startup so
     *  an abandoned session doesn't stay "running" forever. */
    @Query("UPDATE capture_sessions SET endedAtMs = :endedAtMs WHERE endedAtMs IS NULL AND id != :exceptId")
    suspend fun closeAbandonedSessions(endedAtMs: Long, exceptId: Long)

    /** Everything but a session that is still running. */
    @Query("DELETE FROM capture_sessions WHERE endedAtMs IS NOT NULL")
    suspend fun deleteEnded()
}

private const val NO_SESSION = 0L

@Singleton
class CaptureSessionRepository @Inject constructor(
    private val dao: CaptureSessionDao,
) {
    /** The time of the samples this session receives - see [SourceClock]. */
    val sourceClock = SourceClock()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()

    private var currentSessionId = NO_SESSION

    private val _currentSessionStartMs = MutableStateFlow<Long?>(null)

    /**
     * When the current capture session started - or, while none is running, when the most recent
     * one did, so a "this session" view keeps its content after the analyzer stops. Null only
     * until the first session is ever recorded.
     */
    val currentSessionStartMs: StateFlow<Long?> = _currentSessionStartMs

    init {
        scope.launch {
            mutex.withLock {
                if (_currentSessionStartMs.value == null) {
                    _currentSessionStartMs.value = try { dao.getMostRecent()?.startedAtMs } catch (e: Exception) { null }
                }
            }
        }
    }

    /**
     * Opens a session. Also closes any session left open by a previous run - the app can be killed
     * without [endSession] ever running, and an abandoned session must not stay "running" forever.
     */
    fun startSession(
        sourceType: String,
        sourceLabel: String?,
        tunedFrequencyHz: Long,
        sampleRate: Long,
        appVersion: String,
        extensionVersion: String?,
    ) {
        sourceClock.startSession()
        scope.launch {
            mutex.withLock {
                val now = System.currentTimeMillis()
                try {
                    val id = dao.insert(
                        CaptureSessionEntity(
                            startedAtMs = now,
                            sourceType = sourceType,
                            sourceLabel = sourceLabel,
                            tunedFrequencyHz = tunedFrequencyHz,
                            sampleRate = sampleRate,
                            appVersion = appVersion,
                            extensionVersion = extensionVersion,
                        )
                    )
                    dao.closeAbandonedSessions(endedAtMs = now, exceptId = id)
                    currentSessionId = id
                    _currentSessionStartMs.value = now
                    Log.d(TAG, "startSession: id=$id source=$sourceType")
                } catch (e: Exception) {
                    Log.e(TAG, "startSession failed", e)
                }
            }
        }
    }

    fun endSession() {
        sourceClock.endSession()
        scope.launch {
            mutex.withLock {
                val id = currentSessionId
                currentSessionId = NO_SESSION
                if (id == NO_SESSION) return@withLock
                try {
                    dao.markEnded(id, System.currentTimeMillis())
                    Log.d(TAG, "endSession: id=$id")
                } catch (e: Exception) {
                    Log.e(TAG, "endSession failed for id=$id", e)
                }
            }
        }
    }

    /** Forgets the remembered session start after the session table was cleared. */
    fun onSessionsCleared() {
        scope.launch { mutex.withLock { if (currentSessionId == NO_SESSION) _currentSessionStartMs.value = null } }
    }

    companion object {
        private const val TAG = "CaptureSessionRepository"
    }
}
