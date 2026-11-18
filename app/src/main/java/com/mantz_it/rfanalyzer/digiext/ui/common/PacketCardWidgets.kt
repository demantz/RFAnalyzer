package com.mantz_it.rfanalyzer.digiext.ui.common

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * <h1>RF Analyzer - Packet Card Widgets</h1>
 *
 * Module:      PacketCardWidgets.kt
 * Description: Small shared pieces of the Digital tab's cards and pages: a pill badge (also used
 *              outside the tab), a collapsible section, a popup's recent-packet lines and the
 *              lookup URLs.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** Coloured pill badge. */
@Composable
fun PacketBadge(label: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(color.copy(alpha = 0.2f), shape = RoundedCornerShape(40.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * A titled section that opens and closes on a tap anywhere in its title row. Controlled, so a
 * caller with several (RDS's traffic messages, other networks, groups) can keep one open at a time.
 */
@Composable
fun CollapsibleSection(title: String, expanded: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp).clickable { onToggle() },
        ) {
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.rotate(if (expanded) 0f else -90f),
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(text = title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) content()
    }
}

// The external lookups (QRZ.com, aprs.fi, FlightAware, PSKReporter) and "Open in maps app": all go
// through MainViewModel.openUrl(url) and an ACTION_VIEW intent.

fun qrzUrl(callsign: String): String = "https://www.qrz.com/db/${Uri.encode(callsign)}"

/** aprs.fi's station info page (track history, comment, last packets) - not the live-map view. */
fun aprsFiUrl(callsign: String): String = "https://aprs.fi/info/a/${Uri.encode(callsign)}"

/** FlightAware's "current flight for this Mode S hex" redirect endpoint. */
fun flightAwareUrl(icaoHex: String): String = "https://flightaware.com/live/modes/$icaoHex/redirect"

fun pskReporterUrl(callsign: String): String =
    "https://www.pskreporter.info/pskmap.html?callsign=${Uri.encode(callsign)}"

fun geoMapUrl(lat: Double, lon: Double): String = "geo:$lat,$lon?q=$lat,$lon"

/** One line of [RecentPacketsList]: when a packet was received and what it said. */
data class RecentPacket(val timestampMs: Long, val text: String)

private const val RECENT_PACKETS_SHOWN = 5

/** The last few packets of an object, newest first, one line each - the Map popup's stand-in for
 *  the message list of an opened row. Renders nothing for an empty list. */
@Composable
fun RecentPacketsList(packets: List<RecentPacket>, modifier: Modifier = Modifier) {
    if (packets.isEmpty()) return
    val nowMs = System.currentTimeMillis()
    Column(modifier = modifier.padding(top = 6.dp)) {
        Text("Recent", style = DigitalTextStyles.faint, modifier = Modifier.padding(bottom = 2.dp))
        packets.take(RECENT_PACKETS_SHOWN).forEach { packet ->
            Row {
                Text(formatShortTime(nowMs, packet.timestampMs), style = DigitalTextStyles.faint, modifier = Modifier.width(46.dp))
                Text(packet.text, style = DigitalTextStyles.value, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
