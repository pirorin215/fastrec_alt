package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    val fontSize by viewModel.transcriptionFontSize.collectAsState()
    val sortMode by viewModel.sortMode.collectAsState()

    var selectedResultForDetail by remember { mutableStateOf<TranscriptionResult?>(null) }
    val audioDirName by viewModel.audioDirName.collectAsState()
    val context = LocalContext.current

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(0.dp)
    ) {
        Column(modifier = Modifier.padding(0.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    val transcriptionCount by viewModel.transcriptionCount.collectAsState()
                    val audioFileCount by viewModel.audioFileCount.collectAsState()
                    Text(
                        "メモ: $transcriptionCount 件, 音声ファイル: $audioFileCount 件",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (transcriptionResults.isNotEmpty()) {
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
                    IconButton(onClick = { showDeleteAllConfirmDialog = true }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear All")
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
                    modifier = if (sortMode == SortMode.CUSTOM) Modifier.fillMaxWidth().reorderable(reorderableState) else Modifier.fillMaxWidth()
                ) {
                    items(items = transcriptionResults, key = { it.fileName }) { result ->
                        if (sortMode == SortMode.CUSTOM) {
                            ReorderableItem(reorderableState, key = result.fileName) { isDragging ->
                                val elevation = if (isDragging) 4.dp else 0.dp
                                Surface(shadowElevation = elevation) {
                                    TranscriptionResultItem(
                                        result = result,
                                        fontSize = fontSize,
                                        isDraggable = true,
                                        onItemClick = { clickedItem -> selectedResultForDetail = clickedItem },
                                        handleModifier = Modifier.detectReorderAfterLongPress(reorderableState)
                                    )
                                }
                            }
                        } else {
                            TranscriptionResultItem(
                                result = result,
                                fontSize = fontSize,
                                isDraggable = false,
                                onItemClick = { clickedItem -> selectedResultForDetail = clickedItem }
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
}

@Composable
fun TranscriptionResultItem(
    result: TranscriptionResult,
    fontSize: Int,
    isDraggable: Boolean,
    onItemClick: (TranscriptionResult) -> Unit,
    handleModifier: Modifier = Modifier
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isDraggable) {
            Icon(
                imageVector = Icons.Default.DragHandle,
                contentDescription = "Drag to reorder",
                modifier = handleModifier.padding(8.dp)
            )
        } else {
            Spacer(modifier = Modifier.width(24.dp + 16.dp)) // Icon size + padding
        }

        Row(
            modifier = Modifier
                .weight(1f)
                .clickable { onItemClick(result) }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = FileUtil.extractRecordingDateTime(result.fileName),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.width(140.dp)
            )
            Spacer(modifier = Modifier.width(1.dp))
            Text(
                text = result.transcription,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp),
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

