package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pirorin215.fastrecmob.data.FileEntry

@Composable
fun FileDownloadSection(
    fileList: List<FileEntry>,
    fileTransferState: String,
    downloadProgress: Int,
    totalFileSize: Long,
    isBusy: Boolean,
    transferKbps: Float,
    onDownloadClick: (String) -> Unit
) {
    val wavFiles = fileList.filter { it.name.endsWith(".wav", ignoreCase = true) }
    // logFiles filter was unused in original code, kept for consistency if needed later
    // val logFiles = fileList.filter { it.name.startsWith("log.", ignoreCase = true) }

    Column {
        if (fileTransferState == "WaitingForStart" || fileTransferState == "Downloading") {
            val progress = if (totalFileSize > 0) downloadProgress.toFloat() / totalFileSize.toFloat() else 0f
            val percentage = (progress * 100).toInt()
            val statusText = if (fileTransferState == "WaitingForStart") "接続中..." else "$downloadProgress / $totalFileSize bytes ($percentage%) - ${"%.2f".format(transferKbps)} kbps"

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (fileTransferState == "Downloading") {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                }
                Text(statusText)
            }
        }

        if (wavFiles.isNotEmpty()) {
            FileListCard(title = "WAV ファイル", files = wavFiles, onDownloadClick = onDownloadClick, isBusy = isBusy, showDownloadButton = false) // No download button for WAVs
        }

    }
}

@Composable
fun FileListCard(title: String, files: List<FileEntry>, onDownloadClick: (String) -> Unit, isBusy: Boolean, showDownloadButton: Boolean = true) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

            if (files.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 150.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(files) { file ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("${file.name} (${file.size})", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            if (showDownloadButton) { // Conditionally show the button
                                Button(
                                    onClick = { onDownloadClick(file.name) },
                                    enabled = !isBusy
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = "Download")
                                }
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp), // Reduced padding
                    contentAlignment = Alignment.Center
                ) {
                    Text("該当するファイルはありません。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
