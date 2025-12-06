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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

// Data classes for JSON parsing
@Serializable
data class TaskListsResponse(val items: List<TaskList> = emptyList())

@Serializable
data class TaskList(val id: String, val title: String)

@Serializable
data class TasksResponse(val items: List<Task>? = null)

@Serializable
data class Task(val id: String? = null, val title: String? = null, val status: String? = null)

class TodoViewModel(application: Application) : AndroidViewModel(application) {

    private val _todoItems = MutableStateFlow<List<TodoItem>>(emptyList())
    val todoItems: StateFlow<List<TodoItem>> = _todoItems.asStateFlow()

    private val _account = MutableStateFlow<GoogleSignInAccount?>(null)
    val account: StateFlow<GoogleSignInAccount?> = _account.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    val googleSignInClient: GoogleSignInClient

    private var fastrecTaskListId: String? = null

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
            fastrecTaskListId = null
        }
    }

    fun loadTasks() = viewModelScope.launch {
        if (_account.value == null) return@launch
        _isLoading.value = true
        try {
            val taskListId = getFastrecTaskListId()
            if (taskListId == null) {
                Log.e(TAG, "No task list found.")
                _todoItems.value = emptyList()
                return@launch
            }

            val url = "https://www.googleapis.com/tasks/v1/lists/$taskListId/tasks"
            val response = makeApiRequest(url)

            val tasksResponse = json.decodeFromString<TasksResponse>(response)
            _todoItems.value = tasksResponse.items?.mapNotNull { task ->
                task.id?.let {
                    TodoItem(
                        id = it,
                        text = task.title ?: "",
                        isCompleted = mutableStateOf(task.status == "completed")
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

    fun addTodoItem(text: String) = viewModelScope.launch {
        if (_account.value == null || text.isBlank()) return@launch
        _isLoading.value = true
        try {
            val taskListId = fastrecTaskListId ?: getFastrecTaskListId() ?: return@launch
            val url = "https://www.googleapis.com/tasks/v1/lists/$taskListId/tasks"
            val taskJson = json.encodeToString(Task.serializer(), Task(title = text, status = "needsAction"))
            makeApiRequest(url, "POST", taskJson)
            loadTasks() // Refresh list
        } catch (e: Exception) {
            Log.e(TAG, "Error adding task", e)
            _isLoading.value = false
        }
    }
    
    fun toggleTodoCompletion(item: TodoItem) = viewModelScope.launch {
        if (_account.value == null) return@launch
        try {
            val taskListId = fastrecTaskListId ?: getFastrecTaskListId() ?: return@launch
            val newStatus = if (item.isCompleted.value) "needsAction" else "completed"
            val url = "https://www.googleapis.com/tasks/v1/lists/$taskListId/tasks/${item.id}"
            val taskJson = json.encodeToString(Task.serializer(), Task(id = item.id, status = newStatus))
            makeApiRequest(url, "PATCH", taskJson)
            
            // Update UI optimistically
            val updatedItems = _todoItems.value.map {
                if (it.id == item.id) {
                    it.apply { isCompleted.value = !isCompleted.value }
                } else {
                    it
                }
            }
            _todoItems.value = updatedItems
        } catch (e: Exception) {
             Log.e(TAG, "Error updating task", e)
        }
    }

    fun removeTodoItem(item: TodoItem) = viewModelScope.launch {
        if (_account.value == null) return@launch
        try {
            val taskListId = fastrecTaskListId ?: getFastrecTaskListId() ?: return@launch
            val url = "https://www.googleapis.com/tasks/v1/lists/$taskListId/tasks/${item.id}"
            makeApiRequest(url, "DELETE")

            // Update UI
            _todoItems.value = _todoItems.value.filterNot { it.id == item.id }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting task", e)
        }
    }


    private suspend fun getFastrecTaskListId(): String? {
        if (fastrecTaskListId != null) return fastrecTaskListId

        return try {
            val url = "https://www.googleapis.com/tasks/v1/users/@me/lists"
            val response = makeApiRequest(url)
            val taskListsResponse = json.decodeFromString<TaskListsResponse>(response)
            val fastrecList = taskListsResponse.items.find { it.title == "fastrec" }
            fastrecTaskListId = fastrecList?.id ?: taskListsResponse.items.firstOrNull()?.id // Fallback to first list
            fastrecTaskListId
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
    
    fun updateTodoItemText(item: TodoItem, newText: String) { /* TODO */ }

    companion object {
        private const val TAG = "TodoViewModel"
    }
}

class TodoViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(TodoViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return TodoViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}