package com.pirorin215.fastrecmob.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.pirorin215.fastrecmob.viewModel.MainViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay // Add this import
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch // Add this import
import java.util.UUID

sealed class ConnectionState {
    object Disconnected : ConnectionState()
    object Pairing : ConnectionState()
    data class Paired(val device: BluetoothDevice) : ConnectionState()
    data class Connected(val device: BluetoothDevice) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class BleEvent {
    data class MtuChanged(val mtu: Int) : BleEvent()
    object ServicesDiscovered : BleEvent()
    data class CharacteristicChanged(val characteristic: BluetoothGattCharacteristic, val value: ByteArray) : BleEvent()
    object Ready : BleEvent()
    data class Error(val message: String) : BleEvent()
}

@SuppressLint("MissingPermission")
class BleRepository(private val context: Context) {

    companion object {
        const val SERVICE_UUID_STRING = "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
        const val COMMAND_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26aa"
        const val RESPONSE_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ab"
        const val ACK_UUID_STRING = "beb5483e-36e1-4688-b7f5-ea07361b26ac"
        const val CCCD_UUID_STRING = "00002902-0000-1000-8000-00805f9b34fb"
    }

    private val TAG = "BleRepository"
    private val repositoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private var bluetoothGatt: BluetoothGatt? = null

    var commandCharacteristic: BluetoothGattCharacteristic? = null
    var ackCharacteristic: BluetoothGattCharacteristic? = null

    // --- Flows to expose data to ViewModel ---
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    private val _events = MutableSharedFlow<BleEvent>()
    val events = _events.asSharedFlow()

    private val gattCallback: BluetoothGattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val deviceAddress = gatt.device.address
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    Log.d(TAG, "Successfully connected to $deviceAddress")
                    _connectionState.value = ConnectionState.Connected(gatt.device)
                    // MTU request should be initiated by the ViewModel after connection.
                    // For now, we discover services directly. A delay might be needed.
                    repositoryScope.launch {
                        delay(600) // Recommended delay before service discovery
                        val initiated = gatt.discoverServices()
                        if (!initiated) {
                            Log.e(TAG, "Failed to initiate service discovery.")
                            disconnect()
                        }
                    }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.d(TAG, "Successfully disconnected from $deviceAddress")
                    close() // Close the GATT client.
                    _connectionState.value = ConnectionState.Disconnected
                }
            } else {
                Log.e(TAG, "onConnectionStateChange error: status=$status for $deviceAddress")
                close()
                _connectionState.value = ConnectionState.Error("GATT Error $status")
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "MTU changed to $mtu")
                repositoryScope.launch { _events.emit(BleEvent.MtuChanged(mtu)) }
            } else {
                Log.w(TAG, "MTU change failed, status: $status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Services discovered successfully.")
                // Store characteristics
                val service = gatt.getService(UUID.fromString(SERVICE_UUID_STRING))
                if (service == null) {
                    Log.e(TAG, "Custom service (${SERVICE_UUID_STRING}) not found.")
                    disconnect()
                    return
                }
                commandCharacteristic = service.getCharacteristic(UUID.fromString(COMMAND_UUID_STRING))
                ackCharacteristic = service.getCharacteristic(UUID.fromString(ACK_UUID_STRING))
                val responseCharacteristic = service.getCharacteristic(UUID.fromString(RESPONSE_UUID_STRING))

                if (commandCharacteristic == null || ackCharacteristic == null || responseCharacteristic == null) {
                    Log.e(TAG, "One or more required characteristics not found.")
                    disconnect()
                    return
                }

                // Enable notifications for the response characteristic
                gatt.setCharacteristicNotification(responseCharacteristic, true)
                val descriptor = responseCharacteristic.getDescriptor(UUID.fromString(CCCD_UUID_STRING))
                if (descriptor != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        gatt.writeDescriptor(descriptor)
                    }
                    Log.d(TAG, "Writing descriptor to enable notifications for response characteristic.")
                } else {
                    Log.e(TAG, "CCCD descriptor not found for response characteristic.")
                    disconnect()
                }
            } else {
                Log.w(TAG, "Service discovery failed with status $status")
                disconnect()
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.d(TAG, "Descriptor written successfully. Repository is ready.")
                repositoryScope.launch { _events.emit(BleEvent.Ready) }
            } else {
                Log.e(TAG, "Descriptor write failed with status $status")
                disconnect()
            }
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            // Pass the raw data up to the ViewModel to handle
            // Log.d(TAG, "Characteristic ${characteristic.uuid} changed, value: ${value.toString(Charsets.UTF_8)}")
            repositoryScope.launch {
                _events.emit(BleEvent.CharacteristicChanged(characteristic, value))
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Characteristic write failed for ${characteristic?.uuid} with status $status")
                repositoryScope.launch { _events.emit(BleEvent.Error("Write failed with status $status"))}
            }
        }
    }

    private val bondStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                val previousBondState = intent.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR)

                Log.d(TAG, "Bond state changed for device ${device?.address}: ${previousBondState} -> ${bondState}")

                when (bondState) {
                    BluetoothDevice.BOND_BONDED -> {
                        Log.d(TAG, "Device bonded: ${device?.address}")
                        _connectionState.value = ConnectionState.Paired(device!!)
                        connectGatt(device)
                        context.unregisterReceiver(this)
                    }
                    BluetoothDevice.BOND_NONE -> {
                        Log.e(TAG, "Bonding failed or was cancelled for device ${device?.address}")
                        _connectionState.value = ConnectionState.Error("Bonding failed")
                        context.unregisterReceiver(this)
                    }
                    BluetoothDevice.BOND_BONDING -> {
                        Log.d(TAG, "Bonding with device ${device?.address}...")
                        _connectionState.value = ConnectionState.Pairing
                    }
                }
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        Log.d(TAG, "Connecting to device ${device.address}, bond state: ${device.bondState}")
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> {
                _connectionState.value = ConnectionState.Paired(device)
                connectGatt(device)
            }
            BluetoothDevice.BOND_NONE -> {
                Log.d(TAG, "Device not bonded. Starting bonding process.")
                _connectionState.value = ConnectionState.Pairing
                val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(bondStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.registerReceiver(bondStateReceiver, filter)
                }

                if (!device.createBond()) {
                    Log.e(TAG, "Failed to start bonding.")
                    _connectionState.value = ConnectionState.Error("Failed to start bonding")
                    context.unregisterReceiver(bondStateReceiver)
                }
            }
            BluetoothDevice.BOND_BONDING -> {
                Log.d(TAG, "Device is already bonding.")
                _connectionState.value = ConnectionState.Pairing
            }
        }
    }

    private fun connectGatt(device: BluetoothDevice) {
        Log.d(TAG, "Proceeding with GATT connection to ${device.address}")
        bluetoothGatt = device.connectGatt(context, false, gattCallback)
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting from device")
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, which is fine.
        }
        bluetoothGatt?.disconnect()
    }

    fun close() {
        Log.d(TAG, "Closing GATT connection")
        try {
            context.unregisterReceiver(bondStateReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was not registered, which is fine.
        }
        bluetoothGatt?.close()
        bluetoothGatt = null
    }

    fun requestMtu(mtu: Int): Boolean {
        Log.d(TAG, "Requesting MTU of $mtu")
        return bluetoothGatt?.requestMtu(mtu) ?: false
    }

    fun sendCommand(command: String): Boolean {
        val characteristic = commandCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "Command characteristic not found")
            return false
        }
        Log.d(TAG, "Sending command: $command")
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic,
                command.toByteArray(Charsets.UTF_8),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = command.toByteArray(Charsets.UTF_8)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }
    }

    internal fun sendAck(ackValue: ByteArray): Boolean {
        val characteristic = ackCharacteristic
        if (characteristic == null) {
            Log.e(TAG, "ACK characteristic not found")
            return false
        }
        // Log.d(TAG, "Sending ACK: ${ackValue.toString(Charsets.UTF_8)}") // ACK is very frequent, so logging is disabled.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bluetoothGatt?.writeCharacteristic(
                characteristic,
                ackValue,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = ackValue
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            bluetoothGatt?.writeCharacteristic(characteristic) ?: false
        }
    }
}
