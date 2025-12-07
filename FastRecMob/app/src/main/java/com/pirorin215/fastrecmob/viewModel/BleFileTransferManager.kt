package com.pirorin215.fastrecmob.viewModel

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.pirorin215.fastrecmob.data.BleRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

class BleFileTransferManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: BleRepository,
    private val transcriptionManager: TranscriptionManager,
    private val audioDirNameFlow: StateFlow<String>,
    private val logCallback: (String) -> Unit,
    private val sendCommandCallback: (String) -> Unit, // Callback to send command via BleViewModel
    private val sendAckCallback: (ByteArray) -> Unit, // Callback to send ACK via BleViewModel
    private val _currentOperation: MutableStateFlow<BleOperation>, // Current operation state
    private val _fileList: StateFlow<List<com.pirorin215.fastrecmob.data.FileEntry>>, // File list from BleViewModel
    private val _connectionState: StateFlow<String> // Connection state from BleViewModel
) {

    // Constants from BleViewModel.Companion
    companion object {
        const val MAX_DELETE_RETRIES = 3
        const val DELETE_RETRY_DELAY_MS = 1000L // 1 second
        const val RESPONSE_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ab" // Used in handleCharacteristicChanged
    }

    // State properties from BleViewModel
    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress: StateFlow<Int> = _downloadProgress.asStateFlow()

    private val _currentFileTotalSize = MutableStateFlow(0L)
    val currentFileTotalSize: StateFlow<Long> = _currentFileTotalSize.asStateFlow()

    private val _fileTransferState = MutableStateFlow("Idle")
    val fileTransferState: StateFlow<String> = _fileTransferState.asStateFlow()

    private val _transferKbps = MutableStateFlow(0.0f)
    val transferKbps: StateFlow<Float> = _transferKbps.asStateFlow()

    private var currentDownloadingFileName: String? = null
    private var _transferStartTime = 0L
    private var responseBuffer = mutableListOf<Byte>()

    // Methods
    fun resetFileTransferMetrics() {
        _downloadProgress.value = 0
        _currentFileTotalSize.value = 0L
        _transferKbps.value = 0.0f
        _transferStartTime = 0L
        responseBuffer.clear()
    }

    private fun saveFile(data: ByteArray): String? {
        val fileName = currentDownloadingFileName ?: "downloaded_file_${System.currentTimeMillis()}.bin"
        return try {
            if (fileName.startsWith("log.", ignoreCase = true)) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                }

                val resolver = context.contentResolver
                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

                uri?.let {
                    var outputStream: OutputStream? = null
                    try {
                        outputStream = resolver.openOutputStream(it)
                        outputStream?.use { stream ->
                            stream.write(data)
                        }
                        logCallback("Log file saved successfully to Downloads: $fileName")
                        return it.toString()
                    } catch (e: Exception) {
                        logCallback("Error saving log file to MediaStore: ${e.message}")
                        uri.let { u -> resolver.delete(u, null, null) }
                        throw e
                    } finally {
                        outputStream?.close()
                    }
                } ?: run {
                    throw Exception("Failed to create new MediaStore entry for log file.")
                }
            } else {
                val audioDir = context.getExternalFilesDir(audioDirNameFlow.value)
                if (audioDir != null && !audioDir.exists()) {
                    audioDir.mkdirs()
                }
                val file = File(audioDir, fileName)
                FileOutputStream(file).use { it.write(data) }
                logCallback("File saved successfully to app-specific directory: ${file.absolutePath}")
                return file.absolutePath
            }
        } catch (e: Exception) {
            logCallback("Error saving file: ${e.message}")
            _fileTransferState.value = "Error: ${e.message}"
            null
        }
    }

    // This method will be called from BleViewModel's handleCharacteristicChanged
    fun handleCharacteristicChanged(
        characteristic: android.bluetooth.BluetoothGattCharacteristic,
        value: ByteArray,
        currentCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>?,
        currentDeleteCompletion: CompletableDeferred<Boolean>?
    ) {
        if (characteristic.uuid != UUID.fromString(RESPONSE_UUID_STRING)) return

        when (_currentOperation.value) {
            BleOperation.DOWNLOADING_FILE -> {
                val filePath = handleFileDownloadDataInternal(value, currentCommandCompletion)
                if (filePath != null) {
                    currentCommandCompletion?.complete(Pair(true, filePath))
                }
            }
            BleOperation.DELETING_FILE -> {
                val response = value.toString(Charsets.UTF_8).trim()
                logCallback("Received response for file deletion: $response")
                if (response.startsWith("OK: File")) {
                    currentDeleteCompletion?.complete(true)
                    responseBuffer.clear()
                } else if (response.startsWith("ERROR:")) {
                    currentDeleteCompletion?.complete(false)
                    responseBuffer.clear()
                } else {
                    logCallback("Unexpected response during file deletion: $response")
                    currentDeleteCompletion?.complete(false)
                    responseBuffer.clear()
                }
            }
            else -> {
                // Not handled by file transfer manager, let BleViewModel handle it or ignore
            }
        }
    }

    private fun handleFileDownloadDataInternal(value: ByteArray, currentCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>?): String? {
        when (_fileTransferState.value) {
            "WaitingForStart" -> {
                if (value.contentEquals("START".toByteArray())) {
                    logCallback("Received START signal. Sending START_ACK.")
                    sendAckCallback("START_ACK".toByteArray(Charsets.UTF_8))
                    _fileTransferState.value = "Downloading"
                    _transferStartTime = System.currentTimeMillis()
                    responseBuffer.clear()
                    _downloadProgress.value = 0
                    return null
                } else {
                    logCallback("Waiting for START, but received: ${value.toString(Charsets.UTF_8)}")
                    currentCommandCompletion?.complete(Pair(false, null))
                    return null
                }
            }
            "Downloading" -> {
                if (value.contentEquals("EOF".toByteArray())) {
                    logCallback("End of file transfer signal received.")
                    val filePath = saveFile(responseBuffer.toByteArray())
                    if (filePath != null) {
                        return filePath
                    } else {
                        currentCommandCompletion?.complete(Pair(false, null))
                        return null
                    }
                } else if (value.toString(Charsets.UTF_8).startsWith("ERROR:")) {
                    val errorMessage = value.toString(Charsets.UTF_8)
                    logCallback("Received error during transfer: $errorMessage")
                    currentCommandCompletion?.complete(Pair(false, null))
                    return null
                } else {
                    responseBuffer.addAll(value.toList())
                    _downloadProgress.value = responseBuffer.size
                    val elapsedTime = (System.currentTimeMillis() - _transferStartTime) / 1000.0f
                    if (elapsedTime > 0) {
                        _transferKbps.value = (responseBuffer.size / 1024.0f) / elapsedTime
                    }
                    sendAckCallback("ACK".toByteArray(Charsets.UTF_8))
                    return null
                }
            }
        }
        return null
    }

    suspend fun downloadFile(
        fileName: String,
        bleMutex: Mutex,
        currentCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>? // Pass this from ViewModel
    ) {
        if (_connectionState.value != "Connected") {
            logCallback("Cannot download file, not connected.")
            return
        }

        var downloadResult: Pair<Boolean, String?> = Pair(false, null)
        bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logCallback("Cannot download file '$fileName', another operation is in progress: ${_currentOperation.value}")
                return
            }

            try {
                _currentOperation.value = BleOperation.DOWNLOADING_FILE
                _fileTransferState.value = "WaitingForStart"
                this.currentDownloadingFileName = fileName

                val fileEntry = _fileList.value.find { it.name == fileName }
                val fileSize = fileEntry?.size ?: 0L
                _currentFileTotalSize.value = fileSize

                logCallback("Requesting file: $fileName (size: $fileSize bytes)")
                sendCommandCallback("GET:file:$fileName")

                val timeout = 20000L + (fileSize / 8192L) * 1000L
                downloadResult = withTimeoutOrNull(timeout) {
                    currentCommandCompletion?.await() // Await the completion from characteristic changed
                } ?: Pair(false, null)

                if (downloadResult.first) {
                    logCallback("File download operation reported success for: $fileName.")
                } else {
                    logCallback("File download failed for: $fileName (or timed out)")
                }
            } catch (e: Exception) {
                logCallback("An unexpected error occurred during downloadFile: ${e.message}")
            } finally {
                if (_currentOperation.value == BleOperation.DOWNLOADING_FILE) {
                    _currentOperation.value = BleOperation.IDLE
                }
                _fileTransferState.value = "Idle"
                this.currentDownloadingFileName = null
                resetFileTransferMetrics()
                logCallback("downloadFile lock released for $fileName.")
            }
        }

        val (downloadSuccess, savedFilePath) = downloadResult
        if (downloadSuccess && savedFilePath != null) {
            if (fileName.startsWith("log.", ignoreCase = true)) {
                logCallback("Log file '$fileName' downloaded and saved to: $savedFilePath")
            } else if (fileName.endsWith(".wav", ignoreCase = true)) {
                val file = File(savedFilePath)
                if (file.exists()) {
                    transcriptionManager.transcriptionQueue.add(file.absolutePath)
                    logCallback("Added ${file.name} to transcription queue.")

                    transcriptionManager.cleanupTranscriptionResultsAndAudioFiles()
                    transcriptionManager.updateLocalAudioFileCount()
                    
                    logCallback("Post-download deletion of $fileName delegated to ViewModel.")
                } else {
                    logCallback("Error: Downloaded WAV file not found at ${file.absolutePath}. Cannot queue or delete.")
                }
            }
        } else {
            logCallback("Error: File '$fileName' download failed or saved path is null.")
        }
    }

    suspend fun deleteFileOnMicrocontroller(
        fileName: String,
        bleMutex: Mutex,
        currentDeleteCompletion: CompletableDeferred<Boolean>?, // Pass this from ViewModel
        fetchFileListCallback: suspend () -> Unit // Callback to BleViewModel to fetch file list
    ) {
        if (_connectionState.value != "Connected") {
            logCallback("Cannot delete file, not connected.")
            return
        }

        bleMutex.withLock {
            var success = false
            try {
                _currentOperation.value = BleOperation.DELETING_FILE
                for (i in 0..MAX_DELETE_RETRIES) {
                    logCallback("Sending command to delete file: DEL:file:$fileName (Attempt ${i + 1}/${MAX_DELETE_RETRIES + 1})")

                    sendCommandCallback("DEL:file:$fileName")

                    try {
                        success = withTimeout(10000L) {
                            currentDeleteCompletion?.await()
                        } ?: false
                    } catch (e: TimeoutCancellationException) {
                        logCallback("DEL:file:$fileName command timed out. Error: ${e.message}")
                        success = false
                    }

                    if (success) {
                        logCallback("Successfully deleted file: $fileName.")
                        // Allow some time for the device to process deletion before fetching the list
                        delay(1000L)
                        fetchFileListCallback() // Call back to ViewModel to fetch file list
                        transcriptionManager.updateLocalAudioFileCount()
                        break
                    } else if (i < MAX_DELETE_RETRIES) {
                        logCallback("Failed to delete file: $fileName. Retrying in ${DELETE_RETRY_DELAY_MS}ms...")
                        delay(DELETE_RETRY_DELAY_MS)
                    }
                }
            } finally {
                if (!success) {
                    logCallback("Failed to delete file: $fileName after all attempts.")
                }
                _currentOperation.value = BleOperation.IDLE
                logCallback("deleteFileOnMicrocontroller operation scope finished.")
            }
        }
    }
}