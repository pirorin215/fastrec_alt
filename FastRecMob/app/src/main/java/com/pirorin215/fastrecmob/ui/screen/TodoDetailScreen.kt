package com.pirorin215.fastrecmob.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pirorin215.fastrecmob.data.TodoItem
import com.pirorin215.fastrecmob.ui.theme.FastRecMobTheme
import com.pirorin215.fastrecmob.viewModel.TodoViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDetailScreen(
    todoId: String?,
    onBack: () -> Unit,
    todoViewModel: TodoViewModel
) {
    val todoItemState = remember(todoId) { todoViewModel.getTodoItemById(todoId ?: "") }.collectAsState(initial = null)
    val todoItem = todoItemState.value

    var text by remember(todoItem) { mutableStateOf(todoItem?.text ?: "") }
    var notes by remember(todoItem) { mutableStateOf(todoItem?.notes ?: "") }
    var isCompleted by remember(todoItem) { mutableStateOf(todoItem?.isCompleted ?: false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(todoItem?.text ?: "New Todo") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (todoId != null && todoItem != null) {
                        IconButton(onClick = {
                            val updatedTodo = todoItem.copy(
                                text = text,
                                notes = notes,
                                isCompleted = isCompleted
                            )
                            todoViewModel.updateTodoItem(updatedTodo)
                            onBack()
                        }) {
                            Icon(Icons.Default.Check, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        if (todoItem == null && todoId != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                Text("Todo item not found or loading...")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Todo Text") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )
                Spacer(modifier = Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isCompleted,
                        onCheckedChange = { isCompleted = it }
                    )
                    Text("Completed")
                }
                Spacer(modifier = Modifier.height(16.dp))

                if (todoId == null) {
                    Button(onClick = {
                        val newTodo = TodoItem(text = text, notes = notes, isCompleted = isCompleted)
                        todoViewModel.addDetailedTodoItem(newTodo.text, newTodo.notes, newTodo.isCompleted)
                        onBack()
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Add New Todo")
                    }
                }
            }
        }
    }
}

/*
@Preview(showBackground = true)
@Composable
fun PreviewTodoDetailScreen() {
    FastRecMobTheme {
        TodoDetailScreen(todoId = null, onBack = {}, todoViewModel = object : TodoViewModel(
            application = android.app.Application(),
            appSettingsRepository = com.pirorin215.fastrecmob.data.AppSettingsRepository(android.app.Application())
        ) {})
    }
}
*/
