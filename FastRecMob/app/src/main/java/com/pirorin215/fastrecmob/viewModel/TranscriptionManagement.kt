package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.TranscriptionResult
import kotlinx.coroutines.flow.StateFlow

interface TranscriptionManagement {
    val transcriptionState: StateFlow<String>
    val transcriptionResult: StateFlow<String?>
    val audioFileCount: StateFlow<Int>

    fun updateLocalAudioFileCount()
    fun processPendingTranscriptions()
    suspend fun cleanupTranscriptionResultsAndAudioFiles()
    fun retranscribe(result: TranscriptionResult)
    fun addPendingTranscription(fileName: String)
    fun addManualTranscription(text: String)
    fun updateDisplayOrder(reorderedList: List<TranscriptionResult>)
    fun clearTranscriptionResults()
    fun removeTranscriptionResult(result: TranscriptionResult)
    fun updateTranscriptionResult(originalResult: TranscriptionResult, newTranscription: String, newNote: String?)
    fun removeTranscriptionResults(fileNames: Set<String>, clearSelectionCallback: () -> Unit)
    fun resetTranscriptionState()
    fun onCleared()
}
