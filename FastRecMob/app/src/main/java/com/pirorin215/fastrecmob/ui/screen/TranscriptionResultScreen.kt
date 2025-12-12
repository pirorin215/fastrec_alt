package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import android.content.Intent // Add this import
import com.pirorin215.fastrecmob.viewModel.MainViewModel
import com.pirorin215.fastrecmob.viewModel.AppSettingsViewModel // Add this import

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionResultScreen(viewModel: MainViewModel, appSettingsViewModel: AppSettingsViewModel, onBack: () -> Unit) {
    val selectedFileNames by viewModel.selectedFileNames.collectAsState()
    val isSelectionMode = selectedFileNames.isNotEmpty()
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }


    TranscriptionResultPanel(viewModel = viewModel, appSettingsViewModel = appSettingsViewModel, modifier = Modifier)

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

