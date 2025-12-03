package com.pirorin215.fastrecmob.viewModel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.pirorin215.fastrecmob.LocationData
import com.pirorin215.fastrecmob.data.LastKnownLocationRepository
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class LastKnownLocationViewModel(
    private val lastKnownLocationRepository: LastKnownLocationRepository
) : ViewModel() {

    val lastKnownLocation: StateFlow<LocationData?> =
        lastKnownLocationRepository.lastKnownLocationFlow
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = null
            )
}

class LastKnownLocationViewModelFactory(
    private val lastKnownLocationRepository: LastKnownLocationRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LastKnownLocationViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return LastKnownLocationViewModel(lastKnownLocationRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
