package com.pirorin215.fastrecmob.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.pirorin215.fastrecmob.LocationData

// DataStoreのインスタンスをContextの拡張プロパティとして定義 (別名で)
private val Context.transcriptionDataStore: DataStore<Preferences> by preferencesDataStore(name = "transcription_results")

@Serializable
data class TranscriptionResult(
    val fileName: String,
    val transcription: String,
    val timestamp: Long = System.currentTimeMillis(),
    val locationData: LocationData? = null
)

class TranscriptionResultRepository(private val context: Context) {

    private object PreferencesKeys {
        val TRANSCRIPTION_RESULTS = stringPreferencesKey("transcription_results_list")
    }

    private val json = Json {
        prettyPrint = false // JSONを整形しない (保存容量のため)
        ignoreUnknownKeys = true // JSONが将来的に変更されても互換性を保つ
    }

    // 文字起こし結果のリストを監視するためのFlow
    val transcriptionResultsFlow: Flow<List<TranscriptionResult>> = context.transcriptionDataStore.data
        .map { preferences ->
            val jsonString = preferences[PreferencesKeys.TRANSCRIPTION_RESULTS] ?: "[]"
            try {
                json.decodeFromString<List<TranscriptionResult>>(jsonString)
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList() // パース失敗時は空リストを返す
            }
        }

    // 文字起こし結果を追加するsuspend関数
    suspend fun addResult(result: TranscriptionResult) {
        context.transcriptionDataStore.edit { preferences ->
            val currentList = transcriptionResultsFlow.first() // 現在のリストを取得 (Flowをブロックするが、DataStoreは非同期なので問題ない)
            // Filter out any existing result with the same fileName before adding the new one
            val listWithoutExisting = currentList.filter { it.fileName != result.fileName }
            val updatedList = listWithoutExisting + result
            preferences[PreferencesKeys.TRANSCRIPTION_RESULTS] = json.encodeToString(updatedList)
        }
    }

    // 全ての文字起こし結果をクリアするsuspend関数
    suspend fun clearResults() {
        context.transcriptionDataStore.edit { preferences ->
            preferences[PreferencesKeys.TRANSCRIPTION_RESULTS] = "[]"
        }
    }

    // 特定の文字起こし結果を削除するsuspend関数
    suspend fun removeResult(result: TranscriptionResult) {
        context.transcriptionDataStore.edit { preferences ->
            val currentList = transcriptionResultsFlow.first()
            val updatedList = currentList.filter { it.fileName != result.fileName } // resultと一致するものを除外
            preferences[PreferencesKeys.TRANSCRIPTION_RESULTS] = json.encodeToString(updatedList)
        }
    }
}
