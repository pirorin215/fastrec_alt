package com.pirorin215.fastrecmob.viewModel

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import com.pirorin215.fastrecmob.data.TodoItem
import org.burnoutcrew.reorderable.ItemPosition

class TodoViewModel : ViewModel() {

    private val _todoItems = mutableStateListOf<TodoItem>()
    val todoItems: List<TodoItem> get() = _todoItems

    private val _sortMode: MutableState<TodoSortMode> = mutableStateOf(TodoSortMode.DEFAULT)
    val sortMode: State<TodoSortMode> = _sortMode

    fun addTodoItem(text: String) {
        if (text.isNotBlank()) {
            _todoItems.add(TodoItem(text = text))
            applySorting()
        }
    }

    fun toggleTodoCompletion(item: TodoItem) {
        val index = _todoItems.indexOf(item)
        if (index != -1) {
            _todoItems[index].isCompleted.value = !_todoItems[index].isCompleted.value
            applySorting()
        }
    }

    fun removeTodoItem(item: TodoItem) {
        _todoItems.remove(item)
    }

    fun updateTodoItemText(item: TodoItem, newText: String) {
        val index = _todoItems.indexOf(item)
        if (index != -1) {
            _todoItems[index] = _todoItems[index].copy(text = newText)
        }
    }

    fun setSortMode(mode: TodoSortMode) {
        _sortMode.value = mode
        applySorting()
    }

    fun moveItem(from: ItemPosition, to: ItemPosition) {
        // Prevent accidental reordering if not in a "custom" or "default" sort mode
        if (_sortMode.value != TodoSortMode.DEFAULT) {
            return
        }
        val fromIndex = from.index
        val toIndex = to.index
        val item = _todoItems.removeAt(fromIndex)
        _todoItems.add(toIndex, item)
    }

    private fun applySorting() {
        if (_sortMode.value == TodoSortMode.DEFAULT) {
            // When in default mode, we allow manual reordering, so don't apply an explicit sort order
            // The order is managed by direct manipulation (e.g., moveItem)
            return
        }

        _todoItems.sortWith(
            when (_sortMode.value) {
                TodoSortMode.DEFAULT -> compareBy { it.id } // Fallback, should not be reached if previous 'return' works
                TodoSortMode.ALPHABETICAL_ASC -> compareBy { it.text.lowercase() }
                TodoSortMode.ALPHABETICAL_DESC -> compareByDescending { it.text.lowercase() }
                TodoSortMode.COMPLETION_STATUS -> compareBy { it.isCompleted.value }
            }
        )
    }

    enum class TodoSortMode {
        DEFAULT,
        ALPHABETICAL_ASC,
        ALPHABETICAL_DESC,
        COMPLETION_STATUS
    }
}
