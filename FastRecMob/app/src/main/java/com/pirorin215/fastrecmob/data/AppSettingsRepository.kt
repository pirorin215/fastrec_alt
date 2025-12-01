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
        val KEEP_CONNECTION_ALIVE = booleanPreferencesKey("keep_connection_alive")
        val AUDIO_CACHE_LIMIT = intPreferencesKey("audio_cache_limit")
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

    // BLE接続維持設定の変更を監視するためのFlow
    val keepConnectionAliveFlow: Flow<Boolean> = context.dataStore.data
        .map { preferences ->
            // デフォルトはfalse（接続を維持しない）
            preferences[PreferencesKeys.KEEP_CONNECTION_ALIVE] ?: false
        }

    // 音声ファイル保持数の変更を監視するためのFlow
    val audioCacheLimitFlow: Flow<Int> = context.dataStore.data
        .map { preferences ->
            // デフォルト値を100件に設定
            preferences[PreferencesKeys.AUDIO_CACHE_LIMIT] ?: 100
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

    // BLE接続維持設定を保存するsuspend関数
    suspend fun saveKeepConnectionAlive(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.KEEP_CONNECTION_ALIVE] = enabled
        }
    }

    // 音声ファイル保持数を保存するsuspend関数
    suspend fun saveAudioCacheLimit(limit: Int) {
        context.dataStore.edit { preferences ->
            preferences[PreferencesKeys.AUDIO_CACHE_LIMIT] = limit
        }
    }
}
