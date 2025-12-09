package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.TaskListsResponse
import com.pirorin215.fastrecmob.data.TaskList
import com.pirorin215.fastrecmob.data.TasksResponse
import com.pirorin215.fastrecmob.usecase.GoogleTasksUseCase
import com.pirorin215.fastrecmob.data.Task


import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.app.Application
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.fastrecmob.LocationData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.pirorin215.fastrecmob.viewModel.LocationMonitor
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository
import com.pirorin215.fastrecmob.data.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow


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
    
    // ViewModel's own logs and addLog
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private fun addLog(message: String) {
        Log.d(TAG, message)
        _logs.value = (_logs.value + message).takeLast(100)
    }

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

    private val bleSelectionManager by lazy {
        BleSelectionManager(
            logCallback = { addLog(it) }
        )
    }

    // --- BLE Orchestrator ---
    private val bleOrchestrator by lazy {
        BleOrchestrator(
            scope = viewModelScope,
            context = context,
            repository = repository,
            connectionStateFlow = connectionStateFlow,
            onDeviceReadyEvent = onDeviceReadyEvent,
            transcriptionManager = transcriptionManager,
            locationMonitor = locationMonitor,
            appSettingsRepository = appSettingsRepository,
            bleSelectionManager = bleSelectionManager,
            transcriptionResults = transcriptionResults, // Pass ViewModel's transcriptionResults
            logCallback = { addLog(it) } // Pass ViewModel's addLog to Orchestrator
        )
    }

    // --- State exposed from orchestrator and managers ---
    // val logs = bleOrchestrator.logs // Commented out as ViewModel has its own logs
    val currentOperation = bleOrchestrator.currentOperation
    val navigationEvent = bleOrchestrator.navigationEvent
    
    val audioFileCount = transcriptionManager.audioFileCount
    val transcriptionState = transcriptionManager.transcriptionState
    val transcriptionResult = transcriptionManager.transcriptionResult

    val fileList = bleOrchestrator.fileList
    val deviceInfo = bleOrchestrator.deviceInfo
    val deviceSettings = bleOrchestrator.deviceSettings
    val remoteDeviceSettings = bleOrchestrator.remoteDeviceSettings
    val settingsDiff = bleOrchestrator.settingsDiff

    val isAutoRefreshEnabled = bleOrchestrator.isAutoRefreshEnabled
    val selectedFileNames = bleSelectionManager.selectedFileNames

    val downloadProgress = bleOrchestrator.downloadProgress
    val currentFileTotalSize = bleOrchestrator.currentFileTotalSize
    val fileTransferState = bleOrchestrator.fileTransferState
    val transferKbps = bleOrchestrator.transferKbps

    // --- Location Monitor Delegation ---
    val currentForegroundLocation = locationMonitor.currentForegroundLocation
    fun startLowPowerLocationUpdates() = locationMonitor.startLowPowerLocationUpdates()
    fun stopLowPowerLocationUpdates() = locationMonitor.stopLowPowerLocationUpdates()

    // --- Methods delegated to Orchestrator ---
    fun setAutoRefresh(enabled: Boolean) = bleOrchestrator.setAutoRefresh(enabled)
    fun fetchFileList(extension: String = "wav") = bleOrchestrator.fetchFileList(extension)
    fun getSettings() = bleOrchestrator.getSettings()
    fun applyRemoteSettings() = bleOrchestrator.applyRemoteSettings()
    fun dismissSettingsDiff() = bleOrchestrator.dismissSettingsDiff()
    fun sendSettings() = bleOrchestrator.sendSettings()
    fun updateSettings(updater: (com.pirorin215.fastrecmob.data.DeviceSettings) -> com.pirorin215.fastrecmob.data.DeviceSettings) = bleOrchestrator.updateSettings(updater)
    fun downloadFile(fileName: String) = bleOrchestrator.downloadFile(fileName)
    fun sendCommand(command: String) = bleOrchestrator.sendCommand(command)
    fun clearLogs() = bleOrchestrator.clearLogs() // This will call orchestrator's clearLogs, which in turn logs to ViewModel's addLog

    // --- Methods delegated to Selection Manager ---
    fun toggleSelection(fileName: String) = bleSelectionManager.toggleSelection(fileName)
    fun clearSelection() = bleSelectionManager.clearSelection()

    // --- Methods delegated to Transcription Manager ---
    fun resetTranscriptionState() = transcriptionManager.resetTranscriptionState()
    override fun onCleared() {
        super.onCleared()
        bleOrchestrator.stop()
        transcriptionManager.onCleared()
        locationMonitor.onCleared()
        Log.d(TAG, "ViewModel cleared, resources released.")
    }

    fun updateDisplayOrder(reorderedList: List<TranscriptionResult>) = transcriptionManager.updateDisplayOrder(reorderedList)
    fun clearTranscriptionResults() = transcriptionManager.clearTranscriptionResults()
    fun removeTranscriptionResult(result: TranscriptionResult) = transcriptionManager.removeTranscriptionResult(result)
    fun updateTranscriptionResult(originalResult: TranscriptionResult, newTranscription: String, newNote: String?) = transcriptionManager.updateTranscriptionResult(originalResult, newTranscription, newNote)
    fun removeTranscriptionResults(fileNames: Set<String>) {
        transcriptionManager.removeTranscriptionResults(fileNames) {
            bleSelectionManager.clearSelection()
        }
    }
    fun retranscribe(result: TranscriptionResult) = transcriptionManager.retranscribe(result)
    fun addManualTranscription(text: String) = transcriptionManager.addManualTranscription(text)
}
