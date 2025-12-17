package com.pirorin215.fastrecmob

import com.pirorin215.fastrecmob.viewModel.MainViewModel
import com.pirorin215.fastrecmob.viewModel.MainViewModelFactory
import com.pirorin215.fastrecmob.LocationTracker

import android.Manifest
import android.app.Application
import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import com.pirorin215.fastrecmob.service.BleScanService
import com.pirorin215.fastrecmob.ui.screen.MainScreen
import com.pirorin215.fastrecmob.ui.theme.FastRecMobTheme
import com.pirorin215.fastrecmob.viewModel.AppSettingsViewModel
import com.pirorin215.fastrecmob.viewModel.AppSettingsViewModelFactory
import com.pirorin215.fastrecmob.viewModel.BleConnectionManager // Add this import

import kotlinx.coroutines.flow.MutableSharedFlow // Add this import
import kotlinx.coroutines.flow.MutableStateFlow // Add this import
import kotlinx.coroutines.launch // Add this import

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val context = LocalContext.current
            val application = context.applicationContext as Application

            // ViewModelの生成をFactoryに集約
            val mainViewModel: MainViewModel = viewModel(factory = MainViewModelFactory(application))
            val appSettingsViewModel: AppSettingsViewModel = viewModel(
                factory = AppSettingsViewModelFactory(
                    application,
                    (application as MainApplication).appSettingsRepository,
                    (application as MainApplication).transcriptionManager
                )
            )

            val themeMode by mainViewModel.themeMode.collectAsState()

            FastRecMobTheme(themeMode = themeMode) {
                BleApp(
                    modifier = Modifier.fillMaxSize(),
                    appSettingsViewModel = appSettingsViewModel
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // バックグラウンドで処理を継続させるため、Activity破棄時にサービスを停止しないように変更
        // val serviceIntent = Intent(this, BleScanService::class.java)
        // stopService(serviceIntent)
    }
}

private const val TAG = "BleApp"

@Composable
fun BleApp(modifier: Modifier = Modifier, appSettingsViewModel: AppSettingsViewModel) { // Updated signature
    val context = LocalContext.current
    
    // Permission handling
    val requiredPermissions = remember {
        val basePermissions = mutableListOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            basePermissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            basePermissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        Log.d(TAG, "Required Permissions: ${basePermissions.joinToString()}")
        basePermissions
    }

    var permissionsGranted by remember { mutableStateOf(true) } 

    LaunchedEffect(permissionsGranted) { 
        if (permissionsGranted) {
            Log.d(TAG, "All permissions granted. Starting BleScanService.")
            val serviceIntent = Intent(context, BleScanService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        }
    }

    // Main Screen Logic
    MainScreen(appSettingsViewModel = appSettingsViewModel) // Updated call site
}