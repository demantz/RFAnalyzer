package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import android.util.Log
import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import com.mantz_it.rfanalyzer.analyzer.OverloadMonitor
import com.mantz_it.rfanalyzer.database.AppStateRepository
import com.mantz_it.rfanalyzer.digiext.OBJECT_FAMILIES
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select

/**
 * <h1>RF Analyzer - DigitalStoreWriter</h1>
 *
 * Module:      DigitalStoreWriter.kt
 * Description: The single writer of the digital extension database. Every decoded packet passes
 *              through one coroutine here, which stores the packet row and folds it into its
 *              tracked object, then commits both in one transaction.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** How long the writer keeps gathering packets before committing a batch. Long enough to collapse
 *  a burst into one transaction (every transaction is its own fsync), short enough that the tables
 *  reading the database still feel live. */
private const val BATCH_WINDOW_MS = 500L

/** Upper bound on one transaction, so a sustained ADS-B burst can't grow an unbounded batch. */
private const val MAX_BATCH_SIZE = 500

/** Queue depth before packets are dropped and counted. ~40 s of headroom at ADS-B's busiest
 *  observed rate (200 frames/s). */
private const val QUEUE_CAPACITY = 8192

/** Minimum spacing between retention passes. Eviction only has to keep each mode near its
 *  threshold, not exactly at it. */
private const val EVICTION_INTERVAL_MS = 30_000L

/**
 * Why one writer: a packet row and the tracked object it belongs to are two views of the same
 * event. Written by one coroutine in one transaction they cannot disagree - there is no window in
 * which a packet exists without its object or an object counts a packet that was never stored -
 * and the fold needs no locking because nothing else touches the [ObjectStore]s.
 *
 * Producers are Binder callback threads delivering IPC events. They must never block on disk, so
 * [submit] only enqueues.
 */
@Singleton
class DigitalStoreWriter @Inject constructor(
    private val db: DigitalExtensionDatabase,
    private val packetDao: DigitalPacketDao,
    private val trackedObjectDao: TrackedObjectDao,
    private val captureSessionDao: CaptureSessionDao,
    private val captureSessionRepository: CaptureSessionRepository,
    private val appStateRepository: AppStateRepository,
) {
    private sealed interface Op
    /** [isUpdate] distinguishes an in-place row rewrite (a re-parsed packet, keyed by its existing
     *  primary key) from a first-time insert - [PacketEntity] inserts default to ABORT-on-conflict,
     *  so a rewrite has to go through [DigitalPacketDao.updateAll] instead. */
    private class Ingest(val packet: PacketEntity, val fold: (suspend (ObjectStores) -> Unit)?, val isUpdate: Boolean) : Op
    private class Clear(val mode: DigitalMode?, val done: CompletableDeferred<Unit>) : Op
    private class Flush(val done: CompletableDeferred<Unit>) : Op

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Bounded with an explicit, reported drop rather than BufferOverflow.DROP_OLDEST, so saturation
    // shows as an overload. Reaching the bound at all means the write path is pathologically behind.
    private val queue = Channel<Op>(capacity = QUEUE_CAPACITY)
    private val dropped = AtomicLong(0)

    /** The working set of every kind of tracked object. Mutated only on the writer coroutine,
     *  inside a packet's fold; their `live` and `trails` flows are safe to collect anywhere. */
    internal val objects = ObjectStores(OBJECT_FAMILIES, load = trackedObjectDao::find)

    /** Modes that have had rows written since the last eviction pass - only these need checking. */
    private val pendingEvictionModes = mutableSetOf<DigitalMode>()
    private var lastEvictionMs = 0L

    init {
        scope.launch { run() }
    }

    /**
     * Queues one packet: it is stored, and folded into its tracked object(s) on the writer
     * coroutine - both in the same transaction.
     *
     * Never blocks; safe on a Binder thread.
     */
    internal fun submit(packet: Interpreted<*, *>) {
        if (queue.trySend(Ingest(packet.row(), packet::fold, isUpdate = false)).isSuccess) return
        if (dropped.incrementAndGet() % 1000 == 1L) Log.w(TAG, "Write queue full - ${dropped.get()} packet(s) dropped so far")
        OverloadMonitor.reportMissedDeadline("Packet database")
    }

    /**
     * Queues the rewrite of a re-parsed, already-stored packet (same row id). It is folded only into
     * an object [new to it][Reparsed.newIdentity]. Unlike [submit] this suspends while the queue
     * is full instead of dropping - a re-parse is in no hurry and must not crowd out live packets.
     */
    internal suspend fun submitUpdate(reparsed: Reparsed<*, *>) {
        val packet = reparsed.interpreted
        queue.send(Ingest(packet.row(), if (reparsed.newIdentity) packet::fold else null, isUpdate = true))
    }

    /** Returns once everything queued before this call has been committed. */
    suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        queue.send(Flush(done))
        done.await()
    }

    /** Number of packets dropped because the queue was saturated - 0 in normal operation. */
    fun droppedCount(): Long = dropped.get()

    /**
     * Removes everything recorded for [mode], or for every mode when null: packets, tracked
     * objects and their in-memory state together. Clearing everything also removes finished
     * capture sessions.
     *
     * Ordered with the packet stream: packets queued before this call are gone afterwards, packets
     * arriving later start from an empty store.
     */
    suspend fun clear(mode: DigitalMode?) {
        val done = CompletableDeferred<Unit>()
        queue.send(Clear(mode, done))
        done.await()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun run() {
        val packets = ArrayList<PacketEntity>()
        val updates = ArrayList<PacketEntity>()
        while (true) {
            var op: Op? = queue.receive()
            val deadlineNanos = System.nanoTime() + BATCH_WINDOW_MS * 1_000_000
            var ingested = 0
            while (op != null) {
                when (op) {
                    is Ingest -> {
                        op.fold?.let { fold ->
                            try { fold(objects) } catch (e: Exception) { Log.e(TAG, "Fold failed", e) }
                        }
                        if (op.isUpdate) updates += op.packet else packets += op.packet
                        ingested++
                    }
                    is Clear -> {
                        commit(packets, updates)
                        clearNow(op.mode)
                        op.done.complete(Unit)
                    }
                    is Flush -> {
                        commit(packets, updates)
                        op.done.complete(Unit)
                    }
                }
                if (ingested >= MAX_BATCH_SIZE) break
                val remainingMs = (deadlineNanos - System.nanoTime()) / 1_000_000
                if (remainingMs <= 0) break
                // select { onReceive / onTimeout }, not withTimeoutOrNull around receive(): that can
                // take an element from the channel in the instant the timeout fires and lose it
                // silently (~0.3% of frames at ADS-B rates). select resolves the two atomically.
                op = select<Op?> {
                    queue.onReceive { it }
                    onTimeout(remainingMs) { null }
                }
            }
            commit(packets, updates)
        }
    }

    private suspend fun commit(packets: MutableList<PacketEntity>, updates: MutableList<PacketEntity>) {
        val changedObjects = objects.all.flatMap { it.dirtyEntities() }
        if (packets.isNotEmpty() || updates.isNotEmpty() || changedObjects.isNotEmpty()) {
            try {
                db.useWriterConnection { connection ->
                    connection.immediateTransaction {
                        if (packets.isNotEmpty()) packetDao.insertAll(packets)
                        if (updates.isNotEmpty()) packetDao.updateAll(updates)
                        if (changedObjects.isNotEmpty()) trackedObjectDao.upsertAll(changedObjects)
                    }
                }
                objects.all.forEach { it.markClean() }
                packets.mapTo(pendingEvictionModes) { it.mode }
            } catch (e: Exception) {
                // The objects stay dirty and are written with the next batch; the packets are lost.
                Log.e(TAG, "Batch write failed (${packets.size} packets, ${updates.size} updates dropped)", e)
            }
            packets.clear()
            updates.clear()
        }
        val now = System.currentTimeMillis()
        objects.all.forEach { it.evictIdle(now); it.publish() }
        evictPacketsIfDue(now)
    }

    private suspend fun clearNow(mode: DigitalMode?) {
        try {
            db.useWriterConnection<Unit> { connection ->
                connection.immediateTransaction<Unit> {
                    if (mode == null) {
                        packetDao.deleteAll()
                        trackedObjectDao.deleteAll()
                        captureSessionDao.deleteEnded()
                    } else {
                        packetDao.deleteByMode(mode)
                        trackedObjectDao.deleteByMode(mode)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Clear failed for ${mode ?: "all modes"}", e)
            return
        }
        objects.all.filter { mode == null || it.family.kind.mode == mode }.forEach { it.clear(); it.publish() }
        if (mode == null) captureSessionRepository.onSessionsCleared()
    }

    /**
     * Trims each touched mode's packets back to its retention threshold, at most once per
     * [EVICTION_INTERVAL_MS]. Tracked objects are never trimmed - see [TrackedObjectEntity].
     */
    private suspend fun evictPacketsIfDue(now: Long) {
        if (now - lastEvictionMs < EVICTION_INTERVAL_MS) return
        lastEvictionMs = now

        val modes = pendingEvictionModes.toList()
        pendingEvictionModes.clear()

        for (mode in modes) {
            val threshold = appStateRepository.digitalPacketRetentionByMode[mode]?.value ?: continue
            if (threshold <= 0) continue // 0 = keep everything (see defaultRetention)
            try {
                val cutoff = packetDao.findEvictionCutoff(mode, threshold) ?: continue
                packetDao.deleteOlderThan(mode, cutoff)
            } catch (e: Exception) {
                Log.e(TAG, "Eviction failed for $mode", e)
            }
        }
    }

    private companion object {
        const val TAG = "DigitalStoreWriter"
    }
}
