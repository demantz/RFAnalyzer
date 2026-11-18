package com.mantz_it.rfanalyzer.digiext.ui

import androidx.compose.foundation.layout.RowScope
import com.mantz_it.rfanalyzer.digiext.adsb.AdsbSpec
import com.mantz_it.rfanalyzer.digiext.ax25.Ax25Spec
import com.mantz_it.rfanalyzer.digiext.db.ADSB_DETAIL_LIMIT
import com.mantz_it.rfanalyzer.digiext.db.DETAIL_LIMIT
import com.mantz_it.rfanalyzer.digiext.db.K2K_LIST_LIMIT
import com.mantz_it.rfanalyzer.digiext.db.PacketSpec
import com.mantz_it.rfanalyzer.digiext.ft8.Ft8Spec
import com.mantz_it.rfanalyzer.digiext.k2k.K2kSpec
import com.mantz_it.rfanalyzer.digiext.k2k.Psk31Spec
import com.mantz_it.rfanalyzer.digiext.k2k.RttySpec
import com.mantz_it.rfanalyzer.digiext.lora.LoraSpec
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagSpec
import com.mantz_it.rfanalyzer.digiext.r09.R09Spec
import com.mantz_it.rfanalyzer.digiext.rds.RdsSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import com.mantz_it.rfanalyzer.digiext.DigitalExtensionRepository
import com.mantz_it.rfanalyzer.digiext.db.DigitalPacketRepository
import com.mantz_it.rfanalyzer_ipc.digiext.DigitalMode
import com.mantz_it.rfanalyzer.digiext.k2k.K2kOverviewSection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import com.mantz_it.rfanalyzer.digiext.adsb.AdsbAircraftFamily
import com.mantz_it.rfanalyzer.digiext.ax25.AprsStationFamily
import com.mantz_it.rfanalyzer.digiext.db.DigitalFilter
import com.mantz_it.rfanalyzer.digiext.db.ObjectFamily
import com.mantz_it.rfanalyzer.digiext.ft8.Ft8StationFamily
import com.mantz_it.rfanalyzer.digiext.lora.aprs.LoraAprsStationFamily
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.LoRaWanDeviceFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.MeshCoreNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.MeshtasticNodeFamily
import com.mantz_it.rfanalyzer.digiext.pocsag.PocsagPagerFamily
import com.mantz_it.rfanalyzer.digiext.r09.R09BusFamily
import com.mantz_it.rfanalyzer.digiext.rds.RdsStationFamily
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.mantz_it.rfanalyzer.digiext.adsb.adsbOverviewSection
import com.mantz_it.rfanalyzer.digiext.ax25.ax25OverviewSection
import com.mantz_it.rfanalyzer.digiext.ax25.PacketStationFamily
import com.mantz_it.rfanalyzer.digiext.ax25.packetStationOverviewSection
import com.mantz_it.rfanalyzer.digiext.db.TrackedObject
import com.mantz_it.rfanalyzer.digiext.ft8.ft8OverviewSection
import com.mantz_it.rfanalyzer.digiext.lora.aprs.loraAprsOverviewSection
import com.mantz_it.rfanalyzer.digiext.lora.lorawan.loRaWanOverviewSection
import com.mantz_it.rfanalyzer.digiext.lora.meshcore.meshCoreOverviewSection
import com.mantz_it.rfanalyzer.digiext.lora.meshtastic.meshtasticOverviewSection
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.MeshComNodeFamily
import com.mantz_it.rfanalyzer.digiext.lora.meshcom.meshComOverviewSection
import com.mantz_it.rfanalyzer.digiext.names
import com.mantz_it.rfanalyzer.digiext.pocsag.pocsagOverviewSection
import com.mantz_it.rfanalyzer.digiext.r09.r09OverviewSection
import com.mantz_it.rfanalyzer.digiext.rds.rdsOverviewSection
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerExpansion
import com.mantz_it.rfanalyzer.digiext.ui.common.formatFrequency
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow

/**
 * <h1>RF Analyzer - Overview Sections</h1>
 *
 * Module:      OverviewSections.kt
 * Description: The Overview subtab's sections: [OverviewSection], one table of a mode's tracked
 *              objects (or K2K transmissions) with its ticker, the generic [TrackedObjectSection],
 *              the [OverviewContext] a section's content gets, and [digitalOverviewSections], the
 *              one place a mode's sections are registered with the Digital tab.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/**
 * Everything a section's content gets from the Overview: the shared filter, the clock, the actions,
 * whether its mode is being received ([live], null otherwise), how many lines its ticker has, and
 * the section tabs of a mode with several sections ([sectionTabs], drawn in place of the count at
 * the start of the table's header row).
 */
class OverviewContext(
    val mode: DigitalMode,
    val filter: DigitalFilter,
    val nowMs: Long,
    val actions: DigitalTabActions,
    val live: LiveState?,
    val tickerLines: Int,
    /** For a ticker that opens - see [TickerExpansion]; one state per mode, shared by its sections. */
    val tickerExpansion: TickerExpansion,
    val sectionTabs: (@Composable () -> Unit)?,
) {
    val filterActive: Boolean get() = filter.isFilterActive()

    /** The ticker's label: "AX.25 · 144.390 MHz", plus what the mode adds ("1200 Bd"). */
    fun tickerLabel(vararg extra: String?): String =
        metaLine(listOf(mode.names.name, live?.frequencyHz?.let(::formatFrequency)) + extra)

    /** The table header's start: the section tabs where there are several, else the count; then
     *  the summary in the room that is left. */
    val headerStart: @Composable RowScope.(count: String, summary: String) -> Unit = { count, summary ->
        val tabs = sectionTabs
        if (tabs != null) StartThenSummary(tabs, summary, Modifier.weight(1f)) else CountAndSummary(count = count, summary = summary)
    }
}

/** Whether a filter narrows what the Digital tab shows (beyond the mode). */
fun DigitalFilter.isFilterActive(): Boolean =
    search.isNotBlank() || hasTimeRange() || minFrequency != 0L || maxFrequency != 0L

/**
 * One table of the Overview subtab: a mode's tracked objects, or one of them for a mode carrying
 * several protocols (LoRa) or kinds (AX.25: APRS and packet stations).
 *
 * A section owns everything mode-specific about that table: its flows, its [ModePresenter], its
 * ticker and its empty text. It collects its flows inside [Content], so only the section on screen
 * is subscribed to its details. The Digital tab looks the shown mode up in
 * [digitalOverviewSections] and knows nothing about individual modes.
 */
interface OverviewSection {
    /** The tab label, shown when a mode has more than one section. */
    val label: String get() = ""

    /** How many rows the section lists - the count on its tab. */
    val count: Flow<Int> get() = flowOf(0)

    /** How many there are without the filter - the tab shows "12/67" while a filter narrows them. */
    val unfilteredCount: Flow<Int?> get() = flowOf(null)

    /** Grows whenever something new is heard for the section - marks its tab while another one is
     *  selected (see [heardBy]). */
    val activity: Flow<Long> get() = flowOf(0L)

    @Composable
    fun Content(context: OverviewContext, modifier: Modifier)
}

/** What a [TrackedObjectSection]'s ticker can use: the listed objects, the clock, its line count
 *  and the section's context (for its label). */
class TickerScope<S : Any>(val objects: List<TrackedObject<S>>, val nowMs: Long, val lines: Int, val context: OverviewContext)

/**
 * The Overview table of one kind of tracked object - every section but K2K's, which lists
 * transmissions.
 *
 * [presenter] is composable so it can [remember] the presenter on what it depends on (the
 * [DigitalTabActions] always, RDS also the region setting). [ticker] is shown above the table while
 * the mode is being received; it decides itself what to show before the first packet.
 */
internal class TrackedObjectSection<S : Any>(
    private val objects: StateFlow<List<TrackedObject<S>>>,
    private val emptyText: String,
    private val presenter: @Composable (nowMs: () -> Long, actions: DigitalTabActions) -> TrackedObjectPresenter<S>,
    override val label: String = "",
    /** How many objects there are without the filter, for "12 of 67". */
    private val unfiltered: StateFlow<Int>? = null,
    private val ticker: (@Composable TickerScope<S>.() -> Unit)? = null,
) : OverviewSection {
    override val count: Flow<Int> = objects.map { it.size }
    override val unfilteredCount: Flow<Int?> = unfiltered ?: flowOf(null)

    @Composable
    override fun Content(context: OverviewContext, modifier: Modifier) {
        val shown by objects.collectAsState()
        val unfilteredCount = if (context.filterActive) unfiltered?.collectAsState()?.value else null
        TrackedModeSection(
            items = shown,
            presenter = presenter(rememberNow(context.nowMs), context.actions),
            nowMs = context.nowMs,
            live = context.live,
            headerStart = context.headerStart,
            emptyText = emptyText,
            filterActive = context.filterActive,
            unfilteredCount = unfilteredCount,
            ticker = if (context.live != null) ticker?.let { content -> { TickerScope(shown, context.nowMs, context.tickerLines, context).content() } } else null,
            modifier = modifier,
        )
    }
}

/** How fast [counter] grows, per second over the last [windowS] seconds - sampled once a second
 *  while collected. */
internal fun ratePerSecond(counter: StateFlow<Long>, windowS: Int = 5): Flow<Float> = flow {
    val samples = ArrayDeque<Long>()
    while (true) {
        samples.addLast(counter.value)
        if (samples.size > windowS + 1) samples.removeFirst()
        emit(if (samples.size < 2) 0f else (samples.last() - samples.first()).toFloat() / (samples.size - 1))
        delay(1000)
    }
}

/** This section, its tab marked by [heard] - a count of the packets folded into its objects. Not
 *  their last-seen times: a replayed recording's packets carry the recording's time. */
internal fun OverviewSection.heardBy(heard: Flow<Long>): OverviewSection = object : OverviewSection by this {
    override val activity: Flow<Long> = heard
}

/** A stable `() -> Long` that always returns the latest [nowMs]. Presenters take the clock as a
 *  closure so a tick re-renders the age cells without rebuilding (and re-sorting) the presenter. */
@Composable
internal fun rememberNow(nowMs: Long): () -> Long {
    val latest = rememberUpdatedState(nowMs)
    return remember { { latest.value } }
}

/**
 * The Overview sections of every mode - the one place a mode is registered with the Digital tab.
 *
 * Tables read `tracked_objects`, already narrowed by the Digital tab's [filter] in SQL (see
 * `DigitalExtensionRepository.objects`); tickers read the live flows. The table flows are shared in
 * [scope] and kept warm for a few seconds after the last subscriber, so switching subtabs and back
 * doesn't flash an empty table.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun digitalOverviewSections(
    repository: DigitalExtensionRepository,
    packets: DigitalPacketRepository,
    filter: StateFlow<DigitalFilter>,
    scope: CoroutineScope,
): Map<DigitalMode, List<OverviewSection>> {
    fun <T> Flow<List<T>>.shared(): StateFlow<List<T>> = stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** The objects the filter selects. */
    fun <S : Any> shown(family: ObjectFamily<S>) = filter.flatMapLatest { repository.objects(family, it) }.shared()
    /** How many there are with only the mode selected - what the filter narrows down from. */
    fun unfiltered(family: ObjectFamily<*>) = filter.map { DigitalFilter(mode = it.mode) }.distinctUntilChanged()
        .flatMapLatest { repository.objectCount(family, it) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), 0)
    /** K2K has no tracked objects: its table lists the finished transmissions themselves. */
    fun k2k(spec: K2kSpec) = filter.flatMapLatest { f ->
        f.toPacketQuery()?.takeIf { it.mode == spec.mode }?.let { packets.observe(spec, it, K2K_LIST_LIMIT) } ?: flowOf(emptyList())
    }.shared()
    /** One tracked object's packets, newest first: [limit] in an opened row, more on its details page. */
    fun <Ph : Any, D : Any> detail(spec: PacketSpec<Ph, D>, limit: Int = DETAIL_LIMIT, bidirectional: Boolean = false) = ObjectPackets(
        newest = { objectKey: String, n: Int -> packets.detail(spec, objectKey, n, bidirectional) },
        count = { objectKey -> packets.detailCount(spec, objectKey, bidirectional) },
        query = { objectKey -> packets.detailQuery(spec, objectKey, bidirectional) },
        rowLimit = limit,
        newestMatching = { objectKey, offset, glob ->
            packets.newest(spec, packets.detailQuery(spec, objectKey, bidirectional).copy(rawHexOffset = offset, rawHexGlob = glob))
        },
    )

    return mapOf(
        DigitalMode.POCSAG to listOf(
            pocsagOverviewSection(shown(PocsagPagerFamily), unfiltered(PocsagPagerFamily), repository.liveFeed(PocsagSpec), detail(PocsagSpec)),
        ),
        DigitalMode.ADSB to listOf(
            adsbOverviewSection(shown(AdsbAircraftFamily), unfiltered(AdsbAircraftFamily), detail(AdsbSpec, ADSB_DETAIL_LIMIT), ratePerSecond(repository.heard(AdsbAircraftFamily))),
        ),
        DigitalMode.FT8 to listOf(
            // Both directions: the messages a station sent and those addressed to it.
            ft8OverviewSection(shown(Ft8StationFamily), unfiltered(Ft8StationFamily), detail(Ft8Spec, bidirectional = true)),
        ),
        DigitalMode.RDS to listOf(
            rdsOverviewSection(shown(RdsStationFamily), unfiltered(RdsStationFamily), repository.tunedRdsStation, detail(RdsSpec), repository::tmcMessages),
        ),
        DigitalMode.AX25 to listOf(
            ax25OverviewSection(shown(AprsStationFamily), unfiltered(AprsStationFamily), repository.liveFeed(Ax25Spec), detail(Ax25Spec, bidirectional = true))
                .heardBy(repository.heard(AprsStationFamily)),
            packetStationOverviewSection(shown(PacketStationFamily), unfiltered(PacketStationFamily), repository.liveFeed(Ax25Spec), detail(Ax25Spec, bidirectional = true))
                .heardBy(repository.heard(PacketStationFamily)),
        ),
        DigitalMode.R09 to listOf(
            r09OverviewSection(shown(R09BusFamily), unfiltered(R09BusFamily), repository.livePackets(R09Spec), detail(R09Spec)),
        ),
        DigitalMode.LORA to listOf(
            meshCoreOverviewSection(shown(MeshCoreNodeFamily), unfiltered(MeshCoreNodeFamily), repository.liveFeed(LoraSpec), detail(LoraSpec))
                .heardBy(repository.heard(MeshCoreNodeFamily)),
            meshtasticOverviewSection(shown(MeshtasticNodeFamily), unfiltered(MeshtasticNodeFamily), repository.liveFeed(LoraSpec), detail(LoraSpec))
                .heardBy(repository.heard(MeshtasticNodeFamily)),
            // Both directions: a relay's detail lists the frames it relayed.
            meshComOverviewSection(shown(MeshComNodeFamily), unfiltered(MeshComNodeFamily), repository.liveFeed(LoraSpec), detail(LoraSpec, bidirectional = true))
                .heardBy(repository.heard(MeshComNodeFamily)),
            loRaWanOverviewSection(shown(LoRaWanDeviceFamily), unfiltered(LoRaWanDeviceFamily), repository.liveFeed(LoraSpec), detail(LoraSpec))
                .heardBy(repository.heard(LoRaWanDeviceFamily)),
            // Both directions: an iGate's relayed frames are listed under the station they came from.
            loraAprsOverviewSection(shown(LoraAprsStationFamily), unfiltered(LoraAprsStationFamily), repository.liveFeed(LoraSpec), detail(LoraSpec, bidirectional = true))
                .heardBy(repository.heard(LoraAprsStationFamily)),
        ),
        DigitalMode.RTTY to listOf(
            K2kOverviewSection(DigitalMode.RTTY, k2k(RttySpec), repository.currentTransmission(DigitalMode.RTTY), repository.currentTransmissionEarlierText(DigitalMode.RTTY)),
        ),
        DigitalMode.PSK31 to listOf(
            K2kOverviewSection(DigitalMode.PSK31, k2k(Psk31Spec), repository.currentTransmission(DigitalMode.PSK31), repository.currentTransmissionEarlierText(DigitalMode.PSK31)),
        ),
    )
}
