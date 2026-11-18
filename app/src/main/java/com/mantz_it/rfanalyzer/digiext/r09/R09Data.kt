package com.mantz_it.rfanalyzer.digiext.r09

import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketSpec
import com.mantz_it.rfanalyzer.digiext.db.ObjectStores
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.R09Packet
import com.mantz_it.rfanalyzer_ipc.digiext.R09Phy
import kotlinx.serialization.Serializable
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore

/**
 * <h1>RF Analyzer - R09 Data</h1>
 *
 * Module:      R09Data.kt
 * Description: The data layer of R09: [R09Spec] (the extension sends the telegram's bits and what
 *              it measured, [R09Parser] reads them), the tracked [R09Bus], keyed by its
 *              (Liniennummer, Kursnummer, Zielnummer) triple, with its family and fold, and the
 *              grouping of a bus's telegrams into visits of a Meldepunkt.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** One Meldepunkt (stop/checkpoint) of a bus: which Tabelleneinträge have been received for it. */
@Serializable
data class MeldepunktStatus(
    val meldepunkt: Int,
    val receivedTabelleneintraege: Set<R09Paket.Tabelleneintrag>,
    /** Highlighted as the newest. */
    val lastReceivedTabelleneintrag: R09Paket.Tabelleneintrag,
    val lastUpdateMs: Long,
)

/**
 * An R09 bus/tram, keyed by the full (Liniennummer, Kursnummer, Zielnummer) triple - there is no
 * persistent vehicle ID in the protocol. [zielnummer] is part of the identity, so a mid-run
 * destination change is a different tracked bus (it *is* a different run).
 */
@Serializable
data class R09Bus(
    val liniennummer: Int,
    val kursnummer: Int,
    val zielnummer: Int,
    val lastZw: Int = 0,
    /** The Meldepunkt reported most recently. Its Tabelleneinträge accumulate while the bus keeps
     *  reporting it and start over at the next one. */
    val lastMeldepunkt: MeldepunktStatus? = null,
    /** Every Meldepunkt this bus has reported. */
    val meldepunkte: Set<Int> = emptySet(),
) {
    /** Meldepunkte reported before [lastMeldepunkt]. */
    val olderMeldepunkteCount: Int get() = (meldepunkte.size - 1).coerceAtLeast(0)
}

internal val R09BusFamily = ObjectFamily(
    kind = ObjectKind.R09_BUS,
    serializer = R09Bus.serializer(),
    displayName = { key, _ -> key },
)

/** The `objectKey` of a telegram's bus, or `""` for a short telegram (`laenge <= 3`): those carry
 *  no kursnummer and would all collapse onto one meaningless bus, so they are not tracked. */
internal fun r09ObjectKey(paket: R09Paket): String =
    if (paket.laenge > 3) r09BusId(paket.liniennummer, paket.kursnummer, paket.zielnummer) else ""

internal suspend fun applyR09Telegram(buses: ObjectStore<R09Bus>, telegram: R09Telegram) {
    val paket = telegram.data
    val key = r09ObjectKey(paket).ifEmpty { return }
    buses.update(key, telegram.timestampMs, telegram.knownFrequencyHz, create = { R09Bus(paket.liniennummer, paket.kursnummer, paket.zielnummer) }) { bus ->
        // The same visit as groupR09ByMeldepunktVisit's: a later pass of the same Meldepunkt starts
        // its Tabelleneinträge over, so the row and the visit list agree.
        val previous = bus.lastMeldepunkt?.takeIf { it.meldepunkt == paket.mp && telegram.timestampMs - it.lastUpdateMs <= MELDEPUNKT_VISIT_WINDOW_MS }
        bus.copy(
            lastZw = paket.zw,
            lastMeldepunkt = MeldepunktStatus(
                meldepunkt = paket.mp,
                receivedTabelleneintraege = previous?.receivedTabelleneintraege.orEmpty() + paket.tabelleneintrag,
                lastReceivedTabelleneintrag = paket.tabelleneintrag,
                lastUpdateMs = telegram.timestampMs,
            ),
            meldepunkte = bus.meldepunkte + paket.mp,
        )
    }
}

/** R09 is parsed app-side: the extension sends the telegram's bits and what it measured ([R09Phy]). */
internal object R09Spec : DigitalPacketSpec<R09Packet, R09Phy, R09Paket>(
    mode = DigitalMode.R09,
    phySerializer = R09Phy.serializer(),
    dataSerializer = R09Paket.serializer(),
    parserVersion = R09Parser.PARSER_VERSION,
) {
    override fun phyOf(packet: R09Packet) = packet.phy
    override fun parse(raw: Bytes, phy: R09Phy) = R09Parser.parse(raw)
    override fun objectKey(raw: Bytes?, phy: R09Phy, data: R09Paket) = r09ObjectKey(data)
    override suspend fun fold(packet: R09Telegram, objects: ObjectStores) = applyR09Telegram(objects[R09BusFamily], packet)
}

/** The (Linie, Kurs, Ziel) triple's canonical string form. */
fun r09BusId(liniennummer: Int, kursnummer: Int, zielnummer: Int): String = "L$liniennummer/K$kursnummer/Z$zielnummer"

/** Telegrams for one Meldepunkt this close together belong to one visit of it; a later one starts
 *  a new visit (a bus on the same run again, or old data under the same Linie/Kurs/Ziel). */
internal const val MELDEPUNKT_VISIT_WINDOW_MS = 5 * 60_000L

/** One visit to a Meldepunkt (stop/checkpoint): the telegrams received for it within one
 *  continuous window, newest first. */
data class MeldepunktGroup(
    val meldepunkt: Int,
    val messages: List<R09Telegram>, // this visit's telegrams, newest first
    val windowStartMs: Long,
)

/**
 * Splits [messagesNewestFirst] (one bus's stored R09 telegrams, newest first) into per-visit
 * groups: a new group starts whenever consecutive messages *for the same meldepunkt* are more than
 * [windowMs] apart, so a later pass of the same Linie/Kurs/Ziel does not inherit an earlier
 * visit's checkmarks.
 *
 * A pure function over queried data rather than part of the fold ([R09Bus]), which only tracks the
 * current Meldepunkt - all the row shows. The window can be tuned without touching stored data.
 *
 * Gaps for *other* meldepunkte don't affect a given one's own windowing - real routes interleave
 * meldepunkte freely (MP 5, then MP 12, then MP 5 again a minute later is still "MP 5, still open").
 */
fun groupR09ByMeldepunktVisit(
    messagesNewestFirst: List<R09Telegram>,
    windowMs: Long = MELDEPUNKT_VISIT_WINDOW_MS,
): List<MeldepunktGroup> {
    if (messagesNewestFirst.isEmpty()) return emptyList()

    data class WorkingGroup(val meldepunkt: Int, val messages: MutableList<R09Telegram>, var lastSeenMs: Long, val windowStartMs: Long)

    val openGroupByMeldepunkt = HashMap<Int, WorkingGroup>()
    val allGroups = mutableListOf<WorkingGroup>()

    // Process oldest-first so "consecutive" has its natural meaning.
    for (msg in messagesNewestFirst.asReversed()) {
        val mp = msg.data.mp
        val ts = msg.timestampMs
        val open = openGroupByMeldepunkt[mp]
        if (open != null && ts - open.lastSeenMs <= windowMs) {
            open.messages.add(msg)
            open.lastSeenMs = ts
        } else {
            val group = WorkingGroup(mp, mutableListOf(msg), lastSeenMs = ts, windowStartMs = ts)
            openGroupByMeldepunkt[mp] = group
            allGroups.add(group)
        }
    }

    return allGroups
        .map { MeldepunktGroup(it.meldepunkt, it.messages.asReversed(), it.windowStartMs) }
        .sortedByDescending { it.messages.first().timestampMs } // messages is newest-first, so first() = newest
}

/** The [MeldepunktStatus] of one windowed visit group. */
fun MeldepunktGroup.toStatus(): MeldepunktStatus = MeldepunktStatus(
    meldepunkt = meldepunkt,
    receivedTabelleneintraege = messages.map { it.data.tabelleneintrag }.toSet(),
    lastReceivedTabelleneintrag = messages.first().data.tabelleneintrag, // messages is newest-first
    lastUpdateMs = messages.first().timestampMs,
)
