package com.pirorin215.fastrecmob.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository

class BleViewModelFactory(
    private val appSettingsRepository: AppSettingsRepository,
    private val lastKnownLocationRepository: LastKnownLocationRepository,
    private val application: Application,
    private val bleRepository: com.pirorin215.fastrecmob.data.BleRepository,
    private val connectionStateFlow: StateFlow<String>,
    private val onDeviceReadyEvent: SharedFlow<Unit>
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BleViewModel::class.java)) {
            val transcriptionResultRepository = TranscriptionResultRepository(application)
            @Suppress("UNCHECKED_CAST")
                            return BleViewModel(
                                application,
                                appSettingsRepository,
                                transcriptionResultRepository,
                                lastKnownLocationRepository,
                                application,
                                bleRepository,
                                connectionStateFlow,
                                onDeviceReadyEvent
                            ) as T        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class AppSettingsViewModelFactory(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AppSettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return AppSettingsViewModel(appSettingsRepository, application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

class DeviceStatusViewModelFactory(
    private val application: Application,
    private val bleRepository: com.pirorin215.fastrecmob.data.BleRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeviceStatusViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DeviceStatusViewModel(application, application, bleRepository) as T // Pass application as context
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
