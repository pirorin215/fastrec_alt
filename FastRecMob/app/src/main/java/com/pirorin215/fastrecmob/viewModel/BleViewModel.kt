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
import android.provider.MediaStore
import android.content.ContentValues
import android.net.Uri
import java.io.OutputStream
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
import kotlinx.coroutines.flow.distinctUntilChanged
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.ThemeMode
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import com.pirorin215.fastrecmob.LocationData

import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository
import com.pirorin215.fastrecmob.service.SpeechToTextService

sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}
// ...

@SuppressLint("MissingPermission")
class BleViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val lastKnownLocationRepository: LastKnownLocationRepository, // Add this
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
        const val TIME_SYNC_INTERVAL_MS = 300000L // 5 minutes
    }

    private var timeSyncJob: Job? = null

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



    val transcriptionCacheLimit: StateFlow<Int> = appSettingsRepository.transcriptionCacheLimitFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 100 // Default to 100 files
        )

    val transcriptionFontSize: StateFlow<Int> = appSettingsRepository.transcriptionFontSizeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 14 // Default to 14
        )

    val audioDirName: StateFlow<String> = appSettingsRepository.audioDirNameFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "FastRecRecordings" // Default directory name
        )

    val themeMode: StateFlow<ThemeMode> = appSettingsRepository.themeModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM // Default to SYSTEM
        )

    val sortMode: StateFlow<com.pirorin215.fastrecmob.data.SortMode> = appSettingsRepository.sortModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = com.pirorin215.fastrecmob.data.SortMode.TIMESTAMP
        )

    val transcriptionResults: StateFlow<List<TranscriptionResult>> = transcriptionResultRepository.transcriptionResultsFlow
        .combine(sortMode) { list: List<TranscriptionResult>, mode: com.pirorin215.fastrecmob.data.SortMode ->
            when (mode) {
                com.pirorin215.fastrecmob.data.SortMode.TIMESTAMP -> list.sortedByDescending { it.timestamp }
                com.pirorin215.fastrecmob.data.SortMode.CUSTOM -> list.sortedBy { it.displayOrder }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val transcriptionCount: StateFlow<Int> = transcriptionResults
        .map { it.size }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 0
        )

    private val _audioFileCount = MutableStateFlow(0)
    val audioFileCount: StateFlow<Int> = _audioFileCount.asStateFlow()

    private fun updateLocalAudioFileCount() {
        viewModelScope.launch {
            val audioDir = context.getExternalFilesDir(audioDirName.value)
            if (audioDir != null && audioDir.exists()) {
                val count = audioDir.listFiles { _, name ->
                    name.matches(Regex("""R\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}\.wav"""))
                }?.size ?: 0
                _audioFileCount.value = count
                addLog("Updated local audio file count: $count")
            } else {
                _audioFileCount.value = 0
                addLog("Audio directory not found, local audio file count is 0.")
            }
        }
    }



    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val locationTracker = com.pirorin215.fastrecmob.LocationTracker(context) // Add this
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

    private val _selectedFileNames = MutableStateFlow<Set<String>>(emptySet())
    val selectedFileNames: StateFlow<Set<String>> = _selectedFileNames.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    // --- New properties for pre-collected location ---
    private val _currentForegroundLocation = MutableStateFlow<LocationData?>(null)
    val currentForegroundLocation: StateFlow<LocationData?> = _currentForegroundLocation.asStateFlow()
    private var lowPowerLocationJob: Job? = null
    // --- End new properties ---

    // --- Refactored Properties ---
    private val repository: com.pirorin215.fastrecmob.data.BleRepository = com.pirorin215.fastrecmob.data.BleRepository(context)
    private var currentDownloadingFileName: String? = null
    private var currentCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>? = null // Declare this here
    private var currentDeleteCompletion: CompletableDeferred<Boolean>? = null // Add this

    private val json = Json { ignoreUnknownKeys = true }
    private val bleMutex = Mutex()
    private val transcriptionBatchMutex = Mutex()

    enum class Operation {
        IDLE,
        FETCHING_DEVICE_INFO,
        FETCHING_FILE_LIST,
        FETCHING_SETTINGS,
        DOWNLOADING_FILE,
        SENDING_SETTINGS,
        DELETING_FILE,
        SENDING_TIME
    }

    private var responseBuffer = mutableListOf<Byte>()
    private val transcriptionQueue = mutableListOf<String>()
    private var autoRefreshJob: Job? = null
    private var _transferStartTime = 0L

    init {
        // Collect connection state from the repository
        repository.connectionState.onEach { state ->
            when(state) {
                is com.pirorin215.fastrecmob.data.ConnectionState.Pairing -> {
                    _connectionState.value = "Pairing..."
                    addLog("Pairing with device...")
                }
                is com.pirorin215.fastrecmob.data.ConnectionState.Paired -> {
                    _connectionState.value = "Paired"
                    addLog("Device paired. Connecting...")
                }
                is com.pirorin215.fastrecmob.data.ConnectionState.Connected -> {
                    _connectionState.value = "Connected"
                    addLog("Successfully connected to ${state.device.address}")
                    // Request MTU after connection
                    repository.requestMtu(517)
                }
                is com.pirorin215.fastrecmob.data.ConnectionState.Disconnected -> {
                    _connectionState.value = "Disconnected"
                    addLog("Disconnected. Handling reconnection based on app foreground state.")
                    resetOperationStates()
                    timeSyncJob?.cancel() // Cancel time sync job on disconnect
                    timeSyncJob = null
                }
                is com.pirorin215.fastrecmob.data.ConnectionState.Error -> {
                    addLog("Connection Error: ${state.message}. Forcibly disconnecting and cleaning up before recovery.")

                    // 状態をリセットし、GATT接続を完全に閉じる
                    resetOperationStates()
                    repository.disconnect() // Ensure disconnection
                    repository.close()      // Close the GATT client to prevent inconsistent state

                    _connectionState.value = "Disconnected" // 状態をUIに反映

                    timeSyncJob?.cancel()
                    timeSyncJob = null

                    // BLEスタックが安定するのを待ってから再接続を開始する
                    viewModelScope.launch {
                        delay(500L) // 500ms待機
                        addLog("Attempting to recover after error...")
                        restartScan(forceScan = true)
                    }
                }
            }
        }.launchIn(viewModelScope)

        // Collect events from the repository
        repository.events.onEach { event ->
            when(event) {
                is com.pirorin215.fastrecmob.data.BleEvent.MtuChanged -> {
                    addLog("MTU changed to ${event.mtu}")
                }
                is com.pirorin215.fastrecmob.data.BleEvent.Ready -> {
                    addLog("Device is ready for communication.")
                    viewModelScope.launch {
                        bleMutex.withLock { // Acquire lock before sending SET:time
                            _currentOperation.value = Operation.SENDING_TIME // New operation state
                            responseBuffer.clear() // Clear buffer for SET:time response
                            val timeCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                            currentCommandCompletion = timeCompletion // Use common completion for SET:time

                            val currentTimestampSec = System.currentTimeMillis() / 1000
                            val timeCommand = "SET:time:$currentTimestampSec"
                            addLog("Sending initial time synchronization command: $timeCommand")
                            sendCommand(timeCommand)

                            val (timeSyncSuccess, _) = withTimeoutOrNull(5000L) { // 5 second timeout for time sync
                                timeCompletion.await()
                            } ?: Pair(false, null)

                            if (timeSyncSuccess) {
                                addLog("Initial time synchronization successful.")
                            } else {
                                addLog("Initial time synchronization failed or timed out.")
                            }
                            _currentOperation.value = Operation.IDLE
                        }

                        // Launch fetches in separate jobs so they don't block the `onReady` handler
                        fetchDeviceInfo()
                        fetchFileList()


                        // Start periodic time synchronization
                        timeSyncJob?.cancel() // Cancel any existing job
                        timeSyncJob = viewModelScope.launch {
                            while (true) {
                                delay(TIME_SYNC_INTERVAL_MS)
                                if (connectionState.value == "Connected" && _currentOperation.value == Operation.IDLE) {
                                    bleMutex.withLock { // Acquire lock for periodic time sync
                                        val periodicTimestampSec = System.currentTimeMillis() / 1000
                                        val periodicTimeCommand = "SET:time:$periodicTimestampSec"
                                        addLog("Sending periodic time synchronization command: $periodicTimeCommand")
                                        sendCommand(periodicTimeCommand)
                                    } // Release lock
                                } else {
                                    addLog("Skipping periodic time sync: not connected or operation in progress.")
                                }
                            }
                        }
                        if (_isAutoRefreshEnabled.value) {
                            startAutoRefresh()
                        }
                    }
                }
                is com.pirorin215.fastrecmob.data.BleEvent.CharacteristicChanged -> {
                    handleCharacteristicChanged(event.characteristic, event.value)
                }
                else -> {
                    // Other events can be handled here if needed
                }
            }
        }.launchIn(viewModelScope)

        appSettingsRepository.apiKeyFlow.distinctUntilChanged().onEach { apiKey ->
            if (apiKey.isNotEmpty()) {
                speechToTextService = SpeechToTextService(apiKey)
                addLog("SpeechToTextService initialized with API Key.")
            } else {
                speechToTextService = null
                addLog("SpeechToTextService cleared (API Key not set).")
            }
        }.launchIn(viewModelScope)

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
        updateLocalAudioFileCount() // Initial call to update count
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
                fetchDeviceInfo()
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

    // --- Low Power Location Updates ---
    fun startLowPowerLocationUpdates() {
        if (lowPowerLocationJob?.isActive == true) {
            addLog("Low power location updates already active.")
            return
        }
        addLog("Starting low power location updates.")
        lowPowerLocationJob = viewModelScope.launch {
            while (true) {
                locationTracker.getLowPowerLocation().onSuccess { locationData ->
                    _currentForegroundLocation.value = locationData
                    addLog("Pre-collected low power location: Lat=${locationData.latitude}, Lng=${locationData.longitude}")
                }.onFailure { e ->
                    _currentForegroundLocation.value = null // Clear stale location on failure
                    addLog("Failed to pre-collect low power location: ${e.message}")
                }
                delay(30000L) // Update every 30 seconds
            }
        }
    }

    fun stopLowPowerLocationUpdates() {
        lowPowerLocationJob?.cancel()
        lowPowerLocationJob = null
        _currentForegroundLocation.value = null // Clear location when stopping
        addLog("Stopped low power location updates.")
    }
    // --- End Low Power Location Updates ---

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
                    addLog("No files to process on device or locally. Staying connected.")
                } else {
                    // There are still files being transcribed locally.
                    addLog("No new untranscribed WAV files found on microcontroller. Checking transcription queue.")
                    startBatchTranscription()
                }
            }
        }
    }

    private fun handleCharacteristicChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != UUID.fromString(RESPONSE_UUID_STRING)) return

        when (_currentOperation.value) {
            Operation.FETCHING_DEVICE_INFO -> {
                val incomingString = value.toString(Charsets.UTF_8).trim()
                if (responseBuffer.isEmpty() && !incomingString.startsWith("{") && !incomingString.startsWith("ERROR:")) {
                    addLog("FETCHING_DEVICE_INFO: Ignoring unexpected leading fragment: $incomingString")
                    return
                }

                responseBuffer.addAll(value.toList())
                val currentBufferAsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)
                addLog("FETCHING_DEVICE_INFO: Received fragment. Buffer length: ${currentBufferAsString.length}.")
                if (currentBufferAsString.trim().endsWith("}")) {
                    addLog("Assembled data for DeviceInfo: $currentBufferAsString")
                    try {
                        val parsedResponse = json.decodeFromString<DeviceInfoResponse>(currentBufferAsString)
                        _deviceInfo.value = parsedResponse
                        addLog("Parsed DeviceInfo: ${parsedResponse.batteryLevel}%")
                        currentCommandCompletion?.complete(Pair(true, null)) // Signal success
                    } catch (e: Exception) {
                        addLog("Error parsing DeviceInfo JSON: ${e.message}")
                        currentCommandCompletion?.complete(Pair(false, null)) // Signal failure
                    }
                } else if (currentBufferAsString.startsWith("ERROR:")) {
                    addLog("Received error response for GET:info: $currentBufferAsString")
                    currentCommandCompletion?.complete(Pair(false, null)) // Signal completion (with error)
                }
            }
            Operation.FETCHING_FILE_LIST -> {
                val incomingString = value.toString(Charsets.UTF_8).trim()
                // Handle cases where response might not start with '[' (e.g. empty list or error)
                if (responseBuffer.isEmpty() && !incomingString.startsWith("[") && !incomingString.startsWith("ERROR:")) {
                    addLog("FETCHING_FILE_LIST: Ignoring unexpected leading fragment: $incomingString")
                    // If it's an empty array, it might come as "[]" in one go.
                    if (incomingString == "[]") {
                         _fileList.value = emptyList()
                         currentCommandCompletion?.complete(Pair(true, null))
                    }
                    return
                }

                responseBuffer.addAll(value.toList())
                val currentBufferAsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)
                addLog("FETCHING_FILE_LIST: Received fragment. Buffer length: ${currentBufferAsString.length}.")

                if (currentBufferAsString.trim().endsWith("]")) {
                    addLog("Assembled data for FileList: $currentBufferAsString")
                    try {
                        _fileList.value = com.pirorin215.fastrecmob.data.parseFileEntries(currentBufferAsString)
                        addLog("Parsed FileList. Count: ${_fileList.value.size}")
                        currentCommandCompletion?.complete(Pair(true, null)) // Signal success
                    } catch (e: Exception) {
                        addLog("Error parsing FileList JSON: ${e.message}")
                        currentCommandCompletion?.complete(Pair(false, null)) // Signal failure
                    }
                } else if (currentBufferAsString.startsWith("ERROR:")) {
                    addLog("Received error response for GET:ls: $currentBufferAsString")
                    _fileList.value = emptyList() // Clear list on error
                    currentCommandCompletion?.complete(Pair(false, null)) // Signal completion (with error)
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
                val filePath = handleFileDownloadData(value)
                if (filePath != null) {
                    currentCommandCompletion?.complete(Pair(true, filePath))
                }
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
            }
            Operation.SENDING_TIME -> { // Handle response for SET:time
                val response = value.toString(Charsets.UTF_8).trim()
                addLog("Received response for SET:time: $response")
                if (response.startsWith("OK: Time")) {
                    currentCommandCompletion?.complete(Pair(true, null))
                    responseBuffer.clear()
                } else if (response.startsWith("ERROR:")) {
                    currentCommandCompletion?.complete(Pair(false, null))
                    responseBuffer.clear()
                } else {
                    addLog("Unexpected response during SET:time: $response")
                    currentCommandCompletion?.complete(Pair(false, null)) // Treat unexpected as failure
                    responseBuffer.clear()
                }
            }
            else -> {
                addLog("Received data in unexpected state (${_currentOperation.value}): ${value.toString(Charsets.UTF_8)}")
            }
        }
    }

    private fun handleFileDownloadData(value: ByteArray): String? { // Modified to return String?
        when (_fileTransferState.value) {
            "WaitingForStart" -> {
                if (value.contentEquals("START".toByteArray())) {
                    addLog("Received START signal. Sending START_ACK.")
                    sendAck("START_ACK".toByteArray(Charsets.UTF_8))
                    _fileTransferState.value = "Downloading"
                    _transferStartTime = System.currentTimeMillis()
                    responseBuffer.clear()
                    _downloadProgress.value = 0
                    return null // Not finished yet
                } else {
                    addLog("Waiting for START, but received: ${value.toString(Charsets.UTF_8)}")
                    currentCommandCompletion?.complete(Pair(false, null)) // Signal failure
                    return null
                }
            }
            "Downloading" -> {
                if (value.contentEquals("EOF".toByteArray())) {
                    addLog("End of file transfer signal received.")
                    val filePath = saveFile(responseBuffer.toByteArray())
                    if (filePath != null) {
                        // currentCommandCompletion is completed in handleCharacteristicChanged now
                        return filePath // Return the saved file path/URI
                    } else {
                        currentCommandCompletion?.complete(Pair(false, null)) // Signal failure
                        return null
                    }
                } else if (value.toString(Charsets.UTF_8).startsWith("ERROR:")) {
                    val errorMessage = value.toString(Charsets.UTF_8)
                    addLog("Received error during transfer: $errorMessage")
                    currentCommandCompletion?.complete(Pair(false, null)) // Signal failure
                    return null
                } else { // Handle normal data transfer (not EOF or ERROR)
                    responseBuffer.addAll(value.toList())
                    _downloadProgress.value = responseBuffer.size
                    val elapsedTime = (System.currentTimeMillis() - _transferStartTime) / 1000.0f
                    if (elapsedTime > 0) {
                        _transferKbps.value = (responseBuffer.size / 1024.0f) / elapsedTime
                    }
                    sendAck("ACK".toByteArray(Charsets.UTF_8))
                    return null // Not finished yet
                }
            }
        }
        return null // Should not be reached
    }

    private fun sendAck(ackValue: ByteArray) {
        repository.sendAck(ackValue)
    }

    private fun saveFile(data: ByteArray): String? {
        val fileName = currentDownloadingFileName ?: "downloaded_file_${System.currentTimeMillis()}.bin" // Default to .bin for unknown type
        return try {
            if (fileName.startsWith("log.", ignoreCase = true)) {
                // Save log files to the Downloads directory using MediaStore
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain") // Or appropriate mime type for logs
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                }

                val resolver = context.contentResolver
                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let {
                    var outputStream: OutputStream? = null
                    try {
                        outputStream = resolver.openOutputStream(it)
                        outputStream?.use { stream ->
                            stream.write(data)
                        }
                        addLog("Log file saved successfully to Downloads: $fileName")
                        return it.toString()
                    } catch (e: Exception) {
                        addLog("Error saving log file to MediaStore: ${e.message}")
                        // If something went wrong, delete the URI from MediaStore
                        uri.let { u -> resolver.delete(u, null, null) }
                        throw e // Re-throw to be caught by outer catch block
                    } finally {
                        outputStream?.close()
                    }
                } ?: run {
                    throw Exception("Failed to create new MediaStore entry for log file.")
                }
            } else {
                // Save WAV files to the app-specific directory (existing logic)
                val audioDir = context.getExternalFilesDir(audioDirName.value)
                if (audioDir != null && !audioDir.exists()) {
                    audioDir.mkdirs()
                }
                val file = File(audioDir, fileName)
                FileOutputStream(file).use { it.write(data) }
                addLog("File saved successfully to app-specific directory: ${file.absolutePath}")
                return file.absolutePath
            }
        } catch (e: Exception) {
            addLog("Error saving file: ${e.message}")
            _fileTransferState.value = "Error: ${e.message}"
            null
        }
    }

    private suspend fun cleanupTranscriptionResultsAndAudioFiles() = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            addLog("Running transcription results and audio file cleanup...")
            val limit = transcriptionCacheLimit.value
            val currentTranscriptionResults = transcriptionResults.value.sortedBy { it.timestamp } // Oldest first

            if (currentTranscriptionResults.size > limit) {
                val resultsToDelete = currentTranscriptionResults.take(currentTranscriptionResults.size - limit)
                addLog("Transcription cache limit ($limit) exceeded. Found ${currentTranscriptionResults.size} results. Deleting oldest ${resultsToDelete.size} results and associated audio files...")

                resultsToDelete.forEach { result ->
                    // Delete from DataStore
                    transcriptionResultRepository.removeResult(result)
                    addLog("Deleted transcription result: ${result.fileName}")

                    // Delete associated audio file
                    val audioFile = com.pirorin215.fastrecmob.data.FileUtil.getAudioFile(context, audioDirName.value, result.fileName)
                    if (audioFile.exists()) {
                        if (audioFile.delete()) {
                            addLog("Deleted associated audio file: ${result.fileName}")
                        } else {
                            addLog("Failed to delete associated audio file: ${result.fileName}")
                        }
                    } else {
                        addLog("Associated audio file not found for: ${result.fileName}")
                    }
                }
                addLog("Cleanup finished. Deleted ${resultsToDelete.size} transcription results and audio files.")
            } else {
                addLog("Transcription cache is within limit ($limit). No results or files deleted.")
            }
        } catch (e: Exception) {
            addLog("Error during transcription results and audio file cleanup: ${e.message}")
        } finally {
            updateLocalAudioFileCount() // Ensure count is updated after cleanup
        }
    }




    fun startScan() {
        _logs.value = emptyList()
        addLog("Manual scan button pressed. Waiting for service to find device.")
        // サービスがデバイスを見つけたら、BleScanServiceManager経由でここにイベントが来る
        // Note: The actual scan is handled by BleScanService
    }

    fun restartScan(forceScan: Boolean = false) {
        if (!forceScan && _connectionState.value != "Disconnected") {
            addLog("Not restarting scan, already connected or connecting. (forceScan=false)")
            return
        }

        addLog("Attempting to reconnect or scan...")
        // 1. Try to connect to a bonded device first
        val bondedDevices = bluetoothAdapter?.bondedDevices
        val bondedFastRecDevice = bondedDevices?.find { it.name.equals(DEVICE_NAME, ignoreCase = true) }

        if (bondedFastRecDevice != null) {
            addLog("Found bonded device '${bondedFastRecDevice.name}'. Attempting direct connection.")
            connectToDevice(bondedFastRecDevice)
        } else {
            // 2. If no bonded device is found, start a new scan
            addLog("No bonded device found. Requesting a new scan from the service.")
            viewModelScope.launch {
                com.pirorin215.fastrecmob.BleScanServiceManager.emitRestartScan()
            }
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        addLog("Connecting to device ${device.address}")
        repository.connect(device)
    }

    fun sendCommand(command: String) {
        addLog("Sending command: $command")
        repository.sendCommand(command)
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
                            updateLocalAudioFileCount() // Update count after deletion
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

    fun fetchDeviceInfo() {
        viewModelScope.launch {
            if (connectionState.value != "Connected") {
                addLog("Cannot fetch device info, not connected.")
                return@launch
            }

            bleMutex.withLock {
                if (_currentOperation.value != Operation.IDLE) {
                    addLog("Cannot fetch device info, another operation is in progress: ${_currentOperation.value}")
                    return@withLock
                }

                try {
                    _currentOperation.value = Operation.FETCHING_DEVICE_INFO
                    responseBuffer.clear()
                    addLog("Requesting device info from device...")

                    val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                    currentCommandCompletion = commandCompletion

                    sendCommand("GET:info")

                    val (success, _) = withTimeoutOrNull(15000L) {
                        commandCompletion.await()
                    } ?: Pair(false, null)

                    if (success) {
                        addLog("GET:info command completed successfully.")
                        viewModelScope.launch {
                            locationTracker.getCurrentLocation().onSuccess { locationData ->
                                lastKnownLocationRepository.saveLastKnownLocation(locationData)
                                addLog("Saved last known location on GET:info success: Lat=${locationData.latitude}, Lng=${locationData.longitude}")
                            }.onFailure { e ->
                                addLog("Failed to get or save location on GET:info success: ${e.message}")
                            }
                        }
                    } else {
                        addLog("GET:info command failed or timed out.")
                    }
                } catch (e: Exception) {
                    addLog("An unexpected error occurred during fetchDeviceInfo: ${e.message}")
                } finally {
                    _currentOperation.value = Operation.IDLE
                    currentCommandCompletion = null
                    addLog("fetchDeviceInfo lock released.")
                }
            }
        }
    }

    fun fetchFileList(extension: String = "wav") {
        viewModelScope.launch {
            if (connectionState.value != "Connected") {
                addLog("Cannot fetch file list, not connected.")
                return@launch
            }

            bleMutex.withLock {
                if (_currentOperation.value != Operation.IDLE) {
                    addLog("Cannot fetch file list, another operation is in progress: ${_currentOperation.value}")
                    return@withLock
                }

                try {
                    _currentOperation.value = Operation.FETCHING_FILE_LIST
                    responseBuffer.clear()
                    addLog("Requesting file list from device (GET:ls:$extension)...")

                    val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                    currentCommandCompletion = commandCompletion

                    sendCommand("GET:ls:$extension")

                    val (success, _) = withTimeoutOrNull(15000L) {
                        commandCompletion.await()
                    } ?: Pair(false, null)

                    if (success) {
                        addLog("GET:ls:$extension command completed successfully.")
                        if (extension == "wav") {
                            checkForNewWavFilesAndProcess()
                        }
                    } else {
                        addLog("GET:ls:$extension command failed or timed out.")
                    }
                } catch (e: Exception) {
                    addLog("An unexpected error occurred during fetchFileList: ${e.message}")
                } finally {
                    _currentOperation.value = Operation.IDLE
                    currentCommandCompletion = null
                    addLog("fetchFileList lock released.")
                }
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

            var downloadResult: Pair<Boolean, String?> = Pair(false, null)
            bleMutex.withLock {
                if (_currentOperation.value != Operation.IDLE) {
                    addLog("Cannot download file '$fileName', another operation is in progress: ${_currentOperation.value}")
                    return@withLock
                }

                val operationCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentCommandCompletion = operationCompletion

                try {
                    _currentOperation.value = Operation.DOWNLOADING_FILE
                    _fileTransferState.value = "WaitingForStart"
                    currentDownloadingFileName = fileName

                    val fileEntry = _fileList.value.find { it.name == fileName }
                    val fileSize = fileEntry?.size ?: 0L
                    _currentFileTotalSize.value = fileSize

                    addLog("Requesting file: $fileName (size: $fileSize bytes)")
                    sendCommand("GET:file:$fileName")

                    // Generous timeout: 20s base + 1s per 8KB
                    val timeout = 20000L + (fileSize / 8192L) * 1000L
                    downloadResult = withTimeoutOrNull(timeout) {
                        operationCompletion.await()
                    } ?: Pair(false, null)

                    if (downloadResult.first) {
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
            val (downloadSuccess, savedFilePath) = downloadResult
            if (downloadSuccess && savedFilePath != null) {
                if (fileName.startsWith("log.", ignoreCase = true)) {
                    addLog("Log file '$fileName' downloaded and saved to: $savedFilePath")
                    // No further processing for log files (no transcription, cleanup, or deletion from microcontroller)
                } else if (fileName.endsWith(".wav", ignoreCase = true)) {
                    val file = File(savedFilePath) // Use the directly saved path
                    if (file.exists()) {
                        transcriptionQueue.add(file.absolutePath)
                        addLog("Added ${file.name} to transcription queue.")

                        cleanupTranscriptionResultsAndAudioFiles()
                        updateLocalAudioFileCount()

                        deleteFileOnMicrocontroller(fileName)
                    } else {
                        addLog("Error: Downloaded WAV file not found at ${file.absolutePath}. Cannot queue or delete.")
                    }
                }
            } else {
                addLog("Error: File '$fileName' download failed or saved path is null.")
            }
        }
    }
    
    fun disconnect() {
        addLog("Disconnecting from device")
        resetOperationStates()
        repository.disconnect()
    }

    fun forceReconnectBle() {
        addLog("Force reconnect requested. Disconnecting and attempting to restart scan.")
        viewModelScope.launch {
            disconnect()
            delay(500L) // Give a short delay for the stack to clear
            restartScan(forceScan = true)
        }
    }

    private suspend fun doTranscription(filePath: String) {
        _transcriptionState.value = "Transcribing ${File(filePath).name}"
        _transcriptionResult.value = null
        addLog("Starting transcription for $filePath")

        val currentService = speechToTextService
        val actualFileName = File(filePath).name

        var locationData: LocationData? = null
        // Try to use pre-collected low-power location
        locationData = _currentForegroundLocation.value

        if (locationData != null) {
            addLog("Using pre-collected location for transcription: Lat=${locationData?.latitude}, Lng=${locationData?.longitude}")
        } else {
            // Fallback: Get low power location on-demand if pre-collected is not available
            addLog("Pre-collected location not available. Attempting on-demand low power location for transcription.")
            try {
                locationTracker.getLowPowerLocation().onSuccess {
                    locationData = it
                    addLog("Obtained on-demand low power location for transcription: Lat=${it.latitude}, Lng=${it.longitude}")
                }.onFailure { e ->
                    addLog("Failed to get on-demand low power location for transcription: ${e.message}. Proceeding without location data.")
                }
            } catch (e: SecurityException) {
                addLog("Location permission not granted for transcription. Proceeding without location data.")
            } catch (e: IllegalStateException) {
                addLog("Location services are disabled for transcription. Proceeding without location data.")
            } catch (e: Exception) {
                                addLog("Unexpected error getting on-demand low power location for transcription: ${e.message}. Proceeding without location data.")
                            }
                        } // Missing closing brace for the else block.
                
                        if (currentService == null) {
                            _transcriptionState.value = "Error: APIキーが設定されていません。設定画面で入力してください。"
            _transcriptionState.value = "Error: APIキーが設定されていません。設定画面で入力してください。"
            addLog("Transcription failed: APIキーが設定されていません。")
            val errorResult = TranscriptionResult(actualFileName, "文字起こしエラー: APIキーが設定されていません。設定画面で入力してください。", System.currentTimeMillis(), locationData)
            // Directly await these operations
            transcriptionResultRepository.addResult(errorResult)
            addLog("Transcription error result saved for $actualFileName.")
            cleanupTranscriptionResultsAndAudioFiles()
            return
        }

        val result = currentService.transcribeFile(filePath)

        result.onSuccess { transcription ->
            _transcriptionResult.value = transcription
            addLog("Transcription successful for $filePath.")
            val newResult = TranscriptionResult(actualFileName, transcription, System.currentTimeMillis(), locationData)
            // Directly await these operations
            transcriptionResultRepository.addResult(newResult)
            addLog("Transcription result saved for $actualFileName.")
            cleanupTranscriptionResultsAndAudioFiles()
        }.onFailure { error ->
            val errorMessage = error.message ?: "不明なエラー"
            val displayMessage = if (errorMessage.contains("API key authentication failed") || errorMessage.contains("API key is not set")) {
                "文字起こしエラー: APIキーに問題がある可能性があります。設定画面をご確認ください。詳細: $errorMessage"
            } else {
                "文字起こしエラー: $errorMessage"
            }
            _transcriptionState.value = "Error: $displayMessage"
            _transcriptionResult.value = null
            addLog("Transcription failed for $filePath: $displayMessage")
            val errorResult = TranscriptionResult(actualFileName, displayMessage, System.currentTimeMillis(), locationData)
            // Directly await these operations
            transcriptionResultRepository.addResult(errorResult)
            addLog("Transcription error result saved for $actualFileName.")
            cleanupTranscriptionResultsAndAudioFiles()
        }
    }

    private fun startBatchTranscription() {
        if (transcriptionQueue.isEmpty()) {
            addLog("Transcription queue is empty. Nothing to do.")
            return
        }

        viewModelScope.launch {
            if (!transcriptionBatchMutex.tryLock()) {
                addLog("Batch transcription already in progress. Skipping.")
                return@launch
            }
            try {
                addLog("Found ${transcriptionQueue.size} file(s) in queue. Starting batch transcription.")
                val filesToProcess = transcriptionQueue.toList()
                transcriptionQueue.clear()

                for (filePath in filesToProcess) {
                    doTranscription(filePath)
                }
                addLog("Batch transcription finished.")
                _transcriptionState.value = "Idle" // Reset state after batch is done
                cleanupTranscriptionResultsAndAudioFiles() // Call cleanup after batch transcription
            } finally {
                transcriptionBatchMutex.unlock()
            }
        }
    }

    fun resetTranscriptionState() {
        _transcriptionState.value = "Idle"
        _transcriptionResult.value = null
    }

    override fun onCleared() {
        super.onCleared()
        lowPowerLocationJob?.cancel() // Cancel low power location updates
        repository.disconnect()
        repository.close()
        addLog("ViewModel cleared, resources released.")
    }

    fun updateDisplayOrder(reorderedList: List<TranscriptionResult>) {
        viewModelScope.launch {
            val updatedList = reorderedList.mapIndexed { index, result ->
                result.copy(displayOrder = index)
            }
            transcriptionResultRepository.updateResults(updatedList)
            addLog("Transcription results order updated.")
        }
    }

    fun clearTranscriptionResults() {
        viewModelScope.launch {
            // First, clear all results from the repository (DataStore)
            transcriptionResultRepository.clearResults()
            addLog("All transcription results cleared from DataStore.")

            // Then, delete all associated audio files
            val audioDir = context.getExternalFilesDir(audioDirName.value)
            if (audioDir != null && audioDir.exists()) {
                val audioFiles = audioDir.listFiles { _, name ->
                    // Only delete files matching the expected naming convention
                    name.matches(Regex("""R\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}\.wav"""))
                }
                audioFiles?.forEach { file ->
                    if (file.delete()) {
                        addLog("Deleted audio file during clear all: ${file.name}")
                    } else {
                        addLog("Failed to delete audio file during clear all: ${file.name}")
                    }
                }
                updateLocalAudioFileCount() // Update count after all deletions
            } else {
                addLog("Audio directory not found, no files to clear.")
            }
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("App logs cleared.")
    }

    // 特定の文字起こし結果を削除する関数
    fun removeTranscriptionResult(result: TranscriptionResult) {
        viewModelScope.launch {
            // First, delete from the repository (DataStore)
            transcriptionResultRepository.removeResult(result)
            addLog("Transcription result removed from DataStore: ${result.fileName}")

            // Then, delete the associated audio file
            val audioFile = com.pirorin215.fastrecmob.data.FileUtil.getAudioFile(context, audioDirName.value, result.fileName)
            if (audioFile.exists()) {
                if (audioFile.delete()) {
                    addLog("Associated audio file deleted: ${result.fileName}")
                    updateLocalAudioFileCount() // Update count after deletion
                } else {
                    addLog("Failed to delete associated audio file: ${result.fileName}")
                }
            } else {
                addLog("Associated audio file not found: ${result.fileName}")
            }
        }
    }

    fun updateTranscriptionResult(originalResult: TranscriptionResult, newTranscription: String) {
        viewModelScope.launch {
            // Create a new TranscriptionResult with the updated transcription
            val updatedResult = originalResult.copy(transcription = newTranscription)
            // The addResult method now handles updates, so we just call that.
            transcriptionResultRepository.addResult(updatedResult)
            addLog("Transcription result for ${originalResult.fileName} updated.")
        }
    }

    fun toggleSelection(fileName: String) {
        _selectedFileNames.value = if (_selectedFileNames.value.contains(fileName)) {
            _selectedFileNames.value - fileName
        } else {
            _selectedFileNames.value + fileName
        }
        addLog("Toggled selection for $fileName. Current selections: ${_selectedFileNames.value.size}")
    }

    fun clearSelection() {
        _selectedFileNames.value = emptySet()
        addLog("Cleared selection.")
    }

    fun removeTranscriptionResults(fileNames: Set<String>) {
        viewModelScope.launch {
            val resultsToRemove = transcriptionResults.value.filter { fileNames.contains(it.fileName) }
            resultsToRemove.forEach { result ->
                removeTranscriptionResult(result) // Use the existing single delete function
            }
            clearSelection() // Clear selection after deletion
            addLog("Removed ${resultsToRemove.size} selected transcription results.")
        }
    }

    fun retranscribe(result: TranscriptionResult) {
        viewModelScope.launch {
            addLog("Attempting to retranscribe file: ${result.fileName}")

            // 1. Remove the old result
            transcriptionResultRepository.removeResult(result)
            addLog("Removed old transcription result for ${result.fileName} before re-transcribing.")

            // 2. Get the audio file path
            val audioFile = com.pirorin215.fastrecmob.data.FileUtil.getAudioFile(context, audioDirName.value, result.fileName)
            if (audioFile.exists()) {
                addLog("Audio file found for retranscription: ${audioFile.absolutePath}")
                // 3. Call doTranscription with the file path
                doTranscription(audioFile.absolutePath)
                addLog("Initiated retranscription for ${result.fileName}.")
            } else {
                addLog("Error: Audio file not found for retranscription: ${result.fileName}. Cannot retranscribe.")
            }
        }
    }

    fun addManualTranscription(text: String) {
        viewModelScope.launch {
            var locationData: LocationData? = null

            // Try to use pre-collected low-power location
            locationData = _currentForegroundLocation.value

            if (locationData != null) {
                addLog("Using pre-collected location for manual transcription: Lat=${locationData?.latitude}, Lng=${locationData?.longitude}")
            } else {
                // Fallback: Get low power location on-demand if pre-collected is not available
                addLog("Pre-collected location not available. Attempting on-demand low power location for manual transcription.")
                try {
                    locationTracker.getLowPowerLocation().onSuccess {
                        locationData = it
                        addLog("Obtained on-demand low power location for manual transcription: Lat=${it.latitude}, Lng=${it.longitude}")
                    }.onFailure { e ->
                        addLog("Failed to get on-demand low power location for manual transcription: ${e.message}. Proceeding without location data.")
                    }
                } catch (e: SecurityException) {
                    addLog("Location permission not granted for manual transcription. Proceeding without location data.")
                } catch (e: IllegalStateException) {
                    addLog("Location services are disabled for manual transcription. Proceeding without location data.")
                } catch (e: Exception) {
                    addLog("Unexpected error getting on-demand low power location for manual transcription: ${e.message}. Proceeding without location data.")
                }
            }

            val timestamp = System.currentTimeMillis()
            val manualFileName = "M${com.pirorin215.fastrecmob.data.FileUtil.formatTimestampForFileName(timestamp)}.txt"
            val newResult = TranscriptionResult(manualFileName, text, timestamp, locationData)

            transcriptionResultRepository.addResult(newResult)
            addLog("Manual transcription added: $manualFileName")

            cleanupTranscriptionResultsAndAudioFiles()
        }
    }
}
