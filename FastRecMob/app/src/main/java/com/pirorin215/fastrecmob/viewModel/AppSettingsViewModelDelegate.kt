package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.SortMode
import com.pirorin215.fastrecmob.data.ThemeMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class AppSettingsViewModelDelegate(
    appSettingsRepository: AppSettingsRepository,
    scope: CoroutineScope
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
}
