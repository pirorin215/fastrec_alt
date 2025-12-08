package com.pirorin215.fastrecmob.viewModel

import com.pirorin215.fastrecmob.data.TranscriptionResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class BleSelectionManager(
    private val logCallback: (String) -> Unit
) {
    private val _selectedFileNames = MutableStateFlow<Set<String>>(emptySet())
    val selectedFileNames: StateFlow<Set<String>> = _selectedFileNames.asStateFlow()

    fun toggleSelection(fileName: String) {
        _selectedFileNames.value = if (_selectedFileNames.value.contains(fileName)) {
            _selectedFileNames.value - fileName
        } else {
            _selectedFileNames.value + fileName
        }
        logCallback("Toggled selection for $fileName. Current selections: ${_selectedFileNames.value.size}")
    }

    fun clearSelection() {
        _selectedFileNames.value = emptySet()
        logCallback("Cleared selection.")
    }
    
    fun getSelectedFileNames(): Set<String> {
        return _selectedFileNames.value
    }
}
