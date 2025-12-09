package com.pirorin215.fastrecmob.viewModel

import android.content.Context
import com.pirorin215.fastrecmob.LocationData
import com.pirorin215.fastrecmob.LocationTracker
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LocationMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val lastKnownLocationRepository: LastKnownLocationRepository,
    private val logManager: LogManager
) : LocationTracking {
    private val locationTracker = LocationTracker(context)

    private val _currentForegroundLocation = MutableStateFlow<LocationData?>(null)
    override val currentForegroundLocation = _currentForegroundLocation.asStateFlow()

    private var lowPowerLocationJob: Job? = null

    override fun startLowPowerLocationUpdates() {
        if (lowPowerLocationJob?.isActive == true) {
            logManager.addLog("Low power location updates already active.")
            return
        }
        logManager.addLog("Starting low power location updates.")
        lowPowerLocationJob = scope.launch {
            while (true) {
                locationTracker.getLowPowerLocation().onSuccess { locationData ->
                    _currentForegroundLocation.value = locationData
                    logManager.addLog("Pre-collected low power location: Lat=${locationData.latitude}, Lng=${locationData.longitude}")
                }.onFailure { e ->
                    _currentForegroundLocation.value = null // Clear stale location on failure
                    logManager.addLog("Failed to pre-collect low power location: ${e.message}")
                }
                delay(30000L) // Update every 30 seconds
            }
        }
    }

    private suspend fun saveCurrentLocationAsLastKnown() {
        locationTracker.getCurrentLocation().onSuccess { locationData ->
            lastKnownLocationRepository.saveLastKnownLocation(locationData)
            logManager.addLog("Saved last known location.")
        }.onFailure { e ->
            logManager.addLog("Failed to get/save location: ${e.message}")
        }
    }

    override suspend fun updateLocation() {
        logManager.addLog("Triggering one-shot location update.")
        saveCurrentLocationAsLastKnown()
    }

    override fun stopLowPowerLocationUpdates() {
        lowPowerLocationJob?.cancel()
        lowPowerLocationJob = null
        _currentForegroundLocation.value = null // Clear location when stopping
        logManager.addLog("Stopped low power location updates.")
    }

    override fun onCleared() {
        stopLowPowerLocationUpdates()
    }
}
