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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

class FileTransferManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: BleRepository,
    private val transcriptionManager: TranscriptionManager,
    private val audioDirNameFlow: StateFlow<String>,
    private val bleMutex: Mutex,
    private val logCallback: (String) -> Unit,
    private val sendCommandCallback: (String) -> Unit,
    private val sendAckCallback: (ByteArray) -> Unit,
    private val _currentOperation: MutableStateFlow<BleOperation>,
    private val _fileList: StateFlow<List<com.pirorin215.fastrecmob.data.FileEntry>>,
    private val _connectionState: StateFlow<String>,
    private val fetchFileListCallback: suspend () -> Unit
) {

    companion object {
        const val MAX_DELETE_RETRIES = 3
        const val DELETE_RETRY_DELAY_MS = 1000L
        const val RESPONSE_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ab"
    }

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
    private var currentCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>? = null
    private var currentDeleteCompletion: CompletableDeferred<Boolean>? = null

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
                    resolver.openOutputStream(it)?.use { stream ->
                        stream.write(data)
                    }
                    logCallback("Log file saved successfully to Downloads: $fileName")
                    return it.toString()
                } ?: throw Exception("Failed to create new MediaStore entry for log file.")
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

    fun handleCharacteristicChanged(
        characteristic: android.bluetooth.BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (characteristic.uuid != UUID.fromString(RESPONSE_UUID_STRING)) return

        when (_currentOperation.value) {
            BleOperation.DOWNLOADING_FILE -> {
                val filePath = handleFileDownloadDataInternal(value)
                if (filePath != null) {
                    currentCommandCompletion?.complete(Pair(true, filePath))
                }
            }
            BleOperation.DELETING_FILE -> {
                val response = value.toString(Charsets.UTF_8).trim()
                logCallback("Received response for file deletion: $response")
                if (response.startsWith("OK: File")) {
                    currentDeleteCompletion?.complete(true)
                } else {
                    logCallback("Unexpected or error response during file deletion: $response")
                    currentDeleteCompletion?.complete(false)
                }
            }
            else -> {
                // Not for us
            }
        }
    }

    private fun handleFileDownloadDataInternal(value: ByteArray): String? {
        when (_fileTransferState.value) {
            "WaitingForStart" -> {
                if (value.contentEquals("START".toByteArray())) {
                    logCallback("Received START signal. Sending START_ACK.")
                    sendAckCallback("START_ACK".toByteArray(Charsets.UTF_8))
                    _fileTransferState.value = "Downloading"
                    _transferStartTime = System.currentTimeMillis()
                    responseBuffer.clear()
                    _downloadProgress.value = 0
                } else {
                    logCallback("Waiting for START, but received: ${value.toString(Charsets.UTF_8)}")
                    currentCommandCompletion?.complete(Pair(false, null))
                }
            }
            "Downloading" -> {
                if (value.contentEquals("EOF".toByteArray())) {
                    logCallback("End of file transfer signal received.")
                    return saveFile(responseBuffer.toByteArray())
                } else if (value.toString(Charsets.UTF_8).startsWith("ERROR:")) {
                    val errorMessage = value.toString(Charsets.UTF_8)
                    logCallback("Received error during transfer: $errorMessage")
                    currentCommandCompletion?.complete(Pair(false, null))
                } else {
                    responseBuffer.addAll(value.toList())
                    _downloadProgress.value = responseBuffer.size
                    val elapsedTime = (System.currentTimeMillis() - _transferStartTime) / 1000.0f
                    if (elapsedTime > 0) {
                        _transferKbps.value = (responseBuffer.size / 1024.0f) / elapsedTime
                    }
                    sendAckCallback("ACK".toByteArray(Charsets.UTF_8))
                }
            }
        }
        return null
    }

    fun downloadFileAndProcess(fileName: String) {
        scope.launch {
            if (_connectionState.value != "Connected") {
                logCallback("Cannot download file, not connected.")
                return@launch
            }

            var downloadResult: Pair<Boolean, String?>? = null

            bleMutex.withLock {
                if (_currentOperation.value != BleOperation.IDLE) {
                    logCallback("Cannot download file '$fileName', another operation is in progress: ${_currentOperation.value}")
                    return@withLock
                }

                try {
                    _currentOperation.value = BleOperation.DOWNLOADING_FILE
                    _fileTransferState.value = "WaitingForStart"
                    currentDownloadingFileName = fileName
                    currentCommandCompletion = CompletableDeferred()

                    val fileEntry = _fileList.value.find { it.name == fileName }
                    val fileSize = fileEntry?.size ?: 0L
                    _currentFileTotalSize.value = fileSize

                    logCallback("Requesting file: $fileName (size: $fileSize bytes)")
                    sendCommandCallback("GET:file:$fileName")

                    val timeout = 20000L + (fileSize / 1024L) * 100L // Adjusted timeout
                    downloadResult = withTimeoutOrNull(timeout) {
                        currentCommandCompletion!!.await()
                    }

                    if (downloadResult?.first != true) {
                        logCallback("File download failed for: $fileName (or timed out)")
                    }
                } catch (e: Exception) {
                    logCallback("An unexpected error occurred during downloadFile: ${e.message}")
                    downloadResult = Pair(false, null)
                } finally {
                    _currentOperation.value = BleOperation.IDLE
                    _fileTransferState.value = "Idle"
                    currentDownloadingFileName = null
                    resetFileTransferMetrics()
                    logCallback("downloadFile lock released for $fileName.")
                }
            }

            val (downloadSuccess, savedFilePath) = downloadResult ?: Pair(false, null)
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

                        // Automatically delete the file from the microcontroller after successful download and queuing
                        deleteFileAndRefresh(fileName)
                    } else {
                        logCallback("Error: Downloaded WAV file not found at ${file.absolutePath}. Cannot queue or delete.")
                    }
                }
            } else {
                logCallback("Error: File '$fileName' download failed or saved path is null.")
            }
        }
    }


    private suspend fun deleteFileAndRefresh(fileName: String) {
        if (_connectionState.value != "Connected") {
            logCallback("Cannot delete file, not connected.")
            return
        }

        bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logCallback("Cannot delete file '$fileName', another operation is in progress: ${_currentOperation.value}")
                return@withLock
            }

            var success = false
            try {
                _currentOperation.value = BleOperation.DELETING_FILE
                for (i in 0..MAX_DELETE_RETRIES) {
                    currentDeleteCompletion = CompletableDeferred()
                    logCallback("Sending command to delete file: DEL:file:$fileName (Attempt ${i + 1}/${MAX_DELETE_RETRIES + 1})")

                    sendCommandCallback("DEL:file:$fileName")

                    success = try {
                        withTimeout(10000L) {
                            currentDeleteCompletion!!.await()
                        }
                    } catch (e: TimeoutCancellationException) {
                        logCallback("DEL:file:$fileName command timed out. Error: ${e.message}")
                        false
                    }

                    if (success) {
                        logCallback("Successfully deleted file: $fileName.")
                        delay(500L) // Give device time to process
                        fetchFileListCallback()
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
                logCallback("deleteFileOnMicrocontroller operation scope finished for $fileName.")
            }
        }
    }
}