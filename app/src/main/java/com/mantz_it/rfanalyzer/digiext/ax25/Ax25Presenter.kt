package com.mantz_it.rfanalyzer.digiext.ax25

import com.mantz_it.rfanalyzer.digiext.LiveFeed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.aprs.AprsTextMessage
import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectPresenter
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.ui.Noun
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectSection
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.MessageList
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.defaultObjectMetrics
import com.mantz_it.rfanalyzer.digiext.ui.heardFact
import com.mantz_it.rfanalyzer.digiext.ui.rememberDetail
import com.mantz_it.rfanalyzer.digiext.ui.trackOnMap
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import com.mantz_it.rfanalyzer.digiext.ui.share
import com.mantz_it.rfanalyzer.digiext.ui.common.rememberMessages

/**
 * <h1>RF Analyzer - AX.25 Presenter</h1>
 *
 * Module:      Ax25Presenter.kt
 * Description: How AX.25/APRS stations are presented in the Digital tab - the AX.25 mode's "APRS"
 *              section; the other packet stations are [PacketStationPresenter]'s.
 *
 * A row is one [AprsStation], keyed by [ax25IdentityOf]: a plain reporting station and a named
 * APRS Object each get their own row, the same identity the Map subtab's markers use.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class Ax25Presenter(
    /** One station's stored frames, both directions, newest first. */
    private val framesFor: ObjectPackets<Ax25Frame>,
    private val actions: CardActions,
) : TrackedObjectPresenter<AprsStation> {
    override val noun = Noun("Station", "Stations")
    override val kindLabel = "APRS station"
    override val metrics: List<TrackedMetric<TrackedObject<AprsStation>>> = defaultObjectMetrics()

    /** A 9-character SSID-qualified callsign ("KF6YVS-15") and its symbol. */
    override val identityWidth = 116.dp

    override val identityIcon: @Composable (TrackedObject<AprsStation>) -> Unit = { StationSymbol(it.state) }

    override fun payload(item: TrackedObject<AprsStation>): String = aprsStationRowText(item)
    override fun payloadIdentities(item: TrackedObject<AprsStation>): Collection<String> = aprsReportCallsigns(item.state.lastInfo)

    override fun summary(items: List<TrackedObject<AprsStation>>, session: SessionWindow?): String = metaLine(
        "${formatCount(items.count { it.lat != null })} with position",
        session?.let { window -> items.count { it.lastSeenMs in window }.takeIf { it > 0 }?.let { "${formatCount(it)} heard this session" } },
    )

    override fun facts(item: TrackedObject<AprsStation>, nowMs: Long): List<Field> = aprsStationFacts(item) + heardFact(item, nowMs)

    /** Invoked only for an opened row or menu, so the frame subscription behind "Share messages" is
     *  not opened per visible row. */
    @Composable
    override fun actions(item: TrackedObject<AprsStation>): List<PacketMenuAction> = aprsStationActions(
        item, AprsStationFamily, actions.onShareJson, item.trackOnMap(actions.onTrackOnMap), actions.onOpenUrl,
        shareMessages = framesFor.share(item.key, actions),
    )

    @Composable
    override fun Messages(item: TrackedObject<AprsStation>, full: Boolean, onMore: (() -> Unit)?) {
        val frames = rememberMessages(item.key, framesFor, full)
        MessageList(
            items = frames.items,
            total = frames.total,
            onLoadMore = frames.loadMore,
            key = { it.rowId },
            time = { it.timestampMs },
            text = { frame -> stationFrameText(item.state, frame) },
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

/** A frame in a station's list: its report, or - for a message that names the station - who sent it. */
internal fun stationFrameText(station: AprsStation, frame: Ax25Frame): String {
    val source = frame.data.source?.toString()
    val info = frame.data.aprs
    return if (source != null && source != station.callsign && info is AprsTextMessage) "← $source: ${info.text}"
    else ax25FrameText(frame)
}

/** AX.25's APRS section: every station and Object, under the latest-frame ticker. */
internal fun ax25OverviewSection(
    stations: StateFlow<List<TrackedObject<AprsStation>>>,
    unfiltered: StateFlow<Int>,
    liveFrames: LiveFeed<Ax25Frame>,
    framesFor: ObjectPackets<Ax25Frame>,
) = TrackedObjectSection(
    objects = stations,
    label = "APRS",
    unfiltered = unfiltered,
    emptyText = "No APRS stations received yet.",
    presenter = { _, actions -> remember(actions) { Ax25Presenter(framesFor, actions.cardActions) } },
    ticker = { Ax25Ticker(liveFrames, context, lines) },
)
