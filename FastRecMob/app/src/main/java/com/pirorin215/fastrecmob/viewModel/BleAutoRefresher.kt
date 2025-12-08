package com.pirorin215.fastrecmob.viewModel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class BleAutoRefresher(
    private val scope: CoroutineScope,
    private val refreshIntervalSecondsFlow: StateFlow<Int>,
    private val onRefresh: () -> Unit,
    private val logCallback: (String) -> Unit
) {
    private val _isAutoRefreshEnabled = MutableStateFlow(true)
    val isAutoRefreshEnabled = _isAutoRefreshEnabled.asStateFlow()

    private var autoRefreshJob: Job? = null

    fun setAutoRefresh(enabled: Boolean) {
        _isAutoRefreshEnabled.value = enabled
        if (enabled) {
            logCallback("Auto-refresh enabled.")
            onRefresh() // Immediate refresh
            startAutoRefresh()
        } else {
            logCallback("Auto-refresh disabled.")
            stopAutoRefresh()
        }
    }

    fun startAutoRefresh() {
        stopAutoRefresh()
        autoRefreshJob = scope.launch {
            while (true) {
                // Use the value from the StateFlow, converting seconds to milliseconds
                val intervalMs = (refreshIntervalSecondsFlow.value * 1000L).coerceAtLeast(5000L) // Ensure minimum 5s
                delay(intervalMs)
                onRefresh()
            }
        }
    }

    fun stopAutoRefresh() {
        autoRefreshJob?.cancel()
        autoRefreshJob = null
    }
}
