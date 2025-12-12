package com.pirorin215.fastrecmob.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.service.SpeechToTextService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted // Add this import
import kotlinx.coroutines.flow.stateIn // Add this import
import com.pirorin215.fastrecmob.data.ThemeMode // Add this import

enum class ApiKeyStatus {
    VALID,
    INVALID,
    EMPTY,
    CHECKING,
    UNKNOWN_ERROR
}

class AppSettingsViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionManager: TranscriptionManagement, // Added
    private val application: Application
) : ViewModel() {

    private val _apiKeyStatus = MutableStateFlow(ApiKeyStatus.CHECKING)
    val apiKeyStatus: StateFlow<ApiKeyStatus> = _apiKeyStatus.asStateFlow()

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

    val themeMode: StateFlow<ThemeMode> = appSettingsRepository.themeModeFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM // Default to SYSTEM
        )

    val googleTodoListName: StateFlow<String> = appSettingsRepository.googleTodoListNameFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "fastrec"
        )

    val googleTaskTitleLength: StateFlow<Int> = appSettingsRepository.googleTaskTitleLengthFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 20 // Default to 20
        )

    val googleTasksSyncIntervalMinutes: StateFlow<Int> = appSettingsRepository.googleTasksSyncIntervalMinutesFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 5 // Default to 5 minutes
        )

    val showCompletedGoogleTasks: StateFlow<Boolean> = appSettingsRepository.showCompletedGoogleTasksFlow
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    fun saveApiKey(apiKey: String) {
        viewModelScope.launch {
            appSettingsRepository.saveApiKey(apiKey)
        }
    }

    fun saveRefreshInterval(seconds: Int) {
        viewModelScope.launch {
            val interval = if (seconds < 5) 5 else seconds
            appSettingsRepository.saveRefreshIntervalSeconds(interval)
        }
    }

    fun saveTranscriptionCacheLimit(limit: Int) {
        viewModelScope.launch {
            val cacheLimit = if (limit < 1) 1 else limit
            appSettingsRepository.saveTranscriptionCacheLimit(cacheLimit)
        }
    }

    fun saveTranscriptionFontSize(size: Int) {
        viewModelScope.launch {
            val fontSize = size.coerceIn(10, 24)
            appSettingsRepository.saveTranscriptionFontSize(fontSize)
        }
    }

    fun saveThemeMode(themeMode: ThemeMode) {
        viewModelScope.launch {
            appSettingsRepository.saveThemeMode(themeMode)
        }
    }

    fun saveGoogleTodoListName(name: String) {
        viewModelScope.launch {
            appSettingsRepository.saveGoogleTodoListName(name)
        }
    }

    fun saveGoogleTaskTitleLength(length: Int) {
        viewModelScope.launch {
            val titleLength = length.coerceIn(5, 50) // Example bounds: 5 to 50 characters
            appSettingsRepository.saveGoogleTaskTitleLength(titleLength)
        }
    }

    fun saveGoogleTasksSyncIntervalMinutes(minutes: Int) {
        viewModelScope.launch {
            val interval = minutes.coerceAtLeast(1) // Ensure at least 1 minute
            appSettingsRepository.saveGoogleTasksSyncIntervalMinutes(interval)
        }
    }

    fun saveShowCompletedGoogleTasks(show: Boolean) {
        viewModelScope.launch {
            appSettingsRepository.saveShowCompletedGoogleTasks(show)
        }
    }

    fun scanForUnlinkedWavFiles() {
        viewModelScope.launch {
            transcriptionManager.findAndProcessUnlinkedWavFiles()
        }
    }

    private var lastCheckedApiKey: String = ""

    init {
        viewModelScope.launch {
            // Observe API key changes and trigger status check
            appSettingsRepository.apiKeyFlow.collect { currentApiKey ->
                if (currentApiKey != lastCheckedApiKey) {
                    // APIキーが変更されたら、検証済みステータスをリセット
                    appSettingsRepository.saveApiKeyVerifiedStatus(false)
                    lastCheckedApiKey = currentApiKey
                }
                checkApiKeyStatus()
            }
        }
    }

    fun checkApiKeyStatus() {
        viewModelScope.launch {
            _apiKeyStatus.value = ApiKeyStatus.CHECKING
            val apiKey = appSettingsRepository.apiKeyFlow.first() // Get current API key

            if (apiKey.isEmpty()) {
                _apiKeyStatus.value = ApiKeyStatus.EMPTY
                appSettingsRepository.saveApiKeyVerifiedStatus(false) // APIキーがない場合は未検証に
                return@launch
            }

            val isVerifiedInCache = appSettingsRepository.isApiKeyVerifiedFlow.first()
            if (isVerifiedInCache && apiKey == lastCheckedApiKey) { // lastCheckedApiKeyでAPIキーの変更がないことを確認
                _apiKeyStatus.value = ApiKeyStatus.VALID
                return@launch
            }

            // APIキーが変更されたか、または未検証の場合はネットワーク経由でチェック
            val speechToTextService = SpeechToTextService(application, apiKey)
            val result = speechToTextService.verifyApiKey()

            _apiKeyStatus.value = if (result.isSuccess) {
                appSettingsRepository.saveApiKeyVerifiedStatus(true)
                ApiKeyStatus.VALID
            } else {
                appSettingsRepository.saveApiKeyVerifiedStatus(false)
                // ここでエラーの種類をもう少し細かく分類することも可能だが、一旦INVALIDとする
                ApiKeyStatus.INVALID
            }
        }
    }
}


