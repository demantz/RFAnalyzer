package com.mantz_it.rfanalyzer.digiext.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemKey

/**
 * <h1>RF Analyzer - Paged Packet List</h1>
 *
 * Module:      PagedPacketList.kt
 * Description: The Raw subtab's list of stored packets, loaded page by page.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

/** The Raw subtab's paged list: LazyColumn + stable keys + single-expanded-item bookkeeping. */
@Composable
fun <T : Any, K : Any> PagedPacketList(
    items: LazyPagingItems<T>,
    key: (T) -> K,
    expandedKey: K?,
    onExpandedKeyChange: (K?) -> Unit,
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    emptyText: String? = "No received messages.",
    itemContent: @Composable (item: T, expanded: Boolean, onExpand: () -> Unit) -> Unit,
) {
    // A load failure (e.g. a malformed query against the backing DB) otherwise looks identical to
    // "no results" - the empty-state branch below can't tell them apart on its own.
    val refreshError = (items.loadState.refresh as? LoadState.Error)?.error
    if (refreshError != null) {
        Text(
            text = "Couldn't load packets: ${refreshError.message}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.padding(8.dp),
        )
        return
    }

    // Empty only once loaded: during a refresh (a filter change) the count is briefly 0.
    if (items.itemCount == 0 && emptyText != null && items.loadState.refresh is LoadState.NotLoading) {
        Text(
            text = emptyText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.outline,
            modifier = modifier.padding(8.dp),
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxWidth(), state = state, verticalArrangement = Arrangement.spacedBy(0.dp)) {
        items(items.itemCount, key = items.itemKey { key(it) }) { index ->
            val item = items[index]
            if (item != null) {
                val itemKey = key(item)
                itemContent(item, expandedKey == itemKey) {
                    onExpandedKeyChange(if (expandedKey == itemKey) null else itemKey)
                }
            }
            // else: a Paging3 placeholder row for a page still loading (enablePlaceholders=true) -
            // rendering nothing here is fine, it's replaced once that page arrives.
        }
    }
}
