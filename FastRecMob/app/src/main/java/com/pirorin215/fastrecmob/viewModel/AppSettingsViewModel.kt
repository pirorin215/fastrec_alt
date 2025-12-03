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

    init {
        viewModelScope.launch {
            // Observe API key changes and trigger status check
            appSettingsRepository.apiKeyFlow.collect { _ ->
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
                return@launch
            }

            val speechToTextService = SpeechToTextService(apiKey)
            val result = speechToTextService.verifyApiKey()

            _apiKeyStatus.value = if (result.isSuccess) {
                ApiKeyStatus.VALID
            } else {
                // Here, if result is failure, it means either network error or authentication error.
                // SpeechToTextService.verifyApiKey already distinguishes empty key (via IllegalArgumentException)
                // and auth failure (via specific message from StatusRuntimeException),
                // but for simplicity, we can treat any failure from verifyApiKey as INVALID for now.
                // If more granular error messages are needed, the exceptions from verifyApiKey
                // should be propagated or mapped more specifically.
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
