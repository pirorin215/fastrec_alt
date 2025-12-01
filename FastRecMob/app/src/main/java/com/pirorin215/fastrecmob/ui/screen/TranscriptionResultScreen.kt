package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.viewModel.BleViewModel
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*
import com.pirorin215.fastrecmob.data.FileUtil
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.filled.Close // 追加
import androidx.compose.runtime.snapshots.SnapshotStateList // 追加
import androidx.compose.foundation.background // 追加
import androidx.compose.foundation.ExperimentalFoundationApi // 追加
import androidx.compose.foundation.combinedClickable // 追加


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class) // 追加
@Composable
fun TranscriptionResultScreen(viewModel: BleViewModel, onBack: () -> Unit) {
    val transcriptionResults by viewModel.transcriptionResults.collectAsState()
    val scope = rememberCoroutineScope()
    var showDeleteAllConfirmDialog by remember { mutableStateOf(false) }

    // 全文表示用の状態
    var showFullTextDialog by remember { mutableStateOf(false) }
    var fullTextToShow by remember { mutableStateOf("") }
    var fullTextFileName by remember { mutableStateOf("") }

    // 新しい状態変数
    var isSelectionMode by remember { mutableStateOf(false) }
    val selectedItems = remember { mutableStateListOf<TranscriptionResult>() } // 選択されたアイテムのリスト

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文字起こし履歴") },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { // 選択解除ボタン
                            isSelectionMode = false
                            selectedItems.clear()
                        }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode && selectedItems.isNotEmpty()) { // 選択モードでアイテムが選択されていればゴミ箱
                        IconButton(onClick = { showDeleteAllConfirmDialog = true }) { // これは複数削除確認ダイアログとして流用
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected")
                        }
                    } else if (!isSelectionMode && transcriptionResults.isNotEmpty()) { // 通常モードで履歴があれば全て削除
                        IconButton(onClick = { showDeleteAllConfirmDialog = true }) { // これは全て削除確認ダイアログとして流用
                            Icon(Icons.Default.Delete, contentDescription = "Clear All")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (transcriptionResults.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(paddingValues), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("文字起こし履歴はありません。")
            }
        } else {
            LazyColumn(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
                // keyを設定してパフォーマンスと安定性を確保
                items(items = transcriptionResults.sortedByDescending { it.timestamp }, key = { it.timestamp }) { result ->
                    val isSelected = selectedItems.contains(result)
                    TranscriptionResultItem(
                        result = result,
                        isSelectionMode = isSelectionMode,
                        isSelected = isSelected,
                        onItemClick = { clickedItem ->
                            if (isSelectionMode) {
                                if (selectedItems.contains(clickedItem)) {
                                    selectedItems.remove(clickedItem)
                                } else {
                                    selectedItems.add(clickedItem)
                                }
                                if (selectedItems.isEmpty()) {
                                    isSelectionMode = false
                                }
                            } else {
                                // 通常モードではタップで全文表示
                                fullTextToShow = clickedItem.transcription
                                fullTextFileName = clickedItem.fileName
                                showFullTextDialog = true
                            }
                        },
                        onItemLongClick = { clickedItem ->
                            isSelectionMode = true
                            if (selectedItems.contains(clickedItem)) {
                                selectedItems.remove(clickedItem)
                            } else {
                                selectedItems.add(clickedItem)
                            }
                        }
                    )
                    Divider()
                }
            }
        }
    }

    // 全文表示ダイアログ
    if (showFullTextDialog) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val audioFile = remember(fullTextFileName) { FileUtil.getAudioFile(fullTextFileName) }
        val audioFileExists = remember(audioFile) { audioFile.exists() }

        AlertDialog(
            onDismissRequest = { showFullTextDialog = false },
            title = { Text(FileUtil.extractRecordingDateTime(fullTextFileName)) },
            text = {
                val scrollState = rememberScrollState()
                Column(modifier = Modifier.verticalScroll(scrollState).heightIn(max=400.dp)) {
                    Text(fullTextToShow)
                }
            },
            confirmButton = {
                Row {
                    if (audioFileExists) {
                        TextButton(onClick = {
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "com.pirorin215.fastrecmob.provider",
                                    audioFile
                                )
                                setDataAndType(uri, "audio/wav")
                                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            try {
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // Handle case where no app can play WAV files
                                e.printStackTrace()
                            }
                        }) {
                            Text("再生")
                        }
                    }
                    Button(onClick = { showFullTextDialog = false }) {
                        Text("閉じる")
                    }
                }
            }
        )
    }

    // 複数削除/全て削除確認ダイアログ
    if (showDeleteAllConfirmDialog) {
        val dialogTitle = if (isSelectionMode) "選択項目を削除" else "履歴をクリア"
        val dialogText = if (isSelectionMode) "${selectedItems.size}件の項目を削除しますか？" else "全ての文字起こし履歴を削除しますか？"

        AlertDialog(
            onDismissRequest = { showDeleteAllConfirmDialog = false },
            title = { Text(dialogTitle) },
            text = { Text(dialogText) },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        if (isSelectionMode) {
                            selectedItems.forEach { viewModel.removeTranscriptionResult(it) }
                            selectedItems.clear()
                            isSelectionMode = false
                        } else {
                            viewModel.clearTranscriptionResults()
                        }
                        showDeleteAllConfirmDialog = false
                    }
                }) {
                    Text("削除")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteAllConfirmDialog = false }) {
                    Text("キャンセル")
                }
            }
        )
    }
}

@Composable
fun TranscriptionResultItem(
    result: TranscriptionResult,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onItemClick: (TranscriptionResult) -> Unit,
    onItemLongClick: (TranscriptionResult) -> Unit
) {
    val backgroundColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .combinedClickable(
                onClick = { onItemClick(result) },
                onLongClick = if (!isSelectionMode) { { onItemLongClick(result) } } else { null } // 選択モードでなければ長押し可能
            )
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ColumnではなくRowで横並びにする
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            // 録音日時を表示 (固定幅にするか、weightで比率調整するか検討)
            Text(
                text = FileUtil.extractRecordingDateTime(result.fileName),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Normal,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis, // エリプシスで省略
                modifier = Modifier.width(160.dp) // 日時表示の幅を固定
            )
            Spacer(modifier = Modifier.width(8.dp)) // 日時と文章の間のスペース
            // 文字起こし冒頭の文章 (残り幅を占有し、見切れる)
            Text(
                text = result.transcription, // 文字数制限を削除
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Clip, // 見切れるように
                color = contentColor,
                modifier = Modifier.weight(1f) // 残り幅を占有
            )
        }
    }
}
