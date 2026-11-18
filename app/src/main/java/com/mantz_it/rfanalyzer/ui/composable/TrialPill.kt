package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * <h1>RF Analyzer - Trial Pill</h1>
 *
 * Module:      TrialPill.kt
 * Description: The trial's time left - operating time and, where the build has them, the digital
 *              modes' decoding time - in a small pill that hovers above the control drawer while
 *              the trial runs (MainScreen). Always visible, but small and quiet; a tap explains it.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** "1h 52m", "42m", "<1m", "over". */
internal fun formatTrialTimeShort(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
        minutes >= 1 -> "${minutes}m"
        seconds > 0 -> "<1m"
        else -> "over"
    }
}

/** "Trial 42m · Digital 1h 52m"; null leaves a part out: the trial with the Base Version (only the
 *  digital modes' trial counts), the digital one without digital modes in the build. */
internal fun trialPillText(trialSecondsLeft: Int?, digitalTrialSecondsLeft: Int?): String = listOfNotNull(
    trialSecondsLeft?.let { "Trial ${formatTrialTimeShort(it)}" },
    digitalTrialSecondsLeft?.let { "Digital ${formatTrialTimeShort(it)}" },
).joinToString(" · ")

/** The pill takes taps 8 dp above and below what it shows. */
@Composable
fun TrialPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val content = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                .indication(interaction, ripple())
                .padding(start = 6.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
        ) {
            Icon(Icons.Default.HourglassBottom, contentDescription = "Trial version", tint = content, modifier = Modifier.size(11.dp))
            Text(text, color = content, fontSize = 10.sp, lineHeight = 12.sp, modifier = Modifier.padding(start = 3.dp))
        }
    }
}
