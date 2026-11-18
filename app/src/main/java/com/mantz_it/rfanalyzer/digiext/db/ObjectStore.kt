package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode

import android.util.Log
import com.mantz_it.rfanalyzer.digiext.PositionPoint
import com.mantz_it.rfanalyzer.digiext.addPosition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * <h1>RF Analyzer - Object Store</h1>
 *
 * Module:      ObjectStore.kt
 * Description: The storage layer's view of tracked objects: [ObjectFamily], what it needs to know
 *              about one kind of object, and [ObjectStore], the in-memory working set of one
 *              family - a write-through cache of `tracked_objects` whose [ObjectStore.update]
 *              applies the bookkeeping every kind shares. Also the decoding of stored rows into
 *              [TrackedObject]s, migrating an older state version first.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * Everything the storage layer needs to know about one kind of tracked object.
 *
 * [S] is the kind's state: an immutable `@Serializable` class holding only what is specific to the
 * kind. Everything kinds have in common - key, name, first/last seen, packet count, frequency,
 * position, trail - is kept by [ObjectStore], never by the state.
 */
internal class ObjectFamily<S : Any>(
    val kind: ObjectKind,
    val serializer: KSerializer<S>,
    /** The row's label; also searchable. */
    val displayName: (key: String, state: S) -> String,
    /** The object's current position, for kinds that have one. Projected to the `lat`/`lon` columns. */
    val position: ((S) -> LatLon?)? = null,
    /** Whether to keep a live-only trail of position changes (only for kinds with [position]). */
    val trail: Boolean = position != null,
    /** The frequency to list while no packet of the object's own carried one - an RDS station only
     *  known from a neighbour's EON announcement lists the first frequency of its AF list. */
    val fallbackFrequencyHz: ((S) -> Long?)? = null,
    /** How long an untouched object stays in memory before it is dropped (and re-read from the
     *  database if it is heard again). Also how long its trail survives. */
    val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    /**
     * Version of [S]'s stored form. Raise it, and extend [migrateState], when a change to [S] is not
     * additive (a field added with a default needs neither): rows written at an older version are
     * migrated as JSON before they are decoded. Aggregates cannot be rebuilt from packets - those
     * are subject to retention - so this is the only upgrade path for a stored object.
     */
    val stateVersion: Int = 1,
    val migrateState: (state: JsonObject, fromVersion: Int) -> JsonObject = { state, _ -> state },
)

internal const val DEFAULT_IDLE_TIMEOUT_MS = 30 * 60_000L

/** Decodes a stored row into its [TrackedObject], migrating an older state version first. */
internal fun <S : Any> TrackedObjectEntity.toTrackedObject(family: ObjectFamily<S>, json: Json = storageJson): TrackedObject<S> =
    toTrackedObject(decodeState(family, json))

private fun <S : Any> TrackedObjectEntity.decodeState(family: ObjectFamily<S>, json: Json): S =
    if (stateVersion < family.stateVersion) {
        json.decodeFromJsonElement(family.serializer, family.migrateState(json.parseToJsonElement(stateJson).jsonObject, stateVersion))
    } else {
        json.decodeFromString(family.serializer, stateJson)
    }

private fun <S : Any> TrackedObjectEntity.toTrackedObject(state: S) = TrackedObject(
    kind = kind, key = objectKey, displayName = displayName,
    firstSeenMs = firstSeenMs, lastSeenMs = lastSeenMs, packetCount = packetCount,
    frequencyHz = frequencyHz, lat = lat, lon = lon, state = state,
)

/**
 * Decodes the rows of one object list, read again and again as it changes. A row whose state is
 * unchanged since the previous read keeps the state decoded then: a re-read mostly finds the same
 * states, and decoding them is about half of what a read costs.
 */
internal class TrackedObjectListDecoder<S : Any>(private val family: ObjectFamily<S>, private val json: Json = storageJson) {
    private class Decoded<S>(val stateJson: String, val stateVersion: Int, val state: S)

    private var previous = HashMap<String, Decoded<S>>()

    /** A row that fails to decode is skipped - one bad row must not take the whole list down. */
    fun decode(rows: List<TrackedObjectEntity>): List<TrackedObject<S>> {
        val next = HashMap<String, Decoded<S>>(rows.size)
        val objects = rows.mapNotNull { row ->
            val decoded = previous[row.objectKey]?.takeIf { it.stateVersion == row.stateVersion && it.stateJson == row.stateJson }
                ?: try {
                    Decoded(row.stateJson, row.stateVersion, row.decodeState(family, json))
                } catch (e: Exception) {
                    Log.w(TAG, "Skipping undecodable tracked object ${row.mode}/${row.objectKey}", e)
                    return@mapNotNull null
                }
            next[row.objectKey] = decoded
            row.toTrackedObject(decoded.state)
        }
        previous = next
        return objects
    }

    private companion object {
        const val TAG = "TrackedObjectListDecoder"
    }
}

internal fun <S : Any> TrackedObject<S>.toEntity(family: ObjectFamily<S>, json: Json = storageJson): TrackedObjectEntity {
    val stateJson = json.encodeToString(family.serializer, state)
    return TrackedObjectEntity(
        mode = kind.mode, kind = kind, objectKey = key, displayName = displayName,
        firstSeenMs = firstSeenMs, lastSeenMs = lastSeenMs, packetCount = packetCount,
        frequencyHz = frequencyHz, lat = lat, lon = lon,
        searchText = genericSearchText(key, stateJson, json, extraTerms = listOf(displayName)),
        stateJson = stateJson, stateVersion = family.stateVersion,
    )
}

/**
 * The in-memory working set of one [ObjectFamily]: every object heard recently, keyed by
 * `objectKey`.
 *
 * It is a write-through cache of `tracked_objects`, not a second copy of the truth. An object is
 * read from the table the first time it is touched ([update]), replaced in memory, and written
 * back by [DigitalStoreWriter] in the same transaction as the packets that changed it. Dropping an
 * entry ([evictIdle]) therefore loses nothing but its trail.
 *
 * Only the writer coroutine may call the mutating functions; [live] and [trails] are safe to
 * collect anywhere.
 */
internal class ObjectStore<S : Any>(
    val family: ObjectFamily<S>,
    private val load: suspend (mode: DigitalMode, objectKey: String) -> TrackedObjectEntity?,
    private val json: Json = storageJson,
) {
    private class Entry<S : Any>(var obj: TrackedObject<S>) {
        var dirty = false
        var touchedAtMs = 0L
        val trail = ArrayDeque<PositionPoint>()
    }

    private val entries = HashMap<String, Entry<S>>()
    private var changedSincePublish = false

    private val _live = MutableStateFlow<List<TrackedObject<S>>>(emptyList())

    /** The objects currently in memory, i.e. heard within [ObjectFamily.idleTimeoutMs]. For live
     *  tickers; tables read `tracked_objects`. */
    val live: StateFlow<List<TrackedObject<S>>> = _live

    private var heardCount = 0L
    private val _heard = MutableStateFlow(0L)

    /** How many packets were folded into this kind's objects since the app started - grows when
     *  something new is heard, whatever time the packet carries. */
    val heard: StateFlow<Long> = _heard

    private val _trails = MutableStateFlow<Map<String, List<PositionPoint>>>(emptyMap())

    /** Position trails by `objectKey`, newest point first; only objects with at least two points. */
    val trails: StateFlow<Map<String, List<PositionPoint>>> = _trails

    /**
     * Folds one packet into the object [key] - from memory, else restored from its stored row,
     * else created with [create]'s state.
     *
     * The bookkeeping is done here, the same way for every kind:
     *  - [counts]: the packet came from the object, so it counts towards [TrackedObject.packetCount]
     *    and sets the object's frequency. False for an announcement *about* the object (an RDS
     *    station named in a neighbour's EON data), which only moves its last-seen time.
     *  - [trusted]: the packet's content may change the state. False for a packet whose integrity
     *    check failed (a LoRa CRC error): it is counted, its content is not believed.
     *  - A packet older than the object's last-seen time (a re-parse folding in an old row) counts
     *    but never overwrites the state - the state always describes the newest packet.
     */
    suspend fun update(
        key: String,
        timestampMs: Long,
        frequencyHz: Long?,
        counts: Boolean = true,
        trusted: Boolean = true,
        nowMs: Long = System.currentTimeMillis(),
        create: () -> S,
        fold: (S) -> S,
    ) {
        val entry = entries[key] ?: Entry(restore(key) ?: newObject(key, timestampMs, create())).also { entries[key] = it }
        val old = entry.obj
        val newest = timestampMs >= old.lastSeenMs
        val state = if (trusted && newest) fold(old.state) else old.state
        val position = family.position?.invoke(state)
        val ownFrequencyHz = if (counts && newest && frequencyHz != null) frequencyHz else old.frequencyHz
        entry.obj = old.copy(
            displayName = family.displayName(key, state),
            firstSeenMs = minOf(old.firstSeenMs, timestampMs),
            lastSeenMs = maxOf(old.lastSeenMs, timestampMs),
            packetCount = if (counts) old.packetCount + 1 else old.packetCount,
            frequencyHz = ownFrequencyHz ?: family.fallbackFrequencyHz?.invoke(state),
            lat = position?.lat,
            lon = position?.lon,
            state = state,
        )
        if (family.trail && position != null && (position.lat != old.lat || position.lon != old.lon)) {
            entry.trail.addPosition(position.lat, position.lon, timestampMs)
        }
        entry.dirty = true
        entry.touchedAtMs = nowMs
        if (counts) heardCount++
        changedSincePublish = true
    }

    private fun newObject(key: String, timestampMs: Long, state: S) = TrackedObject(
        kind = family.kind, key = key, displayName = family.displayName(key, state),
        firstSeenMs = timestampMs, lastSeenMs = timestampMs, packetCount = 0,
        frequencyHz = null, lat = null, lon = null, state = state,
    )

    private suspend fun restore(key: String): TrackedObject<S>? {
        val row = load(family.kind.mode, key) ?: return null
        // Writing this kind's object would replace the other kind's row - fail the fold instead.
        check(row.kind == family.kind) { "${family.kind} key $key is taken by a ${row.kind}" }
        return try {
            row.toTrackedObject(family, json)
        } catch (e: Exception) {
            // The state class changed incompatibly without a migration; start the object over
            // rather than fail the fold.
            Log.w(TAG, "Could not restore ${family.kind} $key, starting fresh", e)
            null
        }
    }

    /** The rows for every object changed since the last [markClean]. */
    fun dirtyEntities(): List<TrackedObjectEntity> =
        entries.values.filter { it.dirty }.map { it.obj.toEntity(family, json) }

    fun markClean() {
        entries.values.forEach { it.dirty = false }
    }

    /** Republishes [live] and [trails] if anything changed since the last call. */
    fun publish() {
        if (!changedSincePublish) return
        changedSincePublish = false
        _live.value = entries.values.map { it.obj }
        _heard.value = heardCount
        if (family.trail) {
            _trails.value = entries.mapNotNull { (key, entry) ->
                entry.trail.takeIf { it.size >= 2 }?.let { key to it.toList() }
            }.toMap()
        }
    }

    /** Drops objects not touched for [ObjectFamily.idleTimeoutMs]. Never drops an unwritten change. */
    fun evictIdle(nowMs: Long) {
        if (entries.values.removeAll { !it.dirty && nowMs - it.touchedAtMs > family.idleTimeoutMs }) {
            changedSincePublish = true
        }
    }

    fun clear() {
        if (entries.isEmpty()) return
        entries.clear()
        changedSincePublish = true
    }

    private companion object {
        const val TAG = "ObjectStore"
    }
}

/**
 * The working sets of every [ObjectFamily] - owned by [DigitalStoreWriter], handed to each mode's
 * [PacketSpec.fold]. Only the writer coroutine may mutate them.
 */
internal class ObjectStores(
    families: List<ObjectFamily<*>>,
    load: suspend (mode: DigitalMode, objectKey: String) -> TrackedObjectEntity?,
) {
    private val stores: Map<ObjectKind, ObjectStore<*>> = families.associate { it.kind to ObjectStore(it, load) }

    init {
        require(stores.size == families.size) { "Two families of one kind" }
    }

    val all: Collection<ObjectStore<*>> get() = stores.values

    @Suppress("UNCHECKED_CAST")
    operator fun <S : Any> get(family: ObjectFamily<S>): ObjectStore<S> =
        requireNotNull(stores[family.kind]) { "No store for ${family.kind}" } as ObjectStore<S>
}

