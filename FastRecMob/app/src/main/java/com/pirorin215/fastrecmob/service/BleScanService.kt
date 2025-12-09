package com.pirorin215.fastrecmob.service

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pirorin215.fastrecmob.MainActivity
import com.pirorin215.fastrecmob.R // リソースファイルが必要になります
import com.pirorin215.fastrecmob.BleScanServiceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

class BleScanService : Service() {

    companion object {
        const val DEVICE_NAME = "fastrec"
        const val NOTIFICATION_ID = 1 // Moved here
    }

    private val TAG = "BleScanService"
    private val CHANNEL_ID = "BleScanServiceChannel"
    private val SCAN_TIMEOUT_MS = 30000L // 30秒のスキャンタイムアウト

    private lateinit var bluetoothManager: BluetoothManager
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var scanJob: Job? = null

    private val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER) // バッテリー消費を抑える
        .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // 全ての一致を報告
        .setMatchMode(ScanSettings.MATCH_MODE_STICKY) // 不定期なアドバタイズも検出
        .setNumOfMatches(ScanSettings.MATCH_NUM_ONE_ADVERTISEMENT) // 1つのアドバタイズで報告
        .setReportDelay(0L) // 0L: no batching, report results immediately
        .build()

    private val scanFilter = ScanFilter.Builder()
        .setDeviceName(DEVICE_NAME)
        .build()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BleScanService onCreate")
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        createNotificationChannel()

        // Subscribe to restart scan events
        CoroutineScope(Dispatchers.IO).launch {
            BleScanServiceManager.restartScanFlow.collect {
                Log.d(TAG, "Received restart scan signal. Restarting BLE scan.")
                startBleScan()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BleScanService onStartCommand")
        startForeground(NOTIFICATION_ID, buildNotification().build()) // Moved here
        startBleScan()
        return START_STICKY // サービスが強制終了されても再起動する
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BleScanService onDestroy")
        stopBleScan()
        stopForeground(true)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null // 今回はバインドサービスとして使用しない
    }

    @SuppressLint("MissingPermission")
    private fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
            Log.e(TAG, "Bluetooth not available or not enabled.")
            // ユーザーにBluetoothを有効にするよう促す必要がある
            return
        }

        stopBleScan() // 既存のスキャンがあれば停止

        Log.d(TAG, "Starting BLE scan in service...")
        val filters = listOf(scanFilter) // Re-introduce filter
        bluetoothAdapter?.bluetoothLeScanner?.startScan(filters, scanSettings, bleScanCallback)

        // Removed scan timeout job for continuous scanning
    }

    @SuppressLint("MissingPermission")
    private fun stopBleScan() {
        Log.d(TAG, "Stopping BLE scan in service.")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(bleScanCallback)
        // Removed scanJob?.cancel() as scanJob is no longer used for timeout
    }

    private val bleScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            Log.d(TAG, "ScanResult: Device found - ${result.device.name} (${result.device.address}), RSSI: ${result.rssi}")
            if (result.device.name == DEVICE_NAME) {
                Log.d(TAG, "Target device '${DEVICE_NAME}' found! Signaling BleViewModel to connect.")
                // Stop scanning to allow the ViewModel to handle the connection.
                // The ViewModel will be responsible for restarting the scan later.
                stopBleScan()
                CoroutineScope(Dispatchers.IO).launch {
                    BleScanServiceManager.emitDeviceFound(result.device)
                }
            }
        }

        override fun onBatchScanResults(results: List<ScanResult>) {
            super.onBatchScanResults(results)
            Log.d(TAG, "onBatchScanResults: ${results.size} devices found.")
            // バッチスキャン結果の中からターゲットデバイスを探す
            results.forEach { result ->
                if (result.device.name == DEVICE_NAME) {
                    Log.d(TAG, "Target device '${DEVICE_NAME}' found in batch! Signaling BleViewModel to connect.")
                    // Do NOT stop scan here; continue scanning for automatic re-detection
                    CoroutineScope(Dispatchers.IO).launch {
                        BleScanServiceManager.emitDeviceFound(result.device)
                    }
                    return // 1つ見つけたら処理を終了
                }
            }
        }    } // Closing brace for bleScanCallback object.

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "BLE Scan Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun buildNotification(): NotificationCompat.Builder {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FastRecMob")
            .setContentText("バックグラウンドでBLEデバイスをスキャン中...")
            .setSmallIcon(R.mipmap.ic_launcher_round) // 適切なアイコンを設定
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
    }
} // Final closing brace for BleScanService class.