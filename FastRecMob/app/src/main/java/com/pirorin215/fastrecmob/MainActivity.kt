package com.pirorin215.fastrecmob

import com.pirorin215.fastrecmob.viewModel.BleViewModel

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
import com.pirorin215.fastrecmob.viewModel.BleViewModelFactory
import com.pirorin215.fastrecmob.viewModel.DeviceStatusViewModel
import com.pirorin215.fastrecmob.viewModel.DeviceStatusViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val context = LocalContext.current
            val appSettingsRepository = AppSettingsRepository(context.applicationContext as Application)
            val lastKnownLocationRepository = LastKnownLocationRepository(context.applicationContext as Application)
            val bleRepository = com.pirorin215.fastrecmob.data.BleRepository(context.applicationContext)
            
            val deviceStatusViewModelFactory = DeviceStatusViewModelFactory(context.applicationContext as Application, bleRepository)
            val deviceStatusViewModel: DeviceStatusViewModel = viewModel(factory = deviceStatusViewModelFactory)

            // ViewModels are created here to scope them to the Activity
            val bleViewModelFactory = BleViewModelFactory(appSettingsRepository, lastKnownLocationRepository, context.applicationContext as Application, bleRepository, deviceStatusViewModel.connectionState, deviceStatusViewModel.onDeviceReadyEvent)
            val bleViewModel: BleViewModel = viewModel(factory = bleViewModelFactory)

            val appSettingsViewModelFactory = AppSettingsViewModelFactory(context.applicationContext as Application, appSettingsRepository)
            val appSettingsViewModel: AppSettingsViewModel = viewModel(factory = appSettingsViewModelFactory)

            val themeMode by bleViewModel.themeMode.collectAsState()

            val googleSignInLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val intent = result.data ?: return@rememberLauncherForActivityResult
                    bleViewModel.handleSignInResult(intent,
                        onSuccess = { Toast.makeText(context, "Google Sign-In Success!", Toast.LENGTH_SHORT).show() },
                        onFailure = { e -> Toast.makeText(context, "Google Sign-In Failed: ${e.message}", Toast.LENGTH_LONG).show() }
                    )
                } else {
                    Toast.makeText(context, "Google Sign-In Cancelled.", Toast.LENGTH_SHORT).show()
                }
            }

            FastRecMobTheme(themeMode = themeMode) {
                BleApp(
                    modifier = Modifier.fillMaxSize(),
                    appSettingsViewModel = appSettingsViewModel,
                    deviceStatusViewModel = deviceStatusViewModel,
                    onSignInClick = { signInIntent -> googleSignInLauncher.launch(signInIntent) }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop service when app is destroyed (if that's the desired behavior)
        val serviceIntent = Intent(this, BleScanService::class.java)
        stopService(serviceIntent)
    }
}

private const val TAG = "BleApp"

@Composable
fun BleApp(modifier: Modifier = Modifier, appSettingsViewModel: AppSettingsViewModel, deviceStatusViewModel: DeviceStatusViewModel, onSignInClick: (Intent) -> Unit) {
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
    MainScreen(appSettingsViewModel = appSettingsViewModel, deviceStatusViewModel = deviceStatusViewModel, onSignInClick = onSignInClick)
}