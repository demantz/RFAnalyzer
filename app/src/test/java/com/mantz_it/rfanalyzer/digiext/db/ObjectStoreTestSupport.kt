package com.mantz_it.rfanalyzer.digiext.db

/**
 * <h1>RF Analyzer - Object Store Test Support</h1>
 *
 * Module:      ObjectStoreTestSupport.kt
 * Description: Test helpers for [ObjectStore]: a store backed by an in-memory map instead of the
 *              `tracked_objects` table, writing its pending changes to that map, and one object
 *              read as the live flows publish it.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** An [ObjectStore] backed by [rows] instead of the `tracked_objects` table. */
internal fun <S : Any> memoryObjectStore(
    family: ObjectFamily<S>,
    rows: MutableMap<String, TrackedObjectEntity> = mutableMapOf(),
): ObjectStore<S> = ObjectStore(family, load = { _, key -> rows[key] })

/** Writes the store's pending changes to [rows], as `DigitalStoreWriter`'s commit does. */
internal fun ObjectStore<*>.commitTo(rows: MutableMap<String, TrackedObjectEntity>) {
    dirtyEntities().forEach { rows[it.objectKey] = it }
    markClean()
}

/** The in-memory object [key], as the live flows publish it. */
internal fun <S : Any> ObjectStore<S>.liveObject(key: String): TrackedObject<S> {
    publish()
    return live.value.single { it.key == key }
}
