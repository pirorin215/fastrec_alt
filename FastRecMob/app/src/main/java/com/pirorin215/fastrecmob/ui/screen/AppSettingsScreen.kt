package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import com.pirorin215.fastrecmob.data.ThemeMode
import com.pirorin215.fastrecmob.viewModel.BleViewModel
import kotlin.math.roundToInt

import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsScreen(viewModel: BleViewModel, onBack: () -> Unit) {
    BackHandler(onBack = onBack)

    // DataStoreから現在の設定値を取得
    val currentApiKey by viewModel.apiKey.collectAsState()
    val currentInterval by viewModel.refreshIntervalSeconds.collectAsState()
    val currentTranscriptionCacheLimit by viewModel.transcriptionCacheLimit.collectAsState() // Renamed
    val currentFontSize by viewModel.transcriptionFontSize.collectAsState()
    val currentThemeMode by viewModel.themeMode.collectAsState()

    // TextFieldの状態を管理
    var apiKeyText by remember(currentApiKey) { mutableStateOf(currentApiKey) }
    var intervalText by remember(currentInterval) { mutableStateOf(currentInterval.toString()) }
    var transcriptionCacheLimitText by remember(currentTranscriptionCacheLimit) { mutableStateOf(currentTranscriptionCacheLimit.toString()) } // Renamed
    var fontSizeSliderValue by remember(currentFontSize) { mutableStateOf(currentFontSize.toFloat()) }
    var selectedThemeMode by remember(currentThemeMode) { mutableStateOf(currentThemeMode) }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("アプリ設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = apiKeyText,
                onValueChange = { apiKeyText = it },
                label = { Text("Google Cloud API Key") },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = intervalText,
                onValueChange = { intervalText = it },
                label = { Text("自動更新周期（秒）") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = transcriptionCacheLimitText, // Renamed
                onValueChange = { transcriptionCacheLimitText = it },
                label = { Text("文字起こし保持数") }, // Updated text
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            // Font size slider
            Text("文字起こしフォントサイズ: ${fontSizeSliderValue.roundToInt()} sp")
            Slider(
                value = fontSizeSliderValue,
                onValueChange = { fontSizeSliderValue = it },
                valueRange = 10f..24f,
                steps = 13, // (24 - 10) / 1 = 14 steps, so 13 intermediate points
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Theme mode selection
            Text("テーマモード", fontSize = 16.sp)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ThemeMode.values().forEach { themeMode ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = (themeMode == selectedThemeMode),
                            onClick = { selectedThemeMode = themeMode },
                            colors = RadioButtonDefaults.colors()
                        )
                        Text(themeMode.name, fontSize = 14.sp)
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    viewModel.saveApiKey(apiKeyText)
                    // 入力が不正な場合はデフォルト値30を使う
                    val interval = intervalText.toIntOrNull() ?: 30
                    viewModel.saveRefreshInterval(interval)
                    // 入力が不正な場合はデフォルト値100を使う
                    val transcriptionCacheLimit = transcriptionCacheLimitText.toIntOrNull() ?: 100 // Renamed
                    viewModel.saveTranscriptionCacheLimit(transcriptionCacheLimit) // Updated function call
                    viewModel.saveTranscriptionFontSize(fontSizeSliderValue.roundToInt())
                    viewModel.saveThemeMode(selectedThemeMode)
                    onBack() // 保存後に前の画面に戻る
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("保存")
            }
        }
    }
}
