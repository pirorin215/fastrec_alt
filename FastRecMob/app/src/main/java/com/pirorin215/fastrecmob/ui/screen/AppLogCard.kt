package com.pirorin215.fastrecmob.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun AppLogCard(logs: List<String>, onDismiss: () -> Unit, onClearLogs: () -> Unit) {
    val context = LocalContext.current
    val clipboardManager = context.getSystemService(ClipboardManager::class.java) // Use getSystemService
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("アプリログ", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(
                    onClick = { onClearLogs() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                }
                Spacer(modifier = Modifier.width(20.dp))
                IconButton(
                    onClick = {
                        val logText = logs.joinToString("\n")
                        val clip = ClipData.newPlainText("App Logs", logText)
                        clipboardManager.setPrimaryClip(clip)
                    },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy all logs")
                }
                Spacer(modifier = Modifier.width(20.dp))
                IconButton(
                    onClick = { onDismiss() },
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close log panel")
                }
            }
            Spacer(modifier = Modifier.size(8.dp))
            val lazyListState = rememberLazyListState()
            SelectionContainer {
                Card(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp),
                        state = lazyListState
                    ) {
                        items(logs) { log ->
                            Text(text = log, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            LaunchedEffect(logs.size) {
                if (logs.isNotEmpty()) {
                    lazyListState.animateScrollToItem(logs.size - 1)
                }
            }
        }
    }
    SnackbarHost(hostState = snackbarHostState, modifier = Modifier.wrapContentHeight(Alignment.Bottom))
}
