package com.mantz_it.rfanalyzer.digiext.pocsag

import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketSpec
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.db.ObjectKind
import com.mantz_it.rfanalyzer.digiext.db.ObjectStore
import com.mantz_it.rfanalyzer.digiext.db.ObjectStores
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPacket
import com.mantz_it.rfanalyzer_ipc.digiext.PocsagPhy
import kotlinx.serialization.Serializable

/**
 * <h1>RF Analyzer - POCSAG Data</h1>
 *
 * Module:      PocsagData.kt
 * Description: The data layer of POCSAG: [PocsagSpec] (the extension sends the payload bits,
 *              [PocsagParser] reads them) and the tracked [PocsagPager], keyed by its address, with
 *              its family and the fold of a page into it.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * A POCSAG pager (capcode, the object's key): its most recent page. The message history is not
 * kept here - it is queried from `digital_packets` by key.
 */
@Serializable
data class PocsagPager(
    val function: Int,
    val message: PocsagMessage,
) {
    /** The same reading precedence as the page's own ([primaryReading]), so the row text agrees
     *  with the message list. */
    val primaryText: String? get() = message.primaryText(function)
}

internal val PocsagPagerFamily = ObjectFamily(
    kind = ObjectKind.POCSAG_PAGER,
    serializer = PocsagPager.serializer(),
    displayName = { key, _ -> "Addr $key" },
)

/** The `objectKey` of a pager and of every page addressed to it. */
internal fun pocsagObjectKey(address: Long): String = address.toString()

internal suspend fun applyPocsagPage(pagers: ObjectStore<PocsagPager>, page: PocsagPage) {
    val pager = PocsagPager(page.phy.function, page.data)
    pagers.update(pocsagObjectKey(page.phy.address), page.timestampMs, page.knownFrequencyHz, create = { pager }) { pager }
}

/** POCSAG is parsed app-side: the extension sends the payload bits, [PocsagParser] reads them. */
internal object PocsagSpec : DigitalPacketSpec<PocsagPacket, PocsagPhy, PocsagMessage>(
    mode = DigitalMode.POCSAG,
    phySerializer = PocsagPhy.serializer(),
    dataSerializer = PocsagMessage.serializer(),
    parserVersion = PocsagParser.PARSER_VERSION,
) {
    override fun phyOf(packet: PocsagPacket) = packet.phy
    override fun parse(raw: Bytes, phy: PocsagPhy) = PocsagParser.parse(raw, phy)
    override fun objectKey(raw: Bytes?, phy: PocsagPhy, data: PocsagMessage) = pocsagObjectKey(phy.address)
    override suspend fun fold(packet: PocsagPage, objects: ObjectStores) = applyPocsagPage(objects[PocsagPagerFamily], packet)
}
