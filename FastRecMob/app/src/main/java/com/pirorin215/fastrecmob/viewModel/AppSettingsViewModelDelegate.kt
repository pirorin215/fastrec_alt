package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.SortMode
import com.pirorin215.fastrecmob.data.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch // Add this import

class AppSettingsViewModelDelegate(
    private val appSettingsRepository: AppSettingsRepository,
    private val scope: CoroutineScope
) : AppSettingsAccessor {

    override val apiKey: StateFlow<String> = appSettingsRepository.apiKeyFlow
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    override val refreshIntervalSeconds: StateFlow<Int> = appSettingsRepository.refreshIntervalSecondsFlow
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 30 // Provide a default
        )

    override val transcriptionCacheLimit: StateFlow<Int> = appSettingsRepository.transcriptionCacheLimitFlow
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 100 // Default to 100 files
        )

    override val transcriptionFontSize: StateFlow<Int> = appSettingsRepository.transcriptionFontSizeFlow
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = 14 // Default to 14
        )

    override val audioDirName: StateFlow<String> = appSettingsRepository.audioDirNameFlow
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "FastRecRecordings" // Default directory name
        )

    override val themeMode: StateFlow<ThemeMode> = appSettingsRepository.themeModeFlow
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ThemeMode.SYSTEM // Default to SYSTEM
        )

    override val sortMode: StateFlow<SortMode> = appSettingsRepository.sortModeFlow
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = SortMode.TIMESTAMP
        )

    override val googleTodoListName: StateFlow<String> = appSettingsRepository.googleTodoListNameFlow
        .stateIn(scope, SharingStarted.Eagerly, "")

    override val googleTaskTitleLength: StateFlow<Int> = appSettingsRepository.googleTaskTitleLengthFlow
        .stateIn(scope, SharingStarted.Eagerly, 20) // Default to 20

    override fun saveApiKey(apiKey: String) {
        scope.launch { appSettingsRepository.saveApiKey(apiKey) }
    }

    override fun saveRefreshIntervalSeconds(seconds: Int) {
        scope.launch { appSettingsRepository.saveRefreshIntervalSeconds(seconds) }
    }

    override fun saveTranscriptionCacheLimit(limit: Int) {
        scope.launch { appSettingsRepository.saveTranscriptionCacheLimit(limit) }
    }

    override fun saveTranscriptionFontSize(size: Int) {
        scope.launch { appSettingsRepository.saveTranscriptionFontSize(size) }
    }

    override fun saveAudioDirName(name: String) {
        scope.launch { appSettingsRepository.saveAudioDirName(name) }
    }

    override fun saveThemeMode(mode: ThemeMode) {
        scope.launch { appSettingsRepository.saveThemeMode(mode) }
    }

    override fun saveSortMode(sortMode: SortMode) {
        scope.launch { appSettingsRepository.saveSortMode(sortMode) }
    }

    override fun saveGoogleTodoListName(name: String) {
        scope.launch { appSettingsRepository.saveGoogleTodoListName(name) }
    }

    override fun saveGoogleTaskTitleLength(length: Int) {
        scope.launch { appSettingsRepository.saveGoogleTaskTitleLength(length) }
    }
}
