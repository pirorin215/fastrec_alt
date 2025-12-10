package com.pirorin215.fastrecmob.viewModel

import android.content.Context
import com.pirorin215.fastrecmob.LocationData
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.TranscriptionResult
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository
import com.pirorin215.fastrecmob.service.SpeechToTextService
import com.pirorin215.fastrecmob.data.FileUtil
import com.pirorin215.fastrecmob.data.SortMode
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
    private val logManager: LogManager,
    private val googleTaskTitleLengthFlow: StateFlow<Int>, // New parameter
    private val googleTasksIntegration: GoogleTasksIntegration
) : TranscriptionManagement {

    private var speechToTextService: SpeechToTextService? = null

    private val _transcriptionState = MutableStateFlow("Idle")
    override val transcriptionState: StateFlow<String> = _transcriptionState.asStateFlow()

    private val _transcriptionResult = MutableStateFlow<String?>(null)
    override val transcriptionResult: StateFlow<String?> = _transcriptionResult.asStateFlow()

    private val _audioFileCount = MutableStateFlow(0)
    override val audioFileCount: StateFlow<Int> = _audioFileCount.asStateFlow()

    init {
        appSettingsRepository.apiKeyFlow
            .onEach { apiKey ->
                logManager.addLog("TranscriptionManager: API Key changed.")
                if (apiKey.isNotBlank()) {
                    speechToTextService = SpeechToTextService(context, apiKey)
                    logManager.addLog("TranscriptionManager: SpeechToTextService initialized.")
                } else {
                    speechToTextService = null
                    logManager.addLog("TranscriptionManager: SpeechToTextService cleared (API Key not set).")
                }
            }
            .launchIn(scope)

        // Initial update
        updateLocalAudioFileCount()
    }

    override fun updateLocalAudioFileCount() {
        scope.launch {
            val audioDirName = audioDirNameFlow.value
            val audioDir = context.getExternalFilesDir(audioDirName)
            if (audioDir != null && audioDir.exists()) {
                val count = audioDir.listFiles { _, name ->
                    name.matches(Regex("""R\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}\.wav"""))
                }?.size ?: 0
                _audioFileCount.value = count
                logManager.addLog("Updated local audio file count: $count")
            } else {
                _audioFileCount.value = 0
                logManager.addLog("Audio directory not found, local audio file count is 0.")
            }
        }
    }

    private val transcriptionMutex = Mutex()

    suspend fun doTranscription(resultToProcess: TranscriptionResult) {
        logManager.addLog("Entered doTranscription for file: ${resultToProcess.fileName}. Current status: ${resultToProcess.transcriptionStatus}")
        val filePath = FileUtil.getAudioFile(context, audioDirNameFlow.value, resultToProcess.fileName).absolutePath
        _transcriptionState.value = "Transcribing ${File(filePath).name}"
        logManager.addLog("Starting transcription for $filePath")

        val currentService = speechToTextService
        val locationData = currentForegroundLocationFlow.value
        if (locationData != null) {
            logManager.addLog("Using pre-collected location for transcription: Lat=${locationData.latitude}, Lng=${locationData.longitude}")
        } else {
            logManager.addLog("Pre-collected location not available. Proceeding without location data.")
        }

        if (currentService == null) {
            val errorMessage = "APIキーが設定されていません。設定画面で入力してください。"
            _transcriptionState.value = "Error: $errorMessage"
            logManager.addLog("Transcription failed: $errorMessage")
            val errorResult = resultToProcess.copy(
                transcription = "文字起こしエラー: $errorMessage",
                locationData = locationData ?: resultToProcess.locationData,
                transcriptionStatus = "FAILED"
            )
            transcriptionResultRepository.addResult(errorResult)
            logManager.addLog("Transcription error result saved for ${resultToProcess.fileName}.")
            return
        }

        logManager.addLog("Calling transcribeFile for ${filePath}...")
        val result = currentService.transcribeFile(filePath)
        logManager.addLog("TranscribeFile call finished for ${filePath}. Result success: ${result.isSuccess}")

        result.onSuccess { fullTranscription ->
            val googleTaskTitleLength = googleTaskTitleLengthFlow.first()

            val rawTitle = fullTranscription.take(googleTaskTitleLength)
            val cleanTitle = rawTitle.replace("\n", "")
            val title = if (cleanTitle.isBlank()) "Transcription" else cleanTitle

            val notes = if (fullTranscription.length > googleTaskTitleLength) {
                fullTranscription
            } else {
                null
            }

            _transcriptionResult.value = title // Display the clean title
            logManager.addLog("Transcription successful for $filePath. Title: '$title'")
            val newResult = resultToProcess.copy(
                transcription = title, // Save the title as the main transcription
                googleTaskNotes = notes, // Save full transcription as notes if overflow
                locationData = locationData ?: resultToProcess.locationData,
                transcriptionStatus = "COMPLETED"
            )
            transcriptionResultRepository.addResult(newResult)
            logManager.addLog("Transcription result saved for ${resultToProcess.fileName}.")
            // Immediately sync with Google Tasks after successful transcription
            googleTasksIntegration.syncTranscriptionResultsWithGoogleTasks(audioDirNameFlow.value)
        }.onFailure { error ->
            val errorMessage = error.message ?: "不明なエラー"
            val displayMessage = if (errorMessage.contains("API key authentication failed") || errorMessage.contains("API key is not set")) {
                "文字起こしエラー: APIキーに問題がある可能性があります。設定画面をご確認ください。詳細: $errorMessage"
            } else {
                "文字起こしエラー: $errorMessage"
            }
            _transcriptionState.value = "Error: $displayMessage"
            _transcriptionResult.value = null
            logManager.addLog("Transcription failed for $filePath: $displayMessage")
            val errorResult = resultToProcess.copy(
                transcription = displayMessage,
                locationData = locationData ?: resultToProcess.locationData,
                transcriptionStatus = "FAILED"
            )
            transcriptionResultRepository.addResult(errorResult)
            logManager.addLog("Transcription error result saved for ${resultToProcess.fileName}.")
        }
    }

    override fun processPendingTranscriptions() {
        scope.launch {
            if (!transcriptionMutex.tryLock()) {
                logManager.addLog("Transcription processing already in progress. Skipping.")
                return@launch
            }
            try {
                val pendingResults = transcriptionResultRepository.transcriptionResultsFlow.first()
                    .filter { it.transcriptionStatus == "PENDING" }

                if (pendingResults.isEmpty()) {
                    logManager.addLog("No pending transcriptions to process.")
                    return@launch
                }

                logManager.addLog("Found ${pendingResults.size} pending transcription(s). Starting processing.")
                for (result in pendingResults) {
                    logManager.addLog("Processing pending transcription for file: ${result.fileName}")
                    doTranscription(result)
                }
                logManager.addLog("Pending transcription processing finished.")
                _transcriptionState.value = "Idle"
            } finally {
                transcriptionMutex.unlock()
                cleanupTranscriptionResultsAndAudioFiles() // Call cleanup after batch transcription
            }
        }
    }

    override suspend fun cleanupTranscriptionResultsAndAudioFiles() = withContext(Dispatchers.IO) {
        try {
            logManager.addLog("Running transcription results and audio file cleanup...")
            val limit = transcriptionCacheLimitFlow.value
            // We need to fetch the current list from repository
            val currentTranscriptionResults = transcriptionResultRepository.transcriptionResultsFlow.first()
                .filter { !it.isDeletedLocally } // Assuming we only count non-deleted ones for limit? Original code did this.
                .sortedBy { it.lastEditedTimestamp } // Oldest first

            if (currentTranscriptionResults.size > limit) {
                val resultsToDelete = currentTranscriptionResults.take(currentTranscriptionResults.size - limit)
                logManager.addLog("Transcription cache limit ($limit) exceeded. Found ${currentTranscriptionResults.size} results. Deleting oldest ${resultsToDelete.size} results and associated audio files...")

                resultsToDelete.forEach { result ->
                    // Delete from DataStore
                    transcriptionResultRepository.removeResult(result)
                    logManager.addLog("Deleted transcription result: ${result.fileName}")

                    // Delete associated audio file
                    val audioDirName = audioDirNameFlow.value
                    val audioFile = FileUtil.getAudioFile(context, audioDirName, result.fileName)
                    if (audioFile.exists()) {
                        if (audioFile.delete()) {
                            logManager.addLog("Deleted associated audio file: ${result.fileName}")
                        } else {
                            logManager.addLog("Failed to delete associated audio file: ${result.fileName}")
                        }
                    } else {
                        logManager.addLog("Associated audio file not found for: ${result.fileName}")
                    }
                }
                logManager.addLog("Cleanup finished. Deleted ${resultsToDelete.size} transcription results and audio files.")
            } else {
                logManager.addLog("Transcription cache is within limit ($limit). No results or files deleted.")
            }
        } catch (e: Exception) {
            logManager.addLog("Error during transcription results and audio file cleanup: ${e.message}")
        } finally {
            updateLocalAudioFileCount() // Ensure count is updated after cleanup
        }
    }

    override fun retranscribe(result: TranscriptionResult) {
        scope.launch {
            logManager.addLog("Attempting to retranscribe file: ${result.fileName}")

            val audioDirName = audioDirNameFlow.value
            val audioFile = FileUtil.getAudioFile(context, audioDirName, result.fileName)
            if (!audioFile.exists()) {
                logManager.addLog("Error: Audio file not found for retranscription: ${result.fileName}. Cannot retranscribe.")
                // Optionally update status to FAILED here if desired
                val updatedResult = result.copy(transcriptionStatus = "FAILED", transcription = "Audio file not found.")
                transcriptionResultRepository.addResult(updatedResult)
                return@launch
            }

            logManager.addLog("Marking ${result.fileName} as PENDING for retranscription.")
            val pendingResult = result.copy(transcriptionStatus = "PENDING")
            transcriptionResultRepository.addResult(pendingResult)

            // Immediately trigger processing
            processPendingTranscriptions()
        }
    }

    override fun addPendingTranscription(fileName: String) {
        scope.launch {
            logManager.addLog("Creating pending transcription record for $fileName")
            // Check if a result for this file already exists to avoid duplicates
            val existing = transcriptionResultRepository.transcriptionResultsFlow.first().find { it.fileName == fileName }
            if (existing != null) {
                logManager.addLog("Transcription record for $fileName already exists. Skipping creation.")
                return@launch
            }

            val newResult = TranscriptionResult(
                fileName = fileName,
                transcription = "", // Empty transcription initially
                locationData = null, // Location will be fetched during transcription
                transcriptionStatus = "PENDING"
            )
            transcriptionResultRepository.addResult(newResult)
            logManager.addLog("Pending transcription for $fileName saved.")
        }
    }

    override fun addManualTranscription(text: String) {
        scope.launch {
            var locationData: LocationData? = currentForegroundLocationFlow.value
            if (locationData != null) {
                logManager.addLog("Using pre-collected location for manual transcription: Lat=${locationData?.latitude}, Lng=${locationData?.longitude}")
            } else {
                 logManager.addLog("Pre-collected location not available for manual transcription. Proceeding without location data.")
            }

            val timestamp = System.currentTimeMillis()
            val manualFileName = "M${FileUtil.formatTimestampForFileName(timestamp)}.txt"

            val googleTaskTitleLength = googleTaskTitleLengthFlow.first()
            val rawTitle = text.take(googleTaskTitleLength)
            val cleanTitle = rawTitle.replace("\n", "")
            val title = if (cleanTitle.isBlank()) "Manual Transcription" else cleanTitle

            val notes = if (text.length > googleTaskTitleLength) {
                text
            } else {
                null
            }

            val newResult = TranscriptionResult(
                fileName = manualFileName,
                transcription = title, // Save the title as the main transcription
                locationData = locationData,
                googleTaskNotes = notes // Save full transcription as notes if overflow
            )

            transcriptionResultRepository.addResult(newResult)
            logManager.addLog("Manual transcription added: $manualFileName")

            scope.launch { cleanupTranscriptionResultsAndAudioFiles() }
        }
    }

    override fun updateDisplayOrder(reorderedList: List<TranscriptionResult>) {
        scope.launch {
            // Update local display order first
            val updatedList = reorderedList.mapIndexed { index, result ->
                result.copy(displayOrder = index)
            }
            transcriptionResultRepository.updateResults(updatedList)
            logManager.addLog("Transcription results order updated locally.")

            // Then, sync reordering to Google Tasks if applicable
            val sortMode = appSettingsRepository.sortModeFlow.first()
            if (googleTasksIntegration.account.value != null && sortMode == SortMode.CUSTOM) {
                logManager.addLog("Syncing custom order to Google Tasks.")
                val syncedItemsInOrder = updatedList.filter { it.googleTaskId != null }

                for ((index, item) in syncedItemsInOrder.withIndex()) {
                    val taskId = item.googleTaskId!! // Already filtered for non-null
                    val previousTaskId = if (index > 0) syncedItemsInOrder[index - 1].googleTaskId else null
                    googleTasksIntegration.moveTask(taskId, previousTaskId)
                }
                logManager.addLog("Finished syncing custom order to Google Tasks.")
            }
        }
    }

    override fun clearTranscriptionResults() {
        scope.launch {
            logManager.addLog("Starting clear all transcription results...")
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
                            logManager.addLog("Deleted local-only audio file during clear all: ${result.fileName}")
                        } else {
                            logManager.addLog("Failed to delete local-only audio file during clear all: ${result.fileName}")
                        }
                    }
                } else {
                    // Synced item: soft delete (mark for remote deletion during next sync)
                    updatedListForRepo.add(result.copy(isDeletedLocally = true))
                }
            }
            transcriptionResultRepository.updateResults(updatedListForRepo)

            logManager.addLog("All local-only transcription results permanently deleted. Synced results marked for soft deletion.")
            updateLocalAudioFileCount() // Update count after deletions
        }
    }

    override fun removeTranscriptionResult(result: TranscriptionResult) {
        scope.launch {
            if (result.googleTaskId != null) {
                // If synced with Google Tasks, perform a soft delete locally.
                // Actual deletion from Google Tasks and permanent local deletion will happen during sync.
                transcriptionResultRepository.removeResult(result) // This now sets isDeletedLocally = true
                logManager.addLog("Soft-deleted transcription result (synced with Google Tasks): ${result.fileName}")
            } else {
                // If not synced with Google Tasks, permanently delete locally and its audio file immediately.
                transcriptionResultRepository.permanentlyRemoveResult(result)
                logManager.addLog("Permanently deleted local-only transcription result: ${result.fileName}")

                val audioFile = FileUtil.getAudioFile(context, audioDirNameFlow.value, result.fileName)
                if (audioFile.exists()) {
                    if (audioFile.delete()) {
                        logManager.addLog("Associated audio file deleted: ${result.fileName}")
                        updateLocalAudioFileCount()
                    } else {
                        logManager.addLog("Failed to delete associated audio file: ${result.fileName}")
                    }
                } else {
                    logManager.addLog("Associated audio file not found for local-only result: ${result.fileName}")
                }
            }
        }
    }

    override fun updateTranscriptionResult(originalResult: TranscriptionResult, newTranscription: String, newNote: String?) {
        scope.launch {
            // Create a new TranscriptionResult with the updated transcription and notes
            val updatedResult = originalResult.copy(
                transcription = newTranscription,
                googleTaskNotes = newNote, // Update googleTaskNotes
                lastEditedTimestamp = System.currentTimeMillis()
            )
            // The addResult method now handles updates, so we just call that.
            transcriptionResultRepository.addResult(updatedResult)
            logManager.addLog("Transcription result for ${originalResult.fileName} updated.")
        }
    }

    override fun removeTranscriptionResults(fileNames: Set<String>, clearSelectionCallback: () -> Unit) {
        scope.launch {
            // We need to fetch the current results to filter
            val currentResults = transcriptionResultRepository.transcriptionResultsFlow.first()
            val resultsToRemove = currentResults.filter { fileNames.contains(it.fileName) }
            resultsToRemove.forEach { result ->
                removeTranscriptionResult(result) // Use the existing single delete function
            }
            clearSelectionCallback() // Clear selection after deletion
            logManager.addLog("Removed ${resultsToRemove.size} selected transcription results.")
        }
    }


    override fun findAndProcessUnlinkedWavFiles() {
        scope.launch {
            logManager.addLog("Starting to find and process unlinked WAV files...")

            val audioDirName = audioDirNameFlow.value
            val audioDir = context.getExternalFilesDir(audioDirName)
            if (audioDir == null || !audioDir.exists()) {
                logManager.addLog("Audio directory not found: $audioDirName. No WAV files to process.")
                return@launch
            }

            val localWavFiles = audioDir.listFiles { _, name ->
                name.matches(Regex("""R\d{4}-\d{2}-\d{2}-\d{2}-\d{2}-\d{2}\.wav"""))
            }?.map { it.name }?.toSet() ?: emptySet()

            if (localWavFiles.isEmpty()) {
                logManager.addLog("No local WAV files found in $audioDirName.")
                return@launch
            }

            val recordedFileNames = transcriptionResultRepository.transcriptionResultsFlow.first()
                .map { it.fileName }
                .toSet()

            val unlinkedWavFiles = localWavFiles.filter { it !in recordedFileNames }

            if (unlinkedWavFiles.isEmpty()) {
                logManager.addLog("No unlinked WAV files found.")
            } else {
                logManager.addLog("Found ${unlinkedWavFiles.size} unlinked WAV file(s). Adding to pending transcriptions.")
                unlinkedWavFiles.forEach { fileName ->
                    addPendingTranscription(fileName)
                }
                logManager.addLog("Finished adding unlinked WAV files to pending transcriptions.")
                processPendingTranscriptions()
            }

            // Ensure cleanup and count update after processing
            cleanupTranscriptionResultsAndAudioFiles()
            updateLocalAudioFileCount()

            logManager.addLog("Finished finding and processing unlinked WAV files.")
        }
    }

    override fun resetTranscriptionState() {
        _transcriptionState.value = "Idle"
        _transcriptionResult.value = null
    }
    
    override fun onCleared() {
        // Placeholder for future cleanup if needed
    }
}