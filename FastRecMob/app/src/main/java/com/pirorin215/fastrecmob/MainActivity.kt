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
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
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
    var hasPermissions by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            }
        )
    }

    val requestPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermissions = permissions.values.all { it }
    }

    Column(modifier = modifier) {
        if (hasPermissions) {
            BleControl()
        } else {
            Button(onClick = {
                val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    arrayOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT
                    )
                } else {
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                requestPermissionLauncher.launch(permissionsToRequest)
            }) {
                Text("Request BLE Permissions")
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

    Column {
        Button(onClick = { viewModel.startScan() }) {
            Text("Scan for fastrec")
        }
        Button(onClick = { viewModel.disconnect() }) {
            Text("Disconnect")
        }
        Text(text = "Connection State: $connectionState")
        Button(onClick = { viewModel.sendCommand("GET:info") }) {
            Text("Get Info")
        }

        DeviceInfoDisplay(deviceInfo = deviceInfo)

        Divider()
        Text(text = "Logs:")
        LazyColumn {
            items(logs) { log ->
                Text(text = log)
            }
        }
    }
}

@Composable
fun DeviceInfoDisplay(deviceInfo: DeviceInfoResponse?) {
    deviceInfo?.let { info ->
        Column {
            Text(text = "--- Device Information ---")
            Text(text = "バッテリーレベル : ${info.batteryLevel} %")
            Text(text = "バッテリー電圧   : ${String.format("%.2f", info.batteryVoltage)} V")
            Text(text = "アプリ状態       : ${info.appState}")
            Text(text = "WiFi接続状態     : ${info.wifiStatus}")
            Text(text = "接続済みSSID     : ${info.connectedSsid}")
            Text(text = "WiFi RSSI        : ${info.wifiRssi}")
            Text(text = "LittleFS使用率   : ${info.littlefsUsagePercent} %")
            Text(text = "LittleFS使用量   : ${info.littlefsUsedBytes} bytes")
            Text(text = "LittleFS総容量   : ${info.littlefsTotalBytes} bytes")
            Text(text = "ディレクトリ一覧:")
            val fileEntries = parseFileEntries(info.ls)
            fileEntries.forEach { file ->
                Text(text = "  - ${file.name.padEnd(30)} ${file.size.padStart(15)}")
            }
            Text(text = "--------------------------")
        }
    }
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