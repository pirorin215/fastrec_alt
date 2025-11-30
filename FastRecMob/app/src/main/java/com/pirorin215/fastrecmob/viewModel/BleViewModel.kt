package com.pirorin215.fastrecmob.viewModel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.os.Build
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

sealed class NavigationEvent {
    object NavigateBack : NavigationEvent()
}

private const val TAG = "BleViewModel"
private const val DEVICE_NAME = "fastrec"
private const val COMMAND_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26aa"
private const val RESPONSE_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ab"
private const val ACK_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ac"
private const val CCCD_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb"

@SuppressLint("MissingPermission")
class BleViewModel(private val context: Context) : ViewModel() {

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow("Disconnected")
    val connectionState = _connectionState.asStateFlow()

    private val _receivedData = MutableStateFlow("")
    val receivedData = _receivedData.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs = _logs.asStateFlow()

    private val _deviceInfo = MutableStateFlow<DeviceInfoResponse?>(null)
    val deviceInfo = _deviceInfo.asStateFlow()

    private val _deviceSettings = MutableStateFlow<com.pirorin215.fastrecmob.data.DeviceSettings?>(null)
    val deviceSettings = _deviceSettings.asStateFlow()

    private val _remoteDeviceSettings = MutableStateFlow<com.pirorin215.fastrecmob.data.DeviceSettings?>(null)
    val remoteDeviceSettings = _remoteDeviceSettings.asStateFlow()

    private val _settingsDiff = MutableStateFlow<String?>(null)
    val settingsDiff = _settingsDiff.asStateFlow()

    private val _fileList = MutableStateFlow<List<com.pirorin215.fastrecmob.data.FileEntry>>(emptyList())
    val fileList = _fileList.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _currentFileTotalSize = MutableStateFlow(0L)
    val currentFileTotalSize = _currentFileTotalSize.asStateFlow()

    private val _fileTransferState = MutableStateFlow("Idle")
    val fileTransferState = _fileTransferState.asStateFlow()

    private val _currentOperation = MutableStateFlow(Operation.IDLE)
    val currentOperation = _currentOperation.asStateFlow()

    private val _transferKbps = MutableStateFlow(0.0f)
    val transferKbps = _transferKbps.asStateFlow()

    private val _isAutoRefreshEnabled = MutableStateFlow(true)
    val isAutoRefreshEnabled = _isAutoRefreshEnabled.asStateFlow()

    private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
    val navigationEvent = _navigationEvent.asSharedFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var currentDownloadingFileName: String? = null

    private val json = Json { ignoreUnknownKeys = true }

    enum class Operation {
        IDLE,
        FETCHING_INFO,
        FETCHING_SETTINGS,
        DOWNLOADING_FILE,
        SENDING_SETTINGS
    }

    private var responseBuffer = mutableListOf<Byte>()
    private var autoRefreshJob: Job? = null
    private var _transferStartTime = 0L
    private var connectionRetries = 0
    private val maxConnectionRetries = 3

    init {
        connectionState.onEach { state ->
            if (state != "Connected") {
                stopAutoRefresh()
            }
        }.launchIn(viewModelScope)

        deviceInfo.onEach { info ->
            info?.ls?.let { fileString ->
                _fileList.value = com.pirorin215.fastrecmob.data.parseFileEntries(fileString)
            }
        }.launchIn(viewModelScope)
    }

    private fun startAutoRefresh() {
        stopAutoRefresh()
        autoRefreshJob = viewModelScope.launch {
            while (true) {
                delay(30000)
                if (_currentOperation.value == Operation.IDLE && _fileTransferState.value == "Idle") {
                    fetchFileList()
                }
            }
        }
    }

    private fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }

    fun setAutoRefresh(enabled: Boolean) {
        _isAutoRefreshEnabled.value = enabled
        if (enabled) {
            addLog("Auto-refresh enabled.")
            fetchFileList()
            startAutoRefresh()
        } else {
            addLog("Auto-refresh disabled.")
            stopAutoRefresh()
        }
    }

    private fun addLog(message: String) {
        Log.d(TAG, message)
        _logs.value = (_logs.value + message).takeLast(100)
    }

    private fun resetOperationStates() {
        addLog("Resetting all operation states.")
        _currentOperation.value = Operation.IDLE
        _fileTransferState.value = "Idle"
        _downloadProgress.value = 0
        _currentFileTotalSize = 0L
        _transferKbps = 0.0f
        _transferStartTime = 0L
        responseBuffer.clear()
        currentDownloadingFileName = null
    }

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _connectionState.value = "Connected"
                    addLog("Successfully connected to $deviceAddress")
                    connectionRetries = 0
                    addLog("Requesting MTU of 517")
                    gatt.requestMtu(517)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _connectionState.value = "Disconnected"
                    addLog("Successfully disconnected from $deviceAddress")
                    resetOperationStates()
                    gatt.close()
                }
            } else {
                if (status == 133 && connectionRetries < maxConnectionRetries) {
                    connectionRetries++
                    addLog("GATT error 133. Retrying connection... (Attempt ${connectionRetries})")
                    viewModelScope.launch {
                        delay(500)
                        gatt.device.connectGatt(context, false, gattCallback)
                    }
                } else {
                    _connectionState.value = "Disconnected"
                    addLog("Error $status encountered for $deviceAddress! Disconnecting...")
                    resetOperationStates()
                    gatt.close()
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("MTU changed to $mtu")
            } else {
                addLog("MTU change failed, status: $status")
            }
            addLog("Discovering services")
            bluetoothGatt?.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Services discovered successfully")
                gatt.services?.forEach { service ->
                    val responseCharacteristic = service.getCharacteristic(UUID.fromString(RESPONSE_UUID_STRING))
                    if (responseCharacteristic != null) {
                        addLog("Found response characteristic")
                        gatt.setCharacteristicNotification(responseCharacteristic, true)
                        val descriptor = responseCharacteristic.getDescriptor(UUID.fromString(CCCD_UUID_STRING))
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            bluetoothGatt?.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        } else {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            bluetoothGatt?.writeDescriptor(descriptor)
                        }
                        addLog("Writing descriptor to enable notifications")
                    }
                    commandCharacteristic = service.getCharacteristic(UUID.fromString(COMMAND_UUID_STRING))
                    if (commandCharacteristic != null) { addLog("Found command characteristic") }
                    ackCharacteristic = service.getCharacteristic(UUID.fromString(ACK_UUID_STRING))
                    if (ackCharacteristic != null) { addLog("Found ACK characteristic") }
                }
            } else {
                addLog("Service discovery failed with status $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            if (characteristic.uuid != UUID.fromString(RESPONSE_UUID_STRING)) return

            when (_currentOperation.value) {
                Operation.FETCHING_INFO -> {
                    responseBuffer.addAll(value.toList())
                    val currentBufferAsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)
                    if (currentBufferAsString.trim().endsWith("}")) {
                        addLog("Assembled data for DeviceInfo: $currentBufferAsString")
                        try {
                            val parsedResponse = json.decodeFromString<DeviceInfoResponse>(currentBufferAsString)
                            _deviceInfo.value = parsedResponse
                            addLog("Parsed DeviceInfo: ${parsedResponse.batteryLevel}%")
                        } catch (e: Exception) {
                            addLog("Error parsing DeviceInfo JSON: ${e.message}")
                        } finally {
                            _currentOperation.value = Operation.IDLE
                            responseBuffer.clear()
                        }
                    }
                }
                Operation.FETCHING_SETTINGS -> {
                    responseBuffer.addAll(value.toList())
                    viewModelScope.launch {
                        delay(200)
                        if (_currentOperation.value == Operation.FETCHING_SETTINGS) {
                            val settingsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)
                            addLog("Assembled remote settings: $settingsString")
                            val remoteSettings = com.pirorin215.fastrecmob.data.DeviceSettings.fromIniString(settingsString)
                            _remoteDeviceSettings.value = remoteSettings
                            
                            val localSettings = _deviceSettings.value ?: com.pirorin215.fastrecmob.data.DeviceSettings()
                            val diff = remoteSettings.diff(localSettings)
                            
                            if (diff.isNotBlank()) {
                                _settingsDiff.value = diff
                                addLog("Settings have differences.")
                            } else {
                                _settingsDiff.value = "差分はありません。"
                                addLog("Settings are identical.")
                            }

                            _currentOperation.value = Operation.IDLE
                            responseBuffer.clear()
                        }
                    }
                }
                Operation.DOWNLOADING_FILE -> {
                    handleFileDownloadData(value)
                }
                else -> {
                    addLog("Received data in unexpected state (${_currentOperation.value}): ${value.toString(Charsets.UTF_8)}")
                }
            }
        }
        
        private fun handleFileDownloadData(value: ByteArray) {
            when (_fileTransferState.value) {
                "WaitingForStart" -> {
                    if (value.contentEquals("START".toByteArray())) {
                        addLog("Received START signal. Sending START_ACK.")
                        sendAck("START_ACK".toByteArray(Charsets.UTF_8))
                        _fileTransferState.value = "Downloading"
                        _transferStartTime = System.currentTimeMillis()
                        responseBuffer.clear()
                        _downloadProgress.value = 0
                    } else {
                        addLog("Waiting for START, but received: ${value.toString(Charsets.UTF_8)}")
                    }
                }
                "Downloading" -> {
                    if (value.contentEquals("EOF".toByteArray()) || value.toString(Charsets.UTF_8).startsWith("ERROR:")) {
                        val isSuccess = value.contentEquals("EOF".toByteArray())
                        if (isSuccess) {
                            addLog("End of file transfer signal received.")
                            saveFile(responseBuffer.toByteArray())
                            _fileTransferState.value = "Success"
                        } else {
                            val errorMessage = value.toString(Charsets.UTF_8)
                            addLog("Received error during transfer: $errorMessage")
                            _fileTransferState.value = "Error: $errorMessage"
                        }
                        _currentOperation.value = Operation.IDLE
                        responseBuffer.clear()
                        currentDownloadingFileName = null
                        viewModelScope.launch {
                            delay(1000)
                            _fileTransferState.value = "Idle"
                        }
                    } else {
                        responseBuffer.addAll(value.toList())
                        _downloadProgress.value = responseBuffer.size
                        val elapsedTime = (System.currentTimeMillis() - _transferStartTime) / 1000.0f
                        if (elapsedTime > 0) {
                            _transferKbps = (responseBuffer.size / 1024.0f) / elapsedTime
                        }
                        sendAck("ACK".toByteArray(Charsets.UTF_8))
                    }
                }
            }
        }
    
        private fun sendAck(ackValue: ByteArray) {
            if (ackCharacteristic != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGatt?.writeCharacteristic(ackCharacteristic!!, ackValue, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                } else {
                    ackCharacteristic?.value = ackValue
                    ackCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    bluetoothGatt?.writeCharacteristic(ackCharacteristic!!)
                }
            }
        }
    
        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            if (status == BluetoothGatt.GATT_SUCCESS) {
                addLog("Descriptor written successfully. Ready to communicate.")
                viewModelScope.launch {
                    fetchFileList()
                    startAutoRefresh()
                }
            } else {
                addLog("Descriptor write failed with status $status")
            }
        }
    
        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            if(characteristic?.uuid == commandCharacteristic?.uuid) {
                 if (status == BluetoothGatt.GATT_SUCCESS) {
                    addLog("Command written successfully.")
                    if (_currentOperation.value == Operation.SENDING_SETTINGS) {
                         addLog("Settings sent. Device will likely reboot.")
                         _currentOperation.value = Operation.IDLE
                    }
                 } else {
                     addLog("Command write failed with status $status.")
                     if(_currentOperation.value != Operation.IDLE) {
                         _currentOperation.value = Operation.IDLE
                     }
                 }
            }
        }
    }
    
    private fun saveFile(data: ByteArray) {
        val fileName = currentDownloadingFileName ?: "downloaded_file_${System.currentTimeMillis()}"
        try {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(path, fileName)
            FileOutputStream(file).use { it.write(data) }
            addLog("File saved successfully: ${file.absolutePath}")
            _fileTransferState.value = "Success: ${file.absolutePath}"
        } catch (e: Exception) {
            addLog("Error saving file: ${e.message}")
            _fileTransferState.value = "Error: ${e.message}"
        }
    }
    
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            if (result.device.name == DEVICE_NAME) {
                addLog("Found fastrec device, stopping scan and connecting")
                stopScan()
                connectToDevice(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            super.onScanFailed(errorCode)
            addLog("Scan failed with error code $errorCode")
        }
    }
    
    fun startScan() {
        _logs.value = emptyList()
        addLog("Starting BLE scan")
        val scanSettings = ScanSettings.Builder().build()
        bluetoothAdapter?.bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)
        Handler(Looper.getMainLooper()).postDelayed({ stopScan() }, 10000)
    }
    
    private fun stopScan() {
        addLog("Stopping BLE scan")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }
    
    private fun connectToDevice(device: BluetoothDevice) {
        addLog("Connecting to device ${device.address}")
        connectionRetries = 0
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }
    
    fun sendCommand(command: String) {
        if (commandCharacteristic == null) {
            addLog("Command characteristic not found")
            return
        }
        addLog("Sending command: $command")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(commandCharacteristic!!, command.toByteArray(Charsets.UTF_8), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            commandCharacteristic?.value = command.toByteArray(Charsets.UTF_8)
            commandCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(commandCharacteristic!!)
        }
    }
    
    fun fetchFileList() {
        if (_currentOperation.value != Operation.IDLE || connectionState.value != "Connected") {
            addLog("Cannot fetch file list, busy or not connected.")
            return
        }
        _currentOperation.value = Operation.FETCHING_INFO
        responseBuffer.clear()
        addLog("Requesting file list from device...")
        sendCommand("GET:info")
    }
    
    fun getSettings() {
        if (_currentOperation.value != Operation.IDLE || connectionState.value != "Connected") {
            addLog("Cannot get settings, busy or not connected.")
            return
        }
        _currentOperation.value = Operation.FETCHING_SETTINGS
        responseBuffer.clear()
        addLog("Requesting settings from device...")
        sendCommand("GET:setting_ini")
    }
    
    fun applyRemoteSettings() {
        _remoteDeviceSettings.value?.let {
            _deviceSettings.value = it
            addLog("Applied remote settings to local state.")
        }
        dismissSettingsDiff()
    }
    
    fun dismissSettingsDiff() {
        _remoteDeviceSettings.value = null
        _settingsDiff.value = null
    }
    
    fun sendSettings() {
        if (_currentOperation.value != Operation.IDLE || connectionState.value != "Connected") {
            addLog("Cannot send settings, busy or not connected.")
            return
        }
        val settings = _deviceSettings.value ?: run {
            addLog("Cannot send settings, settings data is null.")
            return
        }
        dismissSettingsDiff()
        _currentOperation.value = Operation.SENDING_SETTINGS
        val iniString = settings.toIniString()
        addLog("Sending settings to device:\n$iniString")
        sendCommand("SET:setting_ini:$iniString")
        viewModelScope.launch {
            _navigationEvent.emit(NavigationEvent.NavigateBack)
        }
    }
    
    fun updateSettings(updater: (com.pirorin215.fastrecmob.data.DeviceSettings) -> com.pirorin215.fastrecmob.data.DeviceSettings) {
        dismissSettingsDiff()
        val currentSettings = _deviceSettings.value ?: com.pirorin215.fastrecmob.data.DeviceSettings()
        _deviceSettings.value = updater(currentSettings)
    }
    
    fun downloadFile(fileName: String) {
        if (_currentOperation.value != Operation.IDLE || connectionState.value != "Connected") {
            addLog("Cannot download file, busy or not connected.")
            return
        }
        _currentOperation.value = Operation.DOWNLOADING_FILE
        _fileTransferState.value = "WaitingForStart"
        currentDownloadingFileName = fileName
    
        val fileEntry = _fileList.value.find { it.name == fileName }
        _currentFileTotalSize = fileEntry?.size?.substringBefore(" ")?.toLongOrNull() ?: 0L
    
        addLog("Requesting file: $currentDownloadingFileName (size: ${_currentFileTotalSize.value} bytes)")
        sendCommand("GET:file:$currentDownloadingFileName")
    }
    
    fun disconnect() {
        addLog("Disconnecting from device")
        resetOperationStates()
        bluetoothGatt?.disconnect()
    }
}