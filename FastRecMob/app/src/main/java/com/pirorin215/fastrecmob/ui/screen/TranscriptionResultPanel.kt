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
import android.content.Intent // Add this import
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.pirorin215.fastrecmob.data.FileUtil
import com.pirorin215.fastrecmob.data.SortMode
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.viewModel.MainViewModel
import com.pirorin215.fastrecmob.viewModel.AppSettingsViewModel
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptionResultPanel(viewModel: MainViewModel, appSettingsViewModel: AppSettingsViewModel, modifier: Modifier = Modifier) {
    val transcriptionResults by viewModel.transcriptionResults.collectAsState()
    val scope = rememberCoroutineScope()
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirmDialog by remember { mutableStateOf(false) }
    var showAddManualTranscriptionDialog by remember { mutableStateOf(false) }
    val fontSize by viewModel.transcriptionFontSize.collectAsState()
    val sortMode by appSettingsViewModel.sortMode.collectAsState()
    val selectedFileNames by viewModel.selectedFileNames.collectAsState()

    var selectedResultForDetail by remember { mutableStateOf<TranscriptionResult?>(null) }
    val audioDirName by viewModel.audioDirName.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val localItems = remember { mutableStateListOf<TranscriptionResult>() }
    val originalOrder = remember { mutableStateOf<List<TranscriptionResult>>(emptyList()) }

    LaunchedEffect(transcriptionResults) {
        if (localItems.toList() != transcriptionResults) {
            localItems.clear()
            localItems.addAll(transcriptionResults)
            if (sortMode == SortMode.CUSTOM) {
                originalOrder.value = transcriptionResults
            }
        }
    }

    val reorderableState = rememberReorderableLazyListState(
        onMove = { from, to ->
            localItems.add(to.index, localItems.removeAt(from.index))
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    )

    val isDragging by remember { derivedStateOf { reorderableState.draggingItemKey != null } }

    LaunchedEffect(isDragging) {
        if (isDragging) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
        if (!isDragging && sortMode == SortMode.CUSTOM) {
            if (localItems.toList() != originalOrder.value) {
                viewModel.updateDisplayOrder(localItems.toList())
            }
        }
    }

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
                        .fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isSelectionMode = selectedFileNames.isNotEmpty()
                    val transcriptionCount by viewModel.transcriptionCount.collectAsState()
                    val audioFileCount by viewModel.audioFileCount.collectAsState()

                    Text(
                        "メモ: $transcriptionCount 件, WAV: $audioFileCount 件",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )

                    if (isSelectionMode) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                        }
                    }
                    
                    IconButton(onClick = {
                        if (isSelectionMode) {
                            showDeleteSelectedConfirmDialog = true
                        } else {
                            showDeleteAllConfirmDialog = true
                        }
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = if (isSelectionMode) "Delete Selected" else "Clear All")
                    }
                    
                    var showSortModeMenu by remember { mutableStateOf(false) }
                    IconButton(onClick = { showSortModeMenu = true }) {
                        val icon = when (sortMode) {
                            SortMode.CUSTOM -> Icons.Default.SwapVert
                            SortMode.TIMESTAMP -> Icons.Default.History
                            SortMode.CREATION_TIME -> Icons.Default.Schedule
                        }
                        Icon(icon, contentDescription = "Sort Mode")
                    }
                    DropdownMenu(
                        expanded = showSortModeMenu,
                        onDismissRequest = { showSortModeMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("編集時刻順") },
                            onClick = {
                                appSettingsViewModel.saveSortMode(SortMode.TIMESTAMP)
                                scope.launch {
                                    reorderableState.listState.animateScrollToItem(0)
                                }
                                showSortModeMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                            trailingIcon = { if (sortMode == SortMode.TIMESTAMP) Icon(Icons.Default.Check, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("作成時刻順") },
                            onClick = {
                                appSettingsViewModel.saveSortMode(SortMode.CREATION_TIME)
                                scope.launch {
                                    reorderableState.listState.animateScrollToItem(0)
                                }
                                showSortModeMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) },
                            trailingIcon = { if (sortMode == SortMode.CREATION_TIME) Icon(Icons.Default.Check, contentDescription = null) }
                        )
                        DropdownMenuItem(
                            text = { Text("カスタム順") },
                            onClick = {
                                appSettingsViewModel.saveSortMode(SortMode.CUSTOM)
                                scope.launch {
                                    reorderableState.listState.animateScrollToItem(0)
                                }
                                showSortModeMenu = false
                            },
                            leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null) },
                            trailingIcon = { if (sortMode == SortMode.CUSTOM) Icon(Icons.Default.Check, contentDescription = null) }
                        )
                    }
                    
                    IconButton(onClick = { showAddManualTranscriptionDialog = true }) {
                        Icon(Icons.Filled.Add, "手動で文字起こしを追加")
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp)) // Add this Spacer for separation
            if (localItems.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("no data")
                }
            } else {
                LazyColumn(
                    state = reorderableState.listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (sortMode == SortMode.CUSTOM) {
                                Modifier
                                    .reorderable(reorderableState)
                                    .detectReorderAfterLongPress(reorderableState)
                            } else {
                                Modifier
                            }
                        )
                ) {
                    items(items = localItems, key = { it.fileName }) { result ->
                        ReorderableItem(reorderableState, key = result.fileName) { isDragging ->
                            val elevation = if (isDragging) 12.dp else 0.dp
                            Surface(shadowElevation = elevation) {
                                TranscriptionResultItem(
                                    result = result,
                                    fontSize = fontSize,
                                    isSelected = selectedFileNames.contains(result.fileName),
                                    isDragging = isDragging,
                                    onItemClick = { clickedItem -> if (!isDragging) selectedResultForDetail = clickedItem },
                                    onToggleSelection = { fileName -> viewModel.toggleSelection(fileName) },
                                    sortMode = sortMode
                                )
                            }
                        }
                        Divider()
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
                onPlay = { transcriptionResult ->
                    val fileToPlay = FileUtil.getAudioFile(context, audioDirName, transcriptionResult.fileName)
                    if (fileToPlay.exists()) {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            val uri = FileProvider.getUriForFile(context, "com.pirorin215.fastrecmob.provider", fileToPlay)
                            setDataAndType(uri, "audio/wav")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        try {
                            context.startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                },
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
    isDragging: Boolean,
    onItemClick: (TranscriptionResult) -> Unit,
    onToggleSelection: (String) -> Unit,
    sortMode: SortMode
) {
    val backgroundColor = when {
        isDragging -> MaterialTheme.colorScheme.tertiaryContainer
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        result.transcriptionStatus == "PENDING" -> MaterialTheme.colorScheme.surfaceVariant // PENDING 状態の項目を灰色にする
        result.transcriptionStatus == "FAILED" -> MaterialTheme.colorScheme.errorContainer // FAILED 状態の項目を薄い赤にする
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        isDragging -> MaterialTheme.colorScheme.onTertiaryContainer
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        result.transcriptionStatus == "PENDING" -> MaterialTheme.colorScheme.onSurfaceVariant // PENDING 状態のテキスト色
        result.transcriptionStatus == "FAILED" -> MaterialTheme.colorScheme.onErrorContainer // FAILED 状態のテキスト色
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
            if (sortMode == SortMode.TIMESTAMP || sortMode == SortMode.CREATION_TIME) {
                val dateTimeInfo = FileUtil.getRecordingDateTimeInfo(result.fileName)
                Column(modifier = Modifier.padding(horizontal = 8.dp).width(80.dp)) {
                    Text(text = dateTimeInfo.date, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = contentColor)
                    Text(text = dateTimeInfo.time, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = contentColor)
                }
            } else {
                Spacer(modifier = Modifier.width(16.dp))
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
