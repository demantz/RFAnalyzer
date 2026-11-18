package com.mantz_it.rfanalyzer.digiext.lora.lorawan

import com.mantz_it.rfanalyzer.digiext.LiveFeed
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrame
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.ui.copyTextAction
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectPresenter
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrameList
import com.mantz_it.rfanalyzer.digiext.lora.LoraTicker
import com.mantz_it.rfanalyzer.digiext.ui.Noun
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedColumn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectSection
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.heardFact
import com.mantz_it.rfanalyzer.digiext.ui.lastHeardMetric
import com.mantz_it.rfanalyzer.digiext.ui.nameMetric
import com.mantz_it.rfanalyzer.digiext.ui.packetsMetric
import com.mantz_it.rfanalyzer.digiext.ui.rememberDetail
import com.mantz_it.rfanalyzer.digiext.ui.trackedObjectActions
import java.util.Locale
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import com.mantz_it.rfanalyzer.digiext.ui.share

/**
 * <h1>RF Analyzer - LoRaWAN Presenter</h1>
 *
 * Module:      LoRaWanPresenter.kt
 * Description: How LoRaWAN devices are presented in the Digital tab: one row per DevAddr (or
 *              DevEUI for a join request) with the kind of its last frame, its port and counter.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class LoRaWanPresenter(
    private val framesFor: ObjectPackets<LoraFrame>,
    private val actions: CardActions,
) : TrackedObjectPresenter<LoRaWanDevice> {
    override val noun = Noun("Device", "Devices")
    override val kindLabel = "LoRaWAN device"
    override val identityWidth = 100.dp

    override val columns: List<TrackedColumn<TrackedObject<LoRaWanDevice>>> = listOf(
        TrackedColumn(label = "FCnt", width = 48.dp, value = { it.state.fCnt?.toString().orEmpty() }, priority = 3, factLabel = "Uplink counter"),
    )

    override val metrics: List<TrackedMetric<TrackedObject<LoRaWanDevice>>> = listOf(
        lastHeardMetric { it.lastSeenMs },
        packetsMetric(label = "Frames", header = "Frames"),
        TrackedMetric(label = "Signal (SNR)", header = "SNR", value = { item, _ -> item.state.snrDb?.let { "%.0f dB".format(Locale.US, it) } ?: "–" }, sortKey = { it.state.snrDb ?: Float.NEGATIVE_INFINITY }, width = 50.dp),
        nameMetric { it.displayName },
    )

    override fun payload(item: TrackedObject<LoRaWanDevice>): String? = item.state.preview()

    override fun facts(item: TrackedObject<LoRaWanDevice>, nowMs: Long): List<Field> = fields {
        val s = item.state
        add("DevAddr", s.devAddr)
        s.devAddr?.let { addr -> add("Network", lorawanNetIdLookup(addr.toLong(16))?.let { "${it.operator} (NetID ${it.netIdHex})" }) }
        add("DevEUI", s.devEui?.let { it + (lorawanEuiVendor(it)?.let { v -> " ($v)" } ?: "") })
        add("JoinEUI", s.joinEui)
        add("Signal", s.snrDb?.let { "%.1f dB".format(Locale.US, it) })
        add("Modulation", s.modulation?.label)
        add("Downlink", s.downlinkMType?.let { type ->
            metaLine(lorawanMTypeLabel(type), s.downlinkFPort?.let { "FPort $it" }, s.downlinkFCnt?.let { "FCnt $it" }, s.downlinkSnrDb?.let { "%.1f dB".format(Locale.US, it) })
        })
    } + heardFact(item, nowMs)

    @Composable
    override fun actions(item: TrackedObject<LoRaWanDevice>): List<PacketMenuAction> = trackedObjectActions(
        item, LoRaWanDeviceFamily, actions.onShareJson, onTrackOnMap = {}, onOpenUrl = {},
        shareMessages = framesFor.share(item.key, actions),
        specific = listOfNotNull(
            item.state.devAddr?.let { copyTextAction("Copy DevAddr", it) },
            item.state.devEui?.let { copyTextAction("Copy DevEUI", it) },
            item.state.joinEui?.let { copyTextAction("Copy JoinEUI", it) },
        ),
    )

    @Composable
    override fun Messages(item: TrackedObject<LoRaWanDevice>, full: Boolean, onMore: (() -> Unit)?) =
        LoraFrameList(item.key, framesFor, full, onMore, actions)
}

internal fun loRaWanOverviewSection(
    devices: StateFlow<List<TrackedObject<LoRaWanDevice>>>,
    unfiltered: StateFlow<Int>,
    liveFrames: LiveFeed<LoraFrame>,
    framesFor: ObjectPackets<LoraFrame>,
) = TrackedObjectSection(
    objects = devices,
    label = "LoRaWAN",
    unfiltered = unfiltered,
    emptyText = "No LoRaWAN devices received yet.",
    presenter = { _, actions -> remember(actions) { LoRaWanPresenter(framesFor, actions.cardActions) } },
    ticker = { LoraTicker(liveFrames, context, lines) },
)
