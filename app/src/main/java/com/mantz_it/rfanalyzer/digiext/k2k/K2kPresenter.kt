package com.mantz_it.rfanalyzer.digiext.k2k

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.names
import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer.digiext.ui.ModePresenter
import com.mantz_it.rfanalyzer.digiext.ui.Noun
import com.mantz_it.rfanalyzer.digiext.ui.OverviewContext
import com.mantz_it.rfanalyzer.digiext.ui.OverviewSection
import com.mantz_it.rfanalyzer.digiext.ui.TrackedModeSection
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedColumn
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.formatAge
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.common.formatDuration
import com.mantz_it.rfanalyzer.digiext.ui.common.formatShortTime
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map

/**
 * <h1>RF Analyzer - K2K Presenter</h1>
 *
 * Module:      K2kPresenter.kt
 * Description: How RTTY and PSK31 transmissions are presented in the Digital tab. Keyboard modes
 *              have no tracked objects: each row is one finished transmission - its start time,
 *              who to whom and the text over two lines - and opens into the whole text.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class K2kPresenter(private val actions: CardActions) : ModePresenter<K2kTransmission> {
    override val noun = Noun("Transmission", "Transmissions")
    override val identityWidth = 46.dp
    override val monoPayload = true
    // Opened, the row shows the transmission's card with the whole text.
    override val openedPayloadMaxLines get() = payloadMaxLines
    override val hasDetailsPage = false

    override fun objectKey(item: K2kTransmission): String = item.rowId.toString()
    override fun identity(item: K2kTransmission): String = formatShortTime(System.currentTimeMillis(), item.timestampMs)
    override fun lastSeenMs(item: K2kTransmission): Long = item.timestampMs
    override fun payload(item: K2kTransmission): String = k2kRowText(item)
    override fun payloadIdentities(item: K2kTransmission): Collection<String> = listOfNotNull(item.data.fromCallsign, item.data.toCallsign)

    override val metrics: List<TrackedMetric<K2kTransmission>> = listOf(
        TrackedMetric(
            label = "Start time", header = "Age",
            value = { item, now -> formatAge(now, item.timestampMs) },
            sortKey = { it.timestampMs }, isRecency = true,
        ),
        TrackedMetric(
            label = "Duration", header = "Duration",
            value = { item, _ -> item.endTimestampMs?.let { formatDuration(it - item.timestampMs) }.orEmpty() },
            sortKey = { (it.endTimestampMs ?: it.timestampMs) - it.timestampMs },
            width = 58.dp,
        ),
        TrackedMetric(
            label = "Length", header = "Chars",
            value = { item, _ -> formatCount(item.data.text.length) },
            sortKey = { it.data.text.length },
            width = 52.dp,
        ),
    )

    override fun summary(items: List<K2kTransmission>, session: SessionWindow?): String =
        "${formatCount(items.sumOf { it.data.text.length })} characters"

    @Composable
    override fun actions(item: K2kTransmission): List<PacketMenuAction> = k2kMessageActions(item, actions)

    /** The whole transmission: its card, opened. */
    @Composable
    override fun Opened(item: K2kTransmission, hiddenColumns: List<TrackedColumn<K2kTransmission>>, nowMs: Long, onDetails: (() -> Unit)?) {
        K2kMessageCard(message = item, expanded = true, onExpand = {}, actions = actions, outerPadding = PaddingValues(top = 2.dp, bottom = 4.dp))
    }
}

/** The K2K section: the finished transmissions under the ticker of the one being received. */
class K2kOverviewSection(
    private val mode: DigitalMode,
    private val finished: StateFlow<List<K2kTransmission>>,
    private val current: StateFlow<K2kTransmission?>,
    private val currentEarlierText: StateFlow<String>,
) : OverviewSection {
    override val count: Flow<Int> = finished.map { it.size }

    @Composable
    override fun Content(context: OverviewContext, modifier: Modifier) {
        val items by finished.collectAsState()
        val ongoing by current.collectAsState()
        val earlierText by currentEarlierText.collectAsState()
        val presenter = remember(context.actions) { K2kPresenter(context.actions.cardActions) }
        TrackedModeSection(
            items = items,
            presenter = presenter,
            nowMs = context.nowMs,
            live = context.live,
            headerStart = context.headerStart,
            emptyText = "No ${mode.names.name} transmissions received yet.",
            filterActive = context.filterActive,
            ticker = if (context.live != null) { { K2kTicker(ongoing, earlierText, context, context.tickerLines) } } else null,
            modifier = modifier,
        )
    }
}
