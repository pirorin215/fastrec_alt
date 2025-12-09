package com.pirorin215.fastrecmob.viewModel

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharedFlow
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import com.pirorin215.fastrecmob.data.TranscriptionResultRepository

import com.pirorin215.fastrecmob.viewModel.MainViewModel
import com.pirorin215.fastrecmob.viewModel.LogManager // Import LogManager once

class MainViewModelFactory(
    private val appSettingsRepository: AppSettingsRepository,
    private val lastKnownLocationRepository: LastKnownLocationRepository,
    private val application: Application,
    private val bleRepository: com.pirorin215.fastrecmob.data.BleRepository,
    private val connectionStateFlow: StateFlow<String>,
    private val onDeviceReadyEvent: SharedFlow<Unit>,
    private val logManager: LogManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainViewModel::class.java)) {
            val transcriptionResultRepository = TranscriptionResultRepository(application)
            @Suppress("UNCHECKED_CAST")
                            return MainViewModel(
                                application,
                                appSettingsRepository,
                                transcriptionResultRepository,
                                lastKnownLocationRepository,
                                bleRepository,
                                connectionStateFlow,
                                onDeviceReadyEvent,
                                logManager
                            ) as T
        }
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
    private val bleRepository: com.pirorin215.fastrecmob.data.BleRepository,
    private val logManager: LogManager
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(DeviceStatusViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return DeviceStatusViewModel(application, application, bleRepository, logManager) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}