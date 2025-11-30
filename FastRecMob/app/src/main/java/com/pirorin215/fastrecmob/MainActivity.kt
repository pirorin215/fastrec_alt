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
import kotlinx.coroutines.launch
import android.annotation.SuppressLint
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
}

@Composable
fun BleApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = (LocalContext.current as? ComponentActivity)

    val requiredPermissions = remember {
        val basePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        } else {
            listOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            basePermissions + Manifest.permission.WRITE_EXTERNAL_STORAGE
        } else {
            basePermissions
        }
    }


    var permissionsGranted by remember {
        mutableStateOf(requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
    }

    LaunchedEffect(Unit) {
        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions.toTypedArray())
        }
    }

    if (permissionsGranted) {
        BleControl()
    } else {
        Column(
            modifier = modifier
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text("このアプリの機能を利用するには、必要な権限を許可してください。")
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                permissionLauncher.launch(requiredPermissions.toTypedArray())
            }) {
                Text("権限を許可する")
            }
        }
    }
}

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
    val isInfoLoading by viewModel.isInfoLoading.collectAsState()
    val transferKbps by viewModel.transferKbps.collectAsState()
    val isAutoRefreshEnabled by viewModel.isAutoRefreshEnabled.collectAsState()

    var showLogs by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

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
                val isBusy = isInfoLoading || fileTransferState != "Idle"
                val connectionButtonText = if (connectionState == "Connected") "切断" else "スキャン"

                // 詳細表示ボタン
                Button(
                    onClick = { showDetails = !showDetails },
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy
                ) {
                    Text(if (showDetails) "詳細非表示" else "詳細表示")
                }

                // スキャン/切断ボタン
                Button(
                    onClick = {
                        if (connectionState == "Connected") viewModel.disconnect() else viewModel.startScan()
                    },
                    modifier = Modifier.weight(1f),
                    enabled = !isBusy // Changed this line to ensure it is not busy for any operation
                ) {
                    Text(connectionButtonText)
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
                        enabled = !isBusy && connectionState == "Connected"
                    )
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
                isInfoLoading = isInfoLoading,
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


@Composable
fun FileDownloadSection(
    fileList: List<com.pirorin215.fastrecmob.data.FileEntry>,
    fileTransferState: String,
    downloadProgress: Int,
    totalFileSize: Long,
    isInfoLoading: Boolean,
    transferKbps: Float,
    onDownloadClick: (String) -> Unit
) {
    val wavFiles = fileList.filter { it.name.endsWith(".wav", ignoreCase = true) }
    val logFiles = fileList.filter { it.name.startsWith("log.", ignoreCase = true) }
    val isBusy = isInfoLoading || fileTransferState != "Idle"

    Column {
        if (fileTransferState == "WaitingForStart" || fileTransferState == "Downloading") {
            val progress = if (totalFileSize > 0) downloadProgress.toFloat() / totalFileSize.toFloat() else 0f
            val percentage = (progress * 100).toInt()
            val statusText = if (fileTransferState == "WaitingForStart") "ハンドシェイク中..." else "ダウンロード中... $downloadProgress / $totalFileSize bytes ($percentage%) - ${"%.2f".format(transferKbps)} kbps"

            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                if (fileTransferState == "Downloading") {
                    LinearProgressIndicator(progress = progress, modifier = Modifier.fillMaxWidth())
                }
                Text(statusText)
            }
        }

        FileListCard(title = "WAV ファイル", files = wavFiles, onDownloadClick = onDownloadClick, fileTransferState = fileTransferState, isInfoLoading = isInfoLoading)
        Spacer(modifier = Modifier.height(8.dp))
        FileListCard(title = "ログファイル", files = logFiles, onDownloadClick = onDownloadClick, fileTransferState = fileTransferState, isInfoLoading = isInfoLoading)
    }
}

@Composable
fun FileListCard(title: String, files: List<com.pirorin215.fastrecmob.data.FileEntry>, onDownloadClick: (String) -> Unit, fileTransferState: String, isInfoLoading: Boolean) {
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
                                enabled = !isInfoLoading && fileTransferState == "Idle"
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
            @Suppress("UNCHECKED_CAST")
            return BleViewModel(application) as T
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