package com.mantz_it.rfanalyzer.digiext.lora.meshtastic

import com.mantz_it.rfanalyzer.digiext.LiveFeed
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrame
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.lora.meshtasticNodeActions
import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectPresenter
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrameList
import com.mantz_it.rfanalyzer.digiext.lora.LoraTicker
import com.mantz_it.rfanalyzer.digiext.lora.meshtasticNodeFacts
import com.mantz_it.rfanalyzer.digiext.lora.meshtasticRowText
import com.mantz_it.rfanalyzer.digiext.ui.Noun
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectSection
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.heardFact
import com.mantz_it.rfanalyzer.digiext.ui.lastHeardMetric
import com.mantz_it.rfanalyzer.digiext.ui.nameMetric
import com.mantz_it.rfanalyzer.digiext.ui.packetsMetric
import com.mantz_it.rfanalyzer.digiext.ui.rememberDetail
import com.mantz_it.rfanalyzer.digiext.ui.trackOnMap
import java.util.Locale
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import com.mantz_it.rfanalyzer.digiext.ui.share

/**
 * <h1>RF Analyzer - Meshtastic Presenter</h1>
 *
 * Module:      MeshtasticPresenter.kt
 * Description: How Meshtastic nodes are presented in the Digital tab: one row per node number with
 *              its long name and last message, telemetry or position.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class MeshtasticPresenter(
    private val framesFor: ObjectPackets<LoraFrame>,
    private val actions: CardActions,
) : TrackedObjectPresenter<MeshtasticNode> {
    override val noun = Noun("Node", "Nodes")
    override val kindLabel = "Meshtastic node"
    override val identityWidth = 150.dp

    override val metrics: List<TrackedMetric<TrackedObject<MeshtasticNode>>> = listOf(
        lastHeardMetric { it.lastSeenMs },
        packetsMetric(label = "Frames", header = "Frames"),
        TrackedMetric(label = "Signal (SNR)", header = "SNR", value = { item, _ -> item.state.snrDb?.let { "%.0f dB".format(Locale.US, it) } ?: "–" }, sortKey = { it.state.snrDb ?: Float.NEGATIVE_INFINITY }, width = 50.dp),
        nameMetric { it.displayName.lowercase() },
    )

    override fun payload(item: TrackedObject<MeshtasticNode>): String? = meshtasticRowText(item.state)

    override fun summary(items: List<TrackedObject<MeshtasticNode>>, session: SessionWindow?): String =
        items.count { it.lat != null }.takeIf { it > 0 }?.let { "${formatCount(it)} with position" }.orEmpty()

    override fun facts(item: TrackedObject<MeshtasticNode>, nowMs: Long): List<Field> = meshtasticNodeFacts(item) + heardFact(item, nowMs)

    @Composable
    override fun actions(item: TrackedObject<MeshtasticNode>): List<PacketMenuAction> = meshtasticNodeActions(
        item, actions.onShareJson, item.trackOnMap(actions.onTrackOnMap), actions.onOpenUrl, shareMessages = framesFor.share(item.key, actions),
    )

    @Composable
    override fun Messages(item: TrackedObject<MeshtasticNode>, full: Boolean, onMore: (() -> Unit)?) =
        LoraFrameList(item.key, framesFor, full, onMore, actions)
}

internal fun meshtasticOverviewSection(
    nodes: StateFlow<List<TrackedObject<MeshtasticNode>>>,
    unfiltered: StateFlow<Int>,
    liveFrames: LiveFeed<LoraFrame>,
    framesFor: ObjectPackets<LoraFrame>,
) = TrackedObjectSection(
    objects = nodes,
    label = "Meshtastic",
    unfiltered = unfiltered,
    emptyText = "No Meshtastic nodes received yet.",
    presenter = { _, actions -> remember(actions) { MeshtasticPresenter(framesFor, actions.cardActions) } },
    ticker = { LoraTicker(liveFrames, context, lines) },
)
