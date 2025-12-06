package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pirorin215.fastrecmob.data.TodoItem
import com.pirorin215.fastrecmob.ui.theme.FastRecMobTheme
import com.pirorin215.fastrecmob.viewModel.TodoViewModel
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyListState
import org.burnoutcrew.reorderable.reorderable

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    onBack: () -> Unit,
    todoViewModel: TodoViewModel = viewModel()
) {
    val todoItems by remember { mutableStateOf(todoViewModel.todoItems) }
    val sortMode by todoViewModel.sortMode
    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Todo List") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = !showMenu }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        TodoViewModel.TodoSortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.name.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }) },
                                onClick = {
                                    todoViewModel.setSortMode(mode)
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            AddTodoItemInput(onAddTodo = { todoViewModel.addTodoItem(it) })
            Spacer(modifier = Modifier.height(16.dp))
            if (todoItems.isEmpty()) {
                Text("No todo items yet. Add one above!", modifier = Modifier.align(Alignment.CenterHorizontally))
            } else {
                val reorderableState = rememberReorderableLazyListState(onMove = todoViewModel::moveItem)
                LazyColumn(
                    state = reorderableState.listState,
                    modifier = Modifier
                        .reorderable(reorderableState)
                        .detectReorderAfterLongPress(reorderableState)
                ) {
                    items(todoItems, key = { it.id }) { todoItem ->
                        ReorderableItem(reorderableState, key = todoItem.id) { isDragging ->
                            val elevation = if (isDragging) 8.dp else 0.dp // Example elevation for visual feedback
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                elevation = CardDefaults.cardElevation(defaultElevation = elevation)
                            ) {
                                TodoItemRow(
                                    todoItem = todoItem,
                                    onToggleCompletion = { todoViewModel.toggleTodoCompletion(todoItem) },
                                    onRemove = { todoViewModel.removeTodoItem(todoItem) },
                                    isDragging = isDragging
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddTodoItemInput(onAddTodo: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("New Todo") },
            modifier = Modifier.weight(1f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = {
                if (text.isNotBlank()) {
                    onAddTodo(text)
                    text = ""
                }
            },
            enabled = text.isNotBlank()
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add Todo")
        }
    }
}

@Composable
fun TodoItemRow(
    todoItem: TodoItem,
    onToggleCompletion: (TodoItem) -> Unit,
    onRemove: (TodoItem) -> Unit,
    isDragging: Boolean = false
) {
    val isCompleted by todoItem.isCompleted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onToggleCompletion(todoItem) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = isCompleted,
                onCheckedChange = { onToggleCompletion(todoItem) }
            )
            Text(
                text = todoItem.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
        }
        IconButton(onClick = { onRemove(todoItem) }) {
            Icon(Icons.Default.MoreVert, contentDescription = "Remove Todo")
            // Ideally, this would be a delete icon or a menu with delete
            // For simplicity, reusing MoreVert for now.
        }
    }
}

@Preview(showBackground = true)
@Composable
fun PreviewTodoScreen() {
    FastRecMobTheme {
        TodoScreen(onBack = {})
    }
}
