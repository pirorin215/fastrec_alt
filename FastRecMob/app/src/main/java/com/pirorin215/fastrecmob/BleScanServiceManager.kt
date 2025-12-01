package com.pirorin215.fastrecmob

import android.bluetooth.BluetoothDevice
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object BleScanServiceManager {
    private val _deviceFoundFlow = MutableSharedFlow<BluetoothDevice>(extraBufferCapacity = 1)
    val deviceFoundFlow: SharedFlow<BluetoothDevice> = _deviceFoundFlow.asSharedFlow()

    private val _restartScanFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val restartScanFlow: SharedFlow<Unit> = _restartScanFlow.asSharedFlow()

    suspend fun emitDeviceFound(device: BluetoothDevice) {
        _deviceFoundFlow.emit(device)
    }

    suspend fun emitRestartScan() {
        _restartScanFlow.emit(Unit)
    }
}
