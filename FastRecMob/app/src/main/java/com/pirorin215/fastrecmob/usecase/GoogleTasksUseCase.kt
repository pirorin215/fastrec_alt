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
        logManager.addLog("Starting Google Tasks synchronization...")

        try {
            val currentLocalResults = transcriptionResultRepository.transcriptionResultsFlow.first().toMutableList()
            val successfullyDeletedLocalFiles = mutableSetOf<String>()
            val updatedLocalResultsAfterDeletionProcessing = mutableListOf<TranscriptionResult>()

            val softDeletedLocalResults = currentLocalResults.filter { it.isDeletedLocally && it.googleTaskId != null }
            val nonSoftDeletedLocalResults = currentLocalResults.filter { !it.isDeletedLocally || it.googleTaskId == null }

            for (softDeletedResult in softDeletedLocalResults) {
                if (softDeletedResult.googleTaskId != null) {
                    logManager.addLog("Attempting to delete Google Task '${softDeletedResult.googleTaskId}' for locally soft-deleted result '${softDeletedResult.fileName}'...")
                    try {
                        deleteGoogleTask(softDeletedResult.googleTaskId)
                        logManager.addLog("Successfully deleted Google Task '${softDeletedResult.googleTaskId}'. Permanently removing local result '${softDeletedResult.fileName}'.")
                        transcriptionResultRepository.permanentlyRemoveResult(softDeletedResult)
                        successfullyDeletedLocalFiles.add(softDeletedResult.fileName)

                        val audioFile = FileUtil.getAudioFile(context, audioDirName, softDeletedResult.fileName)
                        if (audioFile.exists()) {
                            if (audioFile.delete()) {
                                logManager.addLog("Deleted associated audio file: ${softDeletedResult.fileName}")
                            } else {
                                logManager.addLog("Failed to delete associated audio file: ${softDeletedResult.fileName}")
                            }
                        } else {
                            logManager.addLog("Associated audio file not found for soft-deleted result: ${softDeletedResult.fileName}")
                        }
                    } catch (e: Exception) {
                        logManager.addLog("Error deleting Google Task '${softDeletedResult.googleTaskId}' for local result '${softDeletedResult.fileName}': ${e.message}. Keeping local soft-deleted for retry.")
                        updatedLocalResultsAfterDeletionProcessing.add(softDeletedResult)
                    }
                }
            }
            updatedLocalResultsAfterDeletionProcessing.addAll(nonSoftDeletedLocalResults)

            val localTranscriptionResults = transcriptionResultRepository.transcriptionResultsFlow.first()
            val googleTasks = loadGoogleTasks()

            val filteredLocalTranscriptionResults = localTranscriptionResults.filter {
                !successfullyDeletedLocalFiles.contains(it.fileName)
            }

            val localMap: MutableMap<String, TranscriptionResult> =
                filteredLocalTranscriptionResults.associateBy { it.googleTaskId ?: it.fileName }.toMutableMap()
            val googleMap: Map<String, Task> = googleTasks.associateBy { it.id!! }

            val reconciledTranscriptionResults = mutableListOf<TranscriptionResult>()

            for (localResult in filteredLocalTranscriptionResults) {
                if (localResult.googleTaskId == null || !localResult.isSyncedWithGoogleTasks) {
                    val addedTask = addGoogleTask(
                        title = localResult.transcription,
                        notes = localResult.googleTaskNotes,
                        isCompleted = localResult.isCompleted
                    )
                    if (addedTask != null && addedTask.id != null) {
                        reconciledTranscriptionResults.add(
                            localResult.copy(
                                googleTaskId = addedTask.id,
                                isSyncedWithGoogleTasks = true
                            )
                        )
                        logManager.addLog("Added local result '${localResult.fileName}' to Google Tasks. New Google ID: ${addedTask.id}")
                    } else {
                        logManager.addLog("Failed to add local result '${localResult.fileName}' to Google Tasks.")
                        reconciledTranscriptionResults.add(localResult)
                    }
                } else {
                    val correspondingGoogleTask = googleMap[localResult.googleTaskId]
                    if (correspondingGoogleTask == null) {
                        logManager.addLog("Google Task '${localResult.googleTaskId}' for local result '${localResult.fileName}' deleted on Google. Permanently deleting local result and audio file.")
                        transcriptionResultRepository.permanentlyRemoveResult(localResult)

                        val audioFile = FileUtil.getAudioFile(context, audioDirName, localResult.fileName)
                        if (audioFile.exists()) {
                            if (audioFile.delete()) {
                                logManager.addLog("Deleted associated audio file: ${localResult.fileName}")
                            } else {
                                logManager.addLog("Failed to delete associated audio file: ${localResult.fileName}")
                            }
                        } else {
                            logManager.addLog("Associated audio file not found for remote-deleted result: ${localResult.fileName}")
                        }
                    } else {
                        val remoteNotesCleared = correspondingGoogleTask.notes.isNullOrEmpty()
                        val localNotesNotEmpty = !localResult.googleTaskNotes.isNullOrEmpty()

                        if (remoteNotesCleared && localNotesNotEmpty) {
                            // Remote notes were explicitly cleared, and local still has content.
                            // Pull this remote clear, regardless of timestamps.
                            logManager.addLog("Remote Google Task '${localResult.googleTaskId}' notes were cleared. Pulling this change to local result '${localResult.fileName}'.")
                            reconciledTranscriptionResults.add(
                                localResult.copy(
                                    googleTaskNotes = null, // Explicitly clear local
                                    isSyncedWithGoogleTasks = true
                                )
                            )
                        } else {
                            val googleTaskUpdateTimestamp = FileUtil.parseRfc3339Timestamp(correspondingGoogleTask.updated)
                            val localLastEditedTimestamp = localResult.lastEditedTimestamp

                            val localTranscriptionChanged = localResult.transcription != correspondingGoogleTask.title
                            val localCompletionChanged = localResult.isCompleted != (correspondingGoogleTask.status == "completed")
                            val normalizedLocalNotes = localResult.googleTaskNotes.takeIf { !it.isNullOrEmpty() }
                            val normalizedRemoteNotes = correspondingGoogleTask.notes.takeIf { !it.isNullOrEmpty() }
                            val localNotesChanged = normalizedLocalNotes != normalizedRemoteNotes

                            if (localTranscriptionChanged || localCompletionChanged || localNotesChanged) {
                                // Local result has changes, push to Google Tasks
                                logManager.addLog("Local result '${localResult.fileName}' has changes. Updating Google Task '${localResult.googleTaskId}'.")
                                val updatedTask = updateGoogleTask(
                                    taskId = localResult.googleTaskId,
                                    title = localResult.transcription,
                                    notes = if (localResult.googleTaskNotes.isNullOrEmpty()) "" else localResult.googleTaskNotes,
                                    isCompleted = localResult.isCompleted
                                )

                                if (updatedTask != null) {
                                    reconciledTranscriptionResults.add(
                                        localResult.copy(
                                            isSyncedWithGoogleTasks = true
                                        )
                                    )
                                } else {
                                    logManager.addLog("Failed to update Google Task for local result '${localResult.fileName}'.")
                                    reconciledTranscriptionResults.add(localResult)
                                }
                            } else if (googleTaskUpdateTimestamp > localLastEditedTimestamp) {
                                // No local changes, but Google Task is newer. Pull from Google.
                                logManager.addLog("Google Task '${localResult.googleTaskId}' is newer. Pulling changes to local result '${localResult.fileName}'.")
                                reconciledTranscriptionResults.add(
                                    localResult.copy(
                                        transcription = correspondingGoogleTask.title ?: localResult.transcription,
                                        googleTaskNotes = correspondingGoogleTask.notes,
                                        isCompleted = (correspondingGoogleTask.status == "completed"),
                                        isSyncedWithGoogleTasks = true
                                    )
                                )
                            } else {
                                // No local changes, Google Task is not newer (or same age). Consider in sync.
                                reconciledTranscriptionResults.add(
                                    localResult.copy(
                                        isSyncedWithGoogleTasks = true
                                    )
                                )
                            }
                        }
                    }
                }
            }

            for (googleTask in googleTasks) {
                if (!reconciledTranscriptionResults.any { it.googleTaskId == googleTask.id }) {
                    logManager.addLog("New Google Task '${googleTask.title}' (ID: ${googleTask.id}) found. Adding to local results.")
                    val newLocalResult = TranscriptionResult(
                        fileName = "GT_${googleTask.id}_${System.currentTimeMillis()}.txt",
                        transcription = googleTask.title ?: "",
                        lastEditedTimestamp = System.currentTimeMillis(),
                        locationData = null,
                        googleTaskId = googleTask.id,
                        isCompleted = (googleTask.status == "completed"),
                        isSyncedWithGoogleTasks = true
                    )
                    reconciledTranscriptionResults.add(newLocalResult)
                }
            }

            transcriptionResultRepository.updateResults(reconciledTranscriptionResults.distinctBy { it.fileName })

            logManager.addLog("Google Tasks synchronization completed.")

        } catch (e: Exception) {
            logManager.addLog("Error during Google Tasks synchronization: ${e.message}")
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
