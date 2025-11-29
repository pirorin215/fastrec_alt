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
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import kotlinx.serialization.json.Json
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import com.pirorin215.fastrecmob.data.FileEntry
import com.pirorin215.fastrecmob.data.parseFileEntries

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

    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var ackCharacteristic: BluetoothGattCharacteristic? = null

    private val json = Json { ignoreUnknownKeys = true }

    // Buffer for assembling fragmented BLE packets
    private var responseBuffer = mutableListOf<Byte>()

    private fun addLog(message: String) {
        Log.d(TAG, message)
        _logs.value = _logs.value + message
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
            if (characteristic.uuid == UUID.fromString(RESPONSE_UUID_STRING)) {
                addLog("Received packet: ${value.toString(Charsets.UTF_8)}")
                responseBuffer.addAll(value.toList())

                // Send ACK to receive next packet
                if (ackCharacteristic != null) {
                    addLog("Sending ACK")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        bluetoothGatt?.writeCharacteristic(ackCharacteristic!!, "ACK".toByteArray(Charsets.UTF_8), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        ackCharacteristic?.value = "ACK".toByteArray(Charsets.UTF_8)
                        ackCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        bluetoothGatt?.writeCharacteristic(ackCharacteristic!!)
                    }
                }

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
                addLog("Characteristic written successfully: ${characteristic?.uuid}")
            } else {
                addLog("Characteristic write failed with status $status")
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            super.onScanResult(callbackType, result)
            addLog("Scan result: ${result.device.name ?: "Unknown"} (${result.device.address})")
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
        _deviceInfo.value = null // Clear previous device info
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
        if (commandCharacteristic != null) {
            responseBuffer.clear() // Clear buffer before sending a new command
            _deviceInfo.value = null // Clear previous device info
            addLog("Sending command: $command")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                bluetoothGatt?.writeCharacteristic(commandCharacteristic!!, command.toByteArray(Charsets.UTF_8), BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            } else {
                commandCharacteristic?.value = command.toByteArray(Charsets.UTF_8)
                commandCharacteristic?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                bluetoothGatt?.writeCharacteristic(commandCharacteristic!!)
            }
        } else {
            addLog("Command characteristic not found")
        }
    }

    fun disconnect() {
        addLog("Disconnecting from device")
        bluetoothGatt?.disconnect()
    }
}
