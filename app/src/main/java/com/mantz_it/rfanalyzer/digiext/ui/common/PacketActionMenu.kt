package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * <h1>RF Analyzer - Packet Action Menu</h1>
 *
 * Module:      PacketActionMenu.kt
 * Description: The actions menu of the Digital tab's cards and table rows: [PacketActionMenu], a
 *              `MoreVert` button that opens a dropdown of [PacketMenuAction]s, and
 *              [PacketActionDropdown], the dropdown alone for callers with their own trigger.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** One entry in a [PacketActionMenu]. */
data class PacketMenuAction(
    val label: String,
    val icon: ImageVector? = null,
    /** Shown as a button of its own in an [ActionStrip] rather than behind "⋯". */
    val prominent: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * A `MoreVert` button that opens [actions]. Its `IconButton` takes its own click, so inside a
 * clickable card a tap on it does not also open or close the card.
 */
@Composable
fun PacketActionMenu(
    actions: List<PacketMenuAction>,
    contentDescription: String = "Actions",
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.MoreVert, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
        }
        PacketActionDropdown(expanded = expanded, onDismissRequest = { expanded = false }, actions = actions)
    }
}

/**
 * The dropdown half of [PacketActionMenu], for callers with their own trigger: a card's or table
 * row's long press, the "⋯" of an [ActionStrip].
 */
@Composable
fun PacketActionDropdown(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    actions: List<PacketMenuAction>,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest) {
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                leadingIcon = action.icon?.let { icon -> { Icon(icon, contentDescription = null) } },
                onClick = {
                    onDismissRequest()
                    action.onClick()
                },
            )
        }
    }
}
