package com.pirorin215.fastrecmob.usecase

import android.app.Application
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.pirorin215.fastrecmob.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

import kotlinx.coroutines.CoroutineScope
import com.pirorin215.fastrecmob.viewModel.LogManager

class GoogleTasksUseCase(
    private val application: Application,
    private val appSettingsRepository: AppSettingsRepository,
    private val transcriptionResultRepository: TranscriptionResultRepository,
    private val context: Context,
    private val scope: CoroutineScope, // Added CoroutineScope
    private val logManager: LogManager // Added LogManager
) {
    private val _account = MutableStateFlow<GoogleSignInAccount?>(null)
    val account: StateFlow<GoogleSignInAccount?> = _account.asStateFlow()

    private val _isLoadingGoogleTasks = MutableStateFlow(false)
    val isLoadingGoogleTasks: StateFlow<Boolean> = _isLoadingGoogleTasks.asStateFlow()

    val googleSignInClient: GoogleSignInClient

    private var taskListId: String? = null
    private val tasksScope = "https://www.googleapis.com/auth/tasks"

    private val json = Json { ignoreUnknownKeys = true }

    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(tasksScope))
            .build()
        googleSignInClient = GoogleSignIn.getClient(application, gso)

        _account.value = GoogleSignIn.getLastSignedInAccount(application)
        if (_account.value != null) {
            logManager.addLog("Signed in to Google Tasks: ${_account.value?.displayName}")
        }

        // Observe changes to the Google Todo list name and clear the cached taskListId
        appSettingsRepository.googleTodoListNameFlow.onEach {
            taskListId = null // Clear cached taskListId so it's re-fetched
            logManager.addLog("Google Todo list name changed. Cleared cached taskListId.")
        }.launchIn(scope)
    }

    private suspend fun makeApiRequest(urlString: String, method: String = "GET", body: String? = null): String = withContext(Dispatchers.IO) {
        val account = _account.value ?: throw IllegalStateException("User not signed in for Google Tasks API.")
        val token = GoogleAuthUtil.getToken(application, account.account!!, "oauth2:$tasksScope")

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

    private suspend fun createGoogleTaskList(listName: String): String? {
        if (_account.value == null || listName.isBlank()) return null
        val taskListJson = json.encodeToString(TaskList.serializer(), TaskList(id = "", title = listName)) // ID is ignored for creation
        return try {
            val response = makeApiRequest(urlString = "https://www.googleapis.com/tasks/v1/users/@me/lists", method = "POST", body = taskListJson)
            val newTaskList = json.decodeFromString<TaskList>(response)
            logManager.addLog("Created new Google Task List: '${newTaskList.title}' (ID: ${newTaskList.id})")
            newTaskList.id
        } catch (e: Exception) {
            logManager.addLog("Error creating Google Task List '$listName': ${e.message}")
            null
        }
    }

    private suspend fun getTaskListId(): String? {
        if (taskListId != null) return taskListId

        val listName = appSettingsRepository.googleTodoListNameFlow.first()
        if (listName.isBlank()) {
            logManager.addLog("Google Todo List Name is blank. Using '@default'.")
            taskListId = "@default" // Cache the default
            return "@default"
        }
        return try {
            val url = "https://www.googleapis.com/tasks/v1/users/@me/lists"
            val response = makeApiRequest(url)
            val taskListsResponse = json.decodeFromString<TaskListsResponse>(response)
            var foundList = taskListsResponse.items.find { it.title == listName }

            if (foundList == null && listName != "@default") {
                logManager.addLog("Google Task List '$listName' not found. Attempting to create it.")
                val newTaskListId = createGoogleTaskList(listName)
                if (newTaskListId != null) {
                    // Refetch the list to get the newly created one, or assume the newTaskListId is enough
                    // For simplicity, we'll just use the newTaskListId directly and update taskListId
                    taskListId = newTaskListId
                    return newTaskListId
                } else {
                    logManager.addLog("Failed to create Google Task List '$listName'. Falling back to default or first available.")
                }
            }

            taskListId = foundList?.id ?: taskListsResponse.items.firstOrNull()?.id // Fallback to first list if user-defined not found/created
            taskListId
        } catch (e: Exception) {
            logManager.addLog("Error getting or creating task lists: ${e.message}")
            null
        }
    }

    private suspend fun addGoogleTask(title: String, notes: String?, isCompleted: Boolean): Task? {
        if (_account.value == null || title.isBlank()) return null
        val currentTaskListId = taskListId ?: getTaskListId() ?: return null
        val status = if (isCompleted) "completed" else "needsAction"
        val taskJson = json.encodeToString(Task.serializer(), Task(title = title, notes = notes, status = status))
        return try {
            val response = makeApiRequest(urlString = "https://www.googleapis.com/tasks/v1/lists/$currentTaskListId/tasks", method = "POST", body = taskJson)
            logManager.addLog("Added new Google Task: $title")
            json.decodeFromString<Task>(response)
        } catch (e: Exception) {
            logManager.addLog("Error adding Google Task '$title': ${e.message}")
            null
        }
    }

    private suspend fun updateGoogleTask(taskId: String, title: String, notes: String?, isCompleted: Boolean): Task? {
        if (_account.value == null || taskId.isBlank()) return null
        val currentTaskListId = taskListId ?: getTaskListId() ?: return null
        val status = if (isCompleted) "completed" else "needsAction"
        val taskJson = json.encodeToString(Task.serializer(), Task(id = taskId, title = title, notes = notes, status = status))
        return try {
            val response = makeApiRequest(urlString = "https://www.googleapis.com/tasks/v1/lists/$currentTaskListId/tasks/$taskId", method = "PATCH", body = taskJson)
            logManager.addLog("Updated Google Task: $title (ID: $taskId)")
            json.decodeFromString<Task>(response)
        } catch (e: Exception) {
            logManager.addLog("Error updating Google Task '$title' (ID: $taskId): ${e.message}")
            null
        }
    }

    private suspend fun deleteGoogleTask(taskId: String) {
        if (_account.value == null || taskId.isBlank()) return
        val currentTaskListId = taskListId ?: getTaskListId() ?: return
        try {
            makeApiRequest(urlString = "https://www.googleapis.com/tasks/v1/lists/$currentTaskListId/tasks/$taskId", method = "DELETE")
            logManager.addLog("Deleted Google Task ID: $taskId")
        } catch (e: Exception) {
            logManager.addLog("Error deleting Google Task ID '$taskId': ${e.message}")
        }
    }

    suspend fun moveTask(taskId: String, previousTaskId: String?): Task? {
        if (_account.value == null || taskId.isBlank()) return null
        val currentTaskListId = taskListId ?: getTaskListId() ?: return null
        val url = "https://www.googleapis.com/tasks/v1/lists/$currentTaskListId/tasks/$taskId/move" +
                (previousTaskId?.let { "?previous=$it" } ?: "")
        return try {
            val response = makeApiRequest(urlString = url, method = "POST")
            logManager.addLog("Moved Google Task ID: $taskId after $previousTaskId")
            json.decodeFromString<Task>(response)
        } catch (e: Exception) {
            logManager.addLog("Error moving Google Task ID '$taskId': ${e.message}")
            null
        }
    }

    private suspend fun loadGoogleTasks(): List<Task> {
        if (_account.value == null) {
            logManager.addLog("Not signed in to Google. Cannot load tasks.")
            return emptyList()
        }
        _isLoadingGoogleTasks.value = true
        try {
            val currentTaskListId = taskListId ?: getTaskListId()
            if (currentTaskListId == null) {
                logManager.addLog("No Google task list found. Cannot load tasks.")
                return emptyList()
            }

            val url = "https://www.googleapis.com/tasks/v1/lists/$currentTaskListId/tasks"
            val response = makeApiRequest(url)

            val tasksResponse = json.decodeFromString<TasksResponse>(response)
            return tasksResponse.items ?: emptyList()

        } catch (e: Exception) {
            logManager.addLog("Error loading Google tasks: ${e.message}")
            return emptyList()
        } finally {
            _isLoadingGoogleTasks.value = false
        }
    }

    suspend fun syncTranscriptionResultsWithGoogleTasks(audioDirName: String) {
        if (_account.value == null) {
            logManager.addLog("Not signed in to Google. Cannot sync tasks.")
            return
        }
        _isLoadingGoogleTasks.value = true
        logManager.addLog("Starting Google Tasks synchronization (one-way)...")

        try {
            val localResults = transcriptionResultRepository.transcriptionResultsFlow.first()
            val updatedResults = mutableListOf<TranscriptionResult>()

            for (localResult in localResults) {
                // Skip locally deleted items, do not sync deletion to Google Tasks
                if (localResult.isDeletedLocally) {
                    updatedResults.add(localResult)
                    continue
                }

                if (localResult.googleTaskId == null) {
                    // This is a new item, add it to Google Tasks
                    logManager.addLog("Local result '${localResult.fileName}' has no Google Task ID. Adding to Google Tasks.")
                    val addedTask = addGoogleTask(
                        title = localResult.transcription,
                        notes = localResult.googleTaskNotes,
                        isCompleted = localResult.isCompleted
                    )
                    if (addedTask != null && addedTask.id != null) {
                        updatedResults.add(
                            localResult.copy(
                                googleTaskId = addedTask.id,
                                isSyncedWithGoogleTasks = true,
                                googleTaskUpdated = addedTask.updated,
                                lastEditedTimestamp = FileUtil.parseRfc3339Timestamp(addedTask.updated)
                            )
                        )
                        logManager.addLog("Added local result '${localResult.fileName}' to Google Tasks. New Google ID: ${addedTask.id}")
                    } else {
                        logManager.addLog("Failed to add local result '${localResult.fileName}' to Google Tasks. Will retry on next sync.")
                        updatedResults.add(localResult.copy(isSyncedWithGoogleTasks = false))
                    }
                } else {
                    // This item already exists on Google Tasks, check if it needs an update.
                    // The condition `!localResult.isSyncedWithGoogleTasks` can be a trigger for updates.
                    // This covers cases like "transcription failed" and then "retry transcription" which updates the content.
                    val needsUpdate = !localResult.isSyncedWithGoogleTasks ||
                            localResult.transcription.isNotEmpty() // More specific conditions can be added if needed.

                    if (needsUpdate) {
                         logManager.addLog("Local result '${localResult.fileName}' needs update on Google Tasks. Pushing changes.")
                        val updatedTask = updateGoogleTask(
                            taskId = localResult.googleTaskId,
                            title = localResult.transcription,
                            notes = localResult.googleTaskNotes,
                            isCompleted = localResult.isCompleted
                        )
                         if (updatedTask != null) {
                            updatedResults.add(
                                localResult.copy(
                                    isSyncedWithGoogleTasks = true,
                                    googleTaskUpdated = updatedTask.updated,
                                    lastEditedTimestamp = FileUtil.parseRfc3339Timestamp(updatedTask.updated)
                                )
                            )
                             logManager.addLog("Successfully updated Google Task for '${localResult.fileName}'.")
                        } else {
                             logManager.addLog("Failed to update Google Task for '${localResult.fileName}'. Will retry on next sync.")
                            updatedResults.add(localResult.copy(isSyncedWithGoogleTasks = false))
                        }
                    } else {
                        // No changes needed for this item
                        updatedResults.add(localResult)
                    }
                }
            }

            // Update the local database with the new state
            transcriptionResultRepository.updateResults(updatedResults)

            logManager.addLog("Google Tasks one-way synchronization completed.")

        } catch (e: Exception) {
            logManager.addLog("Error during Google Tasks one-way synchronization: ${e.message}")
        } finally {
            _isLoadingGoogleTasks.value = false
        }
    }

    fun handleSignInResult(intent: Intent, onSuccess: () -> Unit, onFailure: (Exception) -> Unit) {
        try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(intent)
            _account.value = task.getResult(ApiException::class.java)
            // viewModelScope is not available here. A scope should be passed or use GlobalScope.
            // For now, let's assume the caller will handle the coroutine scope.
            logManager.addLog("Google Sign-In successful for: ${_account.value?.displayName}")
            onSuccess()
        } catch (e: ApiException) {
            logManager.addLog("Google Sign-In failed: ${e.statusCode} - ${e.message}")
            onFailure(e)
        }
    }

    fun signOut() {
        googleSignInClient.signOut().addOnCompleteListener {
            _account.value = null
            taskListId = null
            logManager.addLog("Signed out from Google Tasks.")
        }
    }


}
