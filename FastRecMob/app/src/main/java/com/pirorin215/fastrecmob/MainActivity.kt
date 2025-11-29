package com.pirorin215.fastrecmob

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import com.pirorin215.fastrecmob.data.FileEntry
import com.pirorin215.fastrecmob.data.parseFileEntries
import com.pirorin215.fastrecmob.ui.theme.FastRecMobTheme
import com.pirorin215.fastrecmob.viewModel.BleViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FastRecMobTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BleApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun BleApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = (LocalContext.current as? ComponentActivity)

    val requiredPermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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

    var permissionsGranted by remember {
        mutableStateOf(requiredPermissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    var shouldShowRationale by remember { mutableStateOf(false) }
    var isPermanentlyDenied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionsGranted = permissions.values.all { it }
        if (!permissionsGranted) {
            if (activity != null && requiredPermissions.any { !activity.shouldShowRequestPermissionRationale(it) }) {
                isPermanentlyDenied = true
            } else {
                shouldShowRationale = true
            }
        }
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
            horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            Text("このアプリの機能を利用するには、Bluetoothと位置情報の権限が必要です。")
            Button(onClick = {
                if (isPermanentlyDenied) {
                    // 設定画面に誘導
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = android.net.Uri.fromParts("package", context.packageName, null)
                    intent.data = uri
                    context.startActivity(intent)
                } else {
                    permissionLauncher.launch(requiredPermissions.toTypedArray())
                }
            }) {
                Text(if (isPermanentlyDenied) "設定を開く" else "権限を許可する")
            }

            if (shouldShowRationale) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { shouldShowRationale = false },
                    title = { Text("権限が必要です") },
                    text = { Text("BLEデバイスをスキャンして接続するために、Bluetoothと位置情報の権限を許可してください。") },
                    confirmButton = {
                        Button(onClick = {
                            shouldShowRationale = false
                            permissionLauncher.launch(requiredPermissions.toTypedArray())
                        }) {
                            Text("OK")
                        }
                    },
                    dismissButton = {
                        Button(onClick = { shouldShowRationale = false }) {
                            Text("キャンセル")
                        }
                    }
                )
            }

            if (isPermanentlyDenied) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { isPermanentlyDenied = false },
                    title = { Text("権限が拒否されました") },
                    text = { Text("権限が恒久的に拒否されたため、アプリの機能を利用できません。スマートフォンの設定画面から、このアプリの権限を手動で許可してください。") },
                    confirmButton = {
                        Button(onClick = {
                            isPermanentlyDenied = false
                            val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                            val uri = android.net.Uri.fromParts("package", context.packageName, null)
                            intent.data = uri
                            context.startActivity(intent)
                        }) {
                            Text("設定を開く")
                        }
                    },
                    dismissButton = {
                        Button(onClick = { isPermanentlyDenied = false }) {
                            Text("閉じる")
                        }
                    }
                )
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
    var showLogs by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }

    // Automatically start scanning when the composable enters the composition
    LaunchedEffect(Unit) {
        viewModel.startScan()
    }

    // Re-scan if disconnected
    LaunchedEffect(connectionState) {
        if (connectionState == "Disconnected") {
            viewModel.startScan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        ConnectionStatusIndicator(connectionState = connectionState)
        Row(
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            val buttonText = if (connectionState == "Connected") "切断" else "接続"
            Button(
                onClick = {
                    if (connectionState == "Connected") {
                        viewModel.disconnect()
                    } else {
                        viewModel.startScan()
                    }
                },
                modifier = Modifier.weight(1f)
            ) {
                Text(buttonText)
            }
            Button(
                onClick = { viewModel.sendCommand("GET:info") },
                modifier = Modifier.weight(1f)
            ) {
                Text("状態取得")
            }
        }

        // Display summary information
        deviceInfo?.let {
            SummaryInfoCard(deviceInfo = it)
        }

        // Toggle button for detailed information
        Button(onClick = { showDetails = !showDetails }) {
            Text(if (showDetails) "詳細を隠す" else "詳細表示")
        }

        // Display detailed information if toggled
        if (showDetails) {
            deviceInfo?.let {
                DetailedInfoCard(deviceInfo = it)
            }
        }

        Button(onClick = { showLogs = !showLogs }) {
            Text(if (showLogs) "ログを隠す" else "ログ表示")
        }

        if (showLogs) {
            Divider()
            Text(text = "Logs:")
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(logs) { log ->
                    Text(text = log)
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusIndicator(connectionState: String) {
    val indicatorColor = when (connectionState) {
        "Connected" -> Color.Green
        "Disconnected" -> Color.Red
        else -> Color.Yellow // "Scanning", "Connecting", etc.
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(indicatorColor, shape = CircleShape)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Status: $connectionState",
                style = androidx.compose.material3.MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
fun SummaryInfoCard(deviceInfo: DeviceInfoResponse) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "サマリー", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.size(8.dp))
            InfoRow(label = "バッテリーレベル", value = "${String.format("%.1f", deviceInfo.batteryLevel)} %")
            InfoRow(label = "WiFi", value = "${deviceInfo.wifiStatus} (${deviceInfo.wifiRssi} dBm)")
            InfoRow(label = "ストレージ使用量", value = "${deviceInfo.littlefsUsedBytes / 1024} KB")
        }
    }
}

@Composable
fun DetailedInfoCard(deviceInfo: DeviceInfoResponse) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = "詳細情報", style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.size(8.dp))
            InfoRow(label = "バッテリー電圧", value = "${String.format("%.2f", deviceInfo.batteryVoltage)} V")
            InfoRow(label = "アプリ状態", value = deviceInfo.appState)
            InfoRow(label = "接続済みSSID", value = deviceInfo.connectedSsid)
            InfoRow(label = "LittleFS使用率", value = "${deviceInfo.littlefsUsagePercent} %")
            InfoRow(label = "LittleFS総容量", value = "${deviceInfo.littlefsTotalBytes / 1024 / 1024} MB")

            Spacer(modifier = Modifier.size(8.dp))
            Text(text = "ディレクトリ一覧:", style = androidx.compose.material3.MaterialTheme.typography.titleSmall)
            val fileEntries = parseFileEntries(deviceInfo.ls)
            fileEntries.forEach { file ->
                Row {
                    Text(text = file.name, modifier = Modifier.weight(1f))
                    Text(text = file.size, modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, modifier = Modifier.weight(1f), style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
        Text(text = value, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium)
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