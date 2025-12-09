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
    val lastEditedTimestamp: Long = System.currentTimeMillis(),
    val locationData: LocationData? = null,
    val displayOrder: Int = 0,
    // New fields for Google Tasks integration
    val googleTaskId: String? = null,
    val isCompleted: Boolean = false,
    val googleTaskNotes: String? = null,
    val googleTaskUpdated: String? = null, // RFC 3339 timestamp
    val googleTaskPosition: String? = null,
    val googleTaskDue: String? = null, // RFC 3339 timestamp
    val googleTaskWebViewLink: String? = null,
    val isSyncedWithGoogleTasks: Boolean = false,
    val isDeletedLocally: Boolean = false, // New field for soft deletion
    val transcriptionStatus: String = "COMPLETED" // PENDING, COMPLETED, FAILED
) {
    // Secondary constructor to simplify creation when lastEditedTimestamp is current time
    constructor(
        fileName: String,
        transcription: String,
        locationData: LocationData? = null
    ) : this(
        fileName = fileName,
        transcription = transcription,
        lastEditedTimestamp = System.currentTimeMillis(),
        locationData = locationData,
        displayOrder = 0,
        // Default values for new Google Tasks fields in secondary constructor
        googleTaskId = null,
        isCompleted = false,
        googleTaskNotes = null,
        googleTaskUpdated = null,
        googleTaskPosition = null,
        googleTaskDue = null,
        googleTaskWebViewLink = null,
        isSyncedWithGoogleTasks = false,
        isDeletedLocally = false, // Default for new field
        transcriptionStatus = "COMPLETED"
    )
}

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
                val results = json.decodeFromString<List<TranscriptionResult>>(jsonString)
                // Fix for legacy data without displayOrder.
                if (results.any { it.displayOrder == 0 } && results.distinctBy { it.displayOrder }.size == 1) {
                    results.sortedByDescending { it.lastEditedTimestamp }.mapIndexed { index, result -> result.copy(displayOrder = index) }
                } else {
                    results
                }
            } catch (e: Exception) {
                e.printStackTrace()
                emptyList() // パース失敗時は空リストを返す
            }
        }

    // 文字起こし結果を追加または更新するsuspend関数
    suspend fun addResult(result: TranscriptionResult) {
        context.transcriptionDataStore.edit { preferences ->
            val currentList = transcriptionResultsFlow.first()
            val existingResult = currentList.find { it.fileName == result.fileName }

            val updatedList = if (existingResult != null) {
                // 既存のアイテムを更新 (displayOrderは維持、lastEditedTimestampは渡されたresultのものを使用)
                currentList.map {
                    if (it.fileName == result.fileName) {
                        result.copy(displayOrder = existingResult.displayOrder)
                    } else {
                        it
                    }
                }
            } else {
                // 新しいアイテムを先頭に追加するため、既存アイテムのdisplayOrderをインクリメント
                val shiftedList = currentList.map { it.copy(displayOrder = it.displayOrder + 1) }
                // 新しいアイテムをdisplayOrder = 0として追加
                shiftedList + result.copy(displayOrder = 0)
            }
            preferences[PreferencesKeys.TRANSCRIPTION_RESULTS] = json.encodeToString(updatedList)
        }
    }

    // 新しいリスト全体を保存するsuspend関数
    suspend fun updateResults(results: List<TranscriptionResult>) {
        context.transcriptionDataStore.edit { preferences ->
            preferences[PreferencesKeys.TRANSCRIPTION_RESULTS] = json.encodeToString(results)
        }
    }

    // 全ての文字起こし結果をクリアするsuspend関数
    suspend fun clearResults() {
        context.transcriptionDataStore.edit { preferences ->
            preferences[PreferencesKeys.TRANSCRIPTION_RESULTS] = "[]"
        }
    }

    // 特定の文字起こし結果を永久に削除するsuspend関数
    suspend fun permanentlyRemoveResult(result: TranscriptionResult) {
        context.transcriptionDataStore.edit { preferences ->
            val currentList = transcriptionResultsFlow.first()
            val listAfterRemoval = currentList.filter { it.fileName != result.fileName }
            // displayOrderを再インデックスする
            val updatedList = listAfterRemoval.sortedBy { it.displayOrder }.mapIndexed { index, item ->
                item.copy(displayOrder = index)
            }
            preferences[PreferencesKeys.TRANSCRIPTION_RESULTS] = json.encodeToString(updatedList)
        }
    }

    // 特定の文字起こし結果を論理削除するsuspend関数 (isDeletedLocallyをtrueに設定)
    suspend fun removeResult(result: TranscriptionResult) {
        // 論理削除フラグを設定して結果を更新
        val softDeletedResult = result.copy(isDeletedLocally = true)
        addResult(softDeletedResult)
    }
}
