package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.analyzer.OverloadMonitor

/**
 * <h1>RF Analyzer - Overload Chip</h1>
 *
 * Module:      OverloadChip.kt
 * Description: Warning shown while the device can't keep up and samples are being dropped (see
 *              [OverloadMonitor]); renders nothing otherwise. The "?" opens the manual section
 *              explaining the cause and what to do about it.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Composable
fun OverloadChip(onHelpClicked: () -> Unit, modifier: Modifier = Modifier) {
    val state by OverloadMonitor.state.collectAsState()
    if (!state.chipVisible) return
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
            Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                "Overload – samples dropped",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 8.dp),
            )
            IconButton(onClick = onHelpClicked, modifier = Modifier.size(36.dp)) {
                Icon(Icons.AutoMirrored.Filled.HelpOutline, contentDescription = "What does this mean?", modifier = Modifier.size(18.dp))
            }
        }
    }
}
