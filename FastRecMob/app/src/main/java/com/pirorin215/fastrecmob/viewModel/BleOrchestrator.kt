package com.pirorin215.fastrecmob.viewModel

import android.content.Context
import android.util.Log
import com.pirorin215.fastrecmob.data.BleRepository
import com.pirorin215.fastrecmob.data.TranscriptionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import android.bluetooth.BluetoothGattCharacteristic
import com.pirorin215.fastrecmob.viewModel.LocationMonitor
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

import com.pirorin215.fastrecmob.viewModel.LogManager

class BleOrchestrator(
    private val scope: CoroutineScope,
    private val context: Context,
    private val repository: BleRepository,
    private val connectionStateFlow: StateFlow<String>,
    private val onDeviceReadyEvent: SharedFlow<Unit>,
    private val transcriptionManager: TranscriptionManagement,
    private val locationMonitor: LocationTracking,
    private val appSettingsRepository: AppSettingsRepository,
    private val bleSelectionManager: BleSelection,
    private val transcriptionResults: StateFlow<List<TranscriptionResult>>,
    private val logManager: LogManager
) : BleOrchestration {
    companion object {
        const val TAG = "BleOrchestrator"
        const val RESPONSE_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ab"
    }

    private val _currentOperation = MutableStateFlow(BleOperation.IDLE)
    override val currentOperation = _currentOperation.asStateFlow()
    private val bleMutex = Mutex()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    override val navigationEvent = _navigationEvent.asSharedFlow()

    private val bleDeviceManager by lazy {
        BleDeviceManager(
            scope = scope,
            context = context,
            sendCommand = { command -> sendCommand(command) },
            logManager = logManager,
            _currentOperation = _currentOperation,
            bleMutex = bleMutex,
            onFileListUpdated = { checkForNewWavFilesAndProcess() }
        )
    }

    private val bleSettingsManager by lazy {
        BleSettingsManager(
            scope = scope,
            sendCommand = { command -> sendCommand(command) },
            logManager = logManager,
            _currentOperation = _currentOperation,
            _navigationEvent = _navigationEvent
        )
    }

    private val bleAutoRefresher by lazy {
        BleAutoRefresher(
            scope = scope,
            refreshIntervalSecondsFlow = appSettingsRepository.refreshIntervalSecondsFlow
                .stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = 30
                ),
            onRefresh = {
                fetchFileList()
            },
            logManager = logManager
        )
    }

    // --- Delegated Properties from Managers ---
    override val fileList: StateFlow<List<com.pirorin215.fastrecmob.data.FileEntry>> get() = bleDeviceManager.fileList
    override val deviceInfo: StateFlow<com.pirorin215.fastrecmob.data.DeviceInfoResponse?> get() = bleDeviceManager.deviceInfo
    override val deviceSettings: StateFlow<com.pirorin215.fastrecmob.data.DeviceSettings> get() = bleSettingsManager.deviceSettings
    override val remoteDeviceSettings: StateFlow<com.pirorin215.fastrecmob.data.DeviceSettings?> get() = bleSettingsManager.remoteDeviceSettings
    override val settingsDiff: StateFlow<String?> get() = bleSettingsManager.settingsDiff
    override val isAutoRefreshEnabled: StateFlow<Boolean> get() = bleAutoRefresher.isAutoRefreshEnabled

    private val fileTransferManager by lazy {
        FileTransferManager(
            context = context,
            scope = scope,
            repository = repository,
            transcriptionManager = transcriptionManager,
            audioDirNameFlow = appSettingsRepository.audioDirNameFlow
                .stateIn(
                    scope = scope,
                    started = SharingStarted.WhileSubscribed(5000),
                    initialValue = "FastRecRecordings"
                ),
            bleMutex = bleMutex,
            logManager = logManager,
            sendCommandCallback = { command -> sendCommand(command) },
            sendAckCallback = { ackValue -> sendAck(ackValue) },
            _currentOperation = _currentOperation,
            bleDeviceManager = bleDeviceManager,
            _connectionState = connectionStateFlow
        )
    }

    override val downloadProgress: StateFlow<Int> get() = fileTransferManager.downloadProgress
    override val currentFileTotalSize: StateFlow<Long> get() = fileTransferManager.currentFileTotalSize
    override val fileTransferState: StateFlow<String> get() = fileTransferManager.fileTransferState
    override val transferKbps: StateFlow<Float> get() = fileTransferManager.transferKbps

    init {
        onDeviceReadyEvent
            .onEach {
                addLog("Device ready event received. Triggering initial sync.")
                startFullSync()
            }
            .launchIn(scope)

        repository.events.onEach { event ->
            when(event) {
                is com.pirorin215.fastrecmob.data.BleEvent.CharacteristicChanged -> {
                    handleCharacteristicChanged(event.characteristic, event.value)
                }
                else -> {}
            }
        }.launchIn(scope)
    }
    
    override fun stop() {
        setAutoRefresh(false)
        bleDeviceManager.stopTimeSyncJob()
        addLog("Orchestrator stopped.")
    }

    override fun setAutoRefresh(enabled: Boolean) {
        bleAutoRefresher.setAutoRefresh(enabled)
    }

    fun addLog(message: String) {
        logManager.addLog(message)
    }

    override fun clearLogs() {
        logManager.clearLogs()
        logManager.addLog("App logs cleared.")
    }

    private fun startFullSync() {
        scope.launch {
            addLog("Starting full sync process...")

            // Step 1: Time Sync
            val timeSyncSuccess = bleDeviceManager.syncTime(connectionStateFlow.value)
            if (!timeSyncSuccess) {
                addLog("Time sync failed, aborting full sync.")
                return@launch
            }

            // Start periodic time sync job *after* initial sync is successful
            bleDeviceManager.startTimeSyncJob()

            // Step 2: Fetch Device Info
            val deviceInfoSuccess = bleDeviceManager.fetchDeviceInfo(connectionStateFlow.value)
            if (!deviceInfoSuccess) {
                addLog("Fetch device info failed, aborting full sync.")
                return@launch
            }

            // Step 3: Update location (since GET:info was successful)
            addLog("Device info received, updating location.")
            locationMonitor.updateLocation()

            // Step 4: Fetch file list (which will trigger download/transcription logic via its callback)
            bleDeviceManager.fetchFileList(connectionStateFlow.value)
        }
    }

    private fun checkForNewWavFilesAndProcess() {
        scope.launch {
            val currentWavFilesOnMicrocontroller = bleDeviceManager.fileList.value.filter { it.name.endsWith(".wav", ignoreCase = true) }
            val transcribedFileNames = this@BleOrchestrator.transcriptionResults.value.map { it.fileName }.toSet() // Using passed transcriptionResults

            val filesToProcess = currentWavFilesOnMicrocontroller.filter { fileEntry ->
                !transcribedFileNames.contains(fileEntry.name)
            }

            if (filesToProcess.isNotEmpty()) {
                val fileEntry = filesToProcess.first()
                addLog("Found untranscribed WAV file: ${fileEntry.name}. Starting automatic download.")
                downloadFile(fileEntry.name)
            } else {
                addLog("No new untranscribed WAV files found on microcontroller. Checking for any pending transcriptions.")
                transcriptionManager.processPendingTranscriptions()
            }
            if (!isAutoRefreshEnabled.value) {
                setAutoRefresh(true)
            }
        }
    }

    private fun handleCharacteristicChanged(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid != UUID.fromString(RESPONSE_UUID_STRING)) return

        when (_currentOperation.value) {
            BleOperation.FETCHING_FILE_LIST, BleOperation.FETCHING_DEVICE_INFO, BleOperation.SENDING_TIME -> {
                bleDeviceManager.handleResponse(value)
            }
            BleOperation.FETCHING_SETTINGS -> {
                bleSettingsManager.handleResponse(value)
            }
            BleOperation.DOWNLOADING_FILE, BleOperation.DELETING_FILE -> {
                fileTransferManager.handleCharacteristicChanged(characteristic, value)
            }
            else -> {
                addLog("Received data in unexpected state (${_currentOperation.value}): ${value.toString(Charsets.UTF_8)}")
            }
        }
    }

    override fun sendCommand(command: String) {
        addLog("Sending command: $command")
        repository.sendCommand(command)
    }

    private fun sendAck(ackValue: ByteArray) {
        repository.sendAck(ackValue)
    }

    override fun fetchFileList(extension: String) {
        scope.launch {
            bleDeviceManager.fetchFileList(connectionStateFlow.value, extension)
        }
    }

    override fun getSettings() {
        bleSettingsManager.getSettings(connectionStateFlow.value)
    }

    override fun applyRemoteSettings() {
        bleSettingsManager.applyRemoteSettings()
    }

    override fun dismissSettingsDiff() {
        bleSettingsManager.dismissSettingsDiff()
    }

    override fun sendSettings() {
        bleSettingsManager.sendSettings(connectionStateFlow.value)
    }

    override fun updateSettings(updater: (com.pirorin215.fastrecmob.data.DeviceSettings) -> com.pirorin215.fastrecmob.data.DeviceSettings) {
        bleSettingsManager.updateSettings(updater)
    }

    override fun downloadFile(fileName: String) {
        fileTransferManager.downloadFileAndProcess(fileName)
    }

    fun toggleSelection(fileName: String) {
        bleSelectionManager.toggleSelection(fileName)
    }

    fun clearSelection() {
        bleSelectionManager.clearSelection()
    }
}