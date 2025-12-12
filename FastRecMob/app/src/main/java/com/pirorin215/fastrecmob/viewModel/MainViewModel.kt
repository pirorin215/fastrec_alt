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
import android.media.MediaPlayer
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.emptyFlow
import com.pirorin215.fastrecmob.data.ThemeMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.pirorin215.fastrecmob.viewModel.LogManager


@SuppressLint("MissingPermission")
class MainViewModel(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository, // Keep this
    private val lastKnownLocationRepository: LastKnownLocationRepository,
    private val repository: com.pirorin215.fastrecmob.data.BleRepository,
    private val connectionStateFlow: StateFlow<String>,
    private val onDeviceReadyEvent: SharedFlow<Unit>,
    private val logManager: LogManager,
    private val locationTracker: com.pirorin215.fastrecmob.LocationTracker
) : ViewModel() {

    companion object {
        const val TAG = "MainViewModel"
    }

    private val appSettingsAccessor: AppSettingsAccessor by lazy {
        AppSettingsViewModelDelegate(appSettingsRepository, viewModelScope)
    }

    val apiKey: StateFlow<String> = appSettingsAccessor.apiKey
    val refreshIntervalSeconds: StateFlow<Int> = appSettingsAccessor.refreshIntervalSeconds
    val transcriptionCacheLimit: StateFlow<Int> = appSettingsAccessor.transcriptionCacheLimit
    val transcriptionFontSize: StateFlow<Int> = appSettingsAccessor.transcriptionFontSize
    val audioDirName: StateFlow<String> = appSettingsAccessor.audioDirName
    val themeMode: StateFlow<ThemeMode> = appSettingsAccessor.themeMode
    val sortMode: StateFlow<com.pirorin215.fastrecmob.data.SortMode> = appSettingsAccessor.sortMode
    val googleTodoListName: StateFlow<String> = appSettingsAccessor.googleTodoListName
    val googleTaskTitleLength: StateFlow<Int> = appSettingsAccessor.googleTaskTitleLength
    val googleTasksSyncIntervalMinutes: StateFlow<Int> = appSettingsAccessor.googleTasksSyncIntervalMinutes

    init {
        // Start periodic Google Tasks sync
        viewModelScope.launch {
            googleTasksSyncIntervalMinutes.flatMapLatest { minutes ->
                if (minutes > 0) {
                    // Create a flow that emits every `minutes`
                    flow {
                        while(true) {
                            delay(minutes * 60 * 1000L)
                            emit(minutes)
                        }
                    }
                } else {
                    // If minutes is 0 or less, return an empty flow that does nothing
                    emptyFlow()
                }
            }.collect { minuteValue ->
                logManager.addLog("Triggering periodic Google Tasks sync ($minuteValue min).")
                googleTasksIntegration.syncTranscriptionResultsWithGoogleTasks(audioDirName.value)
            }
        }
    }

    // --- Google Tasks Manager ---
    private val googleTasksIntegration: GoogleTasksIntegration by lazy {
        GoogleTasksManager(
            application = application,
            appSettingsRepository = appSettingsRepository,
            transcriptionResultRepository = transcriptionResultRepository,
            context = application,
            scope = viewModelScope,
            logManager = logManager
        )
    }

    // --- Exposing Google Tasks Manager State ---
    val account: StateFlow<GoogleSignInAccount?> = googleTasksIntegration.account
    val isLoadingGoogleTasks: StateFlow<Boolean> = googleTasksIntegration.isLoadingGoogleTasks
    val googleSignInClient: SharedFlow<GoogleSignInClient> = googleTasksIntegration.googleSignInClient

    // --- Google Tasks Delegated Functions ---
    fun syncTranscriptionResultsWithGoogleTasks() = googleTasksIntegration.syncTranscriptionResultsWithGoogleTasks(audioDirName.value)
    fun handleSignInResult(intent: Intent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) = googleTasksIntegration.handleSignInResult(intent, onSuccess, onFailure)
    fun signOut() = googleTasksIntegration.signOut()

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
    
    val logs = logManager.logs

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private var currentMediaPlayer: MediaPlayer? = null


    // --- Location Monitor ---
    private val locationMonitor: LocationTracking by lazy {
        LocationMonitor(application, viewModelScope, lastKnownLocationRepository, logManager)
    }

    // --- Transcription Manager ---
    val transcriptionManager: TranscriptionManagement by lazy {
        TranscriptionManager(
            context = application,
            scope = viewModelScope,
            appSettingsRepository = appSettingsRepository,
            transcriptionResultRepository = transcriptionResultRepository,
            currentForegroundLocationFlow = locationMonitor.currentForegroundLocation,
            audioDirNameFlow = audioDirName,
            transcriptionCacheLimitFlow = transcriptionCacheLimit,
            logManager = logManager,
            googleTaskTitleLengthFlow = appSettingsAccessor.googleTaskTitleLength, // Pass the new parameter
            googleTasksIntegration = googleTasksIntegration,
            locationTracker = locationTracker
        )
    }

    private val bleSelectionManager: BleSelection by lazy {
        BleSelectionManager(
            logManager = logManager
        )
    }

    val selectedFileNames = bleSelectionManager.selectedFileNames

    // --- Methods delegated to Selection Manager ---
    fun toggleSelection(fileName: String) = bleSelectionManager.toggleSelection(fileName)
    fun clearSelection() = bleSelectionManager.clearSelection()

    // --- BLE Orchestrator ---
    private val bleOrchestrator: BleOrchestration by lazy {
        BleOrchestrator(
            scope = viewModelScope,
            context = application,
            repository = repository,
            connectionStateFlow = connectionStateFlow,
            onDeviceReadyEvent = onDeviceReadyEvent,
            transcriptionManager = transcriptionManager,
            locationMonitor = locationMonitor,
            appSettingsRepository = appSettingsRepository,
            bleSelectionManager = bleSelectionManager,
            transcriptionResults = transcriptionResults, // Pass ViewModel's transcriptionResults
            logManager = logManager
        )
    }

    // --- State exposed from orchestrator and managers ---
    // val logs = bleOrchestrator.logs // Commented out as ViewModel has its own logs
    val currentOperation: StateFlow<BleOperation> = bleOrchestrator.currentOperation
    val navigationEvent: SharedFlow<NavigationEvent> = bleOrchestrator.navigationEvent
    
    val audioFileCount = transcriptionManager.audioFileCount
    val transcriptionState = transcriptionManager.transcriptionState
    val transcriptionResult = transcriptionManager.transcriptionResult

    val fileList: StateFlow<List<com.pirorin215.fastrecmob.data.FileEntry>> = bleOrchestrator.fileList
    val deviceInfo: StateFlow<com.pirorin215.fastrecmob.data.DeviceInfoResponse?> = bleOrchestrator.deviceInfo
    val deviceSettings: StateFlow<com.pirorin215.fastrecmob.data.DeviceSettings> = bleOrchestrator.deviceSettings
    val remoteDeviceSettings: StateFlow<com.pirorin215.fastrecmob.data.DeviceSettings?> = bleOrchestrator.remoteDeviceSettings
    val settingsDiff: StateFlow<String?> = bleOrchestrator.settingsDiff

    val isAutoRefreshEnabled: StateFlow<Boolean> = bleOrchestrator.isAutoRefreshEnabled


    val downloadProgress: StateFlow<Int> = bleOrchestrator.downloadProgress
    val currentFileTotalSize: StateFlow<Long> = bleOrchestrator.currentFileTotalSize
    val fileTransferState: StateFlow<String> = bleOrchestrator.fileTransferState
    val transferKbps: StateFlow<Float> = bleOrchestrator.transferKbps

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

    // --- Methods delegated to Transcription Manager ---
    fun resetTranscriptionState() = transcriptionManager.resetTranscriptionState()
    fun playAudioFile(transcriptionResult: TranscriptionResult) {
        // Stop any currently playing audio
        stopAudioFile()

        val fileToPlay = com.pirorin215.fastrecmob.data.FileUtil.getAudioFile(application, audioDirName.value, transcriptionResult.fileName)
        if (fileToPlay.exists()) {
            try {
                currentMediaPlayer = MediaPlayer().apply {
                    setDataSource(fileToPlay.absolutePath)
                    prepare()
                    start()
                    _isPlaying.value = true
                    logManager.addLog("Started internal audio playback for: ${transcriptionResult.fileName}")

                    setOnCompletionListener { mp ->
                        mp.release()
                        currentMediaPlayer = null
                        _isPlaying.value = false
                        logManager.addLog("Audio playback completed and resources released for: ${transcriptionResult.fileName}")
                    }
                    setOnErrorListener { mp, what, extra ->
                        logManager.addLog("Error during audio playback (what: $what, extra: $extra) for: ${transcriptionResult.fileName}")
                        mp.release()
                        currentMediaPlayer = null
                        _isPlaying.value = false
                        false // Indicate that the error was handled
                    }
                }
            } catch (e: Exception) {
                logManager.addLog("Error playing audio file internally: ${e.message}")
                e.printStackTrace()
                _isPlaying.value = false
                currentMediaPlayer?.release()
                currentMediaPlayer = null
            }
        } else {
            logManager.addLog("Error: Audio file not found for playback: ${transcriptionResult.fileName}")
            _isPlaying.value = false
        }
    }

    fun stopAudioFile() {
        currentMediaPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        currentMediaPlayer = null
        _isPlaying.value = false
        logManager.addLog("Audio playback stopped and resources released.")
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioFile() // Ensure MediaPlayer resources are released
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