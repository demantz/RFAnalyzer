package com.mantz_it.rfanalyzer.digiext.lora.meshcore

import com.mantz_it.rfanalyzer.digiext.LiveFeed
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrame
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.lora.meshCoreNodeActions
import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectPresenter
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrameList
import com.mantz_it.rfanalyzer.digiext.lora.LoraTicker
import com.mantz_it.rfanalyzer.digiext.lora.meshCoreNodeFacts
import com.mantz_it.rfanalyzer.digiext.lora.meshCoreRowText
import com.mantz_it.rfanalyzer.digiext.ui.Noun
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectSection
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.common.plural
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
 * <h1>RF Analyzer - MeshCore Presenter</h1>
 *
 * Module:      MeshCorePresenter.kt
 * Description: How MeshCore nodes are presented in the Digital tab: one row per node (by its key,
 *              or by its name where a group message is all that was heard of it) with its last
 *              message or advert.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class MeshCorePresenter(
    private val framesFor: ObjectPackets<LoraFrame>,
    private val actions: CardActions,
) : TrackedObjectPresenter<MeshCoreNode> {
    override val noun = Noun("Node", "Nodes")
    override val kindLabel = "MeshCore node"
    override val identityWidth = 150.dp

    override val identityIcon: @Composable (TrackedObject<MeshCoreNode>) -> Unit = { node ->
        val icon = when {
            node.state.isRoomServer -> Icons.Default.Public
            node.state.isRepeater -> Icons.Default.Router
            node.state.isSensor -> Icons.Default.Sensors
            node.state.publicKeyHex == null -> Icons.Default.ChatBubble
            else -> Icons.Default.Person
        }
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
    }

    override val metrics: List<TrackedMetric<TrackedObject<MeshCoreNode>>> = listOf(
        lastHeardMetric { it.lastSeenMs },
        packetsMetric(label = "Frames", header = "Frames"),
        TrackedMetric(label = "Signal (SNR)", header = "SNR", value = { item, _ -> item.state.snrDb?.let { "%.0f dB".format(Locale.US, it) } ?: "–" }, sortKey = { it.state.snrDb ?: Float.NEGATIVE_INFINITY }, width = 50.dp),
        nameMetric { it.displayName.lowercase() },
    )

    override fun payload(item: TrackedObject<MeshCoreNode>): String? = meshCoreRowText(item.state)

    override fun summary(items: List<TrackedObject<MeshCoreNode>>, session: SessionWindow?): String = metaLine(
        items.count { it.state.isRepeater }.takeIf { it > 0 }?.let { plural(it, "repeater", "repeaters") },
        items.count { it.state.isRoomServer }.takeIf { it > 0 }?.let { plural(it, "room server", "room servers") },
        items.count { it.lat != null }.takeIf { it > 0 }?.let { "${formatCount(it)} with position" },
    )

    override fun facts(item: TrackedObject<MeshCoreNode>, nowMs: Long): List<Field> = meshCoreNodeFacts(item) + heardFact(item, nowMs)

    @Composable
    override fun actions(item: TrackedObject<MeshCoreNode>): List<PacketMenuAction> = meshCoreNodeActions(
        item, actions.onShareJson, item.trackOnMap(actions.onTrackOnMap), actions.onOpenUrl, shareMessages = framesFor.share(item.key, actions),
    )

    @Composable
    override fun Messages(item: TrackedObject<MeshCoreNode>, full: Boolean, onMore: (() -> Unit)?) =
        LoraFrameList(item.key, framesFor, full, onMore, actions)
}

internal fun meshCoreOverviewSection(
    nodes: StateFlow<List<TrackedObject<MeshCoreNode>>>,
    unfiltered: StateFlow<Int>,
    liveFrames: LiveFeed<LoraFrame>,
    framesFor: ObjectPackets<LoraFrame>,
) = TrackedObjectSection(
    objects = nodes,
    label = "MeshCore",
    unfiltered = unfiltered,
    emptyText = "No MeshCore nodes received yet.",
    presenter = { _, actions -> remember(actions) { MeshCorePresenter(framesFor, actions.cardActions) } },
    ticker = { LoraTicker(liveFrames, context, lines) },
)
