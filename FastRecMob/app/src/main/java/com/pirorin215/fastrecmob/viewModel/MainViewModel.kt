package com.pirorin215.fastrecmob.viewModel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.pirorin215.fastrecmob.BleScanServiceManager
import com.pirorin215.fastrecmob.MainApplication // Add this import
import com.pirorin215.fastrecmob.data.FileEntry
import com.pirorin215.fastrecmob.data.ThemeMode
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.service.BleScanService
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@SuppressLint("MissingPermission")
class MainViewModel(
    private val application: Application
) : ViewModel() {

    companion object {
        const val TAG = "MainViewModel"
    }

    private val mainApplication = application as MainApplication

    // --- Core components from MainApplication ---
    private val appSettingsRepository = mainApplication.appSettingsRepository
    private val transcriptionResultRepository = mainApplication.transcriptionResultRepository
    private val logManager = mainApplication.logManager
    private val bleConnectionManager = mainApplication.bleConnectionManager
    private val bleOrchestrator = mainApplication.bleOrchestrator
    private val transcriptionManager = mainApplication.transcriptionManager
    private val bleSelectionManager = mainApplication.bleSelectionManager
    private val googleTasksIntegration = mainApplication.googleTasksIntegration
    private val locationMonitor = mainApplication.locationMonitor

    // --- UI State Flows ---
    val themeMode: StateFlow<ThemeMode> = appSettingsRepository.themeModeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ThemeMode.SYSTEM)

    val transcriptionFontSize: StateFlow<Int> = appSettingsRepository.transcriptionFontSizeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 14)

    val audioDirName: StateFlow<String> = appSettingsRepository.audioDirNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "FastRecRecordings")

    val showCompletedGoogleTasks: StateFlow<Boolean> = appSettingsRepository.showCompletedGoogleTasksFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val transcriptionResults: StateFlow<List<TranscriptionResult>> = transcriptionResultRepository.transcriptionResultsFlow
        .map { list -> list.filter { !it.isDeletedLocally } }
        .combine(showCompletedGoogleTasks) { list, showCompleted ->
            if (showCompleted) list else list.filter { !it.isSyncedWithGoogleTasks || it.transcriptionStatus == "FAILED" }
        }
        .map { list -> list.sortedByDescending { com.pirorin215.fastrecmob.data.FileUtil.getTimestampFromFileName(it.fileName) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val transcriptionCount: StateFlow<Int> = transcriptionResults
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val logs = logManager.logs
    val selectedFileNames = bleSelectionManager.selectedFileNames

    // --- State exposed from orchestrator and managers ---
    val connectionState: StateFlow<String> = bleConnectionManager.connectionState
    val currentOperation: StateFlow<BleOperation> = bleOrchestrator.currentOperation
    val navigationEvent: SharedFlow<NavigationEvent> = bleOrchestrator.navigationEvent
    val audioFileCount = transcriptionManager.audioFileCount
    val transcriptionState = transcriptionManager.transcriptionState
    val transcriptionResult = transcriptionManager.transcriptionResult
    val fileList: StateFlow<List<FileEntry>> = bleOrchestrator.fileList
    val deviceInfo: StateFlow<com.pirorin215.fastrecmob.data.DeviceInfoResponse?> = bleOrchestrator.deviceInfo
    val deviceSettings: StateFlow<com.pirorin215.fastrecmob.data.DeviceSettings> = bleOrchestrator.deviceSettings
    val remoteDeviceSettings: StateFlow<com.pirorin215.fastrecmob.data.DeviceSettings?> = bleOrchestrator.remoteDeviceSettings
    val settingsDiff: StateFlow<String?> = bleOrchestrator.settingsDiff
    val isAutoRefreshEnabled: StateFlow<Boolean> = bleOrchestrator.isAutoRefreshEnabled
    val downloadProgress: StateFlow<Int> = bleOrchestrator.downloadProgress
    val currentFileTotalSize: StateFlow<Long> = bleOrchestrator.currentFileTotalSize
    val fileTransferState: StateFlow<String> = bleOrchestrator.fileTransferState
    val transferKbps: StateFlow<Float> = bleOrchestrator.transferKbps
    val currentForegroundLocation = locationMonitor.currentForegroundLocation
    
    // --- Audio Player Manager (scoped to ViewModel) ---
    private val audioPlayerManager: AudioPlayerManager by lazy {
        AudioPlayerManager(application)
    }
    val currentlyPlayingFile: StateFlow<String?> = audioPlayerManager.currentlyPlayingFile

    // --- Google Tasks State & Methods ---
    val account: StateFlow<GoogleSignInAccount?> = googleTasksIntegration.account
    val isLoadingGoogleTasks: StateFlow<Boolean> = googleTasksIntegration.isLoadingGoogleTasks
    fun handleSignInResult(intent: Intent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) = googleTasksIntegration.handleSignInResult(intent, onSuccess, onFailure)
    fun signOut() = googleTasksIntegration.signOut()
    suspend fun getGoogleSignInIntent(): Intent? = googleTasksIntegration.googleSignInClient.firstOrNull()?.signInIntent
    fun syncTranscriptionResultsWithGoogleTasks() = viewModelScope.launch {
        googleTasksIntegration.syncTranscriptionResultsWithGoogleTasks(audioDirName.value)
    }

    // --- Methods delegated to orchestrator and managers ---
    fun setAutoRefresh(enabled: Boolean) = bleOrchestrator.setAutoRefresh(enabled)
    fun fetchFileList(extension: String = "wav") = bleOrchestrator.fetchFileList(extension)
    suspend fun getSettings() = bleOrchestrator.getSettings()
    fun applyRemoteSettings() = bleOrchestrator.applyRemoteSettings()
    fun dismissSettingsDiff() = bleOrchestrator.dismissSettingsDiff()
    fun sendSettings() = bleOrchestrator.sendSettings()
    fun updateSettings(updater: (com.pirorin215.fastrecmob.data.DeviceSettings) -> com.pirorin215.fastrecmob.data.DeviceSettings) = bleOrchestrator.updateSettings(updater)
    fun downloadFile(fileName: String) = bleOrchestrator.downloadFile(fileName)
    fun sendCommand(command: String) = bleOrchestrator.sendCommand(command)
    fun clearLogs() = logManager.clearLogs()
    fun forceReconnectBle() = bleConnectionManager.forceReconnect()
    fun toggleSelection(fileName: String) = bleSelectionManager.toggleSelection(fileName)
    fun clearSelection() = bleSelectionManager.clearSelection()

    // --- Location Monitor Delegation ---
    fun startLowPowerLocationUpdates() = locationMonitor.startLowPowerLocationUpdates()
    fun stopLowPowerLocationUpdates() = locationMonitor.stopLowPowerLocationUpdates()

    // --- Methods delegated to Transcription Manager ---
    fun resetTranscriptionState() = transcriptionManager.resetTranscriptionState()
    fun playAudioFile(transcriptionResult: TranscriptionResult) {
        viewModelScope.launch {
            val audioDir = appSettingsRepository.audioDirNameFlow.firstOrNull() ?: "FastRecRecordings"
            val fileToPlay = com.pirorin215.fastrecmob.data.FileUtil.getAudioFile(application, audioDir, transcriptionResult.fileName)
            if (fileToPlay.exists()) {
                audioPlayerManager.play(fileToPlay.absolutePath)
                logManager.addLog("Audio playback requested for: ${transcriptionResult.fileName}")
            } else {
                logManager.addLog("Error: Audio file not found for playback: ${transcriptionResult.fileName}")
            }
        }
    }
    fun stopAudioFile() {
        audioPlayerManager.stop()
        logManager.addLog("Audio playback stopped.")
    }
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
    
    fun stopAppServices() {
        Log.d(TAG, "Stopping all app services...")
        // Stop BLE connection and release resources
        bleConnectionManager.disconnect()
        bleConnectionManager.close()
        
        // Stop the background BLE scanning service
        val serviceIntent = Intent(application, BleScanService::class.java)
        application.stopService(serviceIntent)
        Log.d(TAG, "BleScanService stopped.")

        // Stop transcription processes and release resources
        transcriptionManager.resetTranscriptionState()
        Log.d(TAG, "TranscriptionManager state reset.")

        // Stop location updates
        locationMonitor.stopLowPowerLocationUpdates()
        Log.d(TAG, "Location updates stopped.")

        // Stop audio playback and release resources
        audioPlayerManager.stop()
        audioPlayerManager.release()
        Log.d(TAG, "Audio player released.")

        logManager.addLog("All services stopped. App is shutting down.")
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayerManager.release() // Release player resources
        Log.d(TAG, "ViewModel cleared.")
    }
}