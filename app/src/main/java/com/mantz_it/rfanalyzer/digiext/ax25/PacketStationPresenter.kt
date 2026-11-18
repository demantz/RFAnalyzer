package com.mantz_it.rfanalyzer.digiext.ax25

import com.mantz_it.rfanalyzer.digiext.LiveFeed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer.digiext.ui.Noun
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectPresenter
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectSection
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.MessageList
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
import com.mantz_it.rfanalyzer.digiext.ui.rememberDetail
import com.mantz_it.rfanalyzer.digiext.ui.trackedObjectActions
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedColumn
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.plural
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.common.oneLine
import com.mantz_it.rfanalyzer.digiext.ui.common.qrzUrl
import com.mantz_it.rfanalyzer.digiext.ui.defaultObjectMetrics
import com.mantz_it.rfanalyzer.digiext.ui.heardFact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import com.mantz_it.rfanalyzer.digiext.ui.share
import com.mantz_it.rfanalyzer.digiext.ui.common.rememberMessages

/**
 * <h1>RF Analyzer - Packet Station Presenter</h1>
 *
 * Module:      PacketStationPresenter.kt
 * Description: How AX.25 packet stations (BBSes, nodes, the users connecting to them -
 *              [PacketStation]) are presented in the Digital tab: the row text is what the station
 *              says about itself (mail waiting, its last bulletin, its beacon) or whom it talks to.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class PacketStationPresenter(
    /** One station's stored frames, both directions, newest first. */
    private val framesFor: ObjectPackets<Ax25Frame>,
    private val actions: CardActions,
) : TrackedObjectPresenter<PacketStation> {
    override val noun = Noun("Station", "Stations")
    override val kindLabel = "Packet station"
    override val metrics: List<TrackedMetric<TrackedObject<PacketStation>>> = defaultObjectMetrics()
    override val identityWidth = 112.dp

    override val columns: List<TrackedColumn<TrackedObject<PacketStation>>> = listOf(
        TrackedColumn(label = "Role", width = 40.dp, value = { roleName(it.state.role) }, priority = 2, alignment = TextAlign.Start),
    )

    override fun payload(item: TrackedObject<PacketStation>): String? = packetStationSummary(item)
    override fun payloadIdentities(item: TrackedObject<PacketStation>): Collection<String> =
        item.state.peers + item.state.mailFor.orEmpty() + listOfNotNull(item.state.lastBulletin?.from, item.state.lastBulletin?.to)

    override fun summary(items: List<TrackedObject<PacketStation>>, session: SessionWindow?): String = metaLine(
        items.count { it.state.role == "BBS" }.takeIf { it > 0 }?.let { "${formatCount(it)} BBS" },
        items.count { it.state.role == "NODE" }.takeIf { it > 0 }?.let { plural(it, "node", "nodes") },
    )

    override fun facts(item: TrackedObject<PacketStation>, nowMs: Long): List<Field> = fields {
        val s = item.state
        add("Node", s.nodeAlias?.let { alias -> alias + (s.routes?.let { " · $it routes" } ?: "") })
        add("Mail for", s.mailFor?.joinToString(" ")?.ifEmpty { "nobody" }, identity = !s.mailFor.isNullOrEmpty())
        if (s.bulletins > 0) add("Bulletins", "${s.bulletins} announced")
        add("Talked to", s.peers.joinToString(", ").ifEmpty { null }, identity = true)
        add("Last frame", s.lastFrame)
    } + if (item.packetCount > 0) listOf(heardFact(item, nowMs)) else emptyList()

    @Composable
    override fun actions(item: TrackedObject<PacketStation>): List<PacketMenuAction> = trackedObjectActions(
        item, PacketStationFamily, actions.onShareJson, onTrackOnMap = {}, onOpenUrl = actions.onOpenUrl,
        shareMessages = framesFor.share(item.key, actions),
        specific = listOf(
            PacketMenuAction(label = "QRZ.com", prominent = true) { actions.onOpenUrl(qrzUrl(baseCallsign(item.state.callsign))) },
            copyTextAction("Copy callsign", item.state.callsign),
        ),
    )

    /** The station's frames, both directions - a connection shows as its SABM/UA/I/DISC sequence. */
    @Composable
    override fun Messages(item: TrackedObject<PacketStation>, full: Boolean, onMore: (() -> Unit)?) {
        val frames = rememberMessages(item.key, framesFor, full)
        MessageList(
            items = frames.items,
            total = frames.total,
            onLoadMore = frames.loadMore,
            key = { it.rowId },
            time = { it.timestampMs },
            text = { frame ->
                val source = frame.data.source?.toString()
                (if (source != null && source != item.state.callsign) "← $source: " else "") + ax25FrameText(frame)
            },
            identities = ::ax25FrameCallsigns,
            maxRows = if (full) Int.MAX_VALUE else 5,
            emptyText = "No stored frames.",
            onMore = onMore,
            card = { frame, collapse ->
                Ax25FrameCard(frame = frame, expanded = true, onExpand = collapse, actions = actions, outerPadding = PaddingValues(top = 2.dp, bottom = 6.dp))
            },
        )
    }
}

private fun roleName(role: String?) = when (role) {
    "NODE" -> "Node"
    null -> ""
    else -> role
}

/**
 * A row's text: mail waiting, the last bulletin, the beacon, or whom it is connected to. A station
 * only ever called by others (kept on purpose, without packets of its own) says who called it.
 */
internal fun packetStationSummary(item: TrackedObject<PacketStation>): String? {
    val s = item.state
    return when {
        item.packetCount == 0 && s.peers.isNotEmpty() -> "Called by " + s.peers.joinToString(", ")
        s.mailFor != null && s.mailFor.isNotEmpty() -> "Mail for " + s.mailFor.joinToString(" ")
        s.lastBulletin != null -> "${s.lastBulletin.from} → ${s.lastBulletin.to}: ${s.lastBulletin.title}"
        s.nodeAlias != null -> "Node ${s.nodeAlias}" + (s.routes?.let { " · $it routes" } ?: "")
        s.beacon != null -> oneLine(s.beacon)
        s.mailFor != null -> "No mail waiting"
        s.peers.isNotEmpty() -> "Connected with " + s.peers.joinToString(", ")
        else -> s.lastFrame
    }
}

/** AX.25's second section: the packet-radio stations besides APRS, under the same ticker. */
internal fun packetStationOverviewSection(
    stations: StateFlow<List<TrackedObject<PacketStation>>>,
    unfiltered: StateFlow<Int>,
    liveFrames: LiveFeed<Ax25Frame>,
    framesFor: ObjectPackets<Ax25Frame>,
) = TrackedObjectSection(
    objects = stations,
    label = "Packet",
    unfiltered = unfiltered,
    emptyText = "No packet-radio stations (BBS, nodes, connections) heard yet.",
    presenter = { _, actions -> remember(actions) { PacketStationPresenter(framesFor, actions.cardActions) } },
    ticker = { Ax25Ticker(liveFrames, context, lines) },
)
