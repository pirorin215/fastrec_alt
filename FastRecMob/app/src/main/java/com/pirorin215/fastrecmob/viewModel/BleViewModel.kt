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

    private val _isReceivingFile = MutableStateFlow(false)

    private val _fileList = MutableStateFlow<List<com.pirorin215.fastrecmob.data.FileEntry>>(emptyList())
    val fileList = _fileList.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0)
    val downloadProgress = _downloadProgress.asStateFlow()

    private val _currentFileTotalSize = MutableStateFlow(0L)
    val currentFileTotalSize = _currentFileTotalSize.asStateFlow()

    private val _fileTransferState = MutableStateFlow("Idle")
    val fileTransferState = _fileTransferState.asStateFlow()

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null
    private var currentDownloadingFileName: String? = null

    // Handshake for file transfer start
    private val _transferReady = MutableSharedFlow<Unit>(extraBufferCapacity = 1)


    private val json = Json { ignoreUnknownKeys = true }

        // Buffer for assembling fragmented BLE packets

        private var responseBuffer = mutableListOf<Byte>()

        // private var autoRefreshJob: Job? = null // Disabled for manual refresh

    

        init {

            /* // Disabled for manual refresh

            connectionState.onEach { state ->

                if (state == "Connected") {

                    startAutoRefresh()

                } else {

                    stopAutoRefresh()

                }

            }.launchIn(viewModelScope)

            */

    

            deviceInfo.onEach { info ->

                info?.ls?.let { fileString ->

                    _fileList.value = com.pirorin215.fastrecmob.data.parseFileEntries(fileString)

                }

            }.launchIn(viewModelScope)

        }

    

        /* // Disabled for manual refresh

        private fun startAutoRefresh() {

            stopAutoRefresh() // Ensure only one job is running

            autoRefreshJob = viewModelScope.launch {

                while (true) {

                    if (!_isReceivingFile.value) {

                        sendCommand("GET:info")

                    }

                    delay(5000) // 5 seconds

                }

            }

        }

    

        private fun stopAutoRefresh() {

            autoRefreshJob?.cancel()

            autoRefreshJob = null

        }

        */

    private fun addLog(message: String) {
        Log.d(TAG, message)
        _logs.value = (_logs.value + message).takeLast(100)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _connectionState.value = "Connected"
                    addLog("Successfully connected to $deviceAddress")
                    addLog("Requesting MTU of 517")
                    gatt.requestMtu(517)
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _connectionState.value = "Disconnected"
                    addLog("Successfully disconnected from $deviceAddress")
                    gatt.close()
                }
            } else {
                _connectionState.value = "Disconnected"
                addLog("Error $status encountered for $deviceAddress! Disconnecting...")
                gatt.close()
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

            if (_isReceivingFile.value) {
                // Check for START signal before actual file data
                if (value.contentEquals("START".toByteArray())) {
                    addLog("Received START signal. Sending START_ACK.")
                    sendAck("START_ACK".toByteArray(Charsets.UTF_8))
                    viewModelScope.launch { _transferReady.emit(Unit) }
                    // Do not clear responseBuffer here, actual file data will follow
                    return // No need to process further or send another general ACK
                }
                
                if (value.contentEquals("EOF".toByteArray())) {
                    addLog("End of file transfer signal received.")
                    val fileData = responseBuffer.toByteArray()
                    val header = fileData.take(8).joinToString(" ") { String.format("%02x", it) }
                    addLog("Saving file... Header: $header. Size: ${fileData.size} bytes.")
                    saveFile(fileData)
                    responseBuffer.clear()
                    _isReceivingFile.value = false
                    _fileTransferState.value = "Success"
                    currentDownloadingFileName = null
                    return // Do not send ACK for EOF
                } else {
                    responseBuffer.addAll(value.toList())
                    _downloadProgress.value = responseBuffer.size
                    // addLog("Received file chunk: ${value.size} bytes. Total: ${responseBuffer.size} bytes")
                }
            } else {
                // This is for general command responses (like GET:info)
                responseBuffer.addAll(value.toList())
                val currentBufferAsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)
                if (currentBufferAsString.trim().endsWith("}")) {
                    _receivedData.value = currentBufferAsString
                    addLog("Assembled data: $currentBufferAsString")
                    try {
                        val parsedResponse = json.decodeFromString<DeviceInfoResponse>(currentBufferAsString)
                        _deviceInfo.value = parsedResponse
                        addLog("Parsed DeviceInfo: ${parsedResponse.batteryLevel}%")
                        _receivedData.value = "" // Clear raw received data after parsing
                    } catch (e: Exception) {
                        addLog("Error parsing JSON: ${e.message}")
                    }
                    responseBuffer.clear()
                }
            }

            // Send general ACK for data chunks (not EOF or START)
            sendAck("ACK".toByteArray(Charsets.UTF_8))
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
                addLog("Descriptor written successfully")
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
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun sendCommand(command: String) {
        if (commandCharacteristic == null) {
            addLog("Command characteristic not found")
            return
        }
        // Do not clear buffer if we are in the middle of receiving a file
        if (!_isReceivingFile.value) {
            responseBuffer.clear()
        }

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
        if (connectionState.value == "Connected") {
            addLog("Requesting file list from device...")
            sendCommand("GET:info")
        } else {
            addLog("Cannot fetch file list, not connected.")
        }
    }

    suspend fun downloadFile(fileName: String) {
        if (connectionState.value != "Connected") {
            addLog("Cannot download file, not connected.")
            return
        }
        if (_isReceivingFile.value) {
            addLog("Another file download is already in progress.")
            return
        }

        _isReceivingFile.value = true
        _fileTransferState.value = "Downloading"
        _downloadProgress.value = 0
        responseBuffer.clear()
        currentDownloadingFileName = fileName

        val fileEntry = _fileList.value.find { it.name == fileName }
        _currentFileTotalSize.value = fileEntry?.size?.substringBefore(" ")?.toLongOrNull() ?: 0L

        addLog("Requesting file: $currentDownloadingFileName (size: ${_currentFileTotalSize.value} bytes)")
        sendCommand("GET:file:$currentDownloadingFileName")

        // Wait for START signal from device
        addLog("Waiting for START signal from device...")
        _transferReady.first() // Suspend until START signal is received
        addLog("Received START signal. Proceeding with file transfer.")

        // Now that handshake is complete, clear the buffer for incoming file data
        responseBuffer.clear()
        _downloadProgress.value = 0

    }

    fun disconnect() {
        addLog("Disconnecting from device")
        bluetoothGatt?.disconnect()
    }
}
