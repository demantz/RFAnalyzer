package com.mantz_it.rfanalyzer.ui.screens

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.mantz_it.rfanalyzer.database.Recording
import com.mantz_it.rfanalyzer.R
import com.mantz_it.rfanalyzer.database.calculateFileName
import com.mantz_it.rfanalyzer.ui.composable.EditStringDialog
import com.mantz_it.rfanalyzer.ui.composable.FilesourceFileFormat
import com.mantz_it.rfanalyzer.ui.composable.asSizeInBytesToString
import com.mantz_it.rfanalyzer.ui.composable.asStringWithUnit
import com.mantz_it.rfanalyzer.ui.composable.letUserChooseDestinationFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * <h1>RF Analyzer - Recordings Screen</h1>
 *
 * Module:      RecordingsScreen.kt
 * Description: A screen which displays all recordings.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2025 Dennis Mantz
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */


data class RecordingScreenActions(
    val onDelete: (Recording) -> Unit,
    val onPlay: (Recording) -> Unit,
    val onToggleFavorite: (Recording) -> Unit,
    val onSaveToStorage: (Recording, Uri) -> Unit,
    val onShare: (Recording) -> Unit,
    val onDeleteAll: () -> Unit,
    val onToggleDisplayOnlyFavorites: () -> Unit,
    val renameRecording: (Recording, String) -> Unit
)

@Composable
fun RecordingCard(recording: Recording, recordingScreenActions: RecordingScreenActions, selected: Boolean, onClick: () -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    val backgroundColor = if (selected) colorScheme.secondary.copy(alpha = 0.2f) else colorScheme.surface
    val borderColor = if (selected) colorScheme.primary else Color.Transparent
    val destinationFileChooserForSaveTo = letUserChooseDestinationFile(
        suggestedFileName = recording.calculateFileName(),
        mimeType = "application/octet-stream",
        onAbort = { },
        onDestinationChosen = { destUri -> recordingScreenActions.onSaveToStorage(recording, destUri) })

    var showEditDialog by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp)
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .background(backgroundColor)
            .clickable { onClick() },
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row {
                Text(text = recording.name, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                if(recording.favorite)
                    Icon(imageVector = Icons.Default.Favorite, contentDescription = "Is Favorite", tint = Color.Red)
                Box(
                    modifier = Modifier
                        .background(
                            when (recording.fileFormat) {
                                FilesourceFileFormat.RTLSDR -> Color.Blue
                                FilesourceFileFormat.HACKRF -> Color.Green
                                FilesourceFileFormat.AIRSPY -> Color(alpha = 1f, red = 1f, green = 0.4f, blue = 0f) // orange
                                FilesourceFileFormat.HYDRASDR -> Color(alpha = 1f, red = 1f, green = 0.4f, blue = 0f) // orange
                            },
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = recording.fileFormat.displayName,
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Row {
                Text(
                    text = recording.frequency.asStringWithUnit("Hz"),
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp)
                Text(text = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(recording.date)))
            }
            Row{
                Text(text = recording.sampleRate.asStringWithUnit("Sps"),
                    fontSize = 16.sp, modifier = Modifier.weight(1f))
                val recordingDurationInSeconds = recording.sizeInBytes / recording.fileFormat.bytesPerSample / recording.sampleRate
                Text(text = "${recording.sizeInBytes.asSizeInBytesToString()} ($recordingDurationInSeconds seconds)", fontSize = 16.sp)
            }

            if(selected) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { recordingScreenActions.onPlay(recording) }) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play", modifier = Modifier.size(40.dp))
                    }
                    IconButton(onClick = { showEditDialog = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit", modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = {
                        recordingScreenActions.onDelete(recording)
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete", modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = { recordingScreenActions.onShare(recording) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share", modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = destinationFileChooserForSaveTo) {
                        Icon(painter = painterResource(R.drawable.file_save), contentDescription = "Save to Storage", modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = { recordingScreenActions.onToggleFavorite(recording) }) {
                        Icon(
                            imageVector = if (recording.favorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                            contentDescription = "Toggle Favorite",
                            tint = if (recording.favorite) Color.Red else LocalContentColor.current,
                            modifier = Modifier.size(35.dp))
                    }
                }
            }

            if (showEditDialog) {
                EditStringDialog(
                    title = "Edit Recording Name",
                    label = "Name",
                    initialValue = recording.name,
                    onDismiss = { showEditDialog = false },
                    onConfirm = { newName ->
                        recordingScreenActions.renameRecording(recording, newName)
                        showEditDialog = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsScreen(
        navController: NavHostController,
        recordingsStateFlow: StateFlow<List<Recording>>,
        displayOnlyFavoriteRecordingsStateFlow: StateFlow<Boolean>,
        recordingsScreenActions: RecordingScreenActions) {
    val recordings by recordingsStateFlow.collectAsState()
    val displayOnlyFavorites by displayOnlyFavoriteRecordingsStateFlow.collectAsState()
    var selectedRecording: Recording? by remember { mutableStateOf(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recordings") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if(recordings.isNotEmpty()) {
                        IconButton(onClick = recordingsScreenActions.onDeleteAll) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete All")
                        }
                        IconButton(onClick = recordingsScreenActions.onToggleDisplayOnlyFavorites) {
                            Icon(Icons.Filled.Favorite, contentDescription = "Filter Favorites",
                                tint = if (displayOnlyFavorites) Color.Red else LocalContentColor.current
                                )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if(recordings.isEmpty()) {
            Box(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
                Text("No Recordings.", modifier = Modifier.align(Alignment.Center))
            }
        } else {
            LazyColumn(modifier = Modifier.padding(paddingValues)) {
                items(
                    items = if (displayOnlyFavorites) recordings.filter { it.favorite } else recordings,
                    key = { it.id }
                ) { recording ->
                    RecordingCard(
                        recording,
                        recordingScreenActions = recordingsScreenActions,
                        selected = recording == selectedRecording,
                        onClick = {
                            selectedRecording =
                                if (selectedRecording == recording) null else recording
                        }
                    )
                }
            }
        }
    }
}

@Preview
@Composable
fun RecordingCardPreview () {
    RecordingCard(
        recording = Recording(
            name = "My Recording",
            frequency = 103323000,
            sampleRate = 2000000,
            date = 1534567890000,
            fileFormat = FilesourceFileFormat.RTLSDR,
            sizeInBytes = 10 * 1024 * 1024,
            filePath = "abc://test",
            favorite = true
        ),
        recordingScreenActions = RecordingScreenActions(
            onDelete = { _ -> },
            onPlay = { },
            onToggleFavorite = { },
            onSaveToStorage = { _, _ -> },
            onShare = { },
            onDeleteAll = { },
            onToggleDisplayOnlyFavorites = { },
            renameRecording = { _, _ -> }
        ),
        true,
        { }
    )
}

@Preview
@Composable
fun RecScreenPreview() {
    val rec = Recording(
        name = "My Recording",
        frequency = 103323000,
        sampleRate = 2000000,
        date = 1534567890000,
        fileFormat = FilesourceFileFormat.HYDRASDR,
        sizeInBytes = 10 * 1024 * 1024,
        filePath = "abc://test",
        favorite = true
    )
    //val _recordings = MutableStateFlow<List<Recording>>(emptyList())
    val _recordings = MutableStateFlow<List<Recording>>(listOf(rec))
    val recordings: StateFlow<List<Recording>> = _recordings.asStateFlow()
    RecordingsScreen(
        rememberNavController(),
        recordingsStateFlow = recordings,
        displayOnlyFavoriteRecordingsStateFlow = MutableStateFlow(false).asStateFlow(),
        recordingsScreenActions = RecordingScreenActions(
            onDelete = { },
            onPlay = { },
            onToggleFavorite = { },
            onSaveToStorage = { _, _ -> },
            onShare = { },
            onDeleteAll = { },
            onToggleDisplayOnlyFavorites = { },
            renameRecording = { _, _ -> }
        ),
    )
}