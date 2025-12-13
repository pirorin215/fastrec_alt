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
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.UUID

class FileTransferManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: BleRepository,
    private val transcriptionManager: TranscriptionManagement,
    private val audioDirNameFlow: StateFlow<String>,
    private val bleMutex: Mutex,
    private val logManager: LogManager,
    private val sendCommandCallback: (String) -> Unit,
    private val sendAckCallback: (ByteArray) -> Unit,
    private val _currentOperation: MutableStateFlow<BleOperation>,
    private val bleDeviceCommandManager: BleDeviceCommandManager,
    private val _connectionState: StateFlow<String>
) {

    companion object {
        const val MAX_DELETE_RETRIES = 3
        const val DELETE_RETRY_DELAY_MS = 1000L

        private const val PACKET_TIMEOUT_MS = 30000L // Timeout if no packet is received for this duration
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
    private var downloadTimeoutJob: Job? = null

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
                    logManager.addLog("Log file saved successfully to Downloads: $fileName")
                    return it.toString()
                } ?: throw Exception("Failed to create new MediaStore entry for log file.")
            } else {
                val audioDir = context.getExternalFilesDir(audioDirNameFlow.value)
                if (audioDir != null && !audioDir.exists()) {
                    audioDir.mkdirs()
                }
                val file = File(audioDir, fileName)
                FileOutputStream(file).use { it.write(data) }
                logManager.addLog("File saved successfully to app-specific directory: ${file.absolutePath}")
                return file.absolutePath
            }
        } catch (e: Exception) {
            logManager.addLog("Error saving file: ${e.message}")
            _fileTransferState.value = "Error: ${e.message}"
            null
        }
    }

    fun handleCharacteristicChanged(
        characteristic: android.bluetooth.BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (characteristic.uuid != UUID.fromString(BleRepository.RESPONSE_UUID_STRING)) return

        when (_currentOperation.value) {
            BleOperation.DOWNLOADING_FILE -> {
                val filePath = handleFileDownloadDataInternal(value)
                if (filePath != null) {
                    currentCommandCompletion?.complete(Pair(true, filePath))
                }
            }
            BleOperation.DELETING_FILE -> {
                val response = value.toString(Charsets.UTF_8).trim()
                logManager.addLog("Received response for file deletion: $response")
                if (response.startsWith("OK: File")) {
                    currentDeleteCompletion?.complete(true)
                } else {
                    logManager.addLog("Unexpected or error response during file deletion: $response")
                    currentDeleteCompletion?.complete(false)
                }
            }
            else -> {
                // Not for us
            }
        }
    }

    private fun resetAndStartDownloadTimeoutTimer() {
        downloadTimeoutJob?.cancel()
        downloadTimeoutJob = scope.launch {
            delay(PACKET_TIMEOUT_MS)
            if (isActive) {
                logManager.addLog("File download timed out: No data received for ${PACKET_TIMEOUT_MS}ms.")
                currentCommandCompletion?.complete(Pair(false, null))
            }
        }
    }

    private fun handleFileDownloadDataInternal(value: ByteArray): String? {
        resetAndStartDownloadTimeoutTimer() // Reset timer on any received data

        when (_fileTransferState.value) {
            "WaitingForStart" -> {
                if (value.contentEquals("START".toByteArray())) {
                    logManager.addLog("Received START signal. Sending START_ACK.")
                    sendAckCallback("START_ACK".toByteArray(Charsets.UTF_8))
                    _fileTransferState.value = "Downloading"
                    _transferStartTime = System.currentTimeMillis()
                    responseBuffer.clear()
                    _downloadProgress.value = 0
                } else {
                    logManager.addLog("Waiting for START, but received: ${value.toString(Charsets.UTF_8)}")
                    downloadTimeoutJob?.cancel()
                    currentCommandCompletion?.complete(Pair(false, null))
                }
            }
            "Downloading" -> {
                if (value.contentEquals("EOF".toByteArray())) {
                    logManager.addLog("End of file transfer signal received.")
                    downloadTimeoutJob?.cancel() // Success, cancel timeout
                    return saveFile(responseBuffer.toByteArray())
                } else if (value.toString(Charsets.UTF_8).startsWith("ERROR:")) {
                    val errorMessage = value.toString(Charsets.UTF_8)
                    logManager.addLog("Received error during transfer: $errorMessage")
                    downloadTimeoutJob?.cancel() // Error, cancel timeout
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
                logManager.addLog("Cannot download file, not connected.")
                return@launch
            }

            var downloadResult: Pair<Boolean, String?>? = null

            bleMutex.withLock {
                if (_currentOperation.value != BleOperation.IDLE) {
                    logManager.addLog("Cannot download file '$fileName', another operation is in progress: ${_currentOperation.value}")
                    return@withLock
                }

                try {
                    _currentOperation.value = BleOperation.DOWNLOADING_FILE
                    _fileTransferState.value = "WaitingForStart"
                    currentDownloadingFileName = fileName
                    currentCommandCompletion = CompletableDeferred()

                    val fileEntry = bleDeviceCommandManager.fileList.value.find { it.name == fileName }
                    val fileSize = fileEntry?.size ?: 0L
                    _currentFileTotalSize.value = fileSize

                    logManager.addLog("Requesting file: $fileName (size: $fileSize bytes)")
                    resetAndStartDownloadTimeoutTimer() // Start the timeout timer
                    sendCommandCallback("GET:file:$fileName")

                    downloadResult = currentCommandCompletion!!.await()

                    if (downloadResult?.first != true) {
                        // Log message is now handled by the timeout job or error handler
                    }
                } catch (e: Exception) {
                    logManager.addLog("An unexpected error occurred during downloadFile: ${e.message}")
                    downloadResult = Pair(false, null)
                } finally {
                    downloadTimeoutJob?.cancel()
                    _currentOperation.value = BleOperation.IDLE
                    _fileTransferState.value = "Idle"
                    currentDownloadingFileName = null
                    resetFileTransferMetrics()
                    logManager.addLog("downloadFile lock released for $fileName.")
                }
            }

            val (downloadSuccess, savedFilePath) = downloadResult ?: Pair(false, null)
            if (downloadSuccess && savedFilePath != null) {
                if (fileName.startsWith("log.", ignoreCase = true)) {
                    logManager.addLog("Log file '$fileName' downloaded and saved to: $savedFilePath")
                } else if (fileName.endsWith(".wav", ignoreCase = true)) {
                    val file = File(savedFilePath)
                    if (file.exists()) {
                        transcriptionManager.addPendingTranscription(file.name)
                        logManager.addLog("Created pending transcription record for ${file.name}.")

                        scope.launch { transcriptionManager.cleanupTranscriptionResultsAndAudioFiles() }
                        transcriptionManager.updateLocalAudioFileCount()

                        // Automatically delete the file from the microcontroller after successful download and queuing
                        deleteFileAndUpdateList(fileName)
                    } else {
                        logManager.addLog("Error: Downloaded WAV file not found at ${file.absolutePath}. Cannot queue or delete.")
                    }
                }
            } else {
                logManager.addLog("Error: File '$fileName' download failed or saved path is null.")
                // To prevent immediate retry loops on persistent device errors, fetch file list only after failure.
                // The auto-refresher will eventually handle this, but an immediate fetch can be useful.
                scope.launch {
                    delay(1000) // Small delay before refetching
                    bleDeviceCommandManager.fetchFileList(_connectionState.value)
                }
            }
        }
    }


    private suspend fun deleteFileAndUpdateList(fileName: String) {
        if (_connectionState.value != "Connected") {
            // If not connected, we cannot delete the file from the device.
            // However, we should still remove it from the local list if the download was successful,
            // assuming the intention is to process it and then treat it as "done".
            // For now, we only log and return, as deleting without connection is not possible.
            logManager.addLog("Cannot delete file, not connected.")
            return
        }

        bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addLog("Cannot delete file '$fileName', another operation is in progress: ${_currentOperation.value}")
                return@withLock
            }

            var success = false
            try {
                _currentOperation.value = BleOperation.DELETING_FILE
                for (i in 0..MAX_DELETE_RETRIES) {
                    currentDeleteCompletion = CompletableDeferred()
                    logManager.addLog("Sending command to delete file: DEL:file:$fileName (Attempt ${i + 1}/${MAX_DELETE_RETRIES + 1})")

                    sendCommandCallback("DEL:file:$fileName")

                    success = try {
                        withTimeout(10000L) {
                            currentDeleteCompletion!!.await()
                        }
                    } catch (e: TimeoutCancellationException) {
                        logManager.addLog("DEL:file:$fileName command timed out. Error: ${e.message}")
                        false
                    }

                    if (success) {
                        logManager.addLog("Successfully deleted file: $fileName.")
                        // Instead of re-fetching the list, remove it from the local state
                        bleDeviceCommandManager.removeFileFromList(fileName)
                        transcriptionManager.updateLocalAudioFileCount()
                        break
                    } else if (i < MAX_DELETE_RETRIES) {
                        logManager.addLog("Failed to delete file: $fileName. Retrying in ${DELETE_RETRY_DELAY_MS}ms...")
                        delay(DELETE_RETRY_DELAY_MS)
                    }
                }
            } finally {
                if (!success) {
                    logManager.addLog("Failed to delete file: $fileName after all attempts.")
                }
                _currentOperation.value = BleOperation.IDLE
                logManager.addLog("deleteFileAndUpdateList operation scope finished for $fileName.")
            }
        }
    }
}