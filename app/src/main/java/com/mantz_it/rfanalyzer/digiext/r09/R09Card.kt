package com.mantz_it.rfanalyzer.digiext.r09

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.ui.OverviewContext
import com.mantz_it.rfanalyzer.digiext.ui.common.CardActions
import com.mantz_it.rfanalyzer.digiext.ui.common.CardListPadding
import com.mantz_it.rfanalyzer.digiext.ui.common.Fields
import com.mantz_it.rfanalyzer.digiext.ui.common.IdentityText
import com.mantz_it.rfanalyzer.digiext.ui.common.LiveTicker
import com.mantz_it.rfanalyzer.digiext.ui.common.PacketCard
import com.mantz_it.rfanalyzer.digiext.ui.common.PayloadText
import com.mantz_it.rfanalyzer.digiext.ui.common.RadioLine
import com.mantz_it.rfanalyzer.digiext.ui.common.RawBytes
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerLines
import com.mantz_it.rfanalyzer.digiext.ui.common.TickerWaiting
import com.mantz_it.rfanalyzer.digiext.ui.common.TypeTag
import com.mantz_it.rfanalyzer.digiext.ui.common.fields
import com.mantz_it.rfanalyzer.digiext.ui.common.formatFrequency
import com.mantz_it.rfanalyzer.digiext.ui.common.metaLine
import com.mantz_it.rfanalyzer.digiext.ui.common.packetActions
import com.mantz_it.rfanalyzer.digiext.ui.common.rememberLatestLive
import com.mantz_it.rfanalyzer.digiext.ui.common.signed
import com.mantz_it.rfanalyzer_ipc.digiext.R09Phy
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * <h1>RF Analyzer - R09 Card</h1>
 *
 * Module:      R09Card.kt
 * Description: How R09 telegrams read: the telegram card, the ticker and the texts of rows and
 *              lists. The telegram's terms stay the German ones of the specification (VDV 420):
 *              Linie, Kurs, Ziel, Meldepunkt and the four Tabelleneinträge.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The report a telegram makes at its Meldepunkt. */
internal fun tabelleneintragName(entry: R09Paket.Tabelleneintrag): String = when (entry) {
    R09Paket.Tabelleneintrag.VORANM -> "Voranmeldung"
    R09Paket.Tabelleneintrag.HPTANM -> "Hauptanmeldung"
    R09Paket.Tabelleneintrag.ABMELD -> "Abmeldung"
    R09Paket.Tabelleneintrag.ABFBER -> "Abfahrbereit"
}

/** The Tabelleneinträge of one Meldepunkt in their fixed order: "✓✓··" - received or not. */
internal fun tabelleneintragMarks(received: Set<R09Paket.Tabelleneintrag>): String =
    R09Paket.Tabelleneintrag.entries.joinToString("") { if (it in received) "✓" else "·" }

/** A schedule deviation: "±0 min", "+2 min", "−1 min". */
internal fun formatDeviation(minutes: Int): String = if (minutes == 0) "±0 min" else "${signed(minutes.toLong())} min"

/** "MP 1602 · ±0 min · Ziel 45" - what a telegram says, without the bus it is about. Short
 *  telegrams leave out what they do not carry. */
internal fun r09TelegramText(paket: R09Paket): String = metaLine(
    "MP ${paket.mp}",
    formatDeviation(paket.zw),
    paket.zielnummer.takeIf { paket.laenge > 5 }?.let { "Ziel $it" },
)

/** "Linie 31 · Kurs 0" - the bus a telegram is about; just the line for a short telegram. */
internal fun r09BusName(paket: R09Paket): String = when {
    paket.laenge > 3 -> "Linie ${paket.liniennummer} · Kurs ${paket.kursnummer}"
    paket.laenge > 2 -> "Linie ${paket.liniennummer}"
    else -> "R09.${paket.typ}"
}

/** The opened card's radio line. */
private fun radioLine(phy: R09Phy, frequencyHz: Long): Array<String?> = arrayOf(
    phy.snrDb?.let { "SNR %.1f dB".format(Locale.US, it) },
    phy.frequencyOffsetHz?.let { "%+.0f Hz off".format(Locale.US, it) },
    phy.deviationHz?.let { "deviation %.1f kHz".format(Locale.US, it / 1000) },
    "${phy.baudRate} bit/s",
    frequencyHz.takeIf { it != 0L }?.let(::formatFrequency),
)

@Composable
private fun BusIcon(size: Int = 16) {
    Icon(Icons.Default.DirectionsBus, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(size.dp))
}

/**
 * One telegram. Header: the bus (Linie, Kurs), its report at the Meldepunkt, the time. Payload:
 * Meldepunkt, deviation, Ziel. Opened: every field the telegram carries, the radio line, the bytes.
 */
@Composable
fun R09MessageCard(
    message: R09Telegram,
    expanded: Boolean,
    onExpand: () -> Unit,
    actions: CardActions,
    modifier: Modifier = Modifier,
    outerPadding: PaddingValues = CardListPadding,
) {
    val paket = message.data
    PacketCard(
        expanded = expanded,
        onExpand = onExpand,
        timestampMs = message.timestampMs,
        modifier = modifier,
        outerPadding = outerPadding,
        identity = { IdentityText(r09BusName(paket), leading = { BusIcon() }) },
        tags = { TypeTag(tabelleneintragName(paket.tabelleneintrag)) },
        payload = { open -> PayloadText(r09TelegramText(paket), open) },
        expandedContent = {
            Fields(fields {
                add("Meldepunkt", paket.mp.toString())
                add("Tabelleneintrag", tabelleneintragName(paket.tabelleneintrag))
                add("Abweichung", formatDeviation(paket.zw))
                if (paket.laenge > 2) add("Linie", paket.liniennummer.toString())
                if (paket.laenge > 3) add("Kurs", paket.kursnummer.toString())
                if (paket.laenge > 5) add("Ziel", paket.zielnummer.toString())
                if (paket.laenge > 1) add("Priorität", paket.prioritaet.toString())
                if (paket.laenge > 1) add("Handanforderung", paket.handanforderung.toString())
                if (paket.laenge > 5) add("Zuglänge", paket.zuglaenge.toString())
                add("Telegramm", "R09.${paket.typ} · Länge ${paket.laenge}")
            })
            RadioLine(*radioLine(message.phy, message.frequencyHz))
            RawBytes(message.raw)
        },
        actions = packetActions(message, actions),
    )
}

/** The R09 ticker: the last telegram - the bus and its Ziel on line 1, the report below. */
@Composable
internal fun R09Ticker(telegrams: Flow<R09Telegram>, context: OverviewContext, lines: Int) {
    val telegram = rememberLatestLive(telegrams, context.live?.session?.startMs)
    LiveTicker(label = context.tickerLabel(telegram?.let { "${it.phy.baudRate} bit/s" }), flashKey = telegram?.timestampMs) {
        if (telegram == null) {
            TickerWaiting("Listening on ${formatFrequency(context.live?.frequencyHz ?: 0)}…", lines)
        } else {
            val paket = telegram.data
            TickerLines(
                lines = lines,
                identity = r09BusName(paket),
                icon = { BusIcon(15) },
                meta = metaLine(tabelleneintragName(paket.tabelleneintrag), telegram.phy.snrDb?.let { "%.0f dB".format(Locale.US, it) }),
                timestampMs = telegram.timestampMs,
                payload = metaLine("MP ${paket.mp} ${tabelleneintragName(paket.tabelleneintrag)}", formatDeviation(paket.zw), paket.zielnummer.takeIf { paket.laenge > 5 }?.let { "Ziel $it" }),
            )
        }
    }
}
