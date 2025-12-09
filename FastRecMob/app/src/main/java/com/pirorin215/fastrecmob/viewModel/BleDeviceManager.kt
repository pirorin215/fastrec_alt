package com.pirorin215.fastrecmob.viewModel

import android.content.Context
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import com.pirorin215.fastrecmob.data.FileEntry
import com.pirorin215.fastrecmob.data.parseFileEntries
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json

class BleDeviceManager(
    private val scope: CoroutineScope,
    private val context: Context,
    private val sendCommand: (String) -> Unit,
    private val logManager: LogManager,
    private val _currentOperation: MutableStateFlow<BleOperation>,
    private val bleMutex: Mutex,
    private val onFileListUpdated: () -> Unit // Callback to trigger checking for new files
) {
    private val _deviceInfo = MutableStateFlow<DeviceInfoResponse?>(null)
    val deviceInfo = _deviceInfo.asStateFlow()

    private val _fileList = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileList = _fileList.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val responseBuffer = mutableListOf<Byte>()
    private var currentCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>? = null
    private var timeSyncJob: Job? = null

    companion object {
        const val TIME_SYNC_INTERVAL_MS = 300000L // 5 minutes
    }

    suspend fun syncTime(connectionState: String): Boolean {
        if (connectionState != "Connected") {
            logManager.addLog("Cannot sync time, not connected.")
            return false
        }

        return bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addLog("Cannot sync time, busy: ${_currentOperation.value}")
                return@withLock false
            }

            try {
                _currentOperation.value = BleOperation.SENDING_TIME
                responseBuffer.clear()
                val timeCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentCommandCompletion = timeCompletion

                val currentTimestampSec = System.currentTimeMillis() / 1000
                val timeCommand = "SET:time:$currentTimestampSec"
                logManager.addLog("Sending time synchronization command: $timeCommand")
                sendCommand(timeCommand)

                val (timeSyncSuccess, _) = withTimeoutOrNull(5000L) {
                    timeCompletion.await()
                } ?: Pair(false, "Timeout")

                if (timeSyncSuccess) {
                    logManager.addLog("Time synchronization successful.")
                } else {
                    logManager.addLog("Time synchronization failed or timed out.")
                }
                timeSyncSuccess
            } catch (e: Exception) {
                logManager.addLog("Error during time sync: ${e.message}")
                false
            } finally {
                _currentOperation.value = BleOperation.IDLE
                currentCommandCompletion = null
            }
        }
    }


    fun startTimeSyncJob() {
        timeSyncJob?.cancel()
        timeSyncJob = scope.launch {
            while (true) {
                delay(TIME_SYNC_INTERVAL_MS)
                if (_currentOperation.value == BleOperation.IDLE) {
                    // Using tryLock to avoid waiting if another operation is in progress
                    if (bleMutex.tryLock()) {
                        try {
                            if (_currentOperation.value == BleOperation.IDLE) {
                                val periodicTimestampSec = System.currentTimeMillis() / 1000
                                val periodicTimeCommand = "SET:time:$periodicTimestampSec"
                                logManager.addLog("Sending periodic time synchronization command: $periodicTimeCommand")
                                sendCommand(periodicTimeCommand)
                                // This is a best-effort periodic sync, so we don't wait for the response.
                                // The device will either get it or not. The main sync is more important.
                            }
                        } finally {
                            bleMutex.unlock()
                        }
                    } else {
                        logManager.addLog("Skipping periodic time sync: another operation is in progress.")
                    }
                }
            }
        }
    }

    fun stopTimeSyncJob() {
        timeSyncJob?.cancel()
        timeSyncJob = null
    }

    suspend fun fetchDeviceInfo(connectionState: String): Boolean {
        if (connectionState != "Connected") {
            logManager.addLog("Cannot fetch device info, not connected.")
            return false
        }

        return bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addLog("Cannot fetch device info, busy: ${_currentOperation.value}")
                return@withLock false
            }

            try {
                _currentOperation.value = BleOperation.FETCHING_DEVICE_INFO
                responseBuffer.clear()
                logManager.addLog("Requesting device info from device...")

                val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentCommandCompletion = commandCompletion

                sendCommand("GET:info")

                val (success, _) = withTimeoutOrNull(15000L) {
                    commandCompletion.await()
                } ?: Pair(false, "Timeout")

                if (success) {
                    logManager.addLog("GET:info command completed successfully.")
                } else {
                    logManager.addLog("GET:info command failed or timed out.")
                }
                success
            } catch (e: Exception) {
                logManager.addLog("Error fetchDeviceInfo: ${e.message}")
                false
            } finally {
                _currentOperation.value = BleOperation.IDLE
                currentCommandCompletion = null
            }
        }
    }

    suspend fun fetchFileList(connectionState: String, extension: String = "wav"): Boolean {
        if (connectionState != "Connected") {
            logManager.addLog("Cannot fetch file list, not connected.")
            return false
        }

        return bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addLog("Cannot fetch file list, busy: ${_currentOperation.value}")
                return@withLock false
            }

            try {
                _currentOperation.value = BleOperation.FETCHING_FILE_LIST
                responseBuffer.clear()
                logManager.addLog("Requesting file list (GET:ls:$extension)...")

                val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentCommandCompletion = commandCompletion

                sendCommand("GET:ls:$extension")

                val (success, _) = withTimeoutOrNull(15000L) {
                    commandCompletion.await()
                } ?: Pair(false, "Timeout")

                if (success) {
                    logManager.addLog("GET:ls:$extension completed.")
                    if (extension == "wav") {
                        onFileListUpdated()
                    }
                } else {
                    logManager.addLog("GET:ls:$extension failed or timed out.")
                }
                success
            } catch (e: Exception) {
                logManager.addLog("Error fetchFileList: ${e.message}")
                false
            } finally {
                _currentOperation.value = BleOperation.IDLE
                currentCommandCompletion = null
            }
        }
    }


    fun removeFileFromList(fileName: String) {
        _fileList.value = _fileList.value.filterNot { it.name == fileName }
        logManager.addLog("Removed '$fileName' from local file list.")
        onFileListUpdated() // Callback to trigger checking for new files
    }

    fun handleResponse(value: ByteArray) {
        when (_currentOperation.value) {
            BleOperation.FETCHING_DEVICE_INFO -> {
                val incomingString = value.toString(Charsets.UTF_8).trim()
                if (responseBuffer.isEmpty() && !incomingString.startsWith("{") && !incomingString.startsWith("ERROR:")) {
                    return
                }
                responseBuffer.addAll(value.toList())
                val currentBufferAsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)

                if (currentBufferAsString.trim().endsWith("}")) {
                    try {
                        val parsedResponse = json.decodeFromString<DeviceInfoResponse>(currentBufferAsString)
                        _deviceInfo.value = parsedResponse
                        logManager.addLog("Parsed DeviceInfo: ${parsedResponse.batteryLevel}%")
                        currentCommandCompletion?.complete(Pair(true, null))
                    } catch (e: Exception) {
                        logManager.addLog("Error parsing DeviceInfo: ${e.message}")
                        currentCommandCompletion?.complete(Pair(false, e.message))
                    }
                } else if (currentBufferAsString.startsWith("ERROR:")) {
                    logManager.addLog("Error response GET:info: $currentBufferAsString")
                    currentCommandCompletion?.complete(Pair(false, currentBufferAsString))
                }
            }
            BleOperation.FETCHING_FILE_LIST -> {
                val incomingString = value.toString(Charsets.UTF_8).trim()
                if (responseBuffer.isEmpty() && !incomingString.startsWith("[") && !incomingString.startsWith("ERROR:")) {
                    if (incomingString == "[]") {
                        _fileList.value = emptyList()
                        currentCommandCompletion?.complete(Pair(true, null))
                    }
                    return
                }
                responseBuffer.addAll(value.toList())
                val currentBufferAsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)

                if (currentBufferAsString.trim().endsWith("]")) {
                    try {
                        _fileList.value = parseFileEntries(currentBufferAsString)
                        logManager.addLog("Parsed FileList. Count: ${_fileList.value.size}")
                        currentCommandCompletion?.complete(Pair(true, null))
                    } catch (e: Exception) {
                        logManager.addLog("Error parsing FileList: ${e.message}")
                        currentCommandCompletion?.complete(Pair(false, e.message))
                    }
                } else if (currentBufferAsString.startsWith("ERROR:")) {
                    logManager.addLog("Error response GET:ls: $currentBufferAsString")
                    _fileList.value = emptyList()
                    currentCommandCompletion?.complete(Pair(false, currentBufferAsString))
                }
            }
            BleOperation.SENDING_TIME -> {
                val response = value.toString(Charsets.UTF_8).trim()
                if (response.startsWith("OK: Time")) {
                    currentCommandCompletion?.complete(Pair(true, null))
                    responseBuffer.clear()
                } else if (response.startsWith("ERROR:")) {
                    currentCommandCompletion?.complete(Pair(false, response))
                    responseBuffer.clear()
                } else {
                    logManager.addLog("Unexpected response during SET:time: $response")
                    // Don't complete here, maybe the message is fragmented.
                    // Timeout will handle the failure.
                }
            }
            else -> {
                // This can happen if a response from a previous operation arrives late.
                // logManager.addLog("handleResponse called in non-listening state: ${_currentOperation.value}")
            }
        }
    }
}