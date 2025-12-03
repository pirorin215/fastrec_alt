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

enum class ApiKeyStatus {
    VALID,
    INVALID,
    EMPTY,
    CHECKING,
    UNKNOWN_ERROR
}

class AppSettingsViewModel(
    private val appSettingsRepository: AppSettingsRepository,
    private val application: Application // Context for SpeechToTextService, though not directly used now
) : ViewModel() {

    private val _apiKeyStatus = MutableStateFlow(ApiKeyStatus.CHECKING)
    val apiKeyStatus: StateFlow<ApiKeyStatus> = _apiKeyStatus.asStateFlow()

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
            val speechToTextService = SpeechToTextService(apiKey)
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

class AppSettingsViewModelFactory(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppSettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppSettingsViewModel(appSettingsRepository, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
