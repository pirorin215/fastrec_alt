package com.pirorin215.fastrecmob.viewModel

import kotlinx.coroutines.flow.StateFlow

interface BleSelection {
    val selectedFileNames: StateFlow<Set<String>>
    fun toggleSelection(fileName: String)
    fun clearSelection()
    fun getSelectedFileNames(): Set<String>
}
