package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.TaskListsResponse
import com.pirorin215.fastrecmob.data.TaskList
import com.pirorin215.fastrecmob.data.TasksResponse
import com.pirorin215.fastrecmob.usecase.GoogleTasksUseCase
import com.pirorin215.fastrecmob.data.Task


import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.sync.Mutex

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.ThemeMode
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import com.pirorin215.fastrecmob.LocationData
import com.pirorin215.fastrecmob.LocationTracker

import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient

@SuppressLint("MissingPermission")
class BleViewModel(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val lastKnownLocationRepository: LastKnownLocationRepository,
    private val context: Context,
    private val repository: com.pirorin215.fastrecmob.data.BleRepository,
    private val connectionStateFlow: StateFlow<String>,
    private val onDeviceReadyEvent: SharedFlow<Unit>
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



    private val _receivedData = MutableStateFlow("")
    val receivedData = _receivedData.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _currentOperation = MutableStateFlow(BleOperation.IDLE)
    val currentOperation = _currentOperation.asStateFlow()
    private val bleMutex = Mutex()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    // --- BLE Infrastructure ---



    // --- Location Monitor ---
    private val locationMonitor by lazy {
        LocationMonitor(context, viewModelScope, lastKnownLocationRepository) { addLog(it) }
    }

    // --- Transcription Manager ---
    private val transcriptionManager by lazy {
        TranscriptionManager(
            context = context,
            scope = viewModelScope,
            appSettingsRepository = appSettingsRepository,
            transcriptionResultRepository = transcriptionResultRepository,
            currentForegroundLocationFlow = locationMonitor.currentForegroundLocation,
            audioDirNameFlow = audioDirName,
            transcriptionCacheLimitFlow = transcriptionCacheLimit,
            logCallback = { addLog(it) }
        )
    }

    // --- Managers ---
    private val bleDeviceManager by lazy {
        BleDeviceManager(
            scope = viewModelScope,
            context = context,
            sendCommand = { command -> sendCommand(command) }, // Use the lambda form for sendCommand
            addLog = { addLog(it) },
            _currentOperation = _currentOperation,
            bleMutex = bleMutex,
            onFileListUpdated = { checkForNewWavFilesAndProcess() }
        )
    }


    private val bleSettingsManager by lazy {
        BleSettingsManager(
            scope = viewModelScope,
            sendCommand = { sendCommand(it) },
            addLog = { addLog(it) },
            _currentOperation = _currentOperation,
            _navigationEvent = _navigationEvent
        )
    }

    private val bleAutoRefresher by lazy {
        BleAutoRefresher(
            scope = viewModelScope,
            refreshIntervalSecondsFlow = refreshIntervalSeconds,
            onRefresh = {
                // The mutex in fetchFileList now handles concurrency, so we can call it directly.
                // It will wait if another operation is in progress.
                fetchFileList()

            },
            logCallback = { addLog(it) }
        )
    }

    private val bleSelectionManager by lazy {
        BleSelectionManager(
            logCallback = { addLog(it) }
        )
    }

    val audioFileCount: StateFlow<Int> get() = transcriptionManager.audioFileCount
    val transcriptionState: StateFlow<String> get() = transcriptionManager.transcriptionState
    val transcriptionResult: StateFlow<String?> get() = transcriptionManager.transcriptionResult


    // --- Delegated Properties ---

    val fileList: StateFlow<List<com.pirorin215.fastrecmob.data.FileEntry>> get() = bleDeviceManager.fileList
    
    val deviceSettings: StateFlow<com.pirorin215.fastrecmob.data.DeviceSettings> get() = bleSettingsManager.deviceSettings
    val remoteDeviceSettings: StateFlow<com.pirorin215.fastrecmob.data.DeviceSettings?> get() = bleSettingsManager.remoteDeviceSettings
    val settingsDiff: StateFlow<String?> get() = bleSettingsManager.settingsDiff

    val isAutoRefreshEnabled: StateFlow<Boolean> get() = bleAutoRefresher.isAutoRefreshEnabled
    val selectedFileNames: StateFlow<Set<String>> get() = bleSelectionManager.selectedFileNames


    // --- BleFileTransfer Manager ---
    private val fileTransferManager by lazy {
        FileTransferManager(
            context = context,
            scope = viewModelScope,
            repository = repository,
            transcriptionManager = transcriptionManager,
            audioDirNameFlow = audioDirName,
            bleMutex = bleMutex,
            logCallback = { addLog(it) },
            sendCommandCallback = { command -> sendCommand(command) },
            sendAckCallback = { ackValue -> sendAck(ackValue) },
            _currentOperation = _currentOperation,
            _fileList = bleDeviceManager.fileList, // Use manager's file list

            _connectionState = connectionStateFlow,
            fetchFileListCallback = { fetchFileList() }
        )
    }

    val downloadProgress: StateFlow<Int> get() = fileTransferManager.downloadProgress
    val currentFileTotalSize: StateFlow<Long> get() = fileTransferManager.currentFileTotalSize
    val fileTransferState: StateFlow<String> get() = fileTransferManager.fileTransferState
    val transferKbps: StateFlow<Float> get() = fileTransferManager.transferKbps


    // --- Location Monitor Delegation ---
    val currentForegroundLocation: StateFlow<LocationData?> = locationMonitor.currentForegroundLocation
    fun startLowPowerLocationUpdates() = locationMonitor.startLowPowerLocationUpdates()
    fun stopLowPowerLocationUpdates() = locationMonitor.stopLowPowerLocationUpdates()


    // --- Refactored Properties ---

    init {
        // Observe onDeviceReadyEvent to trigger actions after characteristics are available
        onDeviceReadyEvent
            .onEach {
                addLog("Device ready event received. Triggering initial fetchFileList.")
                fetchFileList()
            }
            .launchIn(viewModelScope)

        // Collect characteristic changes from the repository (this stays in the ViewModel)
        repository.events.onEach { event ->
            when(event) {
                is com.pirorin215.fastrecmob.data.BleEvent.CharacteristicChanged -> {
                    handleCharacteristicChanged(event.characteristic, event.value)
                }
                // Other events are handled by the connection manager
                else -> {}
            }
        }.launchIn(viewModelScope)
    }

    fun setAutoRefresh(enabled: Boolean) {
        bleAutoRefresher.setAutoRefresh(enabled)
    }

    private fun addLog(message: String) {
        Log.d(TAG, message)
        _logs.value = (_logs.value + message).takeLast(100)
    }

    private fun resetOperationStates() {
        addLog("Resetting all operation states.")
        _currentOperation.value = BleOperation.IDLE
        fileTransferManager.resetFileTransferMetrics() // Delegate to manager
    }

    private fun checkForNewWavFilesAndProcess() {
        viewModelScope.launch {


            val currentWavFilesOnMicrocontroller = bleDeviceManager.fileList.value.filter { it.name.endsWith(".wav", ignoreCase = true) }
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
            BleOperation.FETCHING_FILE_LIST -> {
                bleDeviceManager.handleResponse(value)
            }
            BleOperation.FETCHING_SETTINGS -> {
                bleSettingsManager.handleResponse(value)
            }
            BleOperation.DOWNLOADING_FILE, BleOperation.DELETING_FILE -> {
                fileTransferManager.handleCharacteristicChanged(characteristic, value)
            }
            else -> {
                addLog("Received data in unexpected state (${_currentOperation.value}): ${value.toString(Charsets.UTF_8)}")
            }
        }
    }



    fun sendCommand(command: String) {
        addLog("Sending command: $command")
        repository.sendCommand(command)
    }

    private fun sendAck(ackValue: ByteArray) {
        repository.sendAck(ackValue)
    }



    fun fetchFileList(extension: String = "wav") {
        viewModelScope.launch {
            bleDeviceManager.fetchFileList(connectionStateFlow.value, extension)
        }
    }

    fun getSettings() {
        bleSettingsManager.getSettings(connectionStateFlow.value)
    }

    fun applyRemoteSettings() {
        bleSettingsManager.applyRemoteSettings()
    }

    fun dismissSettingsDiff() {
        bleSettingsManager.dismissSettingsDiff()
    }

    fun sendSettings() {
        bleSettingsManager.sendSettings(connectionStateFlow.value)
    }

    fun updateSettings(updater: (com.pirorin215.fastrecmob.data.DeviceSettings) -> com.pirorin215.fastrecmob.data.DeviceSettings) {
        bleSettingsManager.updateSettings(updater)
    }

    fun downloadFile(fileName: String) {
        fileTransferManager.downloadFileAndProcess(fileName)
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
        locationMonitor.onCleared()

        addLog("ViewModel cleared, resources released.")
    }

    fun updateDisplayOrder(reorderedList: List<TranscriptionResult>) {
        transcriptionManager.updateDisplayOrder(reorderedList)
    }

    fun clearTranscriptionResults() {
        transcriptionManager.clearTranscriptionResults()
    }

    fun clearLogs() {
        _logs.value = emptyList()
        addLog("App logs cleared.")
    }

    // 特定の文字起こし結果を削除する関数
    fun removeTranscriptionResult(result: TranscriptionResult) {
        transcriptionManager.removeTranscriptionResult(result)
    }

    fun updateTranscriptionResult(originalResult: TranscriptionResult, newTranscription: String, newNote: String?) {
        transcriptionManager.updateTranscriptionResult(originalResult, newTranscription, newNote)
    }

    fun toggleSelection(fileName: String) {
        bleSelectionManager.toggleSelection(fileName)
    }

    fun clearSelection() {
        bleSelectionManager.clearSelection()
    }

    fun removeTranscriptionResults(fileNames: Set<String>) {
        transcriptionManager.removeTranscriptionResults(fileNames) {
            bleSelectionManager.clearSelection()
        }
    }

    fun retranscribe(result: TranscriptionResult) {
        transcriptionManager.retranscribe(result)
    }

    fun addManualTranscription(text: String) {
        transcriptionManager.addManualTranscription(text)
    }
}
