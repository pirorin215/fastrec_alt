package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.TaskListsResponse
import com.pirorin215.fastrecmob.data.TaskList
import com.pirorin215.fastrecmob.data.TasksResponse
import com.pirorin215.fastrecmob.usecase.GoogleTasksUseCase
import com.pirorin215.fastrecmob.data.Task


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
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.app.Application
import android.content.Intent
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
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.ThemeMode
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import com.pirorin215.fastrecmob.LocationData

import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository
import com.pirorin215.fastrecmob.service.SpeechToTextService
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope

@SuppressLint("MissingPermission")
class BleViewModel(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val lastKnownLocationRepository: LastKnownLocationRepository,
    private val context: Context
) : ViewModel() {

    companion object {
        const val TAG = "BleViewModel"
        const val DEVICE_NAME = "fastrec"
        const val COMMAND_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26aa"
        const val RESPONSE_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ab"
        const val ACK_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ac"
        const val CCCD_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb"
        const val SERVICE_UUID = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
        const val MAX_DELETE_RETRIES = 3
        const val DELETE_RETRY_DELAY_MS = 1000L // 1 second
        const val TIME_SYNC_INTERVAL_MS = 300000L // 5 minutes
    }

    // --- UseCase Instances ---
    private val googleTasksUseCase = GoogleTasksUseCase(application, appSettingsRepository, transcriptionResultRepository, context)

    // --- Exposing UseCase State ---
    val account: StateFlow<GoogleSignInAccount?> = googleTasksUseCase.account
    val isLoadingGoogleTasks: StateFlow<Boolean> = googleTasksUseCase.isLoadingGoogleTasks
    val googleSignInClient: GoogleSignInClient = googleTasksUseCase.googleSignInClient

    // --- Google Tasks Delegated Functions ---
    fun syncTranscriptionResultsWithGoogleTasks() = viewModelScope.launch {
        googleTasksUseCase.syncTranscriptionResultsWithGoogleTasks(audioDirName.value)
        transcriptionManager.updateLocalAudioFileCount()
    }

    fun handleSignInResult(intent: Intent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        googleTasksUseCase.handleSignInResult(intent, {
            viewModelScope.launch {
                withContext(Dispatchers.Main) { onSuccess() }
            }
        }, onFailure)
    }

    fun signOut() {
        googleTasksUseCase.signOut()
    }
    // --- End Google Tasks Delegated Functions ---


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
        .map { list -> list.filter { !it.isDeletedLocally } } // Filter out soft-deleted items
        .combine(sortMode) { list: List<TranscriptionResult>, mode: com.pirorin215.fastrecmob.data.SortMode ->
            when (mode) {
                com.pirorin215.fastrecmob.data.SortMode.TIMESTAMP -> list.sortedByDescending { it.lastEditedTimestamp }
            com.pirorin215.fastrecmob.data.SortMode.CREATION_TIME -> list.sortedByDescending { com.pirorin215.fastrecmob.data.FileUtil.getTimestampFromFileName(it.fileName) }
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

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val locationTracker = com.pirorin215.fastrecmob.LocationTracker(context)

    // --- Transcription Manager ---
    private val transcriptionManager by lazy {
        TranscriptionManager(
            context = context,
            scope = viewModelScope,
            appSettingsRepository = appSettingsRepository,
            transcriptionResultRepository = transcriptionResultRepository,
            locationTracker = locationTracker,
            currentForegroundLocationFlow = currentForegroundLocation,
            audioDirNameFlow = audioDirName,
            transcriptionCacheLimitFlow = transcriptionCacheLimit,
            logCallback = { addLog(it) }
        )
    }

    val audioFileCount: StateFlow<Int> get() = transcriptionManager.audioFileCount
    val transcriptionState: StateFlow<String> get() = transcriptionManager.transcriptionState
    val transcriptionResult: StateFlow<String?> get() = transcriptionManager.transcriptionResult
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState = _connectionState.asStateFlow()

    private val _receivedData = MutableStateFlow("")
    val receivedData = _receivedData.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfoResponse?>(null)
    val deviceInfo = _deviceInfo.asStateFlow()
    
    // --- Managers ---
    private val _currentOperation = MutableStateFlow(BleOperation.IDLE)
    val currentOperation = _currentOperation.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    private val bleSettingsManager by lazy {
        BleSettingsManager(
            scope = viewModelScope,
            sendCommand = { sendCommand(it) },
            addLog = { addLog(it) },
            _currentOperation = _currentOperation,
            _navigationEvent = _navigationEvent
        )
    }

    // --- Settings Delegated Properties ---
    val deviceSettings: StateFlow<com.pirorin215.fastrecmob.data.DeviceSettings> get() = bleSettingsManager.deviceSettings
    val remoteDeviceSettings: StateFlow<com.pirorin215.fastrecmob.data.DeviceSettings?> get() = bleSettingsManager.remoteDeviceSettings
    val settingsDiff: StateFlow<String?> get() = bleSettingsManager.settingsDiff

    private val _fileList = MutableStateFlow<List<com.pirorin215.fastrecmob.data.FileEntry>>(emptyList())
    val fileList = _fileList.asStateFlow()

    // --- BleFileTransfer Manager ---
    private val bleFileTransferManager by lazy {
        BleFileTransferManager(
            context = context,
            scope = viewModelScope,
            repository = repository,
            transcriptionManager = transcriptionManager,
            audioDirNameFlow = audioDirName,
            logCallback = { addLog(it) },
            sendCommandCallback = { command -> sendCommand(command) },
            sendAckCallback = { ackValue -> sendAck(ackValue) },
            _currentOperation = _currentOperation,
            _fileList = _fileList,
            _connectionState = _connectionState
        )
    }

    val downloadProgress: StateFlow<Int> get() = bleFileTransferManager.downloadProgress
    val currentFileTotalSize: StateFlow<Long> get() = bleFileTransferManager.currentFileTotalSize
    val fileTransferState: StateFlow<String> get() = bleFileTransferManager.fileTransferState
    val transferKbps: StateFlow<Float> get() = bleFileTransferManager.transferKbps


    private val _isAutoRefreshEnabled = MutableStateFlow(true)
    val isAutoRefreshEnabled = _isAutoRefreshEnabled.asStateFlow()

    private val _selectedFileNames = MutableStateFlow<Set<String>>(emptySet())
    val selectedFileNames: StateFlow<Set<String>> = _selectedFileNames.asStateFlow()


    // --- New properties for pre-collected location ---
    private val _currentForegroundLocation = MutableStateFlow<LocationData?>(null)
    val currentForegroundLocation: StateFlow<LocationData?> = _currentForegroundLocation.asStateFlow()
    private var lowPowerLocationJob: Job? = null
    // --- End new properties ---

    // --- Refactored Properties ---
    private val repository: com.pirorin215.fastrecmob.data.BleRepository = com.pirorin215.fastrecmob.data.BleRepository(context)
    private var currentCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>? = null // Declare this here
    private var currentDeleteCompletion: CompletableDeferred<Boolean>? = null // Add this

    private val json = Json { ignoreUnknownKeys = true }
    private val bleMutex = Mutex()

    private var autoRefreshJob: Job? = null
    private var responseBuffer = mutableListOf<Byte>()


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
                            _currentOperation.value = BleOperation.SENDING_TIME // New operation state
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
                            _currentOperation.value = BleOperation.IDLE
                        }

                        // Launch fetches in separate jobs so they don't block the `onReady` handler
                        fetchDeviceInfo()
                        fetchFileList()


                        // Start periodic time synchronization
                        timeSyncJob?.cancel() // Cancel any existing job
                        timeSyncJob = viewModelScope.launch {
                            while (true) {
                                delay(TIME_SYNC_INTERVAL_MS)
                                if (connectionState.value == "Connected" && _currentOperation.value == BleOperation.IDLE) {
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
        _currentOperation.value = BleOperation.IDLE
        bleFileTransferManager.resetFileTransferMetrics() // Delegate to manager
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
                if (transcriptionManager.transcriptionQueue.isEmpty()) {
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
            BleOperation.FETCHING_DEVICE_INFO -> {
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
            BleOperation.FETCHING_FILE_LIST -> {
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
            BleOperation.FETCHING_SETTINGS -> {
                bleSettingsManager.handleResponse(value)
            }
            BleOperation.DOWNLOADING_FILE -> {
                bleFileTransferManager.handleCharacteristicChanged(
                    characteristic, value, currentCommandCompletion, currentDeleteCompletion
                )
            }
            BleOperation.DELETING_FILE -> {
                bleFileTransferManager.handleCharacteristicChanged(
                    characteristic, value, currentCommandCompletion, currentDeleteCompletion
                )
            }
            BleOperation.SENDING_TIME -> { // Handle response for SET:time
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

    private fun sendAck(ackValue: ByteArray) {
        repository.sendAck(ackValue)
    }

    fun deleteFileOnMicrocontroller(fileName: String) {
        viewModelScope.launch {
            val deleteCompletion = CompletableDeferred<Boolean>()
            currentDeleteCompletion = deleteCompletion // Set the completion for the ViewModel

            bleFileTransferManager.deleteFileOnMicrocontroller(
                fileName = fileName,
                bleMutex = bleMutex,
                currentDeleteCompletion = deleteCompletion,
                fetchFileListCallback = { fetchFileList() } // Pass a callback to fetch file list
            )
        }
    }

    fun fetchDeviceInfo() {
        viewModelScope.launch {
            if (connectionState.value != "Connected") {
                addLog("Cannot fetch device info, not connected.")
                return@launch
            }

            bleMutex.withLock {
                if (_currentOperation.value != BleOperation.IDLE) {
                    addLog("Cannot fetch device info, another operation is in progress: ${_currentOperation.value}")
                    return@withLock
                }

                try {
                    _currentOperation.value = BleOperation.FETCHING_DEVICE_INFO
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
                    _currentOperation.value = BleOperation.IDLE
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
                if (_currentOperation.value != BleOperation.IDLE) {
                    addLog("Cannot fetch file list, another operation is in progress: ${_currentOperation.value}")
                    return@withLock
                }

                try {
                    _currentOperation.value = BleOperation.FETCHING_FILE_LIST
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
                    _currentOperation.value = BleOperation.IDLE
                    currentCommandCompletion = null
                    addLog("fetchFileList lock released.")
                }
            }
        }
    }

    fun getSettings() {
        bleSettingsManager.getSettings(connectionState.value)
    }

    fun applyRemoteSettings() {
        bleSettingsManager.applyRemoteSettings()
    }

    fun dismissSettingsDiff() {
        bleSettingsManager.dismissSettingsDiff()
    }

    fun sendSettings() {
        bleSettingsManager.sendSettings(connectionState.value)
    }

    fun updateSettings(updater: (com.pirorin215.fastrecmob.data.DeviceSettings) -> com.pirorin215.fastrecmob.data.DeviceSettings) {
        bleSettingsManager.updateSettings(updater)
    }

    fun downloadFile(fileName: String) {
        viewModelScope.launch {
            if (connectionState.value != "Connected") {
                addLog("Cannot download file, not connected.")
                return@launch
            }

            val operationCompletion = CompletableDeferred<Pair<Boolean, String?>>()
            currentCommandCompletion = operationCompletion // Set the completion for the ViewModel

            // Delegate the core download logic to the BleFileTransferManager
            // This function handles acquiring mutex, sending command, and receiving data.
            // It will also complete 'operationCompletion' once the file is downloaded or an error occurs.
            bleFileTransferManager.downloadFile(fileName, bleMutex, operationCompletion)

            // Await the result from the operationCompletion, which will be completed by
            // BleFileTransferManager's handleCharacteristicChanged when the download is finished.
            val (downloadSuccess, savedFilePath) = operationCompletion.await()

            if (downloadSuccess && savedFilePath != null) {
                if (fileName.startsWith("log.", ignoreCase = true)) {
                    addLog("Log file '$fileName' downloaded and saved to: $savedFilePath")
                    // No further processing for log files (no transcription, cleanup, or deletion from microcontroller)
                } else if (fileName.endsWith(".wav", ignoreCase = true)) {
                    val file = File(savedFilePath) // Use the directly saved path
                    if (file.exists()) {
                        transcriptionManager.transcriptionQueue.add(file.absolutePath)
                        addLog("Added ${file.name} to transcription queue.")

                        transcriptionManager.cleanupTranscriptionResultsAndAudioFiles()
                        transcriptionManager.updateLocalAudioFileCount()

                        // This will be delegated in the next step
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

    private fun startBatchTranscription() {
        transcriptionManager.startBatchTranscription()
    }

    fun resetTranscriptionState() {
        transcriptionManager.resetTranscriptionState()
    }

    override fun onCleared() {
        super.onCleared()
        transcriptionManager.onCleared()
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
            addLog("Starting clear all transcription results...")
            val currentTranscriptionResults = transcriptionResults.value

            val updatedListForRepo = mutableListOf<TranscriptionResult>()

            // Process existing results
            currentTranscriptionResults.forEach { result ->
                if (result.googleTaskId == null) {
                    // Local-only item: permanently delete now
                    transcriptionResultRepository.permanentlyRemoveResult(result) // This will remove it from the DataStore
                    val audioFile = com.pirorin215.fastrecmob.data.FileUtil.getAudioFile(context, audioDirName.value, result.fileName)
                    if (audioFile.exists()) {
                        if (audioFile.delete()) {
                            addLog("Deleted local-only audio file during clear all: ${result.fileName}")
                        } else {
                            addLog("Failed to delete local-only audio file during clear all: ${result.fileName}")
                        }
                    }
                } else {
                    // Synced item: soft delete (mark for remote deletion during next sync)
                    updatedListForRepo.add(result.copy(isDeletedLocally = true))
                }
            }
            transcriptionResultRepository.updateResults(updatedListForRepo)

            addLog("All local-only transcription results permanently deleted. Synced results marked for soft deletion.")
            transcriptionManager.updateLocalAudioFileCount() // Update count after deletions
        }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("App logs cleared.")
    }

    // 特定の文字起こし結果を削除する関数
    fun removeTranscriptionResult(result: TranscriptionResult) {
        viewModelScope.launch {
            if (result.googleTaskId != null) {
                // If synced with Google Tasks, perform a soft delete locally.
                // Actual deletion from Google Tasks and permanent local deletion will happen during sync.
                transcriptionResultRepository.removeResult(result) // This now sets isDeletedLocally = true
                addLog("Soft-deleted transcription result (synced with Google Tasks): ${result.fileName}")
            } else {
                // If not synced with Google Tasks, permanently delete locally and its audio file immediately.
                transcriptionResultRepository.permanentlyRemoveResult(result)
                addLog("Permanently deleted local-only transcription result: ${result.fileName}")

                val audioFile = com.pirorin215.fastrecmob.data.FileUtil.getAudioFile(context, audioDirName.value, result.fileName)
                if (audioFile.exists()) {
                    if (audioFile.delete()) {
                        addLog("Associated audio file deleted: ${result.fileName}")
                        transcriptionManager.updateLocalAudioFileCount()
                    } else {
                        addLog("Failed to delete associated audio file: ${result.fileName}")
                    }
                } else {
                    addLog("Associated audio file not found for local-only result: ${result.fileName}")
                }
            }
        }
    }

    fun updateTranscriptionResult(originalResult: TranscriptionResult, newTranscription: String, newNote: String?) {
        viewModelScope.launch {
            // Create a new TranscriptionResult with the updated transcription and notes
            val updatedResult = originalResult.copy(
                transcription = newTranscription,
                googleTaskNotes = newNote, // Update googleTaskNotes
                lastEditedTimestamp = System.currentTimeMillis()
            )
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
        transcriptionManager.retranscribe(result)
    }

    fun addManualTranscription(text: String) {
        transcriptionManager.addManualTranscription(text)
    }
}