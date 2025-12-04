package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.pirorin215.fastrecmob.data.FileUtil
import com.pirorin215.fastrecmob.data.SortMode
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.viewModel.BleViewModel
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TranscriptionResultPanel(viewModel: BleViewModel, modifier: Modifier = Modifier) {
    val transcriptionResults by viewModel.transcriptionResults.collectAsState()
    val scope = rememberCoroutineScope()
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirmDialog by remember { mutableStateOf(false) }
    val fontSize by viewModel.transcriptionFontSize.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()
    val selectedFileNames by viewModel.selectedFileNames.collectAsState()
    val isSelectionMode = selectedFileNames.isNotEmpty()

    var selectedResultForDetail by remember { mutableStateOf<TranscriptionResult?>(null) }
    val audioDirName by viewModel.audioDirName.collectAsState()
    val context = LocalContext.current


    Card(shape = RoundedCornerShape(0.dp)) {
        Column() {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val transcriptionCount by viewModel.transcriptionCount.collectAsState()
                val audioFileCount by viewModel.audioFileCount.collectAsState()
                Text(
                    "メモ: $transcriptionCount 件, WAV: $audioFileCount 件",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )

                if (transcriptionResults.isNotEmpty()) { // Show icons if the list is not empty
                    if (isSelectionMode) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear Selection")
                        }
                    }
                    IconToggleButton(
                        checked = sortMode == SortMode.CUSTOM,
                        onCheckedChange = { isChecked ->
                            val newMode = if (isChecked) SortMode.CUSTOM else SortMode.TIMESTAMP
                            viewModel.saveSortMode(newMode)
                        }
                    ) {
                        if (sortMode == SortMode.CUSTOM) {
                            Icon(Icons.Default.SortByAlpha, contentDescription = "Custom Sort")
                        } else {
                            Icon(Icons.Default.AccessTime, contentDescription = "Sort by Time")
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
                }
            }

            if (transcriptionResults.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("文字起こし履歴はありません。")
                }
            } else {
                val reorderableState = rememberReorderableLazyListState(
                    onMove = { from, to ->
                        val reorderedList = transcriptionResults.toMutableList().apply {
                            add(to.index, removeAt(from.index))
                        }
                        viewModel.updateDisplayOrder(reorderedList)
                    }
                )
                val lazyListState = rememberLazyListState()

                LazyColumn(
                    state = if (sortMode == SortMode.CUSTOM) reorderableState.listState else lazyListState,
                    modifier = (if (sortMode == SortMode.CUSTOM) Modifier.reorderable(reorderableState) else Modifier)
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(items = transcriptionResults, key = { it.fileName }) { result ->
                        val isSelected = selectedFileNames.contains(result.fileName)
                        if (sortMode == SortMode.CUSTOM) {
                            ReorderableItem(reorderableState, key = result.fileName) { isDragging ->
                                val elevation = if (isDragging) 4.dp else 0.dp
                                Surface(shadowElevation = elevation) {
                                    TranscriptionResultItem(
                                        result = result,
                                        fontSize = fontSize,
                                        isSelected = isSelected,
                                        onItemClick = { clickedItem -> selectedResultForDetail = clickedItem },
                                        onToggleSelection = { fileName -> viewModel.toggleSelection(fileName) },
                                        reorderableModifier = Modifier.detectReorderAfterLongPress(reorderableState)
                                    )
                                }
                            }
                        } else {
                            TranscriptionResultItem(
                                result = result,
                                fontSize = fontSize,
                                isSelected = isSelected,
                                onItemClick = { clickedItem -> selectedResultForDetail = clickedItem },
                                onToggleSelection = { fileName -> viewModel.toggleSelection(fileName) }
                            )
                        }
                        Divider()
                    }
                }
            }
        }
    }

    selectedResultForDetail?.let { result ->
        val audioFile = remember(result.fileName, audioDirName) { FileUtil.getAudioFile(context, audioDirName, result.fileName) }
        val audioFileExists = remember(audioFile) { audioFile.exists() }

        TranscriptionDetailBottomSheet(
            result = result,
            fontSize = fontSize,
            audioFileExists = audioFileExists,
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
            onSave = { originalResult, newText ->
                scope.launch {
                    viewModel.updateTranscriptionResult(originalResult, newText)
                    selectedResultForDetail = null
                }
            },
            onDismiss = { selectedResultForDetail = null }
        )
    }

    if (showDeleteAllConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllConfirmDialog = false },
            title = { Text("履歴をクリア") },
            text = { Text("全ての文字起こし履歴を削除しますか？") },
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
            title = { Text("選択した履歴を削除") },
            text = { Text("${selectedFileNames.size} 件の文字起こし履歴を削除しますか？") },
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
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptionResultItem(
    result: TranscriptionResult,
    fontSize: Int,
    isSelected: Boolean,
    onItemClick: (TranscriptionResult) -> Unit,
    onToggleSelection: (String) -> Unit, // New lambda for checkbox
    reorderableModifier: Modifier = Modifier
) {
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .then(reorderableModifier), // Apply reorderableModifier here
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Always display the Checkbox on the left
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggleSelection(result.fileName) }
        )

        // Content area that is clickable
        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    onClick = { onItemClick(result) }
                )
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = FileUtil.extractRecordingDateTime(result.fileName),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = contentColor,
                modifier = Modifier.width(140.dp)
            )
            Spacer(modifier = Modifier.width(1.dp))
            Text(
                text = result.transcription,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

