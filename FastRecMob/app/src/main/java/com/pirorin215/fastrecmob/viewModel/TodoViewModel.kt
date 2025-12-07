package com.pirorin215.fastrecmob.viewModel

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.pirorin215.fastrecmob.data.TodoItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import com.pirorin215.fastrecmob.data.AppSettingsRepository
import kotlinx.coroutines.flow.first

// Data classes for JSON parsing
@Serializable
data class TaskListsResponse(val items: List<TaskList> = emptyList())

@Serializable
data class TaskList(val id: String, val title: String)

@Serializable
data class TasksResponse(val items: List<Task>? = null)

@Serializable
data class Task(
    val id: String? = null,
    val title: String? = null,
    val status: String? = null,
    val notes: String? = null,
    val updated: String? = null, // RFC 3339 timestamp
    val position: String? = null,
    val due: String? = null, // RFC 3339 timestamp
    val webViewLink: String? = null
)

class TodoViewModel(
    application: Application,
    private val appSettingsRepository: AppSettingsRepository
) : AndroidViewModel(application) {

    private val _todoItems = MutableStateFlow<List<TodoItem>>(emptyList())
    val todoItems: StateFlow<List<TodoItem>> = _todoItems.asStateFlow()

    private val _account = MutableStateFlow<GoogleSignInAccount?>(null)
    val account: StateFlow<GoogleSignInAccount?> = _account.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val googleSignInClient: GoogleSignInClient

    private var taskListId: String? = null

    private val json = Json { ignoreUnknownKeys = true }
    private val tasksScope = "https://www.googleapis.com/auth/tasks"

    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(tasksScope))
            .build()
        googleSignInClient = GoogleSignIn.getClient(application, gso)

        viewModelScope.launch {
            _account.value = GoogleSignIn.getLastSignedInAccount(getApplication())
            if (_account.value != null) {
                loadTasks()
            }

            // Observe changes to the Google Todo list name and clear the cached taskListId
            appSettingsRepository.googleTodoListNameFlow.collect {
                taskListId = null
            }
        }
    }

    fun handleSignInResult(intent: Intent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
            _account.value = task.getResult(ApiException::class.java)
            viewModelScope.launch {
                loadTasks()
                withContext(Dispatchers.Main) { onSuccess() }
            }
        } catch (e: ApiException) {
            Log.e(TAG, "signInResult:failed code=" + e.statusCode, e)
            onFailure(e)
        }
    }

    fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            _account.value = null
            _todoItems.value = emptyList()
            taskListId = null
        }
    }

    fun loadTasks() = viewModelScope.launch {
        if (_account.value == null) return@launch
        _isLoading.value = true
        try {
            val currentTaskListId = taskListId ?: getTaskListId() ?: return@launch
            if (currentTaskListId == null) {
                Log.e(TAG, "No task list found.")
                _todoItems.value = emptyList()
                return@launch
            }

            val url = "https://www.googleapis.com/tasks/v1/lists/$currentTaskListId/tasks"
            val response = makeApiRequest(url)

            val tasksResponse = json.decodeFromString<TasksResponse>(response)
            _todoItems.value = tasksResponse.items?.mapNotNull { task ->
                task.id?.let {
                    TodoItem(
                        id = it,
                        text = task.title ?: "",
                        isCompleted = (task.status == "completed"),
                        notes = task.notes,
                        updated = task.updated,
                        position = task.position,
                        due = task.due,
                        webViewLink = task.webViewLink
                    )
                }
            } ?: emptyList()

        } catch (e: Exception) {
            Log.e(TAG, "Error loading tasks", e)
            _todoItems.value = emptyList()
        } finally {
            _isLoading.value = false
        }
    }

    fun toggleTodoCompletion(item: TodoItem) = viewModelScope.launch {
        if (_account.value == null) return@launch
        try {
            val currentTaskListId = taskListId ?: getTaskListId() ?: return@launch
            val newStatus = if (item.isCompleted) "needsAction" else "completed"
            val url = "https://www.googleapis.com/tasks/v1/lists/$currentTaskListId/tasks/${item.id}"
            val taskJson = json.encodeToString(Task.serializer(), Task(id = item.id, status = newStatus))
            makeApiRequest(url, "PATCH", taskJson)

            // Update UI optimistically
            _todoItems.value = _todoItems.value.map {
                if (it.id == item.id) {
                    item.copy(isCompleted = !item.isCompleted) // Create a new immutable TodoItem
                } else {
                    it
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating task completion", e)
        }
    }

    fun removeTodoItem(item: TodoItem) = viewModelScope.launch {
        if (_account.value == null) return@launch
        try {
            val currentTaskListId = taskListId ?: getTaskListId() ?: return@launch
            val url = "https://www.googleapis.com/tasks/v1/lists/$currentTaskListId/tasks/${item.id}"
            makeApiRequest(url, "DELETE")

            // Update UI
            _todoItems.value = _todoItems.value.filterNot { it.id == item.id }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting task", e)
        }
    }

    fun updateTodoItem(item: TodoItem) = viewModelScope.launch {
        if (_account.value == null || item.id == null) return@launch
        try {
            val currentTaskListId = taskListId ?: getTaskListId() ?: return@launch
            val status = if (item.isCompleted) "completed" else "needsAction"
            val url = "https://www.googleapis.com/tasks/v1/lists/$currentTaskListId/tasks/${item.id}"
            val taskJson = json.encodeToString(Task.serializer(), Task(
                id = item.id,
                title = item.text,
                notes = item.notes,
                status = status
            ))
            makeApiRequest(url, "PATCH", taskJson)

            // Optimistically update the local list
            _todoItems.value = _todoItems.value.map {
                if (it.id == item.id) item else it
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error updating task", e)
        }
    }

    fun addDetailedTodoItem(text: String, notes: String? = null, isCompleted: Boolean = false) = viewModelScope.launch {
        if (_account.value == null || text.isBlank()) return@launch
        _isLoading.value = true
        try {
            val currentTaskListId = taskListId ?: getTaskListId() ?: return@launch
            val status = if (isCompleted) "completed" else "needsAction"
            val taskJson = json.encodeToString(Task.serializer(), Task(title = text, notes = notes, status = status))
            makeApiRequest(urlString = "https://www.googleapis.com/tasks/v1/lists/$currentTaskListId/tasks", method = "POST", body = taskJson)
            loadTasks() // Refresh list
        } catch (e: Exception) {
            Log.e(TAG, "Error adding task", e)
            _isLoading.value = false
        }
    }

    fun addTodoItem(text: String) = addDetailedTodoItem(text, null, false)


    private suspend fun getTaskListId(): String? {
        if (taskListId != null) return taskListId

        val listName = appSettingsRepository.googleTodoListNameFlow.first()
        if (listName.isBlank()) {
             Log.w(TAG, "Google Todo List Name is blank. Using '@default'.")
             taskListId = "@default" // Cache the default
             return "@default"
        }
        return try {
            val url = "https://www.googleapis.com/tasks/v1/users/@me/lists"
            val response = makeApiRequest(url)
            val taskListsResponse = json.decodeFromString<TaskListsResponse>(response)
            val foundList = taskListsResponse.items.find { it.title == listName }
            taskListId = foundList?.id ?: taskListsResponse.items.firstOrNull()?.id // Fallback to first list
            taskListId
        } catch (e: Exception) {
            Log.e(TAG, "Error getting task lists", e)
            null
        }
    }

    private suspend fun makeApiRequest(urlString: String, method: String = "GET", body: String? = null): String = withContext(Dispatchers.IO) {
        val account = _account.value ?: throw IllegalStateException("User not signed in")
        val token = GoogleAuthUtil.getToken(getApplication(), account.account!!, "oauth2:$tasksScope")

        val url = URL(urlString)
        (url.openConnection() as HttpURLConnection).run {
            try {
                requestMethod = method
                setRequestProperty("Authorization", "Bearer $token")
                if (method == "POST" || method == "PUT" || method == "PATCH") {
                    setRequestProperty("Content-Type", "application/json")
                }

                if (body != null && (method == "POST" || method == "PUT" || method == "PATCH")) {
                    doOutput = true
                    OutputStreamWriter(outputStream).use { it.write(body) }
                }

                val responseCode = responseCode
                if (responseCode in 200..299) {
                    // For DELETE, there might be no content
                    if (responseCode == 204) "" else BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
                } else {
                    val error = BufferedReader(InputStreamReader(errorStream)).use { it.readText() }
                    throw Exception("HTTP Error: $responseCode - $error")
                }
            } finally {
                disconnect()
            }
        }
    }
    


    fun getTodoItemById(id: String): StateFlow<TodoItem?> {
        return todoItems.map { items ->
            items.find { it.id == id }
        }.stateIn(
            scope = viewModelScope,
            started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
            initialValue = null
        )
    }

    companion object {
        private const val TAG = "TodoViewModel"
    }
}

class TodoViewModelFactory(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TodoViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TodoViewModel(application, appSettingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}