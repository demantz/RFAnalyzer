package com.mantz_it.rfanalyzer.digiext.ft8

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.digiext.callsign.callsignFlag
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
import com.mantz_it.rfanalyzer.digiext.ui.firstHeardMetric
import com.mantz_it.rfanalyzer.digiext.ui.heardFact
import com.mantz_it.rfanalyzer.digiext.ui.lastHeardMetric
import com.mantz_it.rfanalyzer.digiext.ui.nameMetric
import com.mantz_it.rfanalyzer.digiext.ui.packetsMetric
import com.mantz_it.rfanalyzer.digiext.ui.rememberDetail
import com.mantz_it.rfanalyzer.digiext.ui.trackOnMap
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import com.mantz_it.rfanalyzer.digiext.ui.share
import com.mantz_it.rfanalyzer.digiext.ui.common.rememberMessages

/**
 * <h1>RF Analyzer - FT8 Presenter</h1>
 *
 * Module:      Ft8Presenter.kt
 * Description: How FT8 stations are presented in the Digital tab: one row per transmitting
 *              callsign with its flag and last message, opening into its messages both ways.
 *              No ticker - the rows are the news.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class Ft8Presenter(
    /** One station's stored messages (sent and addressed to it), newest first. */
    private val messagesFor: ObjectPackets<Ft8Decode>,
    private val actions: CardActions,
) : TrackedObjectPresenter<Ft8Station> {
    override val noun = Noun("Call", "Calls")
    override val kindLabel = "FT8 station"
    override val identityWidth = 104.dp

    override val identityIcon: @Composable (TrackedObject<Ft8Station>) -> Unit = { station ->
        Text(callsignFlag(station.key) ?: "  ", fontSize = 13.sp)
    }

    override val metrics: List<TrackedMetric<TrackedObject<Ft8Station>>> = listOf(
        lastHeardMetric { it.lastSeenMs },
        TrackedMetric(
            label = "Signal (SNR)", header = "SNR",
            value = { item, _ -> "${formatFt8Snr(item.state.lastSnr)} dB" },
            sortKey = { it.state.lastSnr },
            width = 50.dp,
        ),
        packetsMetric(label = "Messages", header = "Msgs"),
        firstHeardMetric(),
        nameMetric("Callsign") { it.key },
    )

    override fun payload(item: TrackedObject<Ft8Station>): String? = ft8RowText(item)
    override fun payloadIdentities(item: TrackedObject<Ft8Station>): Collection<String> = item.state.lastMessage?.let(::ft8Callsigns).orEmpty()

    override fun summary(items: List<TrackedObject<Ft8Station>>, session: SessionWindow?): String = ft8Summary(items, session)

    override fun facts(item: TrackedObject<Ft8Station>, nowMs: Long): List<Field> = ft8StationFacts(item) + heardFact(item, nowMs)

    @Composable
    override fun actions(item: TrackedObject<Ft8Station>): List<PacketMenuAction> = ft8StationActions(
        item, actions.onShareJson, item.trackOnMap(actions.onTrackOnMap), actions.onOpenUrl, shareMessages = messagesFor.share(item.key, actions),
    )

    @Composable
    override fun Messages(item: TrackedObject<Ft8Station>, full: Boolean, onMore: (() -> Unit)?) {
        val messages = rememberMessages(item.key, messagesFor, full)
        MessageList(
            items = messages.items,
            total = messages.total,
            onLoadMore = messages.loadMore,
            key = { it.rowId },
            time = { it.timestampMs },
            text = { ft8MessageSummary(it.data) },
            identities = { ft8Callsigns(it.data) },
            extra = { "${formatFt8Snr(it.phy.snr)} dB" },
            extraWidth = 50.dp,
            maxRows = if (full) Int.MAX_VALUE else 5,
            emptyText = "No stored messages.",
            onMore = onMore,
            card = { message, collapse ->
                Ft8MessageCard(message = message, expanded = true, onExpand = collapse, actions = actions, outerPadding = PaddingValues(top = 2.dp, bottom = 6.dp))
            },
        )
    }
}

internal fun ft8OverviewSection(
    stations: StateFlow<List<TrackedObject<Ft8Station>>>,
    unfiltered: StateFlow<Int>,
    messagesFor: ObjectPackets<Ft8Decode>,
) = TrackedObjectSection(
    objects = stations,
    unfiltered = unfiltered,
    emptyText = "No FT8 stations received yet.",
    presenter = { _, actions -> remember(actions) { Ft8Presenter(messagesFor, actions.cardActions) } },
)
