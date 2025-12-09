package com.pirorin215.fastrecmob.viewModel

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LogManager {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    fun addLog(message: String) {
        Log.d("AppLog", message) // Using a generic tag for the manager
        _logs.value = (_logs.value + message).takeLast(100)
    }

    fun clearLogs() {
        _logs.value = emptyList()
    }
}
