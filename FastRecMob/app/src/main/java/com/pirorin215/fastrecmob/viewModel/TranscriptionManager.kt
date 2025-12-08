package com.pirorin215.fastrecmob.viewModel

import android.content.Context
import com.pirorin215.fastrecmob.LocationData
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository
import com.pirorin215.fastrecmob.service.SpeechToTextService
import com.pirorin215.fastrecmob.data.FileUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File

class TranscriptionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val currentForegroundLocationFlow: StateFlow<LocationData?>,
    private val audioDirNameFlow: StateFlow<String>,
    private val transcriptionCacheLimitFlow: StateFlow<Int>,
    private val logCallback: (String) -> Unit
) {

    private var speechToTextService: SpeechToTextService? = null
    private val transcriptionBatchMutex = Mutex()
    val transcriptionQueue = mutableListOf<String>()

    private val _transcriptionState = MutableStateFlow("Idle")
    val transcriptionState: StateFlow<String> = _transcriptionState.asStateFlow()

    private val _transcriptionResult = MutableStateFlow<String?>(null)
    val transcriptionResult: StateFlow<String?> = _transcriptionResult.asStateFlow()

    private val _audioFileCount = MutableStateFlow(0)
    val audioFileCount: StateFlow<Int> = _audioFileCount.asStateFlow()

    init {
        // Initialize SpeechToTextService based on API Key
        appSettingsRepository.apiKeyFlow.distinctUntilChanged().onEach { apiKey ->
            if (apiKey.isNotEmpty()) {
                speechToTextService = SpeechToTextService(apiKey)
                logCallback("TranscriptionManager: SpeechToTextService initialized with API Key.")
            } else {
                speechToTextService = null
                logCallback("TranscriptionManager: SpeechToTextService cleared (API Key not set).")
            }
        }.launchIn(scope)

        // Initial update
        updateLocalAudioFileCount()
    }

    fun updateLocalAudioFileCount() {
        scope.launch {
            val audioDirName = audioDirNameFlow.value
            val audioDir = context.getExternalFilesDir(audioDirName)
            if (audioDir != null && audioDir.exists()) {
                val count = audioDir.listFiles { _, name ->
                    name.matches(Regex("""R\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}\.wav"""))
                }?.size ?: 0
                _audioFileCount.value = count
                logCallback("Updated local audio file count: $count")
            } else {
                _audioFileCount.value = 0
                logCallback("Audio directory not found, local audio file count is 0.")
            }
        }
    }

    suspend fun doTranscription(filePath: String) {
        _transcriptionState.value = "Transcribing ${File(filePath).name}"
        _transcriptionResult.value = null
        logCallback("Starting transcription for $filePath")

        val currentService = speechToTextService
        val actualFileName = File(filePath).name

        var locationData: LocationData? = null
        // Try to use pre-collected low-power location
        locationData = currentForegroundLocationFlow.value

        if (locationData != null) {
            logCallback("Using pre-collected location for transcription: Lat=${locationData?.latitude}, Lng=${locationData?.longitude}")
        } else {
            logCallback("Pre-collected location not available. Proceeding without location data.")
        }

        if (currentService == null) {
            _transcriptionState.value = "Error: APIキーが設定されていません。設定画面で入力してください。"
            logCallback("Transcription failed: APIキーが設定されていません。")
            val errorResult = TranscriptionResult(actualFileName, "文字起こしエラー: APIキーが設定されていません。設定画面で入力してください。", locationData)
            transcriptionResultRepository.addResult(errorResult)
            logCallback("Transcription error result saved for $actualFileName.")
            cleanupTranscriptionResultsAndAudioFiles()
            return
        }

        val result = currentService.transcribeFile(filePath)

        result.onSuccess { transcription ->
            _transcriptionResult.value = transcription
            logCallback("Transcription successful for $filePath.")
            val newResult = TranscriptionResult(actualFileName, transcription, locationData)
            transcriptionResultRepository.addResult(newResult)
            logCallback("Transcription result saved for $actualFileName.")
            cleanupTranscriptionResultsAndAudioFiles()
        }.onFailure { error ->
            val errorMessage = error.message ?: "不明なエラー"
            val displayMessage = if (errorMessage.contains("API key authentication failed") || errorMessage.contains("API key is not set")) {
                "文字起こしエラー: APIキーに問題がある可能性があります。設定画面をご確認ください。詳細: $errorMessage"
            } else {
                "文字起こしエラー: $errorMessage"
            }
            _transcriptionState.value = "Error: $displayMessage"
            _transcriptionResult.value = null
            logCallback("Transcription failed for $filePath: $displayMessage")
            val errorResult = TranscriptionResult(actualFileName, displayMessage, locationData)
            transcriptionResultRepository.addResult(errorResult)
            logCallback("Transcription error result saved for $actualFileName.")
            cleanupTranscriptionResultsAndAudioFiles()
        }
    }

    fun startBatchTranscription() {
        if (transcriptionQueue.isEmpty()) {
            logCallback("Transcription queue is empty. Nothing to do.")
            return
        }

        scope.launch {
            if (!transcriptionBatchMutex.tryLock()) {
                logCallback("Batch transcription already in progress. Skipping.")
                return@launch
            }
            try {
                logCallback("Found ${transcriptionQueue.size} file(s) in queue. Starting batch transcription.")
                val filesToProcess = transcriptionQueue.toList()
                transcriptionQueue.clear()

                for (filePath in filesToProcess) {
                    doTranscription(filePath)
                }
                logCallback("Batch transcription finished.")
                _transcriptionState.value = "Idle" // Reset state after batch is done
                cleanupTranscriptionResultsAndAudioFiles() // Call cleanup after batch transcription
            } finally {
                transcriptionBatchMutex.unlock()
            }
        }
    }

    suspend fun cleanupTranscriptionResultsAndAudioFiles() = withContext(Dispatchers.IO) {
        try {
            logCallback("Running transcription results and audio file cleanup...")
            val limit = transcriptionCacheLimitFlow.value
            // We need to fetch the current list from repository
            val currentTranscriptionResults = transcriptionResultRepository.transcriptionResultsFlow.first()
                .filter { !it.isDeletedLocally } // Assuming we only count non-deleted ones for limit? Original code did this.
                .sortedBy { it.lastEditedTimestamp } // Oldest first

            if (currentTranscriptionResults.size > limit) {
                val resultsToDelete = currentTranscriptionResults.take(currentTranscriptionResults.size - limit)
                logCallback("Transcription cache limit ($limit) exceeded. Found ${currentTranscriptionResults.size} results. Deleting oldest ${resultsToDelete.size} results and associated audio files...")

                resultsToDelete.forEach { result ->
                    // Delete from DataStore
                    transcriptionResultRepository.removeResult(result)
                    logCallback("Deleted transcription result: ${result.fileName}")

                    // Delete associated audio file
                    val audioDirName = audioDirNameFlow.value
                    val audioFile = FileUtil.getAudioFile(context, audioDirName, result.fileName)
                    if (audioFile.exists()) {
                        if (audioFile.delete()) {
                            logCallback("Deleted associated audio file: ${result.fileName}")
                        } else {
                            logCallback("Failed to delete associated audio file: ${result.fileName}")
                        }
                    } else {
                        logCallback("Associated audio file not found for: ${result.fileName}")
                    }
                }
                logCallback("Cleanup finished. Deleted ${resultsToDelete.size} transcription results and audio files.")
            } else {
                logCallback("Transcription cache is within limit ($limit). No results or files deleted.")
            }
        } catch (e: Exception) {
            logCallback("Error during transcription results and audio file cleanup: ${e.message}")
        } finally {
            updateLocalAudioFileCount() // Ensure count is updated after cleanup
        }
    }

    fun retranscribe(result: TranscriptionResult) {
        scope.launch {
            logCallback("Attempting to retranscribe file: ${result.fileName}")

            val audioDirName = audioDirNameFlow.value
            val audioFile = FileUtil.getAudioFile(context, audioDirName, result.fileName)
            if (!audioFile.exists()) {
                logCallback("Error: Audio file not found for retranscription: ${result.fileName}. Cannot retranscribe. Item will not be removed.")
                return@launch
            }

            logCallback("Audio file found for retranscription: ${audioFile.absolutePath}")
            transcriptionResultRepository.removeResult(result)
            logCallback("Removed old transcription result for ${result.fileName} before re-transcribing.")

            doTranscription(audioFile.absolutePath)
            logCallback("Initiated retranscription for ${result.fileName}.")
        }
    }

    fun addManualTranscription(text: String) {
        scope.launch {
            var locationData: LocationData? = currentForegroundLocationFlow.value
            if (locationData != null) {
                logCallback("Using pre-collected location for manual transcription: Lat=${locationData?.latitude}, Lng=${locationData?.longitude}")
            } else {
                 logCallback("Pre-collected location not available for manual transcription. Proceeding without location data.")
            }

            val timestamp = System.currentTimeMillis()
            val manualFileName = "M${FileUtil.formatTimestampForFileName(timestamp)}.txt"
            val newResult = TranscriptionResult(manualFileName, text, locationData)

            transcriptionResultRepository.addResult(newResult)
            logCallback("Manual transcription added: $manualFileName")

            cleanupTranscriptionResultsAndAudioFiles()
        }
    }

    fun updateDisplayOrder(reorderedList: List<TranscriptionResult>) {
        scope.launch {
            val updatedList = reorderedList.mapIndexed { index, result ->
                result.copy(displayOrder = index)
            }
            transcriptionResultRepository.updateResults(updatedList)
            logCallback("Transcription results order updated.")
        }
    }

    fun clearTranscriptionResults() {
        scope.launch {
            logCallback("Starting clear all transcription results...")
            val currentTranscriptionResults = transcriptionResultRepository.transcriptionResultsFlow.first()

            val updatedListForRepo = mutableListOf<TranscriptionResult>()

            // Process existing results
            currentTranscriptionResults.forEach { result ->
                if (result.googleTaskId == null) {
                    // Local-only item: permanently delete now
                    transcriptionResultRepository.permanentlyRemoveResult(result) // This will remove it from the DataStore
                    val audioFile = FileUtil.getAudioFile(context, audioDirNameFlow.value, result.fileName)
                    if (audioFile.exists()) {
                        if (audioFile.delete()) {
                            logCallback("Deleted local-only audio file during clear all: ${result.fileName}")
                        } else {
                            logCallback("Failed to delete local-only audio file during clear all: ${result.fileName}")
                        }
                    }
                } else {
                    // Synced item: soft delete (mark for remote deletion during next sync)
                    updatedListForRepo.add(result.copy(isDeletedLocally = true))
                }
            }
            transcriptionResultRepository.updateResults(updatedListForRepo)

            logCallback("All local-only transcription results permanently deleted. Synced results marked for soft deletion.")
            updateLocalAudioFileCount() // Update count after deletions
        }
    }

    fun removeTranscriptionResult(result: TranscriptionResult) {
        scope.launch {
            if (result.googleTaskId != null) {
                // If synced with Google Tasks, perform a soft delete locally.
                // Actual deletion from Google Tasks and permanent local deletion will happen during sync.
                transcriptionResultRepository.removeResult(result) // This now sets isDeletedLocally = true
                logCallback("Soft-deleted transcription result (synced with Google Tasks): ${result.fileName}")
            } else {
                // If not synced with Google Tasks, permanently delete locally and its audio file immediately.
                transcriptionResultRepository.permanentlyRemoveResult(result)
                logCallback("Permanently deleted local-only transcription result: ${result.fileName}")

                val audioFile = FileUtil.getAudioFile(context, audioDirNameFlow.value, result.fileName)
                if (audioFile.exists()) {
                    if (audioFile.delete()) {
                        logCallback("Associated audio file deleted: ${result.fileName}")
                        updateLocalAudioFileCount()
                    } else {
                        logCallback("Failed to delete associated audio file: ${result.fileName}")
                    }
                } else {
                    logCallback("Associated audio file not found for local-only result: ${result.fileName}")
                }
            }
        }
    }

    fun updateTranscriptionResult(originalResult: TranscriptionResult, newTranscription: String, newNote: String?) {
        scope.launch {
            // Create a new TranscriptionResult with the updated transcription and notes
            val updatedResult = originalResult.copy(
                transcription = newTranscription,
                googleTaskNotes = newNote, // Update googleTaskNotes
                lastEditedTimestamp = System.currentTimeMillis()
            )
            // The addResult method now handles updates, so we just call that.
            transcriptionResultRepository.addResult(updatedResult)
            logCallback("Transcription result for ${originalResult.fileName} updated.")
        }
    }

    fun removeTranscriptionResults(fileNames: Set<String>, clearSelectionCallback: () -> Unit) {
        scope.launch {
            // We need to fetch the current results to filter
            val currentResults = transcriptionResultRepository.transcriptionResultsFlow.first()
            val resultsToRemove = currentResults.filter { fileNames.contains(it.fileName) }
            resultsToRemove.forEach { result ->
                removeTranscriptionResult(result) // Use the existing single delete function
            }
            clearSelectionCallback() // Clear selection after deletion
            logCallback("Removed ${resultsToRemove.size} selected transcription results.")
        }
    }

    fun resetTranscriptionState() {
        _transcriptionState.value = "Idle"
        _transcriptionResult.value = null
    }
    
    fun onCleared() {
        // Placeholder for future cleanup if needed
    }
}