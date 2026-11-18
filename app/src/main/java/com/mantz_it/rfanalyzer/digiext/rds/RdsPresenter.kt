package com.mantz_it.rfanalyzer.digiext.rds

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectPresenter
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.ui.DetailsGroup
import com.mantz_it.rfanalyzer.digiext.ui.InlineFacts
import com.mantz_it.rfanalyzer.digiext.ui.Noun
import com.mantz_it.rfanalyzer.digiext.ui.common.CollapsibleSection
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedColumn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectSection
import com.mantz_it.rfanalyzer.digiext.ui.common.ActionStrip
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.Fields
import com.mantz_it.rfanalyzer.digiext.ui.common.MessageList
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.detailsAction
import com.mantz_it.rfanalyzer.digiext.ui.heardFact
import com.mantz_it.rfanalyzer.digiext.ui.lastHeardMetric
import com.mantz_it.rfanalyzer.digiext.ui.nameMetric
import com.mantz_it.rfanalyzer.digiext.ui.packetsMetric
import com.mantz_it.rfanalyzer.digiext.ui.rememberDetail
import com.mantz_it.rfanalyzer.digiext.ui.trackedObjectActions
import com.mantz_it.rfanalyzer.digiext.ui.withHiddenColumns
import com.mantz_it.rfanalyzer.ui.composable.IconTuneStation
import java.util.Locale
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import com.mantz_it.rfanalyzer.digiext.ui.share
import androidx.compose.runtime.produceState

/**
 * <h1>RF Analyzer - RDS Presenter</h1>
 *
 * Module:      RdsPresenter.kt
 * Description: How RDS stations are presented in the Digital tab: one row per station (by PI)
 *              with its name and radio text, the one being received pinned on top. An opened
 *              station shows its facts and three sections: traffic messages, other networks and
 *              its groups by type.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class RdsPresenter(
    private val nowMs: () -> Long,
    /** One station's stored groups, newest first. */
    private val groupsFor: ObjectPackets<RdsGroup>,
    /** One station's TMC messages (still broadcast or not), newest first. */
    private val tmcMessagesFor: (stationKey: String) -> Flow<List<TrackedObject<ResolvedTmcMessage>>>,
    private val actions: CardActions,
    private val onTuneToFrequency: (frequencyHz: Long) -> Unit,
    /** Name PTY codes by the North American table - see [LocalRdsPtyUs]. */
    private val ptyUs: Boolean,
) : TrackedObjectPresenter<RdsStation> {
    override val noun = Noun("Station", "Stations")
    override val kindLabel = "RDS station"
    override val identityWidth = 104.dp

    /** The programme name; the PI where none was received. */
    override fun identity(item: TrackedObject<RdsStation>): String = item.state.name ?: "PI ${item.key}"

    override val columns: List<TrackedColumn<TrackedObject<RdsStation>>> = listOf(
        TrackedColumn(label = "MHz", width = 42.dp, value = { it.frequencyHz?.let { hz -> "%.1f".format(Locale.US, hz / 1_000_000.0) }.orEmpty() }, priority = 1, factLabel = "Frequency"),
        TrackedColumn(label = "Programme", width = 92.dp, value = { it.state.pty?.let { pty -> rdsPtyName(pty, ptyUs) }.orEmpty() }, priority = 4, alignment = TextAlign.Start),
    )

    override val metrics: List<TrackedMetric<TrackedObject<RdsStation>>> = listOf(
        lastHeardMetric { it.lastSeenMs },
        packetsMetric(label = "Groups", header = "Groups"),
        nameMetric("Name") { (it.state.name ?: "~${it.key}").lowercase() },
        TrackedMetric(
            label = "Frequency", header = "MHz",
            value = { item, _ -> item.frequencyHz?.let { "%.1f".format(Locale.US, it / 1_000_000.0) }.orEmpty() },
            sortKey = { it.frequencyHz ?: 0L }, ascending = true,
        ),
    )

    override val pinned: (TrackedObject<RdsStation>) -> Boolean = { it.state.isLive(nowMs()) }
    // The tuned station sends groups continuously.
    override val flashOnUpdate = false

    override fun payload(item: TrackedObject<RdsStation>): String? = item.state.radioText?.trim()?.takeIf { it.isNotEmpty() }

    /** Radio text is long and changes; the frequency column should still fit beside it. */
    override val payloadMinFraction = 0.35f

    override fun summary(items: List<TrackedObject<RdsStation>>, session: SessionWindow?): String =
        items.count { !it.state.tuned }.takeIf { it > 0 }?.let { "${formatCount(it)} known from other stations" }.orEmpty()

    override fun facts(item: TrackedObject<RdsStation>, nowMs: Long): List<Field> = rdsStationFacts(item, ptyUs) + heardFact(item, nowMs)

    @Composable
    override fun actions(item: TrackedObject<RdsStation>): List<PacketMenuAction> = trackedObjectActions(
        item, RdsStationFamily, actions.onShareJson, onTrackOnMap = {}, onOpenUrl = {},
        shareMessages = groupsFor.share(item.key, actions),
        specific = listOfNotNull(
            item.frequencyHz?.let { freq -> PacketMenuAction(label = "Tune", icon = IconTuneStation, prominent = true) { onTuneToFrequency(freq) } },
            copyTextAction("Copy PI code", item.key),
        ),
    )

    /** Facts and actions, then traffic messages, other networks and groups - one open at a time. */
    @Composable
    override fun Opened(item: TrackedObject<RdsStation>, hiddenColumns: List<TrackedColumn<TrackedObject<RdsStation>>>, nowMs: Long, onDetails: (() -> Unit)?) {
        Column {
            // The short facts; frequencies, data applications and TMC are on the details page.
            val short = rdsStationFacts(item, ptyUs).filter { it.label in OPENED_ROW_FACTS }
            InlineFacts(withHiddenColumns(item, hiddenColumns.filter { it.label != "MHz" }, short))
            ActionStrip(listOfNotNull(onDetails?.let(::detailsAction)) + actions(item), modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
            StationSections(item)
        }
    }

    @Composable
    override fun DetailsContent(item: TrackedObject<RdsStation>, nowMs: Long) {
        DetailsGroup("Station") { Fields(facts(item, nowMs)) }
        DetailsGroup("Received") { StationSections(item) }
    }

    @Composable
    private fun StationSections(item: TrackedObject<RdsStation>) {
        val tmcMessages = rememberDetail(item.key, tmcMessagesFor)
        val activeTmcMessages = remember(tmcMessages, item.state.lastOwnGroupMs) {
            tmcMessages.filter { isActiveTmcMessage(it, item.state.lastOwnGroupMs) }
        }
        var open by remember(item.key) { mutableStateOf<String?>(null) }
        fun toggle(section: String) { open = if (open == section) null else section }

        CollapsibleSection(title = "Traffic messages · ${activeTmcMessages.size}", expanded = open == "tmc", onToggle = { toggle("tmc") }) {
            MessageList(
                items = activeTmcMessages,
                key = { it.key },
                time = { it.lastSeenMs },
                text = { "${it.state.eventText} · location ${it.state.locationCode}" },
                maxRows = MAX_TMC_ROWS,
                emptyText = "No active traffic messages.",
                card = { message, _ -> TmcMessageRow(message.state) },
            )
        }
        CollapsibleSection(title = "Other networks · ${item.state.otherNetworks.size}", expanded = open == "eon", onToggle = { toggle("eon") }) {
            MessageList(
                items = item.state.otherNetworks,
                key = { it.pi },
                time = { item.lastSeenMs },
                text = { eon -> listOfNotNull(eon.ps.trim().ifEmpty { null }, "PI %04X".format(Locale.US, eon.pi), eon.pty?.let { rdsPtyName(it, ptyUs) }).joinToString(" · ") },
                extra = { "${it.alternativeFrequencies.size} AF" },
                emptyText = "No other networks announced.",
                card = { eon, _ -> EonFrequencyList(eon, onTuneToFrequency) },
            )
        }
        val groupCount = item.state.groupTypeCounts.values.sum().takeIf { it > 0 } ?: item.packetCount
        CollapsibleSection(title = "Groups · ${formatCount(groupCount)}", expanded = open == "groups", onToggle = { toggle("groups") }) {
            GroupsByType(item)
        }
    }

    /** The station's groups by type: how many, when last, and the latest one stored of each. */
    @Composable
    private fun GroupsByType(item: TrackedObject<RdsStation>) {
        val groups = rememberDetail(item.key, groupsFor)
        val counts = rdsGroupCounts(item.state, groups)
        // The counts are lifetime, the newest groups only a few seconds (~11 groups/s): a rare type's
        // sample is read on its own - its newest stored group, however old.
        val newest = remember(groups) { groups.groupBy { it.typeName }.mapValues { it.value.first() } }
        val missing = counts.keys - newest.keys
        val rare by produceState(emptyMap<String, RdsGroup>(), item.key, missing) {
            value = missing.mapNotNull { type ->
                rdsTypeHexGlob(type)?.let { glob -> groupsFor.newestMatching(item.key, RDS_TYPE_HEX_OFFSET, glob) }?.let { type to it }
            }.toMap()
        }
        val latestByType = newest + rare
        val types = counts.keys.sortedWith(compareBy({ it.dropLast(1).toIntOrNull() ?: 99 }, { it.last() }))
        MessageList(
            items = types,
            key = { it },
            time = { item.state.groupTypeLastMs[it] ?: latestByType[it]?.timestampMs ?: item.lastSeenMs },
            text = { type -> "$type  ×${formatCount(counts[type] ?: 0)}" + (latestByType[type]?.let { rdsGroupText(it, ptyUs) }?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") },
            maxRows = 32,
            emptyText = "No groups counted yet.",
            card = { type, collapse ->
                latestByType[type]?.let { group ->
                    RdsGroupCard(group = group, expanded = true, onExpand = collapse, actions = actions, outerPadding = PaddingValues(top = 2.dp, bottom = 6.dp))
                }
            },
        )
    }
}

private const val MAX_TMC_ROWS = 100

private val OPENED_ROW_FACTS = setOf("Programme", "Traffic", "Content", "Clock", "Known from")

internal fun rdsOverviewSection(
    stations: StateFlow<List<TrackedObject<RdsStation>>>,
    unfiltered: StateFlow<Int>,
    tunedStation: Flow<TrackedObject<RdsStation>?>,
    groupsFor: ObjectPackets<RdsGroup>,
    tmcMessagesFor: (stationKey: String) -> Flow<List<TrackedObject<ResolvedTmcMessage>>>,
) = TrackedObjectSection(
    objects = stations,
    unfiltered = unfiltered,
    emptyText = "No RDS stations received yet.",
    presenter = { now, actions ->
        val ptyUs = LocalRdsPtyUs.current
        remember(actions, ptyUs) {
            RdsPresenter(
                nowMs = now, groupsFor = groupsFor, tmcMessagesFor = tmcMessagesFor, actions = actions.cardActions,
                onTuneToFrequency = actions.onTuneToFrequency, ptyUs = ptyUs,
            )
        }
    },
    ticker = {
        val tuned by tunedStation.collectAsState(null)
        RdsTicker(station = tuned?.takeIf { it.state.isLive(nowMs) }, context = context, lines = lines)
    },
)

/** Groups per type: the station's own count, else the types of its stored groups. */
private fun rdsGroupCounts(station: RdsStation, stored: List<RdsGroup>): Map<String, Int> =
    station.groupTypeCounts.ifEmpty { stored.groupingBy { it.typeName }.eachCount() }
