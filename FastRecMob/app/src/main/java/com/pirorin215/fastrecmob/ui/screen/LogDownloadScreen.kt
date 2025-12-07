package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pirorin215.fastrecmob.viewModel.BleViewModel
import com.pirorin215.fastrecmob.viewModel.BleOperation

import androidx.activity.compose.BackHandler

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogDownloadScreen(viewModel: BleViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    LaunchedEffect(Unit) {
        viewModel.fetchFileList("txt")
    }

    DisposableEffect(Unit) {
        onDispose {
            viewModel.fetchFileList("wav")
        }
    }

    val fileList by viewModel.fileList.collectAsState()
    val fileTransferState by viewModel.fileTransferState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val currentFileTotalSize by viewModel.currentFileTotalSize.collectAsState()
    val currentOperation by viewModel.currentOperation.collectAsState()
    val transferKbps by viewModel.transferKbps.collectAsState()

    val logFiles = fileList.filter { it.name.startsWith("log.", ignoreCase = true) }
    val isBusy = currentOperation != BleOperation.IDLE

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ログファイルダウンロード") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (fileTransferState == "WaitingForStart" || fileTransferState == "Downloading") {
                val progress = if (currentFileTotalSize > 0) downloadProgress.toFloat() / currentFileTotalSize.toFloat() else 0f
                val percentage = (progress * 100).toInt()
                val statusText = if (fileTransferState == "WaitingForStart") "接続中..." else "$downloadProgress / $currentFileTotalSize bytes ($percentage%) - ${"%.2f".format(transferKbps)} kbps"

                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    if (fileTransferState == "Downloading") {
                        LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                    }
                    Text(statusText)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            if (logFiles.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logFiles) { file ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(file.name, style = MaterialTheme.typography.titleMedium)
                                    Text("サイズ: ${file.size} bytes", style = MaterialTheme.typography.bodySmall)
                                }
                                Button(
                                    onClick = { viewModel.downloadFile(file.name) },
                                    enabled = !isBusy
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Download")
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("ダウンロード")
                                }
                            }
                        }
                    }
                }
            } else {
                Text(
                    text = "ログファイルはありません。",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
