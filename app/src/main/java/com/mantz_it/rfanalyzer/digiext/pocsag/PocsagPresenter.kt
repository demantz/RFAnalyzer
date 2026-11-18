package com.mantz_it.rfanalyzer.digiext.pocsag

import com.mantz_it.rfanalyzer.digiext.LiveFeed
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
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
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.heardFact
import com.mantz_it.rfanalyzer.digiext.ui.lastHeardMetric
import com.mantz_it.rfanalyzer.digiext.ui.nameMetric
import com.mantz_it.rfanalyzer.digiext.ui.packetsMetric
import com.mantz_it.rfanalyzer.digiext.ui.rememberDetail
import com.mantz_it.rfanalyzer.digiext.ui.trackedObjectActions
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import com.mantz_it.rfanalyzer.digiext.ui.share
import com.mantz_it.rfanalyzer.digiext.ui.common.rememberMessages

/**
 * <h1>RF Analyzer - POCSAG Presenter</h1>
 *
 * Module:      PocsagPresenter.kt
 * Description: How POCSAG capcodes are presented in the Digital tab: one row per capcode with its
 *              last page, opening into its pages.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class PocsagPresenter(
    /** One capcode's stored pages, newest first. The pager itself keeps only its last page. */
    private val messagesFor: ObjectPackets<PocsagPage>,
    private val actions: CardActions,
) : TrackedObjectPresenter<PocsagPager> {
    override val noun = Noun("Capcode", "Capcodes")
    override val kindLabel = "POCSAG capcode"

    /** Capcodes run to 7 digits. */
    override val identityWidth = 84.dp

    override fun identity(item: TrackedObject<PocsagPager>): String = item.key

    override val identityIcon: @Composable (TrackedObject<PocsagPager>) -> Unit = {
        Icon(Icons.Default.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
    }

    /** The capcode as a number: 1023669 must not sort before 999. */
    override val metrics: List<TrackedMetric<TrackedObject<PocsagPager>>> = listOf(
        lastHeardMetric { it.lastSeenMs },
        packetsMetric(label = "Pages", header = "Pages"),
        nameMetric("Capcode") { it.key.toLongOrNull() ?: 0L },
    )

    override fun payload(item: TrackedObject<PocsagPager>): String = pocsagPageText(item.state.message, item.state.function)

    override fun facts(item: TrackedObject<PocsagPager>, nowMs: Long): List<Field> = fields {
        add("Last page", readingName(item.state.message.primaryReading(item.state.function)) + " · function ${item.state.function}")
    } + heardFact(item, nowMs)

    @Composable
    override fun actions(item: TrackedObject<PocsagPager>): List<PacketMenuAction> = trackedObjectActions(
        item, PocsagPagerFamily, actions.onShareJson, onTrackOnMap = {}, onOpenUrl = {},
        shareMessages = messagesFor.share(item.key, actions),
        specific = listOf(copyTextAction("Copy capcode", item.key)),
    )

    @Composable
    override fun Messages(item: TrackedObject<PocsagPager>, full: Boolean, onMore: (() -> Unit)?) {
        val pages = rememberMessages(item.key, messagesFor, full)
        MessageList(
            items = pages.items,
            total = pages.total,
            onLoadMore = pages.loadMore,
            key = { it.rowId },
            time = { it.timestampMs },
            text = ::pocsagPageText,
            maxRows = if (full) Int.MAX_VALUE else 5,
            emptyText = "No stored pages.",
            onMore = onMore,
            card = { page, collapse ->
                PocsagMessageCard(page = page, expanded = true, onExpand = collapse, actions = actions, outerPadding = PaddingValues(top = 2.dp, bottom = 6.dp))
            },
        )
    }
}

internal fun pocsagOverviewSection(
    pagers: StateFlow<List<TrackedObject<PocsagPager>>>,
    unfiltered: StateFlow<Int>,
    livePages: LiveFeed<PocsagPage>,
    messagesFor: ObjectPackets<PocsagPage>,
) = TrackedObjectSection(
    objects = pagers,
    unfiltered = unfiltered,
    emptyText = "No POCSAG pages received yet.",
    presenter = { _, actions -> remember(actions) { PocsagPresenter(messagesFor, actions.cardActions) } },
    ticker = { PocsagTicker(livePages, context, lines) },
)
