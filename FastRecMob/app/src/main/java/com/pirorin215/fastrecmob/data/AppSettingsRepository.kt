package com.pirorin215.fastrecmob.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// DataStoreのインスタンスをContextの拡張プロパティとして定義
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_settings")

class AppSettingsRepository(private val context: Context) {

    // キーを定義
    private object PreferencesKeys {
        val API_KEY = stringPreferencesKey("google_cloud_api_key")
        val REFRESH_INTERVAL_SECONDS = intPreferencesKey("refresh_interval_seconds")
        val TRANSCRIPTION_CACHE_LIMIT = intPreferencesKey("transcription_cache_limit") // Renamed
        val AUDIO_DIR_NAME = stringPreferencesKey("audio_dir_name")
        val TRANSCRIPTION_FONT_SIZE = intPreferencesKey("transcription_font_size")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val IS_API_KEY_VERIFIED = booleanPreferencesKey("is_api_key_verified") // Add this
    }

    // APIキーの変更を監視するためのFlow
    val apiKeyFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.API_KEY] ?: ""
        }
    
    // 更新周期（秒）の変更を監視するためのFlow
    val refreshIntervalSecondsFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            // デフォルト値を30秒に設定
            preferences[PreferencesKeys.REFRESH_INTERVAL_SECONDS] ?: 30
        }

    // 文字起こし保持数の変更を監視するためのFlow
    val transcriptionCacheLimitFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            // デフォルト値を100件に設定
            preferences[PreferencesKeys.TRANSCRIPTION_CACHE_LIMIT] ?: 100
        }

    // 文字起こし結果のフォントサイズの変更を監視するためのFlow
    val transcriptionFontSizeFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            // デフォルト値を14に設定
            preferences[PreferencesKeys.TRANSCRIPTION_FONT_SIZE] ?: 14
        }

    // 音声保存ディレクトリ名の変更を監視するためのFlow
    val audioDirNameFlow: Flow<String> = context.dataStore.data
        .map { preferences ->
            // デフォルト値を "FastRecRecordings" に設定
            preferences[PreferencesKeys.AUDIO_DIR_NAME] ?: "FastRecRecordings"
        }

    // テーマモードの変更を監視するためのFlow
    val themeModeFlow: Flow<ThemeMode> = context.dataStore.data
        .map { preferences ->
            // デフォルト値をSYSTEMに設定
            ThemeMode.valueOf(preferences[PreferencesKeys.THEME_MODE] ?: ThemeMode.SYSTEM.name)
        }

    // APIキーを保存するsuspend関数
    suspend fun saveApiKey(apiKey: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.API_KEY] = apiKey
        }
    }

    // 更新周期（秒）を保存するsuspend関数
    suspend fun saveRefreshIntervalSeconds(seconds: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.REFRESH_INTERVAL_SECONDS] = seconds
        }
    }

    // 文字起こし保持数を保存するsuspend関数
    suspend fun saveTranscriptionCacheLimit(limit: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TRANSCRIPTION_CACHE_LIMIT] = limit
        }
    }

    // 文字起こし結果のフォントサイズを保存するsuspend関数
    suspend fun saveTranscriptionFontSize(size: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.TRANSCRIPTION_FONT_SIZE] = size
        }
    }

    // 音声保存ディレクトリ名を保存するsuspend関数
    suspend fun saveAudioDirName(name: String) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUDIO_DIR_NAME] = name
        }
    }

    // テーマモードを保存するsuspend関数
    suspend fun saveThemeMode(themeMode: ThemeMode) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.THEME_MODE] = themeMode.name
        }
    }

    // APIキーの検証済みステータスを監視するためのFlow
    val isApiKeyVerifiedFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            preferences[PreferencesKeys.IS_API_KEY_VERIFIED] ?: false // Default to false
        }

    // APIキーの検証済みステータスを保存するsuspend関数
    suspend fun saveApiKeyVerifiedStatus(isVerified: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.IS_API_KEY_VERIFIED] = isVerified
        }
    }
}
