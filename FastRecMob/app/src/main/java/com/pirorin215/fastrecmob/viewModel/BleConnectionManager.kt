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

@SuppressLint("MissingPermission")
class BleConnectionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: BleRepository,
    private val onStateChange: (ConnectionState) -> Unit,
    private val onReady: () -> Unit,
    private val addLog: (String) -> Unit
) {

    companion object {
        const val DEVICE_NAME = "fastrec"
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState = _connectionState.asStateFlow()

    init {
        // Collect connection state from the repository
        repository.connectionState.onEach { state ->
            _connectionState.value = state
            onStateChange(state) // Propagate state change up

            when (state) {
                is ConnectionState.Connected -> {
                    addLog("Successfully connected to ${state.device.address}")
                    repository.requestMtu(517) // Request larger MTU for faster transfers
                }
                is ConnectionState.Disconnected -> {
                    addLog("Disconnected. Handling reconnection based on app foreground state.")
                }
                is ConnectionState.Error -> {
                    addLog("Connection Error: ${state.message}. Forcibly disconnecting and cleaning up before recovery.")
                    // Ensure full disconnection and cleanup
                    repository.disconnect()
                    repository.close()

                    // Attempt to recover after a delay
                    scope.launch {
                        delay(500L)
                        addLog("Attempting to recover after error...")
                        restartScan(forceScan = true)
                    }
                }
                is ConnectionState.Pairing -> addLog("Pairing with device...")
                is ConnectionState.Paired -> addLog("Device paired. Connecting...")
            }
        }.launchIn(scope)

        // Collect events from the repository
        repository.events.onEach { event ->
            when (event) {
                is com.pirorin215.fastrecmob.data.BleEvent.MtuChanged -> {
                    addLog("MTU changed to ${event.mtu}")
                }
                is com.pirorin215.fastrecmob.data.BleEvent.Ready -> {
                    addLog("Device is ready for communication.")
                    onReady() // Notify that the device is ready
                }
                // Characteristic changes are handled by the viewmodel that owns the operation
                else -> { /* Other events can be handled here if needed */ }
            }
        }.launchIn(scope)

        // Listen for devices found by the background scanning service
        scope.launch {
            BleScanServiceManager.deviceFoundFlow.onEach { device ->
                addLog("Device found by service: ${device.name} (${device.address}). Initiating connection.")
                if (_connectionState.value is ConnectionState.Disconnected) {
                    connect(device)
                } else {
                    addLog("Already connected or connecting. Skipping new connection attempt.")
                }
            }.launchIn(this)
        }
    }

    fun startScan() {
        addLog("Manual scan button pressed. Waiting for service to find device.")
        // The actual scan is handled by BleScanService, triggered via UI/ViewModel.
        // This manager listens to the results via BleScanServiceManager.
    }

    fun restartScan(forceScan: Boolean = false) {
        if (!forceScan && _connectionState.value !is ConnectionState.Disconnected) {
            addLog("Not restarting scan, already connected or connecting. (forceScan=false)")
            return
        }

        addLog("Attempting to reconnect or scan...")
        // 1. Try to connect to a bonded device first
        val bondedDevices = bluetoothAdapter?.bondedDevices
        val bondedFastRecDevice = bondedDevices?.find { it.name.equals(DEVICE_NAME, ignoreCase = true) }

        if (bondedFastRecDevice != null) {
            addLog("Found bonded device '${bondedFastRecDevice.name}'. Attempting direct connection.")
            connect(bondedFastRecDevice)
        } else {
            // 2. If no bonded device is found, start a new scan via the service
            addLog("No bonded device found. Requesting a new scan from the service.")
            scope.launch {
                BleScanServiceManager.emitRestartScan()
            }
        }
    }

    fun connect(device: BluetoothDevice) {
        addLog("Connecting to device ${device.address}")
        repository.connect(device)
    }

    fun disconnect() {
        addLog("Disconnecting from device")
        repository.disconnect()
    }

    fun forceReconnect() {
        addLog("Force reconnect requested. Disconnecting and attempting to restart scan.")
        scope.launch {
            disconnect()
            delay(500L) // Give a short delay for the stack to clear
            restartScan(forceScan = true)
        }
    }

    fun close() {
        repository.close()
        addLog("Connection manager resources released.")
    }
}
