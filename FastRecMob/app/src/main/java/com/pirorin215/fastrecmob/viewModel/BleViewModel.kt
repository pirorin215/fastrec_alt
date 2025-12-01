package com.pirorin215.fastrecmob.viewModel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred // Add this import
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.pirorin215.fastrecmob.service.SpeechToTextService
import com.pirorin215.fastrecmob.data.AppSettingsRepository

import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository

sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}
// ...

@SuppressLint("MissingPermission")
class BleViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val context: Context
) : ViewModel() {

    companion object {
        const val TAG = "BleViewModel"
        const val DEVICE_NAME = "fastrec"
        const val COMMAND_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26aa"
        const val RESPONSE_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ab"
        const val ACK_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ac"
        const val CCCD_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb"
        const val SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b" // Add this
        const val MAX_DELETE_RETRIES = 3
        const val DELETE_RETRY_DELAY_MS = 1000L // 1 second
    }

    val apiKey: StateFlow<String> = appSettingsRepository.apiKeyFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    val refreshIntervalSeconds: StateFlow<Int> = appSettingsRepository.refreshIntervalSecondsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 30 // Provide a default
        )

    val keepConnectionAlive: StateFlow<Boolean> = appSettingsRepository.keepConnectionAliveFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true // Default to keeping connection alive
        )

    val audioCacheLimit: StateFlow<Int> = appSettingsRepository.audioCacheLimitFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 100 // Default to 100 files
        )

    val transcriptionResults: StateFlow<List<TranscriptionResult>> = transcriptionResultRepository.transcriptionResultsFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )



    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var speechToTextService: SpeechToTextService? = null // Change to nullable var

    private val _transcriptionState = MutableStateFlow("Idle")
    val transcriptionState: StateFlow<String> = _transcriptionState.asStateFlow()

    private val _transcriptionResult = MutableStateFlow<String?>(null)
    val transcriptionResult: StateFlow<String?> = _transcriptionResult.asStateFlow()
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState = _connectionState.asStateFlow()

    private val _receivedData = MutableStateFlow("")
    val receivedData = _receivedData.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfoResponse?>(null)
    val deviceInfo = _deviceInfo.asStateFlow()

    private val _deviceSettings = MutableStateFlow(com.pirorin215.fastrecmob.data.DeviceSettings()) // Change to non-nullable and provide default
    val deviceSettings = _deviceSettings.asStateFlow()

    private val _remoteDeviceSettings = MutableStateFlow<com.pirorin215.fastrecmob.data.DeviceSettings?>(null)
    val remoteDeviceSettings = _remoteDeviceSettings.asStateFlow()

    private val _settingsDiff = MutableStateFlow<String?>(null)
    val settingsDiff = _settingsDiff.asStateFlow()

    private val _fileList = MutableStateFlow<List<com.pirorin215.fastrecmob.data.FileEntry>>(emptyList())
    val fileList = _fileList.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _currentFileTotalSize = MutableStateFlow(0L)
    val currentFileTotalSize = _currentFileTotalSize.asStateFlow()

    private val _fileTransferState = MutableStateFlow("Idle")
    val fileTransferState = _fileTransferState.asStateFlow()

    private val _currentOperation = MutableStateFlow(Operation.IDLE)
    val currentOperation = _currentOperation.asStateFlow()

    private val _transferKbps = MutableStateFlow(0.0f)
    val transferKbps = _transferKbps.asStateFlow()

    private val _isAutoRefreshEnabled = MutableStateFlow(true)
    val isAutoRefreshEnabled = _isAutoRefreshEnabled.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var currentDownloadingFileName: String? = null
    private var currentCommandCompletion: CompletableDeferred<Boolean>? = null // Declare this here
    private var currentDeleteCompletion: CompletableDeferred<Boolean>? = null // Add this

    private val json = Json { ignoreUnknownKeys = true }
    private val bleMutex = Mutex()

    enum class Operation {
        IDLE,
        FETCHING_INFO,
        FETCHING_SETTINGS,
        DOWNLOADING_FILE,
        SENDING_SETTINGS,
        DELETING_FILE // Added new state
    }

    private var responseBuffer = mutableListOf<Byte>()
    private val transcriptionQueue = mutableListOf<String>()
    private var autoRefreshJob: Job? = null
    private var _transferStartTime = 0L
    private var connectionRetries = 0
    private val maxConnectionRetries = 3

    init {
        connectionState.onEach { state ->
            if (state != "Connected") {
                stopAutoRefresh()
            }
        }.launchIn(viewModelScope)

        deviceInfo.onEach { info ->
            info?.ls?.let { fileString ->
                _fileList.value = com.pirorin215.fastrecmob.data.parseFileEntries(fileString)
                // checkForNewWavFilesAndProcess() will now be called after _currentOperation is IDLE in FETCHING_INFO block
            }
        }.launchIn(viewModelScope)

        // APIキーの変更を監視してSpeechToTextServiceを初期化
        appSettingsRepository.apiKeyFlow.onEach { apiKey ->
            if (apiKey.isNotEmpty()) {
                speechToTextService?.close()
                speechToTextService = SpeechToTextService(apiKey)
                addLog("SpeechToTextService initialized with API Key.")
            } else {
                speechToTextService?.close()
                speechToTextService = null
                addLog("SpeechToTextService cleared (API Key not set).")
            }
        }.launchIn(viewModelScope)

        // BleScanServiceManagerからのデバイス発見イベントを購読
        viewModelScope.launch {
            com.pirorin215.fastrecmob.BleScanServiceManager.deviceFoundFlow.onEach { device ->
                addLog("Device found by service: ${device.name} (${device.address}). Initiating connection.")
                if (_connectionState.value == "Disconnected") {
                    connectToDevice(device)
                } else {
                    addLog("Already connected or connecting. Skipping new connection attempt.")
                }
            }.launchIn(this)
        }
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                // Use the value from the StateFlow, converting seconds to milliseconds
                val intervalMs = (refreshIntervalSeconds.value * 1000L).coerceAtLeast(5000L) // Ensure minimum 5s
                delay(intervalMs)
                // The mutex in fetchFileList now handles concurrency, so we can call it directly.
                // It will wait if another operation is in progress.
                fetchFileList()
            }
        }
    }

    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun setAutoRefresh(enabled: Boolean) {
        _isAutoRefreshEnabled.value = enabled
        if (enabled) {
            addLog("Auto-refresh enabled.")
            fetchFileList()
            startAutoRefresh()
        } else {
            addLog("Auto-refresh disabled.")
            stopAutoRefresh()
        }
    }

    private fun addLog(message: String) {
        Log.d(TAG, message)
        _logs.value = (_logs.value + message).takeLast(100)
    }

    private fun resetOperationStates() {
        addLog("Resetting all operation states.")
        _currentOperation.value = Operation.IDLE
        _fileTransferState.value = "Idle"
        _downloadProgress.value = 0
        _currentFileTotalSize.value = 0L
        _transferKbps.value = 0.0f
        _transferStartTime = 0L
        responseBuffer.clear()
        currentDownloadingFileName = null
    }

    private fun resetFileTransferMetrics() {
        _downloadProgress.value = 0
        _currentFileTotalSize.value = 0L
        _transferKbps.value = 0.0f
        _transferStartTime = 0L
        responseBuffer.clear()
    }

    private fun checkForNewWavFilesAndProcess() {
        viewModelScope.launch {
            if (_connectionState.value != "Connected") { // Only check connection here
                addLog("Not connected, skipping new file check.")
                return@launch
            }

            val currentWavFilesOnMicrocontroller = _fileList.value.filter { it.name.endsWith(".wav", ignoreCase = true) }
            val transcribedFileNames = transcriptionResults.value.map { it.fileName }.toSet()

            val filesToProcess = currentWavFilesOnMicrocontroller.filter { fileEntry ->
                !transcribedFileNames.contains(fileEntry.name)
            }

            if (filesToProcess.isNotEmpty()) {
                // Process only one file at a time; rely on next auto-refresh to pick up others.
                // The mutex inside downloadFile handles the IDLE check.
                val fileEntry = filesToProcess.first()
                addLog("Found untranscribed WAV file: ${fileEntry.name}. Starting automatic download.")
                downloadFile(fileEntry.name)
            } else {
                // No WAV files to download from device. Now, check the local transcription queue.
                if (transcriptionQueue.isEmpty()) {
                    // Device is clean AND local queue is empty. We are fully idle.
                    addLog("No files to process on device or locally. Checking if disconnection is needed.")
                    if (!keepConnectionAlive.value) {
                        addLog("Idle and 'Disconnect when Idle' is enabled. Disconnecting.")
                        disconnect()
                    } else {
                        addLog("Idle but 'Keep Connected' is enabled. Staying connected.")
                    }
                } else {
                    // There are still files being transcribed locally.
                    addLog("No new untranscribed WAV files found on microcontroller. Checking transcription queue.")
                    startBatchTranscription()
                }
            }
        }
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _connectionState.value = "Connected"
                    addLog("Successfully connected to $deviceAddress")
                    connectionRetries = 0
                    addLog("Requesting MTU of 517")
                    gatt.requestMtu(517)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _connectionState.value = "Disconnected"
                    addLog("Successfully disconnected from $deviceAddress")
                    resetOperationStates()
                    gatt.close()
                    // Signal BleScanService to restart scanning
                    viewModelScope.launch {
                        com.pirorin215.fastrecmob.BleScanServiceManager.emitRestartScan()
                    }
                }
            } else {
                if (status == 133 && connectionRetries < maxConnectionRetries) {
                    connectionRetries++
                    addLog("GATT error 133. Retrying connection... (Attempt ${connectionRetries})")
                    viewModelScope.launch {
                        delay(500)
                        gatt.device.connectGatt(context, false, gattCallback)
                    }
                } else {
                    _connectionState.value = "Disconnected"
                    addLog("Error $status encountered for $deviceAddress! Disconnecting...")
                    resetOperationStates()
                    gatt.close()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("MTU changed to $mtu")
                viewModelScope.launch {
                    delay(600)
                    addLog("Discovering services after delay")
                    val initiated = bluetoothGatt?.discoverServices()
                    if (initiated != true) {
                        addLog("Failed to initiate service discovery. Disconnecting.")
                        disconnect()
                    } else {
                        addLog("Service discovery initiated successfully.")
                    }
                }
            } else {
                addLog("MTU change failed, status: $status")
                addLog("Discovering services anyway...")
                val initiated = bluetoothGatt?.discoverServices()
                if (initiated != true) {
                    addLog("Failed to initiate service discovery on fallback. Disconnecting.")
                    disconnect()
                } else {
                    addLog("Service discovery initiated successfully on fallback.")
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Services discovered successfully")
                var foundResponseChar = false
                var foundCommandChar = false
                var foundAckChar = false

                gatt.services?.forEach { service ->
                    addLog("Inspecting service: ${service.getUuid()}")
                    // Check if this is our custom service
                    if (service.getUuid().toString().equals(BleViewModel.SERVICE_UUID, true)) {
                        val responseCharacteristic = service.getCharacteristic(UUID.fromString(BleViewModel.RESPONSE_UUID_STRING))
                        if (responseCharacteristic != null) {
                            addLog("Found response characteristic: ${responseCharacteristic.getUuid()}")
                            foundResponseChar = true
                            gatt.setCharacteristicNotification(responseCharacteristic, true)
                            val descriptor = responseCharacteristic.getDescriptor(UUID.fromString(BleViewModel.CCCD_UUID_STRING))
                            if (descriptor == null) {
                                addLog("Error: CCCD descriptor not found for response characteristic. Disconnecting.")
                                bluetoothGatt?.disconnect() // Disconnect if critical descriptor is missing
                                return@forEach // Skip if no CCCD
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                bluetoothGatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                bluetoothGatt?.writeDescriptor(descriptor)
                            }
                            addLog("Writing descriptor to enable notifications for ${responseCharacteristic.getUuid()}")
                        } else {
                            addLog("Response characteristic ${BleViewModel.RESPONSE_UUID_STRING} not found in service ${service.getUuid()}.")
                        }

                        val localCommandChar = service.getCharacteristic(UUID.fromString(BleViewModel.COMMAND_UUID_STRING))
                        if (localCommandChar != null) {
                            commandCharacteristic = localCommandChar
                            addLog("Found command characteristic: ${localCommandChar.getUuid()}")
                            foundCommandChar = true
                        } else {
                            addLog("Command characteristic ${BleViewModel.COMMAND_UUID_STRING} not found in service ${service.getUuid()}.")
                        }

                        val localAckChar = service.getCharacteristic(UUID.fromString(BleViewModel.ACK_UUID_STRING))
                        if (localAckChar != null) {
                            ackCharacteristic = localAckChar
                            addLog("Found ACK characteristic: ${localAckChar.getUuid()}")
                            foundAckChar = true
                        } else {
                            addLog("ACK characteristic ${BleViewModel.ACK_UUID_STRING} not found in service ${service.getUuid()}.")
                        }
                    } else {
                        addLog("Skipping unknown service: ${service.getUuid()}")
                    }
                }
                if (!foundResponseChar || !foundCommandChar || !foundAckChar) {
                    addLog("Error: Not all required characteristics found. Disconnecting.")
                    bluetoothGatt?.disconnect() // Disconnect if setup is incomplete
                }
            } else {
                addLog("Service discovery failed with status $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            if (characteristic.uuid != UUID.fromString(BleViewModel.RESPONSE_UUID_STRING)) return

            when (_currentOperation.value) {
                Operation.FETCHING_INFO -> {
                    responseBuffer.addAll(value.toList())
                    val currentBufferAsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)
                    if (currentBufferAsString.trim().endsWith("}")) {
                        addLog("Assembled data for DeviceInfo: $currentBufferAsString")
                        try {
                            val parsedResponse = json.decodeFromString<DeviceInfoResponse>(currentBufferAsString)
                            _deviceInfo.value = parsedResponse
                            addLog("Parsed DeviceInfo: ${parsedResponse.batteryLevel}%")
                            currentCommandCompletion?.complete(true) // Signal success
                        } catch (e: Exception) {
                            addLog("Error parsing DeviceInfo JSON: ${e.message}")
                            currentCommandCompletion?.complete(false) // Signal failure
                        }
                    } else if (currentBufferAsString.startsWith("ERROR:")) { // Handle ERROR response from microcontroller
                        addLog("Received error response for GET:info: $currentBufferAsString")
                        currentCommandCompletion?.complete(false) // Signal completion (with error)
                    }
                }
                Operation.FETCHING_SETTINGS -> {
                    responseBuffer.addAll(value.toList())
                    viewModelScope.launch {
                        delay(200)
                        if (_currentOperation.value == Operation.FETCHING_SETTINGS) {
                            val settingsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)
                            addLog("Assembled remote settings: $settingsString")
                            val remoteSettings = com.pirorin215.fastrecmob.data.DeviceSettings.fromIniString(settingsString)
                            _remoteDeviceSettings.value = remoteSettings
                            
                            val diff = remoteSettings.diff(_deviceSettings.value)
                            
                            if (diff.isNotBlank()) {
                                _settingsDiff.value = diff
                                addLog("Settings have differences.")
                            } else {
                                _settingsDiff.value = "差分はありません。"
                                addLog("Settings are identical.")
                            }

                            _currentOperation.value = Operation.IDLE
                            responseBuffer.clear()
                        }
                    }
                }
                Operation.DOWNLOADING_FILE -> {
                    handleFileDownloadData(value)
                }
                Operation.DELETING_FILE -> { // Handle response for file deletion
                    val response = value.toString(Charsets.UTF_8).trim()
                    addLog("Received response for file deletion: $response")
                    if (response.startsWith("OK: File")) {
                        currentDeleteCompletion?.complete(true)
                        responseBuffer.clear()
                    } else if (response.startsWith("ERROR:")) {
                        currentDeleteCompletion?.complete(false)
                        responseBuffer.clear()
                    } else {
                        addLog("Unexpected response during file deletion: $response")
                        currentDeleteCompletion?.complete(false) // Treat unexpected as failure
                        responseBuffer.clear()
                    }
                    // _currentOperation.value is now managed by the deleteFileOnMicrocontroller function
                    // fetchFileList() is also handled by deleteFileOnMicrocontroller after successful deletion
                }
                else -> {
                    addLog("Received data in unexpected state (${_currentOperation.value}): ${value.toString(Charsets.UTF_8)}")
                }
            }
        }
        
        private fun handleFileDownloadData(value: ByteArray) {
            when (_fileTransferState.value) {
                "WaitingForStart" -> {
                    if (value.contentEquals("START".toByteArray())) {
                        addLog("Received START signal. Sending START_ACK.")
                        sendAck("START_ACK".toByteArray(Charsets.UTF_8))
                        _fileTransferState.value = "Downloading"
                        _transferStartTime = System.currentTimeMillis()
                        responseBuffer.clear()
                        _downloadProgress.value = 0
                    } else {
                        addLog("Waiting for START, but received: ${value.toString(Charsets.UTF_8)}")
                        currentCommandCompletion?.complete(false) // Signal failure
                    }
                }
                "Downloading" -> {
                    if (value.contentEquals("EOF".toByteArray())) {
                        addLog("End of file transfer signal received.")
                        val filePath = saveFile(responseBuffer.toByteArray())
                        if (filePath != null) {
                            // The file is saved, the calling function knows the filename.
                            // Signal success. The caller will handle queueing and deletion.
                            currentCommandCompletion?.complete(true)
                        } else {
                            // saveFile would have logged the error.
                            currentCommandCompletion?.complete(false)
                        }
                    } else if (value.toString(Charsets.UTF_8).startsWith("ERROR:")) {
                        val errorMessage = value.toString(Charsets.UTF_8)
                        addLog("Received error during transfer: $errorMessage")
                        currentCommandCompletion?.complete(false)
                    } else { // Handle normal data transfer (not EOF or ERROR)
                        responseBuffer.addAll(value.toList())
                        _downloadProgress.value = responseBuffer.size
                        val elapsedTime = (System.currentTimeMillis() - _transferStartTime) / 1000.0f
                        if (elapsedTime > 0) {
                            _transferKbps.value = (responseBuffer.size / 1024.0f) / elapsedTime
                        }
                        sendAck("ACK".toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    
        private fun sendAck(ackValue: ByteArray) {
            val characteristic = ackCharacteristic
            if (characteristic != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGatt?.writeCharacteristic(characteristic, ackValue, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    characteristic.value = ackValue
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    bluetoothGatt?.writeCharacteristic(characteristic)
                }
            }
        }
    
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Descriptor written successfully. Ready to communicate.")
                viewModelScope.launch {
                    fetchFileList()
                    if (_isAutoRefreshEnabled.value) { // Start auto-refresh if enabled
                        startAutoRefresh()
                    }
                }
            } else {
                addLog("Descriptor write failed with status $status")
            }
        }
    
        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if(characteristic?.uuid == commandCharacteristic?.uuid) {
                 if (status == BluetoothGatt.GATT_SUCCESS) {
                    addLog("Command written successfully.")
                    if (_currentOperation.value == Operation.SENDING_SETTINGS) {
                         addLog("Settings sent. Device will likely reboot.")
                         _currentOperation.value = Operation.IDLE
                    }
                 } else {
                     addLog("Command write failed with status $status.")
                     if(_currentOperation.value != Operation.IDLE) {
                         _currentOperation.value = Operation.IDLE
                     }
                 }
            }
        }
    }
    
    private fun saveFile(data: ByteArray): String? {
        val fileName = currentDownloadingFileName ?: "downloaded_file_${System.currentTimeMillis()}.wav"
        return try {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(path, fileName)
            FileOutputStream(file).use { it.write(data) }
            addLog("File saved successfully: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            addLog("Error saving file: ${e.message}")
            _fileTransferState.value = "Error: ${e.message}"
            null
        }
    }

    private suspend fun cleanupOldAudioFiles() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            addLog("Running audio file cleanup...")
            val limit = audioCacheLimit.value
            val downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            
            // Filter for files managed by this app
            val audioFiles = downloadDir.listFiles { _, name ->
                name.startsWith("R") && name.endsWith(".wav")
            }

            if (audioFiles == null) {
                addLog("Could not list files in download directory.")
                return@withContext
            }

            if (audioFiles.size > limit) {
                addLog("Audio cache limit ($limit) exceeded. Found ${audioFiles.size} files. Deleting oldest...")
                
                // Sort files by name, which corresponds to date (oldest first)
                val sortedFiles = audioFiles.sortedBy { it.name }
                
                val filesToDelete = sortedFiles.take(audioFiles.size - limit)
                
                filesToDelete.forEach { file ->
                    if (file.delete()) {
                        addLog("Deleted old audio file: ${file.name}")
                    } else {
                        addLog("Failed to delete old audio file: ${file.name}")
                    }
                }
                addLog("Cleanup finished. Deleted ${filesToDelete.size} file(s).")
            } else {
                addLog("Audio cache is within limit ($limit). No files deleted.")
            }
        } catch (e: Exception) {
            addLog("Error during audio file cleanup: ${e.message}")
        }
    }
    

    
    fun startScan() {
        _logs.value = emptyList()
        addLog("Manual scan button pressed. Waiting for service to find device.")
        // サービスがデバイスを見つけたら、BleScanServiceManager経由でここにイベントが来る
        // Note: The actual scan is handled by BleScanService
    }
    

    
    private fun connectToDevice(device: BluetoothDevice) {
        addLog("Connecting to device ${device.address}")
        connectionRetries = 0
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }
    
    fun sendCommand(command: String) {
        val characteristic = commandCharacteristic
        if (characteristic == null) {
            addLog("Command characteristic not found")
            return
        }
        addLog("Sending command: $command")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(characteristic, command.toByteArray(Charsets.UTF_8), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            characteristic.value = command.toByteArray(Charsets.UTF_8)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic)
        }
    }
    
    fun deleteFileOnMicrocontroller(fileName: String) {
        viewModelScope.launch {
            if (connectionState.value != "Connected") {
                addLog("Cannot delete file, not connected.")
                return@launch
            }

            bleMutex.withLock {
                var success = false
                try {
                    _currentOperation.value = Operation.DELETING_FILE
                    for (i in 0..MAX_DELETE_RETRIES) {
                        addLog("Sending command to delete file: DEL:file:$fileName (Attempt ${i + 1}/${MAX_DELETE_RETRIES + 1})")
                        
                        val deleteCompletion = CompletableDeferred<Boolean>()
                        currentDeleteCompletion = deleteCompletion
                        
                        sendCommand("DEL:file:$fileName")

                        try {
                            success = withTimeout(10000L) { // 10 seconds timeout for delete command
                                deleteCompletion.await()
                            }
                        } catch (e: TimeoutCancellationException) {
                            addLog("DEL:file:$fileName command timed out. Error: ${e.message}")
                            success = false
                        }

                        if (success) {
                            addLog("Successfully deleted file: $fileName.")
                            // Allow some time for the device to process deletion before fetching the list
                            delay(1000L) 
                            // fetchFileList will acquire its own lock, so we must exit this one first.
                            // Launch a new coroutine to avoid blocking this finally block.
                            viewModelScope.launch { fetchFileList() }
                            break // Exit retry loop on success
                        } else if (i < MAX_DELETE_RETRIES) {
                            addLog("Failed to delete file: $fileName. Retrying in ${DELETE_RETRY_DELAY_MS}ms...")
                            delay(DELETE_RETRY_DELAY_MS)
                        }
                    }
                } finally {
                    if (!success) {
                        addLog("Failed to delete file: $fileName after all attempts.")
                    }
                    _currentOperation.value = Operation.IDLE // Ensure state is IDLE after all attempts
                    currentDeleteCompletion = null
                    addLog("deleteFileOnMicrocontroller operation scope finished.")
                }
            }
        }
    }
    
    fun fetchFileList() {
        viewModelScope.launch {
            if (connectionState.value != "Connected") {
                addLog("Cannot fetch file list, not connected.")
                return@launch
            }

            var infoSuccess = false
            bleMutex.withLock {
                if (_currentOperation.value != Operation.IDLE) {
                    addLog("Cannot fetch file list, another operation is in progress.")
                    return@withLock
                }

                try {
                    _currentOperation.value = Operation.FETCHING_INFO
                    responseBuffer.clear()
                    addLog("Requesting file list from device...")

                    val commandCompletion = CompletableDeferred<Boolean>()
                    currentCommandCompletion = commandCompletion

                    sendCommand("GET:info")

                    infoSuccess = withTimeoutOrNull(15000L) { // 15 seconds timeout
                        commandCompletion.await()
                    } ?: false

                    if(infoSuccess) {
                        addLog("GET:info command completed successfully.")
                    } else {
                        addLog("GET:info command failed or timed out.")
                    }
                } catch (e: Exception) {
                    addLog("An unexpected error occurred during fetchFileList: ${e.message}")
                } finally {
                    _currentOperation.value = Operation.IDLE
                    responseBuffer.clear()
                    currentCommandCompletion = null
                    addLog("fetchFileList lock released.")
                }
            }

            // Lock is released, now we can trigger the next step
            if (infoSuccess) {
                checkForNewWavFilesAndProcess() // Check for new files now that we have the list
            }
        }
    }
    
    fun getSettings() {
        if (_currentOperation.value != Operation.IDLE || connectionState.value != "Connected") {
            addLog("Cannot get settings, busy or not connected.")
            return
        }
        _currentOperation.value = Operation.FETCHING_SETTINGS
        responseBuffer.clear()
        addLog("Requesting settings from device...")
        sendCommand("GET:setting_ini")
    }
    
    fun applyRemoteSettings() {
        _remoteDeviceSettings.value?.let {
            _deviceSettings.value = it
            addLog("Applied remote settings to local state.")
        }
        dismissSettingsDiff()
    }
    
    fun dismissSettingsDiff() {
        _remoteDeviceSettings.value = null
        _settingsDiff.value = null
    }
    
    fun sendSettings() {
        if (_currentOperation.value != Operation.IDLE || connectionState.value != "Connected") {
            addLog("Cannot send settings, busy or not connected.")
            return
        }
        val settings = _deviceSettings.value
        dismissSettingsDiff()
        _currentOperation.value = Operation.SENDING_SETTINGS
        val iniString = settings.toIniString()
        addLog("Sending settings to device:\n$iniString")
        sendCommand("SET:setting_ini:$iniString")
        viewModelScope.launch {
            _navigationEvent.emit(NavigationEvent.NavigateBack)
        }
    }
    
    fun updateSettings(updater: (com.pirorin215.fastrecmob.data.DeviceSettings) -> com.pirorin215.fastrecmob.data.DeviceSettings) {
        dismissSettingsDiff()
        _deviceSettings.value = updater(_deviceSettings.value)
    }
    
    fun downloadFile(fileName: String) {
        viewModelScope.launch {
            if (connectionState.value != "Connected") {
                addLog("Cannot download file, not connected.")
                return@launch
            }
    
            var downloadSuccess = false
            bleMutex.withLock {
                if (_currentOperation.value != Operation.IDLE) {
                    addLog("Cannot download file '$fileName', another operation is in progress: ${_currentOperation.value}")
                    return@withLock
                }
    
                val operationCompletion = CompletableDeferred<Boolean>()
                currentCommandCompletion = operationCompletion
    
                try {
                    _currentOperation.value = Operation.DOWNLOADING_FILE
                    _fileTransferState.value = "WaitingForStart"
                    currentDownloadingFileName = fileName
    
                    val fileEntry = _fileList.value.find { it.name == fileName }
                    val fileSize = fileEntry?.size?.substringBefore(" ")?.toLongOrNull() ?: 0L
                    _currentFileTotalSize.value = fileSize
                    
                    addLog("Requesting file: $fileName (size: $fileSize bytes)")
                    sendCommand("GET:file:$fileName")
                    
                    // Generous timeout: 20s base + 1s per 8KB
                    val timeout = 20000L + (fileSize / 8192L) * 1000L
                    downloadSuccess = withTimeoutOrNull(timeout) {
                        operationCompletion.await()
                    } ?: false
    
                    if (downloadSuccess) {
                        addLog("File download operation reported success for: $fileName.")
                    } else {
                         addLog("File download failed for: $fileName (or timed out)")
                    }
                } catch (e: Exception) {
                    addLog("An unexpected error occurred during downloadFile: ${e.message}")
                } finally {
                    if (_currentOperation.value == Operation.DOWNLOADING_FILE) {
                        _currentOperation.value = Operation.IDLE
                    }
                    _fileTransferState.value = "Idle"
                    currentDownloadingFileName = null
                    currentCommandCompletion = null
                    resetFileTransferMetrics()
                    addLog("downloadFile lock released for $fileName.")
                }
            }

            // Lock is released. Now handle post-download tasks.
            if (downloadSuccess) {
                val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val file = File(path, fileName)
                if (file.exists()) {
                    transcriptionQueue.add(file.absolutePath)
                    addLog("Added ${file.name} to transcription queue.")

                    // Run cleanup after a successful download
                    cleanupOldAudioFiles()

                    // Now, trigger the deletion. This will acquire its own lock.
                    deleteFileOnMicrocontroller(fileName)
                } else {
                    addLog("Error: Downloaded file not found at ${file.absolutePath}. Cannot queue or delete.")
                }
            }
        }
    }
    
    fun disconnect() {
        addLog("Disconnecting from device")
        resetOperationStates()
        bluetoothGatt?.disconnect()
        // The restart scan signal will be handled by gattCallback.onConnectionStateChange
    }

    private suspend fun doTranscription(filePath: String) {
        _transcriptionState.value = "Transcribing ${File(filePath).name}"
        _transcriptionResult.value = null
        addLog("Starting transcription for $filePath")

        val currentService = speechToTextService
        if (currentService == null) {
            _transcriptionState.value = "Error: API key is not set. Please set it in the settings."
            addLog("Transcription failed: API key is not set.")
            return
        }

        val result = currentService.transcribeFile(filePath)

        result.onSuccess { transcription ->
            _transcriptionResult.value = transcription
            addLog("Transcription successful for $filePath.")
            val actualFileName = File(filePath).name
            val newResult = TranscriptionResult(actualFileName, transcription)
            viewModelScope.launch {
                transcriptionResultRepository.addResult(newResult)
                addLog("Transcription result saved for $actualFileName.")
            }
        }.onFailure { error ->
            _transcriptionState.value = "Error: ${error.message}"
            _transcriptionResult.value = null
            addLog("Transcription failed for $filePath: ${error.message}")
        }
    }

    private fun startBatchTranscription() {
        if (transcriptionQueue.isEmpty()) {
            addLog("Transcription queue is empty. Nothing to do.")
            return
        }

        addLog("Found ${transcriptionQueue.size} file(s) in queue. Starting batch transcription.")
        val filesToProcess = transcriptionQueue.toList()
        transcriptionQueue.clear()

        viewModelScope.launch {
            for (filePath in filesToProcess) {
                doTranscription(filePath)
            }
            addLog("Batch transcription finished.")
            _transcriptionState.value = "Idle" // Reset state after batch is done
        }
    }

    fun resetTranscriptionState() {
        _transcriptionState.value = "Idle"
        _transcriptionResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        speechToTextService?.close()
        bluetoothGatt?.disconnect()
        bluetoothGatt?.close()
        addLog("ViewModel cleared, resources released.")
    }

    fun saveRefreshInterval(seconds: Int) {
        viewModelScope.launch {
            // Basic validation, ensure it's not too frequent
            val interval = if (seconds < 5) 5 else seconds
            appSettingsRepository.saveRefreshIntervalSeconds(interval)
            addLog("Refresh interval saved: $interval seconds.")
        }
    }

    fun saveKeepConnectionAlive(enabled: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.saveKeepConnectionAlive(enabled)
            addLog("Keep connection alive setting saved: $enabled.")

            // If the setting is turned OFF, check if we should disconnect now.
            if (!enabled) {
                addLog("'Keep Connection Alive' turned off. Checking if device is idle.")
                
                // Check 1: Any WAV files on the microcontroller?
                val hasWavFilesOnDevice = _fileList.value.any { it.name.endsWith(".wav", ignoreCase = true) }
                // Check 2: Any files pending local transcription?
                val isQueueEmpty = transcriptionQueue.isEmpty()

                if (!hasWavFilesOnDevice && isQueueEmpty && connectionState.value == "Connected") {
                    addLog("Device is idle. Disconnecting immediately.")
                    disconnect()
                } else {
                    addLog("Device is not idle (Has WAV files: $hasWavFilesOnDevice, Queue not empty: ${!isQueueEmpty}) or not connected. No immediate action taken.")
                }
            }
        }
    }

    fun saveAudioCacheLimit(limit: Int) {
        viewModelScope.launch {
            val cacheLimit = if (limit < 1) 1 else limit // Ensure at least 1
            appSettingsRepository.saveAudioCacheLimit(cacheLimit)
            addLog("Audio cache limit saved: $cacheLimit files.")
        }
    }

    fun saveApiKey(apiKey: String) {
        viewModelScope.launch {
            appSettingsRepository.saveApiKey(apiKey)
            addLog("API Key saved.")
        }
    }

    fun clearTranscriptionResults() {
        viewModelScope.launch {
            transcriptionResultRepository.clearResults()
            addLog("All transcription results cleared.")
        }
    }

    // 特定の文字起こし結果を削除する関数
    fun removeTranscriptionResult(result: TranscriptionResult) {
        viewModelScope.launch {
            transcriptionResultRepository.removeResult(result)
            addLog("Transcription result removed: ${result.fileName}")
        }
    }
}