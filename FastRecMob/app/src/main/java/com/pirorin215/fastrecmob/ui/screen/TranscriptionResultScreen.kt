package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pirorin215.fastrecmob.viewModel.BleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionResultScreen(viewModel: BleViewModel, onBack: () -> Unit) {
    val selectedFileNames by viewModel.selectedFileNames.collectAsState()
    val isSelectionMode = selectedFileNames.isNotEmpty()
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isSelectionMode) "${selectedFileNames.size} 件選択中" else "文字起こし履歴"
                    )
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Default.Close, contentDescription = "Cancel Selection")
                        }
                    } else {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete Selected Items")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        TranscriptionResultPanel(viewModel = viewModel, modifier = Modifier.padding(paddingValues))
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("選択した履歴を削除") },
            text = { Text("${selectedFileNames.size} 件の文字起こし履歴を削除しますか？") },
            confirmButton = {
                Button(onClick = {
                    viewModel.removeTranscriptionResults(selectedFileNames)
                    showDeleteConfirmDialog = false
                }) { Text("削除") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showDeleteConfirmDialog = false }) { Text("キャンセル") }
            }
        )
    }
}

