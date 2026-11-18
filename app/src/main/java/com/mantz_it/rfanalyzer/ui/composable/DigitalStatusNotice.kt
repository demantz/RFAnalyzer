package com.mantz_it.rfanalyzer.ui.composable

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mantz_it.rfanalyzer.digiext.DigitalAction
import com.mantz_it.rfanalyzer.digiext.DigitalNotice

/**
 * <h1>RF Analyzer - Digital Status Notice</h1>
 *
 * Module:      DigitalStatusNotice.kt
 * Description: Why digital modes aren't received (see DigitalAvailability.kt), with the one thing
 *              to do about it - the same notice in the Digital tab, its mode sheet and the
 *              Demodulation tab. In the content's flow rather than over it: while it shows, nothing
 *              is decoded.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Composable
fun DigitalStatusNotice(notice: DigitalNotice, onAction: (DigitalAction) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (notice.isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (notice.isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = modifier,
    ) {
        Row(modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp)) {
            Icon(if (notice.isError) Icons.Default.Warning else Icons.Default.Info, contentDescription = null, modifier = Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f).padding(start = 10.dp, end = 8.dp)) {
                Text(notice.title, style = MaterialTheme.typography.labelLarge)
                Text(notice.text, style = MaterialTheme.typography.bodySmall)
                // Below the text: the labels name an app and would squeeze it on a phone.
                notice.action?.let { action ->
                    TextButton(onClick = { onAction(action) }, modifier = Modifier.align(Alignment.End)) { Text(action.label) }
                } ?: Spacer(Modifier.height(8.dp))
            }
        }
    }
}
