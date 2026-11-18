package com.mantz_it.rfanalyzer.digiext.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mantz_it.rfanalyzer.digiext.ui.common.DigitalTextStyles

/**
 * <h1>RF Analyzer - Details Page</h1>
 *
 * Module:      DetailsPage.kt
 * Description: One object on a page of its own inside the Digital tab - reached with "Details"
 *              from its opened row, left with the back arrow or the back gesture, which return to
 *              the table where it was. An extra next to the nested rows, not a replacement.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@Composable
internal fun DetailsPageScaffold(
    title: String,
    kindLabel: String,
    icon: (@Composable () -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(end = 12.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to the table") }
            icon?.let {
                it()
                Spacer(Modifier.width(6.dp))
            }
            Text(
                title,
                style = DigitalTextStyles.identity.copy(fontSize = 17.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(kindLabel, style = DigitalTextStyles.meta, maxLines = 1)
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
        ) {
            content()
            Spacer(Modifier.height(8.dp))
        }
    }
}
