package com.mantz_it.rfanalyzer.digiext.lora.aprs

import com.mantz_it.rfanalyzer.digiext.ax25.aprsReportCallsigns
import com.mantz_it.rfanalyzer.digiext.LiveFeed
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrame
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.ax25.AprsStation
import com.mantz_it.rfanalyzer.digiext.ax25.StationSymbol
import com.mantz_it.rfanalyzer.digiext.ax25.aprsStationActions
import com.mantz_it.rfanalyzer.digiext.ax25.aprsStationFacts
import com.mantz_it.rfanalyzer.digiext.ax25.aprsStationRowText
import com.mantz_it.rfanalyzer.digiext.ui.SessionWindow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectPresenter
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.lora.LoraFrameList
import com.mantz_it.rfanalyzer.digiext.lora.LoraTicker
import com.mantz_it.rfanalyzer.digiext.ui.Noun
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketMenuAction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import com.mantz_it.rfanalyzer.digiext.ui.TrackedObjectSection
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.Field
import com.mantz_it.rfanalyzer.digiext.ui.common.TrackedMetric
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.defaultObjectMetrics
import com.mantz_it.rfanalyzer.digiext.ui.heardFact
import com.mantz_it.rfanalyzer.digiext.ui.rememberDetail
import com.mantz_it.rfanalyzer.digiext.ui.trackOnMap
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import com.mantz_it.rfanalyzer.digiext.ui.share

/**
 * <h1>RF Analyzer - LoRa-APRS Presenter</h1>
 *
 * Module:      LoraAprsPresenter.kt
 * Description: How LoRa-APRS stations are presented in the Digital tab - the same rows, facts and
 *              actions as AX.25's APRS stations, over LoRa frames.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class LoraAprsPresenter(
    /** One station's frames, also those an iGate relayed for it, newest first. */
    private val framesFor: ObjectPackets<LoraFrame>,
    private val actions: CardActions,
) : TrackedObjectPresenter<AprsStation> {
    override val noun = Noun("Station", "Stations")
    override val kindLabel = "LoRa-APRS station"
    override val metrics: List<TrackedMetric<TrackedObject<AprsStation>>> = defaultObjectMetrics()
    override val identityWidth = 116.dp

    override val identityIcon: @Composable (TrackedObject<AprsStation>) -> Unit = { StationSymbol(it.state) }

    override fun payload(item: TrackedObject<AprsStation>): String = aprsStationRowText(item)
    override fun payloadIdentities(item: TrackedObject<AprsStation>): Collection<String> = aprsReportCallsigns(item.state.lastInfo)

    override fun summary(items: List<TrackedObject<AprsStation>>, session: SessionWindow?): String =
        items.count { it.lat != null }.takeIf { it > 0 }?.let { "${formatCount(it)} with position" }.orEmpty()

    override fun facts(item: TrackedObject<AprsStation>, nowMs: Long): List<Field> = aprsStationFacts(item) + heardFact(item, nowMs)

    @Composable
    override fun actions(item: TrackedObject<AprsStation>): List<PacketMenuAction> = aprsStationActions(
        item, LoraAprsStationFamily, actions.onShareJson, item.trackOnMap(actions.onTrackOnMap), actions.onOpenUrl,
        shareMessages = framesFor.share(item.key, actions),
    )

    @Composable
    override fun Messages(item: TrackedObject<AprsStation>, full: Boolean, onMore: (() -> Unit)?) =
        LoraFrameList(item.key, framesFor, full, onMore, actions)
}

/** LoRa-APRS's Overview table - one of LoRa's protocol sections. */
internal fun loraAprsOverviewSection(
    stations: StateFlow<List<TrackedObject<AprsStation>>>,
    unfiltered: StateFlow<Int>,
    liveFrames: LiveFeed<LoraFrame>,
    framesFor: ObjectPackets<LoraFrame>,
) = TrackedObjectSection(
    objects = stations,
    label = "LoRa-APRS",
    unfiltered = unfiltered,
    emptyText = "No LoRa-APRS stations received yet.",
    presenter = { _, actions -> remember(actions) { LoraAprsPresenter(framesFor, actions.cardActions) } },
    ticker = { LoraTicker(liveFrames, context, lines) },
)
