package com.pirorin215.fastrecmob

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import com.pirorin215.fastrecmob.ui.theme.FastRecMobTheme
import com.pirorin215.fastrecmob.viewModel.BleViewModel
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.pirorin215.fastrecmob.data.countWavFiles
import com.pirorin215.fastrecmob.data.parseFileEntries
import com.pirorin215.fastrecmob.ui.screen.SettingsScreen
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.content.Intent // Add this import
import android.net.Uri
import android.provider.Settings
import android.util.Log // Add this import
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository
import androidx.compose.foundation.shape.CircleShape

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FastRecMobTheme {
                BleApp(modifier = Modifier.fillMaxSize())
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // アプリが終了する際にサービスを停止する
        val serviceIntent = Intent(this, com.pirorin215.fastrecmob.service.BleScanService::class.java)
        stopService(serviceIntent)
    }
}

private const val TAG = "BleApp"

@Composable
fun BleApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = (LocalContext.current as? ComponentActivity)

    val requiredPermissions = remember {
        val basePermissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        // FOREGROUND_SERVICE and FOREGROUND_SERVICE_CONNECTED_DEVICE are not runtime permissions
        // that require explicit user consent via a dialog. They are declared in Manifest
        // and managed by the system in conjunction with startForegroundService.

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ requires POST_NOTIFICATIONS for foreground service notification
            basePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            basePermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        Log.d(TAG, "Required Permissions: ${basePermissions.joinToString()}")
        basePermissions
    }


    var permissionsGranted by remember { mutableStateOf(true) } // Temporarily set to true to bypass permission screen

    // Removed permissionLauncher and LaunchedEffect(context) for temporary bypass

    LaunchedEffect(permissionsGranted) { // permissionsGranted will always be true now
        if (permissionsGranted) {
            Log.d(TAG, "All permissions granted. Starting BleScanService.")
            val serviceIntent = Intent(context, com.pirorin215.fastrecmob.service.BleScanService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    // Always show BleControl as permissionsGranted is true
    BleControl()
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("MissingPermission")
@Composable
fun BleControl() {
    val context = LocalContext.current
    val viewModelFactory = BleViewModelFactory(context.applicationContext as Application)
    val viewModel: BleViewModel = viewModel(factory = viewModelFactory)

    val connectionState by viewModel.connectionState.collectAsState()
    val deviceInfo by viewModel.deviceInfo.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val fileList by viewModel.fileList.collectAsState()
    val fileTransferState by viewModel.fileTransferState.collectAsState()
    val downloadProgress by viewModel.downloadProgress.collectAsState()
    val currentFileTotalSize by viewModel.currentFileTotalSize.collectAsState()
    val currentOperation by viewModel.currentOperation.collectAsState()
    val transferKbps by viewModel.transferKbps.collectAsState()
    val isAutoRefreshEnabled by viewModel.isAutoRefreshEnabled.collectAsState()
    val transcriptionState by viewModel.transcriptionState.collectAsState()
    val transcriptionResult by viewModel.transcriptionResult.collectAsState()

    var showLogs by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var showTranscriptionResults by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(fileTransferState) {
        if (fileTransferState.startsWith("Success")) {
            scope.launch {
                snackbarHostState.showSnackbar("ファイル保存完了: ${fileTransferState.substringAfter("Success: ")}")
            }
        } else if (fileTransferState.startsWith("Error")) {
            scope.launch {
                snackbarHostState.showSnackbar("エラー: ${fileTransferState.substringAfter("Error: ")}")
            }
        }
    }

    if (transcriptionState == "Transcribing") { // 処理中のみ表示
        TranscriptionStatusDialog(
            transcriptionState = transcriptionState,
            transcriptionResult = transcriptionResult,
            onDismiss = { viewModel.resetTranscriptionState() }
        )
    }

    when {
        showSettings -> {
            SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
        }
        showAppSettings -> {
            // AppSettingsScreenのインポートが必要になる可能性
            com.pirorin215.fastrecmob.ui.screen.AppSettingsScreen(viewModel = viewModel, onBack = { showAppSettings = false })
        }
        showTranscriptionResults -> {
            com.pirorin215.fastrecmob.ui.screen.TranscriptionResultScreen(viewModel = viewModel, onBack = { showTranscriptionResults = false })
        }
        else -> {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
            ) { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(horizontal = 16.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ConnectionStatusIndicator(connectionState)

                    SummaryInfoCard(deviceInfo = deviceInfo)

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        val isBusy = currentOperation != BleViewModel.Operation.IDLE
                        val connectionButtonText = if (connectionState == "Connected") "切断" else "スキャン"

                        // スキャン/切断ボタン
                        Button(
                            onClick = {
                                if (connectionState == "Connected") viewModel.disconnect() else viewModel.startScan()
                            },
                            modifier = Modifier.weight(1f),
                            enabled = !isBusy
                        ) {
                            Text(connectionButtonText)
                        }

                        // 詳細表示ボタン
                        Button(
                            onClick = { showDetails = !showDetails },
                            modifier = Modifier.weight(1f),
                            enabled = currentOperation == BleViewModel.Operation.IDLE
                        ) {
                            Text(if (showDetails) "詳細非表示" else "詳細表示")
                        }

                        // 自動更新トグルスイッチ
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End
                        ) {
                            Text("自動更新", style = MaterialTheme.typography.labelMedium)
                            Switch(
                                checked = isAutoRefreshEnabled,
                                onCheckedChange = { viewModel.setAutoRefresh(it) },
                                enabled = currentOperation == BleViewModel.Operation.IDLE && connectionState == "Connected"
                            )
                        }
                    }

                    // 2行目: マイコン設定 | アプリ設定
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        // マイコン設定ボタン
                        Button(
                            onClick = { showSettings = true },
                            modifier = Modifier.weight(1f),
                            enabled = connectionState == "Connected"
                        ) {
                            Text("マイコン設定")
                        }

                        // アプリ設定ボタン
                        Button(
                            onClick = { showAppSettings = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("アプリ設定")
                        }
                    }

                    // 新しい行: 文字起こし履歴ボタン
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { showTranscriptionResults = true },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("文字起こし履歴")
                        }
                    }


                    if (showDetails) {
                        DetailedInfoCard(deviceInfo = deviceInfo)
                    }

                    FileDownloadSection(
                        fileList = fileList,
                        fileTransferState = fileTransferState,
                        downloadProgress = downloadProgress,
                        totalFileSize = currentFileTotalSize,
                        isBusy = currentOperation != BleViewModel.Operation.IDLE,
                        transferKbps = transferKbps,
                        onDownloadClick = { viewModel.downloadFile(it) }
                    )

                    Button(onClick = { showLogs = !showLogs }) {
                        Text(if (showLogs) "ログ非表示" else "ログ表示")
                    }

                    if (showLogs) {
                        Card(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                            LazyColumn(modifier = Modifier.padding(8.dp)) {
                                items(logs) { log ->
                                    Text(text = log, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}


@Composable
fun FileDownloadSection(
    fileList: List<com.pirorin215.fastrecmob.data.FileEntry>,
    fileTransferState: String,
    downloadProgress: Int,
    totalFileSize: Long,
    isBusy: Boolean,
    transferKbps: Float,
    onDownloadClick: (String) -> Unit
) {
    val wavFiles = fileList.filter { it.name.endsWith(".wav", ignoreCase = true) }
    val logFiles = fileList.filter { it.name.startsWith("log.", ignoreCase = true) }

    Column {
        if (fileTransferState == "WaitingForStart" || fileTransferState == "Downloading") {
            val progress = if (totalFileSize > 0) downloadProgress.toFloat() / totalFileSize.toFloat() else 0f
            val percentage = (progress * 100).toInt()
            val statusText = if (fileTransferState == "WaitingForStart") "接続中..." else "$downloadProgress / $totalFileSize bytes ($percentage%) - ${"%.2f".format(transferKbps)} kbps"

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (fileTransferState == "Downloading") {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                }
                Text(statusText)
            }
        }

        FileListCard(title = "WAV ファイル", files = wavFiles, onDownloadClick = onDownloadClick, isBusy = isBusy)
        Spacer(modifier = Modifier.height(8.dp))
        FileListCard(title = "ログファイル", files = logFiles, onDownloadClick = onDownloadClick, isBusy = isBusy)
    }
}

@Composable
fun FileListCard(title: String, files: List<com.pirorin215.fastrecmob.data.FileEntry>, onDownloadClick: (String) -> Unit, isBusy: Boolean) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Divider(modifier = Modifier.padding(vertical = 4.dp))

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
                            Button(
                                onClick = { onDownloadClick(file.name) },
                                enabled = !isBusy
                            ) {
                                Icon(Icons.Default.Download, contentDescription = "Download")
                            }
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("該当するファイルはありません。", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}


@Composable
fun ConnectionStatusIndicator(connectionState: String) {
    val statusColor = if (connectionState == "Connected") Color.Green else Color.Red
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .background(color = statusColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "FastRec Manager ($connectionState)",
                style = MaterialTheme.typography.titleLarge
            )
        }
    }
}

@Composable
fun SummaryInfoCard(deviceInfo: DeviceInfoResponse?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceAround // 均等配置
        ) {
            InfoItem(icon = getWifiIcon(deviceInfo?.wifiRssi ?: -100), label = deviceInfo?.connectedSsid ?: "-", value = "${deviceInfo?.wifiRssi ?: "-"}dBm")
            Spacer(Modifier.width(8.dp))
            InfoItem(icon = Icons.Default.BatteryChargingFull, label = "Battery", value = "${String.format("%.0f", deviceInfo?.batteryLevel ?: 0.0f)}%")
            Spacer(Modifier.width(8.dp))
            InfoItem(icon = Icons.Default.SdStorage, label = "Storage", value = "${deviceInfo?.littlefsUsagePercent ?: 0}%")
            Spacer(Modifier.width(8.dp))
            InfoItem(icon = Icons.Default.Audiotrack, label = "WAVs", value = countWavFiles(deviceInfo?.ls ?: "").toString())
        }
    }
}

@Composable
fun InfoItem(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(imageVector = icon, contentDescription = label, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Column {
            Text(text = label, style = MaterialTheme.typography.bodySmall)
            Text(text = value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}


@Composable
fun DetailedInfoCard(deviceInfo: DeviceInfoResponse?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "詳細情報", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.size(8.dp))
            InfoRow(label = "バッテリー電圧", value = "${String.format("%.2f", deviceInfo?.batteryVoltage ?: 0.0f)} V")
            InfoRow(label = "アプリ状態", value = deviceInfo?.appState ?: "-")
            InfoRow(label = "ストレージ使用量", value = "${deviceInfo?.littlefsUsedBytes ?: 0} bytes")
            InfoRow(label = "ストレージ総容量", value = "${(deviceInfo?.littlefsTotalBytes ?: 0) / 1024 / 1024} MB")

        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
    Divider()
}


class BleViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BleViewModel::class.java)) {
            val appSettingsRepository = AppSettingsRepository(application)
            val transcriptionResultRepository = TranscriptionResultRepository(application)
            @Suppress("UNCHECKED_CAST")
            return BleViewModel(appSettingsRepository, transcriptionResultRepository, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// Helper function to get the appropriate WiFi icon based on RSSI
fun getWifiIcon(rssi: Int): ImageVector {
    return when {
        rssi >= -50 -> Icons.Default.SignalWifi4Bar
        rssi >= -67 -> Icons.Default.NetworkWifi3Bar
        rssi >= -70 -> Icons.Default.NetworkWifi2Bar
        rssi >= -80 -> Icons.Default.NetworkWifi1Bar
        else -> Icons.Default.WifiOff
    }
}

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
