package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun TranscriptionStatusDialog(
    transcriptionState: String,
    transcriptionResult: String?,
    onDismiss: () -> Unit
) {
    if (transcriptionState == "Idle") return

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                when {
                    transcriptionState == "Transcribing" -> "文字起こし中..."
                    transcriptionState == "Success" -> "文字起こし結果"
                    transcriptionState.startsWith("Error") -> "エラー"
                    else -> ""
                }
            )
        },
        text = {
            when {
                transcriptionState == "Transcribing" -> {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator()
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("wavファイルをテキストに変換しています...")
                    }
                }
                transcriptionState == "Success" -> {
                    val scrollState = rememberScrollState()
                    Column(modifier = Modifier.verticalScroll(scrollState).heightIn(max=400.dp)) {
                        Text(transcriptionResult ?: "結果がありません。")
                    }
                }
                transcriptionState.startsWith("Error") -> {
                    Text(transcriptionState.substringAfter("Error: "))
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}
