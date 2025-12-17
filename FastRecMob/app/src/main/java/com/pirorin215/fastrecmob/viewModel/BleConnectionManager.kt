package com.pirorin215.fastrecmob.viewModel

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.pirorin215.fastrecmob.BleScanServiceManager
import com.pirorin215.fastrecmob.data.BleRepository
import com.pirorin215.fastrecmob.data.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

import kotlinx.coroutines.flow.MutableSharedFlow // Add this import

@SuppressLint("MissingPermission")
class BleConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: BleRepository,
    private val logManager: LogManager,
    // These flows are now mutable and passed in from the ViewModel/Activity
    private val _connectionStateFlow: MutableStateFlow<String>,
    private val _onDeviceReadyEvent: MutableSharedFlow<Unit>
) {

    val connectionState = _connectionStateFlow.asStateFlow()

    companion object {
        const val DEVICE_NAME = "fastrec"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    // The internal _connectionState is removed, as we update the external _connectionStateFlow
    // private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    // val connectionState = _connectionState.asStateFlow() // No longer exposed

    init {
        // Collect connection state from the repository
        repository.connectionState.onEach { state ->
            // Update the external flow directly
            _connectionStateFlow.value = when (state) {
                is ConnectionState.Connected -> "Connected"
                is ConnectionState.Disconnected -> {
                    // No longer calling bleDeviceCommandManager.stopTimeSyncJob() here
                    "Disconnected"
                }
                is ConnectionState.Error -> {
                    // No longer calling bleDeviceCommandManager.stopTimeSyncJob() here
                    "Disconnected"
                }
                is ConnectionState.Paired -> "Paired"
                is ConnectionState.Pairing -> "Pairing..."
            }

            when (state) {
                is ConnectionState.Connected -> {
                    logManager.addLog("Successfully connected to ${state.device.address}")
                    repository.requestMtu(517) // Request larger MTU for faster transfers
                }
                is ConnectionState.Disconnected -> {
                    logManager.addLog("Disconnected. Handling reconnection based on app foreground state.")
                }
                is ConnectionState.Error -> {
                    logManager.addLog("Connection Error: ${state.message}. Forcibly disconnecting and cleaning up before recovery.")
                    // Ensure full disconnection and cleanup
                    repository.disconnect()
                    repository.close()

                    // Attempt to recover after a delay
                    scope.launch {
                        delay(500L)
                        logManager.addLog("Attempting to recover after error...")
                        restartScan(forceScan = true)
                    }
                }
                is ConnectionState.Pairing -> logManager.addLog("Pairing with device...")
                is ConnectionState.Paired -> logManager.addLog("Device paired. Connecting...")
            }
        }.launchIn(scope)

        // Collect events from the repository
        repository.events.onEach { event ->
            when (event) {
                is com.pirorin215.fastrecmob.data.BleEvent.MtuChanged -> {
                    logManager.addLog("MTU changed to ${event.mtu}")
                }
                is com.pirorin215.fastrecmob.data.BleEvent.Ready -> {
                    logManager.addLog("Device is ready for communication.")
                    _onDeviceReadyEvent.emit(Unit) // Emit event to the external flow
                }
                // Characteristic changes are handled by the viewmodel that owns the operation (BleOrchestrator)
                else -> { /* Other events can be handled here if needed */ }
            }
        }.launchIn(scope)

        // Listen for devices found by the background scanning service
        scope.launch {
            BleScanServiceManager.deviceFoundFlow.onEach { device ->
                logManager.addLog("Device found by service: ${device.name} (${device.address}). Initiating connection.")
                // Use the external connection state flow to check current state
                if (_connectionStateFlow.value == "Disconnected") { // Compare against String value
                    connect(device)
                } else {
                    logManager.addLog("Already connected or connecting. Skipping new connection attempt.")
                }
            }.launchIn(this)
        }
    }

    fun startScan() {
        logManager.addLog("Manual scan button pressed. Waiting for service to find device.")
        // The actual scan is handled by BleScanService, triggered via UI/ViewModel.
        // This manager listens to the results via BleScanServiceManager.
    }

    fun restartScan(forceScan: Boolean = false) {
        if (!forceScan && _connectionStateFlow.value != "Disconnected") { // Use external flow
            logManager.addLog("Not restarting scan, already connected or connecting. (forceScan=false)")
            return
        }

        logManager.addLog("Attempting to reconnect or scan...")
        // 1. Try to connect to a bonded device first
        val bondedDevices = bluetoothAdapter?.bondedDevices
        val bondedFastRecDevice = bondedDevices?.find { it.name.equals(DEVICE_NAME, ignoreCase = true) }

        if (bondedFastRecDevice != null) {
            logManager.addLog("Found bonded device '${bondedFastRecDevice.name}'. Attempting direct connection.")
            connect(bondedFastRecDevice)
        } else {
            // 2. If no bonded device is found, start a new scan via the service
            logManager.addLog("No bonded device found. Requesting a new scan from the service.")
            scope.launch {
                BleScanServiceManager.emitRestartScan()
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        logManager.addLog("Connecting to device ${device.address}")
        repository.connect(device)
    }

    fun disconnect() {
        logManager.addLog("Disconnecting from device")
        repository.disconnect()
    }

    fun forceReconnect() {
        logManager.addLog("Force reconnect requested. Disconnecting and attempting to restart scan.")
        scope.launch {
            disconnect()
            delay(500L) // Give a short delay for the stack to clear
            restartScan(forceScan = true)
        }
    }

    fun close() {
        repository.close()
        logManager.addLog("Connection manager resources released.")
    }
}
