package com.mantz_it.rfanalyzer.digiext.lora.meshcore

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * <h1>RF Analyzer - MeshCore Channels Dialog</h1>
 *
 * Module:      MeshCoreChannelsDialog.kt
 * Description: Minimal editor for the user's custom MeshCore hashtag channels. The built-in default
 *              channels (Public + the preloaded hashtags in [MeshCoreChannelRegistry]) are always
 *              active and are not listed here - this only manages the user's additions.
 *
 * [customChannels] is the current persisted list (bare names, no leading '#'); every edit calls
 * [onChannelsChanged] with the new full list, which the caller persists. Names are normalized and
 * validated against MeshCore's 32-byte name cap before being accepted.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */
@Composable
fun MeshCoreChannelsDialog(
    customChannels: List<String>,
    onChannelsChanged: (List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var newName by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun add() {
        // Normalize to the on-air '#'-prefixed lowercase form, then store the bare name.
        val normalized = MeshCoreChannelRegistry.normalizedHashtag(newName).removePrefix("#")
        when {
            normalized.isEmpty() -> error = "Enter a channel name"
            ("#$normalized").toByteArray(Charsets.UTF_8).size > MeshCoreChannelRegistry.MAX_CHANNEL_NAME_BYTES ->
                error = "Name too long (max ${MeshCoreChannelRegistry.MAX_CHANNEL_NAME_BYTES} bytes incl. '#')"
            customChannels.any { it.equals(normalized, ignoreCase = true) } -> error = "Already added"
            else -> {
                onChannelsChanged(customChannels + normalized)
                newName = ""
                error = null
            }
        }
    }

    fun clipboard() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

    fun copyAll() {
        val text = customChannels.joinToString("\n") { "#$it" }
        clipboard().setPrimaryClip(ClipData.newPlainText("MeshCore channels", text))
    }

    fun pasteFromClipboard() {
        val clipText = clipboard().primaryClip?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString() ?: return
        // Accept line-, comma- or semicolon-separated names, with or without a leading '#'.
        val merged = (customChannels + parsePastedChannelNames(clipText)).distinctBy { it.lowercase() }
        if (merged.size != customChannels.size) {
            onChannelsChanged(merged)
            error = null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("MeshCore Hashtag Channels") },
        text = {
            Column {
                Text(
                    "Add public #hashtag channels to decode group chat on. Built-in channels " +
                        "(Public, #test, …) are always active and not listed here.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it; error = null },
                        label = { Text("Channel name (e.g. #rhein-neckar)") },
                        singleLine = true,
                        isError = error != null,
                        supportingText = error?.let { { Text(it) } },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { add() }),
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { add() }) { Text("Add") }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { copyAll() }, enabled = customChannels.isNotEmpty()) { Text("Copy to clipboard") }
                    TextButton(onClick = { pasteFromClipboard() }) { Text("Paste from clipboard") }
                }

                if (customChannels.isEmpty()) {
                    Text(
                        "No custom channels added.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).padding(top = 8.dp)) {
                        items(customChannels, key = { it }) { name ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("#$name", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                                IconButton(onClick = { onChannelsChanged(customChannels - name) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "Delete #$name")
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        dismissButton = {
            if (customChannels.isNotEmpty()) {
                TextButton(onClick = { onChannelsChanged(emptyList()) }) { Text("Delete all") }
            }
        },
    )
}

/** Parses pasted clipboard text into bare, normalized channel names: split on newlines / commas /
 *  semicolons, strip any leading '#', lowercase, and drop blanks and names over MeshCore's length
 *  cap. Order-preserving; de-duplication against the existing list is left to the caller. */
internal fun parsePastedChannelNames(raw: String): List<String> =
    raw.split('\n', '\r', ',', ';')
        .map { MeshCoreChannelRegistry.normalizedHashtag(it).removePrefix("#") }
        .filter {
            it.isNotEmpty() &&
                ("#$it").toByteArray(Charsets.UTF_8).size <= MeshCoreChannelRegistry.MAX_CHANNEL_NAME_BYTES
        }
