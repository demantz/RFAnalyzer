package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * <h1>RF Analyzer - Digital Text</h1>
 *
 * Module:      DigitalText.kt
 * Description: The text styles every row, ticker, card and page of the Digital tab is built from,
 *              and the few small text elements they share. Roboto with tabular figures throughout
 *              (digits line up in columns without monospace's width); monospace only for teletype
 *              text and hex.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Digits of equal width, so numbers line up in a column. */
const val TABULAR_FIGURES = "tnum"

object DigitalTextStyles {
    /** A row's or card's identity: callsign, capcode, name. */
    val identity: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR_FIGURES,
            color = MaterialTheme.colorScheme.primary,
        )

    /** What was said: message, page, report. */
    val payload: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodyMedium.copy(
            fontSize = 14.sp, lineHeight = 19.sp, fontFeatureSettings = TABULAR_FIGURES, color = MaterialTheme.colorScheme.onSurface,
        )

    /** A table row's payload - a little smaller than a card's. */
    val rowPayload: TextStyle
        @Composable @ReadOnlyComposable get() = payload.copy(fontSize = 13.5.sp, lineHeight = 18.sp)

    /** Teletype text and other fixed-pitch content. */
    val mono: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp, color = MaterialTheme.colorScheme.onSurface,
        )

    /** Small secondary facts next to or under the payload. */
    val meta: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall.copy(
            fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TABULAR_FIGURES, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

    /** Times, ages and other numbers that only place a row. */
    val faint: TextStyle
        @Composable @ReadOnlyComposable get() = meta.copy(color = MaterialTheme.colorScheme.outline)

    /** A table's metric and column cells. */
    val cell: TextStyle
        @Composable @ReadOnlyComposable get() = meta.copy(fontSize = 12.5.sp)

    /** Labels of a label/value list. */
    val label: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall.copy(
            fontSize = 12.5.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.outline,
        )

    /** Values of a label/value list. */
    val value: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.bodySmall.copy(
            fontSize = 13.sp, lineHeight = 18.sp, fontFeatureSettings = TABULAR_FIGURES, color = MaterialTheme.colorScheme.onSurface,
        )
}

/**
 * The Digital tab's surfaces, derived from the drawer's background (`surfaceVariant`) and the app's
 * `surface` - not from Material's container roles, which the app's own colour schemes leave at
 * Material's purple baseline (and which, in the light theme, all but vanish on the drawer).
 */
object DigitalSurfaces {
    private val isDark: Boolean
        @Composable @ReadOnlyComposable get() = MaterialTheme.colorScheme.surface.luminance() < 0.5f

    /** A packet card: a step darker than the drawer in the dark theme, near white in the light one. */
    val card: Color
        @Composable @ReadOnlyComposable get() =
            lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface, if (isDark) 0.3f else 0.8f)

    /** The table's header row: a dark band in the dark theme, a slightly darker one in the light. */
    val band: Color
        @Composable @ReadOnlyComposable get() =
            if (isDark) lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.surface, 0.75f)
            else lerp(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurface, 0.06f)
}

/**
 * Where [identities] (callsigns, node names - whatever the text's object knows to be one) stand in
 * [text] as whole words. Only these are coloured, never a guess at what looks like a callsign.
 */
fun identityRanges(text: String, identities: Collection<String>): List<IntRange> {
    if (identities.isEmpty() || text.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    identities.filter { it.isNotBlank() }.distinct().forEach { identity ->
        var from = 0
        while (true) {
            val at = text.indexOf(identity, from)
            if (at < 0) break
            val end = at + identity.length
            if (!text.isWordChar(at - 1) && !text.isWordChar(end)) ranges += at until end
            from = end
        }
    }
    return ranges.sortedBy { it.first }
}

// "-" belongs to the word (an SSID: DL1ABC is not DL1ABC-9), "/" does not (DL1ABC/P is DL1ABC).
private fun String.isWordChar(index: Int): Boolean = index in indices && (this[index].isLetterOrDigit() || this[index] == '-')

/** [text] with its [identities] in the identity colour - see [identityRanges]. */
@Composable
@ReadOnlyComposable
fun identityStyled(text: String, identities: Collection<String>): AnnotatedString {
    val ranges = identityRanges(text, identities)
    if (ranges.isEmpty()) return AnnotatedString(text)
    val style = SpanStyle(color = MaterialTheme.colorScheme.primary)
    return buildAnnotatedString {
        append(text)
        ranges.forEach { addStyle(style, it.first, it.last + 1) }
    }
}

/** The warning colour of [WarningText]: errors stay red, everything else is amber. */
@Composable
@ReadOnlyComposable
fun warningColor(error: Boolean = false): Color =
    if (error) MaterialTheme.colorScheme.error else Color(0xFFE0A030)

/**
 * One word for something out of the ordinary - "CRC error", "Emergency", "Encrypted". Normal states
 * get no word at all.
 */
@Composable
fun WarningText(text: String, modifier: Modifier = Modifier, error: Boolean = true) {
    Text(
        text = text,
        style = DigitalTextStyles.meta.copy(fontWeight = FontWeight.Medium, color = warningColor(error)),
        maxLines = 1,
        modifier = modifier,
    )
}

/** A message type where the type is part of the message ("Hauptanmeldung", "Message", "MeshCore"):
 *  a small tonal chip - with the identity, the card's colour accent. */
@Composable
fun TypeTag(text: String, modifier: Modifier = Modifier, emphasized: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Text(
        text = text,
        style = DigitalTextStyles.meta.copy(
            fontSize = 11.5.sp,
            lineHeight = 15.sp,
            fontWeight = FontWeight.Medium,
            color = if (emphasized) colors.onPrimaryContainer else colors.onSecondaryContainer,
        ),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(if (emphasized) colors.primaryContainer else colors.secondaryContainer, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp),
    )
}
