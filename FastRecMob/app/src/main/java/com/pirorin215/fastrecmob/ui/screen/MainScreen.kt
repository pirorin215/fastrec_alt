package com.pirorin215.fastrecmob.ui.screen

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pirorin215.fastrecmob.viewModel.AppSettingsViewModel
import com.pirorin215.fastrecmob.viewModel.BleOperation
import com.pirorin215.fastrecmob.viewModel.BleViewModel
import com.pirorin215.fastrecmob.viewModel.DeviceStatusViewModel
import kotlinx.coroutines.launch

private const val TAG = "MainScreen"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterialApi::class)
@SuppressLint("MissingPermission")
@Composable
fun MainScreen(
    appSettingsViewModel: AppSettingsViewModel,
    deviceStatusViewModel: DeviceStatusViewModel,
    onSignInClick: (Intent) -> Unit
) {
    val context = LocalContext.current
    val viewModel: BleViewModel = viewModel() // ViewModel is already created and provided by compositionLocal in MainActivity's setContent
    val connectionState by deviceStatusViewModel.connectionState.collectAsState()
    val deviceInfo by deviceStatusViewModel.deviceInfo.collectAsState()
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
    var showTodoDetailScreen by remember { mutableStateOf<String?>(null) } // New state for TodoDetailScreen visibility and todoId

    var showLogDownloadScreen by remember { mutableStateOf(false) }
    var showLastKnownLocationScreen by remember { mutableStateOf(false) } // New state for LastKnownLocationScreen visibility
    var showAppLogPanel by remember { mutableStateOf(false) } // New state for AppLogCard visibility
    var showGoogleTasksSyncSettings by remember { mutableStateOf(false) } // New state for GoogleTasksSyncSettingsScreen

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val isLoadingGoogleTasks by viewModel.isLoadingGoogleTasks.collectAsState()

    val isRefreshing = isLoadingGoogleTasks
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { viewModel.syncTranscriptionResultsWithGoogleTasks() }
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
            AppSettingsScreen(appSettingsViewModel = appSettingsViewModel, onBack = { showAppSettings = false })
        }

        showLogDownloadScreen -> {
            LogDownloadScreen(viewModel = viewModel, onBack = { showLogDownloadScreen = false })
        }
        showLastKnownLocationScreen -> {
            LastKnownLocationScreen(onBack = { showLastKnownLocationScreen = false })
        }
        showGoogleTasksSyncSettings -> {
            GoogleTasksSyncSettingsScreen(
                viewModel = viewModel,
                appSettingsViewModel = appSettingsViewModel,
                onBack = { showGoogleTasksSyncSettings = false },
                onSignInClick = onSignInClick
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
                                        deviceStatusViewModel.forceReconnectBle()
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
                                    text = { Text("Google Tasks 同期設定") },
                                    onClick = {
                                        showGoogleTasksSyncSettings = true
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

                            }
                        }
                    )
                }
            ) { innerPadding ->
                val apiKeyStatus by appSettingsViewModel.apiKeyStatus.collectAsState()

                Box(modifier = Modifier.fillMaxSize().padding(innerPadding).pullRefresh(pullRefreshState)) {
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
                            isBusy = currentOperation != BleOperation.IDLE,
                            transferKbps = transferKbps,
                            onDownloadClick = { viewModel.downloadFile(it) }
                        )
                        // TranscriptionResultPanel now takes flexible space
                        TranscriptionResultScreen(viewModel = viewModel, appSettingsViewModel = appSettingsViewModel, onBack = { })
                    }
                    PullRefreshIndicator(isRefreshing, pullRefreshState, Modifier.align(Alignment.TopCenter))
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
