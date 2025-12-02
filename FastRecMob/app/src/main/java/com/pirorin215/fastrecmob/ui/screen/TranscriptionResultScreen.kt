package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import com.pirorin215.fastrecmob.viewModel.BleViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionResultScreen(viewModel: BleViewModel, onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文字起こし履歴") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        TranscriptionResultPanel(viewModel = viewModel, modifier = Modifier.padding(paddingValues))
    }
}
