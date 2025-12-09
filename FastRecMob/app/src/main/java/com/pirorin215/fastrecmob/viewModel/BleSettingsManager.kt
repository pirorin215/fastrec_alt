package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.DeviceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BleSettingsManager(
    private val scope: CoroutineScope,
    private val sendCommand: (String) -> Unit,
    private val logManager: LogManager,
    private val _currentOperation: MutableStateFlow<BleOperation>,
    private val _navigationEvent: MutableSharedFlow<NavigationEvent>
) {
    private val _deviceSettings = MutableStateFlow(DeviceSettings())
    val deviceSettings = _deviceSettings.asStateFlow()

    private val _remoteDeviceSettings = MutableStateFlow<DeviceSettings?>(null)
    val remoteDeviceSettings = _remoteDeviceSettings.asStateFlow()

    private val _settingsDiff = MutableStateFlow<String?>(null)
    val settingsDiff = _settingsDiff.asStateFlow()

    private val responseBuffer = mutableListOf<Byte>()

    fun getSettings(connectionState: String) {
        if (_currentOperation.value != BleOperation.IDLE || connectionState != "Connected") {
            logManager.addLog("Cannot get settings, busy or not connected.")
            return
        }
        _currentOperation.value = BleOperation.FETCHING_SETTINGS
        responseBuffer.clear()
        logManager.addLog("Requesting settings from device...")
        sendCommand("GET:setting_ini")
    }

    fun handleResponse(value: ByteArray) {
        responseBuffer.addAll(value.toList())
        scope.launch {
            delay(200)
            if (_currentOperation.value == BleOperation.FETCHING_SETTINGS) {
                val settingsString = responseBuffer.toByteArray().toString(Charsets.UTF_8)
                logManager.addLog("Assembled remote settings: $settingsString")
                try {
                    val remoteSettings = DeviceSettings.fromIniString(settingsString)
                    _remoteDeviceSettings.value = remoteSettings

                    val diff = remoteSettings.diff(_deviceSettings.value)

                    if (diff.isNotBlank()) {
                        _settingsDiff.value = diff
                        logManager.addLog("Settings have differences.")
                    } else {
                        _settingsDiff.value = "差分はありません。"
                        logManager.addLog("Settings are identical.")
                    }
                } catch (e: Exception) {
                    logManager.addLog("Error parsing settings: ${e.message}")
                }

                _currentOperation.value = BleOperation.IDLE
                responseBuffer.clear()
            }
        }
    }

    fun applyRemoteSettings() {
        _remoteDeviceSettings.value?.let {
            _deviceSettings.value = it
            logManager.addLog("Applied remote settings to local state.")
        }
        dismissSettingsDiff()
    }

    fun dismissSettingsDiff() {
        _remoteDeviceSettings.value = null
        _settingsDiff.value = null
    }

    fun sendSettings(connectionState: String) {
        if (_currentOperation.value != BleOperation.IDLE || connectionState != "Connected") {
            logManager.addLog("Cannot send settings, busy or not connected.")
            return
        }
        val settings = _deviceSettings.value
        dismissSettingsDiff()
        _currentOperation.value = BleOperation.SENDING_SETTINGS
        val iniString = settings.toIniString()
        logManager.addLog("Sending settings to device:\n$iniString")
        sendCommand("SET:setting_ini:$iniString")
        scope.launch {
            _navigationEvent.emit(NavigationEvent.NavigateBack)
        }
    }

    fun updateSettings(updater: (DeviceSettings) -> DeviceSettings) {
        dismissSettingsDiff()
        _deviceSettings.value = updater(_deviceSettings.value)
    }
}
