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
    private val logCallback: (String) -> Unit
) {
    private val locationTracker = LocationTracker(context)

    private val _currentForegroundLocation = MutableStateFlow<LocationData?>(null)
    val currentForegroundLocation = _currentForegroundLocation.asStateFlow()

    private var lowPowerLocationJob: Job? = null

    fun startLowPowerLocationUpdates() {
        if (lowPowerLocationJob?.isActive == true) {
            logCallback("Low power location updates already active.")
            return
        }
        logCallback("Starting low power location updates.")
        lowPowerLocationJob = scope.launch {
            while (true) {
                locationTracker.getLowPowerLocation().onSuccess { locationData ->
                    _currentForegroundLocation.value = locationData
                    logCallback("Pre-collected low power location: Lat=${locationData.latitude}, Lng=${locationData.longitude}")
                }.onFailure { e ->
                    _currentForegroundLocation.value = null // Clear stale location on failure
                    logCallback("Failed to pre-collect low power location: ${e.message}")
                }
                delay(30000L) // Update every 30 seconds
            }
        }
    }

    suspend fun saveCurrentLocationAsLastKnown() {
        locationTracker.getCurrentLocation().onSuccess { locationData ->
            lastKnownLocationRepository.saveLastKnownLocation(locationData)
            logCallback("Saved last known location.")
        }.onFailure { e ->
            logCallback("Failed to get/save location: ${e.message}")
        }
    }

    fun stopLowPowerLocationUpdates() {
        lowPowerLocationJob?.cancel()
        lowPowerLocationJob = null
        _currentForegroundLocation.value = null // Clear location when stopping
        logCallback("Stopped low power location updates.")
    }

    fun onCleared() {
        stopLowPowerLocationUpdates()
    }
}