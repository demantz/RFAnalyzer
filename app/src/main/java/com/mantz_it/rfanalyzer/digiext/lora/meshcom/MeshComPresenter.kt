package com.mantz_it.rfanalyzer.digiext.lora.meshcom

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.LiveFeed
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrame
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrameList
import com.mantz_it.rfanalyzer.digiext.lora.LoraTicker
import com.mantz_it.rfanalyzer.digiext.ui.Noun
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectPresenter
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectSection
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.heardFact
import com.mantz_it.rfanalyzer.digiext.ui.lastHeardMetric
import com.mantz_it.rfanalyzer.digiext.ui.nameMetric
import com.mantz_it.rfanalyzer.digiext.ui.packetsMetric
import com.mantz_it.rfanalyzer.digiext.ui.share
import com.mantz_it.rfanalyzer.digiext.ui.trackOnMap
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/**
 * <h1>RF Analyzer - MeshCom Presenter</h1>
 *
 * Module:      MeshComPresenter.kt
 * Description: How MeshCom stations are presented in the Digital tab: one row per callsign with
 *              its last text, position or beacon.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class MeshComPresenter(
    private val framesFor: ObjectPackets<LoraFrame>,
    private val actions: CardActions,
) : TrackedObjectPresenter<MeshComNode> {
    override val noun = Noun("Station", "Stations")
    override val kindLabel = "MeshCom station"
    override val identityWidth = 110.dp

    override val metrics: List<TrackedMetric<TrackedObject<MeshComNode>>> = listOf(
        lastHeardMetric { it.lastSeenMs },
        packetsMetric(label = "Frames", header = "Frames"),
        TrackedMetric(label = "Signal (SNR)", header = "SNR", value = { item, _ -> item.state.snrDb?.let { "%.0f dB".format(Locale.US, it) } ?: "–" }, sortKey = { it.state.snrDb ?: Float.NEGATIVE_INFINITY }, width = 50.dp),
        nameMetric { it.displayName },
    )

    override fun payload(item: TrackedObject<MeshComNode>): String? = meshComRowText(item.state)

    override fun summary(items: List<TrackedObject<MeshComNode>>, session: SessionWindow?): String = listOfNotNull(
        items.count { it.lat != null }.takeIf { it > 0 }?.let { "${formatCount(it)} with position" },
        items.count { it.state.isGateway }.takeIf { it > 0 }?.let { "${formatCount(it)} gateway" + if (it == 1) "" else "s" },
    ).joinToString(" · ")

    override fun facts(item: TrackedObject<MeshComNode>, nowMs: Long): List<Field> = meshComNodeFacts(item) + heardFact(item, nowMs)

    @Composable
    override fun actions(item: TrackedObject<MeshComNode>): List<PacketMenuAction> = meshComNodeActions(
        item, actions.onShareJson, item.trackOnMap(actions.onTrackOnMap), actions.onOpenUrl, shareMessages = framesFor.share(item.key, actions),
    )

    @Composable
    override fun Messages(item: TrackedObject<MeshComNode>, full: Boolean, onMore: (() -> Unit)?) =
        LoraFrameList(item.key, framesFor, full, onMore, actions)
}

internal fun meshComOverviewSection(
    nodes: StateFlow<List<TrackedObject<MeshComNode>>>,
    unfiltered: StateFlow<Int>,
    liveFrames: LiveFeed<LoraFrame>,
    framesFor: ObjectPackets<LoraFrame>,
) = TrackedObjectSection(
    objects = nodes,
    label = "MeshCom",
    unfiltered = unfiltered,
    emptyText = "No MeshCom stations received yet.",
    presenter = { _, actions -> remember(actions) { MeshComPresenter(framesFor, actions.cardActions) } },
    ticker = { LoraTicker(liveFrames, context, lines) },
)
