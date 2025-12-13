package com.pirorin215.fastrecmob.viewModel

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal interface BleSelection {
    val selectedFileNames: StateFlow<Set<String>>
    fun toggleSelection(fileName: String)
    fun clearSelection()
    fun getSelectedFileNames(): Set<String>
}

class BleSelectionManager(
    private val logManager: LogManager
) : BleSelection {
    private val _selectedFileNames = MutableStateFlow<Set<String>>(emptySet())
    override val selectedFileNames: StateFlow<Set<String>> = _selectedFileNames.asStateFlow()

    override fun toggleSelection(fileName: String) {
        _selectedFileNames.value = if (_selectedFileNames.value.contains(fileName)) {
            _selectedFileNames.value - fileName
        } else {
            _selectedFileNames.value + fileName
        }
        logManager.addLog("Toggled selection for $fileName. Current selections: ${_selectedFileNames.value.size}")
    }

    override fun clearSelection() {
        _selectedFileNames.value = emptySet()
        logManager.addLog("Cleared selection.")
    }
    
    override fun getSelectedFileNames(): Set<String> {
        return _selectedFileNames.value
    }
}
