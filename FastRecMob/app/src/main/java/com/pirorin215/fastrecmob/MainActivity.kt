package com.pirorin215.fastrecmob

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast // For Toast message
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString // For AnnotatedString

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
import com.pirorin215.fastrecmob.viewModel.AppSettingsViewModel
import com.pirorin215.fastrecmob.viewModel.AppSettingsViewModelFactory
import com.pirorin215.fastrecmob.viewModel.ApiKeyStatus
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.pirorin215.fastrecmob.data.parseFileEntries
import com.pirorin215.fastrecmob.ui.screen.SettingsScreen
import com.pirorin215.fastrecmob.ui.screen.LogDownloadScreen
import com.pirorin215.fastrecmob.ui.screen.TranscriptionResultPanel
import com.pirorin215.fastrecmob.ui.screen.LastKnownLocationScreen
import com.pirorin215.fastrecmob.ui.screen.TodoScreen

import kotlinx.coroutines.launch
import android.annotation.SuppressLint
import android.content.Intent // Add this import
import android.net.Uri
import android.provider.Settings
import android.util.Log // Add this import
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import com.pirorin215.fastrecmob.data.ThemeMode
import androidx.compose.foundation.shape.CircleShape
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer

import com.pirorin215.fastrecmob.viewModel.TodoViewModel
import com.pirorin215.fastrecmob.viewModel.TodoViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val appSettingsRepository = AppSettingsRepository(context.applicationContext as Application)
            val lastKnownLocationRepository = LastKnownLocationRepository(context.applicationContext as Application)
            val viewModelFactory = BleViewModelFactory(appSettingsRepository, lastKnownLocationRepository, context.applicationContext as Application)
            val viewModel: BleViewModel = viewModel(factory = viewModelFactory)

            val appSettingsViewModelFactory = AppSettingsViewModelFactory(context.applicationContext as Application, appSettingsRepository)
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = appSettingsViewModelFactory)

            val todoViewModelFactory = TodoViewModelFactory(context.applicationContext as Application, appSettingsRepository)
            val todoViewModel: TodoViewModel = viewModel(factory = todoViewModelFactory)

            val themeMode by viewModel.themeMode.collectAsState()

            FastRecMobTheme(themeMode = themeMode) {
                BleApp(modifier = Modifier.fillMaxSize(), appSettingsViewModel = appSettingsViewModel, todoViewModel = todoViewModel)
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
fun BleApp(modifier: Modifier = Modifier, appSettingsViewModel: AppSettingsViewModel, todoViewModel: TodoViewModel) {
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
    BleControl(appSettingsViewModel = appSettingsViewModel, todoViewModel = todoViewModel)
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.material.ExperimentalMaterialApi::class)
@SuppressLint("MissingPermission")
@Composable
fun BleControl(appSettingsViewModel: AppSettingsViewModel, todoViewModel: TodoViewModel) {
    val context = LocalContext.current
    val viewModel: BleViewModel = viewModel() // ViewModel is already created and provided by compositionLocal in MainActivity's setContent
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
    var showSettings by remember { mutableStateOf(false) }
    var showAppSettings by remember { mutableStateOf(false) }
    var showTodoScreen by remember { mutableStateOf(false) }
    var showTodoDetailScreen by remember { mutableStateOf<String?>(null) } // New state for TodoDetailScreen visibility and todoId

    var showLogDownloadScreen by remember { mutableStateOf(false) }
    var showLastKnownLocationScreen by remember { mutableStateOf(false) } // New state for LastKnownLocationScreen visibility
    var showAppLogPanel by remember { mutableStateOf(false) } // New state for AppLogCard visibility


    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val isRefreshing = currentOperation == BleViewModel.Operation.FETCHING_DEVICE_INFO
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { viewModel.fetchFileList() }
    )

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

    // Observe lifecycle events to start/stop low power location updates
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                Log.d(TAG, "ON_RESUME: Starting low power location updates.")
                viewModel.startLowPowerLocationUpdates()
            } else if (event == Lifecycle.Event.ON_PAUSE) {
                Log.d(TAG, "ON_PAUSE: Stopping low power location updates.")
                viewModel.stopLowPowerLocationUpdates()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    when {
        showSettings -> {
            SettingsScreen(viewModel = viewModel, onBack = { showSettings = false })
        }
        showAppSettings -> {
            // AppSettingsScreenのインポートが必要になる可能性
            com.pirorin215.fastrecmob.ui.screen.AppSettingsScreen(appSettingsViewModel = appSettingsViewModel, onBack = { showAppSettings = false })
        }

        showLogDownloadScreen -> {
            LogDownloadScreen(viewModel = viewModel, onBack = { showLogDownloadScreen = false })
        }
        showLastKnownLocationScreen -> {
            LastKnownLocationScreen(onBack = { showLastKnownLocationScreen = false })
        }
        showTodoScreen -> {
            TodoScreen(
                todoViewModel = todoViewModel,
                onBack = { showTodoScreen = false }
            )
        }
                else -> {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
                topBar = {
                    TopAppBar(
                        title = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("FastRec")
                                Spacer(modifier = Modifier.width(8.dp))
                                val statusColor = if (connectionState == "Connected") Color.Green else Color.Red
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(color = statusColor, shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = if (connectionState == "Connected") "（接続中）" else "（未接続）",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                IconButton(
                                    onClick = {
                                        showLastKnownLocationScreen = true
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.LocationOn, contentDescription = "Show Last Known Location")
                                }
                                Spacer(modifier = Modifier.width(8.dp)) // Add this line for spacing
                                IconButton(
                                    onClick = {
                                        viewModel.forceReconnectBle()
                                    },
                                    modifier = Modifier.size(24.dp)
                                ) {
                                    Icon(Icons.Default.Autorenew, contentDescription = "Force Reconnect BLE")
                                }
                            }
                        },
                        actions = {
                            var expanded by remember { mutableStateOf(false) }
                            IconButton(onClick = { expanded = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "More options")
                            }
                            DropdownMenu(
                                expanded = expanded,
                                onDismissRequest = { expanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("マイコン設定") },
                                    onClick = {
                                        showSettings = true
                                        expanded = false
                                    },
                                    enabled = connectionState == "Connected"
                                )
                                DropdownMenuItem(
                                    text = { Text("アプリ設定") },
                                    onClick = {
                                        showAppSettings = true
                                        expanded = false
                                    }
                                )

                                DropdownMenuItem(
                                    text = { Text("ログファイルダウンロード") },
                                    onClick = {
                                        showLogDownloadScreen = true
                                        expanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("アプリログ") },
                                    onClick = {
                                        showAppLogPanel = !showAppLogPanel // Toggle visibility
                                        expanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Todo リスト") },
                                    onClick = {
                                        showTodoScreen = true
                                        expanded = false
                                    }
                                )

                            }
                        }
                    )
                }
            ) { innerPadding ->
                val apiKeyStatus by appSettingsViewModel.apiKeyStatus.collectAsState()

                Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(1.dp)
                    ) {
                        ApiKeyWarningCard(
                            apiKeyStatus = apiKeyStatus,
                            onNavigateToSettings = { showAppSettings = true }
                        )
                        SummaryInfoCard(deviceInfo = deviceInfo)
                        // Move FileDownloadSection above TranscriptionResultPanel
                        FileDownloadSection(
                            fileList = fileList,
                            fileTransferState = fileTransferState,
                            downloadProgress = downloadProgress,
                            totalFileSize = currentFileTotalSize,
                            isBusy = currentOperation != BleViewModel.Operation.IDLE,
                            transferKbps = transferKbps,
                            onDownloadClick = { viewModel.downloadFile(it) }
                        )
                        // TranscriptionResultPanel now takes flexible space
                        TranscriptionResultPanel(viewModel = viewModel, appSettingsViewModel = appSettingsViewModel, modifier = Modifier.weight(1f))
                    }
                    // AppLogCard as an overlay at the bottom
                    if (showAppLogPanel) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .align(Alignment.BottomCenter)
                                .heightIn(max = 200.dp) // Limit height of the log panel
                        ) {
                            AppLogCard(
                                logs = logs,
                                onDismiss = { showAppLogPanel = false },
                                onClearLogs = { viewModel.clearLogs() }
                            )
                        }
                    }
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

        if (wavFiles.isNotEmpty()) {
            FileListCard(title = "WAV ファイル", files = wavFiles, onDownloadClick = onDownloadClick, isBusy = isBusy, showDownloadButton = false) // No download button for WAVs
        }

    }
}

@Composable
fun FileListCard(title: String, files: List<com.pirorin215.fastrecmob.data.FileEntry>, onDownloadClick: (String) -> Unit, isBusy: Boolean, showDownloadButton: Boolean = true) {
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




@Composable
fun SummaryInfoCard(deviceInfo: DeviceInfoResponse?) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceAround // 均等配置
            ) {
                InfoItem(icon = getWifiIcon(deviceInfo?.wifiRssi ?: -100), label = deviceInfo?.connectedSsid ?: "-", value = "${deviceInfo?.wifiRssi ?: "-"}dBm", modifier = Modifier.weight(1f))
                InfoItem(icon = Icons.Default.BatteryChargingFull, label = "Battery", value = "${String.format("%.0f", deviceInfo?.batteryLevel ?: 0.0f)}%", modifier = Modifier.weight(1f))
                InfoItem(icon = Icons.Default.SdStorage, label = "Storage", value = "${deviceInfo?.littlefsUsagePercent ?: 0}%", modifier = Modifier.weight(1f))
                InfoItem(icon = Icons.Default.Audiotrack, label = "WAVs", value = (deviceInfo?.wavCount ?: 0).toString(), modifier = Modifier.weight(1f))
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "Collapse" else "Expand"
                    )
                }
            }
            if (expanded) {
                Spacer(modifier = Modifier.size(8.dp))
                InfoRow(label = "バッテリー電圧", value = "${String.format("%.2f", deviceInfo?.batteryVoltage ?: 0.0f)} V")
                InfoRow(label = "アプリ状態", value = deviceInfo?.appState ?: "-")
                InfoRow(label = "ストレージ使用量", value = "${deviceInfo?.littlefsUsedBytes ?: 0} bytes")
                InfoRow(label = "ストレージ総容量", value = "${(deviceInfo?.littlefsTotalBytes ?: 0) / 1024 / 1024} MB")
            }
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



// ... other imports ...

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

// ... rest of the file ...



class BleViewModelFactory(
    private val appSettingsRepository: AppSettingsRepository,
    private val lastKnownLocationRepository: LastKnownLocationRepository,
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BleViewModel::class.java)) {
            val transcriptionResultRepository = TranscriptionResultRepository(application)
            @Suppress("UNCHECKED_CAST")
            return BleViewModel(appSettingsRepository, transcriptionResultRepository, lastKnownLocationRepository, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// AppSettingsViewModelFactory for providing AppSettingsViewModel
class AppSettingsViewModelFactory(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppSettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppSettingsViewModel(appSettingsRepository, application) as T
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

@Composable
fun ApiKeyWarningCard(
    apiKeyStatus: ApiKeyStatus,
    onNavigateToSettings: () -> Unit
) {
    if (apiKeyStatus == ApiKeyStatus.EMPTY || apiKeyStatus == ApiKeyStatus.INVALID) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = "Warning",
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (apiKeyStatus) {
                        ApiKeyStatus.EMPTY -> "APIキーが設定されていません。設定画面で入力してください。"
                        ApiKeyStatus.INVALID -> "APIキーが無効です。設定画面で確認してください。"
                        else -> "" // Should not happen
                    },
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onNavigateToSettings,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onErrorContainer),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("設定へ", color = MaterialTheme.colorScheme.errorContainer)
                }
            }
        }
    }
}
