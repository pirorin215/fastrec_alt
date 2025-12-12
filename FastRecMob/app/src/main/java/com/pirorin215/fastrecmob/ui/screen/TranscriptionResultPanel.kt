package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import android.widget.Toast
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pirorin215.fastrecmob.data.FileUtil
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.viewModel.MainViewModel
import com.pirorin215.fastrecmob.viewModel.AppSettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptionResultPanel(viewModel: MainViewModel, appSettingsViewModel: AppSettingsViewModel, modifier: Modifier = Modifier) {
    val transcriptionResults by viewModel.transcriptionResults.collectAsState()
    val scope = rememberCoroutineScope()
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirmDialog by remember { mutableStateOf(false) }
    var showAddManualTranscriptionDialog by remember { mutableStateOf(false) }
    val fontSize by viewModel.transcriptionFontSize.collectAsState()
    val selectedFileNames by viewModel.selectedFileNames.collectAsState()
    val isPlaying by viewModel.isPlaying.collectAsState() // Observe isPlaying state

    var selectedResultForDetail by remember { mutableStateOf<TranscriptionResult?>(null) }
    val audioDirName by viewModel.audioDirName.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp), // Added padding for better spacing
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isSelectionMode = selectedFileNames.isNotEmpty()
                    val showCompleted by appSettingsViewModel.showCompletedGoogleTasks.collectAsState()
                    val transcriptionCount by viewModel.transcriptionCount.collectAsState()
                    val audioFileCount by viewModel.audioFileCount.collectAsState()

                    Text(
                        "メモ: $transcriptionCount 件, WAV: $audioFileCount 件",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f) // Give it weight to push other elements
                    )

                    Text(
                        "同期済表示",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                    Switch(
                        checked = showCompleted,
                        onCheckedChange = { appSettingsViewModel.saveShowCompletedGoogleTasks(it) },
                        modifier = Modifier.height(24.dp)
                    )
                    
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            showDeleteSelectedConfirmDialog = true
                        } else {
                            showDeleteAllConfirmDialog = true
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = if (isSelectionMode) "Delete Selected" else "Clear All")
                    }
                    
                    IconButton(onClick = { showAddManualTranscriptionDialog = true }) {
                        Icon(Icons.Filled.Add, "手動で文字起こしを追加")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp)) // Add this Spacer for separation
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                if (transcriptionResults.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillParentMaxSize(), contentAlignment = Alignment.Center) {
                            Text("no data")
                        }
                    }
                } else {
                    items(items = transcriptionResults, key = { it.fileName }) { result ->
                        TranscriptionResultItem(
                            result = result,
                            fontSize = fontSize,
                            isSelected = selectedFileNames.contains(result.fileName),
                            onItemClick = { clickedItem -> selectedResultForDetail = clickedItem },
                            onToggleSelection = { fileName -> viewModel.toggleSelection(fileName) }
                        )
                        HorizontalDivider()
                    }
                }
            }
    }
        selectedResultForDetail?.let { result ->
            TranscriptionDetailBottomSheet(
                result = result,
                fontSize = fontSize,
                audioFileExists = FileUtil.getAudioFile(context, audioDirName, result.fileName).exists(),
                audioDirName = audioDirName,
                isPlaying = isPlaying, // Pass the isPlaying state
                onPlay = { transcriptionResult ->
                    viewModel.playAudioFile(transcriptionResult)
                },
                onStop = { viewModel.stopAudioFile() }, // Pass the stopAudioFile lambda
                onDelete = { transcriptionResult ->
                    scope.launch {
                        viewModel.removeTranscriptionResult(transcriptionResult)
                        selectedResultForDetail = null
                    }
                },
                onSave = { originalResult, newText, newNote -> // Added newNote parameter
                    scope.launch {
                        viewModel.updateTranscriptionResult(originalResult, newText, newNote) // Passed newNote
                        selectedResultForDetail = null
                    }
                },
                onRetranscribe = { transcriptionResult ->
                    scope.launch {
                        viewModel.retranscribe(transcriptionResult)
                        selectedResultForDetail = null
                    }
                },
                onDismiss = { selectedResultForDetail = null }
            )
        }

        if (showDeleteAllConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteAllConfirmDialog = false },
                title = { Text("全件クリア") },
                text = { Text("全てのメモを削除しますか？") },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            viewModel.clearTranscriptionResults()
                            showDeleteAllConfirmDialog = false
                        }
                    }) { Text("削除") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showDeleteAllConfirmDialog = false }) { Text("キャンセル") }
                }
            )
        }
        if (showDeleteSelectedConfirmDialog) {
            AlertDialog(
                onDismissRequest = { showDeleteSelectedConfirmDialog = false },
                title = { Text("選択した項目を削除") },
                text = { Text("${selectedFileNames.size} 件を削除しますか？") },
                confirmButton = {
                    Button(onClick = {
                        scope.launch {
                            viewModel.removeTranscriptionResults(selectedFileNames)
                            showDeleteSelectedConfirmDialog = false
                        }
                    }) { Text("削除") }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showDeleteSelectedConfirmDialog = false }) { Text("キャンセル") }
                }
            )
        }

        if (showAddManualTranscriptionDialog) {
            var transcriptionText by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showAddManualTranscriptionDialog = false },
                title = { Text("新規作成") },
                text = {
                    OutlinedTextField(
                        value = transcriptionText,
                        onValueChange = { transcriptionText = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 10
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (transcriptionText.isNotBlank()) {
                                viewModel.addManualTranscription(transcriptionText)
                                transcriptionText = ""
                                showAddManualTranscriptionDialog = false
                            }
                        }
                    ) {
                        Text("保存")
                    }
                },
                dismissButton = {
                    OutlinedButton(onClick = { showAddManualTranscriptionDialog = false }) {
                        Text("キャンセル")
                    }
                }
            )
        }
    }
}

@Composable
fun TranscriptionResultItem(
    result: TranscriptionResult,
    fontSize: Int,
    isSelected: Boolean,
    onItemClick: (TranscriptionResult) -> Unit,
    onToggleSelection: (String) -> Unit
) {
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        result.transcriptionStatus == "PENDING" -> MaterialTheme.colorScheme.surfaceVariant
        result.transcriptionStatus == "FAILED" -> MaterialTheme.colorScheme.errorContainer
        result.isSyncedWithGoogleTasks -> MaterialTheme.colorScheme.surfaceVariant // Synced items
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        result.transcriptionStatus == "PENDING" -> MaterialTheme.colorScheme.onSurfaceVariant
        result.transcriptionStatus == "FAILED" -> MaterialTheme.colorScheme.onErrorContainer
        result.isSyncedWithGoogleTasks -> MaterialTheme.colorScheme.onSurfaceVariant // Synced items
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .clickable { onItemClick(result) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggleSelection(result.fileName) },
            modifier = Modifier.padding(start = 8.dp)
        )

        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dateTimeInfo = FileUtil.getRecordingDateTimeInfo(result.fileName)
            Column(modifier = Modifier.padding(horizontal = 8.dp).width(80.dp)) {
                Text(text = dateTimeInfo.date, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = contentColor)
                Text(text = dateTimeInfo.time, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = contentColor)
            }
            Text(
                text = result.transcription,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 16.dp),
                color = contentColor
            )
        }
    }
}
