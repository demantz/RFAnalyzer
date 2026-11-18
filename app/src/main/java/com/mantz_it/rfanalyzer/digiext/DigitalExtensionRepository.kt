package com.mantz_it.rfanalyzer.digiext

import android.util.Log
import com.mantz_it.rfanalyzer.digiext.db.CaptureSessionRepository
import com.mantz_it.rfanalyzer.digiext.db.DigitalExtensionDatabase
import com.mantz_it.rfanalyzer.digiext.db.DigitalFilter
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketRepository
import com.mantz_it.rfanalyzer.digiext.db.DigitalStoreWriter
import com.mantz_it.rfanalyzer.digiext.db.Interpreted
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectQuery
import com.mantz_it.rfanalyzer.digiext.db.PacketSpec
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer.digiext.db.TRACKED_OBJECTS_TABLE
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.db.TrackedObjectDao
import com.mantz_it.rfanalyzer.digiext.db.TrackedObjectListDecoder
import com.mantz_it.rfanalyzer.digiext.db.markerId
import com.mantz_it.rfanalyzer.digiext.db.toCountSql
import com.mantz_it.rfanalyzer.digiext.db.toSql
import com.mantz_it.rfanalyzer.digiext.k2k.K2K_SWEEP_INTERVAL_MS
import com.mantz_it.rfanalyzer.digiext.k2k.K2kAggregator
import com.mantz_it.rfanalyzer.digiext.k2k.K2kTransmission
import com.mantz_it.rfanalyzer.digiext.k2k.Psk31Spec
import com.mantz_it.rfanalyzer.digiext.k2k.RttySpec
import com.mantz_it.rfanalyzer.digiext.lora.LoraSpec
import com.mantz_it.rfanalyzer.digiext.rds.RdsStation
import com.mantz_it.rfanalyzer.digiext.rds.RdsStationFamily
import com.mantz_it.rfanalyzer.digiext.rds.ResolvedTmcMessage
import com.mantz_it.rfanalyzer.digiext.rds.TmcMessageFamily
import com.mantz_it.rfanalyzer_ipc.digiext.DemodStatus
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalPacket
import com.mantz_it.rfanalyzer_ipc.digiext.K2kSymbol
import com.mantz_it.rfanalyzer_ipc.digiext.StreamElement
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * <h1>RF Analyzer - Digital Extension Repository</h1>
 *
 * Module:      DigitalExtensionRepository.kt
 * Description: Entry point for everything the digital extension delivers, and the read side of
 *              tracked objects. [DigitalExtensionRepository] interprets each packet with its mode's
 *              spec and hands it to the store writer, reads the tracked objects the Overview and
 *              Map subtabs list, and holds the live-only data: the tickers' packets, the RTTY/PSK31
 *              transmission being received, position trails and demodulator status. It also
 *              re-parses stored packets an older parser wrote.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** How many of a mode's last packets an open ticker can list. */
internal const val RECENT_LIVE_LIMIT = 30
/** How often a rolling time range ("the last hour") moves its edge - coarse, it is hours wide. */
private const val ROLLING_RANGE_TICK_MS = 60_000L
private const val REPARSE_CHUNK_SIZE = 1000
/** After each read of an object list, [objects] rests this many times as long as the read took. */
private const val OBJECT_LIST_REST_FACTOR = 9L

/** One mode's packets as they arrive: [latest] replays the newest, [recent] holds the session's
 *  last ones, newest first - a ticker's closed and open content. */
class LiveFeed<P>(val latest: Flow<P>, val recent: StateFlow<List<P>>)

/**
 * Entry point for everything the digital extension delivers, and the read side of tracked objects.
 *
 * Every packet is interpreted by its mode's [PacketSpec] and handed to [DigitalStoreWriter], which
 * stores it and folds it into its tracked object in one transaction. The database is the single
 * source of truth for both; this class holds no copy of it.
 *
 * What it does hold is the **live-only** data - things that describe "right now" and are
 * deliberately not persisted:
 *  - each mode's packets as they arrive ([livePackets]) and the RTTY/PSK31 transmission being
 *    received ([currentTransmission]), for the tickers,
 *  - position [trails],
 *  - demodulator status, connection state and the per-process packet counters.
 */
@Singleton
class DigitalExtensionRepository @Inject constructor(
    private val db: DigitalExtensionDatabase,
    private val packets: DigitalPacketRepository,
    private val writer: DigitalStoreWriter,
    private val trackedObjectDao: TrackedObjectDao,
    private val captureSessionRepository: CaptureSessionRepository,
) {
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val rtty = K2kAggregator(RttySpec) { writer.submit(Interpreted(RttySpec, it)) }
    private val psk31 = K2kAggregator(Psk31Spec) { writer.submit(Interpreted(Psk31Spec, it)) }

    // ------------------------------------------------------------------
    // Tracked objects (tracked_objects)
    // ------------------------------------------------------------------

    /**
     * The objects of one kind matching [query], decoded off the main thread and read again whenever
     * `tracked_objects` changes.
     *
     * Not an observed Room query: the writer commits twice a second while packets arrive, and a
     * list grows with the collection - reading 10,000 objects takes the better part of a second on
     * a mid-range phone. So each read is followed by a rest of [OBJECT_LIST_REST_FACTOR] times its
     * own duration, changes in the meantime coalesce into one read, and a long list is refreshed
     * less often instead of keeping a core busy. A short list still follows every commit.
     */
    internal fun <S : Any> objects(family: ObjectFamily<S>, query: ObjectQuery): Flow<List<TrackedObject<S>>> {
        val sql = query.copy(mode = family.kind.mode, kind = family.kind)
        return flow {
            val decoder = TrackedObjectListDecoder(family)
            db.invalidationTracker.createFlow(TRACKED_OBJECTS_TABLE).conflate().collect {
                val startNs = System.nanoTime()
                emit(decoder.decode(trackedObjectDao.query(sql.toSql())))
                delay((System.nanoTime() - startNs) / 1_000_000 * OBJECT_LIST_REST_FACTOR)
            }
        }.flowOn(Dispatchers.Default)
    }

    /**
     * The objects of one kind selected by the Digital tab's [filter] - the one definition of
     * "which objects are shown" that the Overview table and the Map both use. Empty when the
     * filter is on another mode. [withPositionOnly] keeps only objects with a known position.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun <S : Any> objects(family: ObjectFamily<S>, filter: DigitalFilter, withPositionOnly: Boolean = false): Flow<List<TrackedObject<S>>> {
        if (filter.mode != family.kind.mode) return flowOf(emptyList())
        return objectQuery(family, filter, withPositionOnly).flatMapLatest { query -> if (query == null) flowOf(emptyList()) else objects(family, query) }
    }

    /** How many objects of one kind [filter] selects - what [objects] would list, without reading them. */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun objectCount(family: ObjectFamily<*>, filter: DigitalFilter): Flow<Int> {
        if (filter.mode != family.kind.mode) return flowOf(0)
        return objectQuery(family, filter, withPositionOnly = false).flatMapLatest { query ->
            if (query == null) flowOf(0) else trackedObjectDao.observeCount(query.toCountSql())
        }
    }

    /** [filter] as the query of one kind's objects, re-resolved as a rolling time range moves. */
    private fun objectQuery(family: ObjectFamily<*>, filter: DigitalFilter, withPositionOnly: Boolean): Flow<ObjectQuery?> =
        rollingRangeClock(filter).map { nowMs -> filter.toObjectQuery(nowMs, family.kind, withPositionOnly) }.distinctUntilChanged()

    /** The current time, re-emitted while [filter] has a rolling time range; a single 0 otherwise. */
    private fun rollingRangeClock(filter: DigitalFilter): Flow<Long> {
        if (filter.lastMs == 0L) return flowOf(0L)
        return flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(ROLLING_RANGE_TICK_MS)
            }
        }
    }

    /** The time of the samples being received - see [SourceClock]. */
    val sourceClock: SourceClock get() = captureSessionRepository.sourceClock

    /** When the current capture session started (the most recent one while none runs) - rows
     *  heard before it are dimmed while their mode is being received. For a mode stamped with the
     *  samples' time, [SourceClock.sessionStartMs] is the session's start instead. */
    val currentSessionStartMs: StateFlow<Long?> get() = captureSessionRepository.currentSessionStartMs

    /** One RDS station's TMC messages, newest first - see [com.mantz_it.rfanalyzer.digiext.rds.isActiveTmcMessage]. */
    internal fun tmcMessages(stationKey: String): Flow<List<TrackedObject<ResolvedTmcMessage>>> =
        objects(TmcMessageFamily, ObjectQuery(mode = DigitalMode.RDS, keyPrefix = "$stationKey/"))

    /**
     * Removes everything recorded for [mode] (all modes when null): packets, tracked objects and
     * trails together, so nothing is left pointing at data that is gone.
     */
    suspend fun clear(mode: DigitalMode?) = writer.clear(mode)

    // ------------------------------------------------------------------
    // Live-only data
    // ------------------------------------------------------------------

    /**
     * Position trails by map marker id ([markerId]), newest point first, for objects heard recently
     * in this process (aircraft, APRS stations, mesh nodes). A trail is not persisted: it is gone
     * after a restart or once its object has been silent for its kind's idle timeout.
     */
    val trails: Flow<Map<String, List<PositionPoint>>> =
        combine(OBJECT_FAMILIES.filter { it.trail }.map { family ->
            writer.objects[family].trails.map { trails -> trails.mapKeys { (key, _) -> markerId(family.kind, key) } }
        }) { trails -> trails.fold(emptyMap()) { all, one -> all + one } }

    /** The objects of [family] heard recently, as they change - for live tickers; tables read the
     *  database ([objects]). */
    internal fun <S : Any> liveObjects(family: ObjectFamily<S>): StateFlow<List<TrackedObject<S>>> = writer.objects[family].live

    /** Grows whenever a packet is folded into an object of [family] - see [ObjectStore.heard]. */
    internal fun <S : Any> heard(family: ObjectFamily<S>): StateFlow<Long> = writer.objects[family].heard

    /** The station decoded most recently, for the RDS ticker - whether it is still being received
     *  is [com.mantz_it.rfanalyzer.digiext.rds.isLive]'s call, made against the UI clock. */
    val tunedRdsStation: Flow<TrackedObject<RdsStation>?> =
        liveObjects(RdsStationFamily).map { stations -> stations.filter { it.state.lastOwnGroupMs != 0L }.maxByOrNull { it.state.lastOwnGroupMs } }

    /**
     * Each mode's packets as they arrive, for the live tickers.
     *
     * A bounded event stream rather than a growing list. `replay = 1` so opening the tab shows the
     * most recent packet instead of an empty strip. `DROP_OLDEST` over a small buffer because a
     * ticker's job is to show what is happening *now*: a queue that grew through a burst would be
     * minutes behind by the time it drained. It also makes [MutableSharedFlow.tryEmit] infallible,
     * which matters because the producer is the Binder callback thread and must never suspend.
     */
    private val live: Map<DigitalMode, MutableSharedFlow<StoredPacket<*, *>>> = DigitalMode.entries.associateWith {
        MutableSharedFlow(replay = 1, extraBufferCapacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    }

    /** [spec]'s mode's packets as they arrive. */
    @Suppress("UNCHECKED_CAST") // each flow only ever carries its own mode's packets
    internal fun <Ph : Any, D : Any> livePackets(spec: PacketSpec<Ph, D>): SharedFlow<StoredPacket<Ph, D>> =
        live.getValue(spec.mode).asSharedFlow() as SharedFlow<StoredPacket<Ph, D>>

    // Each mode's last packets of the session, for an open ticker's log - here rather than in the
    // ticker, so the log is full the moment it is opened.
    private val recent: Map<DigitalMode, MutableStateFlow<List<StoredPacket<*, *>>>> = DigitalMode.entries.associateWith { MutableStateFlow(emptyList()) }

    /** [spec]'s mode's packets as they arrive, with the session's last ones - what a ticker shows. */
    internal fun <Ph : Any, D : Any> liveFeed(spec: PacketSpec<Ph, D>): LiveFeed<StoredPacket<Ph, D>> = LiveFeed(livePackets(spec), recentPackets(spec))

    /** [spec]'s mode's last [RECENT_LIVE_LIMIT] packets of the session, newest first. */
    @Suppress("UNCHECKED_CAST") // each list only ever holds its own mode's packets
    internal fun <Ph : Any, D : Any> recentPackets(spec: PacketSpec<Ph, D>): StateFlow<List<StoredPacket<Ph, D>>> =
        recent.getValue(spec.mode) as StateFlow<List<StoredPacket<Ph, D>>>

    /** The RTTY or PSK31 transmission being received right now, for the K2K ticker; null between
     *  transmissions. */
    fun currentTransmission(mode: DigitalMode): StateFlow<K2kTransmission?> = when (mode) {
        DigitalMode.RTTY -> rtty.current
        DigitalMode.PSK31 -> psk31.current
        else -> error("$mode is not a keyboard-to-keyboard mode")
    }

    /** The end of the already stored parts of [currentTransmission] - see [K2kAggregator.currentEarlierText]. */
    fun currentTransmissionEarlierText(mode: DigitalMode): StateFlow<String> = when (mode) {
        DigitalMode.RTTY -> rtty.currentEarlierText
        DigitalMode.PSK31 -> psk31.currentEarlierText
        else -> error("$mode is not a keyboard-to-keyboard mode")
    }

    // ------------------------------------------------------------------
    // Connection and demodulator status
    // ------------------------------------------------------------------

    private val _extensionConnectionState = MutableStateFlow(ExtensionConnectionState.DISCONNECTED)
    val extensionConnectionState: StateFlow<ExtensionConnectionState> = _extensionConnectionState

    fun onExtensionConnectionStateChanged(state: ExtensionConnectionState) {
        _extensionConnectionState.value = state
    }

    // Packets decoded per mode since the app process started, for the diagnostics overlay.
    // Deliberately not a count of stored rows, which plateaus at the per-mode retention threshold.
    private val _packetCounts = MutableStateFlow<Map<DigitalMode, Int>>(emptyMap())
    val packetCounts: StateFlow<Map<DigitalMode, Int>> = _packetCounts
    private fun incrementPacketCount(mode: DigitalMode) {
        _packetCounts.update { it + (mode to (it[mode] ?: 0) + 1) }
    }

    // Demodulator diagnostics (sync/lock, signal level, FEC health, constellation) - one flow
    // shared across modes, see DemodStatus.mode.
    private val _demodStatus = MutableStateFlow<DemodStatus?>(null)
    val demodStatus: StateFlow<DemodStatus?> = _demodStatus

    fun onDemodStatusReceived(status: DemodStatus) {
        _demodStatus.value = status
        // A lost lock is a strong hint that the K2K transmission just ended - see K2kAggregator.
        if (status.locked == false) {
            when (status.mode) {
                DigitalMode.RTTY -> rtty.onLockLost()
                DigitalMode.PSK31 -> psk31.onLockLost()
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------------
    // Entry points (called on the Binder callback thread - must never block)
    // ------------------------------------------------------------------

    /** Interprets [packet], publishes it to the live ticker and submits it - the fold runs later,
     *  on the writer coroutine. */
    fun onPacket(packet: DigitalPacket) {
        incrementPacketCount(packet.mode) // every genuine decode, discarded noise included
        val interpreted = try {
            interpret(packet, packets.frequencyOrChannel(packet.frequencyHz))
        } catch (e: Exception) {
            Log.e(TAG, "Could not interpret a ${packet.mode} packet", e)
            return
        }
        if (interpreted.discarded) return
        live.getValue(packet.mode).tryEmit(interpreted.packet)
        recent.getValue(packet.mode).update { listOf(interpreted.packet) + it.take(RECENT_LIVE_LIMIT - 1) }
        writer.submit(interpreted)
    }

    fun onStreamElement(element: StreamElement) {
        incrementPacketCount(element.mode)
        when (element) {
            is K2kSymbol -> when (element.mode) {
                DigitalMode.RTTY -> rtty.onSymbol(element, packets.frequencyOrChannel(element.frequencyHz))
                DigitalMode.PSK31 -> psk31.onSymbol(element, packets.frequencyOrChannel(element.frequencyHz))
                else -> Log.w(TAG, "K2K symbol of ${element.mode}")
            }
        }
    }

    // ------------------------------------------------------------------
    // Re-parsing stored packets
    // ------------------------------------------------------------------

    private val reparseMutex = Mutex()

    /**
     * Re-parses stored LoRa packets that carry no tracked identity yet, picking up group channels
     * the user has since added. Fire-and-forget; safe to call from a settings-change callback.
     */
    fun reparseStoredLoraPackets() {
        repositoryScope.launch { reparse(LoraSpec, withoutIdentity = true) }
    }

    /**
     * Re-parses the stored packets of [spec]'s mode with the current parser: those written by an
     * older one, or - [withoutIdentity] - those still without a tracked identity. A row whose parse
     * changed is rewritten in place, and folded into its object only if that object is new to it
     * ([com.mantz_it.rfanalyzer.digiext.db.Reparsed.newIdentity]) - the object it already belonged
     * to counted it when it arrived. Idempotent once the writer has committed, hence one pass at a
     * time and the flush before the next may start.
     */
    private suspend fun reparse(spec: PacketSpec<*, *>, withoutIdentity: Boolean) = reparseMutex.withLock {
        var changed = 0
        var afterId = 0L
        while (true) {
            val chunk = packets.reparseCandidates(spec, withoutIdentity, afterId, REPARSE_CHUNK_SIZE)
            if (chunk.isEmpty()) break
            afterId = chunk.last().id
            for (entity in chunk) {
                val reparsed = try {
                    spec.reparsed(entity)
                } catch (e: Exception) {
                    Log.w(TAG, "Re-parse: skipping undecodable ${spec.mode} row ${entity.id}", e)
                    null
                } ?: continue
                writer.submitUpdate(reparsed)
                changed++
            }
        }
        writer.flush()
        if (changed > 0) Log.i(TAG, "Re-parsed $changed stored ${spec.mode} packet(s)")
    }

    init {
        // A new session's tickers start empty instead of with the last session's packets.
        repositoryScope.launch {
            sourceClock.sessions.drop(1).collect {
                live.values.forEach { it.resetReplayCache() }
                recent.values.forEach { it.value = emptyList() }
            }
        }
        // Rows an older app version parsed are brought up to the current parsers.
        repositoryScope.launch {
            DigitalMode.entries.map(::specOf).filter { it.parserVersion > 0 && packets.hasOutdated(it) }.forEach { spec ->
                spec.awaitParserInputs()
                reparse(spec, withoutIdentity = false)
            }
        }
        // A timer rather than packet-driven: finalizing an abandoned K2K transmission has to happen
        // precisely when nothing is arriving.
        repositoryScope.launch {
            while (isActive) {
                delay(K2K_SWEEP_INTERVAL_MS)
                val now = System.currentTimeMillis()
                rtty.sweep(now)
                psk31.sweep(now)
            }
        }
    }

    private companion object {
        const val TAG = "DigitalExtensionRepository"
    }
}
