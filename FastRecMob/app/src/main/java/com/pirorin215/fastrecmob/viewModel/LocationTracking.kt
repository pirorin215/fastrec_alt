package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.LocationData
import kotlinx.coroutines.flow.StateFlow

interface LocationTracking {
    val currentForegroundLocation: StateFlow<LocationData?>
    fun startLowPowerLocationUpdates()
    fun stopLowPowerLocationUpdates()
    suspend fun updateLocation()
    fun onCleared()
}
