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
    private val addLog: (String) -> Unit,
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

    suspend fun onDeviceReady() {
        // Initial Time Sync
        bleMutex.withLock {
            _currentOperation.value = BleOperation.SENDING_TIME
            responseBuffer.clear()
            val timeCompletion = CompletableDeferred<Pair<Boolean, String?>>()
            currentCommandCompletion = timeCompletion

            val currentTimestampSec = System.currentTimeMillis() / 1000
            val timeCommand = "SET:time:$currentTimestampSec"
            addLog("Sending initial time synchronization command: $timeCommand")
            sendCommand(timeCommand)

            val (timeSyncSuccess, _) = withTimeoutOrNull(5000L) {
                timeCompletion.await()
            } ?: Pair(false, null)

            if (timeSyncSuccess) {
                addLog("Initial time synchronization successful.")
            } else {
                addLog("Initial time synchronization failed or timed out.")
            }
            _currentOperation.value = BleOperation.IDLE
        }

        // Fetch Info and List
        fetchDeviceInfo()

        // Start Periodic Time Sync
        startTimeSyncJob()
    }

    private fun startTimeSyncJob() {
        timeSyncJob?.cancel()
        timeSyncJob = scope.launch {
            while (true) {
                delay(TIME_SYNC_INTERVAL_MS)
                if (_currentOperation.value == BleOperation.IDLE) {
                    // We need to check connection state too, but Manager doesn't know it directly?
                    // We can assume if we are running, we want to try.
                    // Or we should pass connectionState provider.
                    // For now, let's try to acquire lock.
                    if (bleMutex.tryLock()) {
                         try {
                             if (_currentOperation.value == BleOperation.IDLE) {
                                 val periodicTimestampSec = System.currentTimeMillis() / 1000
                                 val periodicTimeCommand = "SET:time:$periodicTimestampSec"
                                 addLog("Sending periodic time synchronization command: $periodicTimeCommand")
                                 sendCommand(periodicTimeCommand)
                                 // Note: We don't wait for response here to block. 
                                 // But technically we should set SENDING_TIME to handle response.
                                 // The original code used withLock and sent command, but didn't wait?
                                 // Original:
                                 /*
                                    bleMutex.withLock {
                                        val periodicTimestampSec = ...
                                        sendCommand(...)
                                    }
                                 */
                                 // It didn't set _currentOperation = SENDING_TIME! 
                                 // It just sent it. If a response comes, it would be handled?
                                 // If response comes and IDLE, it might be logged as unexpected.
                                 // But periodic sync response might be ignored.
                                 // Let's keep it simple.
                             }
                         } finally {
                             bleMutex.unlock()
                         }
                    } else {
                        addLog("Skipping periodic time sync: busy.")
                    }
                }
            }
        }
    }

    fun stopTimeSyncJob() {
        timeSyncJob?.cancel()
        timeSyncJob = null
    }

    suspend fun fetchDeviceInfo(connectionState: String = "Connected", onInfoReceived: suspend () -> Unit = {}) {
        if (connectionState != "Connected") {
            addLog("Cannot fetch device info, not connected.")
            return
        }

        bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                addLog("Cannot fetch device info, busy: ${_currentOperation.value}")
                return@withLock
            }

            try {
                _currentOperation.value = BleOperation.FETCHING_DEVICE_INFO
                responseBuffer.clear()
                addLog("Requesting device info from device...")

                val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentCommandCompletion = commandCompletion

                sendCommand("GET:info")

                val (success, _) = withTimeoutOrNull(15000L) {
                    commandCompletion.await()
                } ?: Pair(false, null)

                if (success) {
                    addLog("GET:info command completed successfully.")
                    scope.launch { onInfoReceived() }
                } else {
                    addLog("GET:info command failed or timed out.")
                }
            } catch (e: Exception) {
                addLog("Error fetchDeviceInfo: ${e.message}")
            } finally {
                _currentOperation.value = BleOperation.IDLE
                currentCommandCompletion = null
            }
        }
    }

    suspend fun fetchFileList(connectionState: String = "Connected", extension: String = "wav") {
        if (connectionState != "Connected") {
            addLog("Cannot fetch file list, not connected.")
            return
        }

        bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                addLog("Cannot fetch file list, busy: ${_currentOperation.value}")
                return@withLock
            }

            try {
                _currentOperation.value = BleOperation.FETCHING_FILE_LIST
                responseBuffer.clear()
                addLog("Requesting file list (GET:ls:$extension)...")

                val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentCommandCompletion = commandCompletion

                sendCommand("GET:ls:$extension")

                val (success, _) = withTimeoutOrNull(15000L) {
                    commandCompletion.await()
                } ?: Pair(false, null)

                if (success) {
                    addLog("GET:ls:$extension completed.")
                    if (extension == "wav") {
                        onFileListUpdated()
                    }
                } else {
                    addLog("GET:ls:$extension failed or timed out.")
                }
            } catch (e: Exception) {
                addLog("Error fetchFileList: ${e.message}")
            } finally {
                _currentOperation.value = BleOperation.IDLE
                currentCommandCompletion = null
            }
        }
    }

    fun handleResponse(value: ByteArray) {
        // Dispatch based on _currentOperation.value
        // Logic copied from BleViewModel
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
                        addLog("Parsed DeviceInfo: ${parsedResponse.batteryLevel}%")
                        currentCommandCompletion?.complete(Pair(true, null))
                    } catch (e: Exception) {
                        addLog("Error parsing DeviceInfo: ${e.message}")
                        currentCommandCompletion?.complete(Pair(false, null))
                    }
                } else if (currentBufferAsString.startsWith("ERROR:")) {
                    addLog("Error response GET:info: $currentBufferAsString")
                    currentCommandCompletion?.complete(Pair(false, null))
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
                        addLog("Parsed FileList. Count: ${_fileList.value.size}")
                        currentCommandCompletion?.complete(Pair(true, null))
                    } catch (e: Exception) {
                        addLog("Error parsing FileList: ${e.message}")
                        currentCommandCompletion?.complete(Pair(false, null))
                    }
                } else if (currentBufferAsString.startsWith("ERROR:")) {
                    addLog("Error response GET:ls: $currentBufferAsString")
                    _fileList.value = emptyList()
                    currentCommandCompletion?.complete(Pair(false, null))
                }
            }
            BleOperation.SENDING_TIME -> {
                val response = value.toString(Charsets.UTF_8).trim()
                if (response.startsWith("OK: Time")) {
                    currentCommandCompletion?.complete(Pair(true, null))
                    responseBuffer.clear()
                } else if (response.startsWith("ERROR:")) {
                    currentCommandCompletion?.complete(Pair(false, null))
                    responseBuffer.clear()
                } else {
                     // Wait for more? or fail? Original failed.
                     // But we should accumulate if fragmented? 
                     // Time response is usually short.
                     // Original code:
                     /*
                    } else {
                        addLog("Unexpected response during SET:time: $response")
                        currentCommandCompletion?.complete(Pair(false, null)) 
                        responseBuffer.clear()
                    }
                     */
                     // It assumes full packet.
                     currentCommandCompletion?.complete(Pair(false, null))
                     responseBuffer.clear()
                }
            }
            else -> {}
        }
    }
}