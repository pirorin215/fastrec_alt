package com.pirorin215.fastrecmob.viewModel

import android.content.Context
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import com.pirorin215.fastrecmob.data.FileEntry
import com.pirorin215.fastrecmob.data.parseFileEntries
import com.pirorin215.fastrecmob.data.DeviceSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import java.util.UUID // Only if needed for handling specific UUIDs directly

class BleDeviceCommandManager(
    private val scope: CoroutineScope,
    private val context: Context, // Potentially not needed if BleRepository handles context
    private val sendCommand: (String) -> Unit,
    private val logManager: LogManager,
    private val _currentOperation: MutableStateFlow<BleOperation>,
    private val bleMutex: Mutex,
    private val onFileListUpdated: () -> Unit, // Callback from BleDeviceManager
    private val _navigationEvent: MutableSharedFlow<NavigationEvent> // From BleSettingsManager
) {
    // --- Properties from BleDeviceManager ---
    private val _deviceInfo = MutableStateFlow<DeviceInfoResponse?>(null)
    val deviceInfo = _deviceInfo.asStateFlow()

    private val _fileList = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileList = _fileList.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val deviceResponseBuffer = mutableListOf<Byte>() // Differentiate response buffers
    private var currentDeviceCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>? = null
    private var timeSyncJob: Job? = null

    companion object {
        const val TIME_SYNC_INTERVAL_MS = 300000L // 5 minutes
        // Moved from BleOrchestrator, if only used by device manager

    }

    // --- Properties from BleSettingsManager ---
    private val _deviceSettings = MutableStateFlow(DeviceSettings())
    val deviceSettings = _deviceSettings.asStateFlow()

    private val _remoteDeviceSettings = MutableStateFlow<DeviceSettings?>(null)
    val remoteDeviceSettings = _remoteDeviceSettings.asStateFlow()

    private val _settingsDiff = MutableStateFlow<String?>(null)
    val settingsDiff = _settingsDiff.asStateFlow()

    private val settingsResponseBuffer = mutableListOf<Byte>() // Differentiate response buffers
    private var currentSettingsCommandCompletion: CompletableDeferred<Pair<Boolean, String?>>? = null
    // Settings commands don't use mutex in BleSettingsManager, so need to consider if it should be added here
    // For now, let's assume they share the bleMutex via _currentOperation

    // --- Methods from BleDeviceManager ---
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
                deviceResponseBuffer.clear()
                val timeCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentDeviceCommandCompletion = timeCompletion

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
                currentDeviceCommandCompletion = null
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
                deviceResponseBuffer.clear()
                logManager.addLog("Requesting device info from device...")

                val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentDeviceCommandCompletion = commandCompletion

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
                currentDeviceCommandCompletion = null
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
                deviceResponseBuffer.clear()
                logManager.addLog("Requesting file list (GET:ls:$extension)...")

                val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentDeviceCommandCompletion = commandCompletion

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
                currentDeviceCommandCompletion = null
            }
        }
    }


    fun removeFileFromList(fileName: String) {
        _fileList.value = _fileList.value.filterNot { it.name == fileName }
        logManager.addLog("Removed '$fileName' from local file list.")
        onFileListUpdated() // Callback to trigger checking for new files
    }

    // --- Methods from BleSettingsManager ---
    suspend fun getSettings(connectionState: String): Boolean {
        if (connectionState != "Connected") {
            logManager.addLog("Cannot get settings, not connected.")
            return false
        }

        return bleMutex.withLock {
            if (_currentOperation.value != BleOperation.IDLE) {
                logManager.addLog("Cannot get settings, busy: ${_currentOperation.value}")
                return@withLock false
            }

            try {
                _currentOperation.value = BleOperation.FETCHING_SETTINGS
                settingsResponseBuffer.clear()
                logManager.addLog("Requesting settings from device...")

                val commandCompletion = CompletableDeferred<Pair<Boolean, String?>>()
                currentSettingsCommandCompletion = commandCompletion // Use specific settings completion

                sendCommand("GET:setting_ini")

                val (success, _) = withTimeoutOrNull(15000L) { // Timeout for response
                    commandCompletion.await()
                } ?: Pair(false, "Timeout")

                if (success) {
                    logManager.addLog("GET:setting_ini command completed successfully.")
                } else {
                    logManager.addLog("GET:setting_ini command failed or timed out.")
                }
                success
            } catch (e: Exception) {
                logManager.addLog("Error getSettings: ${e.message}")
                false
            } finally {
                _currentOperation.value = BleOperation.IDLE
                currentSettingsCommandCompletion = null // Clear completion object
            }
        }
    }

    fun applyRemoteSettings() {
        _remoteDeviceSettings.value?.let {
            _deviceSettings.value = it
            logManager.addLog("Applied remote settings to local state.")
        }
        dismissSettingsDiff()
    }

    fun dismissSettingsDiff() {
        _remoteDeviceSettings.value = null
        _settingsDiff.value = null
    }

    fun sendSettings(connectionState: String) {
        if (_currentOperation.value != BleOperation.IDLE || connectionState != "Connected") {
            logManager.addLog("Cannot send settings, busy or not connected.")
            return
        }
        val settings = _deviceSettings.value
        dismissSettingsDiff()
        _currentOperation.value = BleOperation.SENDING_SETTINGS
        val iniString = settings.toIniString()
        logManager.addLog("Sending settings to device:\n$iniString")
        sendCommand("SET:setting_ini:$iniString")
        scope.launch {
            // Original BleSettingsManager had a delay here.
            // Consider if this navigation event should be triggered by a response to SET:setting_ini
            // rather than immediately after sending, to confirm success.
            // For now, mirroring original behavior.
            delay(500) // Small delay to allow command to be sent over BLE
            _navigationEvent.emit(NavigationEvent.NavigateBack)
            _currentOperation.value = BleOperation.IDLE // Reset operation after sending
        }
    }

    fun updateSettings(updater: (DeviceSettings) -> DeviceSettings) {
        dismissSettingsDiff()
        _deviceSettings.value = updater(_deviceSettings.value)
    }

    // --- Combined handleResponse method ---
    fun handleResponse(value: ByteArray, operation: BleOperation) {
        when (operation) {
            BleOperation.FETCHING_DEVICE_INFO -> {
                val incomingString = value.toString(Charsets.UTF_8).trim()
                if (deviceResponseBuffer.isEmpty() && !incomingString.startsWith("{") && !incomingString.startsWith("ERROR:")) {
                    return
                }
                deviceResponseBuffer.addAll(value.toList())
                val currentBufferAsString = deviceResponseBuffer.toByteArray().toString(Charsets.UTF_8)

                if (currentBufferAsString.trim().endsWith("}")) {
                    logManager.addLog("Raw DeviceInfo JSON: $currentBufferAsString") // Log raw JSON
                    try {
                        val parsedResponse = json.decodeFromString<DeviceInfoResponse>(currentBufferAsString)
                        _deviceInfo.value = parsedResponse
                        currentDeviceCommandCompletion?.complete(Pair(true, null))
                    } catch (e: Exception) {
                        logManager.addLog("Error parsing DeviceInfo: ${e.message}")
                        currentDeviceCommandCompletion?.complete(Pair(false, e.message))
                    }
                } else if (currentBufferAsString.startsWith("ERROR:")) {
                    logManager.addLog("Error response GET:info: $currentBufferAsString")
                    currentDeviceCommandCompletion?.complete(Pair(false, currentBufferAsString))
                }
            }
            BleOperation.FETCHING_FILE_LIST -> {
                val incomingString = value.toString(Charsets.UTF_8).trim()
                if (deviceResponseBuffer.isEmpty() && !incomingString.startsWith("[") && !incomingString.startsWith("ERROR:")) {
                    if (incomingString == "[]") {
                        _fileList.value = emptyList()
                        currentDeviceCommandCompletion?.complete(Pair(true, null))
                    }
                    return
                }
                deviceResponseBuffer.addAll(value.toList())
                val currentBufferAsString = deviceResponseBuffer.toByteArray().toString(Charsets.UTF_8)

                if (currentBufferAsString.trim().endsWith("]")) {
                    try {
                        _fileList.value = parseFileEntries(currentBufferAsString)
                        logManager.addLog("Parsed FileList. Count: ${_fileList.value.size}")
                        currentDeviceCommandCompletion?.complete(Pair(true, null))
                    } catch (e: Exception) {
                        logManager.addLog("Error parsing FileList: ${e.message}")
                        currentDeviceCommandCompletion?.complete(Pair(false, e.message))
                    }
                } else if (currentBufferAsString.startsWith("ERROR:")) {
                    logManager.addLog("Error response GET:ls: $currentBufferAsString")
                    _fileList.value = emptyList()
                    currentDeviceCommandCompletion?.complete(Pair(false, currentBufferAsString))
                }
            }
            BleOperation.SENDING_TIME -> {
                val response = value.toString(Charsets.UTF_8).trim()
                if (response.startsWith("OK: Time")) {
                    currentDeviceCommandCompletion?.complete(Pair(true, null))
                    deviceResponseBuffer.clear()
                } else if (response.startsWith("ERROR:")) {
                    currentDeviceCommandCompletion?.complete(Pair(false, response))
                    deviceResponseBuffer.clear()
                } else {
                    logManager.addLog("Unexpected response during SET:time: $response")
                }
            }
            BleOperation.FETCHING_SETTINGS -> {
                settingsResponseBuffer.addAll(value.toList())
                
                // Launch a coroutine to handle the response after a small delay,
                // allowing time for multi-packet responses to arrive.
                // This mimics the original BleSettingsManager's implicit handling.
                scope.launch {
                    delay(200) // Original BleSettingsManager had a delay.

                    // Only process if still in FETCHING_SETTINGS state and command completion is active
                    // And ensure currentSettingsCommandCompletion is not null (should be handled by try-finally in getSettings)
                    if (_currentOperation.value == BleOperation.FETCHING_SETTINGS) {
                        val settingsString = settingsResponseBuffer.toByteArray().toString(Charsets.UTF_8).trim()
                        logManager.addLog("Assembled remote settings (GET:setting_ini): $settingsString")

                        // Check for explicit ERROR response from device
                        if (settingsString.startsWith("ERROR:")) {
                            logManager.addLog("Error response GET:setting_ini: $settingsString")
                            currentSettingsCommandCompletion?.complete(Pair(false, settingsString))
                        } else {
                            try {
                                val remoteSettings = DeviceSettings.fromIniString(settingsString)
                                _remoteDeviceSettings.value = remoteSettings

                                val diff = remoteSettings.diff(_deviceSettings.value)

                                if (diff.isNotBlank()) {
                                    _settingsDiff.value = diff
                                    logManager.addLog("Settings have differences.")
                                } else {
                                    _settingsDiff.value = "差分はありません。"
                                    logManager.addLog("Settings are identical.")
                                }
                                currentSettingsCommandCompletion?.complete(Pair(true, null))
                            } catch (e: Exception) {
                                logManager.addLog("Error parsing settings (GET:setting_ini): ${e.message}")
                                currentSettingsCommandCompletion?.complete(Pair(false, e.message))
                            }
                        }
                        // Reset operation and buffer after completion, whether successful or not
                        _currentOperation.value = BleOperation.IDLE
                        settingsResponseBuffer.clear()
                    }
                }
            }
            // BleOperation.SENDING_SETTINGS does not have a direct handleResponse logic in original.
            // It just sends command and then navigates back and resets _currentOperation after a delay.
            // If response is expected, it should be handled here.
            else -> {
                // Responses for other operations are handled by other managers (e.g., FileTransferManager)
                // or are not awaited in this manager.
            }
        }
    }
}
