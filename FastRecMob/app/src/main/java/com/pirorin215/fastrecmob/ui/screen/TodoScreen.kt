package com.pirorin215.fastrecmob.ui.screen

import android.app.Activity
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pirorin215.fastrecmob.data.TodoItem
import com.pirorin215.fastrecmob.ui.theme.FastRecMobTheme
import com.pirorin215.fastrecmob.viewModel.TodoViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoScreen(
    onBack: () -> Unit,
    todoViewModel: TodoViewModel,
) {
    val account by todoViewModel.account.collectAsState()
    val todoItems by todoViewModel.todoItems.collectAsState()
    val isLoading by todoViewModel.isLoading.collectAsState()
    var showMenu by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState()
    var showBottomSheet by remember { mutableStateOf(false) }
    var selectedTodoItem by remember { mutableStateOf<TodoItem?>(null) }

    val coroutineScope = rememberCoroutineScope()

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.let { intent ->
                todoViewModel.handleSignInResult(
                    intent = intent,
                    onSuccess = { /* Handle success if needed */ },
                    onFailure = { Log.e("TodoScreen", "Sign in failed", it) }
                )
            }
        } else {
             Log.e("TodoScreen", "Sign in cancelled or failed. Result code: ${result.resultCode}")
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Todo List") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (account != null) {
                        IconButton(onClick = { todoViewModel.loadTasks() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        IconButton(onClick = { showMenu = !showMenu }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Sign Out") },
                                onClick = {
                                    todoViewModel.signOut()
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
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            if (account == null) {
                Button(onClick = { signInLauncher.launch(todoViewModel.googleSignInClient.signInIntent) }) {
                    Text("Sign in with Google")
                }
            } else {
                if (isLoading) {
                    CircularProgressIndicator()
                } else {
                    TodoListContent(
                        todoItems = todoItems,
                        onAddTodo = { text -> todoViewModel.addTodoItem(text) },
                        onRemove = { todoViewModel.removeTodoItem(it) },
                        onItemClick = { todoItem ->
                            selectedTodoItem = todoItem
                            showBottomSheet = true
                        }
                    )
                }
            }
        }

        if (showBottomSheet) {
            ModalBottomSheet(
                onDismissRequest = { showBottomSheet = false },
                sheetState = sheetState
            ) {
                TodoDetailScreen(
                    todoItem = selectedTodoItem,
                    onSave = { updatedTodo ->
                        if (updatedTodo.id.isEmpty()) { // New item
                            todoViewModel.addDetailedTodoItem(updatedTodo.text, updatedTodo.notes, updatedTodo.isCompleted)
                        } else { // Existing item
                            todoViewModel.updateTodoItem(updatedTodo)
                        }
                        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showBottomSheet = false
                                selectedTodoItem = null
                            }
                        }
                    },
                    onDelete = { todoItemToDelete ->
                        todoViewModel.removeTodoItem(todoItemToDelete)
                        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showBottomSheet = false
                                selectedTodoItem = null
                            }
                        }
                    },
                    onBack = {
                        coroutineScope.launch { sheetState.hide() }.invokeOnCompletion {
                            if (!sheetState.isVisible) {
                                showBottomSheet = false
                                selectedTodoItem = null
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun TodoListContent(
    todoItems: List<TodoItem>,
    onAddTodo: (String) -> Unit,
    onRemove: (TodoItem) -> Unit,
    onItemClick: (TodoItem) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        AddTodoItemInput(onAddTodo = onAddTodo)
        Spacer(modifier = Modifier.height(16.dp))
        if (todoItems.isEmpty()) {
            Text("No todo items in 'fastrec' list.", modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            LazyColumn {
                items(todoItems, key = { it.id }) { todoItem ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .clickable {
                                Log.d("TodoScreen", "Todo item clicked: ${todoItem.id}")
                                onItemClick(todoItem)
                            }
                    ) {
                        TodoItemRow(
                            todoItem = todoItem,
                            onRemove = onRemove
                        )
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
    onRemove: (TodoItem) -> Unit
) {
    val isCompleted = todoItem.isCompleted
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(
                text = todoItem.text,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
            )
        }
        // IconButton for deletion can be added here if needed
    }
}

/*
@Preview(showBackground = true)
@Composable
fun PreviewTodoScreen() {
    FastRecMobTheme {
        // This preview will only show the initial state (sign-in button)
        // as we cannot easily create a mock ViewModel with a signed-in state here.
        val application = LocalContext.current.applicationContext as android.app.Application
        val appSettingsRepository = com.pirorin215.fastrecmob.data.AppSettingsRepository(application)
        TodoScreen(onBack = {}, todoViewModel = TodoViewModel(application, appSettingsRepository), onNavigateToDetail = {})
    }
}
*/
