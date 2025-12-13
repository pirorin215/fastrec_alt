package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.DeviceSettings
import com.pirorin215.fastrecmob.data.DeviceInfoResponse
import com.pirorin215.fastrecmob.data.FileEntry
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface BleOrchestration {
    val currentOperation: StateFlow<BleOperation>
    val navigationEvent: SharedFlow<NavigationEvent>
    val fileList: StateFlow<List<FileEntry>>
    val deviceInfo: StateFlow<DeviceInfoResponse?>
    val deviceSettings: StateFlow<DeviceSettings>
    val remoteDeviceSettings: StateFlow<DeviceSettings?>
    val settingsDiff: StateFlow<String?>
    val isAutoRefreshEnabled: StateFlow<Boolean>
    val downloadProgress: StateFlow<Int>
    val currentFileTotalSize: StateFlow<Long>
    val fileTransferState: StateFlow<String>
    val transferKbps: StateFlow<Float>

    fun stop()
    fun setAutoRefresh(enabled: Boolean)
    fun clearLogs()
    fun sendCommand(command: String)
    fun fetchFileList(extension: String = "wav")
    suspend fun getSettings()
    fun applyRemoteSettings()
    fun dismissSettingsDiff()
    fun sendSettings()
    fun updateSettings(updater: (DeviceSettings) -> DeviceSettings)
    fun downloadFile(fileName: String)
}
