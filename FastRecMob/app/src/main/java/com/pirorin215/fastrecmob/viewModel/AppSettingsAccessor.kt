package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.SortMode
import com.pirorin215.fastrecmob.data.ThemeMode
import kotlinx.coroutines.flow.StateFlow

interface AppSettingsAccessor {
    val apiKey: StateFlow<String>
    val refreshIntervalSeconds: StateFlow<Int>
    val transcriptionCacheLimit: StateFlow<Int>
    val transcriptionFontSize: StateFlow<Int>
    val audioDirName: StateFlow<String>
    val themeMode: StateFlow<ThemeMode>
    val sortMode: StateFlow<SortMode>
}