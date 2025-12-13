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
    private val onRefresh: suspend () -> Unit,
    private val logManager: LogManager
) {
    private val _isAutoRefreshEnabled = MutableStateFlow(false)
    val isAutoRefreshEnabled = _isAutoRefreshEnabled.asStateFlow()

    private var autoRefreshJob: Job? = null

    fun setAutoRefresh(enabled: Boolean) {
        _isAutoRefreshEnabled.value = enabled
        if (enabled) {
            logManager.addLog("Auto-refresh enabled.")
            scope.launch { // Launch a coroutine for the initial suspend call
                onRefresh() // Immediate refresh
            }
            startAutoRefresh()
        } else {
            logManager.addLog("Auto-refresh disabled.")
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
