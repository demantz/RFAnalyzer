package com.mantz_it.rfanalyzer.digiext.db

import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalPacket
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * <h1>RF Analyzer - Stored Packet</h1>
 *
 * Module:      StoredPacket.kt
 * Description: The one app-side packet type ([StoredPacket]) and the per-mode description of it
 *              ([PacketSpec]) - the packet counterpart of [TrackedObject]/[ObjectFamily].
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * One packet - or one finished transmission of a stream mode - as the app knows it, whether it just
 * arrived or was read back from `digital_packets`. Cards, detail lists, tickers, folds and export
 * all take this one shape; each mode names its own with a type alias (`PocsagPage`, `AdsbFrame`, ...).
 *
 * The parts follow the observation/interpretation split:
 *  - [raw]: the bits as received. Null only for modes that deliver none (keyboard-to-keyboard text).
 *  - [phy]: the radio parameters not contained in [raw]; [Unit] for modes that have none.
 *  - [data]: what the packet says - decoded by the extension, or parsed here from [raw] by the
 *    parser of [parserVersion].
 *
 * [objectKey] and [secondaryObjectKey] are derived from [phy]/[data] by the mode's [PacketSpec] -
 * never set independently, so a packet and its tracked object cannot disagree.
 */
data class StoredPacket<out Ph : Any, out D : Any>(
    /** The `digital_packets` row id; 0 for a packet not stored yet. */
    val rowId: Long,
    val mode: DigitalMode,
    /** When the packet was received; the start of a transmission. */
    val timestampMs: Long,
    /** The end of a transmission; null for a packet. */
    val endTimestampMs: Long?,
    /** 0 while unknown. */
    val frequencyHz: Long,
    val raw: Bytes?,
    val phy: Ph,
    val data: D,
    /** See [PacketEntity.objectKey]. */
    val objectKey: String?,
    /** See [PacketEntity.secondaryObjectKey]. */
    val secondaryObjectKey: String?,
    val parserVersion: Int,
) {
    /** The frequency for a tracked object to list, or null while unknown. */
    val knownFrequencyHz: Long? get() = frequencyHz.takeIf { it != 0L }
}

/**
 * This packet as the mode's own type, or null if it is another mode's. The one place a packet read
 * generically (the Raw subtab, export) is narrowed for a mode-specific card.
 */
@Suppress("UNCHECKED_CAST")
inline fun <reified Ph : Any, reified D : Any> StoredPacket<*, *>.asPacketOf(): StoredPacket<Ph, D>? =
    if (phy is Ph && data is D) this as StoredPacket<Ph, D> else null

/**
 * Everything the app needs to know about one mode's packets: how [StoredPacket.phy] and
 * [StoredPacket.data] are stored, which tracked object a packet belongs to, and how it is folded
 * into it. One object per mode (`PocsagSpec`, `AdsbSpec`, ...); the pipeline around it - storage,
 * search, re-parsing, detail reads, export - is generic.
 *
 * [phySerializer] is null for a mode without phy ([Ph] = [Unit]); such rows store no `phyJson`.
 *
 * [parserVersion] > 0 marks a mode whose [data] the app parses from [StoredPacket.raw] ([parse]).
 * Raise it whenever [parse]'s output changes for the same input: rows written by an older parser are
 * re-parsed at start-up, which is what keeping the raw bits is for.
 */
internal abstract class PacketSpec<Ph : Any, D : Any>(
    val mode: DigitalMode,
    private val phySerializer: KSerializer<Ph>?,
    private val dataSerializer: KSerializer<D>,
    val parserVersion: Int = 0,
) {
    /** The tracked object a packet belongs to: null for a mode without tracked objects, `""` for a
     *  packet that names none (an unreadable ICAO, an FT8 free-text message). */
    open fun objectKey(raw: Bytes?, phy: Ph, data: D): String? = null

    /** The other party of a packet that involves two - see [PacketEntity.secondaryObjectKey]. */
    open fun secondaryObjectKey(raw: Bytes?, phy: Ph, data: D): String? = null

    /** Search terms beyond what [data] holds as readable text - see [genericSearchText]. */
    open fun searchTerms(packet: StoredPacket<Ph, D>): List<String> = emptyList()

    /** Parses [raw]; only modes with [parserVersion] > 0 have a parser. */
    open fun parse(raw: Bytes, phy: Ph): D = error("$mode is not parsed app-side")

    /** Suspends until [parse] has everything it reads besides the packet (LoRa: the channel keys).
     *  The start-up re-parse waits for it. */
    open suspend fun awaitParserInputs() {}

    /** Whether a packet is dropped before it is stored - decoded noise. */
    open fun discard(packet: StoredPacket<Ph, D>): Boolean = false

    /** Folds the packet into its tracked objects. Runs on the writer coroutine. */
    open suspend fun fold(packet: StoredPacket<Ph, D>, objects: ObjectStores) {}

    fun packet(
        timestampMs: Long, frequencyHz: Long, raw: Bytes?, phy: Ph, data: D,
        endTimestampMs: Long? = null, rowId: Long = 0, parserVersion: Int = this.parserVersion,
    ) = StoredPacket(
        rowId = rowId, mode = mode, timestampMs = timestampMs, endTimestampMs = endTimestampMs,
        frequencyHz = frequencyHz, raw = raw, phy = phy, data = data,
        objectKey = objectKey(raw, phy, data), secondaryObjectKey = secondaryObjectKey(raw, phy, data),
        parserVersion = parserVersion,
    )

    fun toRow(packet: StoredPacket<Ph, D>, json: Json = storageJson): PacketEntity {
        val payloadJson = json.encodeToString(dataSerializer, packet.data)
        return PacketEntity(
            id = packet.rowId,
            mode = mode,
            timestampMs = packet.timestampMs,
            endTimestampMs = packet.endTimestampMs,
            frequencyHz = packet.frequencyHz,
            objectKey = packet.objectKey,
            secondaryObjectKey = packet.secondaryObjectKey,
            // Bytes hands out copies only; Room never writes into the array.
            raw = packet.raw?.toByteArray(),
            phyJson = phySerializer?.let { json.encodeToString(it, packet.phy) },
            payloadJson = payloadJson,
            parserVersion = packet.parserVersion,
            searchText = genericSearchText(packet.objectKey, payloadJson, json, searchTerms(packet)),
        )
    }

    fun fromRow(entity: PacketEntity, json: Json = storageJson): StoredPacket<Ph, D> {
        require(entity.mode == mode) { "A ${entity.mode} row is not a $mode packet" }
        return StoredPacket(
            rowId = entity.id, mode = mode, timestampMs = entity.timestampMs,
            endTimestampMs = entity.endTimestampMs, frequencyHz = entity.frequencyHz,
            raw = entity.raw?.let(::Bytes), phy = phyOf(entity.phyJson, json),
            data = json.decodeFromString(dataSerializer, entity.payloadJson),
            objectKey = entity.objectKey, secondaryObjectKey = entity.secondaryObjectKey,
            parserVersion = entity.parserVersion,
        )
    }

    /**
     * Parses a stored row again with the current parser. Null when the result is unchanged (still
     * no channel key for it, say) or the row cannot be parsed again.
     */
    fun reparsed(entity: PacketEntity, json: Json = storageJson): Reparsed<Ph, D>? {
        val stored = fromRow(entity, json)
        val raw = stored.raw ?: return null
        val fresh = packet(
            timestampMs = stored.timestampMs, frequencyHz = stored.frequencyHz, raw = raw, phy = stored.phy,
            data = parse(raw, stored.phy), endTimestampMs = stored.endTimestampMs, rowId = stored.rowId,
        )
        // Compared as stored, so a field without content equality cannot make every row look changed.
        if (json.encodeToString(dataSerializer, fresh.data) == entity.payloadJson && fresh.parserVersion == stored.parserVersion) return null
        return Reparsed(Interpreted(this, fresh), newIdentity = !fresh.objectKey.isNullOrEmpty() && fresh.objectKey != stored.objectKey)
    }

    /** [packet]'s phy (null for a mode without one) and data as JSON - for export. */
    @Suppress("UNCHECKED_CAST")
    fun toJson(packet: StoredPacket<*, *>, json: Json): Pair<JsonElement?, JsonElement> {
        require(packet.mode == mode) { "A ${packet.mode} packet is not a $mode packet" }
        val typed = packet as StoredPacket<Ph, D>
        return phySerializer?.let { json.encodeToJsonElement(it, typed.phy) } to json.encodeToJsonElement(dataSerializer, typed.data)
    }

    /**
     * Decodes this mode's [phy][StoredPacket.phy]; [Unit] for a mode without one. A row stored before
     * its mode had a phy has none and reads as the phy's defaults - so every field of a phy added
     * later needs one.
     */
    @Suppress("UNCHECKED_CAST")
    private fun phyOf(phyJson: String?, json: Json): Ph =
        if (phySerializer == null) Unit as Ph
        else json.decodeFromString(phySerializer, phyJson ?: "{}")
}

/**
 * A [PacketSpec] for a mode the extension delivers as a [DigitalPacket] (every mode but the stream
 * modes, which arrive as `StreamElement`s and are assembled app-side).
 */
internal abstract class DigitalPacketSpec<P : DigitalPacket, Ph : Any, D : Any>(
    mode: DigitalMode,
    phySerializer: KSerializer<Ph>?,
    dataSerializer: KSerializer<D>,
    parserVersion: Int = 0,
) : PacketSpec<Ph, D>(mode, phySerializer, dataSerializer, parserVersion) {
    abstract fun phyOf(packet: P): Ph

    /** The packet's content: the extension's decode, or - by default - this app's parse of its raw bits. */
    open fun dataOf(packet: P, phy: Ph): D = parse(packet.raw, phy)

    /** [frequencyHz] is what gets stored: the packet's own, or the tuned frequency when it has none. */
    fun interpret(packet: P, frequencyHz: Long): Interpreted<Ph, D> {
        val phy = phyOf(packet)
        return Interpreted(this, packet(packet.timestampMs, frequencyHz, packet.raw, phy, dataOf(packet, phy)))
    }
}

/**
 * A packet together with its mode's spec - what the pipeline passes around when it handles packets
 * of every mode alike, so the spec's typed functions can be applied without a cast.
 */
internal class Interpreted<Ph : Any, D : Any>(val spec: PacketSpec<Ph, D>, val packet: StoredPacket<Ph, D>) {
    val discarded: Boolean get() = spec.discard(packet)
    fun row(): PacketEntity = spec.toRow(packet)
    suspend fun fold(objects: ObjectStores) = spec.fold(packet, objects)
}

internal class Reparsed<Ph : Any, D : Any>(
    val interpreted: Interpreted<Ph, D>,
    /** The re-parse attributes the row to an object it did not belong to before (it had none, or
     *  another one): it is folded into that object. The object it belonged to before keeps it in its
     *  count - a fold cannot be taken back - and a row that keeps its object is not counted twice. */
    val newIdentity: Boolean,
)
