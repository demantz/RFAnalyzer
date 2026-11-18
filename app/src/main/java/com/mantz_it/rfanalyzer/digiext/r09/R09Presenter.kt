package com.mantz_it.rfanalyzer.digiext.r09

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectPresenter
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.ui.Noun
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedColumn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectSection
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.MessageList
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
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
 * <h1>RF Analyzer - R09 Presenter</h1>
 *
 * Module:      R09Presenter.kt
 * Description: How R09 vehicles are presented in the Digital tab: one row per run (Linie, Kurs,
 *              Ziel) with its deviation and the Meldepunkt it reported last, opening into its
 *              visits of Meldepunkte.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class R09Presenter(
    private val messagesFor: ObjectPackets<R09Telegram>,
    private val actions: CardActions,
) : TrackedObjectPresenter<R09Bus> {
    override val noun = Noun("Vehicle", "Vehicles")
    override val kindLabel = "R09 vehicle"
    override val identityWidth = 72.dp

    override fun identity(item: TrackedObject<R09Bus>): String = "Linie ${item.state.liniennummer}"

    /** A bus's row is its columns. */
    override val payloadMinFraction = 0f

    override val columns: List<TrackedColumn<TrackedObject<R09Bus>>> = listOf(
        TrackedColumn(label = "Kurs", width = 34.dp, value = { it.state.kursnummer.toString() }, priority = 1),
        TrackedColumn(label = "Abw.", width = 50.dp, value = { formatDeviation(it.state.lastZw) }, priority = 2, factLabel = "Abweichung"),
        TrackedColumn(
            label = "Meldepunkt",
            width = 92.dp,
            value = { bus -> bus.state.lastMeldepunkt?.let { "${it.meldepunkt} ${tabelleneintragMarks(it.receivedTabelleneintraege)}" }.orEmpty() },
            priority = 1,
            alignment = TextAlign.Start,
        ),
        TrackedColumn(label = "Ziel", width = 36.dp, value = { it.state.zielnummer.takeIf { z -> z != 0 }?.toString().orEmpty() }, priority = 3), // 0 = no destination
    )

    override val metrics: List<TrackedMetric<TrackedObject<R09Bus>>> = listOf(
        lastHeardMetric { it.lastSeenMs },
        packetsMetric(label = "Telegrams", header = "Tel."),
        nameMetric("Linie") { it.state.liniennummer * 1000 + it.state.kursnummer },
    )

    override fun summary(items: List<TrackedObject<R09Bus>>, session: SessionWindow?): String =
        items.map { it.state.liniennummer }.distinct().size.let { if (it == 1) "1 Linie" else "${formatCount(it)} Linien" }

    override fun facts(item: TrackedObject<R09Bus>, nowMs: Long): List<Field> = fields {
        item.state.lastMeldepunkt?.let { mp ->
            add("Meldepunkt", "${mp.meldepunkt} · " + R09Paket.Tabelleneintrag.entries.filter { it in mp.receivedTabelleneintraege }.joinToString(", ") { tabelleneintragName(it) })
        }
        add("Meldepunkte", "${formatCount(item.state.meldepunkte.size)} reported")
    } + heardFact(item, nowMs)

    @Composable
    override fun actions(item: TrackedObject<R09Bus>): List<PacketMenuAction> = trackedObjectActions(
        item, R09BusFamily, actions.onShareJson, onTrackOnMap = {}, onOpenUrl = {},
        shareMessages = messagesFor.share(item.key, actions),
    )

    /** The bus's visits of Meldepunkte, newest first; a visit opens into its telegrams. */
    @Composable
    override fun Messages(item: TrackedObject<R09Bus>, full: Boolean, onMore: (() -> Unit)?) {
        val messages = rememberMessages(item.key, messagesFor, full)
        val visits = remember(messages.items) { groupR09ByMeldepunktVisit(messages.items) }
        MessageList(
            items = visits,
            onLoadMore = messages.loadMore,
            key = { "${it.meldepunkt}@${it.windowStartMs}" },
            time = { it.messages.first().timestampMs },
            text = { visit ->
                val status = visit.toStatus()
                "MP ${visit.meldepunkt}  ${tabelleneintragMarks(status.receivedTabelleneintraege)}  ${tabelleneintragName(status.lastReceivedTabelleneintrag)}"
            },
            extra = { formatDeviation(it.messages.first().data.zw) },
            extraWidth = 56.dp,
            maxRows = if (full) Int.MAX_VALUE else 5,
            emptyText = "No stored telegrams.",
            onMore = onMore,
            card = { visit, _ ->
                Column {
                    visit.messages.forEach { telegram ->
                        var open by remember(telegram.rowId) { mutableStateOf(false) }
                        R09MessageCard(
                            message = telegram, expanded = open, onExpand = { open = !open }, actions = actions,
                            outerPadding = PaddingValues(top = 2.dp, bottom = 4.dp),
                        )
                    }
                }
            },
        )
    }
}

internal fun r09OverviewSection(
    buses: StateFlow<List<TrackedObject<R09Bus>>>,
    unfiltered: StateFlow<Int>,
    liveTelegrams: Flow<R09Telegram>,
    messagesFor: ObjectPackets<R09Telegram>,
) = TrackedObjectSection(
    objects = buses,
    unfiltered = unfiltered,
    emptyText = "No R09 telegrams received yet.",
    presenter = { _, actions -> remember(actions) { R09Presenter(messagesFor, actions.cardActions) } },
    ticker = { R09Ticker(liveTelegrams, context, lines) },
)
