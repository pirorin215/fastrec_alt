package com.pirorin215.fastrecmob.viewModel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pirorin215.fastrecmob.data.BleRepository
import com.pirorin215.fastrecmob.data.ConnectionState
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import com.pirorin215.fastrecmob.data.BleRepository.Companion.RESPONSE_UUID_STRING // Corrected import for RESPONSE_UUID_STRING
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.util.UUID

class DeviceStatusViewModel(
    private val application: Application,
    private val context: Context,
    private val repository: BleRepository
) : ViewModel() {

    companion object {
        const val TAG = "DeviceStatusViewModel"
    }

    // Internal mutable states
    private val _connectionState = MutableStateFlow<String>("Disconnected")
    private val _deviceInfo = MutableStateFlow<DeviceInfoResponse?>(null)
    private val _currentOperation = MutableStateFlow(BleOperation.IDLE)
    private val bleMutex = Mutex()
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    private val _onDeviceReadyEvent = MutableSharedFlow<Unit>()
    val onDeviceReadyEvent = _onDeviceReadyEvent.asSharedFlow()



    // Expose as read-only StateFlows
    val connectionState = _connectionState.asStateFlow()
    val deviceInfo = _deviceInfo.asStateFlow()

    private val bleConnectionManager: BleConnectionManager
    private val bleDeviceManager: BleDeviceManager


    init {
        // BleDeviceManager instantiation - first
        bleDeviceManager = BleDeviceManager(
            scope = viewModelScope,
            context = context,
            sendCommand = { command -> sendCommand(command) },
            addLog = { addLog(it) },
            _currentOperation = _currentOperation,
            bleMutex = bleMutex,
            onFileListUpdated = { /* Handled by BleViewModel */ }
        )

        // bleConnectionManager initialized after bleDeviceManager
        bleConnectionManager = BleConnectionManager(
            context = context,
            scope = viewModelScope,
            repository = repository,
            addLog = { addLog(it) },
            onStateChange = { state ->
                _connectionState.value = when (state) {
                    is ConnectionState.Connected -> "Connected"
                    is ConnectionState.Disconnected -> {
                        resetOperationStates()
                        // Stop time sync job when disconnected
                        bleDeviceManager.stopTimeSyncJob()
                        "Disconnected"
                    }
                    is ConnectionState.Error -> {
                        resetOperationStates()
                        bleDeviceManager.stopTimeSyncJob()
                        "Disconnected"
                    }
                    is ConnectionState.Paired -> "Paired"
                    is ConnectionState.Pairing -> "Pairing..."
                }
            },
            onReady = {
                viewModelScope.launch {
                    val timeSyncSuccess = bleDeviceManager.syncTime(_connectionState.value)
                    if (timeSyncSuccess) {
                        bleDeviceManager.startTimeSyncJob() // Start periodic sync
                        bleDeviceManager.fetchDeviceInfo(_connectionState.value)
                    }
                    _onDeviceReadyEvent.emit(Unit) // Emit event when device is ready
                }
            }
        )

        // Collect characteristic changes from the repository
        repository.events.onEach { event ->
            when(event) {
                is com.pirorin215.fastrecmob.data.BleEvent.CharacteristicChanged -> {
                    // Only handle device info related responses for now
                    handleCharacteristicChanged(event.characteristic, event.value)
                }
                else -> {}
            }
        }.launchIn(viewModelScope)

        // Observe bleDeviceManager's deviceInfo and update our own
        bleDeviceManager.deviceInfo
            .onEach {
                _deviceInfo.value = it
            }
            .launchIn(viewModelScope)
    }

    private fun addLog(message: String) {
        Log.d(TAG, message)
        _logs.value = (_logs.value + message).takeLast(100)
    }

    private fun resetOperationStates() {
        addLog("Resetting all operation states.")
        _currentOperation.value = BleOperation.IDLE
    }

    fun startScan() {
        bleConnectionManager.startScan()
    }

    fun disconnect() {
        bleConnectionManager.disconnect()
    }

    fun forceReconnectBle() {
        bleConnectionManager.forceReconnect()
    }

    fun fetchDeviceInfo() {
        viewModelScope.launch {
            bleDeviceManager.fetchDeviceInfo(connectionState = connectionState.value)
        }
    }

    // This sendCommand is for BleDeviceManager within this ViewModel
    private fun sendCommand(command: String) {
        repository.sendCommand(command)
    }

    private fun handleCharacteristicChanged(characteristic: android.bluetooth.BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid.toString() != RESPONSE_UUID_STRING) return

        when (_currentOperation.value) {
            BleOperation.FETCHING_DEVICE_INFO, BleOperation.SENDING_TIME -> { // SENDING_TIME also updates device info
                bleDeviceManager.handleResponse(value)
            }
            else -> {
                // Ignore other operations for this ViewModel
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        bleConnectionManager.disconnect()
        bleConnectionManager.close()
        addLog("DeviceStatusViewModel cleared, BLE resources released.")
    }
}