package com.mantz_it.rfanalyzer.digiext.adsb

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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
import com.mantz_it.rfanalyzer.digiext.ui.common.formatCount
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.heardFact
import com.mantz_it.rfanalyzer.digiext.ui.lastHeardMetric
import com.mantz_it.rfanalyzer.digiext.ui.nameMetric
import com.mantz_it.rfanalyzer.digiext.ui.packetsMetric
import com.mantz_it.rfanalyzer.digiext.ui.rememberDetail
import com.mantz_it.rfanalyzer.digiext.ui.trackOnMap
import kotlin.math.roundToInt
import java.util.Locale
import com.mantz_it.rfanalyzer.digiext.ui.ObjectPackets
import com.mantz_it.rfanalyzer.digiext.ui.share
import com.mantz_it.rfanalyzer.digiext.ui.common.rememberMessages

/**
 * <h1>RF Analyzer - ADS-B Presenter</h1>
 *
 * Module:      AdsbPresenter.kt
 * Description: How aircraft are presented in the Digital tab: one row per ICAO address with
 *              altitude, speed and track as far as they fit; an aircraft not heard for a while is
 *              drawn faded. An opened aircraft lists its frames by message type. No ticker - the
 *              rows are the news.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
class AdsbPresenter(
    private val framesFor: ObjectPackets<AdsbFrame>,
    private val actions: CardActions,
    /** Messages per second folded into aircraft, over the last few seconds. */
    private val messageRate: Float? = null,
) : TrackedObjectPresenter<AdsbAircraft> {
    override val noun = Noun("Aircraft", "Aircraft")
    override val kindLabel = "Aircraft"
    override val identityWidth = 104.dp

    override val identityIcon: @Composable (TrackedObject<AdsbAircraft>) -> Unit = { aircraft ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            AdsbAircraftIcon(aircraft.state)
            // a blank of the flag's width keeps the identities aligned
            FlagText(aircraft.state.allocation(aircraft.key)?.flag ?: "  ")
        }
    }

    /** The callsign without its field padding, else the ICAO address. */
    override fun identity(item: TrackedObject<AdsbAircraft>): String = item.state.callsign?.let(::adsbCallsign) ?: item.key

    override val columns: List<TrackedColumn<TrackedObject<AdsbAircraft>>> = listOf(
        TrackedColumn(label = "Alt", width = 48.dp, value = { it.state.altitudeFt?.let(::flightLevel) ?: "–" }, priority = 1, factLabel = "Altitude"),
        TrackedColumn(label = "Speed", width = 50.dp, value = { it.state.speedKt?.let { kt -> "$kt kt" } ?: "–" }, priority = 2),
        TrackedColumn(label = "Track", width = 38.dp, value = { it.state.headingDeg?.let { deg -> "${deg.roundToInt()}°" } ?: "–" }, priority = 3),
    )

    override val metrics: List<TrackedMetric<TrackedObject<AdsbAircraft>>> = listOf(
        lastHeardMetric { it.lastSeenMs },
        TrackedMetric(
            label = "Altitude", header = "Alt",
            value = { item, _ -> item.state.altitudeFt?.let(::flightLevel) ?: "–" },
            sortKey = { it.state.altitudeFt ?: Int.MIN_VALUE },
            width = 48.dp,
        ),
        packetsMetric(label = "Frames", header = "Frames"),
        nameMetric("Callsign / ICAO", sortKey = ::adsbIdentitySortKey),
    )

    override val staleThresholdMs: Long = ADSB_STALE_TIMEOUT_MS
    // An aircraft in range squitters several times a second.
    override val flashOnUpdate = false

    override fun payload(item: TrackedObject<AdsbAircraft>): String? = adsbRowText(item)

    /** The text is only for what is out of the ordinary; altitude, speed and track come first. */
    override val payloadMinFraction = 0.18f

    // The message rate only while ADS-B is received (a session is open), and first: it is the part
    // a narrow header keeps.
    override fun summary(items: List<TrackedObject<AdsbAircraft>>, session: SessionWindow?): String = metaLine(
        messageRate?.takeIf { session != null }?.let { if (it < 10f) "%.1f msg/s".format(Locale.US, it) else "${it.roundToInt()} msg/s" },
        "${formatCount(items.count { it.lat != null })} with position",
    )

    override fun facts(item: TrackedObject<AdsbAircraft>, nowMs: Long): List<Field> =
        adsbAircraftFacts(item).filter { it.label != "Altitude" && it.label != "Speed" && it.label != "Track" } + heardFact(item, nowMs)

    @Composable
    override fun actions(item: TrackedObject<AdsbAircraft>): List<PacketMenuAction> = adsbAircraftActions(
        item, actions.onShareJson, item.trackOnMap(actions.onTrackOnMap), actions.onOpenUrl, shareMessages = framesFor.share(item.key, actions),
    )

    /** The frames by message type - one row per type with its latest values; a type opens into
     *  its frames, each a card of its own. */
    @Composable
    override fun Messages(item: TrackedObject<AdsbAircraft>, full: Boolean, onMore: (() -> Unit)?) {
        val frames = rememberMessages(item.key, framesFor, full)
        val groups = remember(frames.items) { groupAdsbFramesByPayloadType(frames.items) }
        MessageList(
            items = groups,
            onLoadMore = frames.loadMore,
            key = { it.typeLabel },
            time = { it.latestFrame.timestampMs },
            text = { "${adsbTypeName(it.latestFrame.data)}: ${compactAdsbPayloadText(it.latestFrame.data)}" },
            extra = { "×${formatCount(it.frames.size)}" },
            emptyText = "No stored frames.",
            card = { group, _ ->
                Column {
                    group.frames.take(if (full) FULL_FRAME_STACK else FRAME_STACK).forEach { frame ->
                        var open by remember(frame.rowId) { mutableStateOf(false) }
                        AdsbFrameCard(
                            frame = frame, expanded = open, onExpand = { open = !open }, actions = actions,
                            outerPadding = PaddingValues(top = 2.dp, bottom = 4.dp),
                        )
                    }
                }
            },
        )
    }

    private companion object {
        const val FRAME_STACK = 5
        const val FULL_FRAME_STACK = 50
    }
}

internal fun adsbOverviewSection(
    aircraft: StateFlow<List<TrackedObject<AdsbAircraft>>>,
    unfiltered: StateFlow<Int>,
    framesFor: ObjectPackets<AdsbFrame>,
    messageRate: Flow<Float>,
) = TrackedObjectSection(
    objects = aircraft,
    unfiltered = unfiltered,
    emptyText = "No aircraft received yet.",
    presenter = { _, actions ->
        val rate by messageRate.collectAsState(null)
        remember(actions, rate) { AdsbPresenter(framesFor, actions.cardActions, rate) }
    },
)
