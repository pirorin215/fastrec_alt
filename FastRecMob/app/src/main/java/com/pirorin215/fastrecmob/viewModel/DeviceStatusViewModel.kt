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
import com.pirorin215.fastrecmob.viewModel.BleDeviceCommandManager
import com.pirorin215.fastrecmob.viewModel.BleConnectionManager

class DeviceStatusViewModel(
    private val application: Application,
    private val context: Context,
    private val repository: BleRepository,
    private val logManager: LogManager
) : ViewModel() {

    companion object {
        const val TAG = "DeviceStatusViewModel"
    }

    // Internal mutable states
    private val _connectionState = MutableStateFlow<String>("Disconnected")
    private val _deviceInfo = MutableStateFlow<DeviceInfoResponse?>(null)
    private val _currentOperation = MutableStateFlow(BleOperation.IDLE)
    private val bleMutex = Mutex()
    private val _onDeviceReadyEvent = MutableSharedFlow<Unit>()
    val onDeviceReadyEvent = _onDeviceReadyEvent.asSharedFlow()

    // Expose as read-only StateFlows
    val connectionState = _connectionState.asStateFlow()
    val deviceInfo = _deviceInfo.asStateFlow()

    private val bleConnectionManager: BleConnectionManager
    private val bleDeviceCommandManager: BleDeviceCommandManager
    private val _bleDeviceCommandNavigationEvent = MutableSharedFlow<NavigationEvent>()


    init {
        // BleDeviceCommandManager instantiation
        bleDeviceCommandManager = BleDeviceCommandManager(
            scope = viewModelScope,
            context = application, // Context is usually application context for ViewModels
            sendCommand = { command -> sendCommand(command) },
            logManager = logManager,
            _currentOperation = _currentOperation,
            bleMutex = bleMutex,
            onFileListUpdated = { /* DeviceStatusViewModel does not process file lists directly */ },
            _navigationEvent = _bleDeviceCommandNavigationEvent // Pass the local navigation event flow
        )

        // bleConnectionManager initialized after bleDeviceManager
        bleConnectionManager = BleConnectionManager(
            context = context,
            scope = viewModelScope,
            repository = repository,
            logManager = logManager,
            onStateChange = { state ->
                _connectionState.value = when (state) {
                    is ConnectionState.Connected -> "Connected"
                    is ConnectionState.Disconnected -> {
                        resetOperationStates()
                        bleDeviceCommandManager.stopTimeSyncJob()
                        "Disconnected"
                    }
                    is ConnectionState.Error -> {
                        resetOperationStates()
                        bleDeviceCommandManager.stopTimeSyncJob()
                        "Disconnected"
                    }
                    is ConnectionState.Paired -> "Paired"
                    is ConnectionState.Pairing -> "Pairing..."
                }
            },
            onReady = {
                viewModelScope.launch {
                    val timeSyncSuccess = bleDeviceCommandManager.syncTime(_connectionState.value)
                    if (timeSyncSuccess) {
                        bleDeviceCommandManager.startTimeSyncJob() // Start periodic sync
                        bleDeviceCommandManager.fetchDeviceInfo(_connectionState.value)
                    }
                    _onDeviceReadyEvent.emit(Unit) // Emit event when device is ready
                }
            },
            bleDeviceCommandManager = bleDeviceCommandManager
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

        bleDeviceCommandManager.deviceInfo
            .onEach {
                _deviceInfo.value = it
            }
            .launchIn(viewModelScope)
    }

    private fun resetOperationStates() {
        logManager.addLog("Resetting all operation states.")
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
            bleDeviceCommandManager.fetchDeviceInfo(connectionState = connectionState.value)
        }
    }

    // This sendCommand is for BleDeviceManager within this ViewModel
    private fun sendCommand(command: String) {
        repository.sendCommand(command)
    }

    private fun handleCharacteristicChanged(characteristic: android.bluetooth.BluetoothGattCharacteristic, value: ByteArray) {
        if (characteristic.uuid.toString() != RESPONSE_UUID_STRING) return

        when (_currentOperation.value) {
            BleOperation.FETCHING_DEVICE_INFO, BleOperation.SENDING_TIME -> {
                bleDeviceCommandManager.handleResponse(value, _currentOperation.value)
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
        logManager.addLog("DeviceStatusViewModel cleared, BLE resources released.")
    }
}