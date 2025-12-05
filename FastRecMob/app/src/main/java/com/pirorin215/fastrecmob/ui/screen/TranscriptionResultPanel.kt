package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.core.content.FileProvider
import com.pirorin215.fastrecmob.data.FileUtil
import com.pirorin215.fastrecmob.data.SortMode
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.viewModel.BleViewModel
import com.pirorin215.fastrecmob.viewModel.AppSettingsViewModel
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

@Composable
fun TranscriptionResultPanel(viewModel: BleViewModel, appSettingsViewModel: AppSettingsViewModel, modifier: Modifier = Modifier) {
    val transcriptionResults by viewModel.transcriptionResults.collectAsState()
    val scope = rememberCoroutineScope()
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedConfirmDialog by remember { mutableStateOf(false) }
    var showAddManualTranscriptionDialog by remember { mutableStateOf(false) }
    val fontSize by viewModel.transcriptionFontSize.collectAsState()
    val sortMode by appSettingsViewModel.sortMode.collectAsState()
    val selectedFileNames by viewModel.selectedFileNames.collectAsState()
    val isSelectionMode = selectedFileNames.isNotEmpty()

    var selectedResultForDetail by remember { mutableStateOf<TranscriptionResult?>(null) }
    val audioDirName by viewModel.audioDirName.collectAsState()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current

    val reorderableListStateRef = remember { mutableStateOf<LazyListState?>(null) }
    val lastDropToIndex = remember { mutableStateOf<Int?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        val reorderableState = rememberReorderableLazyListState(
            onMove = { from, to ->
                val reorderedList = transcriptionResults.toMutableList().apply {
                    add(to.index, removeAt(from.index))
                }
                viewModel.updateDisplayOrder(reorderedList)
                if (from.index != to.index) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                lastDropToIndex.value = to.index
            }
        )

        reorderableListStateRef.value = reorderableState.listState

        // Derive a state to know if any item is being dragged
        val isAnyItemDragging by remember {
            derivedStateOf { reorderableState.draggingItemKey != null }
        }

        LaunchedEffect(isAnyItemDragging) {
            if (!isAnyItemDragging && sortMode == SortMode.CUSTOM) { // Dragging has stopped (item dropped) and it's custom sort
                if (lastDropToIndex.value == 0) { // Only scroll if dropped at index 0
                    // Add a small delay to ensure list state is settled after reorder
                    kotlinx.coroutines.delay(100)
                    reorderableListStateRef.value?.animateScrollToItem(0)
                }
                lastDropToIndex.value = null // Reset after check
            }
        }

        // Swap zIndex based on drag state
        val listZIndex = if (isAnyItemDragging) 1f else 0f
        val panelZIndex = if (isAnyItemDragging) 0f else 1f

        if (transcriptionResults.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("no data")
            }
        } else {
            val lazyListState = rememberLazyListState()
            val listState = if (sortMode == SortMode.CUSTOM) reorderableListStateRef.value!! else lazyListState

            LaunchedEffect(sortMode) {
                scope.launch {
                    kotlinx.coroutines.delay(100) // Allow time for list to recompose with new sort order
                    listState.animateScrollToItem(0)
                }
            }

            // Scroll to top when the first item changes due to sorting by timestamp or creation time
            val firstItemKey = transcriptionResults.firstOrNull()?.fileName
            LaunchedEffect(firstItemKey) {
                if (sortMode == SortMode.TIMESTAMP || sortMode == SortMode.CREATION_TIME) {
                    if (firstItemKey != null) {
                        scope.launch {
                            kotlinx.coroutines.delay(100) // Allow time for list to recompose
                            listState.animateScrollToItem(0)
                        }
                    }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .zIndex(listZIndex) // Apply dynamic zIndex
                    .fillMaxSize()
                    .then(
                        if (sortMode == SortMode.CUSTOM) Modifier.reorderable(reorderableState) else Modifier
                    ),
                contentPadding = PaddingValues(top = 72.dp, bottom = 16.dp)
            ) {
                items(items = transcriptionResults, key = { it.fileName }) { result ->
                    val isSelected = selectedFileNames.contains(result.fileName)
                    if (sortMode == SortMode.CUSTOM) {
                        ReorderableItem(reorderableState, key = result.fileName) { isDragging ->
                            val elevation = if (isDragging) 12.dp else 0.dp
                            LaunchedEffect(isDragging) {
                                if (isDragging) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            Surface(shadowElevation = elevation) {
                                TranscriptionResultItem(
                                    result = result,
                                    fontSize = fontSize,
                                    isSelected = isSelected,
                                    isDragging = isDragging,
                                    onItemClick = { clickedItem -> selectedResultForDetail = clickedItem },
                                    onToggleSelection = { fileName -> viewModel.toggleSelection(fileName) },
                                    sortMode = sortMode,
                                    reorderableModifier = Modifier.detectReorderAfterLongPress(reorderableState)
                                )
                            }
                        }
                    } else {
                        TranscriptionResultItem(
                            result = result,
                            fontSize = fontSize,
                            isSelected = isSelected,
                            isDragging = false,
                            onItemClick = { clickedItem -> selectedResultForDetail = clickedItem },
                            onToggleSelection = { fileName -> viewModel.toggleSelection(fileName) },
                            sortMode = sortMode
                        )
                    }
                    Divider()
                }
            }
        }

        // Control Panel floating on top
        Card(
            modifier = Modifier
                .zIndex(panelZIndex) // Apply dynamic zIndex
                .align(Alignment.TopCenter)
                .padding(8.dp)
                .alpha(if (isAnyItemDragging) 0.3f else 1.0f),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
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

                IconButton(onClick = { showAddManualTranscriptionDialog = true }) {
                    Icon(Icons.Filled.Add, "手動で文字起こしを追加")
                }

                IconButton(onClick = { viewModel.clearSelection() }, enabled = isSelectionMode) {
                    Icon(Icons.Default.Close, contentDescription = "Clear Selection")
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
                            showSortModeMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                        trailingIcon = { if (sortMode == SortMode.TIMESTAMP) Icon(Icons.Default.Check, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("作成時刻順") },
                        onClick = {
                            appSettingsViewModel.saveSortMode(SortMode.CREATION_TIME)
                            showSortModeMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.Schedule, contentDescription = null) },
                        trailingIcon = { if (sortMode == SortMode.CREATION_TIME) Icon(Icons.Default.Check, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text("カスタム順") },
                        onClick = {
                            appSettingsViewModel.saveSortMode(SortMode.CUSTOM)
                            showSortModeMenu = false
                        },
                        leadingIcon = { Icon(Icons.Default.SwapVert, contentDescription = null) },
                        trailingIcon = { if (sortMode == SortMode.CUSTOM) Icon(Icons.Default.Check, contentDescription = null) }
                    )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TranscriptionResultItem(
    result: TranscriptionResult,
    fontSize: Int,
    isSelected: Boolean,
    isDragging: Boolean,
    onItemClick: (TranscriptionResult) -> Unit,
    onToggleSelection: (String) -> Unit,
    sortMode: SortMode,
    reorderableModifier: Modifier = Modifier
) {
    val backgroundColor = when {
        isDragging -> MaterialTheme.colorScheme.tertiaryContainer
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    val contentColor = when {
        isDragging -> MaterialTheme.colorScheme.onTertiaryContainer
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .then(reorderableModifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = isSelected,
            onCheckedChange = { onToggleSelection(result.fileName) }
        )

        Row(
            modifier = Modifier
                .weight(1f)
                .clickable(
                    onClick = { onItemClick(result) }
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (sortMode == SortMode.TIMESTAMP || sortMode == SortMode.CREATION_TIME) {
                val dateTimeInfo = FileUtil.getRecordingDateTimeInfo(result.fileName)
                Column(modifier = Modifier.width(80.dp)) {
                    Text(
                        text = dateTimeInfo.date,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = contentColor
                    )
                    Text(
                        text = dateTimeInfo.time,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = contentColor
                    )
                }
                Spacer(modifier = Modifier.width(1.dp))
            }
            Text(
                text = result.transcription,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = fontSize.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                color = contentColor
            )
        }
    }
}