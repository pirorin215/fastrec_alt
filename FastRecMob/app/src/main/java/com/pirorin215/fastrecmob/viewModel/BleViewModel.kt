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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException

private const val TAG = "BleViewModel"
//from bletool.py
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

    

        private val _fileList = MutableStateFlow<List<com.pirorin215.fastrecmob.data.FileEntry>>(emptyList())

        val fileList = _fileList.asStateFlow()

    

        private val _downloadProgress = MutableStateFlow(0)

        val downloadProgress = _downloadProgress.asStateFlow()

    

        private val _currentFileTotalSize = MutableStateFlow(0L)

        val currentFileTotalSize = _currentFileTotalSize.asStateFlow()

    

        private val _fileTransferState = MutableStateFlow("Idle")

        val fileTransferState = _fileTransferState.asStateFlow()

    

        private val _isInfoLoading = MutableStateFlow(false)

        val isInfoLoading = _isInfoLoading.asStateFlow()

    

        private val _transferKbps = MutableStateFlow(0.0f)

            val transferKbps = _transferKbps.asStateFlow()

        

            private val _isAutoRefreshEnabled = MutableStateFlow(true)

            val isAutoRefreshEnabled = _isAutoRefreshEnabled.asStateFlow()

        

            private var bluetoothGatt: BluetoothGatt? = null

        private var commandCharacteristic: BluetoothGattCharacteristic? = null

        private var ackCharacteristic: BluetoothGattCharacteristic? = null

        private var currentDownloadingFileName: String? = null

    

        private val json = Json { ignoreUnknownKeys = true }

    

        // Buffer for assembling fragmented BLE packets

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

            stopAutoRefresh() // Ensure only one job is running

            autoRefreshJob = viewModelScope.launch {

                while (true) {

                    delay(30000) // 30 seconds

                    // Only refresh if not busy with another operation

                    if (!_isInfoLoading.value && _fileTransferState.value == "Idle") {

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

    

        

    

                            fetchFileList() // Fetch immediately when enabled

    

        

    

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
        _fileTransferState.value = "Idle"
        _isInfoLoading.value = false
        _downloadProgress.value = 0
        _currentFileTotalSize.value = 0L
        _transferKbps.value = 0.0f
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
                    // Reset retries on successful connection
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
                        delay(500) // Wait before retrying
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
                    if (commandCharacteristic != null) {
                        addLog("Found command characteristic")
                    }
                    ackCharacteristic = service.getCharacteristic(UUID.fromString(ACK_UUID_STRING))
                    if (ackCharacteristic != null) {
                        addLog("Found ACK characteristic")
                    }
                }
            } else {
                addLog("Service discovery failed with status $status")
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            if (characteristic.uuid != UUID.fromString(RESPONSE_UUID_STRING)) return

            when (_fileTransferState.value) {
                "WaitingForStart" -> {
                    if (value.contentEquals("START".toByteArray())) {
                        addLog("Received START signal. Sending START_ACK.")
                        sendAck("START_ACK".toByteArray(Charsets.UTF_8))
                        _fileTransferState.value = "Downloading"
                        _transferStartTime = System.currentTimeMillis() // Start timer after handshake
                        responseBuffer.clear()
                        _downloadProgress.value = 0
                    } else {
                        addLog("Waiting for START, but received unexpected data: ${value.toString(Charsets.UTF_8)}")
                    }
                }
                "Downloading" -> {
                    if (value.contentEquals("EOF".toByteArray()) || value.toString(Charsets.UTF_8).startsWith("ERROR:")) {
                        val isSuccess = value.contentEquals("EOF".toByteArray())
                        if (isSuccess) {
                            addLog("End of file transfer signal received.")
                            val fileData = responseBuffer.toByteArray()
                            val header = fileData.take(8).joinToString(" ") { String.format("%02x", it) }
                            addLog("Saving file... Header: $header. Size: ${fileData.size} bytes.")
                            saveFile(fileData)
                            _fileTransferState.value = "Success"
                        } else {
                            val errorMessage = value.toString(Charsets.UTF_8)
                            addLog("Received error during transfer: $errorMessage")
                            _fileTransferState.value = "Error: $errorMessage"
                        }
                        // Reset for next transfer
                        responseBuffer.clear()
                        currentDownloadingFileName = null
                        _transferStartTime = 0L
                        _transferKbps.value = 0.0f
                        // Transition back to Idle after a short delay to allow UI to update
                        viewModelScope.launch {
                            delay(1000)
                            _fileTransferState.value = "Idle"
                        }
                    } else {
                        // First data packet
                        if (_transferStartTime == 0L) {
                            _transferStartTime = System.currentTimeMillis()
                        }
                        responseBuffer.addAll(value.toList())
                        _downloadProgress.value = responseBuffer.size

                        val elapsedTime = (System.currentTimeMillis() - _transferStartTime) / 1000.0f
                        if (elapsedTime > 0) {
                            _transferKbps.value = (responseBuffer.size / 1024.0f) / elapsedTime
                        }
                        sendAck("ACK".toByteArray(Charsets.UTF_8))
                    }
                }
                "Idle" -> {
                    // This is for general command responses (like GET:info)
                    if (!_isInfoLoading.value) {
                        addLog("Received unexpected data in Idle state: ${value.toString(Charsets.UTF_8)}")
                        return
                    }
                    responseBuffer.addAll(value.toList())
                    val currentBufferAsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)
                    if (currentBufferAsString.trim().endsWith("}")) {
                        addLog("Assembled data: $currentBufferAsString")
                        try {
                            val parsedResponse = json.decodeFromString<DeviceInfoResponse>(currentBufferAsString)
                            _deviceInfo.value = parsedResponse
                            addLog("Parsed DeviceInfo: ${parsedResponse.batteryLevel}%")
                            _receivedData.value = "" // Clear raw received data after parsing
                        } catch (e: Exception) {
                            addLog("Error parsing JSON: ${e.message}")
                        } finally {
                            _isInfoLoading.value = false // Reset loading state
                            responseBuffer.clear()
                        }
                    }
                }
            }
        }

        private fun sendAck(ackValue: ByteArray) {
            if (ackCharacteristic != null) {
                // addLog("Sending ACK: ${String(ackValue)}") // For debug
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    bluetoothGatt?.writeCharacteristic(
                        ackCharacteristic!!,
                        ackValue,
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    )
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
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Log only for non-ACK writes to reduce noise
                if(characteristic?.uuid != ackCharacteristic?.uuid) {
                    addLog("Characteristic written successfully: ${characteristic?.uuid}")
                }
            } else {
                addLog("Characteristic write failed with status $status for ${characteristic?.uuid}")
            }
        }
    }

    private fun saveFile(data: ByteArray) {
        val fileName = currentDownloadingFileName ?: "downloaded_file_${System.currentTimeMillis()}"
        try {
            val path = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val file = File(path, fileName)
            FileOutputStream(file).use {
                it.write(data)
            }
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
            // addLog("Scan result: ${result.device.name ?: "Unknown"} (${result.device.address})")
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
        _receivedData.value = ""
        _logs.value = emptyList()
        addLog("Starting BLE scan (no filter)")
        val scanSettings = ScanSettings.Builder().build()
        bluetoothAdapter?.bluetoothLeScanner?.startScan(null, scanSettings, scanCallback)

        Handler(Looper.getMainLooper()).postDelayed({
            stopScan()
        }, 10000)
    }

    private fun stopScan() {
        addLog("Stopping BLE scan")
        bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
    }

    private fun connectToDevice(device: BluetoothDevice) {
        addLog("Connecting to device ${device.address}")
        connectionRetries = 0 // Reset retry counter on new connection attempt
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

        fun sendCommand(command: String) {

            if (commandCharacteristic == null) {

                addLog("Command characteristic not found")

                return

            }

    

            // Buffer clearing is now handled by the state machine logic

            addLog("Sending command: $command")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {

                bluetoothGatt?.writeCharacteristic(

                    commandCharacteristic!!,

                    command.toByteArray(Charsets.UTF_8),

                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                )

            } else {

                commandCharacteristic?.value = command.toByteArray(Charsets.UTF_8)

                commandCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                bluetoothGatt?.writeCharacteristic(commandCharacteristic!!)

            }

        }

    

        fun fetchFileList() {

            if (connectionState.value != "Connected") {

                addLog("Cannot fetch file list, not connected.")

                return

            }

            if (_isInfoLoading.value || _fileTransferState.value != "Idle") {

                addLog("Cannot fetch file list, another operation is in progress.")

                return

            }

            _isInfoLoading.value = true

            addLog("Requesting file list from device...")

            sendCommand("GET:info")

        }

    

        fun downloadFile(fileName: String) {

            if (connectionState.value != "Connected") {

                addLog("Cannot download file, not connected.")

                return

            }

            if (_isInfoLoading.value || _fileTransferState.value != "Idle") {

                addLog("Cannot start download, another operation is in progress.")

                return

            }

            

            _fileTransferState.value = "WaitingForStart"

            currentDownloadingFileName = fileName

    

            val fileEntry = _fileList.value.find { it.name == fileName }

            _currentFileTotalSize.value = fileEntry?.size?.substringBefore(" ")?.toLongOrNull() ?: 0L

    

            addLog("Requesting file: $currentDownloadingFileName (size: ${_currentFileTotalSize.value} bytes)")

            sendCommand("GET:file:$currentDownloadingFileName")

        }

    fun disconnect() {
        addLog("Disconnecting from device")
        resetOperationStates()
        bluetoothGatt?.disconnect()
    }
}
