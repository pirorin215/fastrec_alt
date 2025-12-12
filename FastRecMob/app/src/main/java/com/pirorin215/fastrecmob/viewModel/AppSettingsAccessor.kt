package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.ThemeMode
import kotlinx.coroutines.flow.StateFlow

interface AppSettingsAccessor {
    val apiKey: StateFlow<String>
    val refreshIntervalSeconds: StateFlow<Int>
    val transcriptionCacheLimit: StateFlow<Int>
    val transcriptionFontSize: StateFlow<Int>
    val audioDirName: StateFlow<String>
    val themeMode: StateFlow<ThemeMode>
    val googleTodoListName: StateFlow<String>
    val googleTaskTitleLength: StateFlow<Int>
    val googleTasksSyncIntervalMinutes: StateFlow<Int>

    fun saveApiKey(apiKey: String)
    fun saveRefreshIntervalSeconds(seconds: Int)
    fun saveTranscriptionCacheLimit(limit: Int)
    fun saveTranscriptionFontSize(size: Int)
    fun saveAudioDirName(name: String)
    fun saveThemeMode(mode: ThemeMode)
    fun saveGoogleTodoListName(name: String)
    fun saveGoogleTaskTitleLength(length: Int)
    fun saveGoogleTasksSyncIntervalMinutes(minutes: Int)
}