package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.db.StoredPacket
import com.mantz_it.rfanalyzer.digiext.toExportJson
import com.mantz_it.rfanalyzer_ipc.digiext.Bytes
import kotlin.math.max
import kotlin.math.min
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import com.mantz_it.rfanalyzer.digiext.db.PacketQuery

/**
 * <h1>RF Analyzer - Packet Card</h1>
 *
 * Module:      PacketCard.kt
 * Description: The one card every received packet is shown as - in the Raw subtab, under a row's
 *              message list and in a Map popup - and the parts its opened state is built from
 *              ([Fields], [RawSection], [ActionStrip]).
 *
 *              Closed it is one header line (identity, what it is, time) and the payload. Opened
 *              it adds the fields, one radio line, the raw bytes behind a toggle and the actions;
 *              the time becomes the full date and time, which can take the header's room - so the
 *              fields repeat what the header says. Each mode decides which of its fields go where.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Space around a card in a list. */
val CardListPadding = PaddingValues(horizontal = 8.dp, vertical = 3.dp)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PacketCard(
    expanded: Boolean,
    onExpand: () -> Unit,
    /** When the packet was received; null for a card without one (a tracked object). */
    timestampMs: Long?,
    identity: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    /** Small text after the identity: what kind of packet, to whom, which channel. */
    subtitle: String? = null,
    /** Inline after the subtitle: a [TypeTag]. */
    tags: (@Composable RowScope.() -> Unit)? = null,
    /** A [WarningText] after the subtitle and tags; it keeps its room, a long identity is cut instead. */
    warning: (@Composable () -> Unit)? = null,
    /** Before the time - values a mode wants in the header line itself (FT8's SNR and Δt). */
    trailing: (@Composable RowScope.() -> Unit)? = null,
    payload: (@Composable (expanded: Boolean) -> Unit)? = null,
    expandedContent: (@Composable ColumnScope.() -> Unit)? = null,
    actions: List<PacketMenuAction> = emptyList(),
    stale: Boolean = false,
    outerPadding: PaddingValues = CardListPadding,
    /** The short time even when opened - a Map popup is narrow, and its time is "last heard". */
    shortTime: Boolean = false,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    Card(
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = DigitalSurfaces.card),
        modifier = modifier
            .fillMaxWidth()
            .padding(outerPadding)
            .alpha(if (stale) 0.55f else 1f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onExpand,
                    onLongClickLabel = if (actions.isEmpty()) null else "Actions",
                    onLongClick = if (actions.isEmpty()) null else {
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    },
                )
                .animateContentSize()
                .padding(start = 10.dp, end = 10.dp, top = 7.dp, bottom = 8.dp),
        ) {
            HeaderLine(
                identity = identity,
                middle = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        subtitle?.let {
                            Text(it, style = DigitalTextStyles.meta, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        }
                        tags?.invoke(this)
                    }
                },
                warning = warning,
                end = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        trailing?.invoke(this)
                        timestampMs?.let {
                            Text(
                                text = if (expanded && !shortTime) formatFullDateTime(it) else formatPacketTime(System.currentTimeMillis(), it),
                                style = DigitalTextStyles.faint,
                                maxLines = 1,
                            )
                        }
                    }
                },
            )
            payload?.let {
                Spacer(Modifier.height(2.dp))
                it(expanded)
            }
            if (expanded && (expandedContent != null || actions.isNotEmpty())) {
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    expandedContent?.invoke(this)
                    if (actions.isNotEmpty()) ActionStrip(actions)
                }
            }
            Box { PacketActionDropdown(expanded = menuOpen, onDismissRequest = { menuOpen = false }, actions = actions) }
        }
    }
}

/** A card's or ticker's identity: optional leading symbol, then the name in the primary colour. */
@Composable
fun IdentityText(
    text: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    style: TextStyle = DigitalTextStyles.identity,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        leading?.let {
            it()
            Spacer(Modifier.width(5.dp))
        }
        Text(text = text, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The payload of a card: language in the body font, teletype text and hex in monospace; up to
 * [collapsedLines] lines while the card is closed, all of it when open.
 */
@Composable
fun PayloadText(
    text: String,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    mono: Boolean = false,
    collapsedLines: Int = 3,
    color: Color = Color.Unspecified,
    /** Callsigns in the text, drawn in the identity colour - see [identityRanges]. */
    identities: Collection<String> = emptyList(),
    /** Teletype text: opened, a table ([isTeletypeTable]) keeps its lines and scrolls sideways. */
    preformatted: Boolean = false,
) {
    val style = if (mono) DigitalTextStyles.mono else DigitalTextStyles.payload
    val noWrap = expanded && preformatted && isTeletypeTable(text)
    Text(
        text = identityStyled(if (expanded) text.trimEnd() else oneLineForPreview(text, mono), identities),
        style = if (color == Color.Unspecified) style else style.copy(color = color),
        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
        overflow = if (noWrap) TextOverflow.Clip else TextOverflow.Ellipsis,
        softWrap = !noWrap,
        modifier = if (noWrap) modifier.horizontalScroll(rememberScrollState()) else modifier,
    )
}

/** Whether teletype text is laid out as a table - a DWD weather report: several lines, none wider
 *  than a teleprinter page (69 characters, some slack). Wrapped on a phone, such a table can't be
 *  read; a keyboard chat with long lines still wraps. */
internal fun isTeletypeTable(text: String): Boolean {
    val lines = text.trim().lines().filter { it.isNotBlank() }
    return lines.size >= 3 && lines.maxOf { it.length } <= TELEPRINTER_COLUMNS
}

private const val TELEPRINTER_COLUMNS = 80

/** A closed card shows text without its layout: no leading or doubled line breaks. Teletype text
 *  keeps its single line breaks (a weather report is a table), but loses blank lines. */
private fun oneLineForPreview(text: String, mono: Boolean): String =
    if (mono) text.trim().replace(BLANK_LINES, "\n") else oneLine(text)

private val BLANK_LINES = Regex("(\\s*(\r\n|\r|\n)){2,}")

/**
 * The header line's parts: the end sits at the right edge, a [warning] keeps its full width, the
 * identity its natural width (up to [identityMaxFraction] of the line) in what is left, the middle
 * takes the rest and ellipsizes - a long name is cut, never the warning after it.
 */
@Composable
fun HeaderLine(
    identity: @Composable () -> Unit,
    middle: @Composable () -> Unit,
    end: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    warning: (@Composable () -> Unit)? = null,
    identityMaxFraction: Float = 0.64f,
    gap: Dp = 6.dp,
) {
    Layout(contents = listOf(identity, middle, warning ?: {}, end), modifier = modifier.fillMaxWidth()) { (idM, middleM, warningM, endM), constraints ->
        val width = constraints.maxWidth
        val gapPx = gap.roundToPx()
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val endPlaceable = endM.first().measure(loose)
        val warningPlaceable = warningM.firstOrNull()?.measure(loose.copy(maxWidth = max(0, width - endPlaceable.width - gapPx)))
        val warningWidth = warningPlaceable?.let { it.width + gapPx } ?: 0
        val idMax = max(0, min((width * identityMaxFraction).toInt(), width - endPlaceable.width - warningWidth - gapPx))
        val idPlaceable = idM.first().measure(loose.copy(maxWidth = idMax))
        val middleMax = max(0, width - idPlaceable.width - endPlaceable.width - warningWidth - 2 * gapPx)
        val middlePlaceable = middleM.first().measure(loose.copy(maxWidth = middleMax))
        val height = maxOf(idPlaceable.height, middlePlaceable.height, endPlaceable.height, warningPlaceable?.height ?: 0)
        layout(width, height) {
            idPlaceable.place(0, (height - idPlaceable.height) / 2)
            middlePlaceable.place(idPlaceable.width + gapPx, (height - middlePlaceable.height) / 2)
            warningPlaceable?.let {
                val x = idPlaceable.width + gapPx + if (middlePlaceable.width > 0) middlePlaceable.width + gapPx else 0
                it.place(x, (height - it.height) / 2)
            }
            endPlaceable.place(width - endPlaceable.width, (height - endPlaceable.height) / 2)
        }
    }
}

/** One label/value pair of [Fields]. An [identity] value (a callsign) is drawn in the identity colour. */
data class Field(val label: String, val value: String, val mono: Boolean = false, val color: Color = Color.Unspecified, val identity: Boolean = false)

/** Builds a field list, skipping null and blank values. */
class FieldsBuilder {
    internal val fields = mutableListOf<Field>()
    fun add(label: String, value: String?, mono: Boolean = false, color: Color = Color.Unspecified, identity: Boolean = false) {
        if (!value.isNullOrBlank()) fields += Field(label, value, mono, color, identity)
    }
}

fun fields(block: FieldsBuilder.() -> Unit): List<Field> = FieldsBuilder().apply(block).fields

/**
 * Label/value pairs in two aligned columns - the opened card's, the expanded row's and the
 * details page's one way of listing facts. Labels take the width of the longest one (at most
 * [maxLabelFraction] of the line); values wrap.
 */
@Composable
fun Fields(fields: List<Field>, modifier: Modifier = Modifier, maxLabelFraction: Float = 0.38f) {
    if (fields.isEmpty()) return
    val labelStyle = DigitalTextStyles.label
    val valueStyle = DigitalTextStyles.value
    val monoStyle = DigitalTextStyles.mono.copy(fontSize = valueStyle.fontSize)
    val identityColor = MaterialTheme.colorScheme.primary
    Layout(
        content = {
            fields.forEach { field ->
                Text(field.label, style = labelStyle, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    field.value,
                    style = (if (field.mono) monoStyle else valueStyle).let {
                        when {
                            field.color != Color.Unspecified -> it.copy(color = field.color)
                            field.identity -> it.copy(color = identityColor)
                            else -> it
                        }
                    },
                )
            }
        },
        modifier = modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val rowGap = 2.dp.roundToPx()
        val labelCap = (width * maxLabelFraction).toInt()
        val labelWidth = min(labelCap, measurables.filterIndexed { i, _ -> i % 2 == 0 }.maxOf { it.maxIntrinsicWidth(Int.MAX_VALUE) })
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.mapIndexed { i, m ->
            if (i % 2 == 0) m.measure(loose.copy(maxWidth = labelWidth)) else m.measure(loose.copy(maxWidth = max(0, width - labelWidth - gap)))
        }
        var height = 0
        val rows = placeables.chunked(2)
        val tops = rows.map { (l, v) -> height.also { height += max(l.height, v.height) + rowGap } }
        layout(width, max(0, height - rowGap)) {
            rows.forEachIndexed { i, (l, v) ->
                l.place(0, tops[i])
                v.place(labelWidth + gap, tops[i])
            }
        }
    }
}

/** The opened card's one line of radio parameters: "SF8 · BW 62.5 kHz · CR 4/8 · −13.9 dB". */
@Composable
fun RadioLine(vararg parts: String?) {
    val text = metaLine(*parts)
    if (text.isNotEmpty()) Text(text, style = DigitalTextStyles.faint)
}

/**
 * Raw content behind a toggle - "Raw · 130 bytes ▸" - since it is for checking, not reading. [text]
 * is built only when opened.
 */
@Composable
fun RawSection(title: String, text: () -> String) {
    var open by remember { mutableStateOf(false) }
    Column {
        TextButton(
            onClick = { open = !open },
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            modifier = Modifier.height(28.dp),
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium)
            Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(18.dp))
        }
        if (open) MonoTextBox(text = remember(text) { text() })
    }
}

/** [RawSection] of a byte string as one hex dump. */
@Composable
fun RawBytes(bytes: Bytes?, title: String = "Raw") {
    if (bytes == null || bytes.size == 0) return
    RawSection("$title · ${plural(bytes.size, "byte", "bytes")}") { bytes.toByteArray().toHexDump(8, 4) }
}

/**
 * The actions of an opened card, row or page as buttons: the [PacketMenuAction.prominent] ones
 * (at most [maxButtons]), the first filled, and "⋯" for the rest.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionStrip(actions: List<PacketMenuAction>, modifier: Modifier = Modifier, maxButtons: Int = 3) {
    if (actions.isEmpty()) return
    val buttons = actions.filter { it.prominent }.take(maxButtons)
    val rest = actions - buttons.toSet()
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        buttons.forEachIndexed { index, action -> ActionButton(action, filled = index == 0) }
        if (rest.isNotEmpty()) {
            var open by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { open = true },
                    contentPadding = PaddingValues(horizontal = 8.dp),
                    border = outlinedButtonBorder(),
                    modifier = Modifier.height(32.dp),
                ) {
                    Icon(Icons.Default.MoreHoriz, contentDescription = "More actions", modifier = Modifier.size(18.dp))
                }
                PacketActionDropdown(expanded = open, onDismissRequest = { open = false }, actions = rest)
            }
        }
    }
}

@Composable
private fun ActionButton(action: PacketMenuAction, filled: Boolean) {
    val padding = PaddingValues(start = if (action.icon != null) 8.dp else 12.dp, end = 12.dp)
    val content: @Composable RowScope.() -> Unit = {
        action.icon?.let {
            Icon(it, contentDescription = null, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(4.dp))
        }
        Text(action.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
    if (filled) {
        Button(onClick = action.onClick, contentPadding = padding, modifier = Modifier.height(32.dp), content = content)
    } else {
        OutlinedButton(onClick = action.onClick, contentPadding = padding, border = outlinedButtonBorder(), modifier = Modifier.height(32.dp), content = content)
    }
}

/** What a packet card's actions need from the app. */
class CardActions(
    val onShareJson: (json: String, filenamePrefix: String) -> Unit,
    val onTrackOnMap: (markerId: String, lat: Double, lon: Double) -> Unit,
    val onOpenUrl: (url: String) -> Unit,
    /** Shares every packet [query] matches as a JSON file, written a page at a time - an object's
     *  messages may be far more than fit into one string. */
    val onShareQuery: (query: PacketQuery, filenamePrefix: String) -> Unit = { _, _ -> },
) {
    companion object {
        val None = CardActions({ _, _ -> }, { _, _, _ -> }, {})
    }
}

/**
 * A packet's actions: the mode's [specific] ones first (Map, lookups), "Copy text" for a packet
 * with text, then share and copy as JSON behind "⋯".
 */
@Composable
fun packetActions(
    packet: StoredPacket<*, *>,
    actions: CardActions,
    copyText: String? = null,
    specific: List<PacketMenuAction> = emptyList(),
): List<PacketMenuAction> {
    val context = LocalContext.current
    fun copy(label: String, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
    }
    val prefix = "${packet.mode.name.lowercase()}_export"
    return buildList {
        addAll(specific)
        copyText?.takeIf { it.isNotBlank() }?.let { text -> add(PacketMenuAction(label = "Copy text", prominent = true) { copy("Text", text) }) }
        add(PacketMenuAction(label = "Share as JSON", prominent = copyText.isNullOrBlank() && specific.none { it.prominent }) { actions.onShareJson(packet.toExportJson(), prefix) })
        add(PacketMenuAction(label = "Copy as JSON") { copy("Packet", packet.toExportJson()) })
    }
}

/** The border of the Digital tab's outlined buttons: the app's outline colour, which reads on the
 *  drawer's surface where Material's default (outline variant) disappears. */
@Composable
fun outlinedButtonBorder(): BorderStroke = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
