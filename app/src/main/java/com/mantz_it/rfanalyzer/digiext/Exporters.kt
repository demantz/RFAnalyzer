package com.mantz_it.rfanalyzer.digiext

import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.db.isRawBytes
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * <h1>RF Analyzer - Exporters</h1>
 *
 * Module:      Exporters.kt
 * Description: "Share as JSON": one envelope for packets and tracked objects alike - what it is
 *              (mode or kind), when (ISO 8601), where (frequency), whose (object key), the raw bits
 *              as hex, and the mode's own phy/data or state, with every byte array as hex.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The one JSON configuration of every "share as JSON" export. */
internal val packetExportJson = Json {
    prettyPrint = true
    encodeDefaults = true
}

/** One packet in the export envelope. */
fun StoredPacket<*, *>.toExportJson(): String = packetExportJson.encodeToString(JsonObject.serializer(), toExportElement())

/** Packets in the export envelope, as a JSON array. */
fun List<StoredPacket<*, *>>.toExportJson(): String =
    packetExportJson.encodeToString(JsonArray.serializer(), JsonArray(map { it.toExportElement() }))

private fun StoredPacket<*, *>.toExportElement(): JsonObject {
    val (phy, data) = specOf(mode).toJson(this, packetExportJson)
    return buildJsonObject {
        put("mode", mode.name)
        put("time", Instant.ofEpochMilli(timestampMs).toString())
        endTimestampMs?.let { put("endTime", Instant.ofEpochMilli(it).toString()) }
        put("frequencyHz", frequencyHz)
        objectKey?.takeIf { it.isNotEmpty() }?.let { put("objectKey", it) }
        raw?.let { put("raw", it.toHex()) }
        phy?.let { put("phy", it.hexBytes()) }
        put("data", data.hexBytes())
    }
}

/**
 * One tracked object in the export envelope: its mode and kind, key and bookkeeping (times as ISO 8601) and
 * its kind's state - with byte arrays as hex, see [hexBytes].
 */
internal fun <S : Any> TrackedObject<S>.toExportJson(family: ObjectFamily<S>): String =
    packetExportJson.encodeToString(JsonObject.serializer(), buildJsonObject {
        put("mode", kind.mode.name)
        put("kind", kind.name)
        put("objectKey", key)
        put("displayName", displayName)
        put("firstSeen", Instant.ofEpochMilli(firstSeenMs).toString())
        put("lastSeen", Instant.ofEpochMilli(lastSeenMs).toString())
        put("packetCount", packetCount)
        put("frequencyHz", frequencyHz)
        put("lat", lat)
        put("lon", lon)
        put("state", packetExportJson.encodeToJsonElement(family.serializer, state).hexBytes())
    })

/**
 * Replaces every byte array in [this] by its hex string. A JSON array of integers is always a byte
 * array in what this app stores - the same rule `genericSearchText` relies on to keep bytes out of
 * the search index.
 */
internal fun JsonElement.hexBytes(): JsonElement = when (this) {
    is JsonObject -> JsonObject(mapValues { (_, value) -> value.hexBytes() })
    is JsonArray -> if (isRawBytes()) {
        JsonPrimitive(joinToString("") { "%02X".format(Locale.US, it.jsonPrimitive.long.toInt() and 0xFF) })
    } else {
        JsonArray(map { it.hexBytes() })
    }
    else -> this
}
