package com.mantz_it.rfanalyzer.ui.composable

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * <h1>RF Analyzer - File Pickers</h1>
 *
 * Module:      FilePickers.kt
 * Description: Launchers for the system file pickers: rememberCreateFilePicker lets the user create
 *              a file under a suggested name, rememberOpenFilePicker lets them choose one to open
 *              and reports its URI and display name.
 *
 * @author Dennis Mantz
 * Copyright (C) 2026 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 */

@Composable
fun rememberCreateFilePicker(
    suggestedFileName: String,
    mimeType: String = "*/*",
    onFileCreated: (Uri) -> Unit,
    onAbort: () -> Unit = {}
): () -> Unit {
    val createFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(mimeType),
        onResult = { uri -> if (uri != null) onFileCreated(uri) else onAbort() }
    )
    return { createFileLauncher.launch(suggestedFileName) }
}

@Composable
fun rememberOpenFilePicker(
    mimeTypes: Array<String> = arrayOf("*/*"),
    onFileChosen: (uri: Uri, filename: String?) -> Unit,
    onAbort: () -> Unit = {}
): () -> Unit {
    val context = LocalContext.current
    val openFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                }
                onFileChosen(uri, displayName)
            } else {
                onAbort()
            }
        }
    )
    return { openFileLauncher.launch(mimeTypes) }
}
